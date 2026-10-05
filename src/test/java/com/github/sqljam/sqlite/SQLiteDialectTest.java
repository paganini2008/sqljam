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
package com.github.sqljam.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.db.SQLiteDialect;
import com.github.sqljam.impexp.db.SQLiteMetaDataOperations;

/**
 * @Description: SQLiteDialectTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class SQLiteDialectTest {

    @Test
    void ddl() {
        SQLiteDialect dialect = new SQLiteDialect();
        assertEquals(DbType.SQLITE, dialect.getDbType());
        assertNull(dialect.getCreateUserStatement("u", "p"));
        assertNull(dialect.getCreateDatabaseStatement("db", "u"));
        assertNull(dialect.getCreateSchemaStatement(null, "s", "u"));
        assertEquals("DROP TABLE IF EXISTS t", dialect.getDropTableStatement(null, null, "t"));
        assertEquals("id INTEGER NOT NULL", dialect.getIncrementalColumnStatement(null, null, "t", "id",
                Types.BIGINT, "bigint", 19, 0, null, false).replaceAll("\\s+", " "));
        assertNull(dialect.getResetIdentityStatement(null, null, "t", "id", 10));
        assertNull(dialect.getCreateCommentStatement(null, null, "t", "c", "x"));
        assertNull(dialect.getCreateTableCommentStatement(null, null, "t", "x"));
        assertTrue(dialect.isForeignKeyInline());
        assertEquals("CONSTRAINT fk FOREIGN KEY (a) REFERENCES r (id) ON DELETE CASCADE ON UPDATE SET NULL",
                dialect.getCreateForeignKeyStatement(null, null, "t", "fk", new String[]{"a"}, "r",
                        new String[]{"id"}, "SET NULL", "CASCADE"));
        assertEquals("c INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL", dialect.getGeneratedColumnStatement(null,
                null, "t", "c", Types.INTEGER, "INTEGER", 0, 0, "a * 2", false));
        assertNull(dialect.getSequenceNameStatement(null, null, "t", "id"));
        assertNull(dialect.getDefaultSequenceName(null, null, "t", "id"));
        assertNull(dialect.getAlterSequenceStartValueStatement(null, null, "t", "s", 1));
        assertNull(dialect.getCreatePartitionTableStatement(null, null, "p", "t"));
        assertNull(dialect.getCreateSequenceStatement(null, null, "s", 1, 1, null, null, false, null, null));
    }

    @Test
    void types() {
        SQLiteDialect dialect = new SQLiteDialect();
        assertEquals("VARCHAR(20)", dialect.getColumnTypeName(Types.VARCHAR, "VARCHAR", 20, 0));
        assertEquals("DECIMAL(10,5)", dialect.getColumnTypeName(Types.NUMERIC, "DECIMAL", 10, 5));
        assertEquals("DATETIME", dialect.getColumnTypeName(Types.TIMESTAMP, "DATETIME", 0, 0));
        SQLiteDialect cross = new SQLiteDialect();
        cross.setSourceDbType(DbType.POSTGRESQL);
        assertEquals("TEXT", cross.getColumnTypeName(Types.OTHER, "jsonb", 0, 0));
        assertEquals("BLOB", cross.getColumnTypeName(Types.BINARY, "bytea", 0, 0));
        assertEquals("INTEGER", cross.getColumnTypeName(Types.BIGINT, "int8", 19, 0));
        assertEquals("BOOLEAN", cross.getColumnTypeName(Types.BIT, "bool", 1, 0));
        assertEquals("TEXT", cross.getColumnTypeName(Types.VARCHAR, "varchar", 0, 0));
        assertEquals("TEXT", cross.getColumnTypeName(Types.ARRAY, "_int4", 0, 0));
    }

    @Test
    void values() {
        SQLiteDialect dialect = new SQLiteDialect();
        assertEquals("2024-02-29 10:00:00.5", dialect.getJdbcValue(LocalDateTime.of(2024, 2, 29, 10, 0, 0,
                500_000_000), Types.TIMESTAMP, "DATETIME", 0));
        assertEquals("2024-02-29", dialect.getJdbcValue(LocalDate.of(2024, 2, 29), Types.DATE, "DATE", 0));
        assertEquals("10:00:00", dialect.getJdbcValue(LocalTime.of(10, 0), Types.TIME, "TIME", 0));
        assertEquals("'2024-02-29 10:00:00'", dialect.getTimestampLiteral(LocalDateTime.of(2024, 2, 29, 10, 0)));
        assertEquals("'2024-02-29'", dialect.getDateLiteral(LocalDate.of(2024, 2, 29)));
        assertEquals("'10:00:00'", dialect.getTimeLiteral(LocalTime.of(10, 0)));
        assertEquals("0", dialect.getBooleanLiteral(false));
    }

    @Test
    void declaredTypes() {
        assertEquals(Types.BIGINT, resolve("INTEGER", 2000000000));
        assertEquals(Types.INTEGER, resolve("INT", 2000000000));
        assertEquals(Types.SMALLINT, resolve("INT2", 2000000000));
        assertEquals(Types.TINYINT, resolve("TINYINT", 2000000000));
        assertEquals(Types.DOUBLE, resolve("REAL", 2000000000));
        assertEquals(Types.NUMERIC, resolve("NUMERIC(10,2)", 2000000000));
        assertEquals(Types.BOOLEAN, resolve("BOOLEAN", 2000000000));
        assertEquals(Types.DATE, resolve("DATE", 2000000000));
        assertEquals(Types.TIMESTAMP, resolve("DATETIME", 2000000000));
        assertEquals(Types.TIME, resolve("TIME", 2000000000));
        assertEquals(Types.BLOB, resolve("BLOB", 2000000000));
        assertEquals(Types.BLOB, resolve("", 2000000000));
        assertEquals(Types.CLOB, resolve("TEXT", 2000000000));
        assertEquals(Types.CHAR, resolve("CHARACTER", 20));
        assertEquals(Types.VARCHAR, resolve("VARCHAR(20)", 20));
        assertEquals(Types.CLOB, resolve("NATIVE VARCHAR", 2000000000));
        assertEquals(Types.BIGINT, resolve("UNSIGNED INT8", 2000000000));
        // Type affinity rule of SQLite: a declared type containing "INT" is an integer, even "FLOATING POINT"
        assertEquals(Types.BIGINT, resolve("FLOATING POINT", 2000000000));
        assertEquals(Types.DOUBLE, resolve("DOUBLE VALUE", 2000000000));
        assertEquals(Types.VARCHAR, resolve("JSON", 2000000000));
    }

    private static int resolve(String declaredType, int columnSize) {
        Map<String, Object> columnInfo = new HashMap<>();
        columnInfo.put("TYPE_NAME", declaredType);
        columnInfo.put("COLUMN_SIZE", columnSize);
        columnInfo.put("DECIMAL_DIGITS", 0);
        SQLiteMetaDataOperations.resolveColumnType(columnInfo);
        return (Integer) columnInfo.get("DATA_TYPE");
    }

    @Test
    void generation() {
        String sql = "CREATE TABLE t (id INTEGER, \"c_gen\" INTEGER GENERATED ALWAYS AS (abs(id) * 2) STORED, x INT)";
        String[] generation = SQLiteMetaDataOperations.parseGeneration(sql, "c_gen");
        assertEquals("abs(id) * 2", generation[0]);
        assertEquals("STORED", generation[1]);
        assertNull(SQLiteMetaDataOperations.parseGeneration(sql, "missing"));
    }
}
