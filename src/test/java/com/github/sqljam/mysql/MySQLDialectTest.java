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
package com.github.sqljam.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.db.MySQL55Dialect;
import com.github.sqljam.impexp.db.MySQL56Dialect;
import com.github.sqljam.impexp.db.MySQLDialect;

/**
 * @Description: MySQLDialectTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class MySQLDialectTest {

    private static MySQLDialect cross(DbType sourceDbType) {
        MySQLDialect dialect = new MySQLDialect();
        dialect.setSourceDbType(sourceDbType);
        return dialect;
    }

    @Test
    void identifiersAndNames() {
        MySQLDialect dialect = new MySQLDialect();
        assertEquals(DbType.MYSQL, dialect.getDbType());
        assertEquals("`order`", dialect.quoteIdentifier("order"));
        assertEquals("test.`order`", dialect.getSourceTableName("test", null, "order"));
        dialect.setTargetCatalogName("test_test");
        assertEquals("test_test.orders", dialect.getQualifiedTableName("test", null, "orders"));
        assertEquals("DROP TABLE IF EXISTS test_test.orders", dialect.getDropTableStatement(null, null, "orders"));
        assertEquals("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", dialect.getTableOptions(null, null, "t", null));
        assertEquals("SET FOREIGN_KEY_CHECKS=0", dialect.getSessionStatements()[0]);
        assertEquals("CREATE DATABASE IF NOT EXISTS `my db` DEFAULT CHARACTER SET utf8mb4",
                dialect.getCreateSchemaIfNotExistsStatement("my db"));
        assertNull(dialect.getCreateSchemaStatement(null, "s", "u"));
        assertTrue(dialect.getCreateDatabaseStatement("db", "u").startsWith("CREATE DATABASE IF NOT EXISTS db"));
        assertEquals("GRANT ALL PRIVILEGES ON db.* TO 'u'@'%'", dialect.getStatementAfterDatabaseCreated("db", "u")[0]);
    }

    @Test
    void users() {
        MySQLDialect dialect = new MySQLDialect();
        assertEquals("CREATE USER IF NOT EXISTS 'u'@'%' IDENTIFIED BY 'p'", dialect.getCreateUserStatement("u", "p"));
        assertEquals("CREATE USER 'u'@'%' IDENTIFIED BY 'p'", new MySQL56Dialect().getCreateUserStatement("u", "p"));
    }

    @Test
    void columns() {
        MySQLDialect dialect = new MySQLDialect();
        // Native column type of the same database
        assertEquals("enum('Red','Green')", dialect.getColumnTypeName(Types.CHAR, "enum('Red','Green')", 5, 0));
        assertEquals("name varchar(100) NOT NULL COMMENT 'O''Brien\\\\'",
                dialect.getColumnStatement(null, null, "t", "name", Types.VARCHAR, "varchar(100)", 100, 0, null,
                        false, "O'Brien\\").replaceAll("\\s+", " "));
        assertNull(dialect.getCreateCommentStatement(null, null, "t", "c", "comment"));
        assertEquals("ALTER TABLE t COMMENT = 'Orders'", dialect.getCreateTableCommentStatement(null, null, "t",
                "Orders"));
        assertEquals("id bigint NOT NULL AUTO_INCREMENT", dialect.getIncrementalColumnStatement(null, null, "t",
                "id", Types.DECIMAL, "decimal(19,0)", 19, 0, null, false).replaceAll("\\s+", " "));
        assertEquals("ALTER TABLE t AUTO_INCREMENT = 100", dialect.getResetIdentityStatement(null, null, "t", "id",
                100));
        assertEquals("c bigint GENERATED ALWAYS AS (a + 1) VIRTUAL", dialect.getGeneratedColumnStatement(null,
                null, "t", "c", Types.BIGINT, "bigint", 19, 0, "a + 1", false));
        assertEquals("PRIMARY KEY (id,code)", dialect.getCreatePrimaryKeyStatement(null, null, "t", "id,code",
                "PRIMARY"));
    }

    @Test
    void crossDatabaseColumns() {
        MySQLDialect dialect = cross(DbType.POSTGRESQL);
        assertEquals("json", dialect.getColumnTypeName(Types.OTHER, "jsonb", 0, 0));
        assertEquals("char(36)", dialect.getColumnTypeName(Types.OTHER, "uuid", 0, 0));
        assertEquals("longtext", dialect.getColumnTypeName(Types.ARRAY, "_int4", 0, 0));
        assertEquals("longtext", dialect.getColumnTypeName(Types.VARCHAR, "text", Integer.MAX_VALUE, 0));
        assertEquals("tinyint(1)", dialect.getColumnTypeName(Types.BIT, "bool", 1, 0));
        assertEquals("bit(8)", dialect.getColumnTypeName(Types.BIT, "bit", 8, 0));
        assertEquals("datetime(6)", dialect.getColumnTypeName(Types.TIMESTAMP, "timestamp", 29, 6));
        assertEquals("datetime", dialect.getColumnTypeName(Types.TIMESTAMP, "timestamp", 19, 0));
        assertEquals("varchar(255)", dialect.getColumnTypeName(Types.VARCHAR, "varchar", 255, 0));
        assertEquals("mediumtext", dialect.getColumnTypeName(Types.VARCHAR, "varchar", 100000, 0));
        assertEquals("longblob", dialect.getColumnTypeName(Types.BINARY, "bytea", Integer.MAX_VALUE, 0));
        assertEquals("decimal(65,30)", dialect.getColumnTypeName(Types.NUMERIC, "numeric", 131089, 0));
        assertEquals("varchar(64)", dialect.getColumnTypeName(Types.OTHER, "inet", 0, 0));
        // Old versions
        MySQLDialect mysql56 = (MySQLDialect) dialect.forVersion(5, 6);
        assertTrue(mysql56 instanceof MySQL56Dialect);
        assertEquals("longtext", mysql56.getColumnTypeName(Types.OTHER, "json", 0, 0));
        assertEquals(false, mysql56.isGeneratedColumnSupported());
    }

    @Test
    void defaultValues() {
        MySQLDialect dialect = cross(DbType.POSTGRESQL);
        String column = dialect.getColumnStatement(null, null, "t", "created", Types.TIMESTAMP, "timestamp", 29, 6,
                "now()", true, null).replaceAll("\\s+", " ");
        assertEquals("created datetime(6) DEFAULT CURRENT_TIMESTAMP(6)", column);
        column = dialect.getColumnStatement(null, null, "t", "note", Types.VARCHAR, "text", Integer.MAX_VALUE, 0,
                "'n/a'::text", true, null).replaceAll("\\s+", " ");
        assertEquals("note longtext DEFAULT ('n/a')", column);
        dialect = (MySQLDialect) dialect.forVersion(5, 7);
        column = dialect.getColumnStatement(null, null, "t", "note", Types.VARCHAR, "text", Integer.MAX_VALUE, 0,
                "'n/a'::text", true, null).replaceAll("\\s+", " ");
        assertEquals("note longtext", column);
        dialect = (MySQLDialect) dialect.forVersion(5, 5);
        assertTrue(dialect instanceof MySQL55Dialect);
        column = dialect.getColumnStatement(null, null, "t", "created", Types.TIMESTAMP, "timestamp", 19, 0,
                "now()", true, null).replaceAll("\\s+", " ");
        assertEquals("created datetime", column);
    }

    @Test
    void indexes() {
        MySQLDialect dialect = cross(DbType.POSTGRESQL);
        dialect.registerColumnTypeName("t", "body", "longtext");
        dialect.registerColumnTypeName("t", "title", "varchar(1000)");
        dialect.registerColumnTypeName("t", "code", "varchar(100)");
        assertEquals("CREATE UNIQUE INDEX uidx ON t (body(191),title(191),code)",
                dialect.getCreateIndexStatement(null, null, "t", false, new String[]{"body", "title", "code"},
                        "uidx", true, null));
        assertEquals("CREATE INDEX idx ON t (code)",
                dialect.getCreateIndexStatement(null, null, "t", false, new String[]{"code"}, "idx", false, null));
    }

    @Test
    void partitionsAndLiterals() {
        MySQLDialect dialect = new MySQLDialect();
        Map<String, Object> detail = new HashMap<>();
        detail.put("PARTITION_CLAUSE", "PARTITION BY HASH (`id`) PARTITIONS 4");
        assertEquals("PARTITION BY HASH (`id`) PARTITIONS 4", dialect.getDefinePartitionTableStatement(null, null,
                "t", "HASH", "`id`", detail));
        assertNull(cross(DbType.ORACLE).getDefinePartitionTableStatement(null, null, "t", "HASH", "id", detail));
        assertNull(dialect.getCreatePartitionTableStatement(null, null, "p", "t"));
        assertEquals("'a\\\\b''c'", dialect.getStringLiteral("a\\b'c"));
        assertEquals("NULL", dialect.getStringLiteral(null));
        assertEquals("1", dialect.getBooleanLiteral(true));
        assertEquals("(CURRENT_DATE)", dialect.getCurrentDateExpression());
        assertNull(dialect.getSequenceNameStatement(null, null, "t", "id"));
        assertNull(dialect.getDefaultSequenceName(null, null, "t", "id"));
    }
}
