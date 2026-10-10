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
 * @Description: DuckDBWorkspace is a temporary DuckDB database used to write and read Parquet files. Rows of other
 *               databases are copied into the workspace and written by COPY TO, Parquet files are loaded into the
 *               workspace by read_parquet and copied into other databases. The database file is deleted on close.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class DuckDBWorkspace implements Closeable {

    public static final String DEFAULT_SCHEMA = "main";

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
        String sql = String.format("COPY (SELECT %s FROM %s) TO %s (%s)", String.join(", ", columns),
                qualify(schema, table), literal(parquetFile.getAbsolutePath()), options);
        execute(sql);
    }

    /**
     * Loads Parquet files into a table of the workspace. Identity columns get a sequence and a nextval default, so
     * that they are recognized as identity columns of DuckDB.
     */
    public void loadParquet(String schema, String table, List<File> parquetFiles, List<String> identityColumns)
            throws SQLException {
        String files = parquetFiles.stream().map(f -> literal(f.getAbsolutePath()))
                .collect(Collectors.joining(", ", "[", "]"));
        execute(String.format("CREATE SCHEMA IF NOT EXISTS %s", quote(schema)));
        execute(String.format("CREATE OR REPLACE TABLE %s AS SELECT * FROM read_parquet(%s)", qualify(schema, table),
                files));
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
     * Columns of Parquet files: name and DuckDB type
     */
    public List<String[]> describeParquet(List<File> parquetFiles) throws SQLException {
        String files = parquetFiles.stream().map(f -> literal(f.getAbsolutePath()))
                .collect(Collectors.joining(", ", "[", "]"));
        List<String[]> columns = new ArrayList<>();
        try (Statement statement = getConnection().createStatement();
             ResultSet rs = statement.executeQuery(String.format("DESCRIBE SELECT * FROM read_parquet(%s)", files))) {
            while (rs.next()) {
                columns.add(new String[]{rs.getString("column_name"), rs.getString("column_type")});
            }
        }
        return columns;
    }

    /**
     * First rows of Parquet files, values as text
     */
    public List<List<String>> readParquetRows(List<File> parquetFiles, int limit) throws SQLException {
        String files = parquetFiles.stream().map(f -> literal(f.getAbsolutePath()))
                .collect(Collectors.joining(", ", "[", "]"));
        List<String> columns = describeParquet(parquetFiles).stream()
                .map(column -> String.format("CAST(%s AS VARCHAR)", quote(column[0])))
                .collect(Collectors.toList());
        List<List<String>> rows = new ArrayList<>();
        if (columns.isEmpty()) {
            return rows;
        }
        String sql = String.format("SELECT %s FROM read_parquet(%s) LIMIT %d", String.join(", ", columns), files,
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

    public long countParquetRows(List<File> parquetFiles) throws SQLException {
        String files = parquetFiles.stream().map(f -> literal(f.getAbsolutePath()))
                .collect(Collectors.joining(", ", "[", "]"));
        Map<String, Object> row = JdbcUtils.fetchOne(getConnection(),
                String.format("SELECT count(*) AS n FROM read_parquet(%s)", files), new Object[0]);
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
