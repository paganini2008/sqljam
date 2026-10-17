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
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.CsvImporter;
import com.github.sqljam.impexp.CsvOptions;
import com.github.sqljam.impexp.DataFileImporter;
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
import com.github.sqljam.jdbc.ManualTransaction;
import com.github.sqljam.jdbc.SharedConnectionFactory;

/**
 * @Description: TransferService runs exports to sql scripts, imports into other databases and imports of script
 *               directories
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class TransferService {

    public void transfer(TransferRequest request, ExportListener listener) throws Exception {
        transfer(request, null, listener);
    }

    /**
     * Exports into files, or imports into the target database, in the transaction if it is given
     *
     * @param transaction transaction of the target database opened by {@link #beginTransaction}, null to commit
     *                    page by page
     */
    public void transfer(TransferRequest request, ManualTransaction transaction, ExportListener listener)
            throws Exception {
        if (request.getTarget() == TransferRequest.Target.SCRIPT) {
            exportScripts(request, listener);
        } else {
            importDatabase(request, transaction, listener);
        }
    }

    /**
     * Opens a transaction of the target database for an import which the user commits or rolls back
     */
    public ManualTransaction beginTransaction(ConnectionProfile target, String catalog, String schema)
            throws SQLException {
        if (!target.getDbType().isTransactionSupported()) {
            throw new IllegalArgumentException(target.getDbType().getDisplayName() + " has no transactions.");
        }
        Connection connection = connect(target, catalog, schema);
        try {
            return new ManualTransaction(connection, target.getDbType().isTransactionalDdl());
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    /**
     * Connection of the target database in the catalog and schema
     */
    private static Connection connect(ConnectionProfile target, String catalog, String schema) throws SQLException {
        String url = target.getJdbcUrl(StringUtils.defaultIfBlank(catalog, null));
        Connection connection = StringUtils.isBlank(target.getUsername()) ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, target.getUsername(), target.getPassword());
        try {
            if (StringUtils.isNotBlank(schema) && target.getDbType().isCanSetSchema()) {
                connection.setSchema(schema);
            }
            if (StringUtils.isNotBlank(catalog) && (target.getDbType() == DbType.MYSQL
                    || target.getDbType() == DbType.MARIADB)) {
                connection.setCatalog(catalog);
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            throw e;
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
        configuration.setQueries(new LinkedHashMap<>(request.getQueries()));
        configuration.setWriteMode(request.getWriteMode());
        configuration.setKeyColumnNames(new HashMap<>(request.getKeyColumnNames()));
    }

    private void exportScripts(TransferRequest request, ExportListener listener) throws Exception {
        if (request.getOutputDirectory() == null) {
            throw new IllegalArgumentException("Output directory must be required.");
        }
        if (request.getDataFormat() == DataFormat.PARQUET) {
            exportParquet(request, listener);
            return;
        }
        if (request.getDataFormat() == DataFormat.CSV) {
            exportCsv(request, listener);
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
        parquetExporter.setMaxFileSize(request.getMaxFileSize());
        int[] version = parseVersion(request.getScriptDbVersion());
        if (version != null) {
            parquetExporter.setTargetVersion(version[0], version[1]);
        }
        parquetExporter.setExportListener(listener);
        parquetExporter.export(request.getExportMode());
    }

    /**
     * Export package with CSV files: schema.sql and constraints.sql for the target database type, the types of
     * the columns are kept in manifest.json
     */
    private void exportCsv(TransferRequest request, ExportListener listener) throws Exception {
        CsvExporter csvExporter = new CsvExporter(request.getOutputDirectory());
        configureSource(csvExporter.getConfiguration(), request);
        csvExporter.setTargetDbType(request.getScriptDbType());
        csvExporter.setTargetSchemaName(request.getScriptSchema());
        csvExporter.setIdentifierCase(request.getIdentifierCase());
        csvExporter.setOptions(request.getCsvOptions());
        csvExporter.setMaxFileSize(request.getMaxFileSize());
        int[] version = parseVersion(request.getScriptDbVersion());
        if (version != null) {
            csvExporter.setTargetVersion(version[0], version[1]);
        }
        csvExporter.setExportListener(listener);
        csvExporter.export(request.getExportMode());
    }

    /**
     * Imports Parquet files of other tools into a table: a new table created from the Parquet schema, or rows
     * appended to an existing table
     */
    public DataFileImporter importParquet(ConnectionProfile target, String catalog, String schema, List<File> files,
                                          String tableName, DataFileImporter.Mode mode, ExportListener listener)
            throws Exception {
        return importFiles(target, catalog, schema, files, tableName, mode, null, listener);
    }

    /**
     * Imports Parquet or CSV files of other tools into a table (CSV when all files are .csv, .tsv or .txt): a new
     * table created from the columns of the files, or rows appended to an existing table
     *
     * @param transaction transaction opened by {@link #beginTransaction}, null to commit page by page
     */
    public DataFileImporter importFiles(ConnectionProfile target, String catalog, String schema, List<File> files,
                                        String tableName, DataFileImporter.Mode mode, ManualTransaction transaction,
                                        ExportListener listener) throws Exception {
        return importFiles(target, catalog, schema, files, tableName, mode, Collections.emptyList(), null, false, 0,
                transaction, listener);
    }

    /**
     * Imports Parquet or CSV files of other tools into a table, rows are matched by the key columns
     *
     * @param keyColumns key columns of upserts and skips, empty for the primary key of the table
     * @param csvOptions options of CSV files, null to detect them
     * @param skipErrors whether rows with errors are skipped, otherwise the first error stops the import
     * @param commitRows rows per commit without transaction, 0 for the default page size
     */
    public DataFileImporter importFiles(ConnectionProfile target, String catalog, String schema, List<File> files,
                                        String tableName, DataFileImporter.Mode mode, List<String> keyColumns,
                                        CsvOptions csvOptions, boolean skipErrors, int commitRows,
                                        ManualTransaction transaction, ExportListener listener) throws Exception {
        Connection connection = transaction != null ? transaction.getConnection() : connect(target, catalog, schema);
        try {
            DataFileImporter importer;
            if (files.stream().allMatch(DuckDBWorkspace::isCsv)) {
                CsvImporter csvImporter = new CsvImporter(connection, target.getDbType());
                csvImporter.setOptions(csvOptions);
                importer = csvImporter;
            } else {
                importer = new ParquetImporter(connection, target.getDbType());
            }
            if (StringUtils.isNotBlank(schema) && target.getDbType().isSchemaSupported()) {
                importer.setTargetSchemaName(schema);
            }
            importer.setPageSize(commitRows > 0 ? commitRows : Config.getInstance().getInt("sqljam.export.page-size",
                    Exporter.DEFAULT_PAGE_SIZE));
            importer.setKeyColumns(keyColumns);
            importer.setStopOnError(!skipErrors);
            importer.setExportListener(listener);
            importer.importFiles(files, tableName, mode);
            return importer;
        } finally {
            if (transaction == null) {
                closeQuietly(connection);
            }
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
        return previewParquet(files, rowLimit, null);
    }

    /**
     * Columns, rows and first rows of Parquet or CSV files, CSV files are read with the options (null to detect)
     */
    public ParquetPreview previewParquet(List<File> files, int rowLimit, CsvOptions csvOptions) throws Exception {
        try (DuckDBWorkspace workspace = new DuckDBWorkspace(null)) {
            List<List<String>> firstRows = rowLimit > 0 ? workspace.readRows(files, rowLimit, csvOptions)
                    : Collections.emptyList();
            return new ParquetPreview(workspace.describeFiles(files, csvOptions), workspace.countFileRows(files,
                    csvOptions), firstRows);
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

    private void importDatabase(TransferRequest request, ManualTransaction transaction, ExportListener listener)
            throws Exception {
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
        importConfiguration.setConnectionPoolEnabled(transaction == null);
        if (transaction != null) {
            // All pages and tables in the one transaction
            importExporter.setTargetConnectionFactory(new SharedConnectionFactory(transaction.getConnection()));
        }
        importExporter.setIdentifierCase(request.getIdentifierCase());
        importExporter.setExportListener(listener);
        importExporter.export(request.getExportMode());
    }

    /**
     * Imports an export package (sql scripts, Parquet or CSV files) into the target database
     */
    public AbstractFileImporter importScripts(ConnectionProfile target, String catalog, String schema, File directory,
                                              boolean stopOnError, ExportListener listener) throws Exception {
        return importScripts(target, catalog, schema, directory, stopOnError, 0, null, listener);
    }

    /**
     * Imports an export package into the target database, in the transaction if it is given
     *
     * @param commitRows  rows (statements of scripts) per commit without transaction, 0 for the default batch
     *                    and page sizes
     * @param transaction transaction opened by {@link #beginTransaction}, null to commit batch by batch
     */
    public AbstractFileImporter importScripts(ConnectionProfile target, String catalog, String schema, File directory,
                                              boolean stopOnError, int commitRows, ManualTransaction transaction,
                                              ExportListener listener) throws Exception {
        Connection connection = transaction != null ? transaction.getConnection() : connect(target, catalog, schema);
        try {
            AbstractFileImporter importer = AbstractFileImporter.forDirectory(directory, connection,
                    target.getDbType());
            importer.setStopOnError(stopOnError);
            importer.setBatchSize(commitRows > 0 ? commitRows : Config.getInstance().getInt(
                    "sqljam.import.batch-size", SqlScriptRunner.DEFAULT_BATCH_SIZE));
            // Data files are committed page by page
            if (commitRows > 0 && importer instanceof DataFileImporter) {
                ((DataFileImporter) importer).setPageSize(commitRows);
            }
            importer.setExportListener(listener);
            importer.importDirectory(directory);
            return importer;
        } finally {
            if (transaction == null) {
                closeQuietly(connection);
            }
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
