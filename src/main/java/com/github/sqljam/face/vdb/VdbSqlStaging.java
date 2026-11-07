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
package com.github.sqljam.face.vdb;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbSqlResult;

/**
 * @Description: VdbSqlStaging reads all rows of a SQL query of Elasticsearch or OpenSearch, page by page by its
 *               cursor, into a table of a DuckDB file, which the export wizard reads like any data source: rows of
 *               indexes are exported like the rows of tables into packages and databases. The columns get the
 *               types of their values (numbers, booleans, timestamps), others are text, arrays and objects (and
 *               vectors) JSON text. The file is deleted when SqlJam ends.
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
final class VdbSqlStaging {

    /**
     * Rows of a page read from the server
     */
    static final int PAGE_SIZE = 1000;
    private static final Pattern FROM = Pattern.compile("(?is)\\bFROM\\s+(\"([^\"]+)\"|`([^`]+)`|([^\\s,;()]+))");

    private VdbSqlStaging() {
    }

    /**
     * Rows of a query in a table of a DuckDB file
     */
    static final class Staged {

        final File file;
        final String table;
        final long rows;

        Staged(File file, String table, long rows) {
            this.file = file;
            this.table = table;
            this.rows = rows;
        }
    }

    /**
     * Reads the rows of the query into the table of a new DuckDB file
     *
     * @param progress receives the rows read so far
     */
    static Staged stage(VdbClient client, String sql, String table, AtomicBoolean cancelled, LongConsumer progress)
            throws Exception {
        File directory = new File(FileUtils.getTempDirectory(), ".sqljam-queries");
        FileUtils.forceMkdir(directory);
        File file = new File(directory, UUID.randomUUID() + ".duckdb");
        file.deleteOnExit();
        new File(file.getPath() + ".wal").deleteOnExit();
        VdbSqlResult page = client.querySql(sql, PAGE_SIZE);
        List<String[]> columns = uniqueNames(page.getColumns());
        long rows = 0;
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:" + file.getAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"raw\" (" + columns.stream().map(column -> quote(column[0])
                        + " VARCHAR").collect(Collectors.joining(", ")) + ")");
            }
            String insert = "INSERT INTO \"raw\" VALUES (" + columns.stream().map(column -> "?")
                    .collect(Collectors.joining(", ")) + ")";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                while (true) {
                    for (List<Object> row : page.getRows()) {
                        for (int i = 0; i < columns.size(); i++) {
                            statement.setString(i + 1, text(i < row.size() ? row.get(i) : null));
                        }
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    rows += page.getRows().size();
                    progress.accept(rows);
                    if (!page.hasMore()) {
                        break;
                    }
                    if (cancelled.get()) {
                        client.closeSqlCursor(page.getCursor());
                        throw new ExportCancelledException();
                    }
                    page = client.nextSqlPage(page.getCursor());
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + quote(table) + " AS SELECT " + columns.stream()
                        .map(column -> cast(column[0], column[1])).collect(Collectors.joining(", "))
                        + " FROM \"raw\"");
                statement.execute("DROP TABLE \"raw\"");
            }
        } catch (Exception e) {
            FileUtils.deleteQuietly(file);
            FileUtils.deleteQuietly(new File(file.getPath() + ".wal"));
            throw e;
        }
        return new Staged(file, table, rows);
    }

    /**
     * Columns with names which are not empty and not repeated, a repeated name is numbered
     */
    static List<String[]> uniqueNames(List<String[]> columns) {
        List<String[]> named = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String[] column : columns) {
            String base = StringUtils.defaultIfBlank(column[0], "column" + (named.size() + 1));
            String name = base;
            for (int i = 2; !names.add(name.toLowerCase(Locale.ENGLISH)); i++) {
                name = base + "_" + i;
            }
            named.add(new String[]{name, column[1]});
        }
        return named;
    }

    /**
     * The value of a column of the type of the server, text which is not of the type is NULL
     */
    static String cast(String column, String serverType) {
        String type = duckdbType(serverType);
        String quoted = quote(column);
        if ("TIMESTAMP".equals(type)) {
            // 2024-01-02T03:04:05.123Z of Elasticsearch and 2024-01-02 03:04:05 of OpenSearch
            return String.format("TRY_CAST(replace(regexp_replace(%1$s, 'Z$', ''), 'T', ' ') AS TIMESTAMP) AS %1$s",
                    quoted);
        }
        return "VARCHAR".equals(type) ? quoted : String.format("TRY_CAST(%1$s AS %2$s) AS %1$s", quoted, type);
    }

    /**
     * DuckDB type of a type of Elasticsearch or OpenSearch, text for strings, objects, arrays and others
     */
    static String duckdbType(String serverType) {
        switch (StringUtils.defaultString(serverType).toLowerCase(Locale.ENGLISH)) {
            case "boolean":
                return "BOOLEAN";
            case "byte":
                return "TINYINT";
            case "short":
                return "SMALLINT";
            case "integer":
                return "INTEGER";
            case "long":
                return "BIGINT";
            case "unsigned_long":
                return "DECIMAL(20, 0)";
            case "float":
            case "half_float":
                return "FLOAT";
            case "double":
            case "scaled_float":
                return "DOUBLE";
            case "datetime":
            case "date":
            case "date_nanos":
            case "timestamp":
                return "TIMESTAMP";
            default:
                return "VARCHAR";
        }
    }

    static String text(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString();
    }

    /**
     * Columns which hold vectors: types of vectors, or values which are arrays of numbers
     */
    static List<String> vectorColumns(List<String[]> columns, List<List<Object>> rows) {
        List<String> vectors = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String type = StringUtils.defaultString(columns.get(i)[1]).toLowerCase(Locale.ENGLISH);
            boolean vector = type.contains("vector");
            for (List<Object> row : rows) {
                if (vector || i >= row.size() || row.get(i) == null) {
                    continue;
                }
                vector = row.get(i).toString().matches("(?s)\\[\\s*-?[0-9][^\\[\\]{}\"]*\\]");
                break;
            }
            if (vector) {
                vectors.add(columns.get(i)[0]);
            }
        }
        return vectors;
    }

    /**
     * Name of the table of a query: the index after FROM made an identifier (logs-2024.* is logs_2024__), query
     * if there is none
     */
    static String tableName(String sql) {
        Matcher matcher = FROM.matcher(StringUtils.defaultString(sql));
        if (!matcher.find()) {
            return "query";
        }
        String index = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3)
                : matcher.group(4);
        String name = index.replaceAll("[^\\p{L}\\p{N}_]", "_");
        return name.isEmpty() || Character.isDigit(name.charAt(0)) ? "t_" + name : name;
    }

    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
