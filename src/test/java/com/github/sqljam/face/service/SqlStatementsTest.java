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
package com.github.sqljam.face.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * @Description: SqlStatementsTest verifies how the text of the SQL editor is split: semicolons in strings, quoted
 *               names and comments, PL/SQL blocks ended by a slash, blank statements, and the statement at the caret
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class SqlStatementsTest {

    private static List<String> texts(String sql) {
        return SqlStatements.split(sql).stream().map(SqlStatements.Statement::getText).collect(Collectors.toList());
    }

    @Test
    void splitsBySemicolonsOutsideOfTexts() {
        assertEquals(List.of("SELECT 'a;b' AS \"x;y\" FROM t", "SELECT `c;d` FROM [e;f]", "SELECT 1 -- ;\nFROM dual",
                        "SELECT /* ; */ 2"),
                texts("SELECT 'a;b' AS \"x;y\" FROM t;\nSELECT `c;d` FROM [e;f];\nSELECT 1 -- ;\nFROM dual;"
                        + "\nSELECT /* ; */ 2"));
        // Doubled and escaped quotes stay in the string
        assertEquals(List.of("SELECT 'it''s; \\'ok;'", "SELECT 3"), texts("SELECT 'it''s; \\'ok;';SELECT 3"));
        // An array index of PostgreSQL is not a name of SQL Server
        assertEquals(List.of("SELECT a[1] FROM t", "SELECT 4"), texts("SELECT a[1] FROM t; SELECT 4"));
    }

    @Test
    void leavesOutBlankStatements() {
        assertEquals(List.of(), texts(null));
        assertEquals(List.of(), texts(" ; ;\n -- comment only\n;/* block */"));
        assertEquals(List.of("SELECT 1"), texts(";;SELECT 1;;"));
        // Unclosed strings and comments end with the text
        assertEquals(List.of("SELECT 'open"), texts("SELECT 'open"));
        assertEquals(List.of("SELECT 1 /* open"), texts("SELECT 1 /* open"));
    }

    @Test
    void keepsPlsqlBlocksUntilSlash() {
        String sql = "BEGIN\n  INSERT INTO t VALUES (1);\n  COMMIT;\nEND;\n/\nCREATE OR REPLACE PROCEDURE p AS\nBEGIN\n"
                + "  NULL;\nEND;\n/\nSELECT 1 FROM dual;";
        List<String> statements = texts(sql);
        assertEquals(3, statements.size(), statements.toString());
        assertTrue(statements.get(0).startsWith("BEGIN") && statements.get(0).endsWith("END;"));
        assertTrue(statements.get(1).startsWith("CREATE OR REPLACE PROCEDURE p"));
        assertEquals("SELECT 1 FROM dual", statements.get(2));
        // A slash in an expression does not end a statement
        assertEquals(List.of("SELECT 4 / 2"), texts("SELECT 4 / 2"));
    }

    @Test
    void findsStatementAtCaret() {
        String sql = "SELECT 1;\n\n-- second\nSELECT 2;   \nSELECT 3";
        assertEquals("SELECT 1", SqlStatements.at(sql, 0).getText());
        // After the semicolon on the same line
        assertEquals("SELECT 1", SqlStatements.at(sql, 9).getText());
        // A blank line before a statement belongs to it
        assertEquals("-- second\nSELECT 2", SqlStatements.at(sql, 10).getText());
        assertEquals("-- second\nSELECT 2", SqlStatements.at(sql, sql.indexOf("2;") + 4).getText());
        assertEquals("SELECT 3", SqlStatements.at(sql, sql.length()).getText());
        assertNull(SqlStatements.at("  ", 1));
        // Positions of the statement in the text
        SqlStatements.Statement second = SqlStatements.split(sql).get(1);
        assertEquals(sql.indexOf("-- second"), second.getStart());
        assertEquals(sql.indexOf("2;") + 2, second.getEnd());
        assertEquals("-- second\nSELECT 2", second.toString());
    }

    @Test
    void knowsQueries() {
        for (String query : new String[]{"select 1", "  WITH t AS (SELECT 1) SELECT * FROM t", "-- c\nSHOW TABLES",
                "/* c */ VALUES (1)", "EXPLAIN SELECT 1", "desc t", "PRAGMA table_info(t)"}) {
            assertTrue(SqlStatements.isQuery(query), query);
        }
        for (String statement : new String[]{"UPDATE t SET a = 1", "CREATE TABLE t (a INT)", "selected", ""}) {
            assertFalse(SqlStatements.isQuery(statement), statement);
        }
        assertEquals("SELECT 1", SqlStatements.stripLeadingComments("-- a\n/* b */ SELECT 1"));
        assertEquals("", SqlStatements.stripLeadingComments("/* open"));
        assertEquals("", SqlStatements.stripLeadingComments("-- only"));
    }
}
