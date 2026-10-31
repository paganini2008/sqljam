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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ColumnInfo;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.impexp.DbType;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/**
 * @Description: SyncTabTest creates a sync task in its dialog between two H2 databases, the watermark column is
 *               guessed, wrong input is refused, and the list of the tasks runs it now, shows its status and rows,
 *               opens its report, switches it off and deletes it. An edited task keeps its watermarks.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class SyncTabTest {

    @TempDir
    File dir;

    private AppContext context;
    private MainView mainView;
    private Stage stage;
    private ConnectionProfile source;
    private ConnectionProfile target;

    private ConnectionProfile h2(String name) throws Exception {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setDbType(DbType.H2);
        profile.setDatabase(new File(dir, name).getAbsolutePath());
        profile.setUsername("sa");
        profile.setPassword("");
        context.getProfileRegistry().saveProfile(profile, true);
        return profile;
    }

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        source = h2("source");
        target = h2("target");
        try (Connection connection = DriverManager.getConnection(source.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ORDERS (ID INT PRIMARY KEY, NAME VARCHAR(20), UPDATED_AT TIMESTAMP)");
            statement.execute("CREATE TABLE NOTES (CODE VARCHAR(10) PRIMARY KEY, TEXT VARCHAR(20))");
            for (int i = 1; i <= 4; i++) {
                statement.execute("INSERT INTO ORDERS VALUES (" + i + ", 'order " + i + "', TIMESTAMP "
                        + "'2026-10-01 08:0" + i + ":00')");
            }
        }
        mainView = new MainView(context);
        stage.setScene(new Scene(mainView, 1200, 800));
        // The window of the tests keeps the size of the tests before, the toolbar needs its own width
        stage.setWidth(1200);
        stage.setHeight(800);
        stage.show();
    }

    @AfterEach
    void close() {
        FxTestSupport.closeAllDialogs();
        context.closeSyncScheduler();
        context.getSessionManager().closeAll();
    }

    private TabPane tabs() {
        return (TabPane) mainView.lookup("#tableTabs");
    }

    private SyncTab openSync() {
        MenuItem item = FxTestSupport.menuItem(mainView, "syncMenuItem");
        FxTestSupport.run(item::fire);
        FxTestSupport.run(item::fire);
        List<SyncTab> tabs = FxTestSupport.call(() -> tabs().getTabs().stream().filter(SyncTab.class::isInstance)
                .map(SyncTab.class::cast).collect(Collectors.toList()));
        assertEquals(1, tabs.size());
        return tabs.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T node(SyncTaskDialog dialog, String id) {
        return (T) dialog.getFields().lookup("#" + id);
    }

    /**
     * The dialog of a new task: ORDERS with its update time, into the target
     */
    private SyncTaskDialog dialog(SyncTask task) {
        SyncTaskDialog dialog = FxTestSupport.call(() -> new SyncTaskDialog(stage, context, task));
        if (task == null) {
            FxTestSupport.run(() -> {
                ((TextField) node(dialog, "syncNameField")).setText("orders");
                ComboBox<ConnectionProfile> sources = node(dialog, "syncSourceCombo");
                sources.setValue(sources.getItems().stream().filter(profile -> profile.getName().equals("source"))
                        .findFirst().orElseThrow());
            });
        }
        TableView<SyncTaskDialog.TableRow> tables = node(dialog, "syncTables");
        FxTestSupport.waitUntil(() -> tables.getItems().size() == 2);
        return dialog;
    }

    private static SyncTaskDialog.TableRow row(SyncTaskDialog dialog, String name) {
        TableView<SyncTaskDialog.TableRow> tables = node(dialog, "syncTables");
        return tables.getItems().stream().filter(row -> row.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void createsAndRunsTasks() throws Exception {
        SyncTab tab = openSync();
        assertTrue(tab.getTasks().isEmpty());
        SyncTaskDialog dialog = dialog(null);
        assertEquals(List.of(SyncTask.Kind.DATABASE, SyncTask.Kind.VECTOR), FxTestSupport.call(() ->
                ((ComboBox<SyncTask.Kind>) node(dialog, "syncKindCombo")).getItems()));
        assertEquals("Choose the target data source.", FxTestSupport.call(dialog::validate));
        FxTestSupport.run(() -> {
            ComboBox<ConnectionProfile> targets = node(dialog, "syncTargetCombo");
            targets.setValue(targets.getItems().stream().filter(profile -> profile.getName().equals("target"))
                    .findFirst().orElseThrow());
        });
        assertEquals("Check at least one table.", FxTestSupport.call(dialog::validate));
        // ORDERS is checked and shown, its update time is guessed
        SyncTaskDialog.TableRow orders = row(dialog, "ORDERS");
        FxTestSupport.run(() -> orders.selected.set(true));
        FxTestSupport.waitUntil(() -> "UPDATED_AT".equals(orders.watermarkColumn.get()));
        assertEquals(SyncTask.WatermarkType.UPDATED_AT, orders.watermarkType.get());
        // The target schema is the source schema of the same database file is not, PUBLIC of another one is fine
        FxTestSupport.waitUntil(() -> "PUBLIC".equals(((ComboBox<String>) node(dialog, "syncTargetSchemaCombo"))
                .getValue()));
        FxTestSupport.run(() -> ((ComboBox<SyncTask.ScheduleType>) node(dialog, "syncScheduleCombo")).setValue(
                SyncTask.ScheduleType.DAILY));
        FxTestSupport.run(() -> ((TextField) node(dialog, "syncDailyField")).setText("25:00"));
        assertEquals("Type the time of the day as HH:mm, e.g. 02:00.", FxTestSupport.call(dialog::validate));
        FxTestSupport.run(() -> ((ComboBox<SyncTask.ScheduleType>) node(dialog, "syncScheduleCombo")).setValue(
                SyncTask.ScheduleType.MANUAL));
        assertNull(FxTestSupport.call(dialog::validate));
        SyncTask task = FxTestSupport.call(dialog::buildTask);
        assertEquals("orders", task.getName());
        assertEquals(source.getId(), task.getSourceId());
        assertEquals("PUBLIC", task.getSourceSchema());
        assertEquals(1, task.getTables().size());
        assertEquals("UPDATED_AT", task.getTables().get(0).getWatermarkColumn());
        FxTestSupport.run(() -> tab.save(task));
        assertEquals(1, tab.getTasks().size());
        assertEquals("source / PUBLIC → target / PUBLIC", FxTestSupport.call(() -> tab.route(tab.getTasks().get(0)))
                .replace(" / " + source.getDatabase(), "").replace(" / " + target.getDatabase(), "").replace(
                        " / SOURCE", "").replace(" / TARGET", ""));

        // Run Now: the rows are copied, the list shows the status and the rows, the report opens
        TableView<SyncTask> table = tab.getTable();
        FxTestSupport.run(() -> table.getSelectionModel().select(0));
        TransferReport report = FxTestSupport.call(() -> tab.runNow(tab.getTasks().get(0))).get(60,
                TimeUnit.SECONDS);
        assertEquals(TransferReport.Status.COMPLETED, report.getStatus(), report.getFailure());
        FxTestSupport.waitUntil(() -> "Completed".equals(table.getColumns().get(1).getCellData(0)));
        assertEquals("4", FxTestSupport.call(() -> table.getColumns().get(2).getCellData(0)));
        assertEquals("Run Now only", FxTestSupport.call(() -> table.getColumns().get(3).getCellData(0)));
        try (Connection connection = DriverManager.getConnection(target.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM ORDERS")) {
            rs.next();
            assertEquals(4, rs.getInt(1));
        }
        Button reportButton = (Button) tab.getContent().lookup("#syncReportButton");
        FxTestSupport.waitUntil(() -> !reportButton.isDisabled());
        FxTestSupport.run(reportButton::fire);
        ReportTab opened = (ReportTab) FxTestSupport.call(() -> tabs().getSelectionModel().getSelectedItem());
        assertEquals(report.getId(), opened.getReport().getId());
        assertEquals(TransferReport.Kind.SYNC, opened.getReport().getKind());

        // Edited: the watermark of ORDERS stays, NOTES has none
        SyncTask saved = context.getSyncScheduler().getStore().getTasks().get(0);
        assertNotNull(saved.getTables().get(0).getWatermark());
        SyncTaskDialog edit = dialog(saved);
        assertEquals("orders", FxTestSupport.call(() -> ((TextField) node(edit, "syncNameField")).getText()));
        assertTrue(row(edit, "ORDERS").selected.get());
        FxTestSupport.run(() -> row(edit, "NOTES").selected.set(true));
        SyncTaskDialog.TableRow notes = row(edit, "NOTES");
        FxTestSupport.run(() -> ((TableView<SyncTaskDialog.TableRow>) node(edit, "syncTables")).getSelectionModel()
                .select(notes));
        FxTestSupport.waitUntil(() -> notes.candidates != null);
        // A table without a time or a number has nothing to guess
        assertEquals("Choose the watermark column of NOTES.", FxTestSupport.call(edit::validate));
        FxTestSupport.run(() -> notes.selected.set(false));
        SyncTask edited = FxTestSupport.call(edit::buildTask);
        assertEquals(saved.getId(), edited.getId());
        assertEquals(saved.getTables().get(0).getWatermark().getValue(), edited.getTables().get(0).getWatermark()
                .getValue());

        // Switched off and on, deleted after the question
        Button toggle = (Button) tab.getContent().lookup("#syncToggleButton");
        FxTestSupport.run(toggle::fire);
        assertFalse(context.getSyncScheduler().getStore().getTasks().get(0).isEnabled());
        assertEquals("Disabled", FxTestSupport.call(() -> table.getColumns().get(5).getCellData(0)));
        assertEquals("Enable", FxTestSupport.call(toggle::getText));
        FxTestSupport.run(toggle::fire);
        assertTrue(context.getSyncScheduler().getStore().getTasks().get(0).isEnabled());
        SyncTask toDelete = tab.getTasks().get(0);
        Platform.runLater(() -> tab.delete(toDelete));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        assertEquals(1, tab.getTasks().size());
        Platform.runLater(() -> tab.delete(toDelete));
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> tab.getTasks().isEmpty());
        assertEquals("No sync tasks yet, New creates one", ((Label) table.getPlaceholder()).getText());
    }

    @Test
    void refusesTheSourceAsTarget() {
        SyncTaskDialog dialog = dialog(null);
        FxTestSupport.run(() -> {
            ComboBox<ConnectionProfile> targets = node(dialog, "syncTargetCombo");
            targets.setValue(targets.getItems().stream().filter(profile -> profile.getName().equals("source"))
                    .findFirst().orElseThrow());
            row(dialog, "ORDERS").selected.set(true);
        });
        FxTestSupport.waitUntil(() -> "UPDATED_AT".equals(row(dialog, "ORDERS").watermarkColumn.get()));
        FxTestSupport.waitUntil(() -> "PUBLIC".equals(((ComboBox<String>) node(dialog, "syncTargetSchemaCombo"))
                .getValue()));
        FxTestSupport.run(() -> ((ComboBox<String>) node(dialog, "syncTargetCatalogCombo")).setValue(((ComboBox<
                String>) node(dialog, "syncSourceCatalogCombo")).getValue()));
        assertEquals("The target is the source itself, choose another data source or schema.", FxTestSupport.call(
                dialog::validate));
        FxTestSupport.run(() -> ((TextField) node(dialog, "syncNameField")).setText(" "));
        assertEquals("Name the task.", FxTestSupport.call(dialog::validate));
    }

    @Test
    void warnsOfPasswordsWhichAreNotRemembered() throws Exception {
        ConnectionProfile secret = h2("secret");
        secret.setPassword("x");
        context.getProfileRegistry().saveProfile(secret, false);
        SyncTab tab = openSync();
        SyncTask task = new SyncTask();
        task.setName("scheduled");
        task.setSourceId(source.getId());
        task.setTargetId(secret.getId());
        task.getTables().add(new SyncTask.Table("ORDERS", "ID", SyncTask.WatermarkType.INCREASING_KEY));
        Platform.runLater(() -> tab.save(task));
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> pane
                .getContentText() != null && pane.getContentText().contains("password of secret is not remembered")));
        FxTestSupport.closeDialog(ButtonType.OK);
        assertEquals("Every 60 min", FxTestSupport.call(() -> tab.getTable().getColumns().get(3).getCellData(0)));
    }

    /**
     * Copy Command gives the command line of the task, SqlJam registers nothing with the system
     */
    @Test
    void copiesTheCommandOfATask() throws Exception {
        SyncTab tab = openSync();
        SyncTask task = new SyncTask();
        task.setName("nightly");
        task.setSourceId(source.getId());
        task.setTargetId(target.getId());
        task.getTables().add(new SyncTask.Table("ORDERS", "ID", SyncTask.WatermarkType.INCREASING_KEY));
        task.setScheduleType(SyncTask.ScheduleType.MANUAL);
        FxTestSupport.run(() -> tab.save(task));
        // The toolbar puts its buttons into the scene when it is laid out
        Button command = FxTestSupport.call(() -> {
            tab.getContent().applyCss();
            ((javafx.scene.Parent) tab.getContent()).layout();
            return (Button) tab.getContent().lookup("#syncCommandButton");
        });
        assertTrue(FxTestSupport.call(command::isDisabled));
        FxTestSupport.run(() -> tab.getTable().getSelectionModel().select(0));
        assertFalse(FxTestSupport.call(command::isDisabled));
        FxTestSupport.run(command::fire);
        // The headless screen of the tests has no clipboard of the system, the copied line is told
        String copied = FxTestSupport.call(() -> tab.copyCommand(tab.getTable().getSelectionModel()
                .getSelectedItem()));
        assertTrue(copied.endsWith(" sync --task " + task.getId()), copied);
        assertNull(FxTestSupport.call(() -> tab.copyCommand(null)));
    }

    @Test
    void guessesWatermarkColumns() {
        ColumnInfo id = column("ID", "INTEGER", true);
        ColumnInfo name = column("NAME", "VARCHAR", false);
        ColumnInfo created = column("CREATED_AT", "TIMESTAMP", false);
        ColumnInfo modified = column("GMT_MODIFIED", "DATETIME", false);
        SyncTaskDialog.TableRow row = new SyncTaskDialog.TableRow("T");
        assertEquals(List.of("GMT_MODIFIED", "ID", "CREATED_AT"), SyncTaskDialog.columns(List.of(id, name, created,
                modified), row));
        assertEquals(SyncTask.WatermarkType.UPDATED_AT, row.watermarkType.get());
        SyncTaskDialog.TableRow keyed = new SyncTaskDialog.TableRow("K");
        assertEquals(List.of("ID", "CREATED_AT"), SyncTaskDialog.columns(List.of(name, created, id), keyed));
        assertEquals(SyncTask.WatermarkType.INCREASING_KEY, keyed.watermarkType.get());
        assertEquals(List.of(), SyncTaskDialog.columns(List.of(name), new SyncTaskDialog.TableRow("N")));
        assertEquals("Every day at 02:00", SyncTab.schedule(new SyncTask() {
            {
                setScheduleType(ScheduleType.DAILY);
            }
        }));
    }

    private static ColumnInfo column(String name, String type, boolean key) {
        ColumnInfo column = new ColumnInfo();
        column.setName(name);
        column.setTypeName(type);
        column.setPrimaryKey(key);
        return column;
    }
}
