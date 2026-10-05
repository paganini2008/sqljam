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
package com.github.sqljam.sqlserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.github.sqljam.impexp.db.SQLServer2008Dialect;
import com.github.sqljam.impexp.db.SQLServerDialect;

/**
 * @Description: SQLServerDialectTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class SQLServerDialectTest {

    @Test
    void ddl() {
        SQLServerDialect dialect = new SQLServerDialect();
        assertEquals(DbType.SQLSERVER, dialect.getDbType());
        assertEquals("[order]", dialect.quoteIdentifier("order"));
        assertEquals("[a]]b]", dialect.quoteIdentifier("a]b"));
        assertEquals("demo.dbo.t", dialect.getSourceTableName("demo", "dbo", "t"));
        assertEquals("demo..t", dialect.getSourceTableName("demo", null, "t"));
        assertEquals("dbo", dialect.getDefaultSchemaName("demo"));
        assertEquals("CREATE LOGIN u WITH PASSWORD = 'p'", dialect.getCreateUserStatement("u", "p"));
        assertEquals("CREATE DATABASE demo", dialect.getCreateDatabaseStatement("demo", "u"));
        assertEquals("CREATE SCHEMA s", dialect.getCreateSchemaStatement(null, "s", "u"));
        assertEquals("IF SCHEMA_ID(N's') IS NULL EXEC(N'CREATE SCHEMA s')",
                dialect.getCreateSchemaIfNotExistsStatement("s"));
        assertEquals("CREATE TABLE t", dialect.getCreateTableStatement(null, null, "t"));
        assertTrue(dialect.getDropTableStatement(null, null, "t").contains("sys.foreign_keys"));
        assertEquals("id bigint IDENTITY(1,1) NOT NULL", dialect.getIncrementalColumnStatement(null, null, "t",
                "id", Types.VARCHAR, "varchar", 10, 0, null, false).replaceAll("\\s+", " "));
        assertEquals("DBCC CHECKIDENT ('t', RESEED, 9)", dialect.getResetIdentityStatement(null, null, "t", "id", 10));
        assertEquals("SET IDENTITY_INSERT t ON", dialect.getStatementBeforeInsert(null, null, "t", true)[0]);
        assertEquals("SET IDENTITY_INSERT t OFF", dialect.getStatementAfterInsert(null, null, "t", true)[0]);
        assertNull(dialect.getStatementBeforeInsert(null, null, "t", false));
        assertNull(dialect.getStatementAfterInsert(null, null, "t", false));
        assertEquals("c AS (a * 2) PERSISTED", dialect.getGeneratedColumnStatement(null, null, "t", "c",
                Types.BIGINT, "bigint", 19, 0, "(a * 2)", true));
        assertTrue(dialect.getCreateCommentStatement(null, "dbo", "t", "c", "x").contains("@level0name = @schema"));
        assertTrue(dialect.getCreateTableCommentStatement(null, null, "t", "x").contains("SCHEMA_NAME()"));
        assertEquals("CAST(GETDATE() AS DATE)", dialect.getCurrentDateExpression());
    }

    @Test
    void indexes() {
        SQLServerDialect dialect = new SQLServerDialect();
        dialect.registerColumnTypeName("t", "body", "nvarchar(max)");
        assertNull(dialect.getCreateIndexStatement(null, null, "t", false, new String[]{"body"}, "idx", false, null));
        assertEquals("CREATE UNIQUE INDEX uidx ON t (a)", dialect.getCreateIndexStatement(null, null, "t", false,
                new String[]{"a"}, "uidx", true, null));
        SQLServerDialect cross = new SQLServerDialect();
        cross.setSourceDbType(DbType.POSTGRESQL);
        assertEquals("CREATE UNIQUE INDEX uidx ON t (a,b) WHERE a IS NOT NULL AND b IS NOT NULL",
                cross.getCreateIndexStatement(null, null, "t", false, new String[]{"a", "b"}, "uidx", true, null));
    }

    @Test
    void versions() {
        SQLServerDialect dialect = new SQLServerDialect();
        assertTrue(dialect.isSequenceSupported());
        assertEquals("SELECT * FROM t ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY",
                dialect.getPageStatement("SELECT * FROM t", null, 10, 0));
        assertTrue(dialect.getCreateSequenceStatement(null, null, "seq", 1000, 10, null, null, false, null,
                "int").contains("CREATE SEQUENCE seq AS int START WITH 1000"));
        // SQL Server 2008
        dialect = (SQLServerDialect) dialect.forVersion(10, 50);
        assertTrue(dialect instanceof SQLServer2008Dialect);
        assertFalse(dialect.isSequenceSupported());
        assertNull(dialect.getCreateSequenceStatement(null, null, "seq", 1, 1, null, null, false, null, null));
        assertEquals("SELECT * FROM (SELECT t__.*, ROW_NUMBER() OVER (ORDER BY id) AS rn__ FROM (SELECT * FROM t)"
                + " t__) q__ WHERE rn__ > 20 AND rn__ <= 30", dialect.getPageStatement("SELECT * FROM t", "id", 10, 20));
    }

    @Test
    void types() {
        SQLServerDialect dialect = new SQLServerDialect();
        assertEquals("varchar(max)", dialect.getColumnTypeName(Types.VARCHAR, "varchar", Integer.MAX_VALUE, 0));
        assertEquals("nvarchar(100)", dialect.getColumnTypeName(Types.NVARCHAR, "nvarchar", 100, 0));
        assertEquals("datetime2(7)", dialect.getColumnTypeName(Types.TIMESTAMP, "datetime2", 27, 7));
        assertEquals("int", dialect.getColumnTypeName(Types.INTEGER, "int identity", 10, 0));
        assertEquals("rowversion", dialect.getColumnTypeName(Types.BINARY, "timestamp", 8, 0));
        assertEquals("tinyint", dialect.getColumnTypeName(Types.TINYINT, "tinyint", 3, 0));

        SQLServerDialect cross = new SQLServerDialect();
        cross.setSourceDbType(DbType.POSTGRESQL);
        assertEquals("uniqueidentifier", cross.getColumnTypeName(Types.OTHER, "uuid", 0, 0));
        assertEquals("nvarchar(max)", cross.getColumnTypeName(Types.OTHER, "jsonb", 0, 0));
        assertEquals("varbinary(max)", cross.getColumnTypeName(Types.BINARY, "bytea", Integer.MAX_VALUE, 0));
        assertEquals("datetime2(6)", cross.getColumnTypeName(Types.TIMESTAMP, "timestamp", 29, 6));
        assertEquals("bit", cross.getColumnTypeName(Types.BIT, "bool", 1, 0));
        assertEquals("smallint", cross.getColumnTypeName(Types.TINYINT, "tinyint", 3, 0));
        assertEquals("nvarchar(4000)", cross.getColumnTypeName(Types.VARCHAR, "varchar", 4000, 0));
        assertEquals("nvarchar(max)", cross.getColumnTypeName(Types.VARCHAR, "varchar", 4001, 0));
        SQLServerDialect fromSqlServer = new SQLServerDialect();
        fromSqlServer.setSourceDbType(DbType.MYSQL);
        assertEquals("binary(8)", fromSqlServer.getColumnTypeName(Types.BINARY, "timestamp", 8, 0));
    }

    @Test
    void literalsAndPartitions() {
        SQLServerDialect dialect = new SQLServerDialect();
        assertEquals("N'中文'", dialect.getStringLiteral("中文"));
        assertEquals("NULL", dialect.getStringLiteral(null));
        assertEquals("0x0A", dialect.getBinaryLiteral(new byte[]{10}));
        assertEquals("0x", dialect.getBinaryLiteral(new byte[0]));
        assertEquals("'2024-02-29T23:59:59.1234567'", dialect.getTimestampLiteral(
                LocalDateTime.of(2024, 2, 29, 23, 59, 59, 123_456_700)));
        assertEquals("'2024-02-29'", dialect.getDateLiteral(LocalDate.of(2024, 2, 29)));
        assertEquals("'10:20:30'", dialect.getTimeLiteral(LocalTime.of(10, 20, 30)));
        assertEquals("0", dialect.getBooleanLiteral(false));

        Map<String, Object> detail = new HashMap<>();
        assertNull(dialect.getDefinePartitionTableStatement(null, null, "t", "RANGE", "y", detail));
        assertNull(dialect.getStatementBeforePartitionTableCreated(null, null, "t", detail));
        detail.put("PARTITION_SCHEME", "ps_year");
        detail.put("PARTITION_FUNCTION", "pf_year");
        detail.put("PARTITION_FUNCTION_DDL", "CREATE PARTITION FUNCTION [pf_year](int) AS RANGE RIGHT FOR VALUES (1)");
        assertEquals("ON ps_year(y)", dialect.getDefinePartitionTableStatement(null, null, "t", "RANGE RIGHT", "y",
                detail));
        String[] statements = dialect.getStatementBeforePartitionTableCreated(null, null, "t", detail);
        assertTrue(statements[0].contains("sys.partition_functions"));
        assertTrue(statements[1].contains("CREATE PARTITION SCHEME ps_year AS PARTITION pf_year ALL TO ([PRIMARY])"));
        assertNull(dialect.getCreatePartitionTableStatement(null, null, "p", "t"));
    }
}
