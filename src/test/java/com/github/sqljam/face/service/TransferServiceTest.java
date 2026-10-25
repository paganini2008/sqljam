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
package com.github.sqljam.face.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.view.UiDatabase;
import com.github.sqljam.impexp.CsvImporter;
import com.github.sqljam.impexp.DataFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.jdbc.ManualTransaction;

/**
 * @Description: TransferServiceTest verifies CSV export packages and imports in manual transactions of the user
 *               interface: direct imports, package imports and file imports are rolled back or committed, databases
 *               without transactions are refused
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class TransferServiceTest {

    @TempDir
    File dir;

    private final TransferService service = new TransferService();

    private ConnectionProfile target(String name) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setDbType(DbType.H2);
        profile.setDatabase(new File(dir, name).getAbsolutePath());
        profile.setUsername("sa");
        profile.setPassword("");
        return profile;
    }

    private static long count(ConnectionProfile profile, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private File exportCsv(ConnectionProfile source) throws Exception {
        File packageDir = new File(dir, "csv");
        TransferRequest request = new TransferRequest();
        request.setSource(source);
        request.setSourceCatalog(UiDatabase.CATALOG);
        request.setSourceSchema(UiDatabase.SCHEMA);
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(packageDir);
        request.setScriptDbType(DbType.H2);
        request.setDataFormat(DataFormat.CSV);
        request.setCsvDelimiter("|");
        request.setMaxFileSize(4 * 1024);
        service.transfer(request, ExportListener.NONE);
        return packageDir;
    }

    @Test
    void exportsCsvPackage() throws Exception {
        File packageDir = exportCsv(UiDatabase.create(dir, "source"));
        ExportManifest manifest = ExportManifest.read(packageDir);
        assertEquals(DataFormat.CSV, manifest.getDataFormat());
        assertEquals("|", manifest.getOptions().get("delimiter"));
        ExportManifest.TableEntry paged = manifest.getTables().stream().filter(table -> "T_PAGED".equals(table
                .getName())).findFirst().orElseThrow();
        assertTrue(paged.getDataFiles().size() > 1, "Parts of T_PAGED: " + paged.getDataFiles());
    }

    @Test
    void refusesDatabasesWithoutTransactions() {
        ConnectionProfile clickHouse = new ConnectionProfile();
        clickHouse.setDbType(DbType.CLICKHOUSE);
        assertThrows(IllegalArgumentException.class, () -> service.beginTransaction(clickHouse, null, null));
    }

    @Test
    void importsDirectlyInTransaction() throws Exception {
        ConnectionProfile source = UiDatabase.create(dir, "source");
        ConnectionProfile target = target("direct");
        TransferRequest request = new TransferRequest();
        request.setSource(source);
        request.setSourceCatalog(UiDatabase.CATALOG);
        request.setSourceSchema(UiDatabase.SCHEMA);
        request.setTarget(TransferRequest.Target.DATABASE);
        request.setTargetProfile(target);
        request.setOneTransaction(true);
        request.setFailFast(false);
        try (ManualTransaction transaction = service.beginTransaction(target, null, null)) {
            service.transfer(request, transaction, ExportListener.NONE);
            assertFalse(transaction.isRolledBack());
            transaction.rollback();
        }
        assertEquals(0, count(target, "T_PAGED"));
        try (ManualTransaction transaction = service.beginTransaction(target, null, null)) {
            service.transfer(request, transaction, ExportListener.NONE);
            assertEquals(List.of(), transaction.commit());
        }
        assertEquals(UiDatabase.PAGED_ROWS, count(target, "T_PAGED"));
        assertEquals(5, count(target, "T_ONE"));
    }

    /**
     * The report of a direct import: its header, the tables with their rows, the objects, and the rows of the
     * target which are compared with the written rows of tables which the import created
     */
    @Test
    void reportsDirectImport() throws Exception {
        ConnectionProfile source = UiDatabase.create(dir, "source");
        ConnectionProfile target = target("reported");
        TransferRequest request = new TransferRequest();
        request.setSource(source);
        request.setSourceCatalog(UiDatabase.CATALOG);
        request.setSourceSchema(UiDatabase.SCHEMA);
        request.setTarget(TransferRequest.Target.DATABASE);
        request.setTargetProfile(target);
        request.setTableRecreated(true);
        request.setTables(List.of("T_PAGED", "T_ONE"));
        TransferReport report = new TransferReport();
        TransferService.describeReport(request, report);
        assertEquals(TransferReport.Kind.DATABASE, report.getKind());
        assertEquals("source / " + UiDatabase.CATALOG + " / " + UiDatabase.SCHEMA, report.getSource());
        assertEquals("reported", report.getTarget());
        assertEquals("T_PAGED, T_ONE", report.getOptions().get("Tables"));
        assertEquals("true", report.getOptions().get("Tables recreated"));

        ReportRecorder recorder = new ReportRecorder(report);
        recorder.start();
        service.transfer(request, new ExportListener() {
            @Override
            public void onTableStart(String catalogName, String schemaName, String tableName, long totalRows) {
                recorder.tableStarted(catalogName, schemaName, tableName, totalRows);
            }

            @Override
            public void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
                recorder.tableEnded(catalogName, schemaName, tableName, processedRows);
            }

            @Override
            public void onDdlExecuted(String statement, boolean successful) {
                recorder.ddlExecuted(statement, successful);
            }
        });
        recorder.finish(TransferReport.Status.COMPLETED, null, null);
        // A table which was not reached is not counted
        report.table(null, null, "NEVER");
        service.verifyReport(request, report);

        assertEquals(2, report.getObjects().get("Tables"));
        TransferReport.TableReport paged = report.getTables().stream().filter(table -> "T_PAGED".equalsIgnoreCase(
                table.getName())).findFirst().orElseThrow();
        assertEquals(UiDatabase.PAGED_ROWS, paged.getWritten());
        assertEquals(UiDatabase.PAGED_ROWS, paged.getTargetRows());
        assertTrue(paged.getVerified());
        assertNull(report.table(null, null, "NEVER").getTargetRows());

        // Rows of tables which were there before are counted, not compared
        request.setTableRecreated(false);
        service.verifyReport(request, report);
        assertNull(paged.getVerified());
        // Nothing is counted in a package
        TransferReport packageReport = new TransferReport();
        packageReport.table(null, null, "T_PAGED").setStatus(TransferReport.TableStatus.COMPLETED);
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(new File(dir, "package"));
        service.verifyReport(request, packageReport);
        assertNull(packageReport.getTables().get(0).getTargetRows());
        TransferService.describeReport(request, packageReport);
        assertEquals(TransferReport.Kind.PACKAGE, packageReport.getKind());
        assertEquals(TransferReport.Transaction.NONE, packageReport.getTransaction());
    }

    @Test
    void importsPackageAndFilesInTransaction() throws Exception {
        File packageDir = exportCsv(UiDatabase.create(dir, "source"));
        ConnectionProfile target = target("package");
        try (ManualTransaction transaction = service.beginTransaction(target, null, null)) {
            assertTrue(service.importScripts(target, null, null, packageDir, true, 0, transaction,
                    ExportListener.NONE) instanceof CsvImporter);
            transaction.rollback();
        }
        assertEquals(0, count(target, "T_PAGED"));
        try (ManualTransaction transaction = service.beginTransaction(target, null, null)) {
            service.importScripts(target, null, null, packageDir, true, 0, transaction, ExportListener.NONE);
            transaction.commit();
        }
        assertEquals(UiDatabase.PAGED_ROWS, count(target, "T_PAGED"));
        // Commits every 100 rows typed in the dialog: pages of 100 rows
        ConnectionProfile paged = target("package100");
        // Rows of T_ONE and T_PAGED
        assertEquals(5 + UiDatabase.PAGED_ROWS, ((CsvImporter) service.importScripts(paged, null, null, packageDir,
                true, 100, null, ExportListener.NONE)).getImportedRows());
        assertEquals(UiDatabase.PAGED_ROWS, count(paged, "T_PAGED"));

        // CSV files of the package as files of other tools, the parts in order
        ExportManifest manifest = ExportManifest.read(packageDir);
        List<File> files = manifest.getTables().stream().filter(table -> "T_PAGED".equals(table.getName()))
                .findFirst().orElseThrow().getDataFiles().stream().map(path -> new File(packageDir, path)).toList();
        try (ManualTransaction transaction = service.beginTransaction(target, null, null)) {
            DataFileImporter importer = service.importFiles(target, null, null, files, "PAGED_COPY",
                    DataFileImporter.Mode.CREATE, transaction, ExportListener.NONE);
            assertTrue(importer instanceof CsvImporter);
            assertEquals(UiDatabase.PAGED_ROWS, importer.getImportedRows());
            transaction.commit();
        }
        assertEquals(UiDatabase.PAGED_ROWS, count(target, "PAGED_COPY"));
        // Without a transaction the rows are committed page by page
        assertEquals(UiDatabase.PAGED_ROWS, service.importParquet(target, null, null, files, "PAGED_AUTO",
                DataFileImporter.Mode.CREATE, ExportListener.NONE).getImportedRows());
        assertEquals(UiDatabase.PAGED_ROWS, count(target, "PAGED_AUTO"));
    }

    /**
     * Statements of the structure and skipped rows of package and file imports reach the listener of the user
     * interface through the listeners of the importers, so that their reports hold them
     */
    @Test
    void reportsStatementsOfImports() throws Exception {
        File packageDir = exportCsv(UiDatabase.create(dir, "source"));
        TransferReport report = new TransferReport();
        ReportRecorder recorder = new ReportRecorder(report);
        ExportListener listener = new ExportListener() {
            @Override
            public void onRowSkipped(String catalogName, String schemaName, String tableName, String key,
                                     Throwable e) {
                recorder.rowSkipped(catalogName, schemaName, tableName, key, e);
            }

            @Override
            public void onDdlExecuted(String statement, boolean successful) {
                recorder.ddlExecuted(statement, successful);
            }
        };
        service.importScripts(target("statements"), null, null, packageDir, false, 0, null, listener);
        assertTrue(report.getObjects().getOrDefault("Tables", 0) >= 2, report.getObjects().toString());

        // A file whose rows do not fit the table: the rows are skipped one by one
        ConnectionProfile target = target("skipped");
        try (Connection connection = DriverManager.getConnection(target.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE NARROW (ID INT PRIMARY KEY, NAME VARCHAR(1))");
        }
        File csv = new File(dir, "narrow.csv");
        java.nio.file.Files.writeString(csv.toPath(), "ID,NAME\n1,a\n2,too long\n3,c\n");
        service.importFiles(target, null, null, List.of(csv), "NARROW", DataFileImporter.Mode.APPEND, List.of(),
                null, true, 0, null, listener);
        assertEquals(1, report.getSkippedRowCount(), report.getSkippedRows().toString());
        assertEquals(2, count(target, "NARROW"));
    }
}
