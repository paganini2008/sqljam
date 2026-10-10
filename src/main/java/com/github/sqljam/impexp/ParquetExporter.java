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
package com.github.sqljam.impexp;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.db.DuckDBDialect;
import com.github.sqljam.impexp.db.DuckDBMetaDataOperations;
import com.github.sqljam.jdbc.SharedConnectionFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ParquetExporter exports tables as an export package with Parquet files: schema.sql and
 *               constraints.sql for the target database (the same as SQL packages), data/&lt;table&gt;.parquet for
 *               rows and manifest.json. Rows are copied into a temporary DuckDB workspace first (values which
 *               Parquet can not hold exactly are kept as text), then written by DuckDB.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public final class ParquetExporter {

    public static final String PARQUET_EXTENSION = ".parquet";
    public static final String DEFAULT_COMPRESSION = "ZSTD";
    public static final String[] COMPRESSIONS = {"ZSTD", "SNAPPY", "GZIP", "UNCOMPRESSED"};

    private final File root;
    private final Exporter.ExportConfiguration configuration = new Exporter.ExportConfiguration();
    private ExportListener exportListener = ExportListener.NONE;
    private DbType targetDbType;
    private IdentifierCase identifierCase;
    private String targetCatalogName;
    private String targetSchemaName;
    private int targetMajorVersion = -1;
    private int targetMinorVersion = -1;
    private String compression = DEFAULT_COMPRESSION;
    private final List<File> writtenFiles = new ArrayList<>();

    public ParquetExporter(File root) {
        this.root = root;
        configuration.setShowCreateUserSql(false);
        configuration.setShowCreateCatalogSql(false);
        configuration.setShowCreateSchemaSql(false);
    }

    /**
     * Source database and options, the same as {@link ScriptExporter#getConfiguration()}
     */
    public Exporter.ExportConfiguration getConfiguration() {
        return configuration;
    }

    public void setExportListener(ExportListener exportListener) {
        this.exportListener = exportListener != null ? exportListener : ExportListener.NONE;
    }

    /**
     * Database type of schema.sql and constraints.sql. Default is the same as source database.
     */
    public void setTargetDbType(DbType targetDbType) {
        this.targetDbType = targetDbType;
    }

    public void setIdentifierCase(IdentifierCase identifierCase) {
        this.identifierCase = identifierCase;
    }

    public void setTargetCatalogName(String targetCatalogName) {
        this.targetCatalogName = targetCatalogName;
    }

    public void setTargetSchemaName(String targetSchemaName) {
        this.targetSchemaName = targetSchemaName;
    }

    public void setTargetVersion(int majorVersion, int minorVersion) {
        this.targetMajorVersion = majorVersion;
        this.targetMinorVersion = minorVersion;
    }

    /**
     * Compression of Parquet files: ZSTD (default), SNAPPY, GZIP or UNCOMPRESSED
     */
    public void setCompression(String compression) {
        this.compression = StringUtils.defaultIfBlank(compression, DEFAULT_COMPRESSION).toUpperCase();
    }

    public String getCompression() {
        return compression;
    }

    public File getRoot() {
        return root;
    }

    public List<File> getWrittenFiles() {
        return new ArrayList<>(writtenFiles);
    }

    public void exportDdlAndData() throws Exception {
        export(ExportMode.DDL_DATA);
    }

    public void exportDdl() throws Exception {
        export(ExportMode.DDL);
    }

    public void exportData() throws Exception {
        export(ExportMode.DATA);
    }

    public void export(ExportMode exportMode) throws Exception {
        writtenFiles.clear();
        if (configuration.getDbType() == null) {
            configuration.setDbType(DbType.forUrl(configuration.getUrl()));
        }
        boolean successful = false;
        exportListener.onStart(exportMode);
        PhaseListener phaseListener = new PhaseListener(exportListener);
        try {
            ExportManifest manifest;
            if (exportMode != ExportMode.DATA) {
                exportSchema(phaseListener);
                manifest = ExportManifest.read(root);
            } else {
                manifest = null;
            }
            if (manifest == null) {
                manifest = new ExportManifest();
                manifest.getSource().setDbType(configuration.getDbType());
                manifest.getTarget().setDbType(targetDbType != null ? targetDbType : configuration.getDbType());
            }
            List<ExportManifest.FileEntry> parquetFiles = new ArrayList<>();
            if (exportMode != ExportMode.DDL) {
                exportRows(phaseListener, manifest, parquetFiles);
            }
            writeManifest(manifest, exportMode, parquetFiles);
            successful = !phaseListener.isFailed();
        } catch (ExportCancelledException e) {
            throw e;
        } catch (Exception e) {
            exportListener.onError(e.getMessage(), e);
            throw e;
        } finally {
            exportListener.onEnd(successful);
        }
    }

    /**
     * schema.sql and constraints.sql by the script exporter
     */
    private void exportSchema(PhaseListener listener) throws Exception {
        exportListener.onMessage("Export schema");
        ScriptExporter scriptExporter = new ScriptExporter(root, DataFileStrategy.SINGLE_FILE, 0);
        copyConfiguration(configuration, scriptExporter.getConfiguration());
        scriptExporter.setTargetDbType(targetDbType);
        scriptExporter.setIdentifierCase(identifierCase);
        scriptExporter.setTargetCatalogName(targetCatalogName);
        scriptExporter.setTargetSchemaName(targetSchemaName);
        if (targetMajorVersion >= 0) {
            scriptExporter.setTargetVersion(targetMajorVersion, targetMinorVersion);
        }
        scriptExporter.setConstraintsSeparated(true);
        scriptExporter.setExportListener(listener.forPhase(false));
        scriptExporter.exportDdl();
        writtenFiles.addAll(scriptExporter.getWrittenFiles());
    }

    /**
     * Rows are copied into the workspace and written as Parquet files, one file per table
     */
    private void exportRows(PhaseListener listener, ExportManifest manifest,
                            List<ExportManifest.FileEntry> parquetFiles) throws Exception {
        try (DuckDBWorkspace workspace = new DuckDBWorkspace(root)) {
            exportListener.onMessage("Copy rows into workspace: " + workspace.getFile());
            ImportExporter importExporter = new ImportExporter();
            copyConfiguration(configuration, importExporter.getExportConfiguration());
            Exporter.ExportConfiguration source = importExporter.getExportConfiguration();
            source.setIdReused(true);
            source.setTableRecreated(true);
            source.setIndexIncluded(false);
            source.setForeignKeyIncluded(false);
            source.setCommentIncluded(false);
            // Defaults of the same database type may use sequences
            source.setSequenceIncluded(true);
            ImportExportHandler.ImportConfiguration target = importExporter.getImportConfiguration();
            target.setDbType(DbType.DUCKDB);
            target.setUrl(workspace.getUrl());
            target.setConnectionPoolEnabled(false);
            DuckDBDialect dialect = new DuckDBDialect();
            dialect.setExactValues(true);
            dialect.setExactTargetDbType(targetDbType != null ? targetDbType : configuration.getDbType());
            // Table names of the source are kept
            dialect.setIdentifierCase(IdentifierCase.KEEP);
            importExporter.setTargetDialect(dialect);
            importExporter.setTargetConnectionFactory(new SharedConnectionFactory(workspace.getConnection()));
            importExporter.setExportListener(listener.forPhase(true));
            importExporter.exportDdlAndData();
            if (listener.isFailed()) {
                return;
            }

            List<String[]> tables = workspace.getTables();
            boolean schemaQualified = tables.stream().map(t -> t[0]).distinct().count() > 1;
            DuckDBMetaDataOperations operations = new DuckDBMetaDataOperations();
            DatabaseMetaData metaData = workspace.getConnection().getMetaData();
            for (String[] table : tables) {
                if (exportListener.isCancelled()) {
                    throw new ExportCancelledException();
                }
                String schema = table[0];
                String tableName = table[1];
                String fileName = (schemaQualified ? LobManifestWriter.toFileName(schema) + "." : "")
                        + LobManifestWriter.toFileName(tableName) + PARQUET_EXTENSION;
                File file = new File(new File(root, ScriptExportHandler.DATA_DIR_NAME), fileName);
                Map<String, String> fileMetadata = new LinkedHashMap<>();
                fileMetadata.put("sqljam_table", tableName);
                if (sourceSchema(schema) != null) {
                    fileMetadata.put("sqljam_schema", sourceSchema(schema));
                }
                fileMetadata.put("sqljam_source", String.valueOf(configuration.getDbType()));
                workspace.writeParquet(schema, tableName, file, compression, fileMetadata);
                writtenFiles.add(file);
                long rows = workspace.countRows(schema, tableName);
                exportListener.onMessage(String.format("Write %d rows of %s to %s", rows, tableName, file));

                ExportManifest.TableEntry tableEntry = findTableEntry(manifest, sourceSchema(schema), tableName)
                        .orElseGet(() -> {
                            ExportManifest.TableEntry entry = new ExportManifest.TableEntry();
                            entry.setSchema(sourceSchema(schema));
                            entry.setName(tableName);
                            manifest.getTables().add(entry);
                            return entry;
                        });
                tableEntry.setRows(rows);
                String path = ScriptExportHandler.DATA_DIR_NAME + "/" + fileName;
                tableEntry.getDataFiles().clear();
                tableEntry.getDataFiles().add(path);
                tableEntry.getIdentityColumns().clear();
                for (Map<String, Object> column : operations.getColumnInfos(metaData, null, schema, tableName)) {
                    if ("YES".equalsIgnoreCase((String) column.get("IS_AUTOINCREMENT"))) {
                        tableEntry.getIdentityColumns().add((String) column.get("COLUMN_NAME"));
                    }
                }
                parquetFiles.add(new ExportManifest.FileEntry(path, ExportManifest.FileType.PARQUET));
            }
        }
    }

    /**
     * Schema of the source table, tables of sources without schemas are in the default schema of the workspace
     */
    private String sourceSchema(String workspaceSchema) {
        return configuration.getDbType() != null && !configuration.getDbType().isSchemaSupported()
                && DuckDBWorkspace.DEFAULT_SCHEMA.equals(workspaceSchema) ? null : workspaceSchema;
    }

    private static Optional<ExportManifest.TableEntry> findTableEntry(ExportManifest manifest, String schema,
                                                                    String table) {
        return manifest.getTables().stream()
                .filter(entry -> table.equals(entry.getName())
                        && (schema == null || entry.getSchema() == null || schema.equals(entry.getSchema())))
                .findFirst();
    }

    /**
     * Files in the order of importing: schema.sql, Parquet files, constraints.sql
     */
    private void writeManifest(ExportManifest manifest, ExportMode exportMode,
                               List<ExportManifest.FileEntry> parquetFiles) throws Exception {
        manifest.setDataFormat(DataFormat.PARQUET);
        manifest.setExportMode(exportMode);
        manifest.setStatus(ExportManifest.Status.COMPLETED);
        manifest.getOptions().put("compression", compression);
        List<ExportManifest.FileEntry> files = new ArrayList<>();
        for (ExportManifest.FileEntry entry : manifest.getFiles()) {
            if (entry.getType() == ExportManifest.FileType.SCHEMA) {
                files.add(entry);
            }
        }
        files.addAll(parquetFiles);
        for (ExportManifest.FileEntry entry : manifest.getFiles()) {
            if (entry.getType() == ExportManifest.FileType.CONSTRAINTS) {
                files.add(entry);
            }
        }
        manifest.setFiles(files);
        manifest.write(root);
        File manifestFile = new File(root, ExportManifest.FILE_NAME);
        if (!writtenFiles.contains(manifestFile)) {
            writtenFiles.add(manifestFile);
        }
        exportListener.onMessage("Write manifest: " + manifestFile);
    }

    /**
     * Copies source settings and options, dialects are created by each exporter
     */
    static void copyConfiguration(Exporter.ExportConfiguration from, Exporter.ExportConfiguration to) {
        for (Field field : Exporter.ExportConfiguration.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || "dialect".equals(field.getName())
                    || "sourceDialect".equals(field.getName())) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(to, field.get(from));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Forwards events of the schema phase and the rows phase as one export: messages and errors of both phases,
     * progress of the rows phase only, start and end are reported by the parquet exporter
     */
    static class PhaseListener {

        private final ExportListener delegate;
        private boolean failed;

        PhaseListener(ExportListener delegate) {
            this.delegate = delegate;
        }

        boolean isFailed() {
            return failed;
        }

        ExportListener forPhase(boolean progress) {
            return new ExportListener() {

                @Override
                public void onTableStart(String catalog, String schema, String table, long totalRows) {
                    if (progress) {
                        delegate.onTableStart(catalog, schema, table, totalRows);
                    }
                }

                @Override
                public void onTableProgress(String catalog, String schema, String table, long rows,
                                            long totalRows) {
                    if (progress) {
                        delegate.onTableProgress(catalog, schema, table, rows, totalRows);
                    }
                }

                @Override
                public void onTableEnd(String catalog, String schema, String table, long rows) {
                    if (progress) {
                        delegate.onTableEnd(catalog, schema, table, rows);
                    }
                }

                @Override
                public void onProgress(long processed, long total) {
                    if (progress) {
                        delegate.onProgress(processed, total);
                    }
                }

                @Override
                public void onMessage(String message) {
                    delegate.onMessage(message);
                }

                @Override
                public void onError(String message, Throwable e) {
                    failed = true;
                    delegate.onError(message, e);
                }

                @Override
                public void onEnd(boolean successful) {
                    if (!successful) {
                        failed = true;
                    }
                }

                @Override
                public boolean isCancelled() {
                    return delegate.isCancelled();
                }
            };
        }
    }
}
