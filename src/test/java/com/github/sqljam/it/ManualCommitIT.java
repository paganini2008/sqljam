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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.impexp.ScriptExporter;
import com.github.sqljam.jdbc.ManualTransaction;
import com.github.sqljam.jdbc.SharedConnectionFactory;

/**
 * @Description: ManualCommitIT imports the fixture tables of H2 into every database in one manual transaction:
 *               a roll back keeps nothing of the rows (databases with transactional DDL keep the tables as they
 *               were, others keep the created tables empty), a commit keeps everything and runs the deferred DDL
 *               (foreign keys, identity values) afterwards. ClickHouse has no transactions.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ManualCommitIT {

    private static final ItDatabase SOURCE = ItDatabase.H2;
    private static final String[] TABLES = {"dept", "emp", "emp_tag", "sales", "types"};

    /**
     * Imports the fixture tables in the transaction, the transaction is still open afterwards
     */
    private static ManualTransaction importInTransaction(ItDatabase target) throws Exception {
        ManualTransaction transaction = new ManualTransaction(target.getTargetConnection(),
                target.getDbType().isTransactionalDdl());
        ImportExporter importExporter = new ImportExporter();
        SOURCE.configureSource(importExporter.getExportConfiguration());
        importExporter.getExportConfiguration().setIdReused(true);
        importExporter.getExportConfiguration().setPageSize(100);
        importExporter.getExportConfiguration().setFailFast(true);
        target.configureTarget(importExporter.getImportConfiguration());
        importExporter.getImportConfiguration().setConnectionPoolEnabled(false);
        importExporter.setTargetConnectionFactory(new SharedConnectionFactory(transaction.getConnection()));
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        try {
            importExporter.exportDdlAndData();
        } catch (Exception e) {
            transaction.close();
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors(), "Errors of " + SOURCE + " -> " + target);
        assertFalse(transaction.isRolledBack());
        return transaction;
    }

    /**
     * Rows of the fixture tables in the target, -1 for a missing table
     */
    private static long[] counts(ItDatabase target) throws SQLException {
        long[] counts = new long[TABLES.length];
        try (Connection connection = target.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(SOURCE, target, connection);
            for (int i = 0; i < TABLES.length; i++) {
                try {
                    counts[i] = verifier.count(TABLES[i]);
                } catch (SQLException | RuntimeException e) {
                    counts[i] = -1;
                }
            }
        }
        return counts;
    }

    @ParameterizedTest(name = "manual commit into {0}")
    @EnumSource(ItDatabase.class)
    void rollBackThenCommit(ItDatabase target) throws Exception {
        assumeTrue(target.getDbType().isTransactionSupported());
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        SOURCE.loadFixture();
        long[] before = counts(target);

        // Rolled back: nothing of the rows is kept
        try (ManualTransaction transaction = importInTransaction(target)) {
            if (!target.getDbType().isTransactionalDdl()) {
                // Foreign keys and identity values wait for the commit, they would commit the rows
                assertFalse(transaction.getDeferredStatements().isEmpty(), "Deferred statements of " + target);
            }
            transaction.rollback();
            assertTrue(transaction.isFinished());
        }
        long[] afterRollback = counts(target);
        for (int i = 0; i < TABLES.length; i++) {
            if (target.getDbType().isTransactionalDdl()) {
                assertEquals(before[i], afterRollback[i], TABLES[i] + " is as before the import in " + target);
            } else {
                assertEquals(0, afterRollback[i], TABLES[i] + " is created and empty in " + target);
            }
        }

        // Committed: rows, keys, identity values and the deferred statements
        try (ManualTransaction transaction = importInTransaction(target)) {
            assertEquals(List.of(), transaction.commit(), "Deferred statements of " + target);
            assertThrows(IllegalStateException.class, transaction::rollback);
        }
        try (Connection connection = target.getTargetConnection()) {
            new ItVerifier(SOURCE, target, connection).verifyAll();
        }
    }

    /**
     * Exports the fixture tables of H2 as a package for the target: sql scripts with LOB files, or CSV files
     */
    private static File exportPackage(ItDatabase target, DataFormat format) throws Exception {
        File dir = new File(ItDatabase.DATA_DIR, "manual-commit/" + format + "-" + target);
        FileUtils.deleteQuietly(dir);
        Exporter.ExportConfiguration configuration;
        CollectingListener listener = new CollectingListener();
        if (format == DataFormat.CSV) {
            CsvExporter exporter = new CsvExporter(dir);
            exporter.setMaxFileSize(8 * 1024);
            configuration = exporter.getConfiguration();
            SOURCE.configureSource(configuration);
            configuration.setIdReused(true);
            exporter.setTargetDbType(target.getDbType());
            exporter.setTargetCatalogName(target.getTargetCatalog());
            exporter.setTargetSchemaName(target.getTargetSchema());
            exporter.setExportListener(listener);
            exporter.exportDdlAndData();
        } else {
            ScriptExporter exporter = new ScriptExporter(dir, DataFileStrategy.SINGLE_FILE, 8 * 1024);
            configuration = exporter.getConfiguration();
            SOURCE.configureSource(configuration);
            configuration.setIdReused(true);
            exporter.setTargetDbType(target.getDbType());
            exporter.setTargetCatalogName(target.getTargetCatalog());
            exporter.setTargetSchemaName(target.getTargetSchema());
            exporter.setExportListener(listener);
            exporter.exportDdlAndData();
        }
        assertEquals(List.of(), listener.getErrors());
        return dir;
    }

    private static ManualTransaction importPackageInTransaction(ItDatabase target, File dir) throws Exception {
        ManualTransaction transaction = new ManualTransaction(target.getTargetConnection(),
                target.getDbType().isTransactionalDdl());
        AbstractFileImporter importer = AbstractFileImporter.forDirectory(dir, transaction.getConnection(),
                target.getDbType());
        CollectingListener listener = new CollectingListener();
        importer.setExportListener(listener);
        try {
            importer.importDirectory(dir);
        } catch (Exception e) {
            transaction.close();
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors(), "Errors of the package for " + target);
        assertFalse(transaction.isRolledBack());
        return transaction;
    }

    /**
     * Packages of sql scripts (batches, LOB files) and of CSV files are imported in one transaction as well
     */
    @ParameterizedTest(name = "manual commit of packages into {0}")
    @EnumSource(ItDatabase.class)
    void packageRollBackThenCommit(ItDatabase target) throws Exception {
        assumeTrue(target.getDbType().isTransactionSupported());
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        SOURCE.loadFixture();
        for (DataFormat format : new DataFormat[]{DataFormat.SQL, DataFormat.CSV}) {
            File dir = exportPackage(target, format);
            long[] before = counts(target);
            try (ManualTransaction transaction = importPackageInTransaction(target, dir)) {
                transaction.rollback();
            }
            long[] afterRollback = counts(target);
            for (int i = 0; i < TABLES.length; i++) {
                if (target.getDbType().isTransactionalDdl()) {
                    assertEquals(before[i], afterRollback[i], format + " " + TABLES[i] + " as before in " + target);
                } else {
                    assertEquals(0, afterRollback[i], format + " " + TABLES[i] + " is empty in " + target);
                }
            }
            try (ManualTransaction transaction = importPackageInTransaction(target, dir)) {
                assertEquals(List.of(), transaction.commit(), format + " deferred statements of " + target);
            }
            try (Connection connection = target.getTargetConnection()) {
                ItVerifier verifier = new ItVerifier(SOURCE, target, connection);
                if (format == DataFormat.CSV) {
                    verifier.withInstantEquality();
                }
                verifier.verifyAll();
            }
        }
    }

    /**
     * An open transaction is rolled back when it is closed, e.g. when the user closes the window
     */
    @Test
    void closeRollsBack() throws Exception {
        ItDatabase target = ItDatabase.POSTGRESQL;
        assumeTrue(SOURCE.isAvailable() && target.isAvailable());
        SOURCE.loadFixture();
        long[] before = counts(target);
        ManualTransaction transaction = importInTransaction(target);
        transaction.close();
        assertTrue(transaction.isFinished());
        assertEquals(before[1], counts(target)[1]);
    }
}
