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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.ParquetExporter;
import com.github.sqljam.impexp.ParquetImporter;
import com.github.sqljam.impexp.ScriptExportHandler;

/**
 * @Description: AbstractParquetIT exports fixture tables of a source database as Parquet packages and imports them
 *               into every database, and loads Parquet files into new and existing tables
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public abstract class AbstractParquetIT {

    protected final ItDatabase source;

    protected AbstractParquetIT(ItDatabase source) {
        this.source = source;
    }

    protected File getExportDirectory(String name) {
        File dir = new File(ItDatabase.DATA_DIR, "parquet/" + source + "-" + name);
        FileUtils.deleteQuietly(dir);
        return dir;
    }

    private ParquetExporter newExporter(File dir, ItDatabase target) {
        ParquetExporter parquetExporter = new ParquetExporter(dir);
        source.configureSource(parquetExporter.getConfiguration());
        parquetExporter.getConfiguration().setIdReused(true);
        parquetExporter.setTargetDbType(target.getDbType());
        parquetExporter.setTargetCatalogName(target.getTargetCatalog());
        parquetExporter.setTargetSchemaName(target.getTargetSchema());
        // Compressions are rotated over the matrix
        parquetExporter.setCompression(ParquetExporter.COMPRESSIONS[(source.ordinal() + target.ordinal())
                % ParquetExporter.COMPRESSIONS.length]);
        return parquetExporter;
    }

    @ParameterizedTest(name = "parquet for {0}")
    @EnumSource(ItDatabase.class)
    void exportAndImport(ItDatabase target) throws Exception {
        // Oracle test user has only one schema
        // Test users of Oracle and MariaDB have one schema, the same database type is verified by copyIntoSameSchema
        assumeTrue(!(source == target && source.isSingleSchema()));
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();

        File dir = getExportDirectory(target.name());
        ParquetExporter parquetExporter = newExporter(dir, target);
        CollectingListener listener = new CollectingListener();
        parquetExporter.setExportListener(listener);
        try {
            parquetExporter.exportDdlAndData();
        } catch (Exception e) {
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors());
        assertEquals(Boolean.TRUE, listener.getSuccessful());

        ExportManifest manifest = ExportManifest.read(dir);
        assertEquals(DataFormat.PARQUET, manifest.getDataFormat());
        assertEquals(ExportManifest.Status.COMPLETED, manifest.getStatus());
        assertEquals(target.getDbType(), manifest.getTarget().getDbType());
        assertEquals(5, manifest.getTables().size(), "Tables in manifest: " + manifest.getTables().size());
        ExportManifest.TableEntry emp = manifest.getTables().stream()
                .filter(table -> table.getName().equalsIgnoreCase(source.getPrefix() + "emp")).findFirst()
                .orElseThrow();
        assertEquals(ItDatabase.EMP_COUNT, emp.getRows());
        // ClickHouse has no identity columns
        assertEquals(source == ItDatabase.CLICKHOUSE ? 0 : 1, emp.getIdentityColumns().size(), "Identity column of emp");
        assertTrue(manifest.getFiles().stream().anyMatch(f -> f.getType() == ExportManifest.FileType.PARQUET));
        assertEquals(ExportManifest.FileType.SCHEMA, manifest.getFiles().get(0).getType());
        assertTrue(new File(dir, emp.getDataFiles().iterator().next()).isFile(), "Parquet file of emp");

        try (Connection connection = target.getTargetConnection()) {
            ParquetImporter importer = new ParquetImporter(connection, target.getDbType());
            listener = new CollectingListener();
            importer.setExportListener(listener);
            try {
                importer.importDirectory(dir);
            } catch (Exception e) {
                throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
            }
            assertEquals(List.of(), listener.getErrors());
            assertTrue(listener.isCompleted(), "Import progress 100%");
            new ItVerifier(source, target, connection).withInstantEquality().verifyAll();
        }
    }

    /**
     * Parquet files are loaded into a new table (created from the Parquet schema) and appended to it
     */
    @Test
    void loadParquetFiles() throws Exception {
        ItDatabase target = ItDatabase.H2;
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();
        File dir = getExportDirectory("files");
        ParquetExporter parquetExporter = newExporter(dir, target);
        parquetExporter.export(ExportMode.DATA);
        ExportManifest manifest = ExportManifest.read(dir);
        assertEquals(ExportMode.DATA, manifest.getExportMode());
        ExportManifest.TableEntry emp = manifest.getTables().stream()
                .filter(table -> table.getName().equalsIgnoreCase(source.getPrefix() + "emp")).findFirst()
                .orElseThrow();
        File file = new File(dir, emp.getDataFiles().iterator().next());
        assertTrue(file.getParentFile().getName().equals(ScriptExportHandler.DATA_DIR_NAME));

        String tableName = source.getPrefix() + "pq_emp";
        try (Connection connection = target.getTargetConnection()) {
            ParquetImporter importer = new ParquetImporter(connection, target.getDbType());
            importer.setTargetSchemaName(target.getTargetSchema());
            CollectingListener listener = new CollectingListener();
            importer.setExportListener(listener);
            assertEquals(ItDatabase.EMP_COUNT, importer.importFiles(List.of(file), tableName,
                    ParquetImporter.Mode.CREATE));
            assertEquals(List.of(), listener.getErrors());
            assertTrue(listener.isCompleted(), "Import progress 100%");
            // Rows are appended to the existing table
            listener = new CollectingListener();
            importer.setExportListener(listener);
            assertEquals(ItDatabase.EMP_COUNT, importer.importFiles(List.of(file), tableName,
                    ParquetImporter.Mode.APPEND));
            assertEquals(List.of(), listener.getErrors());
            assertTrue(listener.isCompleted(), "Append progress 100%");
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM \"" + target.getTargetSchema()
                         + "\".\"" + tableName.toUpperCase() + "\"")) {
                rs.next();
                assertEquals(ItDatabase.EMP_COUNT * 2, rs.getLong(1));
            }
        }
    }
}
