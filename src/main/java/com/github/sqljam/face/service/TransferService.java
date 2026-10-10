/*
 * Copyright 2023-2026 Fred Feng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.sqljam.face.service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.DuckDBWorkspace;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.ImportExportHandler;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.impexp.ParquetExporter;
import com.github.sqljam.impexp.ParquetImporter;
import com.github.sqljam.impexp.ScriptExporter;
import com.github.sqljam.impexp.SqlScriptRunner;

/**
 * @Description: TransferService runs exports to sql scripts, imports into other databases and imports of script
 *               directories
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class TransferService {

    public void transfer(TransferRequest request, ExportListener listener) throws Exception {
        if (request.getTarget() == TransferRequest.Target.SCRIPT) {
            exportScripts(request, listener);
        } else {
            importDatabase(request, listener);
        }
    }

    private void configureSource(Exporter.ExportConfiguration configuration, TransferRequest request) {
        DdlGenerator.configure(configuration, request.getSource(), request.getSourceCatalog(),
                request.getSourceSchema());
        if (!request.getTables().isEmpty()) {
            configuration.setIncludedTableNames(request.getTables().toArray(new String[0]));
        }
        configuration.setTableRecreated(request.isTableRecreated());
        configuration.setIdReused(request.isIdReused());
        configuration.setIndexIncluded(request.isIndexIncluded());
        configuration.setForeignKeyIncluded(request.isForeignKeyIncluded());
        configuration.setCommentIncluded(request.isCommentIncluded());
        configuration.setSequenceIncluded(request.isSequenceIncluded());
        configuration.setFailFast(request.isFailFast());
        configuration.setPageSize(request.getPageSize());
        configuration.setLobPageSize(request.getLobPageSize());
        configuration.setConnectionPoolEnabled(true);
        configuration.setTableQueries(new HashMap<>(request.getTableQueries()));
    }

    private void exportScripts(TransferRequest request, ExportListener listener) throws Exception {
        if (request.getOutputDirectory() == null) {
            throw new IllegalArgumentException("Output directory must be required.");
        }
        if (request.getDataFormat() == DataFormat.PARQUET) {
            exportParquet(request, listener);
            return;
        }
        ScriptExporter scriptExporter = new ScriptExporter(request.getOutputDirectory(),
                request.getDataFileStrategy(), request.getMaxFileSize());
        configureSource(scriptExporter.getConfiguration(), request);
        scriptExporter.setTargetDbType(request.getScriptDbType());
        scriptExporter.setTargetSchemaName(request.getScriptSchema());
        scriptExporter.setIdentifierCase(request.getIdentifierCase());
        scriptExporter.setLobSeparated(request.isLobSeparated());
        int[] version = parseVersion(request.getScriptDbVersion());
        if (version != null) {
            scriptExporter.setTargetVersion(version[0], version[1]);
        }
        scriptExporter.setExportListener(listener);
        scriptExporter.export(request.getExportMode());
    }

    /**
     * Export package with Parquet files: schema.sql and constraints.sql for the target database type
     */
    private void exportParquet(TransferRequest request, ExportListener listener) throws Exception {
        ParquetExporter parquetExporter = new ParquetExporter(request.getOutputDirectory());
        configureSource(parquetExporter.getConfiguration(), request);
        parquetExporter.setTargetDbType(request.getScriptDbType());
        parquetExporter.setTargetSchemaName(request.getScriptSchema());
        parquetExporter.setIdentifierCase(request.getIdentifierCase());
        parquetExporter.setCompression(request.getCompression());
        int[] version = parseVersion(request.getScriptDbVersion());
        if (version != null) {
            parquetExporter.setTargetVersion(version[0], version[1]);
        }
        parquetExporter.setExportListener(listener);
        parquetExporter.export(request.getExportMode());
    }

    /**
     * Imports Parquet files of other tools into a table: a new table created from the Parquet schema, or rows
     * appended to an existing table
     */
    public ParquetImporter importParquet(ConnectionProfile target, String catalog, String schema, List<File> files,
                                         String tableName, ParquetImporter.Mode mode, ExportListener listener)
            throws Exception {
        String url = target.getJdbcUrl(StringUtils.defaultIfBlank(catalog, null));
        try (Connection connection = StringUtils.isBlank(target.getUsername()) ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, target.getUsername(), target.getPassword())) {
            if (StringUtils.isNotBlank(schema) && target.getDbType().isCanSetSchema()) {
                connection.setSchema(schema);
            }
            if (StringUtils.isNotBlank(catalog) && (target.getDbType() == DbType.MYSQL
                    || target.getDbType() == DbType.MARIADB)) {
                connection.setCatalog(catalog);
            }
            ParquetImporter importer = new ParquetImporter(connection, target.getDbType());
            if (StringUtils.isNotBlank(schema) && target.getDbType().isSchemaSupported()) {
                importer.setTargetSchemaName(schema);
            }
            importer.setPageSize(Config.getInstance().getInt("sqljam.export.page-size", Exporter.DEFAULT_PAGE_SIZE));
            importer.setExportListener(listener);
            importer.importFiles(files, tableName, mode);
            return importer;
        }
    }

    /**
     * Columns (name and type) and rows of Parquet files, shown before importing
     */
    public ParquetPreview previewParquet(List<File> files) throws Exception {
        return previewParquet(files, 0);
    }

    /**
     * Columns, rows and the first rows (values as text) of Parquet files
     */
    public ParquetPreview previewParquet(List<File> files, int rowLimit) throws Exception {
        try (DuckDBWorkspace workspace = new DuckDBWorkspace(null)) {
            List<List<String>> firstRows = rowLimit > 0 ? workspace.readParquetRows(files, rowLimit)
                    : Collections.emptyList();
            return new ParquetPreview(workspace.describeParquet(files), workspace.countParquetRows(files), firstRows);
        }
    }

    /**
     * Columns and rows of Parquet files
     */
    public static class ParquetPreview {

        private final List<String[]> columns;
        private final long rows;
        private final List<List<String>> firstRows;

        public ParquetPreview(List<String[]> columns, long rows, List<List<String>> firstRows) {
            this.columns = columns;
            this.rows = rows;
            this.firstRows = firstRows;
        }

        /**
         * First rows of the files, values as text
         */
        public List<List<String>> getFirstRows() {
            return firstRows;
        }

        /**
         * Name and DuckDB type of each column
         */
        public List<String[]> getColumns() {
            return columns;
        }

        public long getRows() {
            return rows;
        }
    }

    private void importDatabase(TransferRequest request, ExportListener listener) throws Exception {
        ConnectionProfile target = request.getTargetProfile();
        if (target == null) {
            throw new IllegalArgumentException("Target database must be required.");
        }
        ImportExporter importExporter = new ImportExporter();
        configureSource(importExporter.getExportConfiguration(), request);
        ImportExportHandler.ImportConfiguration importConfiguration = importExporter.getImportConfiguration();
        importConfiguration.setDbType(target.getDbType());
        importConfiguration.setUrl(target.getJdbcUrl(StringUtils.defaultIfBlank(request.getTargetCatalog(), null)));
        importConfiguration.setUsername(target.getUsername());
        importConfiguration.setPassword(target.getPassword());
        importConfiguration.setTargetCatalogName(request.getTargetCatalog());
        importConfiguration.setTargetSchemaName(request.getTargetSchema());
        importConfiguration.setTargetSchemaCreated(request.isTargetSchemaCreated());
        importConfiguration.setTableNamePattern(request.getTableNamePattern());
        importConfiguration.setConnectionPoolEnabled(true);
        importExporter.setIdentifierCase(request.getIdentifierCase());
        importExporter.setExportListener(listener);
        importExporter.export(request.getExportMode());
    }

    /**
     * Imports an export package (sql scripts or Parquet files) into the target database
     */
    public AbstractFileImporter importScripts(ConnectionProfile target, String catalog, String schema, File directory,
                                        boolean stopOnError, ExportListener listener) throws Exception {
        String url = target.getJdbcUrl(StringUtils.defaultIfBlank(catalog, null));
        try (Connection connection = StringUtils.isBlank(target.getUsername()) ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, target.getUsername(), target.getPassword())) {
            if (StringUtils.isNotBlank(schema) && target.getDbType().isCanSetSchema()) {
                connection.setSchema(schema);
            }
            if (StringUtils.isNotBlank(catalog) && (target.getDbType() == DbType.MYSQL
                    || target.getDbType() == DbType.MARIADB)) {
                connection.setCatalog(catalog);
            }
            AbstractFileImporter importer = AbstractFileImporter.forDirectory(directory, connection,
                    target.getDbType());
            importer.setStopOnError(stopOnError);
            importer.setBatchSize(Config.getInstance().getInt("sqljam.import.batch-size",
                    SqlScriptRunner.DEFAULT_BATCH_SIZE));
            importer.setExportListener(listener);
            importer.importDirectory(directory);
            return importer;
        }
    }

    /**
     * Parses version text, e.g. "11.2" or "2008" (SQL Server product year)
     */
    static int[] parseVersion(String version) {
        if (StringUtils.isBlank(version)) {
            return null;
        }
        String text = version.trim();
        switch (text) {
            case "2008":
                return new int[]{10, 0};
            case "2012":
                return new int[]{11, 0};
            case "2014":
                return new int[]{12, 0};
            case "2016":
                return new int[]{13, 0};
            case "2017":
                return new int[]{14, 0};
            case "2019":
                return new int[]{15, 0};
            case "2022":
                return new int[]{16, 0};
            default:
                break;
        }
        String[] parts = text.split("\\.");
        try {
            return new int[]{Integer.parseInt(parts[0].replaceAll("\\D", "")),
                    parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("\\D", "")) : 0};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid database version: " + version);
        }
    }

    static void closeQuietly(Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
