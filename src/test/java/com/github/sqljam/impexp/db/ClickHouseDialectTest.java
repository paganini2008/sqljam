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
package com.github.sqljam.impexp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;

/**
 * @Description: ClickHouseDialectTest verifies ddl, literals and values of ClickHouse and the type mapping of
 *               ClickHouse columns
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ClickHouseDialectTest {

    private static ClickHouseDialect cross(DbType sourceDbType) {
        ClickHouseDialect dialect = new ClickHouseDialect();
        dialect.setSourceDbType(sourceDbType);
        return dialect;
    }

    private static Map<String, Object> column(String type) {
        Map<String, Object> columnInfo = new HashMap<>();
        ClickHouseMetaDataOperations.normalizeType(columnInfo, type);
        return columnInfo;
    }

    @Test
    void mapsColumnsOfOtherDatabases() {
        ClickHouseDialect dialect = cross(DbType.MYSQL);
        assertEquals("Int32", dialect.getColumnTypeName(Types.INTEGER, "int", 10, 0));
        assertEquals("Decimal(12,2)", dialect.getColumnTypeName(Types.DECIMAL, "decimal", 12, 2));
        assertEquals("String", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 0, 0));
        assertEquals("DateTime64(3)", dialect.getColumnTypeName(Types.TIMESTAMP, "datetime", 23, 3));
        assertEquals("Date32", dialect.getColumnTypeName(Types.DATE, "date", 10, 0));
        assertEquals("String", dialect.getColumnTypeName(Types.TIME, "time", 8, 0));
        assertEquals("String", dialect.getColumnTypeName(Types.BLOB, "blob", 65535, 0));
        assertEquals("Bool", dialect.getColumnTypeName(Types.BIT, "bit", 1, 0));
        assertEquals("Int64", dialect.getColumnTypeName(Types.BIT, "bit(8)", 8, 0));
        assertEquals("UUID", cross(DbType.POSTGRESQL).getColumnTypeName(Types.OTHER, "uuid", 0, 0));
        assertEquals("String", cross(DbType.POSTGRESQL).getColumnTypeName(Types.ARRAY, "_int4", 0, 0));
        assertEquals("String", cross(DbType.POSTGRESQL).getColumnTypeName(Types.OTHER, "jsonb", 0, 0));
        assertEquals("Int16", dialect.getColumnTypeName(Types.DATE, "year", 4, 0));
    }

    @Test
    void createsMergeTreeTables() {
        ClickHouseDialect dialect = cross(DbType.MYSQL);
        assertEquals("id                            Int32", dialect.getColumnStatement(null, null, "t", "ID",
                Types.INTEGER, "int", 10, 0, null, false));
        assertEquals("name                          Nullable(String)               DEFAULT 'n/a' COMMENT 'It''s'",
                dialect.getColumnStatement(null, null, "t", "name", Types.VARCHAR, "varchar", 10, 0, "'n/a'", true,
                        "It's"));
        assertEquals("id                            Int64", dialect.getIncrementalColumnStatement(null, null, "t",
                "id", Types.BIGINT, "bigint", 19, 0, null, true));
        assertEquals("PRIMARY KEY (id,name)", dialect.getCreatePrimaryKeyStatement(null, null, "t", "ID,NAME",
                "PRIMARY"));
        assertEquals("ENGINE = MergeTree ORDER BY (id, name) SETTINGS allow_nullable_key = 1",
                dialect.getTableOptions(null, null, "t", Map.of(), List.of("ID", "NAME")));
        assertEquals("ENGINE = MergeTree ORDER BY tuple() SETTINGS allow_nullable_key = 1",
                dialect.getTableOptions(null, null, "t", null, List.of()));
        assertNull(dialect.getCreateIndexStatement(null, null, "t", false, new String[]{"a"}, "idx", true, null));
        assertNull(dialect.getCreateForeignKeyStatement(null, null, "t", "fk", new String[]{"a"}, "r",
                new String[]{"id"}, null, "CASCADE"));
        assertNull(dialect.getResetIdentityStatement(null, "app", "t", "id", 10));
        assertFalse(dialect.isSequenceSupported());
        assertFalse(dialect.isLobSeparationSupported());
        assertFalse(dialect.isArrayParameterSupported());
        assertEquals("DROP TABLE IF EXISTS app.t", withSchema(dialect).getDropTableStatement(null, "app", "t"));
        assertEquals("ALTER TABLE app.t COMMENT COLUMN c 'x'", withSchema(dialect).getCreateCommentStatement(null,
                "app", "t", "c", "x"));
        assertEquals("ALTER TABLE app.t MODIFY COMMENT 'x'", withSchema(dialect).getCreateTableCommentStatement(null,
                "app", "t", "x"));
        assertEquals("CREATE DATABASE IF NOT EXISTS app", dialect.getCreateSchemaStatement(null, "app", null));
        assertEquals("default", dialect.getDefaultSchemaName(null));
        assertNull(dialect.getCreateUserStatement("u", "p"));
        assertNull(dialect.getCreateDatabaseStatement("db", null));
    }

    private static ClickHouseDialect withSchema(ClickHouseDialect dialect) {
        dialect.setTargetSchemaName("app");
        return dialect;
    }

    @Test
    void keepsTablesOfClickHouse() {
        ClickHouseDialect dialect = new ClickHouseDialect();
        assertFalse(dialect.isCrossDatabase());
        assertEquals("Map(String, Int32)", dialect.getColumnTypeName(Types.VARCHAR, "Map(String, Int32)", 0, 0));
        assertEquals("c                             LowCardinality(Nullable(String))", dialect.getColumnStatement(
                null, null, "t", "c", Types.VARCHAR, "LowCardinality(String)", 0, 0, null, true));
        assertEquals("c                             Array(Int32)", dialect.getColumnStatement(null, null, "t", "c",
                Types.VARCHAR, "Array(Int32)", 0, 0, null, true));
        assertNull(dialect.getCreatePrimaryKeyStatement(null, null, "t", "id", null));
        assertEquals("ENGINE = ReplacingMergeTree ORDER BY id", dialect.getTableOptions(null, null, "t",
                Map.of("ENGINE_FULL", "ReplacingMergeTree ORDER BY id"), List.of("id")));
        assertEquals("g Int32 MATERIALIZED id * 2", dialect.getGeneratedColumnStatement(null, null, "t", "g",
                Types.INTEGER, "Int32", 10, 0, "id * 2", true));
        assertEquals("a Int32 ALIAS id + 1", dialect.getGeneratedColumnStatement(null, null, "t", "a",
                Types.INTEGER, "Int32", 10, 0, "id + 1", false));
        assertEquals("Nullable(Int32)", ClickHouseDialect.toNullable("Nullable(Int32)"));
        assertEquals("Map(String, Int32)", ClickHouseDialect.toNullable("Map(String, Int32)"));
    }

    @Test
    void writesLiteralsAndValues() {
        ClickHouseDialect dialect = cross(DbType.MYSQL);
        assertEquals("'O''Brien \\\\ x'", dialect.getStringLiteral("O'Brien \\ x"));
        assertEquals("NULL", dialect.getStringLiteral(null));
        assertEquals("unhex('DEAD')", dialect.getBinaryLiteral(new byte[]{(byte) 0xDE, (byte) 0xAD}));
        assertEquals("toDateTime64('2024-01-02 03:04:05.123', 6)",
                dialect.getTimestampLiteral(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_000_000)));
        assertEquals("toDate32('2024-02-29')", dialect.getDateLiteral(LocalDate.of(2024, 2, 29)));
        assertEquals("'12:00:01'", dialect.getTimeLiteral(LocalTime.of(12, 0, 1)));
        assertEquals("now64()", dialect.getCurrentTimestampExpression());
        assertEquals("today()", dialect.getCurrentDateExpression());
        // Temporal values are bound as text, their wall clock time is kept
        assertEquals("2024-01-02 03:04:05.123456", dialect.getJdbcValue(
                LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_789), Types.TIMESTAMP, "datetime", 26));
        assertEquals("2024-01-02 03:04:05", dialect.getJdbcValue(Timestamp.valueOf("2024-01-02 03:04:05"),
                Types.TIMESTAMP, "datetime", 19));
        assertEquals("2024-02-29", dialect.getJdbcValue(Date.valueOf("2024-02-29"), Types.DATE, "date", 10));
        assertEquals("12:00:01", dialect.getJdbcValue(Time.valueOf("12:00:01"), Types.TIME, "time", 8));
        UUID uuid = UUID.randomUUID();
        assertEquals(uuid.toString(), dialect.getJdbcValue(uuid, Types.OTHER, "uuid", 36));
        ClickHouseDialect same = new ClickHouseDialect();
        assertEquals("12:00:00+08:00", same.getJdbcValue(OffsetTime.of(12, 0, 0, 0, ZoneOffset.ofHours(8)),
                Types.TIME_WITH_TIMEZONE, "String", 0));
        assertEquals(new BigDecimal("1.5"), dialect.getJdbcValue(new BigDecimal("1.5"), Types.DECIMAL, "decimal", 2));
    }

    @Test
    void readsTextOfSpecialTypes() {
        ClickHouseDialect dialect = new ClickHouseDialect();
        assertEquals("toString(m) AS m", dialect.getSelectColumnExpression("m", "Map(String, Int32)"));
        assertEquals("toString(v) AS v", dialect.getSelectColumnExpression("v", "Decimal(76, 10)"));
        assertEquals("d", dialect.getSelectColumnExpression("d", "Decimal(38, 10)"));
        assertEquals("s", dialect.getSelectColumnExpression("s", "String"));
        // Strings are read as bytes for ClickHouse targets
        dialect.setReadTargetDbType(DbType.CLICKHOUSE);
        assertEquals("hex(s) AS s", dialect.getSelectColumnExpression("s", "String"));
        assertEquals("hex(f) AS f", dialect.getSelectColumnExpression("f", "FixedString(4)"));
        assertEquals("SELECT 1 ORDER BY tuple(*) LIMIT 10 OFFSET 0", dialect.getPageStatement("SELECT 1", null, 10,
                0));
        assertEquals("SELECT 1 ORDER BY id LIMIT 10 OFFSET 20", dialect.getPageStatement("SELECT 1", "id", 10, 20));
    }

    @Test
    void mapsClickHouseTypes() {
        Map<String, Object> nullableString = column("Nullable(String)");
        assertEquals(Types.VARCHAR, nullableString.get("DATA_TYPE"));
        assertEquals(Integer.MAX_VALUE, nullableString.get("COLUMN_SIZE"));
        assertEquals("YES", nullableString.get("IS_NULLABLE"));
        assertEquals("String", nullableString.get("TYPE_NAME"));
        Map<String, Object> low = column("LowCardinality(Nullable(String))");
        assertEquals("LowCardinality(String)", low.get("TYPE_NAME"));
        assertEquals("YES", low.get("IS_NULLABLE"));
        Map<String, Object> decimal = column("Decimal(18, 4)");
        assertEquals(Types.DECIMAL, decimal.get("DATA_TYPE"));
        assertEquals(18, decimal.get("COLUMN_SIZE"));
        assertEquals(4, decimal.get("DECIMAL_DIGITS"));
        // Decimals wider than DECIMAL(38) are text of other databases
        assertEquals(Types.VARCHAR, column("Decimal256(10)").get("DATA_TYPE"));
        assertEquals(Types.VARCHAR, column("Decimal(76, 10)").get("DATA_TYPE"));
        assertEquals(9, column("Decimal32(2)").get("COLUMN_SIZE"));
        assertEquals(Types.TIMESTAMP, column("DateTime64(6, 'UTC')").get("DATA_TYPE"));
        assertEquals(6, column("DateTime64(6, 'UTC')").get("DECIMAL_DIGITS"));
        assertEquals(0, column("DateTime('UTC')").get("DECIMAL_DIGITS"));
        assertEquals(Types.CHAR, column("FixedString(4)").get("DATA_TYPE"));
        assertEquals(4, column("FixedString(4)").get("COLUMN_SIZE"));
        assertEquals("UUID", column("UUID").get("TYPE_NAME"));
        assertEquals(15, column("IPv4").get("COLUMN_SIZE"));
        assertEquals(45, column("IPv6").get("COLUMN_SIZE"));
        assertEquals(6, column("Enum8('red' = 1, 'gre''en' = 2)").get("COLUMN_SIZE"));
        assertEquals(Types.NUMERIC, column("UInt64").get("DATA_TYPE"));
        assertEquals(20, column("UInt64").get("COLUMN_SIZE"));
        assertEquals(38, column("Int128").get("COLUMN_SIZE"));
        assertEquals(Types.VARCHAR, column("Int256").get("DATA_TYPE"));
        assertEquals(Types.VARCHAR, column("Array(Nullable(String))").get("DATA_TYPE"));
        assertEquals("NO", column("Array(Nullable(String))").get("IS_NULLABLE"));
        for (String[] type : new String[][]{{"Bool", "16"}, {"Int8", "-6"}, {"UInt8", "5"}, {"UInt16", "4"},
                {"UInt32", "-5"}, {"Float32", "7"}, {"Float64", "8"}, {"Date", "91"}, {"Date32", "91"}}) {
            assertEquals(Integer.parseInt(type[1]), column(type[0]).get("DATA_TYPE"), type[0]);
        }
        assertTrue(ClickHouseMetaDataOperations.isTextReadType("Nullable(IPv4)"));
        assertTrue(ClickHouseMetaDataOperations.isTextReadType("Tuple(a Int32, b String)"));
        assertFalse(ClickHouseMetaDataOperations.isTextReadType("Nullable(Int32)"));
    }

    @Test
    void parsesKeys() {
        assertEquals(List.of("id"), ClickHouseMetaDataOperations.parseKeyColumns("(id)"));
        assertEquals(List.of("emp_id", "tag_name"), ClickHouseMetaDataOperations.parseKeyColumns(
                "emp_id, tag_name"));
        assertEquals(List.of("a", "b"), ClickHouseMetaDataOperations.parseKeyColumns("tuple(a, `b`)"));
        assertEquals(List.of(), ClickHouseMetaDataOperations.parseKeyColumns("tuple()"));
        assertEquals(List.of(), ClickHouseMetaDataOperations.parseKeyColumns(""));
        // Keys with expressions are not primary keys of other databases
        assertEquals(List.of(), ClickHouseMetaDataOperations.parseKeyColumns("toDate(ts), id"));
        assertEquals(List.of("a", "f(b, 'x,y')", "c"), ClickHouseMetaDataOperations.splitTopLevel(
                "a, f(b, 'x,y'), c"));
    }

    @Test
    void renamesCopiedTables() {
        Dialect dialect = new ClickHouseDialect();
        assertFalse(dialect.isTableRenamed());
        assertEquals("emp", dialect.getTargetTableName("emp"));
        dialect.setTableNamePattern(Dialect.TABLE_PLACEHOLDER);
        assertFalse(dialect.isTableRenamed());
        dialect.setTableNamePattern("{table}_copy");
        assertTrue(dialect.isTableRenamed());
        assertEquals("emp_copy", dialect.getTargetTableName("emp"));
        // The case of the source name is kept
        assertEquals("EMP_COPY", dialect.getTargetTableName("EMP"));
        assertEquals("Emp_copy", dialect.getTargetTableName("Emp"));
        assertNull(dialect.getTargetTableName(null));
        dialect.setTableNamePattern("backup");
        assertEquals("backup", dialect.getTargetTableName("emp"));
        assertEquals("backup", dialect.forVersion(26, 9).getTargetTableName("emp"));
    }
}
