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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.ImpExpException;
import com.github.sqljam.impexp.ImportExportHandler;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.impexp.TableQuery;

/**
 * @Description: AbstractImportIT imports fixture tables of a source database into every database directly
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public abstract class AbstractImportIT {

    protected final ItDatabase source;

    protected AbstractImportIT(ItDatabase source) {
        this.source = source;
    }

    static boolean isTargetSelected(ItDatabase target) {
        String filter = System.getProperty("it.target");
        return filter == null || target.name().equalsIgnoreCase(filter);
    }

    @ParameterizedTest(name = "import into {0}")
    @EnumSource(ItDatabase.class)
    void importInto(ItDatabase target) throws Exception {
        // Oracle test user has only one schema
        // Test users of Oracle and MariaDB have one schema, the same database type is verified by copyIntoSameSchema
        assumeTrue(!(source == target && source.isSingleSchema()));
        assumeTrue(isTargetSelected(target));
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();

        ImportExporter importExporter = new ImportExporter();
        source.configureSource(importExporter.getExportConfiguration());
        importExporter.getExportConfiguration().setIdReused(true);
        importExporter.getExportConfiguration().setPageSize(100);
        target.configureTarget(importExporter.getImportConfiguration());
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        try {
            importExporter.exportDdlAndData();
        } catch (Exception e) {
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors(), "Errors of " + source + " -> " + target);
        assertEquals(Boolean.TRUE, listener.getSuccessful());
        assertTrue(listener.isCompleted(), "Progress 100%");

        try (Connection connection = target.getTargetConnection()) {
            new ItVerifier(source, target, connection).verifyAll();
        }
    }

    /**
     * Rows are optional: structure only import creates tables, keys, indexes, comments and sequences
     */
    @Test
    void importStructureOnly() throws Exception {
        ItDatabase target = ItDatabase.H2;
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();

        ImportExporter importExporter = new ImportExporter();
        source.configureSource(importExporter.getExportConfiguration());
        target.configureTarget(importExporter.getImportConfiguration());
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        importExporter.exportDdl();
        assertEquals(List.of(), listener.getErrors());
        assertEquals(Boolean.TRUE, listener.getSuccessful());

        try (Connection connection = target.getTargetConnection()) {
            new ItVerifier(source, target, connection).verifyStructureOnly();
        }
    }

    private static final String[] FIXTURE_TABLES = {"emp_tag", "emp", "dept", "sales", "types"};
    private static final String COPY_SUFFIX = "_cp";

    /**
     * Configures an import from the fixture tables into the same schema of the same database
     */
    private ImportExporter createCopy(String tableNamePattern) {
        ImportExporter importExporter = new ImportExporter();
        source.configureSource(importExporter.getExportConfiguration());
        importExporter.getExportConfiguration().setIncludedTableNamePattern("(?i)" + source.getPrefix()
                + "(dept|emp|emp_tag|sales|types)");
        importExporter.getExportConfiguration().setIdReused(true);
        ImportExportHandler.ImportConfiguration configuration = importExporter.getImportConfiguration();
        configuration.setDbType(source.getDbType());
        configuration.setUrl(source.getSourceUrl());
        configuration.setUsername(source.getUsername());
        configuration.setPassword(source.getPassword());
        configuration.setTargetCatalogName(source.getSourceCatalog());
        configuration.setTargetSchemaName(source.getSourceSchema());
        configuration.setTableNamePattern(tableNamePattern);
        return importExporter;
    }

    /**
     * Copies of the fixture tables are dropped, referencing tables first
     */
    private void dropCopies() throws Exception {
        Dialect dialect = source.getDbType().createDialect();
        dialect.setTargetSchemaName(source.getSourceSchema());
        try (Connection connection = source.getSourceConnection();
             Statement statement = connection.createStatement()) {
            for (String table : FIXTURE_TABLES) {
                // Names of copies follow the case of the source tables, e.g. SJH_EMP_CP of H2
                String copyName = source.getPrefix() + table + COPY_SUFFIX;
                if (dialect.getStoredIdentifierCase() == IdentifierCase.UPPER) {
                    copyName = copyName.toUpperCase(Locale.ENGLISH);
                }
                String sql = dialect.getDropTableStatement(source.getSourceCatalog(), source.getSourceSchema(),
                        copyName);
                if (source == ItDatabase.ORACLE) {
                    sql = String.format("BEGIN EXECUTE IMMEDIATE 'DROP TABLE %s CASCADE CONSTRAINTS'; EXCEPTION "
                            + "WHEN OTHERS THEN NULL; END;", (source.getPrefix() + table + COPY_SUFFIX)
                            .toUpperCase(Locale.ENGLISH));
                }
                statement.execute(sql);
            }
        }
    }

    /**
     * Tables are copied in the same schema of the same database by a table name pattern, like copying tables.
     * Keys, indexes, foreign keys, comments, identity and partitions of the copies are verified, the source tables
     * are not changed.
     */
    @Test
    void copyIntoSameSchema() throws Exception {
        assumeTrue(source.isAvailable());
        source.loadFixture();
        dropCopies();
        try {
            ImportExporter importExporter = createCopy("{table}" + COPY_SUFFIX);
            CollectingListener listener = new CollectingListener();
            importExporter.setExportListener(listener);
            try {
                importExporter.exportDdlAndData();
            } catch (Exception e) {
                throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
            }
            assertEquals(List.of(), listener.getErrors(), "Errors of copying " + source);
            assertEquals(Boolean.TRUE, listener.getSuccessful());
            try (Connection connection = source.getSourceConnection()) {
                new ItVerifier(source, source, connection, source.getSourceSchema()).withTableSuffix(COPY_SUFFIX)
                        .verifyAll();
                // The source tables are not changed
                new ItVerifier(source, source, connection, source.getSourceSchema()).verifyCounts();
            }
        } finally {
            dropCopies();
        }
    }

    /**
     * Importing into the tables which are read is rejected, the tables would be dropped before reading
     */
    @Test
    void rejectsSameTables() throws Exception {
        assumeTrue(source.isAvailable());
        source.loadFixture();
        for (String pattern : new String[]{null, "", Dialect.TABLE_PLACEHOLDER}) {
            ImpExpException e = assertThrows(ImpExpException.class, () -> createCopy(pattern).exportDdlAndData());
            assertTrue(e.getMessage().startsWith("Source and target tables are the same"), e.getMessage());
        }
        try (Connection connection = source.getSourceConnection()) {
            new ItVerifier(source, source, connection, source.getSourceSchema()).verifyCounts();
        }
    }

    /**
     * Rows filtered in the data viewer are imported into a database without the table: the table is created with
     * the selected columns, keys and indexes whose columns are selected, and the rows of the condition
     */
    @Test
    void importQueryIntoNewTable() throws Exception {
        assumeTrue(source.isAvailable());
        source.loadFixture();
        File file = new File("target/it-data/query-" + source.name().toLowerCase(Locale.ENGLISH));
        FileUtils.deleteQuietly(new File(file.getPath() + ".mv.db"));
        String url = DbType.H2.getUrl(null, 0, file.getAbsolutePath());

        ImportExporter importExporter = new ImportExporter();
        source.configureSource(importExporter.getExportConfiguration());
        String emp = findSourceTable("emp");
        importExporter.getExportConfiguration().setIncludedTableNames(new String[]{emp});
        importExporter.getExportConfiguration().setIdReused(true);
        List<String> columns = selectColumns(emp, "id", "name", "email", "salary");
        importExporter.getExportConfiguration().getTableQueries().put(emp, new TableQuery(columns,
                source.getDbType().createDialect().quoteIdentifier(columns.get(3)) + " > 1000", null,
                source.getDbType().createDialect().quoteIdentifier(columns.get(1)) + " DESC"));
        ImportExportHandler.ImportConfiguration target = importExporter.getImportConfiguration();
        target.setDbType(DbType.H2);
        target.setUrl(url);
        target.setUsername("sa");
        target.setPassword("");
        target.setTargetSchemaName("PUBLIC");
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        importExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors());

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            String table = emp.toUpperCase(Locale.ENGLISH);
            List<String> created = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getColumns(null, "PUBLIC", table, null)) {
                while (rs.next()) {
                    created.add(rs.getString("COLUMN_NAME"));
                }
            }
            assertEquals(List.of("ID", "NAME", "EMAIL", "SALARY"), created, "Columns of the created table");
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*), MIN(ID), MAX(ID) FROM " + table)) {
                rs.next();
                // salary = id * 10.25 > 1000 (id >= 98) and no salary for multiples of 7 (98, 105 ...)
                assertEquals(131, rs.getInt(1));
                assertEquals(99, rs.getInt(2));
                assertEquals(250, rs.getInt(3));
            }
            try (ResultSet rs = connection.getMetaData().getPrimaryKeys(null, "PUBLIC", table)) {
                assertTrue(rs.next(), "Primary key of the selected id");
            }
            if (source != ItDatabase.CLICKHOUSE) {
                List<String> indexColumns = new ArrayList<>();
                try (ResultSet rs = connection.getMetaData().getIndexInfo(null, "PUBLIC", table, true, false)) {
                    while (rs.next()) {
                        indexColumns.add(rs.getString("COLUMN_NAME"));
                    }
                }
                assertTrue(indexColumns.contains("EMAIL"), "Unique index of the selected email: " + indexColumns);
            }
            try (ResultSet rs = connection.getMetaData().getImportedKeys(null, "PUBLIC", table)) {
                assertFalse(rs.next(), "No foreign key, dept_id is not selected");
            }
        }
    }

    private String findSourceTable(String name) throws Exception {
        try (Connection connection = source.getSourceConnection()) {
            MetaDataOperations operations = source.getDbType().createMetaDataOperations();
            String catalog = source.getDbType().isCatalogSupported() ? source.getSourceCatalog() : null;
            return operations.getTableInfos(connection.getMetaData(), catalog, source.getSourceSchema()).stream()
                    .map(info -> (String) info.get("TABLE_NAME"))
                    .filter(table -> table.equalsIgnoreCase(source.getPrefix() + name)).findFirst().orElseThrow();
        }
    }

    /**
     * Column names in the case of the source table
     */
    private static List<String> selectColumns(String table, String... names) {
        boolean upper = table.equals(table.toUpperCase(Locale.ENGLISH));
        return Arrays.stream(names).map(name -> upper ? name.toUpperCase(Locale.ENGLISH) : name)
                .collect(Collectors.toList());
    }
}
