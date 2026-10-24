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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @Description: ManualTransactionTest verifies the transaction of a manual commit with in-memory databases: commits
 *               of importers are ignored, DDL after written rows is deferred on databases which commit around DDL
 *               (H2), a rollback of an importer rolls back everything, and the deferred statements after the commit
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ManualTransactionTest {

    @TempDir
    File dir;

    private static String url() {
        return "jdbc:h2:mem:mt" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static long count(String url, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void classifiesStatements() {
        assertTrue(ManualTransaction.isDdl("  create table t (id int)"));
        assertTrue(ManualTransaction.isDdl("ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES p (id)"));
        assertTrue(ManualTransaction.isDdl("COMMENT ON TABLE t IS 'x'"));
        assertTrue(ManualTransaction.isDdl("BEGIN EXECUTE IMMEDIATE 'DROP TABLE T'; EXCEPTION WHEN OTHERS THEN "
                + "NULL; END;"));
        assertFalse(ManualTransaction.isDdl("INSERT INTO t VALUES (1)"));
        assertFalse(ManualTransaction.isDdl("SET IDENTITY_INSERT t ON"));
        assertTrue(ManualTransaction.isDml("insert into t values (1)"));
        assertTrue(ManualTransaction.isDml("MERGE INTO t USING s ON (1 = 1)"));
        // Batches of Oracle scripts
        assertTrue(ManualTransaction.isDml("BEGIN\nINSERT INTO t VALUES (1);\nINSERT INTO t VALUES (2);\nEND;"));
        assertFalse(ManualTransaction.isDml("SELECT setval('s', 10)"));
        assertFalse(ManualTransaction.isDml("CREATE TABLE inserted (id int)"));
    }

    @Test
    void defersDdlAfterRowsAndCommits() throws Exception {
        String url = url();
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false)) {
            Connection connection = transaction.getConnection();
            assertFalse(connection.getAutoCommit());
            // DDL before rows runs at once, it commits nothing of the import
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
                statement.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT)");
            }
            connection.setAutoCommit(true);
            assertFalse(connection.getAutoCommit());
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO parent VALUES (?)")) {
                ps.setInt(1, 1);
                ps.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO child VALUES (1, 1)");
                // Commits of importers are ignored
                connection.commit();
                assertEquals(0, statement.executeUpdate("ALTER TABLE child ADD CONSTRAINT fk_child FOREIGN KEY "
                        + "(parent_id) REFERENCES parent (id)"));
                statement.addBatch("CREATE INDEX idx_child ON child (parent_id)");
                assertSame(connection, statement.getConnection());
            }
            connection.close();
            assertFalse(transaction.getConnection().isClosed());
            assertEquals(2, transaction.getDeferredStatements().size());
            // Nothing is visible to other connections before the commit
            assertEquals(0, count(url, "parent"));
            assertEquals(List.of(), transaction.commit());
            assertTrue(transaction.isFinished());
            assertThrows(IllegalStateException.class, transaction::commit);
        }
        assertEquals(1, count(url, "parent"));
        assertEquals(1, count(url, "child"));
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                     + "WHERE CONSTRAINT_NAME = 'FK_CHILD'")) {
            rs.next();
            assertEquals(1, rs.getLong(1));
        }
    }

    private static void assertSame(Connection expected, Connection actual) {
        assertTrue(expected == actual, "Statements return the transaction connection");
    }

    @Test
    void emptiedTablesWhichAreDroppedWriteNoRows() throws Exception {
        String url = url();
        try (Connection setup = DriverManager.getConnection(url); Statement statement = setup.createStatement()) {
            statement.execute("CREATE TABLE old_t (id INT PRIMARY KEY)");
            statement.execute("CREATE TABLE kept (id INT PRIMARY KEY)");
        }
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false)) {
            try (Statement statement = transaction.getConnection().createStatement()) {
                // Access empties the tables before it drops them
                statement.executeUpdate("DELETE FROM old_t");
                statement.execute("DROP TABLE old_t");
                statement.execute("CREATE TABLE new_t (id INT PRIMARY KEY)");
                assertEquals(List.of(), transaction.getDeferredStatements());
                // Rows deleted from a table which is kept would be committed by DDL
                statement.executeUpdate("DELETE FROM kept");
                statement.execute("CREATE INDEX idx_kept ON kept (id)");
            }
            assertEquals(List.of("CREATE INDEX idx_kept ON kept (id)"), transaction.getDeferredStatements());
            transaction.rollback();
        }
        assertEquals("old_t", ManualTransaction.getTableName(Pattern.compile("^DELETE\\s+FROM\\s+(\\S+)$"),
                "DELETE FROM [OLD_T]"));
        assertNull(ManualTransaction.getTableName(Pattern.compile("^DROP\\s+TABLE\\s+(\\S+)$"), "DROP VIEW v"));
    }

    @Test
    void rollbackOfImporterRollsBackEverything() throws Exception {
        String url = url();
        try (Connection setup = DriverManager.getConnection(url); Statement statement = setup.createStatement()) {
            statement.execute("CREATE TABLE t (id INT PRIMARY KEY)");
        }
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), true)) {
            Connection connection = transaction.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1)");
                // Commit of a batch, then a failed batch is rolled back by the importer
                connection.commit();
                statement.executeUpdate("INSERT INTO t VALUES (2)");
                connection.rollback();
            }
            assertTrue(transaction.isRolledBack());
            transaction.rollback();
            assertThrows(IllegalStateException.class, transaction::rollback);
        }
        assertEquals(0, count(url, "t"));
    }

    /**
     * DDL of databases with transactional DDL is part of the transaction and rolled back with it
     */
    @Test
    void keepsDdlInTransaction() throws Exception {
        String url = "jdbc:duckdb:" + new File(dir, "manual.duckdb").getAbsolutePath();
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), true)) {
            try (Statement statement = transaction.getConnection().createStatement()) {
                statement.execute("CREATE TABLE t (id INT)");
                statement.executeUpdate("INSERT INTO t VALUES (1)");
                statement.execute("CREATE INDEX idx_t ON t (id)");
            }
            assertEquals(List.of(), transaction.getDeferredStatements());
            transaction.rollback();
        }
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM duckdb_tables() WHERE table_name = 't'")) {
            rs.next();
            assertEquals(0, rs.getLong(1));
        }
    }

    @Test
    void reportsFailedDeferredStatements() throws Exception {
        String url = url();
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false)) {
            Connection connection = transaction.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t (id INT)");
                statement.executeUpdate("INSERT INTO t VALUES (1)");
                statement.execute("ALTER TABLE missing ADD COLUMN x INT");
                statement.execute("CREATE INDEX idx_t ON t (id)");
            }
            List<String> errors = transaction.commit();
            // The failure does not stop the next statement, the rows are committed
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).startsWith("ALTER TABLE missing"), errors.toString());
            assertTrue(transaction.toString().contains("deferred=2"));
        }
        assertEquals(1, count(url, "t"));
    }

    @Test
    void closeRollsBackOpenTransaction() throws Exception {
        String url = url();
        try (Connection setup = DriverManager.getConnection(url); Statement statement = setup.createStatement()) {
            statement.execute("CREATE TABLE t (id INT)");
        }
        ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), true);
        assertTrue(transaction.isTransactionalDdl());
        try (Statement statement = transaction.getConnection().createStatement()) {
            statement.executeUpdate("INSERT INTO t VALUES (1)");
        }
        assertSame(transaction.getConnection(), transaction.getConnection().unwrap(Connection.class));
        transaction.close();
        assertTrue(transaction.isFinished());
        assertEquals(0, count(url, "t"));
        // Closing twice does nothing
        transaction.close();
    }

    /**
     * The connection of a transaction is known, also when a connection factory shares it
     */
    @Test
    void knowsConnectionsOfTransactions() throws Exception {
        String url = url();
        try (Connection plain = DriverManager.getConnection(url);
             ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false)) {
            assertTrue(ManualTransaction.isManaged(transaction.getConnection()));
            assertTrue(ManualTransaction.isManaged(new SharedConnectionFactory(transaction.getConnection())
                    .getConnection()));
            assertFalse(ManualTransaction.isManaged(plain));
            assertFalse(ManualTransaction.isManaged(new SharedConnectionFactory(plain).getConnection()));
        }
    }
}
