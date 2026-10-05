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
package com.github.sqljam.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.TransactionIsolationLevel;
import com.github.sqljam.jdbc.page.MapBasedPageReader;
import com.github.sqljam.impexp.db.H2Dialect;
import com.github.sqljam.page.EachPage;
import com.github.sqljam.page.PageRequest;
import com.github.sqljam.page.PageResponse;

/**
 * @Description: JdbcUtilsTest runs on H2 in memory database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class JdbcUtilsTest {

    private static final String URL = "jdbc:h2:mem:jdbc_utils;DB_CLOSE_DELAY=-1";

    private SimpleConnectionFactory connectionFactory;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connectionFactory = new SimpleConnectionFactory("org.h2.Driver", URL, "sa", "");
        connectionFactory.setAutoCommit(true);
        connectionFactory.setTransactionIsolationLevel(TransactionIsolationLevel.READ_COMMITTED);
        connection = connectionFactory.getConnection();
        JdbcUtils.update(connection, "DROP TABLE IF EXISTS t");
        JdbcUtils.update(connection, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(20), body CLOB, data BLOB,"
                + " d DATE, ts TIMESTAMP, tm TIME, xml_col VARCHAR(10), arr INTEGER ARRAY, flag BOOLEAN)");
        JdbcUtils.update(connection, "CREATE INDEX idx_t_name ON t (name)");
    }

    @AfterEach
    void tearDown() throws Exception {
        connectionFactory.close(connection);
        connectionFactory.destroy();
    }

    @Test
    void updatesAndQueries() throws Exception {
        assertEquals(1, JdbcUtils.update(connection, "INSERT INTO t (id, name) VALUES (?, ?)", new Object[]{1, "a"}));
        int[] rows = JdbcUtils.batchUpdate(connection, "INSERT INTO t (id, name) VALUES (?, ?)",
                Arrays.asList(new Object[]{2, "b"}, new Object[]{3, "c"}, new Object[0]));
        assertEquals(2, rows.length);
        assertTrue(JdbcUtils.execute(connection, "SELECT 1"));
        List<Map<String, Object>> all = JdbcUtils.fetchAll(connection, "SELECT id, name FROM t ORDER BY id");
        assertEquals(3, all.size());
        assertEquals("a", all.get(0).get("NAME"));
        assertEquals("a", all.get(0).get("name"));
        assertEquals(2, JdbcUtils.fetchAll(connection, "SELECT * FROM t WHERE id > ?", new Object[]{1}).size());
        assertEquals(List.of(1, 2, 3), JdbcUtils.fetchAll(connection, "SELECT id FROM t ORDER BY id", Integer.class));
        assertEquals(List.of("b"), JdbcUtils.fetchAll(connection, "SELECT name FROM t WHERE id = ?",
                new Object[]{2}, String.class));
        assertEquals(3L, JdbcUtils.fetchOne(connection, "SELECT COUNT(*) FROM t", Long.class));
        assertEquals("c", JdbcUtils.fetchOne(connection, "SELECT name FROM t WHERE id = ?", new Object[]{3},
                String.class));
        assertEquals("c", JdbcUtils.fetchOne(connection, "SELECT name FROM t WHERE id = 3").get("NAME"));
        assertEquals("b", JdbcUtils.fetchOne(connection, "SELECT name FROM t WHERE id = ?", new Object[]{2})
                .get("name"));
        assertNull(JdbcUtils.fetchOne(connection, "SELECT name FROM t WHERE id = 9"));
        assertNull(JdbcUtils.fetchOne(connection, "SELECT name FROM t WHERE id = 9", String.class));
        assertNull(JdbcUtils.fetchOne(connection, "SELECT name FROM t WHERE id = ?", new Object[]{9}));

        Cursor<Map<String, Object>> cursor = JdbcUtils.getCursor(connection, "SELECT * FROM t ORDER BY id",
                new Object[0]);
        assertTrue(cursor.isOpened());
        assertEquals(3, cursor.list().size());
        Cursor<Integer> ids = JdbcUtils.getCursor(connection, "SELECT id FROM t WHERE id < ? ORDER BY id",
                new Object[]{3}, Integer.class);
        assertEquals(List.of(1, 2), ids.list());
        assertFalse(ids.isOpened());

        connection.setAutoCommit(false);
        JdbcUtils.update(connection, "DELETE FROM t WHERE id = 3");
        JdbcUtils.commit(connection);
        JdbcUtils.commitQuietly(connection);
        connection.setAutoCommit(true);
        assertEquals(2L, JdbcUtils.fetchOne(connection, "SELECT COUNT(*) FROM t", Long.class));
        assertThrows(SQLException.class, () -> JdbcUtils.update(connection, "INSERT INTO missing VALUES (1)"));
    }

    @Test
    void metadata() throws Exception {
        assertFalse(JdbcUtils.getCatalogInfos(connection).list().isEmpty());
        assertFalse(JdbcUtils.getSchemaInfos(connection, null).list().isEmpty());
        assertFalse(JdbcUtils.getTableInfos(connection, null, "PUBLIC").list().isEmpty() && false);
        assertEquals(10, JdbcUtils.getColumnInfos(connection, null, "PUBLIC", "T").list().size());
        assertEquals(1, JdbcUtils.getPrimaryKeyInfos(connection, null, "PUBLIC", "T").list().size());
        assertTrue(JdbcUtils.getIndexInfos(connection, null, "PUBLIC", "T").list().size() >= 2);
        assertTrue(JdbcUtils.getImportedKeyInfos(connection, null, "PUBLIC", "T").list().isEmpty());
        JdbcUtils.setPath(connection, connection.getCatalog(), "PUBLIC");
        JdbcUtils.setPath(connection, null, null);
        assertEquals("PUBLIC", connection.getSchema());
    }

    @Test
    void columnValues() throws Exception {
        JdbcUtils.update(connection, "INSERT INTO t VALUES (1, 'n', 'clob text', X'0102', DATE '2024-02-29',"
                + " TIMESTAMP '2024-02-29 23:59:59.123456', TIME '10:20:30', '<a/>', ARRAY[1, 2], TRUE)");
        MapBasedPageReader reader = new MapBasedPageReader(connectionFactory, "SELECT * FROM t", new Object[0], -1,
                new H2Dialect(), "id");
        List<Map<String, Object>> rows = reader.list(0, 10);
        Map<String, Object> row = rows.get(0);
        assertEquals("clob text", row.get("BODY"));
        assertArrayEquals(new byte[]{1, 2}, (byte[]) row.get("DATA"));
        assertEquals(LocalDate.of(2024, 2, 29), row.get("D"));
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 123_456_000), row.get("TS"));
        assertEquals(LocalTime.of(10, 20, 30), row.get("TM"));
        assertArrayEquals(new Object[]{1, 2}, (Object[]) row.get("ARR"));
        assertEquals(true, row.get("FLAG"));
        assertEquals(1L, reader.rowCount());
        assertEquals(1L, reader.rowCount());
        assertEquals(5L, new MapBasedPageReader(connectionFactory, "SELECT * FROM t", 5).rowCount());
        // Default pagination without dialect
        assertEquals(1, new MapBasedPageReader(connectionFactory, "SELECT * FROM t", -1).list(0, 10).size());

        assertEquals(new BigDecimal("18446744073709551615"), JdbcUtils.normalizeValue(
                new BigInteger("18446744073709551615")));
        assertEquals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", JdbcUtils.normalizeValue(
                java.util.UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")));
        assertEquals(2024, JdbcUtils.normalizeValue(java.time.Year.of(2024)));
        assertEquals(LocalTime.of(1, 2, 3), JdbcUtils.normalizeValue(java.sql.Time.valueOf("01:02:03")));
        assertNull(JdbcUtils.normalizeValue(null));
        assertEquals(new BigDecimal("1234.56"), JdbcUtils.parseMoney("$1,234.56"));
        assertEquals(new BigDecimal("-1234.56"), JdbcUtils.parseMoney("($1,234.56)"));
        assertEquals(new BigDecimal("-0.01"), JdbcUtils.parseMoney("-$0.01"));
        assertNull(JdbcUtils.parseMoney(null));
        assertNull(JdbcUtils.parseMoney("$"));
    }

    @Test
    void pages() throws Exception {
        for (int i = 1; i <= 25; i++) {
            JdbcUtils.update(connection, "INSERT INTO t (id, name) VALUES (?, ?)", new Object[]{i, "n" + i});
        }
        MapBasedPageReader reader = new MapBasedPageReader(connectionFactory, "SELECT id FROM t", new Object[0], -1,
                new H2Dialect(), "id");
        PageResponse<Map<String, Object>> response = reader.list(PageRequest.of(10));
        assertEquals(25, response.getTotalRecords());
        assertEquals(3, response.getTotalPages());
        assertTrue(response.isFirstPage());
        assertTrue(response.hasNextPage());
        assertFalse(response.hasPreviousPage());
        assertFalse(response.isEmpty());
        assertEquals(10, response.getPageSize());
        assertEquals(0, response.getOffset());
        int count = 0;
        for (EachPage<Map<String, Object>> page : response) {
            count += page.getContent().size();
            assertEquals(3, page.getTotalPages());
            assertEquals(25, page.getTotalRecords());
            assertFalse(page.isEmpty());
            assertEquals(10, page.getPageSize());
            assertNull(page.getNextToken());
            if (page.isLastPage()) {
                assertFalse(page.hasNextPage());
                assertTrue(page.hasPreviousPage());
                assertEquals(20, page.getOffset());
                assertEquals(3, page.getPageNumber());
            } else if (page.isFirstPage()) {
                assertTrue(page.hasNextPage());
            }
        }
        assertEquals(25, count);
        PageResponse<Map<String, Object>> last = response.lastPage();
        assertTrue(last.isLastPage());
        assertEquals(last, last.lastPage());
        assertEquals(last, last.nextPage());
        assertEquals(2, last.previousPage().getPageNumber());
        assertEquals(1, last.firstPage().getPageNumber());
        assertEquals(response, response.firstPage());
        assertEquals(response, response.previousPage());
        assertEquals(5, response.setPage(2).getContent().getContent().size() - 5);
        assertEquals("Page: 2, Size: 10", PageRequest.of(2, 10).toString());
        assertEquals(1, PageRequest.of(2, 10).previous().getPageNumber());
        assertEquals(1, PageRequest.of(1, 10).previous().getPageNumber());
        assertEquals(1, PageRequest.of(3, 10).first().getPageNumber());
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(0, 10));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(1, 0));
        assertEquals(5, reader.list(1, 0, 5).size());
    }

    @Test
    void connectionFactory() throws Exception {
        SimpleConnectionFactory factory = new SimpleConnectionFactory();
        factory.setDriverClassName("org.h2.Driver");
        factory.setUrl(URL);
        factory.setUser("sa");
        factory.setPassword("");
        assertEquals("org.h2.Driver", factory.getDriverClassName());
        assertEquals(URL, factory.getUrl());
        assertEquals("sa", factory.getUser());
        assertEquals("", factory.getPassword());
        assertNull(factory.getAutoCommit());
        assertNull(factory.getTransactionIsolationLevel());
        try (Connection other = factory.getConnection(null, "PUBLIC")) {
            assertEquals("PUBLIC", other.getSchema());
        }
        assertThrows(IllegalArgumentException.class, () -> factory.setDriverClassName("no.such.Driver"));
        assertEquals(TransactionIsolationLevel.READ_COMMITTED, TransactionIsolationLevel.get(
                Connection.TRANSACTION_READ_COMMITTED));
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, TransactionIsolationLevel.SERIALIZABLE.getLevel());
        JdbcUtils.closeQuietly((Connection) null);
        JdbcUtils.closeQuietly((java.sql.Statement) null);
        JdbcUtils.closeQuietly((java.sql.ResultSet) null);
        try (Connection direct = JdbcUtils.getConnection("jdbc:h2:mem:direct", null, null)) {
            assertTrue(direct.isValid(1));
        }
    }
}
