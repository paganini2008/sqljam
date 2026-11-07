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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.client.FakeServer;

/**
 * @Description: VdbSqlStagingTest verifies that the rows of a SQL query of Elasticsearch are read page by page
 *               into a DuckDB file with the types of their columns (timestamps of Elasticsearch, numbers, booleans,
 *               text, JSON of objects), a stop between pages which closes the cursor, an error which leaves no
 *               file, the names of tables of queries, repeated names of columns and columns of vectors
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class VdbSqlStagingTest {

    private static final String FIRST = "{\"columns\": [{\"name\": \"id\", \"type\": \"keyword\"},"
            + " {\"name\": \"n\", \"type\": \"integer\"}, {\"name\": \"price\", \"type\": \"double\"},"
            + " {\"name\": \"ok\", \"type\": \"boolean\"}, {\"name\": \"at\", \"type\": \"datetime\"},"
            + " {\"name\": \"big\", \"type\": \"unsigned_long\"}, {\"name\": \"tags\", \"type\": \"object\"}],"
            + " \"rows\": [[\"a\", 1, 1.5, true, \"2024-01-02T03:04:05.123Z\", 18446744073709551615, {\"k\": 1}]],"
            + " \"cursor\": \"c1\"}";
    private static final String NEXT = "{\"rows\": [[\"b\", null, 2, false, \"bad date\", 1, null]]}";

    private static VdbClient client(FakeServer server) {
        VdbSource source = new VdbSource(VdbType.ELASTICSEARCH, server.getUrl());
        source.setTimeoutSeconds(5);
        return VdbType.ELASTICSEARCH.createClient(source);
    }

    @Test
    void readsAllPagesWithTypes() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("POST /_sql?format=json", body -> body.contains("\"cursor\"") ? NEXT : FIRST);
            List<Long> progress = new ArrayList<>();
            VdbSqlStaging.Staged staged;
            try (VdbClient client = client(server)) {
                staged = VdbSqlStaging.stage(client, "SELECT * FROM docs", "docs", new AtomicBoolean(),
                        progress::add);
            }
            assertEquals(2, staged.rows);
            assertEquals(List.of(1L, 2L), progress);
            assertTrue(staged.file.isFile());
            try (Connection connection = DriverManager.getConnection("jdbc:duckdb:" + staged.file
                    .getAbsolutePath()); Statement statement = connection.createStatement()) {
                List<String> types = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("DESCRIBE \"docs\"")) {
                    while (rs.next()) {
                        types.add(rs.getString("column_name") + " " + rs.getString("column_type"));
                    }
                }
                assertEquals(List.of("id VARCHAR", "n INTEGER", "price DOUBLE", "ok BOOLEAN", "at TIMESTAMP",
                        "big DECIMAL(20,0)", "tags VARCHAR"), types);
                try (ResultSet rs = statement.executeQuery("SELECT * FROM \"docs\" ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals("2024-01-02 03:04:05.123", rs.getTimestamp("at").toString());
                    assertEquals(new BigDecimal("18446744073709551615"), rs.getBigDecimal("big"));
                    assertEquals("{\"k\":1}", rs.getString("tags"));
                    assertTrue(rs.next());
                    // A value which is not of the type of its column is NULL
                    rs.getInt("n");
                    assertTrue(rs.wasNull());
                    assertEquals(null, rs.getTimestamp("at"));
                }
                try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM duckdb_tables() WHERE"
                        + " table_name = 'raw'")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void stopsBetweenPagesAndClosesTheCursor() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("POST /_sql?format=json", FIRST).route("POST /_sql/close", "{\"succeeded\": true}");
            AtomicBoolean cancelled = new AtomicBoolean();
            try (VdbClient client = client(server)) {
                assertThrows(ExportCancelledException.class, () -> VdbSqlStaging.stage(client, "SELECT * FROM docs",
                        "docs", cancelled, rows -> cancelled.set(true)));
            }
            assertEquals(1, server.requests("POST /_sql/close").size());
            assertTrue(server.requests("POST /_sql/close").get(0).body.contains("c1"));
        }
    }

    @Test
    void errorsLeaveNoFile() throws Exception {
        java.io.File directory = new java.io.File(org.apache.commons.io.FileUtils.getTempDirectory(),
                ".sqljam-queries");
        int before = files(directory);
        try (FakeServer server = new FakeServer()) {
            server.route("POST /_sql?format=json", body -> {
                if (body.contains("\"cursor\"")) {
                    throw new IllegalStateException("broken");
                }
                return FIRST;
            });
            try (VdbClient client = client(server)) {
                assertThrows(VdbException.class, () -> VdbSqlStaging.stage(client, "SELECT * FROM docs", "docs",
                        new AtomicBoolean(), rows -> { }));
            }
        }
        assertEquals(before, files(directory));
    }

    private static int files(java.io.File directory) {
        String[] names = directory.list();
        return names == null ? 0 : names.length;
    }

    @Test
    void namesTypesAndVectors() {
        assertEquals("docs", VdbSqlStaging.tableName("select * from docs where n > 1"));
        assertEquals("logs_2024__", VdbSqlStaging.tableName("SELECT a FROM \"logs-2024.*\""));
        assertEquals("my_index", VdbSqlStaging.tableName("SELECT a FROM `my index`"));
        assertEquals("t_2024", VdbSqlStaging.tableName("SELECT a FROM 2024"));
        assertEquals("query", VdbSqlStaging.tableName("SHOW TABLES"));
        assertEquals("query", VdbSqlStaging.tableName(null));
        assertEquals("VARCHAR", VdbSqlStaging.duckdbType("geo_point"));
        assertEquals("VARCHAR", VdbSqlStaging.duckdbType(null));
        assertEquals("TINYINT", VdbSqlStaging.duckdbType("byte"));
        assertEquals("SMALLINT", VdbSqlStaging.duckdbType("short"));
        assertEquals("BIGINT", VdbSqlStaging.duckdbType("LONG"));
        assertEquals("FLOAT", VdbSqlStaging.duckdbType("half_float"));
        assertEquals("DOUBLE", VdbSqlStaging.duckdbType("scaled_float"));
        assertEquals("TIMESTAMP", VdbSqlStaging.duckdbType("timestamp"));
        assertEquals("\"t\"", VdbSqlStaging.cast("t", "text"));
        assertEquals("1.50", VdbSqlStaging.text(new BigDecimal("1.50")));
        assertEquals(List.of("a", "A_2", "column3"), VdbSqlStaging.uniqueNames(List.of(
                new String[]{"a", "keyword"}, new String[]{"A", "long"}, new String[]{"", "long"})).stream()
                .map(column -> column[0]).toList());
        List<List<Object>> rows = List.of(Arrays.asList("x", "[0.5,-1.0]", "[\"a\"]", null, "y"),
                Arrays.asList("z", "[1]", "[\"b\"]", "[2,3]", "w"));
        assertEquals(List.of("embedding", "dense"), VdbSqlStaging.vectorColumns(List.of(new String[]{"id", "text"},
                new String[]{"embedding", "nested"}, new String[]{"tags", "nested"}, new String[]{"dense",
                "dense_vector"}, new String[]{"note", "text"}), rows));
        assertTrue(VdbSqlStaging.vectorColumns(List.<String[]>of(new String[]{"id", "text"}), List.of()).isEmpty());
    }
}
