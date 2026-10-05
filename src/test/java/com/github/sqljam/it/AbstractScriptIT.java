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
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.LobManifestWriter;
import com.github.sqljam.impexp.ScriptExportHandler;
import com.github.sqljam.impexp.ScriptExporter;
import com.github.sqljam.impexp.ScriptImporter;

/**
 * @Description: AbstractScriptIT exports fixture tables of a source database as scripts of every database type
 *               and imports the export directory into the target database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public abstract class AbstractScriptIT {

    protected final ItDatabase source;

    protected AbstractScriptIT(ItDatabase source) {
        this.source = source;
    }

    protected File getExportDirectory(String name) {
        File dir = new File(ItDatabase.DATA_DIR, "scripts/" + source + "-" + name);
        FileUtils.deleteQuietly(dir);
        return dir;
    }

    @ParameterizedTest(name = "script for {0}")
    @EnumSource(ItDatabase.class)
    void exportAndImport(ItDatabase target) throws Exception {
        assumeTrue(!(source == ItDatabase.ORACLE && target == ItDatabase.ORACLE));
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();

        File dir = getExportDirectory(target.name());
        // Both data file strategies are verified with small max file size to split files
        boolean single = (source.ordinal() + target.ordinal()) % 2 == 0;
        ScriptExporter scriptExporter = single ? new ScriptExporter(dir, DataFileStrategy.SINGLE_FILE, 8 * 1024)
                : new ScriptExporter(dir, DataFileStrategy.FILE_PER_TABLE, 4 * 1024);
        source.configureSource(scriptExporter.getConfiguration());
        scriptExporter.getConfiguration().setIdReused(true);
        scriptExporter.setTargetDbType(target.getDbType());
        scriptExporter.setTargetCatalogName(target.getTargetCatalog());
        scriptExporter.setTargetSchemaName(target.getTargetSchema());
        CollectingListener listener = new CollectingListener();
        scriptExporter.setExportListener(listener);
        try {
            scriptExporter.exportDdlAndData();
        } catch (Exception e) {
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors());
        assertTrue(listener.isCompleted(), "Export progress 100%");
        assertTrue(new File(dir, ScriptExportHandler.SCHEMA_FILE_NAME).exists(), "schema.sql");
        assertTrue(new File(dir, LobManifestWriter.MANIFEST_FILE_NAME).exists(), "lob-manifest.json");
        List<File> dataFiles = ScriptImporter.getDataFiles(dir);
        assertTrue(dataFiles.size() > 1, "Data files are split");
        assertTrue(dataFiles.stream().anyMatch(file -> file.getName().endsWith("_2.sql")), "Part file naming");
        ExportManifest manifest = ExportManifest.read(dir);
        assertEquals(ExportManifest.Status.COMPLETED, manifest.getStatus());
        assertEquals(target.getDbType(), manifest.getTarget().getDbType());
        assertEquals(5, manifest.getTables().size(), "Tables in manifest");
        assertEquals(ItDatabase.EMP_COUNT, manifest.getTables().stream()
                .filter(table -> table.getName().equalsIgnoreCase(source.getPrefix() + "emp")).findFirst()
                .orElseThrow().getRows());
        assertEquals(ExportManifest.FileType.SCHEMA, manifest.getFiles().get(0).getType());

        try (Connection connection = target.getTargetConnection()) {
            ScriptImporter importer = new ScriptImporter(connection, target.getDbType());
            listener = new CollectingListener();
            importer.setExportListener(listener);
            try {
                importer.importDirectory(dir);
            } catch (Exception e) {
                throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
            }
            assertEquals(List.of(), listener.getErrors());
            assertTrue(importer.getLobCount() > 0, "LOB values restored");
            assertTrue(listener.isCompleted(), "Import progress 100%");
            new ItVerifier(source, target, connection).verifyAll();
        }
    }

    /**
     * With default max file size (10MB) small exports are not split
     */
    @Test
    void exportWithDefaultMaxFileSize() throws Exception {
        assumeTrue(source.isAvailable());
        source.loadFixture();
        File dir = getExportDirectory("default");
        ScriptExporter scriptExporter = new ScriptExporter(dir, false);
        source.configureSource(scriptExporter.getConfiguration());
        CollectingListener listener = new CollectingListener();
        scriptExporter.setExportListener(listener);
        scriptExporter.export(ExportMode.DDL_DATA);
        assertEquals(List.of(), listener.getErrors());
        assertEquals(List.of(new File(dir, ScriptExportHandler.DATA_FILE_NAME)), ScriptImporter.getDataFiles(dir));
        // Foreign keys of SQLite are defined in create table statements
        assertEquals(source != ItDatabase.SQLITE, new File(dir, ScriptExportHandler.CONSTRAINT_FILE_NAME).exists(),
                "constraints.sql");
    }

    /**
     * DDL only export writes foreign keys into schema.sql
     */
    @Test
    void exportDdlOnly() throws Exception {
        assumeTrue(source.isAvailable());
        source.loadFixture();
        File dir = getExportDirectory("ddl");
        ScriptExporter scriptExporter = new ScriptExporter(dir, true);
        source.configureSource(scriptExporter.getConfiguration());
        scriptExporter.exportDdl();
        assertEquals(List.of(), ScriptImporter.getDataFiles(dir));
        assertTrue(new File(dir, ScriptExportHandler.SCHEMA_FILE_NAME).exists());
        ExportManifest manifest = ExportManifest.read(dir);
        assertEquals(ExportMode.DDL, manifest.getExportMode());
        // Tables are listed even without rows
        assertEquals(5, manifest.getTables().size());
        assertTrue(manifest.getTables().stream().allMatch(table -> table.getRows() == 0));
    }

    /**
     * Structure only package is imported as a pair: tables without rows
     */
    @Test
    void exportAndImportStructureOnly() throws Exception {
        ItDatabase target = ItDatabase.H2;
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();
        File dir = getExportDirectory("structure");
        ScriptExporter scriptExporter = new ScriptExporter(dir, false);
        source.configureSource(scriptExporter.getConfiguration());
        scriptExporter.setTargetDbType(target.getDbType());
        scriptExporter.setTargetCatalogName(target.getTargetCatalog());
        scriptExporter.setTargetSchemaName(target.getTargetSchema());
        scriptExporter.exportDdl();
        assertEquals(List.of(), ScriptImporter.getDataFiles(dir));

        try (Connection connection = target.getTargetConnection()) {
            ScriptImporter importer = new ScriptImporter(connection, target.getDbType());
            CollectingListener listener = new CollectingListener();
            importer.setExportListener(listener);
            importer.importDirectory(dir);
            assertEquals(List.of(), listener.getErrors());
            assertEquals(0, importer.getFailedCount());
            new ItVerifier(source, target, connection).verifyStructureOnly();
        }
    }
}
