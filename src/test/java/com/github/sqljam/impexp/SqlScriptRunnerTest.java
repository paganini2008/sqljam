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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.github.sqljam.it.CollectingListener;

/**
 * @Description: SqlScriptRunnerTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class SqlScriptRunnerTest {

    @Test
    void parseStatements() throws Exception {
        String script = String.join("\n",
                "-- comment line",
                "CREATE TABLE t (id INT, name VARCHAR(20)); -- trailing comment",
                "INSERT INTO t VALUES (1, 'a;b');",
                "INSERT INTO t VALUES (2, 'line1",
                "line2; still string');",
                "/* block",
                "comment */ INSERT INTO t VALUES (3, 'c');",
                "INSERT INTO t VALUES (4, 'x'); INSERT INTO t VALUES (5, 'y');",
                "");
        List<String> statements = SqlScriptRunner.parseStatements(script);
        assertEquals(5, statements.size(), statements.toString());
        assertEquals("CREATE TABLE t (id INT, name VARCHAR(20))", statements.get(0));
        assertEquals("INSERT INTO t VALUES (1, 'a;b')", statements.get(1));
        assertEquals("INSERT INTO t VALUES (2, 'line1\nline2; still string')", statements.get(2));
        assertEquals("INSERT INTO t VALUES (3, 'c')", statements.get(3).trim());
        // Statements are terminated by ';' at the end of a line
        assertEquals("INSERT INTO t VALUES (4, 'x'); INSERT INTO t VALUES (5, 'y')", statements.get(4));
    }

    @Test
    void parseBlocks() throws Exception {
        String script = String.join("\n",
                "BEGIN EXECUTE IMMEDIATE 'DROP TABLE t'; EXCEPTION WHEN OTHERS THEN NULL; END;",
                "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW",
                "BEGIN",
                "  IF :NEW.id IS NULL THEN SELECT seq.NEXTVAL INTO :NEW.id FROM DUAL; END IF;",
                "END;",
                "DECLARE",
                "  v NUMBER;",
                "BEGIN",
                "  v := 1;",
                "END",
                "/",
                "DECLARE @schema SYSNAME = SCHEMA_NAME(); EXEC sp_addextendedproperty @name = N'x';",
                "SELECT 1",
                "GO",
                "SELECT 2");
        List<String> statements = SqlScriptRunner.parseStatements(script);
        assertEquals(6, statements.size(), statements.toString());
        assertEquals("BEGIN EXECUTE IMMEDIATE 'DROP TABLE t'; EXCEPTION WHEN OTHERS THEN NULL; END;",
                statements.get(0));
        assertEquals(true, statements.get(1).startsWith("CREATE OR REPLACE TRIGGER"));
        assertEquals(true, statements.get(1).endsWith("END;"));
        assertEquals("DECLARE\n  v NUMBER;\nBEGIN\n  v := 1;\nEND;", statements.get(2));
        assertEquals("DECLARE @schema SYSNAME = SCHEMA_NAME(); EXEC sp_addextendedproperty @name = N'x'",
                statements.get(3));
        assertEquals("SELECT 1", statements.get(4));
        assertEquals("SELECT 2", statements.get(5));
    }

    @Test
    void parseMySqlVersionComment() throws Exception {
        List<String> statements = SqlScriptRunner.parseStatements(
                "CREATE TABLE t (id INT) /*!50100 PARTITION BY HASH (id) */;\n`a``b`;");
        assertEquals("CREATE TABLE t (id INT) /*!50100 PARTITION BY HASH (id) */", statements.get(0));
        assertEquals("`a``b`", statements.get(1));
    }

    @Test
    void runScript() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:runner;DB_CLOSE_DELAY=-1")) {
            SqlScriptRunner runner = new SqlScriptRunner(connection);
            runner.setBatchCommitSize(2);
            CollectingListener listener = new CollectingListener();
            runner.setExportListener(listener);
            runner.runScript("CREATE TABLE t (id INT PRIMARY KEY);\nINSERT INTO t VALUES (1);\n"
                    + "INSERT INTO t VALUES (2);\nINSERT INTO t VALUES (3);");
            assertEquals(4, runner.getExecutedCount());
            assertEquals(1, count(connection));

            // Errors are skipped when not stopping on error
            SqlScriptRunner skipping = new SqlScriptRunner(connection);
            skipping.setStopOnError(false);
            skipping.setExportListener(listener);
            skipping.runScript("INSERT INTO t VALUES (1);\nINSERT INTO t VALUES (4);");
            assertEquals(1, skipping.getFailedCount());
            assertEquals(1, listener.getErrors().size());

            // The whole script is rolled back when stopping on error
            SqlScriptRunner stopping = new SqlScriptRunner(connection);
            assertThrows(SQLException.class, () -> stopping.runScript(
                    "INSERT INTO t VALUES (5);\nINSERT INTO t VALUES (1);"));
            try (ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM t WHERE id = 5")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }

            listener.cancel();
            SqlScriptRunner cancelled = new SqlScriptRunner(connection);
            cancelled.setExportListener(listener);
            assertThrows(ExportCancelledException.class, () -> cancelled.runScript("SELECT 1;"));
        }
    }

    @Test
    void runBatches() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:batch;DB_CLOSE_DELAY=-1")) {
            StringBuilder script = new StringBuilder("CREATE TABLE b (id INT PRIMARY KEY);\n");
            for (int i = 1; i <= 25; i++) {
                script.append("INSERT INTO b VALUES (").append(i).append(");\n");
            }
            script.append("UPDATE b SET id = id + 100 WHERE id = 25;\nINSERT INTO b VALUES (26);");
            SqlScriptRunner runner = new SqlScriptRunner(connection);
            runner.setBatchSize(10);
            CollectingListener listener = new CollectingListener();
            runner.setExportListener(listener);
            runner.runScript(script.toString());
            assertEquals(28, runner.getExecutedCount());
            assertEquals(26, rows(connection, "SELECT COUNT(*) FROM b"));
            assertEquals(1, rows(connection, "SELECT COUNT(*) FROM b WHERE id = 125"));
            assertTrue(listener.isCompleted(), "Progress 100%");

            // A failed batch is executed statement by statement, only the failed statement is skipped
            SqlScriptRunner skipping = new SqlScriptRunner(connection);
            skipping.setBatchSize(10);
            skipping.setStopOnError(false);
            skipping.setExportListener(new CollectingListener());
            skipping.runScript("INSERT INTO b VALUES (201);\nINSERT INTO b VALUES (1);\nINSERT INTO b VALUES (202);");
            assertEquals(1, skipping.getFailedCount());
            assertEquals(2, skipping.getExecutedCount());
            assertEquals(2, rows(connection, "SELECT COUNT(*) FROM b WHERE id IN (201, 202)"));

            // Batches are committed, a failed batch is rolled back when stopping on error
            SqlScriptRunner stopping = new SqlScriptRunner(connection);
            stopping.setBatchSize(2);
            assertThrows(SQLException.class, () -> stopping.runScript(
                    "INSERT INTO b VALUES (301);\nINSERT INTO b VALUES (302);\nINSERT INTO b VALUES (303);\n"
                            + "INSERT INTO b VALUES (1);"));
            assertEquals(2, rows(connection, "SELECT COUNT(*) FROM b WHERE id IN (301, 302, 303)"));

            // Batch size 1 executes statement by statement
            SqlScriptRunner single = new SqlScriptRunner(connection);
            single.setBatchSize(1);
            single.runScript("INSERT INTO b VALUES (401);\nINSERT INTO b VALUES (402);");
            assertEquals(2, single.getExecutedCount());
        }
    }

    @Test
    void oracleBlocks() {
        assertTrue(SqlScriptRunner.isBatchable("INSERT INTO t VALUES (1)"));
        assertTrue(SqlScriptRunner.isBatchable("insert  into\nt (a) values (1)"));
        assertFalse(SqlScriptRunner.isBatchable("UPDATE t SET a = 1"));
        assertFalse(SqlScriptRunner.isBatchable("BEGIN INSERT INTO t VALUES (1); END;"));

        List<String> batch = new ArrayList<>();
        for (int i = 1; i <= SqlScriptRunner.ORACLE_BLOCK_SIZE + 1; i++) {
            batch.add("INSERT INTO FENGY.T(ID,NAME) VALUES (" + i + ",'a;b')");
        }
        List<String> sqls = SqlScriptRunner.getOracleBlockStatements(batch);
        assertEquals(2, sqls.size(), sqls.toString());
        assertTrue(sqls.get(0).startsWith("BEGIN\nINSERT INTO FENGY.T(ID,NAME) VALUES (1,'a;b');\n"));
        assertTrue(sqls.get(0).endsWith(";\nEND;"));
        assertEquals(SqlScriptRunner.ORACLE_BLOCK_SIZE, sqls.get(0).split("\n").length - 2);
        assertEquals("BEGIN\nINSERT INTO FENGY.T(ID,NAME) VALUES (" + (SqlScriptRunner.ORACLE_BLOCK_SIZE + 1)
                + ",'a;b');\nEND;", sqls.get(1));
    }

    private static int rows(Connection connection, String sql) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM t WHERE id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
