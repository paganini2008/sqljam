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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @Description: ManifestTest verifies manifest.json and lob-manifest.json of export packages
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void lobManifest() throws Exception {
        File root = tempDir.toFile();
        try (LobManifestWriter writer = new LobManifestWriter(root, DbType.H2)) {
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("ID", 1001L);
            Map<String, Object> lobs = new LinkedHashMap<>();
            lobs.put("CONTENT", "中文");
            lobs.put("ATTACHMENT", new byte[]{1, 2});
            writer.writeRow("PUBLIC", "DOCUMENT", key, lobs, 1);
            writer.writeRow("PUBLIC", "DOCUMENT", key, new LinkedHashMap<>(), 2);
            Map<String, Object> otherKey = new LinkedHashMap<>();
            otherKey.put("CODE", "a/b");
            otherKey.put("V", new java.math.BigDecimal("1.5"));
            otherKey.put("F", 2.5f);
            otherKey.put("B", true);
            otherKey.put("N", null);
            Map<String, Object> otherLobs = new LinkedHashMap<>();
            otherLobs.put("BODY", "x");
            writer.writeRow(null, "OTHER TABLE", otherKey, otherLobs, 12);
            assertEquals(3, writer.getFileCount());
        }
        JsonNode manifest = new ObjectMapper().readTree(new File(root, LobManifestWriter.MANIFEST_FILE_NAME));
        assertEquals("H2", manifest.path("dbType").asText());
        JsonNode table = manifest.path("tables").get(0);
        assertEquals("DOCUMENT", table.path("table").asText());
        assertEquals(1001, table.path("rows").get(0).path("key").path("ID").asInt());
        assertEquals("lob/DOCUMENT/000001_CONTENT.clob",
                table.path("rows").get(0).path("columns").path("CONTENT").path("file").asText());
        assertEquals("BLOB", table.path("rows").get(0).path("columns").path("ATTACHMENT").path("type").asText());
        assertEquals("lob/OTHER_TABLE/000012_BODY.clob", manifest.path("tables").get(1).path("rows").get(0)
                .path("columns").path("BODY").path("file").asText());
        assertEquals("中文", Files.readString(root.toPath().resolve("lob/DOCUMENT/000001_CONTENT.clob"),
                StandardCharsets.UTF_8));
        assertEquals(3, manifest.path("fileCount").asInt());
    }

    @Test
    void exportManifest() throws Exception {
        File root = tempDir.toFile();
        Files.writeString(root.toPath().resolve("schema.sql"), "CREATE TABLE t (id INT);\n");
        ExportManifest manifest = new ExportManifest();
        manifest.setStatus(ExportManifest.Status.COMPLETED);
        manifest.setExportMode(ExportMode.DDL);
        manifest.getTarget().setDbType(DbType.H2);
        manifest.getFiles().add(new ExportManifest.FileEntry("schema.sql", ExportManifest.FileType.SCHEMA));
        manifest.getFiles().add(new ExportManifest.FileEntry("missing.sql", ExportManifest.FileType.DATA));
        manifest.write(root);
        ExportManifest read = ExportManifest.read(root);
        assertEquals(ExportManifest.FORMAT, read.getFormat());
        assertEquals(64, read.getFiles().get(0).getSha256().length());
        assertEquals(25, read.getFiles().get(0).getSize());
        assertNull(ExportManifest.read(tempDir.resolve("none").toFile()));

        // Import validates the manifest
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:manifest")) {
            ScriptImporter importer = new ScriptImporter(connection, DbType.H2);
            assertThrows(ImpExpException.class, () -> importer.importDirectory(root));
            read.getFiles().remove(1);
            read.write(root);
            importer.importDirectory(root);
            assertEquals(1, importer.getExecutedCount());

            Files.writeString(root.toPath().resolve("schema.sql"), "CREATE TABLE t2 (id INT);\n");
            assertTrue(assertThrows(ImpExpException.class, () -> importer.importDirectory(root)).getMessage()
                    .contains("checksum"));
            assertThrows(ImpExpException.class, () -> new ScriptImporter(connection, DbType.MYSQL)
                    .importDirectory(root));
            read.setStatus(ExportManifest.Status.FAILED);
            read.write(root);
            assertThrows(ImpExpException.class, () -> importer.importDirectory(root));
            read.setFormat("other");
            read.write(root);
            assertThrows(ImpExpException.class, () -> importer.importDirectory(root));
            assertThrows(ImpExpException.class, () -> importer.importDirectory(tempDir.resolve("empty").toFile()));
        }
    }

    @Test
    void legacyScripts() throws Exception {
        File root = tempDir.resolve("legacy").toFile();
        File unit = new File(root, "db1");
        Files.createDirectories(unit.toPath().resolve("data"));
        Files.writeString(unit.toPath().resolve("schema.sql"), "CREATE TABLE t (id INT PRIMARY KEY);\n");
        Files.writeString(unit.toPath().resolve("data.sql"), "INSERT INTO t VALUES (1);\n");
        Files.writeString(unit.toPath().resolve("data_2.sql"), "INSERT INTO t VALUES (2);\n");
        Files.writeString(unit.toPath().resolve("data_10.sql"), "INSERT INTO t VALUES (10);\n");
        Files.writeString(unit.toPath().resolve("data/t.sql"), "INSERT INTO t VALUES (3);\n");
        Files.writeString(unit.toPath().resolve("constraints.sql"), "-- nothing\n");
        assertEquals(java.util.List.of("data.sql", "data_2.sql", "data_10.sql", "t.sql"),
                ScriptImporter.getDataFiles(unit).stream().map(File::getName).collect(
                        java.util.stream.Collectors.toList()));
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:legacy")) {
            ScriptImporter importer = new ScriptImporter(connection, null);
            importer.setStopOnError(false);
            importer.importDirectory(root);
            assertEquals(5, importer.getExecutedCount());
            assertEquals(0, importer.getFailedCount());
        }
    }

    @Test
    void identityValues() {
        IdentityValueTracker tracker = new IdentityValueTracker();
        TableMetaData table = new TableMetaData("t", new java.util.HashMap<>(), null);
        tracker.track(table, java.util.List.of(), java.util.List.of());
        assertNull(tracker.get(table));
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", 5);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", 3L);
        Map<String, Object> row3 = new LinkedHashMap<>();
        row3.put("id", null);
        tracker.track(table, java.util.List.of("id"), java.util.List.of(row1, row2, row3));
        assertEquals(5L, tracker.get(table).get("id"));
        assertEquals(5L, tracker.remove(table).get("id"));
        assertNull(tracker.get(table));
    }

    @Test
    void portableFileNames() {
        assertEquals("orders", LobManifestWriter.toFileName("orders"));
        assertEquals("a_b_c_d", LobManifestWriter.toFileName("a/b:c d"));
        // Windows reserved device names and trailing dots
        assertEquals("aux_", LobManifestWriter.toFileName("aux"));
        assertEquals("CON_", LobManifestWriter.toFileName("CON"));
        assertEquals("com1_", LobManifestWriter.toFileName("com1"));
        assertEquals("console", LobManifestWriter.toFileName("console"));
        assertEquals("orders_", LobManifestWriter.toFileName("orders."));
    }
}
