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
package com.github.sqljam.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.db.PostgreSQL10Dialect;
import com.github.sqljam.impexp.db.PostgreSQL9Dialect;
import com.github.sqljam.impexp.db.PostgreSQLDialect;

/**
 * @Description: PostgreSQLDialectTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class PostgreSQLDialectTest {

    @Test
    void ddl() {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        assertEquals(DbType.POSTGRESQL, dialect.getDbType());
        assertEquals("public", dialect.getDefaultSchemaName("demo"));
        assertEquals("CREATE USER u WITH PASSWORD 'p'", dialect.getCreateUserStatement("u", "p"));
        assertEquals("CREATE DATABASE demo OWNER u", dialect.getCreateDatabaseStatement("demo", "u"));
        assertEquals("GRANT ALL PRIVILEGES ON DATABASE demo TO u",
                dialect.getStatementAfterDatabaseCreated("demo", "u")[0]);
        assertEquals("CREATE SCHEMA IF NOT EXISTS s AUTHORIZATION u", dialect.getCreateSchemaStatement(null, "s", "u"));
        assertEquals("CREATE SCHEMA IF NOT EXISTS s", dialect.getCreateSchemaIfNotExistsStatement("s"));
        assertEquals("DROP TABLE IF EXISTS t CASCADE", dialect.getDropTableStatement(null, null, "t"));
        assertEquals("COMMENT ON COLUMN t.c IS 'x'", dialect.getCreateCommentStatement(null, null, "t", "c", "x"));
        assertEquals("COMMENT ON TABLE t IS 'x'", dialect.getCreateTableCommentStatement(null, null, "t", "x"));
        assertEquals("id bigserial NOT NULL", dialect.getIncrementalColumnStatement(null, null, "t", "id",
                Types.BIGINT, "int8", 19, 0, null, false).replaceAll("\\s+", " "));
        assertEquals("SELECT setval(pg_get_serial_sequence('t', 'id'), 10, false)",
                dialect.getResetIdentityStatement(null, null, "t", "id", 10));
        assertEquals("ALTER SEQUENCE s restart with 5",
                dialect.getAlterSequenceStartValueStatement(null, null, "t", "s", 5));
        assertTrue(dialect.getSequenceNameStatement(null, "s", "t", "id").contains("pg_get_serial_sequence"));
        assertEquals("s.t_id_seq", dialect.getDefaultSequenceName(null, "s", "t", "id"));
        assertEquals(Types.OTHER, dialect.getNullSqlType(Types.VARCHAR));
        assertEquals("c int8 GENERATED ALWAYS AS (a * 2) STORED", dialect.getGeneratedColumnStatement(null, null,
                "t", "c", Types.BIGINT, "int8", 19, 0, "a * 2", false));
        assertEquals("'\\xDEAD'::bytea", dialect.getBinaryLiteral(new byte[]{(byte) 0xDE, (byte) 0xAD}));
    }

    @Test
    void indexesAndVersions() {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS uidx ON t (a,b)", dialect.getCreateIndexStatement(null, null,
                "t", false, new String[]{"a", "b"}, "uidx", true, null));
        assertEquals("CREATE INDEX IF NOT EXISTS idx ON t (a)", dialect.getCreateIndexStatement(null, null, "t",
                true, new String[]{"a"}, "idx", true, null));
        assertEquals("CREATE INDEX IF NOT EXISTS idx ON t USING HASH (a)", dialect.getCreateIndexStatement(null,
                null, "t", false, new String[]{"a"}, "idx", false, "HASH"));
        assertEquals("serial", dialect.getSerialTypeName(Types.INTEGER));
        assertEquals("smallserial", dialect.getSerialTypeName(Types.SMALLINT));
        assertTrue(dialect.isGeneratedColumnSupported());

        assertFalse(new PostgreSQL10Dialect().isGeneratedColumnSupported());
        dialect = new PostgreSQL9Dialect();
        assertEquals("CREATE INDEX idx ON t (a)", dialect.getCreateIndexStatement(null, null, "t", false,
                new String[]{"a"}, "idx", false, null));
        assertEquals("CREATE INDEX idx ON t USING HASH (a)", dialect.getCreateIndexStatement(null, null, "t", false,
                new String[]{"a"}, "idx", false, "HASH"));
        assertFalse(dialect.isGeneratedColumnSupported());
        assertEquals("CREATE SEQUENCE s START WITH 10 INCREMENT BY 1", dialect.getCreateSequenceStatement(null,
                null, "s", 10, 1, null, null, false, null, "bigint"));
        assertEquals("DROP SEQUENCE IF EXISTS s CASCADE",
                dialect.getStatementBeforeSequenceCreated(null, null, "s")[0]);
        assertFalse(dialect.isPartitionSupported());
        assertEquals("serial", dialect.getSerialTypeName(Types.SMALLINT));
        assertEquals("bigserial", dialect.getSerialTypeName(Types.BIGINT));
    }

    @Test
    void sequences() {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        assertTrue(dialect.isSequenceSupported());
        org.junit.jupiter.api.Assertions.assertNull(dialect.getStatementBeforeSequenceCreated(null, null, "s"));
        assertEquals("CREATE SEQUENCE IF NOT EXISTS seq AS bigint START WITH 1000 INCREMENT BY 10 MINVALUE 1"
                        + " MAXVALUE 99999 CYCLE CACHE 20",
                dialect.getCreateSequenceStatement(null, null, "seq", 1000, 10, 1L, 99999L, true, 20L, "bigint"));
        assertEquals("SELECT setval('seq', 1000, false)",
                dialect.getStatementAfterSequenceCreated(null, null, "seq", 1000)[0]);
        dialect.setTargetSchemaName("dst");
        assertEquals("SELECT setval('dst.seq', 5, false)",
                dialect.getStatementAfterSequenceCreated(null, "src", "seq", 5)[0]);
    }

    @Test
    void columnTypes() {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        assertEquals("jsonb", dialect.getColumnTypeName(Types.OTHER, "jsonb", 0, 0));
        assertEquals("int4[]", dialect.getColumnTypeName(Types.ARRAY, "_int4", 10, 0));
        assertEquals("bit(8)", dialect.getColumnTypeName(Types.BIT, "bit", 8, 0));
        assertEquals("varbit(16)", dialect.getColumnTypeName(Types.OTHER, "varbit", 16, 0));
        assertEquals("varbit", dialect.getColumnTypeName(Types.OTHER, "varbit", 0, 0));
        assertEquals("varchar", dialect.getColumnTypeName(Types.VARCHAR, "varchar", Integer.MAX_VALUE, 0));
        assertEquals("char(10)", dialect.getColumnTypeName(Types.CHAR, "bpchar", 10, 0));
        assertEquals("timestamp", dialect.getColumnTypeName(Types.TIMESTAMP, "timestamp", 29, 6));

        PostgreSQLDialect cross = new PostgreSQLDialect();
        cross.setSourceDbType(DbType.SQLSERVER);
        assertEquals("uuid", cross.getColumnTypeName(Types.CHAR, "uniqueidentifier", 36, 0));
        assertEquals("timestamptz", cross.getColumnTypeName(-155, "datetimeoffset", 34, 7));
        assertEquals("int8", cross.getColumnTypeName(Types.BIT, "bit", 8, 0));
        assertEquals("int2", cross.getColumnTypeName(Types.SMALLINT, "year", 4, 0));
        assertEquals("xml", cross.getColumnTypeName(Types.SQLXML, "xmltype", 0, 0));
    }

    @Test
    void partitions() {
        PostgreSQLDialect dialect = new PostgreSQLDialect();
        assertTrue(dialect.isPartitionSupported());
        Map<String, Object> detail = new HashMap<>();
        assertEquals("PARTITION BY RANGE (sale_year)", dialect.getDefinePartitionTableStatement(null, null, "t",
                "RANGE", "sale_year", detail));
        detail.put("PARTITION_CLAUSE", "PARTITION BY LIST (lower(region))");
        assertEquals("PARTITION BY LIST (lower(region))", dialect.getDefinePartitionTableStatement(null, null, "t",
                "LIST", "lower(region)", detail));
        assertEquals("CREATE TABLE IF NOT EXISTS t_2024 PARTITION OF t",
                dialect.getCreatePartitionTableStatement(null, null, "t_2024", "t"));
    }
}
