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
package com.github.sqljam.access;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import javax.sql.rowset.serial.SerialBlob;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.db.AccessDialect;

/**
 * @Description: AccessDialectTest
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class AccessDialectTest {

    @Test
    void ddl() {
        AccessDialect dialect = new AccessDialect();
        assertEquals(DbType.ACCESS, dialect.getDbType());
        assertNull(dialect.getCreateUserStatement("u", "p"));
        assertNull(dialect.getCreateDatabaseStatement("db", "u"));
        assertNull(dialect.getCreateSchemaStatement(null, "s", "u"));
        assertEquals("CREATE TABLE t", dialect.getCreateTableStatement("c", "s", "t"));
        assertEquals("t", dialect.getSourceTableName("c", "s", "t"));
        assertEquals("`order`", dialect.getSourceTableName("c", "s", "order"));
        assertEquals("DROP TABLE t", dialect.getDropTableStatement(null, null, "t"));
        assertEquals("DELETE FROM t", dialect.getEmptyTableStatement(null, null, "t"));
        assertEquals("CREATE UNIQUE INDEX ux ON t (a,b)", dialect.getCreateIndexStatement(null, null, "t", false,
                new String[]{"a", "b"}, "ux", true, null).replace(", ", ","));
        assertEquals("CREATE INDEX ix ON t (a)", dialect.getCreateIndexStatement(null, null, "t", false,
                new String[]{"a"}, "ix", false, null));
        assertTrue(dialect.isForeignKeyOrderRequired());
        assertFalse(dialect.isLobSeparationSupported());
        assertFalse(dialect.isSequenceSupported());
        assertTrue(dialect.isSingleIncrementalColumn());
        assertEquals("id COUNTER NOT NULL", dialect.getIncrementalColumnStatement(null, null, "t", "id",
                Types.INTEGER, "INTEGER", 10, 0, null, false).replaceAll("\\s+", " "));
        // Columns related to a BIGINT identity are BIGINT
        assertEquals("id BIGINT NOT NULL", dialect.getIncrementalColumnStatement(null, null, "t", "id",
                Types.BIGINT, "BIGINT", 19, 0, null, false).replaceAll("\\s+", " "));
        assertEquals("c LONG", dialect.getGeneratedColumnStatement(null, null, "t", "c", Types.INTEGER, "INTEGER",
                10, 0, "a * 2", true).replaceAll("\\s+", " "));
        assertNull(dialect.getResetIdentityStatement(null, null, "t", "id", 10));
        assertNull(dialect.getCreateCommentStatement(null, null, "t", "c", "x"));
        assertNull(dialect.getCreateTableCommentStatement(null, null, "t", "x"));
        assertNull(dialect.getSequenceNameStatement(null, null, "t", "id"));
        assertNull(dialect.getDefaultSequenceName(null, null, "t", "id"));
        assertNull(dialect.getAlterSequenceStartValueStatement(null, null, "t", "s", 1));
        assertNull(dialect.getCreatePartitionTableStatement(null, null, "p", "t"));
        assertNull(dialect.getStatementBeforeInsert(null, null, "t", false));
        assertNull(dialect.getStatementAfterInsert(null, null, "t", false));
        assertArrayEquals(new String[]{"DISABLE AUTOINCREMENT ON t"}, dialect.getStatementBeforeInsert(null, null,
                "t", true));
        assertArrayEquals(new String[]{"ENABLE AUTOINCREMENT ON t"}, dialect.getStatementAfterInsert(null, null,
                "t", true));
        assertEquals("`order`", dialect.getIdentifier("order"));
        assertEquals(64, dialect.getMaxIdentifierLength());
        assertEquals(3, dialect.getMaxFractionalSecondsPrecision());
    }

    @Test
    void types() {
        AccessDialect dialect = new AccessDialect();
        dialect.setSourceDbType(DbType.POSTGRESQL);
        assertEquals("YESNO", dialect.getColumnTypeName(Types.BOOLEAN, "bool", 1, 0));
        assertEquals("SMALLINT", dialect.getColumnTypeName(Types.SMALLINT, "int2", 5, 0));
        assertEquals("LONG", dialect.getColumnTypeName(Types.INTEGER, "int4", 10, 0));
        assertEquals("BIGINT", dialect.getColumnTypeName(Types.BIGINT, "int8", 19, 0));
        assertEquals("SINGLE", dialect.getColumnTypeName(Types.REAL, "float4", 8, 0));
        assertEquals("DOUBLE", dialect.getColumnTypeName(Types.DOUBLE, "float8", 17, 0));
        assertEquals("DECIMAL(10,2)", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 10, 2));
        assertEquals("DECIMAL(18,0)", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 18, 0));
        assertEquals("DECIMAL(21,1)", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 20, 0));
        assertEquals("DECIMAL(28,1)", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 28, 0));
        assertEquals("TEXT(100)", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 40, 2));
        assertEquals("DECIMAL(28,10)", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 0, 0));
        assertEquals("DATETIME", dialect.getColumnTypeName(Types.TIMESTAMP, "timestamp", 29, 6));
        assertEquals("DATETIME", dialect.getColumnTypeName(Types.DATE, "date", 13, 0));
        assertEquals("DATETIME", dialect.getColumnTypeName(Types.TIME, "time", 15, 6));
        assertEquals("DATETIME", dialect.getColumnTypeName(Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz", 35, 6));
        assertEquals("BIGINT", dialect.getColumnTypeName(Types.BIT, "bit", 64, 0));
        assertEquals("TEXT(20)", dialect.getColumnTypeName(Types.VARCHAR, "varchar", 20, 0));
        assertEquals("MEMO", dialect.getColumnTypeName(Types.VARCHAR, "varchar", 300, 0));
        assertEquals("MEMO", dialect.getColumnTypeName(Types.VARCHAR, "text", Integer.MAX_VALUE, 0));
        assertEquals("VARBINARY(16)", dialect.getColumnTypeName(Types.VARBINARY, "varbinary", 16, 0));
        assertEquals("OLE", dialect.getColumnTypeName(Types.BINARY, "bytea", Integer.MAX_VALUE, 0));
        assertEquals("TEXT(38)", dialect.getColumnTypeName(Types.OTHER, "uuid", 0, 0));
        assertEquals("MEMO", dialect.getColumnTypeName(Types.OTHER, "jsonb", 0, 0));
        assertEquals("TEXT(50)", dialect.getColumnTypeName(Types.OTHER, "inet", 0, 0));
        assertEquals("MEMO", dialect.getColumnTypeName(Types.ARRAY, "_int4", 0, 0));
        assertEquals("LONG", dialect.getColumnTypeName(Types.BIT, "bit", 8, 0));

        AccessDialect mysql = new AccessDialect();
        mysql.setSourceDbType(DbType.MYSQL);
        assertEquals("TEXT(255)", mysql.getColumnTypeName(Types.CHAR, "enum", 5, 0));
        assertEquals("MEMO", mysql.getColumnTypeName(Types.LONGVARCHAR, "mediumtext", 16777215, 0));

        // Files of Access 2010 keep 32-bit integers only
        Dialect access2010 = dialect.forVersion(14, 0);
        assertEquals("DECIMAL(28,1)", access2010.getColumnTypeName(Types.BIGINT, "int8", 19, 0));
        assertEquals("BIGINT", dialect.forVersion(16, 0).getColumnTypeName(Types.BIGINT, "int8", 19, 0));
    }

    @Test
    void readsFileVersion() throws SQLException {
        AccessDialect dialect = new AccessDialect();
        assertArrayEquals(new int[]{14, 0}, dialect.readDatabaseVersion(metaData("V2010 [VERSION_14]")));
        assertArrayEquals(new int[]{16, 0}, dialect.readDatabaseVersion(metaData("V2016 [VERSION_16]")));
        assertArrayEquals(new int[]{-1, -1}, dialect.readDatabaseVersion(metaData(null)));
    }

    @Test
    void missingDropErrors() {
        AccessDialect dialect = new AccessDialect();
        SQLException missing = new SQLException("UCAExc:::5.1.8", new SQLException(
                "user lacks privilege or object not found: T"));
        assertTrue(dialect.isMissingDropError("DROP TABLE t", missing));
        assertTrue(dialect.isMissingDropError(" delete from t", new SQLException("Table t does not exist")));
        assertFalse(dialect.isMissingDropError("INSERT INTO t VALUES (1)", missing));
        assertFalse(dialect.isMissingDropError("DROP TABLE t", new SQLException("Reference to missing table")));
        assertFalse(dialect.isMissingDropError(null, missing));
    }

    @Test
    void dml() {
        AccessDialect dialect = new AccessDialect();
        assertEquals("MERGE INTO t tgt USING (VALUES (1,'a')) src (id,v) ON tgt.id = src.id"
                        + " WHEN MATCHED THEN UPDATE SET tgt.v = src.v"
                        + " WHEN NOT MATCHED THEN INSERT (id,v) VALUES (src.id, src.v)",
                dialect.getUpsertStatement(null, null, "t", new String[]{"id", "v"}, new String[]{"id"},
                        new String[]{"v"}, new Object[]{"1", "'a'"}));
        assertEquals("MERGE INTO t tgt USING (VALUES (1)) src (id) ON tgt.id = src.id"
                        + " WHEN NOT MATCHED THEN INSERT (id) VALUES (src.id)",
                dialect.getUpsertStatement(null, null, "t", new String[]{"id"}, new String[]{"id"},
                        new String[0], new Object[]{"1"}));
        assertEquals("NOW()", dialect.getCurrentTimestampExpression());
        assertEquals("DATE()", dialect.getCurrentDateExpression());
        assertEquals("TRUE", dialect.getBooleanLiteral(true));
        assertEquals("FALSE", dialect.getBooleanLiteral(false));
        assertEquals("'2024-02-29'", dialect.getDateLiteral(LocalDate.of(2024, 2, 29)));
        assertTrue(dialect.getTimestampLiteral(LocalDateTime.of(2024, 2, 29, 23, 59, 59)).startsWith(
                "'2024-02-29 23:59:59"));
        assertTrue(dialect.getTimeLiteral(LocalTime.of(8, 30)).startsWith("'1899-12-30 08:30:00"));
    }

    @Test
    void literalsOfNumbers() {
        AccessDialect dialect = new AccessDialect();
        assertEquals("-12345678901234.5", dialect.getStringValue(null, null, "t", "c", -1.23456789012345E13));
        assertEquals("1" + "0".repeat(100), dialect.getStringValue(null, null, "t", "c", 1.0E100));
        assertEquals("3.5", dialect.getStringValue(null, null, "t", "c", 3.5f));
        assertEquals("NULL", dialect.getStringValue(null, null, "t", "c", Double.NaN));
        assertEquals("1.50", dialect.getStringValue(null, null, "t", "c", new BigDecimal("1.50")));
        assertEquals("'a'", dialect.getStringValue(null, null, "t", "c", "a"));
    }

    @Test
    void jdbcValues() {
        AccessDialect dialect = new AccessDialect();
        assertEquals(LocalDateTime.of(1899, 12, 30, 8, 30), dialect.getJdbcValue(LocalTime.of(8, 30), Types.TIME,
                "time", 8));
        assertEquals(1, dialect.getJdbcValue(1, Types.INTEGER, "int4", 10));
        // Parameters of MERGE: date times as timestamps, bytes by a cast
        assertTrue(dialect.isTargetTypeConversionRequired());
        assertEquals(Timestamp.valueOf("2024-02-29 23:59:59"), dialect.getTargetJdbcValue(LocalDateTime.of(2024, 2,
                29, 23, 59, 59), "TIMESTAMP"));
        assertEquals(Timestamp.valueOf("2024-02-29 00:00:00"), dialect.getTargetJdbcValue(LocalDate.of(2024, 2, 29),
                "TIMESTAMP"));
        assertEquals(Timestamp.valueOf("1899-12-30 08:30:00"), dialect.getTargetJdbcValue(LocalTime.of(8, 30),
                "TIMESTAMP"));
        assertEquals("a", dialect.getTargetJdbcValue("a", "VARCHAR"));
        assertEquals("CAST(? AS BLOB)", dialect.getParameterMarker("BLOB"));
        assertEquals("CAST(? AS VARBINARY(255))", dialect.getParameterMarker("varbinary"));
        assertEquals("?", dialect.getParameterMarker("VARCHAR"));
        assertEquals("?", dialect.getParameterMarker(null));
    }

    @Test
    void columnValues() throws SQLException {
        AccessDialect dialect = new AccessDialect();
        byte[] bytes = {1, 2, 3};
        // Bytes of an OLE column are the key of UCanAccess, the blob is the content
        assertArrayEquals(bytes, (byte[]) dialect.getColumnValue(resultSet(Map.of("getBlob", new SerialBlob(bytes),
                "getBytes", new byte[]{9})), 1, Types.BLOB, "BLOB"));
        assertNull(dialect.getColumnValue(resultSet(Map.of()), 1, Types.LONGVARBINARY, "BLOB"));
        // SINGLE of a file: NUMERIC(100,7)
        assertEquals(3.5, dialect.getColumnValue(resultSet(Map.of("getObject", new BigDecimal("3.5000000"),
                "getPrecision", 100)), 1, Types.NUMERIC, "NUMERIC"));
        assertEquals(new BigDecimal("3.5000000"), dialect.getColumnValue(resultSet(Map.of("getObject",
                new BigDecimal("3.5000000"), "getPrecision", 10)), 1, Types.NUMERIC, "NUMERIC"));
        assertEquals("x", dialect.getColumnValue(resultSet(Map.of("getObject", "x")), 1, Types.VARCHAR, "VARCHAR"));
    }

    private static DatabaseMetaData metaData(String productVersion) {
        return (DatabaseMetaData) Proxy.newProxyInstance(AccessDialectTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) ->
                        "getDatabaseProductVersion".equals(method.getName()) ? productVersion : null);
    }

    /**
     * Result set returning the values of the methods by name, e.g. getBlob, and getPrecision of its metadata
     */
    private static ResultSet resultSet(Map<String, Object> values) {
        ResultSetMetaData metaData = (ResultSetMetaData) Proxy.newProxyInstance(
                AccessDialectTest.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> values.getOrDefault(method.getName(), 0));
        return (ResultSet) Proxy.newProxyInstance(AccessDialectTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaData;
                    }
                    if ("wasNull".equals(method.getName())) {
                        return false;
                    }
                    return values.get(method.getName());
                });
    }
}
