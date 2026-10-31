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

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.it.ItDatabase;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: SyncDemo shows the scheduled sync on the screen, step by step with captions: a task is created in
 *               its dialog (MySQL orders into PostgreSQL by the time of the last update), it runs and shows its
 *               report, rows of the source change and the next run copies only them, and the history lists both
 *               runs. It is not a test of the build (the name does not end with Test), run it by
 *               mvn test -Pui -Dtest=SyncDemo
 *               on the databases of the integration tests, its tables are dropped when the tests are cleaned up.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class SyncDemo {

    private static final String STYLESHEET = "/com/github/sqljam/face/app.css";
    private static final String TABLE = "sjm_sync_orders";
    private static final String SCHEMA = "sjit_sync";
    private static final long STEP = Long.getLong("demo.step", 1600);

    @TempDir
    File dir;

    private Stage stage;
    private AppContext context;
    private MainView mainView;
    private Label caption;
    private ConnectionProfile mysql;
    private ConnectionProfile postgresql;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        FxTestSupport.run(() -> ThemeManager.apply(ThemeManager.DEFAULT_THEME));
        context = FxTestSupport.createContext(dir);
        mysql = ItDatabase.MYSQL.getSourceProfile();
        mysql.setName("MySQL on 63");
        context.getProfileRegistry().saveProfile(mysql, true);
        postgresql = ItDatabase.POSTGRESQL.getSourceProfile();
        postgresql.setName("PostgreSQL on 63");
        context.getProfileRegistry().saveProfile(postgresql, true);
        resetData();
        mainView = new MainView(context);
        caption = new Label();
        caption.setWrapText(true);
        caption.setMaxWidth(Double.MAX_VALUE);
        caption.setPadding(new Insets(12, 18, 12, 18));
        caption.setStyle("-fx-font-size: 17px; -fx-text-fill: white; -fx-background-color: #1f6feb;");
        BorderPane root = new BorderPane(mainView);
        root.setBottom(caption);
        Scene scene = new Scene(root, 1360, 860);
        scene.getStylesheets().add(SyncDemo.class.getResource(STYLESHEET).toExternalForm());
        stage.setScene(scene);
        stage.setTitle("SqlJam: scheduled sync demo");
        Branding.applyIcons(stage);
        stage.show();
        stage.centerOnScreen();
    }

    /**
     * Five orders in MySQL, no copy in PostgreSQL yet
     */
    private void resetData() throws Exception {
        try (Connection connection = ItDatabase.MYSQL.getSourceConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE + " (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, customer "
                    + "VARCHAR(50), amount DECIMAL(10,2), updated_at TIMESTAMP(3) NOT NULL DEFAULT "
                    + "CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3))");
            statement.execute("INSERT INTO " + TABLE + " (customer, amount) VALUES ('Alice', 120.00), ('Bob', "
                    + "80.50), ('Carol', 300.00), ('Dave', 45.25), ('Eve', 99.99)");
        }
        try (Connection connection = DriverManager.getConnection(postgresql.getJdbcUrl(), postgresql.getUsername(),
                postgresql.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static <T> T onFx(Callable<T> callable) {
        return FxTestSupport.call(callable);
    }

    private void say(String text) {
        FxTestSupport.run(() -> caption.setText(text));
        pause(STEP + text.length() * 25L);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Glows the node a moment, so that the step is seen before it happens
     */
    private static void highlight(Node node) {
        String style = onFx(node::getStyle);
        FxTestSupport.run(() -> node.setStyle(style + "; -fx-effect: dropshadow(gaussian, #ffb000, 18, 0.75, 0, 0);"));
        pause(900);
        FxTestSupport.run(() -> node.setStyle(style));
    }

    private static void press(Node node) {
        highlight(node);
        Platform.runLater(((ButtonBase) node)::fire);
        pause(700);
    }

    private static void type(TextField field, String text) {
        highlight(field);
        FxTestSupport.run(field::clear);
        for (char c : text.toCharArray()) {
            FxTestSupport.run(() -> field.appendText(String.valueOf(c)));
            pause(60);
        }
        pause(400);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void choose(Node node, Object value) {
        ComboBox combo = (ComboBox) node;
        highlight(combo);
        FxTestSupport.run(combo::show);
        pause(900);
        FxTestSupport.run(() -> {
            combo.setValue(value);
            combo.hide();
        });
        pause(500);
    }

    /**
     * Node of the id in the topmost window which has it, e.g. a dialog over the main window
     */
    private static Node top(String id) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Node node = onFx(() -> {
                List<Window> windows = new ArrayList<>(Window.getWindows());
                for (int i = windows.size() - 1; i >= 0; i--) {
                    Window window = windows.get(i);
                    if (window.isShowing() && window.getScene() != null) {
                        Node found = window.getScene().getRoot().lookup("#" + id);
                        if (found != null && found.isVisible()) {
                            return found;
                        }
                    }
                }
                return null;
            });
            if (node != null) {
                return node;
            }
            pause(100);
        }
        throw new AssertionError("#" + id + " is not shown");
    }

    private void menu(String id) {
        MenuItem item = FxTestSupport.menuItem(mainView, id);
        FxTestSupport.run(() -> item.getParentMenu().show());
        pause(1200);
        FxTestSupport.run(() -> {
            item.getParentMenu().hide();
            item.fire();
        });
        pause(900);
    }

    private SyncTask task() {
        return context.getSyncScheduler().getStore().getTasks().get(0);
    }

    private String targetRows() throws Exception {
        StringBuilder rows = new StringBuilder();
        try (Connection connection = DriverManager.getConnection(postgresql.getJdbcUrl(), postgresql.getUsername(),
                postgresql.getPassword()); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT customer, amount FROM " + SCHEMA + "." + TABLE
                     + " ORDER BY id")) {
            while (rs.next()) {
                rows.append(rows.length() > 0 ? ", " : "").append(rs.getString(1)).append(' ').append(rs
                        .getBigDecimal(2));
            }
        }
        return rows.toString();
    }

    private void runNow(SyncTab tab) {
        TableView<SyncTask> table = tab.getTable();
        FxTestSupport.run(() -> table.getSelectionModel().select(0));
        long runs = task().getLastRunAt();
        press(tab.getContent().lookup("#syncRunButton"));
        FxTestSupport.waitUntil(() -> task().getLastRunAt() != runs && !context.getSyncScheduler().isRunning(task()
                .getId()));
        FxTestSupport.run(tab::refresh);
        pause(1500);
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsScheduledSync() throws Exception {
        say("Scheduled sync: keep a copy of MySQL orders in PostgreSQL, copying only the rows which changed.");
        menu("syncMenuItem");
        SyncTab tab = onFx(() -> (SyncTab) ((TabPane) mainView.lookup("#tableTabs")).getSelectionModel()
                .getSelectedItem());
        say("Transfer > Sync Tasks lists the tasks. New creates one.");
        press(tab.getContent().lookup("#syncAddButton"));

        say("Name the task and choose the source: the MySQL data source and its database.");
        type((TextField) top("syncNameField"), "Orders to PostgreSQL");
        ComboBox<ConnectionProfile> sources = (ComboBox<ConnectionProfile>) top("syncSourceCombo");
        choose(sources, onFx(() -> sources.getItems().stream().filter(profile -> profile.getName().equals(
                "MySQL on 63")).findFirst().orElseThrow()));
        ComboBox<String> catalogs = (ComboBox<String>) top("syncSourceCatalogCombo");
        FxTestSupport.waitUntil(() -> catalogs.getItems().contains(ItDatabase.MYSQL.getSourceCatalog()));
        choose(catalogs, ItDatabase.MYSQL.getSourceCatalog());
        TableView<SyncTaskDialog.TableRow> tables = (TableView<SyncTaskDialog.TableRow>) top("syncTables");
        FxTestSupport.waitUntil(() -> tables.getItems().stream().anyMatch(row -> row.name.equals(TABLE)));
        SyncTaskDialog.TableRow orders = onFx(() -> tables.getItems().stream().filter(row -> row.name.equals(TABLE))
                .findFirst().orElseThrow());
        say("Check the table of the orders. SqlJam picks its watermark: updated_at, the time of the last change.");
        FxTestSupport.run(() -> {
            tables.scrollTo(orders);
            orders.selected.set(true);
        });
        FxTestSupport.waitUntil(() -> "updated_at".equalsIgnoreCase(orders.watermarkColumn.get()));
        highlight(top("syncWatermarkCombo"));
        highlight(top("syncWatermarkTypeCombo"));

        say("The target: PostgreSQL, into a schema of its own. Missing tables are created with their keys.");
        ComboBox<ConnectionProfile> targets = (ComboBox<ConnectionProfile>) top("syncTargetCombo");
        choose(targets, onFx(() -> targets.getItems().stream().filter(profile -> profile.getName().equals(
                "PostgreSQL on 63")).findFirst().orElseThrow()));
        ComboBox<String> schemas = (ComboBox<String>) top("syncTargetSchemaCombo");
        FxTestSupport.waitUntil(() -> !schemas.getItems().isEmpty());
        highlight(schemas);
        FxTestSupport.run(() -> schemas.setValue(SCHEMA));
        pause(600);

        say("Every 15 minutes, while SqlJam is open. Copy Command gives its command line for a cron of your own.");
        ComboBox<SyncTask.ScheduleType> schedules = (ComboBox<SyncTask.ScheduleType>) top("syncScheduleCombo");
        choose(schedules, SyncTask.ScheduleType.INTERVAL);
        highlight(top("syncIntervalSpinner"));
        FxTestSupport.run(() -> ((javafx.scene.control.Spinner<Integer>) top("syncIntervalSpinner"))
                .getValueFactory().setValue(15));
        DialogPane pane = onFx(() -> (DialogPane) top("syncNameField").getScene().getRoot());
        press(onFx(() -> pane.lookupButton(ButtonType.OK)));
        FxTestSupport.waitUntil(() -> !tab.getTasks().isEmpty());

        say("Run Now: the first run copies all rows. The watermark is read before the rows.");
        runNow(tab);
        say("Completed, 5 rows. PostgreSQL has: " + targetRows() + ".");
        say("Report opens the report of the run: rows of each table, checked in the target, and the "
                + "watermark.");
        press(tab.getContent().lookup("#syncReportButton"));
        pause(3500);
        FxTestSupport.run(() -> ((TabPane) mainView.lookup("#tableTabs")).getSelectionModel().select(tab));

        try (Connection connection = ItDatabase.MYSQL.getSourceConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE " + TABLE + " SET amount = 150.00 WHERE customer = 'Alice'");
            statement.execute("UPDATE " + TABLE + " SET customer = 'Bob Jr.' WHERE customer = 'Bob'");
            statement.execute("INSERT INTO " + TABLE + " (customer, amount) VALUES ('Frank', 60.00), ('Grace', "
                    + "210.00)");
            statement.execute("DELETE FROM " + TABLE + " WHERE customer = 'Dave'");
        }
        say("Meanwhile in MySQL: Alice is now 150.00, Bob is renamed Bob Jr., Frank and Grace are new, Dave is "
                + "deleted.");
        say("Run Now again: only the rows after the watermark are read, and updated or inserted by key.");
        runNow(tab);
        say("PostgreSQL now has: " + targetRows() + ". Dave stays: deleted rows are kept in the target.");
        press(tab.getContent().lookup("#syncReportButton"));
        say("The report tells the rows which changed since the last run and the new watermark.");
        pause(3000);

        say("Transfer > Import History keeps the report of every run, next to the imports and exports.");
        menu("historyMenuItem");
        pause(4000);
        say("That is the scheduled sync. The tables of this demo are dropped with the other test tables.");
        pause(2000);
    }
}
