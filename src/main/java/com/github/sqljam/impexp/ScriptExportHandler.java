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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DdlScripter.Catalog;
import com.github.sqljam.impexp.DdlScripter.Schema;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.page.EachPage;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ScriptExportHandler writes ddl and rows into an export package: schema.sql, data files, LOB files, constraints.sql and manifest.json
 * @Author: Fred Feng
 * @Date: 30/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class ScriptExportHandler implements ExportHandler {

    private static final String NEWLINE = System.getProperty("line.separator");
    public static final String SCHEMA_FILE_NAME = "schema.sql";
    public static final String DATA_FILE_NAME = "data.sql";
    public static final String CONSTRAINT_FILE_NAME = "constraints.sql";
    public static final String DATA_DIR_NAME = "data";

    private final File root;
    private final DataFileStrategy dataFileStrategy;
    /**
     * Max bytes of a data file, a new part file is created when exceeding it. 0 means unlimited.
     */
    private long maxFileSize;
    /**
     * Writes LOB values into separated files instead of inline literals
     */
    private boolean lobSeparated = true;
    /**
     * Scripts of each catalog are written into its own directory when exporting multiple catalogs
     */
    private boolean catalogDirectory;

    /**
     * Default max bytes of a data file: 10MB
     */
    public static final long DEFAULT_MAX_FILE_SIZE = 10L * 1024 * 1024;

    public ScriptExportHandler(File root, boolean separate) {
        this(root, separate ? DataFileStrategy.FILE_PER_TABLE : DataFileStrategy.SINGLE_FILE, DEFAULT_MAX_FILE_SIZE);
    }

    public ScriptExportHandler(File root, DataFileStrategy dataFileStrategy, long maxFileSize) {
        this.root = root;
        this.dataFileStrategy = dataFileStrategy;
        this.maxFileSize = maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    /**
     * Part files of a data file
     */
    private static class DataFile {

        private final File dir;
        private final String baseName;
        private int partIndex;
        private File currentFile;
        private long currentSize;

        DataFile(File dir, String baseName) {
            this.dir = dir;
            this.baseName = baseName;
        }

        File nextFile() {
            // orders.sql, orders_2.sql, orders_3.sql ...
            currentFile = new File(dir, partIndex == 0 ? baseName + ".sql" : baseName + "_" + (partIndex + 1) + ".sql");
            partIndex++;
            currentSize = 0;
            return currentFile;
        }
    }

    private final Map<String, DataFile> dataFiles = new HashMap<>();
    private final List<File> dataFileOrder = new ArrayList<>();
    private final Map<TableMetaData, ExportManifest.TableEntry> tableEntries = new LinkedHashMap<>();
    private ExportManifest manifest;
    private Exporter.ExportConfiguration configuration;
    private ExportMode exportMode;
    /**
     * Statements to repeat at the beginning of a new part file, e.g. SET IDENTITY_INSERT ON
     */
    private List<String> tablePrologue = new ArrayList<>();

    /**
     * Foreign keys are always written into constraints.sql, even without rows (Parquet packages load rows later)
     */
    private boolean constraintsSeparated;

    public void setConstraintsSeparated(boolean constraintsSeparated) {
        this.constraintsSeparated = constraintsSeparated;
    }

    public void setLobSeparated(boolean lobSeparated) {
        this.lobSeparated = lobSeparated;
    }

    private final Map<File, LobManifestWriter> lobManifestWriters = new LinkedHashMap<>();
    private final Map<TableMetaData, Long> rowNumbers = new HashMap<>();

    private String lastTableName;
    private final Set<File> writtenFiles = new HashSet<>();
    private final Set<String> dataCatalogNames = new HashSet<>();
    private final IdentityValueTracker identityValueTracker = new IdentityValueTracker();
    private ExportListener exportListener = ExportListener.NONE;
    private Dialect dialect;

    @Override
    public Dialect configureDialect(Dialect dialect) {
        this.dialect = dialect;
        return dialect;
    }

    @Override
    public void setExportListener(ExportListener exportListener) {
        this.exportListener = exportListener != null ? exportListener : ExportListener.NONE;
    }

    @Override
    public void start() {
        writtenFiles.clear();
        dataCatalogNames.clear();
        lobManifestWriters.clear();
        rowNumbers.clear();
        dataFiles.clear();
        dataFileOrder.clear();
        tableEntries.clear();
        lastTableName = null;
    }

    /**
     * Writes manifest.json describing the export directory
     */
    @Override
    public void finish(boolean successful) throws Exception {
        closeLobManifestWriters();
        if (manifest == null) {
            return;
        }
        manifest.setStatus(successful ? ExportManifest.Status.COMPLETED
                : exportListener.isCancelled() ? ExportManifest.Status.CANCELLED : ExportManifest.Status.FAILED);
        ExportManifest.Database target = manifest.getTarget();
        if (dialect != null) {
            target.setDbType(dialect.getDbType());
            if (dialect.getDatabaseMajorVersion() >= 0) {
                target.setVersion(dialect.getDatabaseMajorVersion() + "." + dialect.getDatabaseMinorVersion());
            }
            target.setCatalog(dialect.getTargetCatalogName());
            target.setSchema(dialect.getTargetSchemaName());
            manifest.getOptions().put("identifierCase", dialect.getIdentifierCase());
        }
        if (configuration != null) {
            Map<String, Object> options = manifest.getOptions();
            options.put("tableRecreated", configuration.isTableRecreated());
            options.put("idReused", configuration.isIdReused());
            options.put("indexIncluded", configuration.isIndexIncluded());
            options.put("foreignKeyIncluded", configuration.isForeignKeyIncluded());
            options.put("commentIncluded", configuration.isCommentIncluded());
            options.put("sequenceIncluded", configuration.isSequenceIncluded());
            options.put("pageSize", configuration.getPageSize());
        }
        manifest.getOptions().put("dataFileStrategy", dataFileStrategy);
        manifest.getOptions().put("maxFileSize", maxFileSize);
        manifest.getOptions().put("lobSeparated", lobSeparated);

        // Files in the order of importing
        Set<File> dirs = new LinkedHashSet<>();
        writtenFiles.forEach(file -> dirs.add(file.getParentFile().getName().equals(DATA_DIR_NAME)
                ? file.getParentFile().getParentFile() : file.getParentFile()));
        for (File dir : dirs) {
            addManifestFile(new File(dir, SCHEMA_FILE_NAME), ExportManifest.FileType.SCHEMA);
            for (File dataFile : dataFileOrder) {
                File dataDir = dataFile.getParentFile().getName().equals(DATA_DIR_NAME)
                        ? dataFile.getParentFile().getParentFile() : dataFile.getParentFile();
                if (dataDir.equals(dir)) {
                    addManifestFile(dataFile, ExportManifest.FileType.DATA);
                }
            }
            addManifestFile(new File(dir, LobManifestWriter.MANIFEST_FILE_NAME),
                    ExportManifest.FileType.LOB_MANIFEST);
            addManifestFile(new File(dir, CONSTRAINT_FILE_NAME), ExportManifest.FileType.CONSTRAINTS);
        }
        manifest.getTables().addAll(tableEntries.values());
        manifest.write(root);
        writtenFiles.add(new File(root, ExportManifest.FILE_NAME));
        exportListener.onMessage("Write manifest: " + new File(root, ExportManifest.FILE_NAME));
    }

    private void addManifestFile(File file, ExportManifest.FileType type) {
        if (writtenFiles.contains(file)) {
            manifest.getFiles().add(new ExportManifest.FileEntry(relativize(file), type));
        }
    }

    private String relativize(File file) {
        return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private void closeLobManifestWriters() {
        for (LobManifestWriter writer : lobManifestWriters.values()) {
            try {
                writer.close();
            } catch (IOException e) {
                if (log.isErrorEnabled()) {
                    log.error(e.getMessage(), e);
                }
            }
        }
        lobManifestWriters.clear();
    }

    @Override
    public void prepare(Exporter.ExportConfiguration configuration, ExportMode exportMode) {
        this.configuration = configuration;
        this.exportMode = exportMode;
    }

    @Override
    public void prepare(ServerMetaData serverMetaData) throws Exception {
        // The dialect may be replaced by the dialect of source database version
        this.dialect = serverMetaData.getDialect();
        manifest = new ExportManifest();
        manifest.setExportMode(exportMode);
        DatabaseMetaData databaseMetaData = serverMetaData.getMetaData();
        ExportManifest.Database source = manifest.getSource();
        source.setDbType(configuration != null ? configuration.getDbType() : null);
        source.setProduct(databaseMetaData.getDatabaseProductName() + " "
                + databaseMetaData.getDatabaseProductVersion());
        source.setVersion(databaseMetaData.getDatabaseMajorVersion() + "." + databaseMetaData.getDatabaseMinorVersion());
        for (CatalogMetaData catalogMetaData : serverMetaData.getCatalogMetaDatas()) {
            if (StringUtils.isNotBlank(catalogMetaData.getCatalogName())) {
                source.getCatalogs().add(catalogMetaData.getCatalogName());
            }
            for (SchemaMetaData schemaMetaData : catalogMetaData.getSchemaMetaDatas()) {
                if (StringUtils.isNotBlank(schemaMetaData.getSchemaName())) {
                    source.getSchemas().add(schemaMetaData.getSchemaName());
                }
                // All exported tables are listed, including empty tables and structure only exports
                for (TableMetaData tableMetaData : schemaMetaData.getTableMetaDatas()) {
                    if (!tableMetaData.isPartitionTable()) {
                        getTableEntry(tableMetaData);
                    }
                }
            }
        }
        long catalogCount = serverMetaData.getCatalogMetaDatas().stream()
                .filter(md -> md.getSchemaMetaDatas().stream().anyMatch(s -> !s.getTableMetaDatas().isEmpty()))
                .count();
        this.catalogDirectory = catalogCount > 1;
    }

    @Override
    public void releaseExternalResource() {
        closeLobManifestWriters();
    }

    public File getRoot() {
        return root;
    }

    public List<File> getWrittenFiles() {
        return new ArrayList<>(writtenFiles);
    }

    private File getCatalogDir(String catalogName) {
        return catalogDirectory && StringUtils.isNotBlank(catalogName) ? new File(root, catalogName) : root;
    }

    private LobManifestWriter getLobManifestWriter(String catalogName) throws IOException {
        File dir = getCatalogDir(catalogName);
        LobManifestWriter writer = lobManifestWriters.get(dir);
        if (writer == null) {
            writer = new LobManifestWriter(dir, dialect != null ? dialect.getDbType() : null);
            lobManifestWriters.put(dir, writer);
            writtenFiles.add(new File(dir, LobManifestWriter.MANIFEST_FILE_NAME));
        }
        return writer;
    }

    private static final Set<String> LOB_TYPE_NAMES = new HashSet<>(Arrays.asList("text",
            "tinytext", "mediumtext", "longtext", "ntext", "clob", "nclob", "blob", "tinyblob", "mediumblob",
            "longblob", "bytea", "image", "long", "long raw", "character large object", "binary large object",
            "national character large object"));

    /**
     * LOB columns are large text/binary columns. JSON, XML and spatial columns are not LOB columns since an empty
     * placeholder is not a valid value of them.
     */
    static boolean isLobColumn(ColumnMetaData columnMetaData) {
        if (columnMetaData.isGenerated()) {
            return false;
        }
        Map<String, Object> detail = columnMetaData.getDetail();
        String typeName = StringUtils.defaultString((String) detail.get("TYPE_NAME")).toLowerCase(Locale.ENGLISH);
        String baseTypeName = typeName.replaceAll("\\(.*\\)", "").trim();
        if (LOB_TYPE_NAMES.contains(baseTypeName) || typeName.endsWith("(max)")) {
            return true;
        }
        int dataType = TableMetaData.getInt(detail, "DATA_TYPE");
        int columnSize = TableMetaData.getInt(detail, "COLUMN_SIZE");
        boolean large = columnSize <= 0 || columnSize >= Integer.MAX_VALUE;
        switch (baseTypeName) {
            case "varchar":
            case "nvarchar":
            case "varbinary":
                // varchar without length (PostgreSQL), varchar(max) of SQL Server
                return large && (dataType == Types.VARCHAR || dataType == Types.NVARCHAR
                        || dataType == Types.VARBINARY || dataType == Types.LONGVARCHAR
                        || dataType == Types.LONGNVARCHAR || dataType == Types.LONGVARBINARY);
            default:
                return false;
        }
    }

    /**
     * Writes lines into the output file, the file is truncated when it's written for the first time in this export
     */
    private void writeLines(File outputFile, List<String> sqlLines) throws IOException {
        FileUtils.forceMkdirParent(outputFile);
        boolean append = writtenFiles.contains(outputFile);
        if (!append) {
            // e.g. disabling foreign key checks so that tables can be dropped and rows inserted in any order
            List<String> lines = getSessionLines(outputFile);
            lines.addAll(sqlLines);
            sqlLines = lines;
        }
        FileOutputStream fos = null;
        try {
            fos = FileUtils.openOutputStream(outputFile, append);
            IOUtils.writeLines(sqlLines, NEWLINE, fos, StandardCharsets.UTF_8);
            writtenFiles.add(outputFile);
            if (log.isDebugEnabled()) {
                log.debug("Successfully write to file: {}", outputFile);
            }
        } finally {
            IOUtils.closeQuietly(fos);
        }
    }

    @Override
    public void exportDdl(DdlScripter ddlScripter) throws Exception {
        for (Map.Entry<String, Catalog> entry : ddlScripter.getCatalogs().entrySet()) {
            String catalogName = entry.getKey();
            if (log.isInfoEnabled()) {
                log.info("Switch to catalog: {}", catalogName);
            }
            List<String> sqls = entry.getValue().getPrettyScripts();
            if (CollectionUtils.isNotEmpty(sqls)) {
                List<String> sqlLines = new ArrayList<>(sqls);
                sqlLines.addAll(0, SqlTextUtils.addEndMarks(ddlScripter.getBeforeStatements()));
                sqlLines.addAll(SqlTextUtils.addEndMarks(ddlScripter.getAfterStatements()));
                if (log.isDebugEnabled()) {
                    sqlLines.forEach(sql -> {
                        log.debug("Generated ddl: {}", sql);
                    });
                }
                File outputFile = new File(getCatalogDir(catalogName), SCHEMA_FILE_NAME);
                writeLines(outputFile, sqlLines);
                exportListener.onMessage("Write ddl to file: " + outputFile);
            }
            if (log.isInfoEnabled()) {
                log.info("Get out of catalog: {}", catalogName);
            }
        }
    }

    @Override
    public void exportConstraints(DdlScripter ddlScripter) throws Exception {
        String commentPrefix = ddlScripter.getDialect().getScriptCommentPrefix();
        for (Map.Entry<String, Catalog> entry : ddlScripter.getCatalogs().entrySet()) {
            String catalogName = entry.getKey();
            List<String> sqlLines = new ArrayList<>();
            for (Schema schema : entry.getValue().getSchemas().values()) {
                sqlLines.addAll(SqlTextUtils.addEndMarks(schema.getConstraintStatements()));
            }
            if (sqlLines.isEmpty()) {
                continue;
            }
            sqlLines.add(0, commentPrefix);
            sqlLines.add(1, String.format("%s Create foreign keys", commentPrefix));
            File catalogDir = getCatalogDir(catalogName);
            File outputFile;
            if (!constraintsSeparated && !dataCatalogNames.contains(StringUtils.defaultString(catalogName))) {
                outputFile = new File(catalogDir, SCHEMA_FILE_NAME);
            } else {
                // Foreign keys are created after rows inserted and LOBs restored
                outputFile = new File(catalogDir, CONSTRAINT_FILE_NAME);
            }
            writeLines(outputFile, sqlLines);
        }
    }

    @Override
    public void exportData(String catalogName,
                           String schemaName,
                           String tableName,
                           TableMetaData tableMetaData,
                           EachPage<Map<String, Object>> eachPage,
                           boolean idReused,
                           ConnectionFactory connectionFactory) throws Exception {
        List<Map<String, Object>> dataList = eachPage.getContent();
        if (CollectionUtils.isEmpty(dataList)) {
            return;
        }
        DataFile dataFile = getDataFile(catalogName, tableName);
        dataCatalogNames.add(StringUtils.defaultString(catalogName));
        Dialect dialect = tableMetaData.getDialect();
        List<Map<String, Object>> rowList = new ArrayList<>();
        Map<String, Object> template = tableMetaData.getColumnMetaDatas().stream()
                .filter(md -> idReused || !shouldFilterColumn(md.getColumnName(), tableMetaData))
                .filter(md -> tableMetaData.isInsertableColumn(md.getColumnName(), tableMetaData.getDialect()))
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getColumnName(), null), LinkedHashMap::putAll);
        for (Map<String, Object> data : dataList) {
            Map<String, Object> row = new LinkedHashMap<>(template);
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (row.containsKey(entry.getKey())) {
                    row.put(entry.getKey(), entry.getValue());
                }
            }
            rowList.add(row);
        }
        List<String> identityColumnNames = idReused ? tableMetaData.getIncrementalColumnNames(dialect)
                : new ArrayList<>();
        identityValueTracker.track(tableMetaData, identityColumnNames, rowList);
        boolean identityIncluded = identityColumnNames.stream().anyMatch(template::containsKey);

        List<String> sqlLines = new ArrayList<>();
        if (StringUtils.isBlank(lastTableName) || !lastTableName.equals(tableName)) {
            sqlLines.add(dialect.getScriptCommentPrefix());
            sqlLines.add(String.format("%s Table: %s", dialect.getScriptCommentPrefix(), tableName));
            String[] beforeStatements = dialect.getStatementBeforeInsert(catalogName, schemaName, tableName,
                    identityIncluded);
            tablePrologue = ArrayUtils.isNotEmpty(beforeStatements)
                    ? SqlTextUtils.addEndMarks(Arrays.asList(beforeStatements)) : new ArrayList<>();
            sqlLines.addAll(tablePrologue);
        }

        List<String> keyColumnNames = tableMetaData.getPrimaryKeyColumnNames();
        Set<String> lobColumnNames = new HashSet<>();
        // Rows are located by primary keys when restoring LOBs
        if (lobSeparated && dialect.isLobSeparationSupported() && !keyColumnNames.isEmpty()
                && template.keySet().containsAll(keyColumnNames)) {
            tableMetaData.getColumnMetaDatas().stream().filter(ScriptExportHandler::isLobColumn)
                    .map(ColumnMetaData::getColumnName).filter(template::containsKey).forEach(lobColumnNames::add);
        }
        String[] columnNames;
        String[] columnStringValues;
        String insertSql;
        for (Map<String, Object> row : rowList) {
            long rowNumber = rowNumbers.merge(tableMetaData, 1L, Long::sum);
            columnNames = row.keySet().toArray(new String[0]);
            columnStringValues = new String[columnNames.length];
            Map<String, Object> lobValues = new LinkedHashMap<>();
            for (int i = 0; i < columnNames.length; i++) {
                ColumnMetaData columnMetaData = tableMetaData.findColumnMetaData(columnNames[i]).orElse(null);
                Object value = getJdbcValue(dialect, columnMetaData, row.get(columnNames[i]));
                if (value != null && lobColumnNames.contains(columnNames[i])) {
                    lobValues.put(dialect.foldIdentifier(columnNames[i]), value);
                    columnStringValues[i] = dialect.getEmptyLobLiteral(value instanceof byte[]);
                } else {
                    columnStringValues[i] = dialect.getStringValue(catalogName, schemaName, tableName,
                            columnNames[i], value);
                }
            }
            if (!lobValues.isEmpty()) {
                getTableEntry(tableMetaData).setLobFiles(getTableEntry(tableMetaData).getLobFiles()
                        + lobValues.size());
                Map<String, Object> key = new LinkedHashMap<>();
                for (String keyColumnName : keyColumnNames) {
                    ColumnMetaData columnMetaData = tableMetaData.findColumnMetaData(keyColumnName).orElse(null);
                    key.put(dialect.foldIdentifier(keyColumnName), getJdbcValue(dialect, columnMetaData,
                            row.get(keyColumnName)));
                }
                getLobManifestWriter(catalogName).writeRow(dialect.getTargetSchemaName(schemaName),
                        dialect.foldIdentifier(tableName), key, lobValues, rowNumber);
            }
            insertSql = dialect.getInsertTableStatement(catalogName, schemaName, tableName, columnNames,
                    columnStringValues);
            sqlLines.add(insertSql);
        }
        if (eachPage.isLastPage()) {
            String[] afterStatements = dialect.getStatementAfterInsert(catalogName, schemaName, tableName,
                    identityIncluded);
            if (ArrayUtils.isNotEmpty(afterStatements)) {
                sqlLines.addAll(Arrays.asList(afterStatements));
            }
            if (idReused) {
                sqlLines.addAll(resetStartValueOfIdSequence(catalogName, schemaName, tableName, tableMetaData));
            }
        }
        if (CollectionUtils.isNotEmpty(sqlLines)) {
            if (log.isDebugEnabled()) {
                sqlLines.forEach(sql -> {
                    log.debug("Generated dml: {}", sql);
                });
            }
            writeData(dataFile, SqlTextUtils.addEndMarks(sqlLines), getTableEntry(tableMetaData));
        }
        getTableEntry(tableMetaData).setRows(getTableEntry(tableMetaData).getRows() + rowList.size());
        if (eachPage.isLastPage()) {
            tablePrologue = new ArrayList<>();
        }
        this.lastTableName = tableName;
    }

    private DataFile getDataFile(String catalogName, String tableName) {
        File catalogDir = getCatalogDir(catalogName);
        if (dataFileStrategy == DataFileStrategy.FILE_PER_TABLE) {
            File dir = new File(catalogDir, DATA_DIR_NAME);
            String baseName = LobManifestWriter.toFileName(tableName);
            return dataFiles.computeIfAbsent(dir.getPath() + "/" + baseName, key -> new DataFile(dir, baseName));
        }
        return dataFiles.computeIfAbsent(catalogDir.getPath(), key -> new DataFile(catalogDir, "data"));
    }

    /**
     * Writes statements into data file, a new part file is created when the size exceeds max file size. A
     * statement is never split.
     */
    private ExportManifest.TableEntry getTableEntry(TableMetaData tableMetaData) {
        return tableEntries.computeIfAbsent(tableMetaData, md -> {
            ExportManifest.TableEntry entry = new ExportManifest.TableEntry();
            entry.setCatalog(StringUtils.defaultIfBlank(md.getCatalogName(), null));
            entry.setSchema(md.getSchemaName());
            entry.setName(md.getTableName());
            return entry;
        });
    }

    private void useDataFile(File file, ExportManifest.TableEntry tableEntry) {
        if (!dataFileOrder.contains(file)) {
            dataFileOrder.add(file);
        }
        tableEntry.getDataFiles().add(relativize(file));
    }

    private void writeData(DataFile dataFile, List<String> sqlLines, ExportManifest.TableEntry tableEntry)
            throws IOException {
        List<String> buffer = new ArrayList<>();
        long bufferSize = 0;
        if (dataFile.currentFile == null) {
            dataFile.nextFile();
        }
        useDataFile(dataFile.currentFile, tableEntry);
        for (String line : sqlLines) {
            long lineSize = line.getBytes(StandardCharsets.UTF_8).length + NEWLINE.length();
            if (maxFileSize > 0 && dataFile.currentSize + bufferSize > 0
                    && dataFile.currentSize + bufferSize + lineSize > maxFileSize) {
                if (!buffer.isEmpty()) {
                    writeLines(dataFile.currentFile, buffer);
                }
                buffer = new ArrayList<>(tablePrologue);
                File file = dataFile.nextFile();
                useDataFile(file, tableEntry);
                bufferSize = sizeOf(buffer) + sizeOf(getSessionLines(file));
            }
            buffer.add(line);
            bufferSize += lineSize;
        }
        if (!buffer.isEmpty()) {
            writeLines(dataFile.currentFile, buffer);
        }
        dataFile.currentSize += bufferSize;
    }

    private static long sizeOf(List<String> lines) {
        return lines.stream().mapToLong(line -> line.getBytes(StandardCharsets.UTF_8).length + NEWLINE.length())
                .sum();
    }

    private List<String> getSessionLines(File file) {
        if (writtenFiles.contains(file) || dialect == null || ArrayUtils.isEmpty(dialect.getSessionStatements())) {
            return new ArrayList<>();
        }
        return SqlTextUtils.addEndMarks(Arrays.asList(dialect.getSessionStatements()));
    }

    static Object getJdbcValue(Dialect dialect, ColumnMetaData columnMetaData, Object value) {
        if (columnMetaData == null) {
            return dialect.getJdbcValue(value);
        }
        Map<String, Object> detail = columnMetaData.getDetail();
        return dialect.getJdbcValue(value, TableMetaData.getInt(detail, "DATA_TYPE"), (String) detail.get("TYPE_NAME"),
                TableMetaData.getInt(detail, "COLUMN_SIZE"));
    }

    protected List<String> resetStartValueOfIdSequence(String catalogName, String schemaName, String tableName,
                                                       TableMetaData tableMetaData) {
        List<String> sqls = new ArrayList<>();
        Map<String, Long> maxValues = identityValueTracker.remove(tableMetaData);
        if (maxValues == null) {
            return sqls;
        }
        for (Map.Entry<String, Long> entry : maxValues.entrySet()) {
            String sql = tableMetaData.getDialect().getResetIdentityStatement(catalogName, schemaName, tableName,
                    entry.getKey(), entry.getValue() + 1);
            if (StringUtils.isNotBlank(sql)) {
                sqls.add(sql);
            }
        }
        return sqls;
    }

    protected boolean shouldFilterColumn(String columnName, TableMetaData tableMetaData) {
        return tableMetaData.isAutoIncrementColumn(columnName);
    }
}
