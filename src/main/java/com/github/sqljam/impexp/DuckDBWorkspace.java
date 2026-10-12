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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.jdbc.JdbcUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: DuckDBWorkspace is a temporary DuckDB database used to write and read Parquet and CSV files. Rows
 *               of other databases are copied into the workspace and written by COPY TO, files are loaded into the
 *               workspace by read_parquet or read_csv and copied into other databases. The database file is deleted
 *               on close.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class DuckDBWorkspace implements Closeable {

    public static final String DEFAULT_SCHEMA = "main";
    public static final String DEFAULT_DELIMITER = ",";

    private final File directory;
    private final File file;
    private Connection connection;

    /**
     * @param parent directory of the workspace, the system temporary directory if null
     */
    public DuckDBWorkspace(File parent) throws IOException {
        File base = parent != null ? parent : FileUtils.getTempDirectory();
        FileUtils.forceMkdir(base);
        this.directory = Files.createTempDirectory(base.toPath(), ".sqljam-workspace-").toFile();
        this.file = new File(directory, "workspace.duckdb");
    }

    public String getUrl() {
        return DbType.DUCKDB.getUrl(null, 0, file.getAbsolutePath());
    }

    public File getFile() {
        return file;
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(getUrl());
        }
        return connection;
    }

    /**
     * Tables of the workspace: schema and table name
     */
    public List<String[]> getTables() throws SQLException {
        String sql = "SELECT schema_name, table_name FROM duckdb_tables() WHERE database_name = current_database()"
                + " AND NOT internal ORDER BY schema_name, table_name";
        return JdbcUtils.fetchAll(getConnection(), sql, new Object[0]).stream()
                .map(row -> new String[]{(String) row.get("schema_name"), (String) row.get("table_name")})
                .collect(Collectors.toList());
    }

    public long countRows(String schema, String table) throws SQLException {
        Map<String, Object> row = JdbcUtils.fetchOne(getConnection(),
                String.format("SELECT count(*) AS n FROM %s", qualify(schema, table)), new Object[0]);
        Object count = row != null ? row.get("n") : null;
        return count instanceof Number ? ((Number) count).longValue() : 0;
    }

    /**
     * Writes rows of the table into a Parquet file. Integers wider than DECIMAL(38) have no Parquet type, they are
     * written as DECIMAL(38,0).
     *
     * @param compression SNAPPY, ZSTD, GZIP or UNCOMPRESSED
     * @param metadata    key value metadata of the file
     */
    public void writeParquet(String schema, String table, File parquetFile, String compression,
                             Map<String, String> metadata) throws SQLException, IOException {
        writeParquet(schema, table, parquetFile, compression, metadata, 0, 0);
    }

    /**
     * Writes a range of rows in the order of insertion into a Parquet file, e.g. a part of a large table
     *
     * @param offset first row of the range
     * @param limit  rows of the range, 0 or less for all rows
     */
    public void writeParquet(String schema, String table, File parquetFile, String compression,
                             Map<String, String> metadata, long offset, long limit) throws SQLException, IOException {
        FileUtils.forceMkdirParent(parquetFile);
        String columnSql = "SELECT column_name, data_type FROM duckdb_columns() WHERE database_name = current_database()"
                + " AND schema_name = ? AND table_name = ? ORDER BY column_index";
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : JdbcUtils.fetchAll(getConnection(), columnSql, new Object[]{schema, table})) {
            String column = quote((String) row.get("column_name"));
            String dataType = StringUtils.upperCase((String) row.get("data_type"), Locale.ENGLISH);
            if ("HUGEINT".equals(dataType) || "UHUGEINT".equals(dataType)) {
                columns.add(String.format("CAST(%1$s AS DECIMAL(38,0)) AS %1$s", column));
            } else {
                columns.add(column);
            }
        }
        StringBuilder options = new StringBuilder("FORMAT parquet, COMPRESSION ")
                .append(StringUtils.defaultIfBlank(compression, "ZSTD").toLowerCase(Locale.ENGLISH));
        if (metadata != null && !metadata.isEmpty()) {
            options.append(", KV_METADATA {").append(metadata.entrySet().stream()
                    .map(e -> e.getKey() + ": " + literal(e.getValue())).collect(Collectors.joining(", ")))
                    .append("}");
        }
        String range = limit > 0 ? String.format(" ORDER BY rowid LIMIT %d OFFSET %d", limit, Math.max(offset, 0))
                : "";
        String sql = String.format("COPY (SELECT %s FROM %s%s) TO %s (%s)", String.join(", ", columns),
                qualify(schema, table), range, literal(parquetFile.getAbsolutePath()), options);
        execute(sql);
    }

    /**
     * Loads Parquet files into a table of the workspace. Identity columns get a sequence and a nextval default, so
     * that they are recognized as identity columns of DuckDB.
     */
    public void loadParquet(String schema, String table, List<File> parquetFiles, List<String> identityColumns)
            throws SQLException {
        execute(String.format("CREATE SCHEMA IF NOT EXISTS %s", quote(schema)));
        execute(String.format("CREATE OR REPLACE TABLE %s AS SELECT * FROM read_parquet(%s)", qualify(schema, table),
                fileList(parquetFiles)));
        addIdentities(schema, table, identityColumns);
    }

    /**
     * Identity columns get a sequence and a nextval default, so that they are recognized as identity columns
     */
    private void addIdentities(String schema, String table, List<String> identityColumns) throws SQLException {
        if (identityColumns != null) {
            for (String column : identityColumns) {
                String sequence = qualify(schema, table + "_" + column + "_seq");
                execute(String.format("CREATE SEQUENCE IF NOT EXISTS %s START 1", sequence));
                execute(String.format("ALTER TABLE %s ALTER COLUMN %s SET DEFAULT nextval('%s')",
                        qualify(schema, table), quote(column), sequence.replace("'", "''")));
            }
        }
    }

    /**
     * Writes rows of the table into a CSV file in UTF-8 with the delimiter, quote, text of NULL, header and date
     * format of the options. An empty string is quoted to keep it apart from NULL, binary values are written as
     * escaped text (\x00). Other encodings are converted after writing.
     */
    public void writeCsv(String schema, String table, File csvFile, CsvOptions options) throws SQLException,
            IOException {
        FileUtils.forceMkdirParent(csvFile);
        StringBuilder sql = new StringBuilder("COPY ").append(qualify(schema, table)).append(" TO ")
                .append(literal(csvFile.getAbsolutePath())).append(" (FORMAT csv, HEADER ").append(options.isHeader())
                .append(", DELIMITER ").append(literal(StringUtils.defaultIfEmpty(options.getDelimiter(),
                        DEFAULT_DELIMITER))).append(", QUOTE ").append(literal(options.getQuote()))
                .append(", ESCAPE ").append(literal(options.getQuote())).append(", NULLSTR ")
                .append(literal(options.getNullString()));
        if (StringUtils.isNotEmpty(options.getDateFormat())) {
            sql.append(", DATEFORMAT ").append(literal(options.getDateFormat())).append(", TIMESTAMPFORMAT ")
                    .append(literal(options.getTimestampFormat()));
        }
        execute(sql.append(")").toString());
    }

    /**
     * Loads CSV files into a table of the workspace. With the columns of the package the values are read with
     * their types (exactly as exported), without them the types are detected from the files.
     *
     * @param columns columns and types of the files, null to detect them
     * @param options options of the files, files of other encodings are converted to UTF-8 first
     */
    public void loadCsv(String schema, String table, List<File> csvFiles, List<ExportManifest.ColumnEntry> columns,
                        List<String> identityColumns, CsvOptions options) throws SQLException, IOException {
        execute(String.format("CREATE SCHEMA IF NOT EXISTS %s", quote(schema)));
        execute(String.format("CREATE OR REPLACE TABLE %s AS SELECT * FROM %s", qualify(schema, table),
                csvReader(toUtf8(csvFiles, options), columns, options)));
        addIdentities(schema, table, identityColumns);
    }

    /**
     * Files in UTF-8: files of other encodings are converted into the workspace, the BOM of UTF-8 is removed
     */
    private List<File> toUtf8(List<File> csvFiles, CsvOptions options) throws IOException {
        if (options == null || options.isUtf8()) {
            return csvFiles;
        }
        List<File> converted = new ArrayList<>();
        for (File file : csvFiles) {
            File target = new File(directory, "utf8-" + (converted.size() + 1) + "-" + file.getName());
            CsvSplitter.transcode(file, options.getCharset(), target, StandardCharsets.UTF_8, false);
            converted.add(target);
        }
        return converted;
    }

    /**
     * read_csv of the files: a quoted empty field is an empty string and the text of NULL is NULL. Options of
     * files of other tools which are not given are detected.
     */
    private static String csvReader(List<File> csvFiles, List<ExportManifest.ColumnEntry> columns,
                                    CsvOptions options) {
        StringBuilder reader = new StringBuilder("read_csv(").append(fileList(csvFiles))
                .append(", allow_quoted_nulls = false");
        if (options != null) {
            reader.append(", header = ").append(options.isHeader()).append(", quote = ").append(literal(options
                    .getQuote())).append(", escape = ").append(literal(options.getQuote())).append(", nullstr = ")
                    .append(literal(options.getNullString()));
            if (StringUtils.isNotEmpty(options.getDelimiter())) {
                reader.append(", delim = ").append(literal(options.getDelimiter()));
            }
            if (StringUtils.isNotEmpty(options.getDateFormat())) {
                reader.append(", dateformat = ").append(literal(options.getDateFormat())).append(", timestampformat = ")
                        .append(literal(options.getTimestampFormat()));
            }
        } else {
            reader.append(", header = true");
        }
        if (columns != null && !columns.isEmpty()) {
            reader.append(", auto_detect = false, columns = {").append(columns.stream().map(column -> literal(column
                    .getName()) + ": " + literal(column.getType())).collect(Collectors.joining(", "))).append("}");
        }
        return reader.append(")").toString();
    }

    /**
     * Reader of the files by their extension: read_csv for .csv, .tsv and .txt files, read_parquet for others
     */
    private String reader(List<File> files, CsvOptions options) throws IOException {
        return files.stream().allMatch(DuckDBWorkspace::isCsv) ? csvReader(toUtf8(files, options), null, options)
                : String.format("read_parquet(%s)", fileList(files));
    }

    public static boolean isCsv(File file) {
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        return name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt");
    }

    private static String fileList(List<File> files) {
        return files.stream().map(f -> literal(f.getAbsolutePath())).collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Drops columns of a workspace table, e.g. columns which are generated by the target database
     */
    public void dropColumns(String schema, String table, List<String> columns) throws SQLException {
        for (String column : columns) {
            execute(String.format("ALTER TABLE %s DROP COLUMN IF EXISTS %s", qualify(schema, table), quote(column)));
        }
    }

    /**
     * Columns of a workspace table: name and DuckDB type
     */
    public List<String[]> describeTable(String schema, String table) throws SQLException {
        List<String[]> columns = new ArrayList<>();
        try (Statement statement = getConnection().createStatement();
             ResultSet rs = statement.executeQuery(String.format("DESCRIBE %s", qualify(schema, table)))) {
            while (rs.next()) {
                columns.add(new String[]{rs.getString("column_name"), rs.getString("column_type")});
            }
        }
        return columns;
    }

    /**
     * Columns of Parquet or CSV files: name and DuckDB type, types of CSV files are detected
     */
    public List<String[]> describeFiles(List<File> dataFiles) throws SQLException, IOException {
        return describeFiles(dataFiles, null);
    }

    /**
     * Columns of Parquet or CSV files, CSV files are read with the options (null to detect them)
     */
    public List<String[]> describeFiles(List<File> dataFiles, CsvOptions options) throws SQLException, IOException {
        List<String[]> columns = new ArrayList<>();
        try (Statement statement = getConnection().createStatement();
             ResultSet rs = statement.executeQuery(String.format("DESCRIBE SELECT * FROM %s", reader(dataFiles,
                     options)))) {
            while (rs.next()) {
                columns.add(new String[]{rs.getString("column_name"), rs.getString("column_type")});
            }
        }
        return columns;
    }

    /**
     * First rows of Parquet or CSV files, values as text
     */
    public List<List<String>> readRows(List<File> dataFiles, int limit) throws SQLException, IOException {
        return readRows(dataFiles, limit, null);
    }

    public List<List<String>> readRows(List<File> dataFiles, int limit, CsvOptions options) throws SQLException,
            IOException {
        List<String> columns = describeFiles(dataFiles, options).stream()
                .map(column -> String.format("CAST(%s AS VARCHAR)", quote(column[0])))
                .collect(Collectors.toList());
        List<List<String>> rows = new ArrayList<>();
        if (columns.isEmpty()) {
            return rows;
        }
        String sql = String.format("SELECT %s FROM %s LIMIT %d", String.join(", ", columns), reader(dataFiles, options),
                Math.max(limit, 0));
        try (Statement statement = getConnection().createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            int count = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    public long countFileRows(List<File> dataFiles) throws SQLException, IOException {
        return countFileRows(dataFiles, null);
    }

    public long countFileRows(List<File> dataFiles, CsvOptions options) throws SQLException, IOException {
        Map<String, Object> row = JdbcUtils.fetchOne(getConnection(),
                String.format("SELECT count(*) AS n FROM %s", reader(dataFiles, options)), new Object[0]);
        Object count = row != null ? row.get("n") : null;
        return count instanceof Number ? ((Number) count).longValue() : 0;
    }

    public void execute(String sql) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug("Workspace: {}", sql);
        }
        try (Statement statement = getConnection().createStatement()) {
            statement.execute(sql);
        }
    }

    public static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static String qualify(String schema, String table) {
        return StringUtils.isNotBlank(schema) ? quote(schema) + "." + quote(table) : quote(table);
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Closes the connection of the workspace, it is opened again when needed
     */
    public synchronized void closeConnection() {
        JdbcUtils.closeQuietly(connection);
        connection = null;
    }

    @Override
    public synchronized void close() {
        JdbcUtils.closeQuietly(connection);
        connection = null;
        FileUtils.deleteQuietly(directory);
    }
}
