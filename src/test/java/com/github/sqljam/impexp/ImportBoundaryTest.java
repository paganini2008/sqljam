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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.github.sqljam.it.CollectingListener;
import com.github.sqljam.jdbc.ManualTransaction;
import com.github.sqljam.jdbc.SharedConnectionFactory;

/**
 * @Description: ImportBoundaryTest verifies upserts and transactions of imports at their boundaries with H2: empty
 *               tables, NULL values, missing key columns, key columns narrowed away by a query, new tables, an error
 *               in the middle of commits every N rows and in one transaction, rows with errors which are skipped,
 *               existing rows which are skipped, and a transaction without rows
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ImportBoundaryTest {

    private static String newUrl() {
        return "jdbc:h2:mem:ib" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static void execute(String url, String... sqls) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> column(String url, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static ImportExporter importer(String source, String target, WriteMode writeMode) {
        ImportExporter importExporter = new ImportExporter();
        Exporter.ExportConfiguration configuration = importExporter.getExportConfiguration();
        configuration.setDbType(DbType.H2);
        configuration.setUrl(source);
        configuration.setUsername("sa");
        configuration.setPassword("");
        configuration.setIncludedSchemaNames(new String[]{"PUBLIC"});
        configuration.setIdReused(true);
        configuration.setConnectionPoolEnabled(false);
        configuration.setTableRecreated(false);
        configuration.setWriteMode(writeMode);
        configuration.setPageSize(2);
        importExporter.getImportConfiguration().setDbType(DbType.H2);
        importExporter.getImportConfiguration().setUrl(target);
        importExporter.getImportConfiguration().setUsername("sa");
        importExporter.getImportConfiguration().setPassword("");
        importExporter.getImportConfiguration().setConnectionPoolEnabled(false);
        return importExporter;
    }

    @Test
    void upsertsEmptyTablesAndNulls() throws Exception {
        String source = newUrl();
        String target = newUrl();
        execute(source, "CREATE TABLE EMPTY (ID INT PRIMARY KEY)",
                "CREATE TABLE ITEMS (ID INT PRIMARY KEY, NOTE VARCHAR(20))", "INSERT INTO ITEMS VALUES (1, NULL)");
        execute(target, "CREATE TABLE ITEMS (ID INT PRIMARY KEY, NOTE VARCHAR(20))",
                "INSERT INTO ITEMS VALUES (1, 'old')");
        CollectingListener listener = new CollectingListener();
        ImportExporter importExporter = importer(source, target, WriteMode.UPSERT);
        importExporter.setExportListener(listener);
        importExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors());
        // The empty table is created, NULL replaces the old value
        assertEquals(List.of("0"), column(target, "SELECT COUNT(*) FROM EMPTY"));
        assertEquals(1, column(target, "SELECT NOTE FROM ITEMS").size());
        assertNull(column(target, "SELECT NOTE FROM ITEMS").get(0));
    }

    @Test
    void refusesKeysWhichAreNotImported() throws Exception {
        String source = newUrl();
        String target = newUrl();
        execute(source, "CREATE TABLE ITEMS (ID INT PRIMARY KEY, NOTE VARCHAR(20))", "INSERT INTO ITEMS VALUES (1, 'a')");
        // A chosen column which does not exist
        ImportExporter missing = importer(source, target, WriteMode.UPSERT);
        missing.getExportConfiguration().getKeyColumnNames().put("ITEMS", List.of("CODE"));
        ImpExpException e = assertThrows(ImpExpException.class, missing::exportDdlAndData);
        assertTrue(e.getMessage().contains("Key column CODE of table ITEMS is not imported"), e.getMessage());
        // The primary key is not a column of the query
        ImportExporter narrowed = importer(source, target, WriteMode.UPSERT);
        narrowed.getExportConfiguration().getTableQueries().put("ITEMS", new TableQuery(List.of("NOTE"), null, null,
                null));
        e = assertThrows(ImpExpException.class, narrowed::exportDdlAndData);
        assertTrue(e.getMessage().contains("Key column ID of table ITEMS is not imported"), e.getMessage());
    }

    private static String failingSource() throws Exception {
        String source = newUrl();
        execute(source, "CREATE TABLE ITEMS (ID INT PRIMARY KEY, AMOUNT INT)",
                "INSERT INTO ITEMS SELECT X, X FROM SYSTEM_RANGE(1, 6)");
        return source;
    }

    /**
     * The fifth row breaks a check of the target: pages of two rows before it are committed, one transaction keeps
     * nothing
     */
    @Test
    void errorInTheMiddleOfAnImport() throws Exception {
        String source = failingSource();
        String everyRows = newUrl();
        String oneTransaction = newUrl();
        for (String target : new String[]{everyRows, oneTransaction}) {
            execute(target, "CREATE TABLE ITEMS (ID INT PRIMARY KEY, AMOUNT INT CHECK (AMOUNT <> 5))");
        }
        ImportExporter pages = importer(source, everyRows, WriteMode.INSERT);
        pages.setExportListener(new CollectingListener());
        assertThrows(Exception.class, pages::exportDdlAndData);
        assertEquals(List.of("1", "2", "3", "4"), column(everyRows, "SELECT ID FROM ITEMS ORDER BY ID"));

        ImportExporter transactional = importer(source, oneTransaction, WriteMode.INSERT);
        transactional.setExportListener(new CollectingListener());
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(oneTransaction, "sa",
                ""), false)) {
            transactional.setTargetConnectionFactory(new SharedConnectionFactory(transaction.getConnection()));
            assertThrows(Exception.class, transactional::exportDdlAndData);
            assertTrue(transaction.isRolledBack());
        }
        assertEquals(List.of(), column(oneTransaction, "SELECT ID FROM ITEMS"));
    }

    @Test
    void commitsTransactionWithoutRows() throws Exception {
        String source = newUrl();
        String target = newUrl();
        execute(source, "CREATE TABLE EMPTY (ID INT PRIMARY KEY)");
        ImportExporter importExporter = importer(source, target, WriteMode.INSERT);
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(target, "sa", ""),
                false)) {
            importExporter.setTargetConnectionFactory(new SharedConnectionFactory(transaction.getConnection()));
            importExporter.exportDdlAndData();
            assertEquals(List.of(), transaction.commit());
        }
        assertEquals(List.of("0"), column(target, "SELECT COUNT(*) FROM EMPTY"));
    }

    /**
     * The fifth row breaks a check of the target and is skipped: the other rows are written by commits every N
     * rows and in one transaction, each skipped row is reported with its key
     */
    @Test
    void skipsRowsWithErrors() throws Exception {
        String source = failingSource();
        String everyRows = newUrl();
        String oneTransaction = newUrl();
        for (String target : new String[]{everyRows, oneTransaction}) {
            execute(target, "CREATE TABLE ITEMS (ID INT PRIMARY KEY, AMOUNT INT CHECK (AMOUNT <> 5))");
        }
        ImportExporter pages = importer(source, everyRows, WriteMode.INSERT);
        pages.getExportConfiguration().setFailFast(false);
        List<Long> tableRows = new ArrayList<>();
        CollectingListener listener = new CollectingListener() {
            @Override
            public void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
                tableRows.add(processedRows);
            }
        };
        pages.setExportListener(listener);
        pages.exportDdlAndData();
        assertEquals(List.of("1", "2", "3", "4", "6"), column(everyRows, "SELECT ID FROM ITEMS ORDER BY ID"));
        assertEquals(1, listener.getErrors().size());
        // The table reports the written rows, the skipped row is not counted
        assertEquals(List.of(5L), tableRows);
        assertTrue(listener.getErrors().get(0).startsWith("Skipped row of table 'ITEMS' (ID=5): "),
                listener.getErrors().get(0));

        ImportExporter transactional = importer(source, oneTransaction, WriteMode.INSERT);
        transactional.getExportConfiguration().setFailFast(false);
        listener = new CollectingListener();
        transactional.setExportListener(listener);
        try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(oneTransaction, "sa",
                ""), false)) {
            transactional.setTargetConnectionFactory(new SharedConnectionFactory(transaction.getConnection()));
            transactional.exportDdlAndData();
            // The savepoint of the page keeps the transaction
            assertTrue(!transaction.isRolledBack());
            assertEquals(List.of(), transaction.commit());
        }
        assertEquals(List.of("1", "2", "3", "4", "6"), column(oneTransaction, "SELECT ID FROM ITEMS ORDER BY ID"));
        assertEquals(1, listener.getErrors().size());
    }

    /**
     * DuckDB has no savepoints: commits every N rows write the rows of a failed page one by one, one transaction
     * can not go on and is rolled back
     */
    @Test
    void skipsRowsWithoutSavepoints(@org.junit.jupiter.api.io.TempDir java.io.File dir) throws Exception {
        String source = failingSource();
        for (boolean managed : new boolean[]{false, true}) {
            String target = "jdbc:duckdb:" + new java.io.File(dir, "skip" + managed + ".duckdb").getAbsolutePath();
            try (Connection connection = DriverManager.getConnection(target);
                 Statement statement = connection.createStatement()) {
                // Tables of the source schema PUBLIC go into the schema public
                statement.execute("CREATE SCHEMA public");
                statement.execute("CREATE TABLE public.items (id INT PRIMARY KEY, amount INT CHECK (amount <> 5))");
            }
            ImportExporter importExporter = importer(source, target, WriteMode.INSERT);
            importExporter.getImportConfiguration().setUsername(null);
            importExporter.getImportConfiguration().setPassword(null);
            importExporter.getExportConfiguration().setFailFast(false);
            CollectingListener listener = new CollectingListener();
            importExporter.setExportListener(listener);
            if (!managed) {
                importExporter.exportDdlAndData();
                assertEquals(List.of("1", "2", "3", "4", "6"), duckColumn(target));
                assertEquals(1, listener.getErrors().size());
                continue;
            }
            try (ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(target), true)) {
                importExporter.setTargetConnectionFactory(new SharedConnectionFactory(transaction.getConnection()));
                importExporter.exportDdlAndData();
                assertTrue(transaction.isRolledBack());
                assertTrue(listener.getErrors().stream().anyMatch(error -> error.contains(
                        "can not be skipped in one transaction without savepoints")), listener.getErrors().toString());
            }
            assertEquals(List.of(), duckColumn(target));
        }
    }

    private static List<String> duckColumn(String url) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM public.items ORDER BY id")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    /**
     * Rows of existing keys are kept, rows of new keys are inserted, a table without key is inserted
     */
    @Test
    void skipsExistingRows() throws Exception {
        String source = newUrl();
        String target = newUrl();
        execute(source, "CREATE TABLE ITEMS (ID INT PRIMARY KEY, NOTE VARCHAR(20))",
                "INSERT INTO ITEMS VALUES (1, 'new'), (2, 'b'), (3, 'c')",
                "CREATE TABLE LOGS (NOTE VARCHAR(20))", "INSERT INTO LOGS VALUES ('x')");
        execute(target, "CREATE TABLE ITEMS (ID INT PRIMARY KEY, NOTE VARCHAR(20))",
                "INSERT INTO ITEMS VALUES (1, 'old')", "CREATE TABLE LOGS (NOTE VARCHAR(20))",
                "INSERT INTO LOGS VALUES ('x')");
        CollectingListener listener = new CollectingListener();
        ImportExporter importExporter = importer(source, target, WriteMode.SKIP_EXISTING);
        importExporter.setExportListener(listener);
        importExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors());
        assertEquals(List.of("old", "b", "c"), column(target, "SELECT NOTE FROM ITEMS ORDER BY ID"));
        assertEquals(List.of("x", "x"), column(target, "SELECT NOTE FROM LOGS"));
        // Chosen key columns: NOTE of the first row is new, the row is inserted and breaks the primary key
        ImportExporter chosen = importer(source, target, WriteMode.SKIP_EXISTING);
        chosen.getExportConfiguration().setIncludedTableNames(new String[]{"ITEMS"});
        chosen.getExportConfiguration().getKeyColumnNames().put("ITEMS", List.of("NOTE"));
        chosen.getExportConfiguration().setFailFast(false);
        listener = new CollectingListener();
        chosen.setExportListener(listener);
        chosen.exportDdlAndData();
        assertEquals(1, listener.getErrors().size());
        assertTrue(listener.getErrors().get(0).startsWith("Skipped row of table 'ITEMS' (NOTE=new): "),
                listener.getErrors().get(0));
        assertEquals(List.of("old", "b", "c"), column(target, "SELECT NOTE FROM ITEMS ORDER BY ID"));
    }

    @Test
    void describesRows() {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("ID", 7);
        row.put("DATA", new byte[3]);
        row.put("NOTE", "x".repeat(80));
        assertEquals("id=7, data=<3 bytes>, NOTE=" + "x".repeat(47) + "...", ImportExportHandler.describeRow(row,
                List.of("id", "data", "NOTE")));
        assertEquals("MISSING=null", ImportExportHandler.describeRow(row, List.of("MISSING")));
    }
}

