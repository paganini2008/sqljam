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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.apache.commons.io.FileUtils;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.ExampleDatabase;
import com.github.sqljam.face.service.JdbcUrls;
import com.github.sqljam.impexp.DbType;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: TutorialVideo records the tutorial video of SqlJam with the example database: connect, query rows,
 *               export into CSV files, import the package into PostgreSQL (with a partitioned table in the tree),
 *               query with the SQL editor and export the result into PostgreSQL, export directly into SQL Server
 *               (its data source created from a pasted JDBC URL) and Db2 (skipping existing rows and rows with
 *               errors). Imports run in one transaction, the package import is confirmed before its commit.
 *               Subtitles are in English, the background music is generated. It runs about 3 minutes.
 *               It is recorded once per major version, it is not a test of the build (the name does not
 *               end with Test), run it by
 *               mvn test -Dtest=TutorialVideo -Dvideo.file=docs/assets/sqljam-tutorial.mp4
 *               The windows are shown on the screen while recording, the target databases are those of the
 *               integration tests (-Dvideo.postgresql.url, -Dvideo.sqlserver.url, -Dvideo.db2.url, user and
 *               password by -Dvideo.username and -Dvideo.password). Recorded tables are
 *               dropped afterwards and an existing table of the same name stops the recording.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class TutorialVideo {

    private static final String STYLESHEET = "/com/github/sqljam/face/app.css";
    private static final String SCHEMA = "shop";
    private static final List<String> TABLES = List.of("categories", "customers", "products", "orders",
            "order_items");
    private static final String USERNAME = System.getProperty("video.username", "fengy");
    private static final String PASSWORD = System.getProperty("video.password", "123456");
    private static final String POSTGRESQL_URL = System.getProperty("video.postgresql.url",
            "jdbc:postgresql://localhost:5432/demo");
    private static final String SQLSERVER_URL = System.getProperty("video.sqlserver.url",
            "jdbc:sqlserver://localhost:1433;databaseName=demo;encrypt=false;trustServerCertificate=true");
    private static final String DB2_URL = System.getProperty("video.db2.url", "jdbc:db2://localhost:50000/demo");
    /**
     * Schema of Db2, names of Db2 are upper case
     */
    private static final String DB2_SCHEMA = "SHOP";
    /**
     * Partitioned table of PostgreSQL shown in the tree with its partitions
     */
    private static final String PARTITIONED = "order_archive";
    /**
     * Table of the rows of the SQL editor, exported into PostgreSQL
     */
    private static final String QUERY_TABLE = "category_revenue";
    private static final double WIDTH = 1280;
    private static final double HEIGHT = 800;

    @TempDir
    File dir;

    private Stage stage;
    private StackPane holder;
    private AppContext context;
    private MainView mainView;
    private VideoRecorder recorder;
    private File packageDir;
    /**
     * Tables are dropped only after the check found none of them, tables of the user are never touched
     */
    private boolean targetsChecked;
    /**
     * Keeps Db2 active while recording, a database without connections is deactivated and started slowly
     */
    private Connection db2Connection;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        FxTestSupport.run(() -> ThemeManager.apply(ThemeManager.DEFAULT_THEME));
        context = FxTestSupport.createContext(dir);
        ConnectionProfile example = context.getExampleDatabase().create();
        context.getProfileRegistry().saveProfile(example, true);
        saveProfile("PostgreSQL demo", POSTGRESQL_URL);
        saveProfile("Db2 demo", DB2_URL);
        holder = new StackPane();
        holder.setStyle("-fx-background-color: -color-bg-default;");
        Scene scene = new Scene(holder, WIDTH, HEIGHT);
        scene.getStylesheets().add(TutorialVideo.class.getResource(STYLESHEET).toExternalForm());
        stage.setScene(scene);
        stage.setTitle("SqlJam");
        Branding.applyIcons(stage);
        stage.show();
        stage.centerOnScreen();
    }

    /**
     * Data source of the url: database type, host, port and database read from it
     */
    private void saveProfile(String name, String url) throws Exception {
        ConnectionProfile profile = JdbcUrls.parse(url);
        profile.setName(name);
        profile.setUsername(USERNAME);
        profile.setPassword(PASSWORD);
        context.getProfileRegistry().saveProfile(profile, true);
    }

    @AfterEach
    void close() throws Exception {
        context.getSessionManager().closeAll();
        FxTestSupport.closeAllDialogs();
        if (db2Connection != null) {
            db2Connection.close();
        }
        if (targetsChecked) {
            dropTables();
        }
        FileUtils.deleteQuietly(packageDir);
    }

    @Test
    void record() throws Exception {
        checkTargets();
        targetsChecked = true;
        db2Connection = DriverManager.getConnection(DB2_URL, USERNAME, PASSWORD);
        File work = new File(System.getProperty("video.work", "target/video"));
        work.mkdirs();
        packageDir = new File(work, "shop-export");
        FileUtils.deleteQuietly(packageDir);
        File output = new File(System.getProperty("video.file", "target/video/sqljam-tutorial.mp4"));
        recorder = FxTestSupport.call(() -> new VideoRecorder(stage, System.getProperty("ffmpeg", "ffmpeg"), 1.25,
                24, 100));
        recorder.start(new File(work, "raw.mp4"));

        intro();
        connect();
        query();
        exportFiles();
        importPackage();
        sqlQuery();
        exportSqlServer();
        exportDb2();
        outro();

        recorder.stop();
        File music = new File(work, "music.wav");
        TutorialMusic.write(music, recorder.getDurationSeconds() + 2);
        recorder.finish(output, music, System.getProperty("video.font", "Helvetica Neue"));
        assertTrue(output.length() > 0);
        assertTrue(recorder.getDurationSeconds() < 200, "The video is longer than about 3 minutes");
    }

    private void intro() throws Exception {
        FxTestSupport.run(() -> holder.getChildren().setAll(card("Export & Import",
                "Getting started with the example database",
                "Example Shop (H2)  →  PostgreSQL · SQL Server · Db2")));
        recorder.caption("Getting started with the example database");
        pause(3500);
    }

    private void outro() throws Exception {
        FxTestSupport.run(() -> holder.getChildren().setAll(card("Schema, data and all.",
                "Move tables across databases", Branding.REPOSITORY_URL)));
        recorder.caption("More on GitHub: paganini2008/sqljam");
        pause(4000);
    }

    private static VBox card(String title, String subtitle, String footer) {
        ImageView logo = Branding.logoView(150);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 34px; -fx-font-weight: bold;");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.setStyle("-fx-font-size: 22px;");
        Label footerLabel = new Label(footer);
        footerLabel.getStyleClass().add("muted");
        footerLabel.setStyle("-fx-font-size: 16px;");
        VBox card = new VBox(18, logo, titleLabel, subtitleLabel, footerLabel);
        card.setAlignment(Pos.CENTER);
        return card;
    }

    private void connect() throws Exception {
        LoginView loginView = FxTestSupport.call(() -> {
            LoginView view = new LoginView(context, profile -> {
                mainView = new MainView(context);
                holder.getChildren().setAll(mainView);
                mainView.selectConnection(profile);
            });
            holder.getChildren().setAll(view);
            return view;
        });
        recorder.caption("The first start brings the Example Shop (H2) database, connect to it");
        @SuppressWarnings("unchecked")
        ListView<ConnectionProfile> list = (ListView<ConnectionProfile>) node(loginView, "profileList");
        pause(1200);
        moveTo(onFx(() -> list.lookup(".list-cell")));
        pause(800);
        press(node(loginView, "loginButton"));
        waitFor(() -> mainView != null && connection(ExampleDatabase.PROFILE_NAME) != null);
        pause(1500);
    }

    private ConnectionTree tree() {
        return (ConnectionTree) mainView.lookup("#connectionTree");
    }

    private TreeItem<DbNode> connection(String name) {
        return onFx(() -> tree().getRoot().getChildren().stream().filter(item -> name.equals(item
                .getValue().getLabel())).findFirst().orElse(null));
    }

    private TreeItem<DbNode> child(TreeItem<DbNode> parent, String label) {
        return waitFor(() -> parent.getChildren().stream().filter(item -> label.equalsIgnoreCase(item.getValue()
                .getLabel())).findFirst().orElse(null));
    }

    private TreeItem<DbNode> firstChild(TreeItem<DbNode> parent) {
        return waitFor(() -> parent.getChildren().isEmpty() ? null : parent.getChildren().get(0));
    }

    /**
     * Expands the tree item like a click on its cell, its children are loaded in the background
     */
    private TreeItem<DbNode> expand(TreeItem<DbNode> item) throws Exception {
        moveTo(cell(item));
        recorder.click();
        FxTestSupport.run(() -> {
            tree().getSelectionModel().select(item);
            item.setExpanded(true);
        });
        waitFor(() -> !item.getChildren().isEmpty() && item.getChildren().stream().noneMatch(child -> child
                .getValue().getKind() == DbNode.Kind.LOADING) ? item : null);
        pause(700);
        return item;
    }

    private void collapse(TreeItem<DbNode> item) throws Exception {
        moveTo(cell(item));
        recorder.click();
        FxTestSupport.run(() -> item.setExpanded(false));
        pause(600);
    }

    private Node cell(TreeItem<DbNode> item) {
        return waitFor(() -> {
            tree().scrollTo(tree().getRow(item));
            tree().layout();
            return tree().lookupAll(".tree-cell").stream().filter(node -> ((TreeCell<?>) node).getTreeItem() == item)
                    .findFirst().orElse(null);
        });
    }

    private void select(TreeItem<DbNode> item) throws Exception {
        moveTo(cell(item));
        recorder.click();
        FxTestSupport.run(() -> tree().getSelectionModel().select(item));
        pause(500);
    }

    private TreeItem<DbNode> examplePublic() throws Exception {
        TreeItem<DbNode> example = connection(ExampleDatabase.PROFILE_NAME);
        TreeItem<DbNode> catalog = firstChild(example.isExpanded() ? example : expand(example));
        TreeItem<DbNode> schema = catalog.isExpanded() ? child(catalog, "PUBLIC") : child(expand(catalog), "PUBLIC");
        return schema.isExpanded() ? schema : expand(schema);
    }

    private void query() throws Exception {
        recorder.caption("Browse the e-commerce tables of the example");
        TreeItem<DbNode> schema = examplePublic();
        pause(1000);
        recorder.caption("Open ORDERS and narrow its rows on the Data tab");
        TreeItem<DbNode> orders = child(schema, "ORDERS");
        moveTo(cell(orders));
        recorder.click();
        FxTestSupport.run(() -> mainView.openTable(orders.getValue()));
        pause(900);
        TabPane tabs = waitFor(() -> {
            TabPane tableTabs = (TabPane) mainView.lookup("#tableTabs");
            return tableTabs.getSelectionModel().getSelectedItem() == null ? null : (TabPane) tableTabs
                    .getSelectionModel().getSelectedItem().getContent();
        });
        Node dataTab = waitFor(() -> tabs.lookupAll(".tab-label").stream().filter(node -> "Data".equals(((Labeled)
                node).getText())).findFirst().orElse(null));
        moveTo(dataTab);
        recorder.click();
        FxTestSupport.run(() -> tabs.getSelectionModel().select(4));
        waitFor(() -> "300 rows".equals(((Label) mainView.lookup("#totalLabel")).getText()) ? true : null);
        pause(1200);
        type((TextField) node(mainView, "whereField"), "status = 'PAID'");
        press(node(mainView, "applyQueryButton"));
        waitFor(() -> "60 rows".equals(((Label) mainView.lookup("#totalLabel")).getText()) ? true : null);
        pause(1800);
    }

    /**
     * Opens the export dialog of the PUBLIC schema of the example with all of its tables
     */
    private void openExport() throws Exception {
        select(examplePublic());
        press(node(mainView, "action-export"));
        Node selectAll = waitFor(() -> topNode("selectAllButton"));
        fitDialog(topWindow());
        pause(900);
        press(selectAll);
        pause(800);
    }

    private void exportFiles() throws Exception {
        recorder.caption("Export to CSV files: all tables of the schema, written for PostgreSQL");
        openExport();
        scrollDialog(1);
        type((TextField) topNode("directoryField"), packageDir.getAbsolutePath());
        choose(topNode("scriptDbTypeCombo"), DbType.POSTGRESQL);
        RadioButton csv = (RadioButton) topNode("csvFormatRadio");
        press(csv, () -> csv.setSelected(true));
        pause(1000);
        press(topNode("startButton"));
        recorder.caption("The package holds the schema, CSV data files, constraints and a manifest");
        Window progress = waitForProgress();
        pause(1500);
        press(node(progress.getScene().getRoot(), "viewPackageButton"));
        Node viewer = waitFor(() -> topNode("packageViewer"));
        fitDialog(onFx(() -> viewer.getScene().getWindow()));
        recorder.caption("The package viewer shows every file of the package");
        pause(2400);
        FxTestSupport.run(() -> viewer.getScene().getWindow().hide());
        pause(500);
        closeProgress(progress);
    }

    private void importPackage() throws Exception {
        recorder.caption("Import the CSV package into the shop schema of PostgreSQL");
        select(connection(ExampleDatabase.PROFILE_NAME));
        press(node(mainView, "action-importScripts"));
        TextField directory = waitFor(() -> (TextField) topNode("directoryField"));
        fitDialog(topWindow());
        pause(800);
        type(directory, packageDir.getAbsolutePath());
        FxTestSupport.run(() -> directory.fireEvent(new ActionEvent()));
        ComboBox<?> target = waitFor(() -> {
            ComboBox<?> combo = (ComboBox<?>) topNode("targetCombo");
            return combo.getValue() != null && "PostgreSQL demo".equals(((ConnectionProfile) combo.getValue())
                    .getName()) ? combo : null;
        });
        moveTo(target);
        pause(900);
        ComboBox<?> catalog = waitFor(() -> {
            ComboBox<?> combo = (ComboBox<?>) topNode("catalogCombo");
            return "demo".equals(combo.getValue()) ? combo : null;
        });
        moveTo(catalog);
        pause(500);
        ComboBox<?> schema = waitFor(() -> {
            ComboBox<?> combo = (ComboBox<?>) topNode("schemaCombo");
            return combo.getItems().contains(SCHEMA) ? combo : null;
        });
        choose(schema, SCHEMA);
        pause(800);
        // One transaction rolls back on error, confirm before commit lets the user review the import first
        CheckBox confirm = (CheckBox) topNode("confirmCommitCheck");
        recorder.caption("One transaction: an error rolls back everything, confirm to review first");
        press(confirm, () -> confirm.setSelected(true));
        pause(1200);
        press(topNode("startButton"));
        Window progress = commitImport("Review the import, then commit or roll back", 2600);
        closeProgress(progress);
        recorder.caption("The tables are in PostgreSQL now");
        TreeItem<DbNode> postgresql = expand(connection("PostgreSQL demo"));
        TreeItem<DbNode> shop = expand(child(expand(child(postgresql, "demo")), SCHEMA));
        // The partitioned table of the schema, its partitions are below it
        assertEquals(TABLES.size() + 1, FxTestSupport.call(() -> shop.getChildren().size()).intValue());
        pause(1200);
        recorder.caption("Partitioned tables show their partitioning, their partitions are listed below them");
        TreeItem<DbNode> partitioned = child(shop, PARTITIONED);
        moveTo(cell(partitioned));
        recorder.click();
        FxTestSupport.run(() -> partitioned.setExpanded(true));
        pause(2600);
        collapse(postgresql);
    }

    /**
     * Revenue of the categories by a join in the SQL editor, its rows go into a new table of PostgreSQL
     */
    private void sqlQuery() throws Exception {
        recorder.caption("SQL Query: write any SELECT, keywords are highlighted");
        select(examplePublic());
        press(node(mainView, "action-sqlQuery"));
        CodeArea editor = waitFor(() -> (CodeArea) mainView.lookup("#sqlEditor"));
        pause(600);
        moveTo(editor);
        recorder.click();
        FxTestSupport.run(editor::requestFocus);
        String sql = "SELECT c.name AS category, SUM(i.quantity * i.unit_price) AS revenue\n"
                + "FROM order_items i\nJOIN products p ON p.id = i.product_id\n"
                + "JOIN categories c ON c.id = p.category_id\nGROUP BY c.name ORDER BY revenue DESC";
        for (char c : sql.toCharArray()) {
            FxTestSupport.run(() -> editor.appendText(String.valueOf(c)));
            Thread.sleep(16);
        }
        pause(500);
        press(node(mainView, "sqlRunButton"));
        TableView<?> result = (TableView<?>) node(mainView, "sqlResultTable");
        waitFor(() -> result.getItems().isEmpty() ? null : true);
        pause(1500);
        recorder.caption("Export the rows of the query as a new table of PostgreSQL");
        press(node(mainView, "sqlExportButton"));
        TextField table = waitFor(() -> (TextField) topNode("queryTableField"));
        fitDialog(topWindow());
        pause(500);
        type(table, QUERY_TABLE);
        RadioButton database = (RadioButton) topNode("databaseRadio");
        scrollDialog(1);
        press(database, () -> database.setSelected(true));
        pause(500);
        scrollDialog(1);
        ConnectionProfile postgresql = context.getProfileRegistry().getProfiles().stream().filter(profile ->
                "PostgreSQL demo".equals(profile.getName())).findFirst().orElseThrow();
        choose(topNode("targetCombo"), postgresql);
        ComboBox<?> schema = waitFor(() -> {
            ComboBox<?> combo = (ComboBox<?>) topNode("targetSchemaCombo");
            return combo.getItems().contains(SCHEMA) ? combo : null;
        });
        choose(schema, SCHEMA);
        pause(500);
        press(topNode("startButton"));
        Window progress = waitForProgress();
        pause(1000);
        closeProgress(progress);
    }

    private void exportSqlServer() throws Exception {
        recorder.caption("Export straight into SQL Server, create its data source with the + button");
        openExport();
        RadioButton database = (RadioButton) topNode("databaseRadio");
        scrollDialog(1);
        press(database, () -> database.setSelected(true));
        pause(700);
        scrollDialog(1);
        Window export = topWindow();
        press(topNode("newTargetButton"));
        Node form = waitFor(() -> topWindow() != export ? topNode("nameField") : null);
        fitDialog(topWindow());
        pause(700);
        type((TextField) form, "SQL Server demo");
        recorder.caption("Paste a JDBC URL: the type, host, port and database follow it");
        type((TextField) topNode("urlField"), SQLSERVER_URL);
        pause(1500);
        type((TextField) topNode("usernameField"), USERNAME);
        type((TextField) topNode("passwordField"), PASSWORD);
        press(buttonOf(topWindow(), "Test"));
        waitFor(() -> ((Labeled) topNode("formStatusLabel")).getText().startsWith("Connected") ? true : null);
        pause(1200);
        press(buttonOf(topWindow(), "Save"));
        waitFor(() -> topWindow() == export ? true : null);
        recorder.caption("The new data source is the target, a missing schema is created");
        pause(1000);
        ComboBox<?> schema = waitFor(() -> {
            ComboBox<?> combo = (ComboBox<?>) topNode("targetSchemaCombo");
            return combo.getItems().isEmpty() ? null : combo;
        });
        type(schema.getEditor(), SCHEMA);
        moveTo(topNode("createSchemaCheck"));
        assertTrue(FxTestSupport.call(() -> ((CheckBox) topNode("createSchemaCheck")).isSelected()));
        pause(1000);
        press(topNode("startButton"));
        // Committed when the import succeeds
        Window progress = waitForProgress();
        pause(1500);
        closeProgress(progress);
    }

    /**
     * Into Db2 with the write modes: existing rows are skipped by key, rows with errors are skipped and logged
     */
    private void exportDb2() throws Exception {
        recorder.caption("Straight into Db2: types are translated, keys, indexes and comments come along");
        openExport();
        RadioButton database = (RadioButton) topNode("databaseRadio");
        scrollDialog(1);
        press(database, () -> database.setSelected(true));
        pause(500);
        scrollDialog(1);
        ConnectionProfile db2 = context.getProfileRegistry().getProfiles().stream().filter(profile -> "Db2 demo"
                .equals(profile.getName())).findFirst().orElseThrow();
        choose(topNode("targetCombo"), db2);
        ComboBox<?> schema = waitFor(() -> {
            ComboBox<?> combo = (ComboBox<?>) topNode("targetSchemaCombo");
            return combo.getItems().isEmpty() ? null : combo;
        });
        type(schema.getEditor(), DB2_SCHEMA);
        recorder.caption("Rows are inserted, updated or skipped by key");
        RadioButton skipExisting = (RadioButton) topNode("skipExistingRadio");
        press(skipExisting, () -> skipExisting.setSelected(true));
        recorder.caption("Rows with errors can be skipped, each of them is logged");
        CheckBox skipErrors = (CheckBox) topNode("skipErrorsCheck");
        press(skipErrors, () -> skipErrors.setSelected(true));
        pause(800);
        press(topNode("startButton"));
        Window progress = waitForProgress();
        pause(1500);
        closeProgress(progress);
        TreeItem<DbNode> db2Item = expand(waitFor(() -> connection("Db2 demo")));
        expand(child(db2Item, DB2_SCHEMA));
        pause(1800);
        collapse(db2Item);
    }

    /**
     * Waits until the progress window is completed, its log is shown for a moment
     */
    private Window waitForProgress() throws InterruptedException {
        Window window = waitFor(() -> Window.getWindows().stream().filter(Window::isShowing).filter(item -> item
                .getScene() != null && item.getScene().getRoot().lookup("#progressStatus") != null).findFirst()
                .orElse(null));
        fitDialog(window);
        waitFor(() -> "Close".equals(((Labeled) window.getScene().getRoot().lookup("#cancelButton")).getText())
                ? true : null);
        String status = FxTestSupport.call(() -> ((Labeled) window.getScene().getRoot().lookup("#progressStatus"))
                .getText());
        assertEquals("Completed", status);
        return window;
    }

    /**
     * Waits until the import into a database is complete and asks to commit, then commits it
     *
     * @param caption subtitle while the question is shown, null to keep the subtitle
     */
    private Window commitImport(String caption, long think) throws InterruptedException {
        Window window = waitFor(() -> Window.getWindows().stream().filter(Window::isShowing).filter(item -> item
                .getScene() != null && item.getScene().getRoot().lookup("#progressStatus") != null).findFirst()
                .orElse(null));
        fitDialog(window);
        DialogPane question = waitFor(() -> (DialogPane) topNode("decisionPane"));
        fitDialog(onFx(() -> question.getScene().getWindow()));
        if (caption != null) {
            recorder.caption(caption);
        }
        pause(think);
        press(onFx(() -> question.getButtonTypes().stream().map(type -> (Button) question.lookupButton(type))
                .filter(button -> "Commit".equals(button.getText())).findFirst().orElseThrow()));
        waitFor(() -> "Close".equals(((Labeled) window.getScene().getRoot().lookup("#cancelButton")).getText())
                ? true : null);
        assertEquals("Committed", FxTestSupport.call(() -> ((Labeled) window.getScene().getRoot()
                .lookup("#progressStatus")).getText()));
        pause(1000);
        return window;
    }

    /**
     * Dialogs are placed by the window system, the video shows them inside the main window: a higher dialog is
     * made smaller and every dialog is centered
     */
    private void fitDialog(Window window) throws InterruptedException {
        FxTestSupport.run(() -> {
            Scene main = stage.getScene();
            double maxHeight = main.getHeight() - 30;
            if (window.getHeight() > maxHeight) {
                window.setHeight(maxHeight);
            }
            double decoration = window.getScene() == null ? 0 : window.getScene().getY();
            window.setX(stage.getX() + main.getX() + (main.getWidth() - window.getWidth()) / 2);
            window.setY(stage.getY() + main.getY() + (main.getHeight() - window.getHeight() + decoration) / 2
                    - decoration);
        });
        Thread.sleep(200);
    }

    private void closeProgress(Window window) throws Exception {
        press(node(window.getScene().getRoot(), "cancelButton"));
        waitFor(() -> window.isShowing() ? null : true);
        pause(600);
    }

    /**
     * Scrolls the scroll pane of the top dialog to the position with an animation
     */
    private void scrollDialog(double target) throws Exception {
        ScrollPane scrollPane = FxTestSupport.call(() -> (ScrollPane) topWindow().getScene().getRoot()
                .lookup(".scroll-pane"));
        if (scrollPane == null) {
            return;
        }
        double from = FxTestSupport.call(scrollPane::getVvalue);
        for (int i = 1; i <= 30; i++) {
            double value = from + (target - from) * i / 30;
            FxTestSupport.run(() -> scrollPane.setVvalue(value));
            Thread.sleep(25);
        }
        pause(400);
    }

    /**
     * Runs on the FX thread, directly when it is called there, e.g. by waitFor
     */
    private static <T> T onFx(Callable<T> callable) {
        if (Platform.isFxApplicationThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return FxTestSupport.call(callable);
    }

    private static Node node(Node root, String id) {
        return onFx(() -> root.lookup("#" + id));
    }

    /**
     * The topmost showing window which is not a popup
     */
    private Window topWindow() {
        return onFx(() -> {
            List<Window> windows = new ArrayList<>(Window.getWindows());
            for (int i = windows.size() - 1; i >= 0; i--) {
                Window window = windows.get(i);
                if (window.isShowing() && window instanceof Stage && window.getScene() != null) {
                    return window;
                }
            }
            return stage;
        });
    }

    /**
     * Node of the id in the topmost window which has it
     */
    private Node topNode(String id) {
        return onFx(() -> {
            List<Window> windows = new ArrayList<>(Window.getWindows());
            for (int i = windows.size() - 1; i >= 0; i--) {
                Window window = windows.get(i);
                if (window.isShowing() && window.getScene() != null) {
                    Node node = window.getScene().getRoot().lookup("#" + id);
                    if (node != null && node.isVisible()) {
                        return node;
                    }
                }
            }
            return null;
        });
    }

    private static Button buttonOf(Window window, String text) {
        return onFx(() -> window.getScene().getRoot().lookupAll(".button").stream().filter(node ->
                text.equals(((Button) node).getText())).map(node -> (Button) node).findFirst().orElseThrow());
    }

    private <T> T waitFor(Supplier<T> supplier) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            T value = FxTestSupport.call(supplier::get);
            if (value != null) {
                return value;
            }
            sleep(100);
        }
        throw new AssertionError("Not shown in 60 seconds");
    }

    private void moveTo(Node node) throws InterruptedException {
        double[] center = FxTestSupport.call(() -> {
            Bounds bounds = node.localToScreen(node.getBoundsInLocal());
            Scene main = stage.getScene();
            return new double[]{bounds.getCenterX() - stage.getX() - main.getX(), bounds.getCenterY() - stage.getY()
                    - main.getY()};
        });
        recorder.movePointer(center[0], center[1], 550);
        Thread.sleep(150);
    }

    /**
     * Moves to the button and fires it later, a modal dialog opened by it does not block the recording
     */
    private void press(Node node) throws InterruptedException {
        press(node, ((ButtonBase) node)::fire);
    }

    private void press(Node node, Runnable action) throws InterruptedException {
        moveTo(node);
        recorder.click();
        Platform.runLater(action);
        Thread.sleep(350);
    }

    private void type(TextField field, String text) throws InterruptedException {
        moveTo(field);
        recorder.click();
        FxTestSupport.run(() -> {
            field.requestFocus();
            field.clear();
        });
        for (char c : text.toCharArray()) {
            FxTestSupport.run(() -> {
                field.appendText(String.valueOf(c));
                field.positionCaret(field.getText().length());
            });
            Thread.sleep(text.length() > 30 ? 18 : 55);
        }
        Thread.sleep(300);
    }

    /**
     * Opens the drop down of the combo box, shows the choice and selects it
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void choose(Node node, Object value) throws InterruptedException {
        ComboBox combo = (ComboBox) node;
        moveTo(combo);
        recorder.click();
        FxTestSupport.run(combo::show);
        Thread.sleep(700);
        Node cell = FxTestSupport.call(() -> {
            ListView<?> list = (ListView<?>) ((ComboBoxListViewSkin<?>) combo.getSkin()).getPopupContent();
            list.scrollTo(list.getItems().indexOf(value));
            list.layout();
            return list.lookupAll(".list-cell").stream().filter(item -> Objects.equals(((ListCell<?>) item).getItem(),
                    value)).findFirst().orElse(null);
        });
        if (cell != null) {
            moveTo(cell);
            recorder.click();
        }
        FxTestSupport.run(() -> {
            combo.setValue(value);
            combo.hide();
        });
        Thread.sleep(500);
    }

    private static void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tables of the video must not exist yet, they are dropped after the recording
     */
    private static void checkTargets() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRESQL_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM pg_tables WHERE schemaname = '" + SCHEMA + "'"),
                    "Schema shop of PostgreSQL has tables");
            // The package import uses an existing schema, it has a partitioned table
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            statement.execute("CREATE TABLE " + SCHEMA + "." + PARTITIONED + " (id BIGINT NOT NULL, order_year INT"
                    + " NOT NULL, total NUMERIC(12,2), PRIMARY KEY (id, order_year)) PARTITION BY RANGE (order_year)");
            for (int year : new int[]{2024, 2025}) {
                statement.execute(String.format("CREATE TABLE %s.%s_%d PARTITION OF %s.%s FOR VALUES FROM (%d) TO (%d)",
                        SCHEMA, PARTITIONED, year, SCHEMA, PARTITIONED, year, year + 1));
            }
        }
        try (Connection connection = DriverManager.getConnection(SQLSERVER_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM sys.schemas WHERE name = '" + SCHEMA + "'"),
                    "Schema shop of SQL Server exists");
        }
        try (Connection connection = DriverManager.getConnection(DB2_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA = '" + DB2_SCHEMA
                    + "'"), "Schema SHOP of Db2 has tables");
        }
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void dropTables() {
        execute(POSTGRESQL_URL, List.of("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"));
        List<String> sqlServer = new ArrayList<>();
        for (String table : TABLES) {
            sqlServer.add("IF OBJECT_ID('" + SCHEMA + "." + table + "', 'U') IS NOT NULL ALTER TABLE " + SCHEMA + "."
                    + table + " NOCHECK CONSTRAINT ALL");
        }
        // Children before parents
        for (String table : List.of("order_items", "orders", "products", "customers", "categories")) {
            sqlServer.add("DROP TABLE IF EXISTS " + SCHEMA + "." + table);
        }
        sqlServer.add("IF SCHEMA_ID('" + SCHEMA + "') IS NOT NULL DROP SCHEMA " + SCHEMA);
        execute(SQLSERVER_URL, sqlServer);
        List<String> db2 = new ArrayList<>();
        for (String table : List.of("order_items", "orders", "products", "customers", "categories")) {
            db2.add("DROP TABLE IF EXISTS " + DB2_SCHEMA + "." + table.toUpperCase());
        }
        db2.add("BEGIN DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END; "
                + "EXECUTE IMMEDIATE 'DROP SCHEMA " + DB2_SCHEMA + " RESTRICT'; END");
        execute(DB2_URL, db2);
    }

    private static void execute(String url, List<String> sqls) {
        try (Connection connection = DriverManager.getConnection(url, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            System.err.println("Unable to drop the tables of the video in " + url + ": " + e.getMessage());
        }
    }
}
