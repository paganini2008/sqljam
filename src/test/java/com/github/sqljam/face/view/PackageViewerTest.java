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
package com.github.sqljam.face.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.ScriptExportHandler;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: PackageViewerTest verifies the package viewer: files with sizes and modified times, the summary of
 *               the manifest, previews of json, sql, LOB and Parquet files, large files and temporary workspaces
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class PackageViewerTest {

    @TempDir
    File dir;

    private AppContext context;
    private Stage stage;
    private ConnectionProfile source;
    private PackageViewer viewer;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        source = UiDatabase.create(new File(dir, "source"), "Source H2");
        context.getProfileRegistry().saveProfile(source, true);
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();
    }

    @AfterEach
    void close() {
        if (viewer != null) {
            FxTestSupport.run(() -> viewer.getStage().close());
        }
        context.getSessionManager().closeAll();
    }

    private File exportPackage(String name, DataFormat dataFormat) throws Exception {
        File packageDir = new File(dir, name);
        TransferRequest request = new TransferRequest();
        request.setSource(source);
        request.setSourceCatalog(UiDatabase.CATALOG);
        request.setSourceSchema(UiDatabase.SCHEMA);
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(packageDir);
        request.setScriptDbType(DbType.H2);
        request.setDataFormat(dataFormat);
        context.getTransferService().transfer(request, ExportListener.NONE);
        return packageDir;
    }

    private void open(File directory) {
        viewer = FxTestSupport.call(() -> {
            PackageViewer packageViewer = new PackageViewer(stage, context, directory);
            packageViewer.show();
            return packageViewer;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T node(String id) {
        return (T) viewer.getStage().getScene().getRoot().lookup("#" + id);
    }

    private TreeTableView<File> tree() {
        return node("packageFileTree");
    }

    private String fileLabel() {
        return FxTestSupport.call(() -> ((Label) node("packageFileLabel")).getText());
    }

    private String textPreview() {
        return FxTestSupport.call(() -> ((TextArea) node("packageTextPreview")).getText());
    }

    private void select(File file) {
        assertTrue(FxTestSupport.call(() -> viewer.selectFile(file)), file.toString());
    }

    private static List<String> names(TreeItem<File> item) {
        return item.getChildren().stream().map(child -> child.getValue().getName()).collect(Collectors.toList());
    }

    @Test
    void showsFilesWithSizeAndModifiedTime() throws Exception {
        File packageDir = exportPackage("sql", DataFormat.SQL);
        open(packageDir);
        List<String> columns = FxTestSupport.call(() -> tree().getColumns().stream()
                .map(column -> column.getText()).collect(Collectors.toList()));
        assertEquals(List.of("Name", "Size", "Modified"), columns);
        File manifestFile = new File(packageDir, ExportManifest.FILE_NAME);
        // The cells of size and modified time
        String size = FxTestSupport.call(() -> (String) tree().getColumns().get(1).getCellObservableValue(
                tree().getSelectionModel().getSelectedItem()).getValue());
        assertEquals(FileUtils.byteCountToDisplaySize(manifestFile.length()), size);
        String modified = FxTestSupport.call(() -> (String) tree().getColumns().get(2).getCellObservableValue(
                tree().getSelectionModel().getSelectedItem()).getValue());
        assertTrue(modified.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), modified);
        assertEquals(PackageViewer.formatModified(manifestFile), modified);
        // A directory is sized by its files
        assertEquals(FileUtils.byteCountToDisplaySize(FileUtils.sizeOfDirectory(packageDir)),
                PackageViewer.formatSize(packageDir));
        // manifest.json is selected and formatted when the viewer is shown
        assertTrue(fileLabel().startsWith("manifest.json  ("), fileLabel());
        assertTrue(fileLabel().contains(modified), fileLabel());
        assertTrue(textPreview().contains("\"dataFormat\" : \"SQL\""), textPreview());
        String summary = FxTestSupport.call(() -> ((Label) node("packageSummary")).getText());
        assertTrue(summary.startsWith("Format: SQL   Status: COMPLETED"), summary);
        assertTrue(summary.contains("Tables: 3"), summary);
        // sql scripts are previewed as text
        select(new File(packageDir, ScriptExportHandler.SCHEMA_FILE_NAME));
        assertTrue(textPreview().contains("CREATE TABLE"), textPreview());
    }

    @Test
    void listsDirectoriesFirstAndSkipsWorkspaces() throws Exception {
        File packageDir = new File(dir, "layout");
        FileUtils.forceMkdir(new File(packageDir, "data"));
        FileUtils.forceMkdir(new File(packageDir, ".sqljam-workspace-1"));
        Files.writeString(new File(packageDir, "b.sql").toPath(), "SELECT 1;");
        Files.writeString(new File(packageDir, "a.sql").toPath(), "SELECT 2;");
        Files.writeString(new File(packageDir, "data/t.sql").toPath(), "SELECT 3;");
        TreeItem<File> root = PackageViewer.createItem(packageDir);
        assertEquals(List.of("data", "a.sql", "b.sql"), names(root));
        assertEquals(List.of("t.sql"), names(root.getChildren().get(0)));
        open(packageDir);
        assertEquals("No manifest.json, files of the directory are listed",
                FxTestSupport.call(() -> ((Label) node("packageSummary")).getText()));
        select(new File(packageDir, "data/t.sql"));
        assertEquals("SELECT 3;", textPreview());
    }

    @Test
    void previewsParquetFiles() throws Exception {
        File packageDir = exportPackage("parquet", DataFormat.PARQUET);
        open(packageDir);
        assertTrue(textPreview().contains("\"dataFormat\" : \"PARQUET\""), textPreview());
        File parquetFile = new File(packageDir, "data").listFiles((parent, name) -> name.contains("T_PAGED"))[0];
        select(parquetFile);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> ((TableView<?>) node("packageTablePreview"))
                .isVisible()));
        @SuppressWarnings("unchecked")
        TableView<List<String>> table = node("packageTablePreview");
        assertEquals(PackageViewer.PARQUET_PREVIEW_ROWS, (int) FxTestSupport.call(() -> table.getItems().size()));
        List<String> headers = FxTestSupport.call(() -> table.getColumns().stream().map(column -> column.getText())
                .collect(Collectors.toList()));
        assertEquals(5, headers.size());
        assertTrue(headers.get(0).startsWith("ID\n"), headers.toString());
        assertTrue(fileLabel().contains(UiDatabase.PAGED_ROWS + " rows, the first 100 are shown"), fileLabel());
        assertEquals("1", FxTestSupport.call(() -> table.getItems().get(0).get(0)));
        // A text file is shown again after a Parquet file
        select(new File(packageDir, ExportManifest.FILE_NAME));
        assertFalse(FxTestSupport.call(table::isVisible));
    }

    @Test
    void reportsUnreadableParquetFile() throws Exception {
        File packageDir = new File(dir, "broken");
        FileUtils.forceMkdir(packageDir);
        File parquetFile = new File(packageDir, "broken.parquet");
        Files.writeString(parquetFile.toPath(), "not parquet");
        open(packageDir);
        select(parquetFile);
        FxTestSupport.waitUntil(() -> !"Loading...".equals(textPreview()));
        assertFalse(textPreview().isEmpty());
    }

    @Test
    void previewsLargeAndBinaryFiles() throws Exception {
        File large = new File(dir, "large.sql");
        Files.writeString(large.toPath(), "x".repeat(PackageViewer.TEXT_PREVIEW_BYTES + 10));
        String text = PackageViewer.readPreview(large);
        assertTrue(text.startsWith("x".repeat(100)));
        assertTrue(text.endsWith("... the first 256 KB of 256 KB are shown"), text.substring(text.length() - 80));
        File blob = new File(dir, "1.blob");
        byte[] bytes = new byte[PackageViewer.HEX_PREVIEW_BYTES + 1];
        bytes[0] = (byte) 0xDE;
        bytes[1] = (byte) 0xAD;
        Files.write(blob.toPath(), bytes);
        String hex = PackageViewer.readPreview(blob);
        assertTrue(hex.startsWith("00000000  de ad 00"), hex.substring(0, 40));
        assertTrue(hex.contains("00000010  00"), hex.substring(0, 120));
        assertTrue(hex.contains("... the first 4 KB of 4 KB are shown"));
        // Invalid json is shown as text
        File json = new File(dir, "bad.json");
        Files.writeString(json.toPath(), "{bad", StandardCharsets.UTF_8);
        assertEquals("{bad", PackageViewer.readPreview(json));
        assertNotNull(PackageViewer.describe(dir));
    }
}
