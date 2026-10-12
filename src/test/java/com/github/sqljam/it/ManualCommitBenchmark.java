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

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.jdbc.ManualTransaction;
import com.github.sqljam.jdbc.SharedConnectionFactory;

/**
 * @Description: ManualCommitBenchmark measures imports of many rows into each database: commits page by page, one
 *               manual transaction with its import and commit times, and the roll back of one transaction. It is
 *               not a test of the build (the name does not end with Test or IT), run it by
 *               mvn test -Dtest=ManualCommitBenchmark -Dbench.rows=1000000 -Dbench.targets=POSTGRESQL,MYSQL
 *               The results are written to target/bench/manual-commit.md. The table bench_orders is dropped from
 *               each database right after its measurement, the source database is deleted at the end.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ManualCommitBenchmark {

    private static final String TABLE = "BENCH_ORDERS";
    private static final File DIR = new File("target/bench");

    /**
     * A new source database with the rows, the source of an earlier run is deleted first
     */
    private static String createSource(long rows) throws Exception {
        File file = new File(DIR, "source");
        FileUtils.deleteQuietly(new File(file.getPath() + ".mv.db"));
        String url = "jdbc:h2:file:" + file.getAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + TABLE + " (ID BIGINT PRIMARY KEY, ORDER_NO VARCHAR(20) NOT NULL,"
                    + " CUSTOMER_ID INT NOT NULL, STATUS VARCHAR(12) NOT NULL, TOTAL DECIMAL(12,2) NOT NULL,"
                    + " NOTE VARCHAR(100), CREATED_AT TIMESTAMP NOT NULL)");
            statement.execute("INSERT INTO " + TABLE + " SELECT X, 'SO-' || LPAD(X, 12, '0'), MOD(X, 5000),"
                    + " CASEWHEN(MOD(X, 3) = 0, 'PAID', 'NEW'), MOD(X, 100000) / 100.0, REPEAT('n', MOD(X, 60)),"
                    + " TIMESTAMP '2024-01-01 00:00:00' + X * INTERVAL '1' SECOND FROM SYSTEM_RANGE(1, " + rows + ")");
        }
        return url;
    }

    private static ImportExporter newImporter(String sourceUrl, ItDatabase target) {
        ImportExporter importExporter = new ImportExporter();
        importExporter.getExportConfiguration().setDbType(DbType.H2);
        importExporter.getExportConfiguration().setUrl(sourceUrl);
        importExporter.getExportConfiguration().setUsername("sa");
        importExporter.getExportConfiguration().setPassword("");
        importExporter.getExportConfiguration().setIncludedSchemaNames(new String[]{"PUBLIC"});
        importExporter.getExportConfiguration().setIncludedTableNames(new String[]{TABLE});
        importExporter.getExportConfiguration().setIdReused(true);
        importExporter.getExportConfiguration().setConnectionPoolEnabled(false);
        target.configureTarget(importExporter.getImportConfiguration());
        return importExporter;
    }

    /**
     * Seconds of the import committed page by page, the default before manual commit
     */
    private static double pageByPage(String sourceUrl, ItDatabase target) throws Exception {
        ImportExporter importExporter = newImporter(sourceUrl, target);
        importExporter.getImportConfiguration().setConnectionPoolEnabled(true);
        long start = System.nanoTime();
        importExporter.exportDdlAndData();
        return (System.nanoTime() - start) / 1e9;
    }

    /**
     * Seconds of the import in one transaction and of its end: commit or roll back
     */
    private static double[] oneTransaction(String sourceUrl, ItDatabase target, boolean commit) throws Exception {
        try (ManualTransaction transaction = new ManualTransaction(target.getTargetConnection(),
                target.getDbType().isTransactionalDdl())) {
            ImportExporter importExporter = newImporter(sourceUrl, target);
            importExporter.getExportConfiguration().setFailFast(true);
            importExporter.setTargetConnectionFactory(new SharedConnectionFactory(transaction.getConnection()));
            long start = System.nanoTime();
            importExporter.exportDdlAndData();
            double imported = (System.nanoTime() - start) / 1e9;
            start = System.nanoTime();
            if (commit) {
                List<String> errors = transaction.commit();
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("Deferred statements failed: " + errors);
                }
            } else {
                transaction.rollback();
            }
            return new double[]{imported, (System.nanoTime() - start) / 1e9};
        }
    }

    /**
     * The imported table in the target schema (or database) with the case of the target
     */
    private static String targetTable(ItDatabase target) {
        boolean upper = target.getDbType() == DbType.ORACLE || target.getDbType() == DbType.H2;
        String table = upper ? TABLE : TABLE.toLowerCase(Locale.ENGLISH);
        String owner = target.getDbType() == DbType.MYSQL || target.getDbType() == DbType.MARIADB
                ? target.getTargetCatalog() : target.getTargetSchema();
        return owner != null ? owner + "." + table : table;
    }

    private static long targetRows(ItDatabase target) {
        try (Connection connection = target.getTargetConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + targetTable(target))) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void drop(ItDatabase target) {
        try (Connection connection = target.getTargetConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + targetTable(target));
        } catch (Exception ignored) {
            // The table is not created
        }
    }

    @Test
    void run() throws Exception {
        long rows = Long.getLong("bench.rows", 1_000_000);
        List<String> names = Arrays.asList(System.getProperty("bench.targets",
                "POSTGRESQL,MYSQL,MARIADB,ORACLE,SQLSERVER,H2").split(","));
        DIR.mkdirs();
        String sourceUrl = createSource(rows);
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ENGLISH, "Rows: %,d (7 columns, about 100 bytes), page size 5000", rows));
        lines.add("");
        lines.add("| Target | Page by page | One transaction: import | Commit | Roll back after import | Roll back |");
        lines.add("|---|---|---|---|---|---|");
        for (String name : names) {
            ItDatabase target = ItDatabase.valueOf(name.trim());
            if (!target.isAvailable()) {
                lines.add("| " + target + " | unavailable | | | | |");
                continue;
            }
            String row;
            try {
                drop(target);
                double pages = pageByPage(sourceUrl, target);
                drop(target);
                double[] committed = oneTransaction(sourceUrl, target, true);
                long committedRows = targetRows(target);
                drop(target);
                double[] rolledBack = oneTransaction(sourceUrl, target, false);
                long remaining = targetRows(target);
                row = String.format(Locale.ENGLISH, "| %s | %.1f s | %.1f s | %.2f s | %.1f s | %.2f s |", target,
                        pages, committed[0], committed[1], rolledBack[0], rolledBack[1]);
                if (committedRows != rows || remaining > 0) {
                    row += " rows after commit " + committedRows + ", after roll back " + remaining;
                }
            } catch (Exception e) {
                row = "| " + target + " | failed: " + e.getMessage().replace('\n', ' ').replace('|', '/') + " | | | | |";
            } finally {
                drop(target);
            }
            System.out.println(row);
            lines.add(row);
        }
        Files.write(new File(DIR, "manual-commit.md").toPath(), lines);
        lines.forEach(System.out::println);
        // The source database is test data as well
        FileUtils.deleteQuietly(new File(DIR, "source.mv.db"));
        FileUtils.deleteQuietly(new File(DIR, "source.trace.db"));
    }
}
