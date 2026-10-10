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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.ParquetImporter;
import com.github.sqljam.impexp.ScriptExportHandler;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: ImportPackageDialogTest verifies importing export packages: manifest summary, status, data source
 *               filter, legacy scripts and corrupted packages
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ImportPackageDialogTest {

    @TempDir
    File dir;

    private AppContext context;
    private Stage stage;
    private ConnectionProfile source;
    private ConnectionProfile target;
    private ImportPackageDialog dialog;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        source = UiDatabase.create(new File(dir, "source"), "Source H2");
        context.getProfileRegistry().saveProfile(source, true);
        target = new ConnectionProfile();
        target.setName("Target H2");
        target.setDbType(DbType.H2);
        target.setDatabase(new File(dir, "target/db").getAbsolutePath());
        target.setUsername("sa");
        target.setPassword("");
        context.getProfileRegistry().saveProfile(target, true);
        ConnectionProfile sqlite = new ConnectionProfile();
        sqlite.setName("Local SQLite");
        sqlite.setDbType(DbType.SQLITE);
        sqlite.setDatabase(new File(dir, "local.sqlite").getAbsolutePath());
        context.getProfileRegistry().saveProfile(sqlite, true);
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();
    }

    @AfterEach
    void close() {
        if (dialog != null) {
            FxTestSupport.run(dialog::close);
        }
        context.getSessionManager().closeAll();
    }

    /**
     * Exports tables of the source database as an export package
     */
    private File exportPackage() throws Exception {
        return exportPackage("package", DataFormat.SQL);
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

    @SuppressWarnings("unchecked")
    private <T extends Node> T node(String id) {
        return (T) dialog.getDialogPane().lookup("#" + id);
    }

    private void open(File directory) {
        dialog = FxTestSupport.call(() -> {
            ImportPackageDialog importDialog = new ImportPackageDialog(stage, context, null);
            importDialog.show();
            return importDialog;
        });
        if (directory != null) {
            FxTestSupport.run(() -> {
                TextField field = node("directoryField");
                field.setText(directory.getAbsolutePath());
                field.fireEvent(new ActionEvent());
            });
            FxTestSupport.waitUntil(() -> !"Loading...".equals(((Label) node("statusValue")).getText()));
        }
    }

    private String text(String id) {
        return FxTestSupport.call(() -> ((Label) node(id)).getText());
    }

    private boolean startDisabled() {
        return FxTestSupport.call(() -> dialog.getDialogPane().lookupButton(ButtonType.OK).isDisabled());
    }

    private List<String> targetNames() {
        return FxTestSupport.call(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<ConnectionProfile> combo = (ComboBox<ConnectionProfile>) node("targetCombo");
            return combo.getItems().stream().map(ConnectionProfile::getName).collect(Collectors.toList());
        });
    }

    private void setStatus(File packageDir, ExportManifest.Status status) throws Exception {
        ExportManifest manifest = ExportManifest.read(packageDir);
        manifest.setStatus(status);
        manifest.write(packageDir);
    }

    @Test
    void requiresDirectory() {
        open(null);
        assertTrue(startDisabled());
        assertEquals("-", text("statusValue"));
    }

    @Test
    void rejectsDirectoryWhichIsNotPackage() {
        File empty = new File(dir, "empty");
        assertTrue(empty.mkdirs());
        open(empty);
        assertTrue(startDisabled());
        open(new File(dir, "missing"));
        assertTrue(startDisabled());
        assertEquals("Please choose a directory exported by SqlJam", text("warningLabel"));
    }

    @Test
    void importsLegacyScriptsIntoAnyDataSource() throws Exception {
        File legacy = new File(dir, "legacy");
        assertTrue(legacy.mkdirs());
        Files.writeString(new File(legacy, ScriptExportHandler.SCHEMA_FILE_NAME).toPath(),
                "CREATE TABLE LEGACY (ID INT);\n");
        open(legacy);
        assertEquals("Legacy scripts (no manifest)", text("statusValue"));
        assertEquals(3, targetNames().size());
        assertFalse(startDisabled());
        ImportPackageDialog.ImportRequest request = FxTestSupport.call(dialog::buildRequest);
        assertEquals(legacy.getAbsolutePath(), request.getDirectory().getAbsolutePath());
        assertEquals(null, request.getManifest());
        assertEquals(legacy.getAbsolutePath(), context.getSettings().getLastImportDirectory());
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsManifestAndFiltersDataSources() throws Exception {
        File packageDir = exportPackage();
        open(packageDir);
        assertEquals("COMPLETED", text("statusValue"));
        assertTrue(text("sourceValue").startsWith("H2"), text("sourceValue"));
        assertTrue(text("targetValue").startsWith("H2"), text("targetValue"));
        assertTrue(text("filesValue").matches("\\d+ files, .*"), text("filesValue"));
        // Tables with rows are recorded in the manifest
        List<String> tables = FxTestSupport.call(() -> ((TableView<ExportManifest.TableEntry>) node("packageTables"))
                .getItems().stream().map(ExportManifest.TableEntry::getName).collect(Collectors.toList()));
        assertTrue(tables.containsAll(List.of("T_ONE", "T_PAGED")), tables.toString());
        // Only data sources of the target database type of the package
        assertEquals(List.of("Source H2", "Target H2"), targetNames());
        assertFalse(startDisabled());
        assertEquals("", text("warningLabel"));
    }

    @Test
    void rejectsFailedAndCancelledPackages() throws Exception {
        File packageDir = exportPackage();
        for (ExportManifest.Status status : new ExportManifest.Status[]{ExportManifest.Status.FAILED,
                ExportManifest.Status.CANCELLED}) {
            setStatus(packageDir, status);
            open(packageDir);
            assertEquals(status.name(), text("statusValue"));
            assertTrue(startDisabled());
            assertEquals("The package is " + status + ", it can not be imported", text("warningLabel"));
            FxTestSupport.run(dialog::close);
        }
    }

    @Test
    void warnsWithoutDataSourceOfTargetType() throws Exception {
        File packageDir = exportPackage();
        ExportManifest manifest = ExportManifest.read(packageDir);
        manifest.getTarget().setDbType(DbType.ORACLE);
        manifest.write(packageDir);
        open(packageDir);
        assertTrue(targetNames().isEmpty());
        assertTrue(startDisabled());
        assertEquals("The package is exported for Oracle, please create a Oracle data source first",
                text("warningLabel"));
    }

    private ProgressDialog importPackage(File packageDir) {
        ProgressDialog progress = FxTestSupport.call(() -> new ProgressDialog(stage, context, "Import", 3));
        FxTestSupport.run(() -> progress.run(() -> context.getTransferService().importScripts(target, null, null,
                packageDir, true, progress.getListener()), (AbstractFileImporter importer) -> String.format(
                "%d %d %d", importer.getExecutedCount(), importer.getLobCount(), importer.getFailedCount()), null));
        FxTestSupport.waitUntil(progress::isFinished);
        return progress;
    }

    private static String status(ProgressDialog progress) {
        return FxTestSupport.call(() -> ((Label) progress.getStage().getScene().getRoot().lookup("#progressStatus"))
                .getText());
    }

    @Test
    void importsPackage() throws Exception {
        File packageDir = exportPackage();
        ProgressDialog progress = importPackage(packageDir);
        assertEquals("Completed", status(progress));
        assertEquals("Overall 100%", FxTestSupport.call(() -> ((Label) progress.getStage().getScene().getRoot()
                .lookup("#percentLabel")).getText()));
        try (Connection connection = DriverManager.getConnection(target.getJdbcUrl(), "sa", "");
             ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM T_PAGED")) {
            rs.next();
            assertEquals(UiDatabase.PAGED_ROWS, rs.getInt(1));
        }
    }

    @Test
    void reportsModifiedFile() throws Exception {
        File packageDir = exportPackage();
        File dataFile = new File(packageDir, ScriptExportHandler.DATA_FILE_NAME);
        Files.writeString(dataFile.toPath(), "-- modified\n", StandardOpenOption.APPEND);
        ProgressDialog progress = importPackage(packageDir);
        assertEquals("Failed", status(progress));
        assertTrue(progress.getErrorLogCount() > 0);
    }

    @Test
    void reportsMissingFile() throws Exception {
        File packageDir = exportPackage();
        assertTrue(new File(packageDir, ScriptExportHandler.SCHEMA_FILE_NAME).delete());
        ProgressDialog progress = importPackage(packageDir);
        assertEquals("Failed", status(progress));
    }

    @Test
    void showsDataFormatOfPackages() throws Exception {
        open(exportPackage());
        assertEquals("SQL scripts", text("formatValue"));
        FxTestSupport.run(dialog::close);
        open(exportPackage("parquet", DataFormat.PARQUET));
        assertEquals("COMPLETED", text("statusValue"));
        assertEquals("Parquet", text("formatValue"));
        assertFalse(startDisabled());
    }

    @Test
    void guidesParquetFilesWithoutManifest() throws Exception {
        File parquetDir = exportPackage("parquet", DataFormat.PARQUET);
        assertTrue(new File(parquetDir, ExportManifest.FILE_NAME).delete());
        open(parquetDir);
        assertTrue(startDisabled());
        assertEquals("The directory contains Parquet files without manifest.json, please use Import Parquet Files",
                text("warningLabel"));
        assertTrue(ImportPackageDialog.isParquetDirectory(parquetDir));
        assertFalse(ImportPackageDialog.isParquetDirectory(dir));
    }

    @Test
    void viewsPackage() throws Exception {
        File packageDir = exportPackage();
        open(packageDir);
        FxTestSupport.run(() -> ((Button) node("viewPackageButton")).fire());
        FxTestSupport.waitUntil(() -> findViewer() != null);
        Stage viewer = findViewer();
        assertEquals("Export Package - package", FxTestSupport.call(viewer::getTitle));
        FxTestSupport.run(viewer::close);
    }

    private static Stage findViewer() {
        return FxTestSupport.call(() -> Window.getWindows().stream().filter(window -> window instanceof Stage
                && "Export Package - package".equals(((Stage) window).getTitle())).map(window -> (Stage) window)
                .findFirst().orElse(null));
    }

    @Test
    void importsParquetPackage() throws Exception {
        File packageDir = exportPackage("parquet", DataFormat.PARQUET);
        ProgressDialog progress = FxTestSupport.call(() -> new ProgressDialog(stage, context, "Import", 3));
        FxTestSupport.run(() -> progress.run(() -> context.getTransferService().importScripts(target, null, null,
                packageDir, true, progress.getListener()), (AbstractFileImporter importer) -> importer.getClass()
                .getSimpleName(), null));
        FxTestSupport.waitUntil(progress::isFinished);
        assertEquals("Completed", status(progress));
        assertEquals("Overall 100%", FxTestSupport.call(() -> ((Label) progress.getStage().getScene().getRoot()
                .lookup("#percentLabel")).getText()));
        assertTrue(AbstractFileImporter.forDirectory(packageDir, null, DbType.H2) instanceof ParquetImporter);
        try (Connection connection = DriverManager.getConnection(target.getJdbcUrl(), "sa", "");
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT COUNT(*), COUNT(DATA), MAX(LENGTH(NOTE)) FROM T_PAGED")) {
            rs.next();
            assertEquals(UiDatabase.PAGED_ROWS, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
            assertEquals(UiDatabase.LONG_TEXT_LENGTH, rs.getInt(3));
        }
    }
}
