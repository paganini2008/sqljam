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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.db.PostgreSQLDialect;

/**
 * @Description: DdlScripterTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class DdlScripterTest {

    private DdlScripter newScripter() {
        DdlScripter scripter = new DdlScripter(new PostgreSQLDialect());
        scripter.getBeforeStatements().add("SET client_encoding = 'UTF8'");
        scripter.getAfterStatements().add("ANALYZE");

        DdlScripter.Catalog catalog = scripter.getCatalog("shop");
        catalog.getBeforeStatements().add("SELECT 1");
        catalog.getCatalogStatements().add("CREATE DATABASE shop");
        catalog.getAfterStatements().add("SELECT 2");

        DdlScripter.Schema schema = scripter.getSchema("shop", "sales");
        schema.getBeforeStatements().add("SELECT 3");
        schema.getSchemaStatements().add("CREATE SCHEMA sales");
        schema.getAfterStatements().add("SELECT 4");
        schema.getSequenceStatements().add("CREATE SEQUENCE order_seq");
        schema.getConstraintStatements().add("ALTER TABLE orders ADD FOREIGN KEY (customer_id) REFERENCES customer (id)");

        DdlScripter.Table table = scripter.getTable("shop", "sales", "orders");
        table.getBeforeStatements().add("DROP TABLE IF EXISTS orders");
        table.getCreateTableStatements().add("CREATE TABLE orders ");
        table.getColumnStatements().add("id bigint NOT NULL");
        table.getColumnStatements().add("customer_id bigint");
        table.getPrimaryKeyStatements().add("PRIMARY KEY (id)");
        table.getTableOptions().add("WITH (fillfactor = 90)");
        table.getPartitionStatements().add("PARTITION BY RANGE (id)");
        table.getCommentStatements().add("COMMENT ON TABLE orders IS 'Orders'");
        table.getIndexStatements().add("CREATE INDEX idx_customer ON orders (customer_id)");
        table.getAfterStatements().add("SELECT 5");

        DdlScripter.PartitionTable partitionTable = scripter.getPartitionTable("shop", "sales", "orders_1");
        partitionTable.getBeforeStatements().add("DROP TABLE IF EXISTS orders_1");
        partitionTable.getCreateTableStatements().add("CREATE TABLE orders_1 PARTITION OF orders");
        partitionTable.getPrimaryKeyStatements().add("PRIMARY KEY (id)");
        partitionTable.getPartitionStatements().add("FOR VALUES FROM (1) TO (1000)");
        partitionTable.getAfterStatements().add("SELECT 6");
        return scripter;
    }

    @Test
    void prettyScripts() {
        DdlScripter scripter = newScripter();
        String text = String.join("\n", scripter.getPrettyScripts());
        for (String expected : new String[]{"To do something before backup operation", "Create catalog: shop",
                "Create schema: sales", "Create table: orders", "Create comments for table: orders",
                "Create indexes for table: orders", "To do something after backup operation", "CREATE DATABASE shop;",
                "CREATE SEQUENCE order_seq;", "    id bigint NOT NULL,", "    PRIMARY KEY (id)",
                "WITH (fillfactor = 90)", "PARTITION BY RANGE (id)", "FOR VALUES FROM (1) TO (1000)", "SELECT 6;"}) {
            assertTrue(text.contains(expected), expected + " in\n" + text);
        }
        // Statements keep the order: before, catalog, schema, tables, after
        assertTrue(text.indexOf("SET client_encoding") < text.indexOf("CREATE DATABASE"));
        assertTrue(text.indexOf("CREATE SCHEMA") < text.indexOf("CREATE TABLE orders"));
        assertTrue(text.indexOf("CREATE TABLE orders") < text.lastIndexOf("ANALYZE"));
    }

    @Test
    void plainScripts() {
        DdlScripter scripter = newScripter();
        List<String> sqls = scripter.getPlainScripts();
        assertEquals("SET client_encoding = 'UTF8';", sqls.get(0));
        assertEquals("ANALYZE;", sqls.get(sqls.size() - 1));
        assertTrue(sqls.contains("CREATE TABLE orders (id bigint NOT NULL,customer_id bigint,PRIMARY KEY (id))"
                + " WITH (fillfactor = 90) PARTITION BY RANGE (id);"), sqls.toString());
        assertTrue(sqls.contains("CREATE TABLE orders_1 PARTITION OF orders(PRIMARY KEY (id))"
                + " FOR VALUES FROM (1) TO (1000);"), sqls.toString());
        assertTrue(scripter.getConstraintScripts().get(0).startsWith("ALTER TABLE orders ADD FOREIGN KEY"));
    }

    @Test
    void emptyScripter() {
        DdlScripter scripter = new DdlScripter(new PostgreSQLDialect());
        assertTrue(scripter.getPrettyScripts().isEmpty());
        assertTrue(scripter.getPlainScripts().isEmpty());
        // Unnamed catalog and schema have no create statements
        scripter.getSchema(null, null).getSchemaStatements().add("CREATE SCHEMA x");
        assertTrue(String.join("\n", scripter.getPrettyScripts()).indexOf("Create schema") < 0);
    }

    @Test
    void dbTypeLookup() {
        assertEquals(DbType.MYSQL, DbType.forName("mysql"));
        assertNull(DbType.forName("db2"));
        assertEquals(DbType.MYSQL, DbType.forUrl("jdbc:mysql://localhost:3306/test"));
        assertEquals(DbType.MARIADB, DbType.forUrl("jdbc:mariadb://localhost:3306/test"));
        assertEquals(DbType.POSTGRESQL, DbType.forUrl("jdbc:postgresql://localhost/demo"));
        assertEquals(DbType.ORACLE, DbType.forUrl("jdbc:oracle:thin:@localhost:1521/demo"));
        assertEquals(DbType.SQLSERVER, DbType.forUrl("jdbc:sqlserver://localhost:1433;databaseName=demo"));
        assertEquals(DbType.H2, DbType.forUrl("JDBC:H2:mem:test"));
        assertEquals(DbType.SQLITE, DbType.forUrl("jdbc:sqlite:test.db"));
        assertNull(DbType.forUrl("jdbc:db2://localhost/test"));
        assertNull(DbType.forUrl(" "));
    }
}
