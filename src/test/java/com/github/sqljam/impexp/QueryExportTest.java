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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.it.CollectingListener;

/**
 * @Description: QueryExportTest exports free queries like tables at their boundaries with H2: a join whose columns
 *               have the same names, expressions without sizes, an empty result, rows of more pages than one, and
 *               a query with an error. Every exit is used: a database, SQL, CSV and Parquet packages.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class QueryExportTest {

    private static final String JOIN = "SELECT o.ID, c.ID, c.NAME, o.AMOUNT * 2 AS DOUBLED,"
            + " CASE WHEN o.AMOUNT > 5 THEN 'big' ELSE 'small' END AS SIZE_TEXT, o.CREATED"
            + " FROM ORDERS o JOIN CUSTOMERS c ON c.ID = o.CUSTOMER_ID ORDER BY o.ID";

    @TempDir
    File dir;

    private static String newUrl() {
        return "jdbc:h2:mem:qe" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static String source() throws Exception {
        String url = newUrl();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE CUSTOMERS (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            statement.execute("CREATE TABLE ORDERS (ID INT PRIMARY KEY, CUSTOMER_ID INT, AMOUNT DECIMAL(10,2),"
                    + " CREATED TIMESTAMP)");
            statement.execute("INSERT INTO CUSTOMERS VALUES (1, 'Ann'), (2, 'Bob')");
            statement.execute("INSERT INTO ORDERS SELECT X, MOD(X, 2) + 1, X, TIMESTAMP '2026-01-01 10:00:00'"
                    + " FROM SYSTEM_RANGE(1, 7)");
        }
        return url;
    }

    private static void configure(Exporter.ExportConfiguration configuration, String source, String table,
                                  String sql) {
        configuration.setDbType(DbType.H2);
        configuration.setUrl(source);
        configuration.setUsername("sa");
        configuration.setPassword("");
        configuration.setConnectionPoolEnabled(false);
        configuration.setIncludedSchemaNames(new String[]{"PUBLIC"});
        configuration.setPageSize(3);
        configuration.getQueries().put(table, sql);
    }

    private static List<List<String>> rows(String url, String sql) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static final List<List<String>> EXPECTED = List.of(
            List.of("1", "2", "Bob", "2.00", "small", "2026-01-01 10:00:00"),
            List.of("2", "1", "Ann", "4.00", "small", "2026-01-01 10:00:00"),
            List.of("7", "2", "Bob", "14.00", "big", "2026-01-01 10:00:00"));

    private static void verify(String target) throws Exception {
        List<List<String>> rows = rows(target, "SELECT ID, ID_2, NAME, DOUBLED, SIZE_TEXT, CREATED FROM"
                + " ORDER_SUMMARY ORDER BY ID");
        assertEquals(7, rows.size());
        assertEquals(EXPECTED.get(0), rows.get(0));
        assertEquals(EXPECTED.get(1), rows.get(1));
        assertEquals(EXPECTED.get(2), rows.get(6));
    }

    @Test
    void exportsQueryIntoDatabase() throws Exception {
        String source = source();
        String target = newUrl();
        ImportExporter importExporter = new ImportExporter();
        configure(importExporter.getExportConfiguration(), source, "ORDER_SUMMARY", JOIN);
        importExporter.getImportConfiguration().setDbType(DbType.H2);
        importExporter.getImportConfiguration().setUrl(target);
        importExporter.getImportConfiguration().setUsername("sa");
        importExporter.getImportConfiguration().setPassword("");
        importExporter.getImportConfiguration().setConnectionPoolEnabled(false);
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        importExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors());
        verify(target);
        // Only the query is exported, not the tables of the schema
        assertEquals(List.of(List.of("1")), rows(target, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC'"));
        // Columns of the same name can not be counted by a subquery, the rows are reported as they are read
        assertTrue(listener.getMessages().contains("0 rows of 1 tables to export"), listener.getMessages()
                .toString());

        // Rows of a query which can be counted
        String counted = newUrl();
        ImportExporter totals = new ImportExporter();
        configure(totals.getExportConfiguration(), source, "TOTALS", "SELECT CUSTOMER_ID, SUM(AMOUNT) AS TOTAL"
                + " FROM ORDERS GROUP BY CUSTOMER_ID");
        totals.getImportConfiguration().setDbType(DbType.H2);
        totals.getImportConfiguration().setUrl(counted);
        totals.getImportConfiguration().setUsername("sa");
        totals.getImportConfiguration().setPassword("");
        totals.getImportConfiguration().setConnectionPoolEnabled(false);
        CollectingListener totalsListener = new CollectingListener();
        totals.setExportListener(totalsListener);
        totals.exportDdlAndData();
        assertTrue(totalsListener.getMessages().contains("2 rows of 1 tables to export"), totalsListener
                .getMessages().toString());
        assertEquals(List.of(List.of("1", "12.00"), List.of("2", "16.00")), rows(counted, "SELECT * FROM TOTALS"
                + " ORDER BY 1"));
    }

    @Test
    void exportsQueryAsPackages() throws Exception {
        String source = source();
        for (DataFormat format : DataFormat.values()) {
            File packageDir = new File(dir, format.name());
            CollectingListener listener = new CollectingListener();
            if (format == DataFormat.SQL) {
                ScriptExporter exporter = new ScriptExporter(packageDir, DataFileStrategy.SINGLE_FILE, 0);
                configure(exporter.getConfiguration(), source, "ORDER_SUMMARY", JOIN);
                exporter.setExportListener(listener);
                exporter.exportDdlAndData();
            } else {
                DataFileExporter exporter = format == DataFormat.CSV ? new CsvExporter(packageDir)
                        : new ParquetExporter(packageDir);
                configure(exporter.getConfiguration(), source, "ORDER_SUMMARY", JOIN);
                exporter.setExportListener(listener);
                exporter.exportDdlAndData();
            }
            assertEquals(List.of(), listener.getErrors(), format.name());
            ExportManifest manifest = ExportManifest.read(packageDir);
            assertEquals(List.of("ORDER_SUMMARY"), manifest.getTables().stream().map(ExportManifest.TableEntry::getName)
                    .toList(), format.name());
            assertEquals(7, manifest.getTables().get(0).getRows(), format.name());
            String target = newUrl();
            try (Connection connection = DriverManager.getConnection(target, "sa", "")) {
                AbstractFileImporter importer = AbstractFileImporter.forDirectory(packageDir, connection, DbType.H2);
                CollectingListener importListener = new CollectingListener();
                importer.setExportListener(importListener);
                importer.importDirectory(packageDir);
                assertEquals(List.of(), importListener.getErrors(), format.name());
            }
            verify(target);
        }
    }

    @Test
    void exportsEmptyQueryAndRefusesBadQuery() throws Exception {
        String source = source();
        String target = newUrl();
        ImportExporter empty = new ImportExporter();
        configure(empty.getExportConfiguration(), source, "NONE", "SELECT ID, NAME FROM CUSTOMERS WHERE 1 = 0");
        empty.getImportConfiguration().setDbType(DbType.H2);
        empty.getImportConfiguration().setUrl(target);
        empty.getImportConfiguration().setUsername("sa");
        empty.getImportConfiguration().setPassword("");
        empty.getImportConfiguration().setConnectionPoolEnabled(false);
        empty.setExportListener(new CollectingListener());
        empty.exportDdlAndData();
        // The table is created from the columns of the query
        assertEquals(List.of(List.of("0")), rows(target, "SELECT COUNT(*) FROM NONE"));
        assertEquals(List.of(List.of("ID"), List.of("NAME")), rows(target, "SELECT COLUMN_NAME FROM"
                + " INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'NONE' ORDER BY ORDINAL_POSITION"));

        ImportExporter bad = new ImportExporter();
        configure(bad.getExportConfiguration(), source, "BAD", "SELECT MISSING FROM CUSTOMERS");
        bad.getImportConfiguration().setDbType(DbType.H2);
        bad.getImportConfiguration().setUrl(target);
        bad.getImportConfiguration().setUsername("sa");
        bad.getImportConfiguration().setPassword("");
        bad.getImportConfiguration().setConnectionPoolEnabled(false);
        bad.setExportListener(new CollectingListener());
        Exception e = assertThrows(Exception.class, bad::exportDdlAndData);
        assertTrue(String.valueOf(e.getMessage()).contains("MISSING"), e.getMessage());
    }

    /**
     * Labels of a result: names used before get a number, blank labels are named by their position
     */
    @Test
    void namesColumnsOfResults() throws Exception {
        try (Connection connection = DriverManager.getConnection(newUrl(), "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1 AS A, 2 AS \"a\", 3 AS A_2, 4 AS A")) {
            assertEquals(List.of("A", "a_2", "A_2_2", "A_3"), QueryMetaDataOperations.getColumnNames(
                    rs.getMetaData()));
        }
    }
}
