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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.db.H2Dialect;
import com.github.sqljam.impexp.db.MySQLDialect;
import com.github.sqljam.impexp.db.OracleDialect;
import com.github.sqljam.impexp.db.PostgreSQLDialect;

/**
 * @Description: DialectTest verifies common behaviors of dialects
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class DialectTest {

    private static Dialect cross(Dialect dialect, DbType sourceDbType) {
        dialect.setSourceDbType(sourceDbType);
        return dialect;
    }

    @Test
    void identifiers() {
        Dialect postgresql = cross(new PostgreSQLDialect(), DbType.ORACLE);
        assertTrue(postgresql.isCrossDatabase());
        assertEquals("users", postgresql.getIdentifier("USERS"));
        assertEquals("\"MixedCase\"", postgresql.getIdentifier("MixedCase"));
        assertEquals("\"user\"", postgresql.getIdentifier("USER"));
        assertEquals("\"a b\"", postgresql.getIdentifier("a b"));
        assertEquals("id,\"order\"", postgresql.getIdentifiers("ID, ORDER"));

        Dialect oracle = cross(new OracleDialect(), DbType.POSTGRESQL);
        assertEquals("USERS", oracle.getIdentifier("users"));
        assertEquals("\"USER\"", oracle.getIdentifier("user"));

        Dialect same = new PostgreSQLDialect();
        assertFalse(same.isCrossDatabase());
        assertEquals("\"USERS\"", same.getIdentifier("USERS"));

        same.setIdentifierCase(IdentifierCase.UPPER);
        assertEquals("\"ABC\"", same.getIdentifier("abc"));
        same.setIdentifierCase(IdentifierCase.LOWER);
        assertEquals("abc", same.getIdentifier("ABC"));
        same.setIdentifierCase(IdentifierCase.KEEP);
        assertEquals("\"Abc\"", same.getIdentifier("Abc"));
        same.setIdentifierCase(null);
        assertEquals(IdentifierCase.AUTO, same.getIdentifierCase());
        assertEquals("", same.quoteIdentifier(""));
    }

    @Test
    void limitIdentifier() {
        Dialect dialect = new PostgreSQLDialect();
        String name = "x".repeat(100);
        String limited = dialect.getLimitedIdentifier(name);
        assertEquals(63, limited.length());
        assertEquals(limited, dialect.getLimitedIdentifier(name));
        assertEquals("short", dialect.getLimitedIdentifier("short"));
    }

    @Test
    void qualifiedTableName() {
        Dialect dialect = new PostgreSQLDialect();
        assertEquals("orders", dialect.getQualifiedTableName("db", "src", "orders"));
        dialect.setSourceSchemaPreserved(true);
        assertEquals("src.orders", dialect.getQualifiedTableName("db", "src", "orders"));
        dialect.setTargetSchemaName("dst");
        assertEquals("dst.orders", dialect.getQualifiedTableName("db", "src", "orders"));
        assertEquals("dst", dialect.getTargetSchemaName("src"));
        assertEquals("src.orders", dialect.getSourceTableName("db", "src", "orders"));
    }

    @Test
    void versions() {
        Dialect dialect = new PostgreSQLDialect();
        assertTrue(dialect.isVersionAtLeast(99, 0));
        dialect.setDatabaseVersion(9, 6);
        assertTrue(dialect.isVersionAtLeast(9, 5));
        assertFalse(dialect.isVersionAtLeast(9, 7));
        assertFalse(dialect.isVersionAtLeast(10, 0));
        assertTrue(dialect.isVersionAtLeast(8, 9));
    }

    @Test
    void defaultValues() {
        Dialect mysql = cross(new MySQLDialect(), DbType.SQLSERVER);
        assertEquals("0", mysql.getDefaultValue(Types.INTEGER, "int", "((0))"));
        assertEquals("'abc'", mysql.getDefaultValue(Types.VARCHAR, "varchar", "(N'abc')"));
        assertEquals("CURRENT_TIMESTAMP", mysql.getDefaultValue(Types.TIMESTAMP, "datetime", "(getdate())"));
        assertEquals("1", mysql.getDefaultValue(Types.BIT, "bit", "((1))"));
        assertNull(mysql.getDefaultValue(Types.VARCHAR, "varchar", "(newid())"));
        assertNull(mysql.getDefaultValue(Types.VARCHAR, "varchar", null));

        Dialect postgresql = cross(new PostgreSQLDialect(), DbType.MYSQL);
        assertEquals("true", postgresql.getDefaultValue(Types.BIT, "tinyint(1)", "1"));
        assertEquals("'n/a'", postgresql.getDefaultValue(Types.VARCHAR, "varchar", "'n/a'"));
        assertEquals("CURRENT_TIMESTAMP", postgresql.getDefaultValue(Types.TIMESTAMP, "datetime",
                "CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"));
        assertEquals("CURRENT_DATE", postgresql.getDefaultValue(Types.DATE, "date", "curdate()"));
        assertEquals("CURRENT_DATE", postgresql.getDefaultValue(Types.DATE, "date", "now()"));
        assertEquals("12", postgresql.getDefaultValue(Types.INTEGER, "int", "'12'"));
        assertEquals("false", postgresql.getDefaultValue(Types.BIT, "bit(1)", "b'0'"));
        assertNull(postgresql.getDefaultValue(Types.INTEGER, "int", "NULL"));

        Dialect oracle = cross(new OracleDialect(), DbType.POSTGRESQL);
        assertEquals("'abc'", oracle.getDefaultValue(Types.VARCHAR, "varchar", "'abc'::character varying"));
        assertEquals("1", oracle.getDefaultValue(Types.BOOLEAN, "bool", "true"));
        assertEquals("SYSTIMESTAMP", oracle.getDefaultValue(Types.TIMESTAMP, "timestamp", "now()"));
        assertNull(oracle.getDefaultValue(Types.BIGINT, "int8", "nextval('seq'::regclass)"));

        // Default values are kept for the same database type
        assertEquals("nextval('seq'::regclass)", new PostgreSQLDialect().getDefaultValue(Types.BIGINT, "int8",
                " nextval('seq'::regclass) "));
    }

    @Test
    void columnTypes() {
        Dialect postgresql = cross(new PostgreSQLDialect(), DbType.MYSQL);
        assertEquals("int4", postgresql.getColumnTypeName(Types.SMALLINT, "smallint unsigned", 5, 0));
        assertEquals("int8", postgresql.getColumnTypeName(Types.INTEGER, "int unsigned", 10, 0));
        assertEquals("numeric(20, 0)", postgresql.getColumnTypeName(Types.BIGINT, "bigint unsigned", 20, 0));
        assertEquals("int4", postgresql.getColumnTypeName(Types.INTEGER, "mediumint unsigned", 8, 0));
        assertEquals("text", postgresql.getColumnTypeName(Types.VARCHAR, "varchar", Integer.MAX_VALUE, 0));
        assertEquals("varchar(100)", postgresql.getColumnTypeName(Types.VARCHAR, "varchar", 100, 0));
        assertEquals("numeric", postgresql.getColumnTypeName(Types.DECIMAL, "decimal", 0, 0));
        assertEquals("text", postgresql.getColumnTypeName(1234, "unknown", 0, 0));
        assertEquals("text", postgresql.getColumnTypeName(Types.ARRAY, "array", 0, 0));

        Dialect fromOracle = cross(new PostgreSQLDialect(), DbType.ORACLE);
        assertEquals("int2", fromOracle.getColumnTypeName(Types.NUMERIC, "NUMBER", 4, 0));
        assertEquals("int4", fromOracle.getColumnTypeName(Types.NUMERIC, "NUMBER", 9, 0));
        assertEquals("int8", fromOracle.getColumnTypeName(Types.NUMERIC, "NUMBER", 19, 0));
        assertEquals("numeric(20, 0)", fromOracle.getColumnTypeName(Types.NUMERIC, "NUMBER", 20, 0));
        assertEquals("numeric", fromOracle.getColumnTypeName(Types.NUMERIC, "NUMBER", 0, -127));
        assertEquals("varchar(100)", fromOracle.getColumnTypeName(-103, "INTERVAL YEAR(2) TO MONTH", 2, 0));
        assertEquals("timestamptz", fromOracle.getColumnTypeName(-101, "TIMESTAMP(6) WITH TIME ZONE", 13, 6));
        assertEquals("float4", fromOracle.getColumnTypeName(100, "BINARY_FLOAT", 4, 0));

        Dialect fromSqlServer = cross(new PostgreSQLDialect(), DbType.SQLSERVER);
        assertEquals("int2", fromSqlServer.getColumnTypeName(Types.TINYINT, "tinyint", 3, 0));
        assertEquals("varchar(4000)", fromSqlServer.getColumnTypeName(-150, "sql_variant", 0, 0));
        assertEquals("timestamp", fromSqlServer.getColumnTypeName(-151, "datetime", 23, 3));
        assertEquals("numeric(19, 4)", fromSqlServer.getColumnTypeName(Types.DECIMAL, "money", 19, 4));
    }

    @Test
    void dataTypes() {
        assertEquals(Types.VARCHAR, Dialect.resolveDataType(-150, "sql_variant"));
        assertEquals(Types.VARBINARY, Dialect.resolveDataType(Types.VARBINARY, "hierarchyid"));
        assertEquals(Types.TIMESTAMP, Dialect.resolveDataType(-151, "datetime"));
        assertEquals(Types.DATE, Dialect.resolveDataType(Types.DATE, "datetime"));
        assertEquals(Types.DECIMAL, Dialect.resolveDataType(Types.DECIMAL, "smallmoney"));
        assertEquals(Types.LONGVARBINARY, Dialect.resolveDataType(Types.VARBINARY, "geography"));
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, Dialect.normalizeDataType(-155));
        assertEquals(Types.TIMESTAMP, Dialect.normalizeDataType(-102));
        assertEquals(Types.DOUBLE, Dialect.normalizeDataType(101));
        assertEquals(Types.DECIMAL, Dialect.normalizeDataType(-148));
        assertEquals(Types.VARCHAR, Dialect.normalizeDataType(-8));
        assertEquals(Types.LONGVARBINARY, Dialect.normalizeDataType(-157));
        assertEquals(Types.INTEGER, Dialect.normalizeDataType(Types.INTEGER));
    }

    @Test
    void jdbcValues() {
        Dialect postgresql = cross(new PostgreSQLDialect(), DbType.MYSQL);
        assertEquals(170L, postgresql.getJdbcValue(new byte[]{(byte) 0xAA}, Types.BIT, "bit(8)", 8));
        assertEquals(5L, postgresql.getJdbcValue("101", Types.BIT, "bit(3)", 3));
        assertEquals(1L, postgresql.getJdbcValue(true, Types.BIT, "bit(2)", 2));
        assertEquals(true, postgresql.getJdbcValue(1, Types.BOOLEAN, "boolean", 1));
        assertEquals(false, postgresql.getJdbcValue(0L, Types.BIT, "bit", 1));
        assertEquals("[1, 2]", postgresql.getJdbcValue(new Object[]{1, 2}, Types.ARRAY, "array", 0));
        assertEquals(LocalTime.of(12, 0), postgresql.getJdbcValue(OffsetTime.of(12, 0, 0, 0, ZoneOffset.UTC),
                Types.TIME_WITH_TIMEZONE, "timetz", 0));
        assertEquals(LocalTime.of(12, 0), postgresql.getJdbcValue("12:00:00+08", Types.TIME, "timetz", 0));
        OffsetDateTime offsetDateTime = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        assertEquals(offsetDateTime.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime(),
                postgresql.getJdbcValue(offsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz", 0));
        // Fractional seconds are truncated to the precision of target database
        assertEquals(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000), postgresql.getJdbcValue(
                LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_900), Types.TIMESTAMP, "datetime2", 7));
        assertEquals(LocalTime.of(1, 2, 3, 123_456_000), postgresql.getJdbcValue(
                LocalTime.of(1, 2, 3, 123_456_789), Types.TIME, "time", 7));
        assertNull(postgresql.getJdbcValue(null, Types.INTEGER, "int", 0));

        // Text values of temporal columns (SQLite)
        assertEquals(LocalDate.of(2024, 2, 29), postgresql.getJdbcValue("2024-02-29", Types.DATE, "DATE", 0));
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0), postgresql.getJdbcValue("2024-02-29", Types.TIMESTAMP,
                "DATETIME", 0));
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 123_000_000), postgresql.getJdbcValue(
                "2024-02-29 23:59:59.123", Types.TIMESTAMP, "DATETIME", 0));
        assertEquals(LocalDate.of(2024, 2, 29), postgresql.getJdbcValue("2024-02-29T10:00:00", Types.DATE,
                "DATE", 0));
        assertEquals(LocalTime.of(23, 59, 59), postgresql.getJdbcValue("23:59:59", Types.TIME, "TIME", 0));
        assertEquals(new Timestamp(1700000000000L).toLocalDateTime(), postgresql.getJdbcValue("1700000000000",
                Types.TIMESTAMP, "DATETIME", 0));
        assertEquals("not a date", postgresql.getJdbcValue("not a date", Types.DATE, "DATE", 0));
    }

    @Test
    void literals() {
        Dialect dialect = new H2Dialect();
        assertEquals("NULL", dialect.getStringValue(null, null, "t", "c", null));
        assertEquals("1.5", dialect.getStringValue(null, null, "t", "c", new BigDecimal("1.50").stripTrailingZeros()));
        assertEquals("-1.0E100", dialect.getStringValue(null, null, "t", "c", -1E100));
        assertEquals("2.5", dialect.getStringValue(null, null, "t", "c", 2.5f));
        assertEquals("NULL", dialect.getStringValue(null, null, "t", "c", Double.NaN));
        assertEquals("42", dialect.getStringValue(null, null, "t", "c", 42L));
        assertEquals("'x'", dialect.getStringValue(null, null, "t", "c", 'x'));
        assertEquals("'O''Brien'", dialect.getStringValue(null, null, "t", "c", "O'Brien"));
        assertEquals("true", dialect.getStringValue(null, null, "t", "c", true));
        assertEquals("X'0AFF'", dialect.getStringValue(null, null, "t", "c", new byte[]{10, -1}));
        assertEquals("TIMESTAMP '2024-02-29 23:59:59.123'", dialect.getStringValue(null, null, "t", "c",
                LocalDateTime.of(2024, 2, 29, 23, 59, 59, 123_000_000)));
        assertEquals("TIMESTAMP '2024-02-29 00:00:00'", dialect.getStringValue(null, null, "t", "c",
                Timestamp.valueOf("2024-02-29 00:00:00")));
        assertEquals("DATE '2024-02-29'", dialect.getStringValue(null, null, "t", "c", LocalDate.of(2024, 2, 29)));
        assertEquals("DATE '2024-02-29'", dialect.getStringValue(null, null, "t", "c",
                java.sql.Date.valueOf("2024-02-29")));
        assertEquals("TIME '10:20:30.5'", dialect.getStringValue(null, null, "t", "c",
                LocalTime.of(10, 20, 30, 500_000_000)));
        assertEquals("TIME '10:20:30'", dialect.getStringValue(null, null, "t", "c",
                java.sql.Time.valueOf("10:20:30")));
        assertEquals("'2024-01-01 08:00:00+08:00'", dialect.getStringValue(null, null, "t", "c",
                OffsetDateTime.of(2024, 1, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8))));
        assertEquals("'12:00:00+08:00'", dialect.getStringValue(null, null, "t", "c",
                OffsetTime.of(12, 0, 0, 0, ZoneOffset.ofHours(8))));
        assertEquals("ARRAY[1, 'a']", dialect.getStringValue(null, null, "t", "c", new Object[]{1, "a"}));
        UUID uuid = UUID.randomUUID();
        assertEquals("'" + uuid + "'", dialect.getStringValue(null, null, "t", "c", uuid));
        assertTrue(dialect.getStringValue(null, null, "t", "c", new java.util.Date(0)).startsWith("TIMESTAMP '"));
        assertTrue(dialect.getStringValue(null, null, "t", "c",
                java.time.ZonedDateTime.now()).startsWith("TIMESTAMP '"));
        assertEquals("X''", dialect.getEmptyLobLiteral(true));
        assertEquals("''", dialect.getEmptyLobLiteral(false));
    }

    @Test
    void statements() {
        Dialect dialect = cross(new PostgreSQLDialect(), DbType.MYSQL);
        assertEquals("CONSTRAINT orders_pkey PRIMARY KEY (id,\"order\")",
                dialect.getCreatePrimaryKeyStatement(null, null, "orders", "id,order", "PRIMARY"));
        assertEquals("ALTER TABLE orders ADD CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id)"
                        + " ON DELETE CASCADE ON UPDATE SET NULL",
                dialect.getCreateForeignKeyStatement(null, null, "orders", "fk_orders_user", new String[]{"user_id"},
                        "users", new String[]{"id"}, "SET NULL", "CASCADE"));
        assertEquals("INSERT INTO orders(id,name) VALUES (?,?)",
                dialect.getInsertTableStatement(null, null, "orders", new String[]{"id", "name"}));
        assertEquals("SELECT id,name FROM src.orders",
                dialect.getSelectTableStatement(null, "src", "orders", new String[]{"id", "name"}));
        assertEquals("SELECT COUNT(*) FROM orders", dialect.getCountTableStatement(null, null, "orders"));
        assertEquals("SELECT max(id) FROM src.orders", dialect.getSelectMaxColumnStatement(null, "src", "orders",
                "id"));
        assertEquals("SELECT * FROM t ORDER BY id LIMIT 10 OFFSET 20",
                dialect.getPageStatement("SELECT * FROM t", "id", 10, 20));
        assertEquals("idx_orders_user_id", dialect.getIndexNameStatement(null, null, "orders",
                new String[]{"user_id"}, false, null));
        assertEquals("uidx_orders_code", dialect.getIndexNameStatement(null, null, "orders", new String[]{"code"},
                true, null));
        assertNull(dialect.getDefinePartitionTableStatement(null, null, "t", "RANGE", "id", new java.util.HashMap<>()));
        assertNull(dialect.getSessionStatements());
        assertNull(dialect.getStatementBeforeInsert(null, null, "t", true));
        assertNull(dialect.getStatementAfterInsert(null, null, "t", true));
        assertTrue(dialect.getReservedWords().contains("select"));
        assertEquals(Integer.valueOf(1), dialect.getJdbcValue(1));
        assertArrayEquals(new byte[0], (byte[]) dialect.getJdbcValue(new byte[0]));
    }

    @Test
    void versionDialects() {
        assertEquals("MySQL55Dialect", DbType.MYSQL.createDialect(5, 5).getClass().getSimpleName());
        assertEquals("MySQL56Dialect", DbType.MYSQL.createDialect(5, 6).getClass().getSimpleName());
        assertEquals("MySQL57Dialect", DbType.MYSQL.createDialect(5, 7).getClass().getSimpleName());
        assertEquals("MySQLDialect", DbType.MYSQL.createDialect(8, 0).getClass().getSimpleName());
        assertEquals("PostgreSQL9Dialect", DbType.POSTGRESQL.createDialect(9, 6).getClass().getSimpleName());
        assertEquals("PostgreSQL10Dialect", DbType.POSTGRESQL.createDialect(11, 0).getClass().getSimpleName());
        assertEquals("PostgreSQLDialect", DbType.POSTGRESQL.createDialect(16, 0).getClass().getSimpleName());
        assertEquals("Oracle11gDialect", DbType.ORACLE.createDialect(11, 2).getClass().getSimpleName());
        assertEquals("Oracle12cDialect", DbType.ORACLE.createDialect(12, 1).getClass().getSimpleName());
        assertEquals("OracleDialect", DbType.ORACLE.createDialect(12, 2).getClass().getSimpleName());
        assertEquals("OracleDialect", DbType.ORACLE.createDialect(23, 0).getClass().getSimpleName());
        assertEquals("SQLServer2008Dialect", DbType.SQLSERVER.createDialect(10, 50).getClass().getSimpleName());
        assertEquals("SQLServerDialect", DbType.SQLSERVER.createDialect(16, 0).getClass().getSimpleName());
        assertEquals("H2Dialect", DbType.H2.createDialect(2, 3).getClass().getSimpleName());
        assertEquals("SQLiteDialect", DbType.SQLITE.createDialect(3, 46).getClass().getSimpleName());
        assertEquals(11, DbType.ORACLE.createDialect(11, 2).getDatabaseMajorVersion());

        // Settings are copied to the dialect of the version
        Dialect dialect = DbType.ORACLE.createDialect();
        dialect.setSourceDbType(DbType.MYSQL);
        dialect.setIdentifierCase(IdentifierCase.LOWER);
        dialect.setTargetCatalogName("c");
        dialect.setTargetSchemaName("s");
        dialect.setSourceSchemaPreserved(true);
        Dialect oracle11g = dialect.forVersion(11, 2);
        assertEquals(DbType.MYSQL, oracle11g.getSourceDbType());
        assertEquals(IdentifierCase.LOWER, oracle11g.getIdentifierCase());
        assertEquals("c", oracle11g.getTargetCatalogName());
        assertEquals("s", oracle11g.getTargetSchemaName());
        assertTrue(oracle11g.isSourceSchemaPreserved());
        assertEquals(2, oracle11g.getDatabaseMinorVersion());
    }
}
