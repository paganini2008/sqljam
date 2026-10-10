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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ParquetImporter;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: ImportParquetDialogTest verifies loading Parquet files from the UI: preview of columns and rows, table
 *               name, modes, unreadable files and loading into a database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ImportParquetDialogTest {

    private static final int ROWS = 120;

    @TempDir
    File dir;

    private AppContext context;
    private Stage stage;
    private ConnectionProfile target;
    private ImportParquetDialog dialog;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        target = new ConnectionProfile();
        target.setName("Target H2");
        target.setDbType(DbType.H2);
        target.setDatabase(new File(dir, "target/db").getAbsolutePath());
        target.setUsername("sa");
        target.setPassword("");
        context.getProfileRegistry().saveProfile(target, true);
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
     * Parquet file of another tool, written by DuckDB
     */
    private File writeParquet(String name, int from, int to) throws Exception {
        File file = new File(dir, name);
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute(String.format("COPY (SELECT range::INTEGER AS id, 'name ' || range AS name,"
                    + " DATE '2024-01-01' + range::INTEGER AS created FROM range(%d, %d)) TO '%s' (FORMAT parquet)",
                    from, to, file.getAbsolutePath().replace("'", "''")));
        }
        return file;
    }

    @SuppressWarnings("unchecked")
    private <T extends Node> T node(String id) {
        return (T) dialog.getDialogPane().lookup("#" + id);
    }

    private void open(DbNode node) {
        dialog = FxTestSupport.call(() -> {
            ImportParquetDialog parquetDialog = new ImportParquetDialog(stage, context, node);
            parquetDialog.show();
            return parquetDialog;
        });
    }

    private void addFiles(File... files) {
        FxTestSupport.run(() -> dialog.addFiles(List.of(files)));
        waitForPreview();
    }

    private void waitForPreview() {
        FxTestSupport.waitUntil(() -> !"Loading...".equals(text("parquetPreviewLabel")));
    }

    private String text(String id) {
        return FxTestSupport.call(() -> ((Label) node(id)).getText());
    }

    private boolean startDisabled() {
        return FxTestSupport.call(() -> dialog.getDialogPane().lookupButton(ButtonType.OK).isDisabled());
    }

    @SuppressWarnings("unchecked")
    private TableView<List<String>> preview() {
        return (TableView<List<String>>) node("parquetPreview");
    }

    @Test
    void requiresFilesAndTable() throws Exception {
        open(null);
        assertTrue(startDisabled());
        assertEquals("Files of one table are loaded together, columns and the first rows are previewed",
                text("parquetPreviewLabel"));
        // The only data source is the target
        assertEquals("Target H2", FxTestSupport.call(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<ConnectionProfile> combo = (ComboBox<ConnectionProfile>)
                    node("targetCombo");
            return combo.getValue().getName();
        }));
        addFiles(writeParquet("orders.parquet", 0, ROWS));
        assertFalse(startDisabled());
        assertEquals("orders", FxTestSupport.call(() -> ((TextField) node("tableField")).getText()));
        FxTestSupport.run(() -> ((TextField) node("tableField")).setText(" "));
        assertTrue(startDisabled());
        assertEquals("Please enter the name of the target table", text("warningLabel"));
    }

    @Test
    void previewsColumnsAndFirstRows() throws Exception {
        open(null);
        File first = writeParquet("part-1.parquet", 0, ROWS);
        File second = writeParquet("part-2.parquet", ROWS, ROWS + 30);
        addFiles(first, second);
        // The same file is added once
        addFiles(first);
        assertEquals(2, (int) FxTestSupport.call(() -> ((ListView<?>) node("parquetFiles")).getItems().size()));
        assertEquals("3 columns, " + (ROWS + 30) + " rows, the first " + ImportParquetDialog.PREVIEW_ROWS
                + " are shown", text("parquetPreviewLabel"));
        List<String> headers = FxTestSupport.call(() -> preview().getColumns().stream()
                .map(column -> ((Label) column.getGraphic()).getText()).collect(Collectors.toList()));
        assertEquals(List.of("id\ninteger", "name\nvarchar", "created\ndate"), headers);
        assertEquals(ImportParquetDialog.PREVIEW_ROWS, (int) FxTestSupport.call(() -> preview().getItems().size()));
        assertEquals("name 0", FxTestSupport.call(() -> preview().getItems().get(0).get(1)));
        assertEquals("part_1", FxTestSupport.call(() -> ((TextField) node("tableField")).getText()));
        // Files are removed, the preview is cleared
        FxTestSupport.run(() -> dialog.removeFiles(List.of(first, second)));
        assertTrue(FxTestSupport.call(() -> preview().getColumns().isEmpty()));
        assertTrue(startDisabled());
    }

    @Test
    void reportsUnreadableFiles() throws Exception {
        open(null);
        File broken = new File(dir, "broken.parquet");
        Files.writeString(broken.toPath(), "not parquet");
        addFiles(broken);
        FxTestSupport.waitUntil(() -> text("warningLabel").startsWith("Unable to read Parquet files"));
        assertTrue(startDisabled());
    }

    @Test
    void derivesTableNames() {
        assertEquals("orders", ImportParquetDialog.getTableName(new File("orders.parquet")));
        assertEquals("T_PAGED", ImportParquetDialog.getTableName(new File("PUBLIC.T_PAGED.parquet")));
        assertEquals("sales_2024_q1", ImportParquetDialog.getTableName(new File("sales-2024 q1.parquet")));
        assertEquals("t_2024", ImportParquetDialog.getTableName(new File("2024.parquet")));
        assertEquals("订单", ImportParquetDialog.getTableName(new File("订单.parquet")));
    }

    @Test
    void buildsRequest() throws Exception {
        open(DbNode.connection(target));
        File file = writeParquet("orders.parquet", 0, 10);
        addFiles(file);
        FxTestSupport.run(() -> {
            ((RadioButton) node("appendRadio")).setSelected(true);
            ((TextField) node("tableField")).setText(" ORDERS ");
        });
        ImportParquetDialog.ParquetRequest request = FxTestSupport.call(dialog::buildRequest);
        assertEquals(List.of(file), request.getFiles());
        assertEquals("ORDERS", request.getTableName());
        assertEquals(ParquetImporter.Mode.APPEND, request.getMode());
        assertEquals(target.getId(), request.getTarget().getId());
        assertNull(request.getSchema());
    }

    private ProgressDialog load(List<File> files, ParquetImporter.Mode mode) {
        ProgressDialog progress = FxTestSupport.call(() -> new ProgressDialog(stage, context, "Import", 1));
        FxTestSupport.run(() -> progress.run(() -> context.getTransferService().importParquet(target, null, null,
                files, "ORDERS", mode, progress.getListener()), (ParquetImporter importer) -> String.valueOf(
                importer.getImportedRows()), null));
        FxTestSupport.waitUntil(progress::isFinished);
        return progress;
    }

    private static String status(ProgressDialog progress) {
        return FxTestSupport.call(() -> ((Label) progress.getStage().getScene().getRoot().lookup("#progressStatus"))
                .getText());
    }

    private int countRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(target.getJdbcUrl(), "sa", "");
             ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM ORDERS")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void loadsFilesIntoDatabase() throws Exception {
        File first = writeParquet("orders-1.parquet", 0, ROWS);
        ProgressDialog created = load(List.of(first), ParquetImporter.Mode.CREATE);
        assertEquals("Completed", status(created));
        assertEquals(ROWS, countRows());
        FxTestSupport.run(() -> created.getStage().close());
        // Rows are appended to the existing table
        ProgressDialog appended = load(List.of(writeParquet("orders-2.parquet", ROWS, ROWS + 5)),
                ParquetImporter.Mode.APPEND);
        assertEquals("Completed", status(appended));
        assertEquals(ROWS + 5, countRows());
        FxTestSupport.run(() -> appended.getStage().close());
        // Replaced by CREATE
        ProgressDialog replaced = load(List.of(first), ParquetImporter.Mode.CREATE);
        assertEquals("Completed", status(replaced));
        assertEquals(ROWS, countRows());
        FxTestSupport.run(() -> replaced.getStage().close());
    }
}
