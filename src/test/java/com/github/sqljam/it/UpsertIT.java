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
package com.github.sqljam.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.ImpExpException;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.impexp.ScriptExporter;
import com.github.sqljam.impexp.WriteMode;
import com.github.sqljam.jdbc.ManualTransaction;
import com.github.sqljam.jdbc.SharedConnectionFactory;

/**
 * @Description: UpsertIT upserts the fixture tables of H2 into every database: rows changed and removed in the
 *               target are written again by their primary keys (composite keys included), kept tables, indexes and
 *               constraints are skipped. Direct imports, packages of sql scripts and packages of CSV files are
 *               verified, ClickHouse refuses upserts, a table without key is inserted and chosen key columns are
 *               used.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class UpsertIT {

    private static final ItDatabase SOURCE = ItDatabase.H2;

    private static ImportExporter newImporter(ItDatabase target, WriteMode writeMode, boolean recreated) {
        ImportExporter importExporter = new ImportExporter();
        SOURCE.configureSource(importExporter.getExportConfiguration());
        Exporter.ExportConfiguration source = importExporter.getExportConfiguration();
        source.setIdReused(true);
        source.setPageSize(100);
        source.setWriteMode(writeMode);
        source.setTableRecreated(recreated);
        target.configureTarget(importExporter.getImportConfiguration());
        return importExporter;
    }

    private static void importDirectly(ItDatabase target, WriteMode writeMode, boolean recreated) throws Exception {
        ImportExporter importExporter = newImporter(target, writeMode, recreated);
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        importExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors(), writeMode + " of " + SOURCE + " -> " + target);
    }

    /**
     * Rows of the target are changed and removed: notes of every employee, all tags and one sale
     */
    private static void tamper(ItDatabase target) throws Exception {
        try (Connection connection = target.getTargetConnection(); Statement statement = connection.createStatement()) {
            ItVerifier verifier = new ItVerifier(SOURCE, target, connection);
            statement.executeUpdate("UPDATE " + verifier.ref("emp") + " SET note = 'tampered'");
            statement.executeUpdate("DELETE FROM " + verifier.ref("emp_tag"));
            statement.executeUpdate("DELETE FROM " + verifier.ref("sales") + " WHERE id = 1");
            // The row inserted by the verification of identity values
            statement.executeUpdate("DELETE FROM " + verifier.ref("dept") + " WHERE name = 'New Dept'");
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            assertEquals(0, verifier.count("emp_tag"));
        }
    }

    private static void verify(ItDatabase target, boolean instantEquality) throws Exception {
        try (Connection connection = target.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(SOURCE, target, connection);
            if (instantEquality) {
                verifier.withInstantEquality();
            }
            verifier.verifyAll();
        }
    }

    @ParameterizedTest(name = "upsert into {0}")
    @EnumSource(ItDatabase.class)
    void upsertDirectly(ItDatabase target) throws Exception {
        assumeTrue(target.getDbType().createDialect().isUpsertSupported());
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        SOURCE.loadFixture();
        importDirectly(target, WriteMode.INSERT, true);
        tamper(target);
        // Kept tables: existing tables, indexes and constraints are skipped, rows are updated or inserted
        importDirectly(target, WriteMode.UPSERT, false);
        verify(target, false);
        // Again with nothing changed but the verification row
        tamper(target);
        importDirectly(target, WriteMode.UPSERT, false);
        verify(target, false);
    }

    private static File exportPackage(ItDatabase target, DataFormat format) throws Exception {
        return exportPackage(target, format, WriteMode.UPSERT);
    }

    private static File exportPackage(ItDatabase target, DataFormat format, WriteMode writeMode) throws Exception {
        File dir = new File(ItDatabase.DATA_DIR, "upsert/" + format + "-" + writeMode + "-" + target);
        FileUtils.deleteQuietly(dir);
        CollectingListener listener = new CollectingListener();
        Exporter.ExportConfiguration configuration;
        if (format == DataFormat.CSV) {
            CsvExporter exporter = new CsvExporter(dir);
            configuration = exporter.getConfiguration();
            SOURCE.configureSource(configuration);
            exporter.setTargetDbType(target.getDbType());
            exporter.setTargetCatalogName(target.getTargetCatalog());
            exporter.setTargetSchemaName(target.getTargetSchema());
            exporter.setExportListener(listener);
            configure(configuration, writeMode);
            exporter.exportDdlAndData();
        } else {
            ScriptExporter exporter = new ScriptExporter(dir, DataFileStrategy.SINGLE_FILE, 8 * 1024);
            configuration = exporter.getConfiguration();
            SOURCE.configureSource(configuration);
            exporter.setTargetDbType(target.getDbType());
            exporter.setTargetCatalogName(target.getTargetCatalog());
            exporter.setTargetSchemaName(target.getTargetSchema());
            exporter.setExportListener(listener);
            configure(configuration, writeMode);
            exporter.exportDdlAndData();
        }
        assertEquals(List.of(), listener.getErrors());
        return dir;
    }

    private static void configure(Exporter.ExportConfiguration configuration, WriteMode writeMode) {
        configuration.setIdReused(true);
        configuration.setWriteMode(writeMode);
        configuration.setTableRecreated(false);
    }

    @ParameterizedTest(name = "upsert packages into {0}")
    @EnumSource(ItDatabase.class)
    void upsertPackages(ItDatabase target) throws Exception {
        assumeTrue(target.getDbType().createDialect().isUpsertSupported());
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        SOURCE.loadFixture();
        for (DataFormat format : new DataFormat[]{DataFormat.SQL, DataFormat.CSV}) {
            File dir = exportPackage(target, format);
            importDirectly(target, WriteMode.INSERT, true);
            tamper(target);
            try (Connection connection = target.getTargetConnection()) {
                AbstractFileImporter importer = AbstractFileImporter.forDirectory(dir, connection, target.getDbType());
                CollectingListener listener = new CollectingListener();
                importer.setExportListener(listener);
                importer.importDirectory(dir);
                assertEquals(List.of(), listener.getErrors(), format + " package of " + target);
            }
            verify(target, format == DataFormat.CSV);
        }
    }

    /**
     * Rows of the target are kept, missing rows are inserted: the notes of the employees stay tampered, removed
     * tags and sales come back, by a direct import and by SQL and CSV packages
     */
    @ParameterizedTest(name = "skip existing rows of {0}")
    @EnumSource(ItDatabase.class)
    void skipExistingRows(ItDatabase target) throws Exception {
        assumeTrue(target.getDbType().createDialect().isUpsertSupported());
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        SOURCE.loadFixture();
        for (DataFormat format : new DataFormat[]{null, DataFormat.SQL, DataFormat.CSV}) {
            importDirectly(target, WriteMode.INSERT, true);
            tamper(target);
            if (format == null) {
                importDirectly(target, WriteMode.SKIP_EXISTING, false);
            } else {
                File dir = exportPackage(target, format, WriteMode.SKIP_EXISTING);
                try (Connection connection = target.getTargetConnection()) {
                    AbstractFileImporter importer = AbstractFileImporter.forDirectory(dir, connection,
                            target.getDbType());
                    CollectingListener listener = new CollectingListener();
                    importer.setExportListener(listener);
                    importer.importDirectory(dir);
                    assertEquals(List.of(), listener.getErrors(), format + " package of " + target);
                }
            }
            try (Connection connection = target.getTargetConnection()) {
                ItVerifier verifier = new ItVerifier(SOURCE, target, connection);
                String label = (format == null ? "direct" : format) + " import into " + target;
                assertEquals(ItDatabase.EMP_COUNT, verifier.count("emp"), label);
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + verifier.ref("emp") + " WHERE note = 'tampered'")) {
                    rs.next();
                    assertEquals(ItDatabase.EMP_COUNT, rs.getLong(1), label);
                }
                try (Connection source = SOURCE.getSourceConnection()) {
                    ItVerifier sourceVerifier = new ItVerifier(SOURCE, SOURCE, source, "PUBLIC");
                    for (String table : new String[]{"emp_tag", "sales"}) {
                        assertEquals(sourceVerifier.count(table), verifier.count(table), table + " of " + label);
                    }
                }
            }
        }
    }

    /**
     * Rows of the same key exist already and fail: they are skipped and reported, the missing rows are written
     * by commits every N rows and in one transaction. One transaction of DuckDB has no savepoints and is rolled
     * back.
     */
    @ParameterizedTest(name = "skip rows with errors of {0}")
    @EnumSource(ItDatabase.class)
    void skipRowsWithErrors(ItDatabase target) throws Exception {
        assumeTrue(target.getDbType().isTransactionSupported());
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        try (Connection source = SOURCE.getSourceConnection(); Statement statement = source.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS sjh_skip");
            statement.execute("CREATE TABLE sjh_skip (id INT PRIMARY KEY, note VARCHAR(20))");
            statement.execute("INSERT INTO sjh_skip SELECT X, 'row ' || X FROM SYSTEM_RANGE(1, 6)");
        }
        try {
            ImportExporter create = newImporter(target, WriteMode.INSERT, true);
            create.getExportConfiguration().setIncludedTableNames(new String[]{"SJH_SKIP"});
            create.setExportListener(new CollectingListener());
            create.exportDdlAndData();
            for (boolean oneTransaction : new boolean[]{false, true}) {
                String label = (oneTransaction ? "one transaction of " : "commits every N rows of ") + target;
                try (Connection connection = target.getTargetConnection(); Statement statement = connection
                        .createStatement()) {
                    ItVerifier verifier = new ItVerifier(SOURCE, target, connection);
                    statement.executeUpdate("DELETE FROM " + verifier.ref("skip") + " WHERE id IN (2, 4)");
                    if (!connection.getAutoCommit()) {
                        connection.commit();
                    }
                }
                ImportExporter importExporter = newImporter(target, WriteMode.INSERT, false);
                importExporter.getExportConfiguration().setIncludedTableNames(new String[]{"SJH_SKIP"});
                importExporter.getExportConfiguration().setPageSize(2);
                importExporter.getExportConfiguration().setFailFast(false);
                CollectingListener listener = new CollectingListener();
                importExporter.setExportListener(listener);
                boolean rolledBack = false;
                if (oneTransaction) {
                    try (ManualTransaction transaction = new ManualTransaction(target.getTargetConnection(),
                            target.getDbType().isTransactionalDdl())) {
                        importExporter.setTargetConnectionFactory(new SharedConnectionFactory(
                                transaction.getConnection()));
                        importExporter.exportData();
                        rolledBack = transaction.isRolledBack();
                        if (!rolledBack) {
                            assertEquals(List.of(), transaction.commit(), label);
                        }
                    }
                } else {
                    importExporter.exportData();
                }
                try (Connection connection = target.getTargetConnection()) {
                    ItVerifier verifier = new ItVerifier(SOURCE, target, connection);
                    if (rolledBack) {
                        // Without savepoints the transaction can not go on (DuckDB)
                        assertEquals(DbType.DUCKDB, target.getDbType(), label);
                        assertEquals(4, verifier.count("skip"), label);
                        continue;
                    }
                    assertEquals(6, verifier.count("skip"), label);
                }
                List<String> skipped = listener.getErrors();
                assertEquals(4, skipped.size(), label + ": " + skipped);
                assertTrue(skipped.stream().allMatch(error -> error.toLowerCase().startsWith(
                        "skipped row of table 'sjh_skip' (id=")), label + ": " + skipped);
            }
        } finally {
            // The table of the source is not a fixture table of other tests
            try (Connection source = SOURCE.getSourceConnection(); Statement statement = source.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS sjh_skip");
            }
            try (Connection connection = target.getTargetConnection(); Statement statement = connection
                    .createStatement()) {
                statement.execute("DROP TABLE " + new ItVerifier(SOURCE, target, connection).ref("skip"));
            } catch (Exception | AssertionError e) {
                // The table was not created
            }
        }
    }

    /**
     * The target table exists with names which differ in case from the source: the table, the columns and the
     * keys are matched without case, rows are inserted and then upserted
     */
    @ParameterizedTest(name = "names in other case of {0}")
    @EnumSource(ItDatabase.class)
    void matchesNamesWhichDifferInCase(ItDatabase target) throws Exception {
        assumeTrue(target.getDbType().createDialect().isUpsertSupported());
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        try (Connection source = SOURCE.getSourceConnection(); Statement statement = source.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS \"sjh_case\"");
            statement.execute("CREATE TABLE \"sjh_case\" (\"OrderId\" INT PRIMARY KEY, \"customerName\" VARCHAR(20),"
                    + " \"amount\" INT)");
            statement.execute("INSERT INTO \"sjh_case\" VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)");
        }
        com.github.sqljam.impexp.Dialect dialect = target.getDbType().createDialect();
        String table = (target.getTargetSchema() != null && target.getDbType().isSchemaSupported()
                ? quote(dialect, target.getTargetSchema()) + "." : "") + quote(dialect, "SJH_CASE");
        try (Connection connection = target.getTargetConnection(); Statement statement = connection.createStatement()) {
            // Tables of both cases, e.g. a copy of the source table by an earlier run
            String prefix = table.substring(0, table.length() - quote(dialect, "SJH_CASE").length());
            for (String name : new String[]{table, prefix + quote(dialect, "sjh_case")}) {
                // DuckDB closes a statement when it fails
                try (Statement drop = connection.createStatement()) {
                    drop.execute("DROP TABLE " + name);
                } catch (Exception e) {
                    // The table does not exist
                }
            }
            // Columns of primary keys are NOT NULL in Db2
            statement.execute("CREATE TABLE " + table + " (" + quote(dialect, "orderid")
                    + " INT NOT NULL PRIMARY KEY, " + quote(dialect, "CUSTOMERNAME") + " VARCHAR(20), "
                    + quote(dialect, "Amount") + " INT)");
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
        try {
            for (WriteMode writeMode : new WriteMode[]{WriteMode.INSERT, WriteMode.UPSERT}) {
                if (writeMode == WriteMode.UPSERT) {
                    try (Connection source = SOURCE.getSourceConnection();
                         Statement statement = source.createStatement()) {
                        statement.execute("UPDATE \"sjh_case\" SET \"amount\" = \"amount\" + 1");
                        statement.execute("INSERT INTO \"sjh_case\" VALUES (4, 'd', 40)");
                    }
                }
                ImportExporter importExporter = newImporter(target, writeMode, false);
                importExporter.getExportConfiguration().setIncludedTableNames(new String[]{"sjh_case"});
                CollectingListener listener = new CollectingListener();
                importExporter.setExportListener(listener);
                importExporter.exportData();
                assertEquals(List.of(), listener.getErrors(), writeMode + " into " + target);
            }
            try (Connection connection = target.getTargetConnection(); Statement statement = connection
                    .createStatement(); ResultSet rs = statement.executeQuery("SELECT " + quote(dialect, "orderid")
                    + ", " + quote(dialect, "CUSTOMERNAME") + ", " + quote(dialect, "Amount") + " FROM " + table
                    + " ORDER BY 1")) {
                List<String> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    rows.add(rs.getInt(1) + " " + rs.getString(2) + " " + rs.getInt(3));
                }
                assertEquals(List.of("1 a 11", "2 b 21", "3 c 31", "4 d 40"), rows, target.toString());
            }
        } finally {
            try (Connection connection = target.getTargetConnection(); Statement statement = connection
                    .createStatement()) {
                statement.execute("DROP TABLE " + table);
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
            try (Connection source = SOURCE.getSourceConnection(); Statement statement = source.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS \"sjh_case\"");
            }
        }
    }

    private static String quote(com.github.sqljam.impexp.Dialect dialect, String name) {
        return dialect.getOpenQuote() + name + dialect.getCloseQuote();
    }

    @Test
    void refusesWithoutUpsert() {
        ItDatabase target = ItDatabase.CLICKHOUSE;
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        ImpExpException e = assertThrows(ImpExpException.class, () -> newImporter(target, WriteMode.UPSERT, false)
                .exportDdlAndData());
        assertTrue(e.getMessage().contains("can not match rows by their key"), e.getMessage());
    }

    private static long count(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Tables without primary key are inserted, chosen key columns identify the rows of other tables
     */
    @Test
    void usesChosenKeysAndInsertsTablesWithoutKey() throws Exception {
        String source = "jdbc:h2:mem:up" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        String target = "jdbc:h2:mem:up" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(source, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE LOGS (MESSAGE VARCHAR(20))");
            statement.execute("INSERT INTO LOGS VALUES ('a'), ('b')");
            statement.execute("CREATE TABLE USERS (ID INT PRIMARY KEY, EMAIL VARCHAR(40), NAME VARCHAR(20))");
            statement.execute("INSERT INTO USERS VALUES (1, 'a@x.io', 'A'), (2, 'b@x.io', 'B')");
        }
        try (Connection connection = DriverManager.getConnection(target, "sa", "");
             Statement statement = connection.createStatement()) {
            // The same email with another id
            statement.execute("CREATE TABLE USERS (ID INT, EMAIL VARCHAR(40), NAME VARCHAR(20))");
            statement.execute("INSERT INTO USERS VALUES (7, 'a@x.io', 'old')");
        }
        for (int i = 0; i < 2; i++) {
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
            configuration.setWriteMode(WriteMode.UPSERT);
            configuration.getKeyColumnNames().put("USERS", List.of("EMAIL"));
            importExporter.getImportConfiguration().setDbType(DbType.H2);
            importExporter.getImportConfiguration().setUrl(target);
            importExporter.getImportConfiguration().setUsername("sa");
            importExporter.getImportConfiguration().setPassword("");
            importExporter.getImportConfiguration().setConnectionPoolEnabled(false);
            CollectingListener listener = new CollectingListener();
            importExporter.setExportListener(listener);
            importExporter.exportDdlAndData();
            assertEquals(List.of(), listener.getErrors());
            assertTrue(listener.getMessages().stream().anyMatch(message -> message.startsWith("Table LOGS has no "
                    + "primary key")), listener.getMessages().toString());
        }
        // Inserted twice without key
        assertEquals(4, count(target, "SELECT COUNT(*) FROM LOGS"));
        // Matched by email: the row of a@x.io is updated with the source id, b@x.io is inserted once
        assertEquals(2, count(target, "SELECT COUNT(*) FROM USERS"));
        assertEquals(1, count(target, "SELECT COUNT(*) FROM USERS WHERE EMAIL = 'a@x.io' AND ID = 1 AND NAME = 'A'"));
    }
}
