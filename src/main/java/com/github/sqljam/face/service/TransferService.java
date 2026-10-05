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

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.ImportExportHandler;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.impexp.ScriptExporter;
import com.github.sqljam.impexp.ScriptImporter;
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
    }

    private void exportScripts(TransferRequest request, ExportListener listener) throws Exception {
        if (request.getOutputDirectory() == null) {
            throw new IllegalArgumentException("Output directory must be required.");
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
        importConfiguration.setConnectionPoolEnabled(true);
        importExporter.setIdentifierCase(request.getIdentifierCase());
        importExporter.setExportListener(listener);
        importExporter.export(request.getExportMode());
    }

    /**
     * Imports a directory exported as sql scripts into the target database
     */
    public ScriptImporter importScripts(ConnectionProfile target, String catalog, String schema, File directory,
                                        boolean stopOnError, ExportListener listener) throws Exception {
        String url = target.getJdbcUrl(StringUtils.defaultIfBlank(catalog, null));
        try (Connection connection = StringUtils.isBlank(target.getUsername()) ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, target.getUsername(), target.getPassword())) {
            if (StringUtils.isNotBlank(schema) && target.getDbType().isCanSetSchema()) {
                connection.setSchema(schema);
            }
            if (StringUtils.isNotBlank(catalog) && target.getDbType() == DbType.MYSQL) {
                connection.setCatalog(catalog);
            }
            ScriptImporter importer = new ScriptImporter(connection, target.getDbType());
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
