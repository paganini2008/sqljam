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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.db.H2Dialect;
import com.github.sqljam.it.CollectingListener;

/**
 * @Description: ColumnMappingTest imports H2 tables with the columns which the user mapped: renamed columns in the
 *               table, its primary key, indexes, foreign keys (also of referenced columns), comments, identity values
 *               and keys of upserts, types written as they are, skipped columns with their keys and foreign keys, and
 *               the mappings which are refused. An import without mappings is the import of before.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class ColumnMappingTest {

    private static String newUrl() {
        return "jdbc:h2:mem:cm" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static void execute(String url, String... sqls) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> values(String url, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.add(rs.getString(i));
                }
                values.add(String.join("|", row));
            }
        }
        return values;
    }

    private static String source() throws Exception {
        String url = newUrl();
        execute(url, "CREATE TABLE CUSTOMERS (ID INT PRIMARY KEY, NAME VARCHAR(20))",
                "CREATE TABLE ORDERS (ORDER_ID BIGINT AUTO_INCREMENT PRIMARY KEY, CUSTOMER_ID INT REFERENCES CUSTOMERS(ID),"
                        + " AMOUNT DECIMAL(10,2), NOTE VARCHAR(100), SECRET VARCHAR(20))",
                "CREATE INDEX IDX_AMOUNT ON ORDERS(AMOUNT)",
                "COMMENT ON COLUMN ORDERS.NOTE IS 'Note of the order'",
                "INSERT INTO CUSTOMERS VALUES (1, 'Ann'), (2, 'Bob')",
                "INSERT INTO ORDERS (CUSTOMER_ID, AMOUNT, NOTE, SECRET) VALUES (1, 10.5, 'first', 's1'),"
                        + " (2, 20.25, 'second', 's2'), (1, 30, 'third', 's3')");
        return url;
    }

    private static ImportExporter importer(String source, String target) {
        ImportExporter importExporter = new ImportExporter();
        Exporter.ExportConfiguration configuration = importExporter.getExportConfiguration();
        configuration.setDbType(DbType.H2);
        configuration.setUrl(source);
        configuration.setUsername("sa");
        configuration.setPassword("");
        configuration.setIncludedSchemaNames(new String[]{"PUBLIC"});
        configuration.setIdReused(true);
        configuration.setConnectionPoolEnabled(false);
        configuration.setPageSize(2);
        importExporter.getImportConfiguration().setDbType(DbType.H2);
        importExporter.getImportConfiguration().setUrl(target);
        importExporter.getImportConfiguration().setUsername("sa");
        importExporter.getImportConfiguration().setPassword("");
        importExporter.getImportConfiguration().setConnectionPoolEnabled(false);
        return importExporter;
    }

    private static Map<String, ColumnMapping> mappings(ImportExporter importExporter, String table) {
        return importExporter.getImportConfiguration().getColumnMappings().computeIfAbsent(table,
                key -> new HashMap<>());
    }

    private static List<String> columns(String url, String table) throws Exception {
        return values(url, "SELECT COLUMN_NAME, DATA_TYPE, NUMERIC_SCALE FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = '" + table + "' ORDER BY ORDINAL_POSITION");
    }

    @Test
    void importsMappedColumns() throws Exception {
        String source = source();
        String target = newUrl();
        ImportExporter importExporter = importer(source, target);
        mappings(importExporter, "ORDERS").put("ORDER_ID", new ColumnMapping("ID", null, false));
        mappings(importExporter, "ORDERS").put("CUSTOMER_ID", new ColumnMapping("CUSTOMER", null, false));
        mappings(importExporter, "ORDERS").put("AMOUNT", new ColumnMapping(null, "DECIMAL(18,4)", false));
        mappings(importExporter, "ORDERS").put("SECRET", ColumnMapping.skip());
        // A referenced column which is renamed is the column of the foreign key
        mappings(importExporter, "CUSTOMERS").put("ID", new ColumnMapping(" CUSTOMER_KEY ", " ", false));
        importExporter.exportDdlAndData();

        assertEquals(List.of("ID|BIGINT|0", "CUSTOMER|INTEGER|0", "AMOUNT|NUMERIC|4", "NOTE|CHARACTER VARYING|null"),
                columns(target, "ORDERS"));
        assertEquals(List.of("CUSTOMER_KEY|INTEGER|0", "NAME|CHARACTER VARYING|null"), columns(target, "CUSTOMERS"));
        assertEquals(List.of("1|1|10.5000|first", "2|2|20.2500|second", "3|1|30.0000|third"),
                values(target, "SELECT ID, CUSTOMER, AMOUNT, NOTE FROM ORDERS ORDER BY ID"));
        // Primary keys, the index, the foreign key and the comment are on the mapped columns
        assertEquals(List.of("ORDERS|ID", "CUSTOMERS|CUSTOMER_KEY"), values(target, "SELECT TC.TABLE_NAME,"
                + " KCU.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE"
                + " KCU ON KCU.CONSTRAINT_NAME = TC.CONSTRAINT_NAME WHERE TC.CONSTRAINT_TYPE = 'PRIMARY KEY'"
                + " ORDER BY TC.TABLE_NAME DESC"));
        assertEquals(List.of("CUSTOMER|CUSTOMER_KEY"), values(target, "SELECT KCU.COLUMN_NAME, CCU.COLUMN_NAME FROM"
                + " INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS RC JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU"
                + " ON KCU.CONSTRAINT_NAME = RC.CONSTRAINT_NAME JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE CCU"
                + " ON CCU.CONSTRAINT_NAME = RC.UNIQUE_CONSTRAINT_NAME"));
        assertEquals(List.of("AMOUNT"), values(target, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS"
                + " WHERE TABLE_NAME = 'ORDERS' AND INDEX_NAME NOT LIKE 'PRIMARY%' AND INDEX_NAME NOT LIKE 'CONSTRAINT%'"
                + " AND COLUMN_NAME = 'AMOUNT'"));
        assertEquals(List.of("Note of the order"), values(target, "SELECT REMARKS FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = 'ORDERS' AND COLUMN_NAME = 'NOTE'"));
        // Identity values go on after the copied rows in the renamed column
        execute(target, "INSERT INTO ORDERS (CUSTOMER, AMOUNT, NOTE) VALUES (2, 1, 'new')");
        assertEquals(List.of("4"), values(target, "SELECT ID FROM ORDERS WHERE NOTE = 'new'"));
    }

    @Test
    void upsertsByMappedKey() throws Exception {
        String source = source();
        String target = newUrl();
        execute(target, "CREATE TABLE CUSTOMERS (CUSTOMER_KEY INT PRIMARY KEY, FULL_NAME VARCHAR(20))",
                "INSERT INTO CUSTOMERS VALUES (1, 'old'), (9, 'kept')");
        ImportExporter importExporter = importer(source, target);
        importExporter.getExportConfiguration().setIncludedTableNames(new String[]{"CUSTOMERS"});
        importExporter.getExportConfiguration().setTableRecreated(false);
        importExporter.getExportConfiguration().setWriteMode(WriteMode.UPSERT);
        mappings(importExporter, "CUSTOMERS").put("ID", new ColumnMapping("customer_key", null, false));
        mappings(importExporter, "CUSTOMERS").put("NAME", new ColumnMapping("full_name", null, false));
        importExporter.exportDdlAndData();
        // The key of the source is the mapped key of the existing table, names differ in case only
        assertEquals(List.of("1|Ann", "2|Bob", "9|kept"), values(target,
                "SELECT CUSTOMER_KEY, FULL_NAME FROM CUSTOMERS ORDER BY 1"));

        // A chosen key column is named by its source column too
        ImportExporter chosen = importer(source, target);
        chosen.getExportConfiguration().setIncludedTableNames(new String[]{"CUSTOMERS"});
        chosen.getExportConfiguration().setTableRecreated(false);
        chosen.getExportConfiguration().setWriteMode(WriteMode.SKIP_EXISTING);
        chosen.getExportConfiguration().getKeyColumnNames().put("CUSTOMERS", List.of("ID"));
        mappings(chosen, "CUSTOMERS").put("ID", new ColumnMapping("CUSTOMER_KEY", null, false));
        mappings(chosen, "CUSTOMERS").put("NAME", new ColumnMapping("FULL_NAME", null, false));
        execute(target, "UPDATE CUSTOMERS SET FULL_NAME = 'changed' WHERE CUSTOMER_KEY = 2");
        chosen.exportDdlAndData();
        assertEquals(List.of("changed"), values(target, "SELECT FULL_NAME FROM CUSTOMERS WHERE CUSTOMER_KEY = 2"));
    }

    @Test
    void skipsColumnsWithTheirKeys() throws Exception {
        String source = source();
        String target = newUrl();
        ImportExporter importExporter = importer(source, target);
        // The referenced key is not imported: no primary key of customers, no foreign key of orders
        mappings(importExporter, "CUSTOMERS").put("ID", ColumnMapping.skip());
        mappings(importExporter, "ORDERS").put("AMOUNT", ColumnMapping.skip());
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        importExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors());
        assertEquals(List.of("NAME|CHARACTER VARYING|null"), columns(target, "CUSTOMERS"));
        assertEquals(List.of("Ann", "Bob"), values(target, "SELECT NAME FROM CUSTOMERS ORDER BY NAME"));
        assertFalse(columns(target, "ORDERS").stream().anyMatch(column -> column.startsWith("AMOUNT|")));
        assertEquals(List.of("0"), values(target, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS"));
        assertEquals(List.of("0"), values(target, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS"
                + " WHERE TABLE_NAME = 'ORDERS' AND COLUMN_NAME = 'AMOUNT'"));
        assertEquals(List.of("3"), values(target, "SELECT COUNT(*) FROM ORDERS"));
    }

    @Test
    void refusesMappingsWhichCanNotBeImported() throws Exception {
        String source = source();
        // Two columns with the same target name, in another case too
        ImportExporter twice = importer(source, newUrl());
        mappings(twice, "ORDERS").put("NOTE", new ColumnMapping("secret", null, false));
        ImpExpException e = assertThrows(ImpExpException.class, twice::exportDdlAndData);
        assertTrue(e.getMessage().contains("are both mapped to"), e.getMessage());
        // A column which the table does not have
        ImportExporter missing = importer(source, newUrl());
        mappings(missing, "ORDERS").put("TOTAL", new ColumnMapping("SUM", null, false));
        e = assertThrows(ImpExpException.class, missing::exportDdlAndData);
        assertTrue(e.getMessage().contains("Column TOTAL of the mapping is not a column of table ORDERS"),
                e.getMessage());
        // A key which is skipped can not be the key of an upsert
        ImportExporter key = importer(source, newUrl());
        key.getExportConfiguration().setWriteMode(WriteMode.UPSERT);
        key.getExportConfiguration().setIncludedTableNames(new String[]{"CUSTOMERS"});
        key.getExportConfiguration().getKeyColumnNames().put("CUSTOMERS", List.of("ID"));
        mappings(key, "CUSTOMERS").put("ID", ColumnMapping.skip());
        e = assertThrows(ImpExpException.class, key::exportDdlAndData);
        assertTrue(e.getMessage().contains("Key column"), e.getMessage());
        // A type which the target does not have fails with the error of the database
        ImportExporter type = importer(source, newUrl());
        mappings(type, "ORDERS").put("NOTE", new ColumnMapping(null, "NO_SUCH_TYPE", false));
        assertThrows(Exception.class, type::exportDdlAndData);
    }

    /**
     * Mappings which change nothing, and no mappings, import the tables as before
     */
    @Test
    void importsAsBeforeWithoutMappings() throws Exception {
        String source = source();
        String plain = newUrl();
        importer(source, plain).exportDdlAndData();
        String identity = newUrl();
        ImportExporter importExporter = importer(source, identity);
        mappings(importExporter, "ORDERS").put("NOTE", new ColumnMapping());
        mappings(importExporter, "ORDERS").put("AMOUNT", new ColumnMapping("AMOUNT", null, false));
        importExporter.exportDdlAndData();
        for (String table : List.of("ORDERS", "CUSTOMERS")) {
            assertEquals(columns(plain, table), columns(identity, table));
            assertEquals(values(plain, "SELECT * FROM " + table + " ORDER BY 1"), values(identity, "SELECT * FROM "
                    + table + " ORDER BY 1"));
        }

        // The dialect returns what it is given without mappings
        H2Dialect dialect = new H2Dialect();
        String[] names = {"A", "B"};
        assertSame(names, dialect.getTargetColumnNames("T", names));
        assertEquals("A,B", dialect.getTargetColumnNames("T", "A,B"));
        assertEquals("A", dialect.getTargetColumnName("T", "A"));
        assertFalse(dialect.isColumnSkipped("T", "A"));
        assertNull(dialect.getMappedColumnType("T", "A"));
        assertNull(dialect.getColumnMapping(null, "A"));
        dialect.setColumnMappings(null);
        assertTrue(dialect.getColumnMappings().isEmpty());
        dialect.setColumnMappings(Map.of("T", Map.of("A", new ColumnMapping("X", "TEXT", false))));
        assertEquals("X,B", dialect.getTargetColumnNames("T", "A,B"));
        assertEquals(Dialect.VERBATIM_TYPE_PREFIX + "TEXT", dialect.getMappedColumnType("T", "A"));
        assertNull(dialect.getColumnMapping("T", null));
        assertNull(dialect.getColumnMapping("U", "A"));
        // Copied with the dialect of another version
        assertEquals("X", dialect.forVersion(2, 0).getTargetColumnName("T", "A"));

        ColumnMapping mapping = new ColumnMapping();
        assertTrue(mapping.isIdentity());
        assertEquals("A", mapping.getTargetName("A"));
        assertFalse(ColumnMapping.skip().isIdentity());
        assertEquals(new ColumnMapping(" X ", "", false), new ColumnMapping("X", null, false));
        assertEquals(new ColumnMapping("X", null, false).hashCode(), new ColumnMapping("X", null, false).hashCode());
        assertFalse(new ColumnMapping("X", null, false).equals("X"));
        assertTrue(ColumnMapping.skip().toString().contains("skipped=true"));
    }
}
