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
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import com.github.sqljam.jdbc.SharedConnectionFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: DataFileImporter imports data files into a database by a connection. Packages exported by
 *               {@link DataFileExporter} are imported in the order of manifest.json: schema.sql, data files into
 *               the created tables (identity values are kept and identities are reset), constraints.sql. Files of
 *               other tools are imported into a new table created from the columns of the files, or appended to an
 *               existing table. Files are read into a temporary DuckDB workspace, then rows are copied by
 *               {@link ImportExporter} with the dialect of the target database. The format is read by the
 *               subclass: {@link ParquetImporter} or {@link CsvImporter}.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public abstract class DataFileImporter extends AbstractFileImporter {

    /**
     * CREATE drops and creates the table from the columns of the files, APPEND inserts rows into the existing table,
     * UPSERT updates the rows of the same key in the existing table and inserts the others, SKIP_EXISTING inserts
     * the rows whose key is missing and keeps the existing rows
     */
    public enum Mode {
        CREATE,
        APPEND,
        UPSERT,
        SKIP_EXISTING
    }

    private String targetCatalogName;
    private String targetSchemaName;
    private IdentifierCase identifierCase;
    private int targetMajorVersion = -1;
    private int targetMinorVersion = -1;
    private int pageSize = Exporter.DEFAULT_PAGE_SIZE;
    private long importedRows;
    /**
     * Key columns of upserts of files, the primary key of the target table by default
     */
    private List<String> keyColumns = Collections.emptyList();

    protected DataFileImporter(Connection connection, DbType dbType) {
        super(connection, dbType);
    }

    /**
     * Format of the packages imported by this importer
     */
    protected abstract DataFormat getDataFormat();

    protected abstract ExportManifest.FileType getFileType();

    /**
     * Loads the files into a table of the workspace
     *
     * @param tableEntry the table of the package, null for files of other tools
     */
    protected abstract void load(DuckDBWorkspace workspace, String schema, String table, List<File> files,
                                 List<String> identityColumns, ExportManifest.TableEntry tableEntry)
            throws SQLException, IOException;

    /**
     * Rows of files of other tools, e.g. for the progress
     */
    protected long countRows(DuckDBWorkspace workspace, List<File> files) throws SQLException, IOException {
        return workspace.countFileRows(files);
    }

    /**
     * Reads options of the package before its files are imported, e.g. the delimiter of CSV files
     */
    protected void prepare(ExportManifest manifest) {
    }

    /**
     * Catalog of the table, the catalog of the connection by default
     */
    public void setTargetCatalogName(String targetCatalogName) {
        this.targetCatalogName = targetCatalogName;
    }

    /**
     * Schema of the table, the schema of the connection by default
     */
    public void setTargetSchemaName(String targetSchemaName) {
        this.targetSchemaName = targetSchemaName;
    }

    public void setIdentifierCase(IdentifierCase identifierCase) {
        this.identifierCase = identifierCase;
    }

    public void setTargetVersion(int majorVersion, int minorVersion) {
        this.targetMajorVersion = majorVersion;
        this.targetMinorVersion = minorVersion;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * Key columns of upserts of files, empty for the primary key of the target table
     */
    public void setKeyColumns(List<String> keyColumns) {
        this.keyColumns = keyColumns != null ? keyColumns : Collections.emptyList();
    }

    /**
     * Rows imported from data files
     */
    public long getImportedRows() {
        return importedRows;
    }

    /**
     * Imports data files of other tools into a table
     *
     * @return number of imported rows
     */
    public long importFiles(List<File> files, String tableName, Mode mode) throws Exception {
        Validate.notEmpty(files, "Data files must be required");
        files = CsvSplitter.sortParts(files);
        Validate.notBlank(tableName, "Table name must be required");
        importedRows = 0;
        boolean successful = false;
        exportListener.onStart(mode == Mode.CREATE ? ExportMode.DDL_DATA : ExportMode.DATA);
        try (DuckDBWorkspace workspace = new DuckDBWorkspace(workspaceDirectory)) {
            long total = countRows(workspace, files);
            exportListener.onMessage(String.format("Import %d rows of %d %s files into %s", total, files.size(),
                    getDataFormat(), tableName));
            importTable(workspace, DuckDBWorkspace.DEFAULT_SCHEMA, tableName, files, Collections.emptyList(), null,
                    mode, null, null, new ProgressListener(exportListener, 0, total));
            executedCount = (int) Math.min(Integer.MAX_VALUE, importedRows);
            successful = true;
        } finally {
            exportListener.onEnd(successful);
        }
        return importedRows;
    }

    /**
     * schema.sql, data files (all together, in the order of foreign keys) and constraints.sql
     */
    @Override
    protected void importManifest(File dir, ExportManifest manifest) throws IOException, SQLException {
        if (manifest.getDataFormat() != getDataFormat()) {
            throw new ImpExpException(String.format("Packages of %s files are not imported by %s",
                    manifest.getDataFormat(), getClass().getSimpleName()));
        }
        prepare(manifest);
        importedRows = 0;
        boolean dataImported = false;
        for (ExportManifest.FileEntry fileEntry : manifest.getFiles()) {
            File file = new File(dir, fileEntry.getPath());
            if (fileEntry.getType() == getFileType()) {
                if (!dataImported) {
                    importPackageFiles(dir, manifest);
                    dataImported = true;
                }
            } else {
                runScript(file);
            }
        }
    }

    private void importPackageFiles(File dir, ExportManifest manifest) throws SQLException {
        long dataBytes = manifest.getFiles().stream()
                .filter(entry -> entry.getType() == getFileType())
                .mapToLong(entry -> new File(dir, entry.getPath()).length()).sum();
        long startBytes = completedBytes;
        ExportListener bytesListener = new ForwardingListener(exportListener) {

            // Tables of the package are completed one by one in the progress of the import
            @Override
            public void onTableStart(String catalogName, String schemaName, String tableName, long totalRows) {
                exportListener.onTableStart(catalogName, schemaName, tableName, totalRows);
            }

            @Override
            public void onTableProgress(String catalogName, String schemaName, String tableName, long processedRows,
                                        long totalRows) {
                exportListener.onTableProgress(catalogName, schemaName, tableName, processedRows, totalRows);
            }

            @Override
            public void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
                exportListener.onTableEnd(catalogName, schemaName, tableName, processedRows);
            }

            @Override
            public void onProgress(long processed, long total) {
                // Rows are converted to bytes of data files
                long bytes = total > 0 ? dataBytes * processed / total : 0;
                exportListener.onProgress(Math.min(startBytes + bytes, totalBytes), totalBytes);
            }

            @Override
            public void onError(String message, Throwable e) {
                failedCount++;
                exportListener.onError(message, e);
            }
        };
        List<ExportManifest.TableEntry> tables = manifest.getTables().stream()
                .filter(table -> !table.getDataFiles().isEmpty()).collect(Collectors.toList());
        IdentifierCase manifestCase = getIdentifierCase(manifest);
        if (identifierCase == null) {
            identifierCase = manifestCase;
        }
        try {
            if (createDialect().isForeignKeyOrderRequired()) {
                tables = sortByForeignKeys(tables, manifest);
            }
            long total = tables.stream().mapToLong(ExportManifest.TableEntry::getRows).sum();
            long completed = 0;
            try (DuckDBWorkspace workspace = new DuckDBWorkspace(workspaceDirectory)) {
                for (ExportManifest.TableEntry table : tables) {
                    if (exportListener.isCancelled()) {
                        throw new ExportCancelledException();
                    }
                    // Parts in order: orders.csv, orders_2.csv ... orders_n.csv
                    List<File> files = CsvSplitter.sortParts(table.getDataFiles().stream()
                            .map(path -> new File(dir, path)).collect(Collectors.toList()));
                    String schema = StringUtils.defaultIfBlank(table.getSchema(), DuckDBWorkspace.DEFAULT_SCHEMA);
                    exportListener.onMessage(String.format("Import %d rows of %s", table.getRows(), table.getName()));
                    // Packages of upserts update the rows of the same key, others keep them
                    WriteMode writeMode = WriteMode.of(manifest.getOptions().get("writeMode"));
                    Mode mode = writeMode.isKeyed() ? Mode.valueOf(writeMode.name()) : Mode.APPEND;
                    importTable(workspace, schema, table.getName(), files, table.getIdentityColumns(), table,
                            mode, manifest.getTarget().getCatalog(), manifest.getTarget().getSchema(),
                            new ProgressListener(bytesListener, completed, total));
                    completed += table.getRows();
                }
            }
        } catch (ImpExpException | SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new ImpExpException(e.getMessage(), e);
        }
        executedCount += (int) Math.min(Integer.MAX_VALUE, importedRows);
        completedBytes = startBytes;
        completeBytes(dataBytes);
    }

    private void importTable(DuckDBWorkspace workspace, String schema, String tableName, List<File> files,
                             List<String> identityColumns, ExportManifest.TableEntry tableEntry, Mode mode,
                             String packageCatalog, String packageSchema, ProgressListener listener) throws Exception {
        load(workspace, schema, tableName, files, identityColumns, tableEntry);
        String catalog = StringUtils.defaultIfBlank(targetCatalogName, packageCatalog);
        String targetSchema = StringUtils.defaultIfBlank(targetSchemaName, packageSchema);
        if (mode != Mode.CREATE) {
            // Values of generated and row version columns are maintained by the target database
            List<String> generated = getGeneratedColumns(tableName, catalog, targetSchema);
            List<String> dropped = new ArrayList<>();
            for (String[] column : workspace.describeTable(schema, tableName)) {
                if (generated.stream().anyMatch(name -> name.equalsIgnoreCase(column[0]))) {
                    dropped.add(column[0]);
                }
            }
            workspace.dropColumns(schema, tableName, dropped);
        }
        ImportExporter importExporter = new ImportExporter();
        Exporter.ExportConfiguration source = importExporter.getExportConfiguration();
        source.setDbType(DbType.DUCKDB);
        source.setUrl(workspace.getUrl());
        source.setConnectionPoolEnabled(false);
        source.setIncludedSchemaNames(new String[]{schema});
        source.setIncludedTableNames(new String[]{tableName});
        source.setPageSize(pageSize);
        source.setIdReused(true);
        source.setTableRecreated(mode == Mode.CREATE);
        source.setIndexIncluded(false);
        source.setForeignKeyIncluded(false);
        source.setCommentIncluded(false);
        source.setSequenceIncluded(false);
        // Rows with errors are skipped when the import goes on after errors
        source.setFailFast(stopOnError);
        if (mode == Mode.UPSERT || mode == Mode.SKIP_EXISTING) {
            source.setWriteMode(WriteMode.valueOf(mode.name()));
            List<String> keys = tableEntry != null ? tableEntry.getKeyColumns() : keyColumns;
            if (!keys.isEmpty()) {
                source.getKeyColumnNames().put(tableName, keys);
            }
        }
        ImportExportHandler.ImportConfiguration target = importExporter.getImportConfiguration();
        target.setDbType(dbType);
        target.setTargetCatalogName(catalog);
        target.setTargetSchemaName(targetSchema);
        importExporter.setTargetConnectionFactory(new SharedConnectionFactory(connection));
        if (identifierCase != null) {
            importExporter.setIdentifierCase(identifierCase);
        }
        if (targetMajorVersion >= 0) {
            importExporter.setTargetVersion(targetMajorVersion, targetMinorVersion);
        }
        // Tables go into the target schema or the default schema, not into a schema named as the workspace one
        importExporter.setSourceSchemaPreserved(false);
        importExporter.setExportListener(listener);
        // The workspace is read by connections of the importer
        workspace.closeConnection();
        importExporter.export(mode == Mode.CREATE ? ExportMode.DDL_DATA : ExportMode.DATA);
        // Errors which were skipped are reported already, the import goes on
        if (listener.getError() != null && stopOnError) {
            throw new ImpExpException(listener.getError(), listener.getCause());
        }
        importedRows += listener.getRows();
    }

    /**
     * Dialect folding names as the importer does: the workspace (DuckDB) is the source
     */
    private Dialect createDialect() {
        Dialect dialect = dbType.createDialect();
        dialect.setSourceDbType(DbType.DUCKDB);
        if (identifierCase != null) {
            dialect.setIdentifierCase(identifierCase);
        }
        return dialect;
    }

    private static IdentifierCase getIdentifierCase(ExportManifest manifest) {
        Object option = manifest.getOptions().get("identifierCase");
        if (option == null) {
            return null;
        }
        try {
            return IdentifierCase.valueOf(option.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Generated (computed) and row version columns of the existing target table
     */
    private List<String> getGeneratedColumns(String tableName, String catalog, String schema) throws SQLException {
        List<String> columns = new ArrayList<>();
        MetaDataOperations operations = dbType.createMetaDataOperations();
        String tableSchema = StringUtils.defaultIfBlank(schema, connection.getSchema());
        String tableCatalog = dbType.isCatalogSupported() && dbType != DbType.ORACLE
                ? StringUtils.defaultIfBlank(catalog, connection.getCatalog()) : null;
        List<Map<String, Object>> columnInfos;
        try {
            columnInfos = operations.getColumnInfos(connection.getMetaData(), tableCatalog, tableSchema,
                    createDialect().foldIdentifier(tableName));
        } catch (ImpExpException e) {
            // The table does not exist, the importer reports it
            columnInfos = Collections.emptyList();
        }
        for (Map<String, Object> column : columnInfos) {
            if ("YES".equalsIgnoreCase((String) column.get("IS_GENERATEDCOLUMN"))
                    || Boolean.TRUE.equals(column.get("IS_ROWVERSION"))) {
                columns.add((String) column.get("COLUMN_NAME"));
            }
        }
        return columns;
    }

    /**
     * Referenced tables are imported first, foreign keys are read from the tables created by schema.sql
     */
    private List<ExportManifest.TableEntry> sortByForeignKeys(List<ExportManifest.TableEntry> tables,
                                                             ExportManifest manifest) throws SQLException {
        Dialect dialect = createDialect();
        Map<String, ExportManifest.TableEntry> byName = new LinkedHashMap<>();
        for (ExportManifest.TableEntry table : tables) {
            byName.put(dialect.foldIdentifier(table.getName()).toLowerCase(), table);
        }
        DatabaseMetaData metaData = connection.getMetaData();
        MetaDataOperations operations = dbType.createMetaDataOperations();
        String schema = StringUtils.defaultIfBlank(targetSchemaName,
                StringUtils.defaultIfBlank(manifest.getTarget().getSchema(), connection.getSchema()));
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, ExportManifest.TableEntry> entry : byName.entrySet()) {
            Set<String> referenced = new LinkedHashSet<>();
            for (Map<String, Object> keyInfo : operations.getImportedKeyInfos(metaData, null, schema,
                    dialect.foldIdentifier(entry.getValue().getName()))) {
                String refTable = StringUtils.lowerCase((String) keyInfo.get("PKTABLE_NAME"));
                if (refTable != null && !refTable.equals(entry.getKey()) && byName.containsKey(refTable)) {
                    referenced.add(refTable);
                }
            }
            dependencies.put(entry.getKey(), referenced);
        }
        List<ExportManifest.TableEntry> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String name : byName.keySet()) {
            addInOrder(name, byName, dependencies, visited, new HashSet<>(), sorted);
        }
        return sorted;
    }

    private static void addInOrder(String name, Map<String, ExportManifest.TableEntry> tables,
                                   Map<String, Set<String>> dependencies, Set<String> visited, Set<String> visiting,
                                   List<ExportManifest.TableEntry> sorted) {
        if (visited.contains(name) || !visiting.add(name)) {
            return;
        }
        for (String referenced : dependencies.getOrDefault(name, Collections.emptySet())) {
            addInOrder(referenced, tables, dependencies, visited, visiting, sorted);
        }
        visited.add(name);
        sorted.add(tables.get(name));
    }

    /**
     * Forwards events of a table import: progress of the table is added to rows of tables imported before, start
     * and end of the inner import are not forwarded, the first error is kept
     */
    static class ProgressListener implements ExportListener {

        private final ExportListener delegate;
        private final long completed;
        private final long total;
        private long rows;
        private String error;
        private Throwable cause;

        ProgressListener(ExportListener delegate, long completed, long total) {
            this.delegate = delegate;
            this.completed = completed;
            this.total = total;
        }

        long getRows() {
            return rows;
        }

        String getError() {
            return error;
        }

        Throwable getCause() {
            return cause;
        }

        @Override
        public void onTableStart(String catalogName, String schemaName, String tableName, long totalRows) {
            delegate.onTableStart(catalogName, schemaName, tableName, totalRows);
        }

        @Override
        public void onTableProgress(String catalogName, String schemaName, String tableName, long processedRows,
                                    long totalRows) {
            delegate.onTableProgress(catalogName, schemaName, tableName, processedRows, totalRows);
        }

        @Override
        public void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
            rows += processedRows;
            delegate.onTableEnd(catalogName, schemaName, tableName, processedRows);
        }

        @Override
        public void onProgress(long processed, long tableTotal) {
            if (total > 0) {
                delegate.onProgress(Math.min(completed + processed, total), total);
            }
        }

        @Override
        public void onMessage(String message) {
            delegate.onMessage(message);
        }

        @Override
        public void onError(String message, Throwable e) {
            if (error == null) {
                error = message;
                cause = e;
            }
            delegate.onError(message, e);
        }

        @Override
        public void onRowSkipped(String catalogName, String schemaName, String tableName, String key, Throwable e) {
            delegate.onRowSkipped(catalogName, schemaName, tableName, key, e);
        }

        @Override
        public void onDdlExecuted(String statement, boolean successful) {
            delegate.onDdlExecuted(statement, successful);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }
}
