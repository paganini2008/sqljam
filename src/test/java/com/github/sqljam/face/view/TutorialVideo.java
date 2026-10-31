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
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.Banner;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.ExampleDatabase;
import com.github.sqljam.face.service.JdbcUrls;
import com.github.sqljam.face.vdb.VdbProfile;
import com.github.sqljam.face.vdb.VdbProfileStore;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.vdb.VdbCollectionMetaData;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.it.VdbServer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: TutorialVideo records the three tutorial videos of SqlJam, about 2 minutes each, for users, with
 *               generated music and English subtitles, starting with the name, the version (-Dvideo.version)
 *               and the subject: SQL databases (query the example, export into CSV files, import the package
 *               into PostgreSQL with a partitioned table in the tree, the SQL editor, a direct copy into SQL
 *               Server), vector databases (the filter of the tree, a Qdrant collection page by page, conditions,
 *               nearest neighbors and whole values, its import into PostgreSQL, the tenants of Weaviate, the info)
 *               and the scheduled sync (a task of the example into PostgreSQL, the first and the next run, their
 *               reports, the command line of a task and the history). They are recorded once per major version, they
 *               are not tests of the build (the name does not end with Test), run them by
 *               mvn test -Pui -Dtest=TutorialVideo -Dvideo.dir=docs/assets
 *               The windows are shown on the screen while recording, the target databases are those of the
 *               integration tests (-Dvideo.postgresql.url, -Dvideo.sqlserver.url, user and password by
 *               -Dvideo.username and -Dvideo.password), the vector databases those of it-vdb-test.properties
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
    /**
     * Partitioned table of PostgreSQL shown in the tree with its partitions
     */
    private static final String PARTITIONED = "order_archive";
    /**
     * Version shown by the title, the version of the build without -SNAPSHOT by default
     */
    private static final String VERSION = System.getProperty("video.version",
            Banner.getVersion().replace("-SNAPSHOT", ""));
    /**
     * Vector database of the video, its collection is imported into PostgreSQL
     */
    private static final VdbServer VECTOR_SERVER = VdbServer.QDRANT;
    private static final String VECTOR_SOURCE = "Qdrant docs";
    /**
     * Vector database with tenants, its collection of two tenants is created for the video
     */
    private static final VdbServer TENANT_SERVER = VdbServer.WEAVIATE;
    private static final String TENANT_SOURCE = "Weaviate tenants";
    private static final String TENANT_COLLECTION = "SjvtTenant";
    /**
     * Table of the imported collection in the schema shop of PostgreSQL
     */
    private static final String VECTOR_TABLE = VdbCollectionMetaData.getDefaultTableName(VECTOR_SERVER
            .getCollection());
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
    private boolean vectorsCreated;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        FxTestSupport.run(() -> ThemeManager.apply(ThemeManager.DEFAULT_THEME));
        context = FxTestSupport.createContext(dir);
        ConnectionProfile example = context.getExampleDatabase().create();
        context.getProfileRegistry().saveProfile(example, true);
        saveProfile("PostgreSQL demo", POSTGRESQL_URL);
        // The vector database of the integration tests, its collection is created for the video
        VdbProfile qdrant = new VdbProfile();
        qdrant.setName(VECTOR_SOURCE);
        qdrant.setType(VdbType.QDRANT);
        qdrant.setUrl(VECTOR_SERVER.getSource().getUrl());
        qdrant.setApiKey(VECTOR_SERVER.getSource().getApiKey());
        VdbProfileStore.getInstance().saveProfile(qdrant, true);
        VdbProfile weaviate = new VdbProfile();
        weaviate.setName(TENANT_SOURCE);
        weaviate.setType(VdbType.WEAVIATE);
        weaviate.setUrl(TENANT_SERVER.getSource().getUrl());
        weaviate.setApiKey(TENANT_SERVER.getSource().getApiKey());
        VdbProfileStore.getInstance().saveProfile(weaviate, true);
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
        context.closeSyncScheduler();
        context.getSessionManager().closeAll();
        FxTestSupport.closeAllDialogs();
        if (targetsChecked) {
            dropTables();
        }
        if (vectorsCreated) {
            VECTOR_SERVER.dropFixture();
            TENANT_SERVER.dropPartitionedFixture();
        }
        FileUtils.deleteQuietly(packageDir);
    }

    /**
     * Steps of a video between its title and its end
     */
    @FunctionalInterface
    private interface Steps {
        void run() throws Exception;
    }

    /**
     * Records one video of about 2 minutes: its title, its steps and the end, with music and subtitles
     */
    private void record(String name, String subject, Steps steps) throws Exception {
        File work = new File(System.getProperty("video.work", "target/video"));
        work.mkdirs();
        packageDir = new File(work, "shop-export");
        FileUtils.deleteQuietly(packageDir);
        File output = new File(System.getProperty("video.dir", "target/video"), "sqljam-" + name + ".mp4");
        output.getParentFile().mkdirs();
        recorder = FxTestSupport.call(() -> new VideoRecorder(stage, System.getProperty("ffmpeg", "ffmpeg"), 1.25,
                24, 100));
        recorder.start(new File(work, "raw-" + name + ".mp4"));
        title(subject);
        steps.run();
        outro();
        recorder.stop();
        File music = new File(work, "music-" + name + ".wav");
        TutorialMusic.write(music, recorder.getDurationSeconds() + 2);
        recorder.finish(output, music, System.getProperty("video.font", "Helvetica Neue"));
        assertTrue(output.length() > 0);
        assertTrue(recorder.getDurationSeconds() < 150, "The video is longer than 2.5 minutes: " + recorder
                .getDurationSeconds());
    }

    /**
     * SQL databases: query, export files, import the package, the SQL editor and a direct copy
     */
    @Test
    void recordSql() throws Exception {
        checkTargets();
        targetsChecked = true;
        record("sql", "Relational and OLAP databases", () -> {
            connect();
            query();
            exportFiles();
            importPackage();
            sqlQuery();
            exportSqlServer();
        });
    }

    /**
     * Vector databases: the tree, a collection, its import into PostgreSQL, tenants and the info
     */
    @Test
    void recordVectors() throws Exception {
        checkVectorTarget();
        targetsChecked = true;
        VECTOR_SERVER.createFixture();
        TENANT_SERVER.createPartitionedFixture();
        vectorsCreated = true;
        record("vectors", "Vector databases", () -> {
            connect();
            vectorDatabase();
        });
    }

    /**
     * Scheduled sync of the example into PostgreSQL, its reports and the history
     */
    @Test
    void recordSync() throws Exception {
        checkSyncTarget();
        targetsChecked = true;
        record("sync", "Scheduled sync and migration reports", () -> {
            connect();
            sync();
        });
    }

    /**
     * Opens the File menu a moment and runs its item of the id
     */
    private void fileMenu(String id) throws Exception {
        MenuBar bar = (MenuBar) onFx(() -> mainView.lookup(".menu-bar"));
        javafx.scene.control.MenuItem item = FxTestSupport.menuItem(mainView, id);
        javafx.scene.control.Menu menu = item.getParentMenu();
        int index = onFx(() -> bar.getMenus().indexOf(menu));
        // The button of the menu in the menu bar
        Node button = waitFor(() -> {
            bar.applyCss();
            bar.layout();
            List<Node> buttons = new ArrayList<>(bar.lookupAll(".menu"));
            return buttons.size() > index ? buttons.get(index) : null;
        });
        moveTo(button);
        recorder.click();
        FxTestSupport.run(menu::show);
        pause(1300);
        FxTestSupport.run(() -> {
            menu.hide();
            item.fire();
        });
        pause(900);
    }

    private SyncTask syncTask() {
        return context.getSyncScheduler().getStore().getTasks().get(0);
    }

    /**
     * Runs the task of the list now and waits for its end
     */
    private void runSync(SyncTab tab) throws Exception {
        FxTestSupport.run(() -> tab.getTable().getSelectionModel().select(0));
        long last = syncTask().getLastRunAt();
        press(node(tab.getContent(), "syncRunButton"));
        waitFor(() -> syncTask().getLastRunAt() != last && !context.getSyncScheduler().isRunning(syncTask().getId())
                ? true : null);
        FxTestSupport.run(tab::refresh);
        pause(1600);
    }

    /**
     * Changes of the shop meanwhile: orders are shipped, new orders and a new customer arrive
     */
    private void changeShop() throws SQLException {
        ConnectionProfile example = onFx(() -> connection(ExampleDatabase.PROFILE_NAME).getValue().getProfile());
        try (Connection connection = DriverManager.getConnection(example.getJdbcUrl(), example.getUsername(),
                example.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE orders SET status = 'SHIPPED', updated_at = CURRENT_TIMESTAMP WHERE id IN "
                    + "(1, 2, 3)");
            statement.execute("INSERT INTO customers (name, email, city) VALUES ('Nina Video', "
                    + "'nina.video@example.com', 'Lisbon')");
            statement.execute("INSERT INTO orders (order_no, customer_id, status, total, created_at, updated_at) "
                    + "VALUES ('SO-2026-90001', 1, 'NEW', 59.90, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                    + "('SO-2026-90002', 2, 'NEW', 120.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    /**
     * A sync task of the example into PostgreSQL: the first run copies all rows, the next one the changed rows,
     * every run leaves a report, its command line runs it without the window, the history lists the runs
     */
    @SuppressWarnings("unchecked")
    private void sync() throws Exception {
        recorder.caption("Keep a copy up to date: Transfer > Sync Tasks copies the rows which changed, on a schedule");
        fileMenu("syncMenuItem");
        SyncTab tab = onFx(() -> (SyncTab) ((TabPane) mainView.lookup("#tableTabs")).getSelectionModel()
                .getSelectedItem());
        pause(1200);
        press(node(tab.getContent(), "syncAddButton"));
        TextField name = waitFor(() -> (TextField) topNode("syncNameField"));
        fitDialog(topWindow());
        recorder.caption("The source: the tables of the shop");
        type(name, "Shop to PostgreSQL");
        ComboBox<ConnectionProfile> sources = (ComboBox<ConnectionProfile>) topNode("syncSourceCombo");
        choose(sources, onFx(() -> sources.getItems().stream().filter(profile -> ExampleDatabase.PROFILE_NAME
                .equals(profile.getName())).findFirst().orElseThrow()));
        TableView<SyncTaskDialog.TableRow> tables = waitFor(() -> {
            TableView<SyncTaskDialog.TableRow> view = (TableView<SyncTaskDialog.TableRow>) topNode("syncTables");
            return view != null && view.getItems().stream().anyMatch(row -> row.name.equalsIgnoreCase("orders"))
                    ? view : null;
        });
        pause(800);
        recorder.caption("Check a table: SqlJam picks its watermark, the time of the last update when it has one");
        for (String table : new String[]{"ORDERS", "CUSTOMERS"}) {
            SyncTaskDialog.TableRow row = onFx(() -> tables.getItems().stream().filter(item -> item.name
                    .equalsIgnoreCase(table)).findFirst().orElseThrow());
            Node cell = onFx(() -> {
                tables.scrollTo(row);
                tables.layout();
                return tables.lookupAll(".table-row-cell").stream().filter(item -> ((javafx.scene.control.TableRow<?>)
                        item).getItem() == row).findFirst().map(item -> item.lookup(".check-box")).orElse(tables);
            });
            press(cell, () -> row.selected.set(true));
            waitFor(() -> row.watermarkColumn.get() != null ? true : null);
            moveTo(topNode("syncWatermarkCombo"));
            pause(1500);
        }
        recorder.caption("Orders by their update time, customers by their key which only grows");
        moveTo(topNode("syncWatermarkTypeCombo"));
        pause(1600);
        recorder.caption("The target: PostgreSQL. Missing tables are created with their keys, rows are updated or "
                + "inserted by key");
        ComboBox<ConnectionProfile> targets = (ComboBox<ConnectionProfile>) topNode("syncTargetCombo");
        choose(targets, onFx(() -> targets.getItems().stream().filter(profile -> "PostgreSQL demo".equals(profile
                .getName())).findFirst().orElseThrow()));
        ComboBox<String> schemas = waitFor(() -> {
            ComboBox<String> combo = (ComboBox<String>) topNode("syncTargetSchemaCombo");
            return combo != null && !combo.getItems().isEmpty() ? combo : null;
        });
        moveTo(schemas);
        recorder.click();
        FxTestSupport.run(() -> schemas.setValue(SCHEMA));
        pause(900);
        recorder.caption("Every 15 minutes, while SqlJam is open");
        choose(topNode("syncScheduleCombo"), SyncTask.ScheduleType.INTERVAL);
        moveTo(topNode("syncIntervalSpinner"));
        FxTestSupport.run(() -> ((javafx.scene.control.Spinner<Integer>) topNode("syncIntervalSpinner"))
                .getValueFactory().setValue(15));
        pause(1200);
        press(buttonOf(topWindow(), "OK"));
        waitFor(() -> tab.getTasks().isEmpty() ? null : true);
        pause(800);

        recorder.caption("Run Now: the first run copies all rows, its watermark is read before them");
        runSync(tab);
        recorder.caption("Each run leaves a report: tables, rows, the rows found in the target and the watermarks");
        press(node(tab.getContent(), "syncReportButton"));
        pause(4200);

        FxTestSupport.run(() -> ((TabPane) mainView.lookup("#tableTabs")).getSelectionModel().select(tab));
        changeShop();
        recorder.caption("Meanwhile the shop changes: three orders are shipped, two orders and a customer are new");
        pause(3500);
        recorder.caption("The next run reads only the rows after the watermarks, deleted rows stay in the target");
        runSync(tab);
        press(node(tab.getContent(), "syncReportButton"));
        pause(1500);
        recorder.caption("Its report: the changed rows of each table, and how far the watermarks moved");
        pause(4000);

        FxTestSupport.run(() -> ((TabPane) mainView.lookup("#tableTabs")).getSelectionModel().select(tab));
        recorder.caption("Errors of the connection are run again, errors of the data are not, the watermark moves "
                + "after the commit only");
        pause(3500);
        recorder.caption("Copy Command gives the command line of the task, for a cron of your own when SqlJam is "
                + "closed");
        FxTestSupport.run(() -> tab.getTable().getSelectionModel().select(0));
        press(node(tab.getContent(), "syncCommandButton"));
        pause(3000);

        recorder.caption("Transfer > Import History keeps the report of every run, next to the imports and exports");
        fileMenu("historyMenuItem");
        pause(3800);
    }

    /**
     * The name, the version and what SqlJam is for, before the tutorial starts
     */
    private void title(String subject) throws Exception {
        FxTestSupport.run(() -> {
            ImageView logo = Branding.logoView(190);
            Label name = new Label("SqlJam");
            name.setStyle("-fx-font-size: 46px; -fx-font-weight: bold;");
            Label version = new Label("v" + VERSION);
            version.getStyleClass().add("muted");
            version.setStyle("-fx-font-size: 18px;");
            Label tagline = new Label("Import and export tool for relational, OLAP and vector databases");
            tagline.getStyleClass().add("muted");
            tagline.setStyle("-fx-font-size: 18px;");
            Label topic = new Label(subject);
            topic.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");
            VBox card = new VBox(12, logo, name, version, tagline, topic);
            card.setAlignment(Pos.CENTER);
            holder.getChildren().setAll(card);
        });
        // The pointer starts at the centre, it would cover the logo
        recorder.movePointer(WIDTH * 0.82, HEIGHT * 0.78, 1);
        pause(4500);
    }

    private void outro() throws Exception {
        FxTestSupport.run(() -> holder.getChildren().setAll(card("Schema, data and all.",
                "Move tables across databases", Branding.REPOSITORY_URL)));
        recorder.caption("More on GitHub: paganini2008/sqljam");
        pause(3500);
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

    /**
     * SqlJam opens its main window with the data sources in the tree, the example is connected by expanding it
     */
    private void connect() throws Exception {
        mainView = FxTestSupport.call(() -> {
            MainView view = new MainView(context);
            // The video shows the window only: its menus are in it, not in the menu bar of macOS
            ((MenuBar) view.lookup(".menu-bar")).setUseSystemMenuBar(false);
            holder.getChildren().setAll(view);
            view.applyCss();
            view.layout();
            return view;
        });
        recorder.caption("SqlJam opens with its data sources, the first start brings the Example Shop (H2) database");
        waitFor(() -> connection(ExampleDatabase.PROFILE_NAME));
        pause(1800);
    }

    private ConnectionTree tree() {
        return (ConnectionTree) mainView.lookup("#connectionTree");
    }

    private TreeItem<DbNode> connection(String name) {
        return onFx(() -> {
            // The tree is found once the window was laid out
            ConnectionTree tree = tree();
            return tree == null ? null : tree.getConnectionItems().stream().filter(item -> name.equals(item
                    .getValue().getLabel())).findFirst().orElse(null);
        });
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
        pause(800);
        type((TextField) node(mainView, "whereField"), "status = 'PAID'");
        press(node(mainView, "applyQueryButton"));
        waitFor(() -> "60 rows".equals(((Label) mainView.lookup("#totalLabel")).getText()) ? true : null);
        pause(1100);
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
        pause(900);
        press(node(progress.getScene().getRoot(), "viewPackageButton"));
        Node viewer = waitFor(() -> topNode("packageViewer"));
        fitDialog(onFx(() -> viewer.getScene().getWindow()));
        recorder.caption("The package viewer shows every file of the package");
        pause(1400);
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
        Window progress = commitImport("Review the import, then commit or roll back", 1800);
        closeProgress(progress);
        recorder.caption("The tables are in PostgreSQL now");
        TreeItem<DbNode> postgresql = expand(connection("PostgreSQL demo"));
        TreeItem<DbNode> shop = expand(child(expand(child(postgresql, "demo")), SCHEMA));
        // The partitioned table of the schema, its partitions are below it
        assertEquals(TABLES.size() + 1, FxTestSupport.call(() -> shop.getChildren().size()).intValue());
        pause(700);
        recorder.caption("Partitioned tables show their partitioning, their partitions are listed below them");
        TreeItem<DbNode> partitioned = child(shop, PARTITIONED);
        moveTo(cell(partitioned));
        recorder.click();
        FxTestSupport.run(() -> partitioned.setExpanded(true));
        pause(1500);
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
        pause(1200);
        recorder.caption("Export… sends all rows of the query to files or another database, like a table");
        moveTo(node(mainView, "sqlExportButton"));
        pause(1600);
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
        pause(1000);
        type((TextField) topNode("usernameField"), USERNAME);
        type((TextField) topNode("passwordField"), PASSWORD);
        press(buttonOf(topWindow(), "Test"));
        waitFor(() -> ((Labeled) topNode("formStatusLabel")).getText().startsWith("Connected") ? true : null);
        pause(800);
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
        pause(700);
        recorder.caption("Rows are inserted, updated or skipped by key, rows with errors can be skipped and logged");
        RadioButton skipExisting = (RadioButton) topNode("skipExistingRadio");
        press(skipExisting, () -> skipExisting.setSelected(true));
        CheckBox skipErrors = (CheckBox) topNode("skipErrorsCheck");
        press(skipErrors, () -> skipErrors.setSelected(true));
        pause(900);
        press(topNode("startButton"));
        // Committed when the import succeeds
        Window progress = waitForProgress();
        pause(1500);
        closeProgress(progress);
    }

    /**
     * The vector databases: a Qdrant collection is browsed, searched and imported into PostgreSQL, the tenants of
     * Weaviate are listed, and the Info pages show the servers
     */
    private void vectorDatabase() throws Exception {
        FxTestSupport.run(() -> ((TabPane) mainView.lookup("#tableTabs")).getTabs().clear());
        recorder.caption("Vector databases are the third kind of data sources, next to relational and OLAP ones");
        TreeItem<DbNode> vectorGroup = waitFor(() -> tree().getRoot().getChildren().stream().filter(group -> group
                .getValue().getExtension() != null).findFirst().orElse(null));
        moveTo(cell(vectorGroup));
        pause(2200);
        recorder.caption("The filter above the tree finds data sources by name or type");
        TextField filter = (TextField) node(mainView, "treeFilterField");
        type(filter, "qdrant");
        pause(1200);
        TreeItem<DbNode> qdrant = source(VECTOR_SOURCE);
        expand(qdrant);
        TreeItem<DbNode> collection = child(qdrant, VECTOR_SERVER.getCollection());
        recorder.caption("Open a collection like a table: its fields with their types, vectors with their dimensions");
        TabPane contents = openSourceItem(collection);
        pause(2600);

        recorder.caption("Its records, page by page, read by the API of the database");
        selectTab(contents, "Data", 1);
        TableView<List<String>> data = dataTable(contents, VdbServer.RECORDS);
        pause(2200);
        recorder.caption("Conditions narrow the records, like a WHERE");
        type((TextField) node(contents, "vdbFilterField"), "n >= 6");
        press(node(contents, "vdbApplyButton"));
        waitFor(() -> data.getItems().size() == 3 ? true : null);
        pause(1800);
        recorder.caption("Or the nearest neighbors of a record or a vector");
        FxTestSupport.run(() -> ((TextField) node(contents, "vdbFilterField")).clear());
        type((TextField) node(contents, "vdbNearestField"), "2");
        press(node(contents, "vdbApplyButton"));
        waitFor(() -> !data.getItems().isEmpty() && data.getItems().size() < VdbServer.RECORDS ? true : null);
        pause(2000);
        press(node(contents, "vdbClearButton"));
        waitFor(() -> data.getItems().size() == VdbServer.RECORDS ? true : null);
        pause(800);

        recorder.caption("Cells show one line, a double click shows the whole value");
        Node vectorCell = waitFor(() -> data.lookupAll(".value-cell").stream().filter(node -> {
            TableCell<?, ?> cell = (TableCell<?, ?>) node;
            return !cell.isEmpty() && cell.getTableColumn() != null && "embedding".equals(cell.getTableColumn()
                    .getText());
        }).findFirst().orElse(null));
        doubleClick(vectorCell);
        Node valueArea = waitFor(() -> topNode("valueArea"));
        Window valueWindow = onFx(() -> valueArea.getScene().getWindow());
        fitDialog(valueWindow);
        pause(2600);
        FxTestSupport.run(valueWindow::hide);
        pause(400);
        recorder.caption("Select rows and copy them as CSV, TSV, JSON or Markdown");
        Node titleCell = waitFor(() -> data.lookupAll(".value-cell").stream().filter(node -> {
            TableCell<?, ?> cell = (TableCell<?, ?>) node;
            return !cell.isEmpty() && cell.getIndex() == 2 && cell.getTableColumn() != null && "title".equals(cell
                    .getTableColumn().getText());
        }).findFirst().orElse(null));
        moveTo(titleCell);
        recorder.click();
        FxTestSupport.run(() -> data.getSelectionModel().selectRange(0, 3));
        pause(600);
        ContextMenu menu = onFx(() -> ((TableCell<?, ?>) titleCell).getContextMenu());
        FxTestSupport.run(() -> {
            Bounds bounds = titleCell.localToScreen(titleCell.getBoundsInLocal());
            menu.show(titleCell, bounds.getMinX() + 20, bounds.getMaxY());
        });
        pause(900);
        Menu copyRows = onFx(() -> (Menu) menu.getItems().stream().filter(item -> item instanceof Menu)
                .findFirst().orElseThrow());
        FxTestSupport.run(copyRows::show);
        pause(1600);
        FxTestSupport.run(() -> copyRows.getItems().get(3).fire());
        FxTestSupport.run(menu::hide);
        pause(600);

        recorder.caption("Import the collection into PostgreSQL: only databases with vectors are targets");
        Window main = topWindow();
        press(node(contents, "vdbImportButton"));
        ComboBox<?> target = waitFor(() -> topWindow() != main ? (ComboBox<?>) topNode("vdbTargetCombo") : null);
        fitDialog(topWindow());
        pause(1400);
        recorder.caption("Ids and vectors become columns, each field a column, the whole payload is kept as JSON");
        pause(2400);
        scrollDialog(1);
        ConnectionProfile postgresql = waitFor(() -> target.getItems().stream().map(item -> (ConnectionProfile)
                item).filter(profile -> "PostgreSQL demo".equals(profile.getName())).findFirst().orElse(null));
        choose(target, postgresql);
        ComboBox<?> schema = waitFor(() -> {
            ComboBox<?> combo = (ComboBox<?>) topNode("vdbSchemaCombo");
            return combo != null && combo.getItems().contains(SCHEMA) ? combo : null;
        });
        choose(schema, SCHEMA);
        Node start = waitFor(() -> {
            Node button = topNode("vdbStartButton");
            return button != null && !button.isDisabled() ? button : null;
        });
        recorder.caption("Show SQL: the table, a vector(1024) column of pgvector and its HNSW index");
        Window wizard = topWindow();
        press(topNode("vdbShowSqlButton"));
        Window sql = waitFor(() -> topWindow() != wizard ? topWindow() : null);
        fitDialog(sql);
        pause(2700);
        press(buttonOf(sql, "OK"));
        waitFor(() -> topWindow() == wizard ? true : null);
        pause(500);
        press(start);
        // Existing tables of the collections are dropped and created again after a confirmation
        Window confirmation = waitFor(() -> topWindow() != wizard ? topWindow() : null);
        pause(900);
        press(buttonOf(confirmation, "OK"));
        Window progress = waitForProgress();
        pause(1500);
        closeProgress(progress);

        recorder.caption("The collection is a table of PostgreSQL now, its vectors are searchable by SQL");
        FxTestSupport.run(filter::clear);
        pause(500);
        TreeItem<DbNode> postgresqlItem = expand(waitFor(() -> connection("PostgreSQL demo")));
        TreeItem<DbNode> shop = expand(child(expand(child(postgresqlItem, "demo")), SCHEMA));
        // The schema was read before the import, Refresh reads its tables again
        select(shop);
        press(node(mainView, "action-refresh"));
        TreeItem<DbNode> table = child(shop, VECTOR_TABLE);
        moveTo(cell(table));
        recorder.click();
        FxTestSupport.run(() -> mainView.openTable(table.getValue()));
        TabPane tableContents = waitFor(() -> {
            TabPane tableTabs = (TabPane) mainView.lookup("#tableTabs");
            Tab tab = tableTabs.getSelectionModel().getSelectedItem();
            return tab instanceof TableTab ? (TabPane) tab.getContent() : null;
        });
        pause(2400);
        selectTab(tableContents, "Data", 4);
        waitFor(() -> ((Label) mainView.lookup("#totalLabel")).getText().startsWith(VdbServer.RECORDS + " ")
                ? true : null);
        pause(2400);
        collapse(postgresqlItem);

        recorder.caption("Each vector database differs: Weaviate keeps tenants, Milvus partitions, Pinecone namespaces");
        TreeItem<DbNode> weaviate = expand(source(TENANT_SOURCE));
        TreeItem<DbNode> tenants = expand(child(weaviate, TENANT_COLLECTION));
        pause(1500);
        recorder.caption("Open one tenant, its records are read and imported apart");
        TabPane tenant = openSourceItem(child(tenants, "t2"));
        selectTab(tenant, "Data", 1);
        dataTable(tenant, 2);
        pause(2100);

        recorder.caption("Info shows the server and its health, refreshed when you ask");
        select(qdrant);
        press(node(mainView, "action-info"));
        waitForInfo();
        pause(2300);
        select(postgresqlItem);
        press(node(mainView, "action-info"));
        waitForInfo();
        pause(2300);
    }

    private TreeItem<DbNode> source(String name) {
        return waitFor(() -> tree().getRoot().getChildren().stream().flatMap(group -> group.getChildren().stream())
                .filter(item -> name.equals(item.getValue().getLabel())).findFirst().orElse(null));
    }

    /**
     * Opens the tab of a collection or a partition, like a double click on its cell
     */
    private TabPane openSourceItem(TreeItem<DbNode> item) throws Exception {
        moveTo(cell(item));
        recorder.click();
        FxTestSupport.run(() -> mainView.openSourceItem(item.getValue()));
        return waitFor(() -> {
            TabPane tableTabs = (TabPane) mainView.lookup("#tableTabs");
            Tab tab = tableTabs.getSelectionModel().getSelectedItem();
            return tab != null && !(tab instanceof TableTab) && tab.getContent() instanceof TabPane ? (TabPane) tab
                    .getContent() : null;
        });
    }

    private void selectTab(TabPane tabs, String text, int index) throws Exception {
        Node label = waitFor(() -> tabs.lookupAll(".tab-label").stream().filter(node -> text.equals(((Labeled) node)
                .getText())).findFirst().orElse(null));
        moveTo(label);
        recorder.click();
        FxTestSupport.run(() -> tabs.getSelectionModel().select(index));
    }

    @SuppressWarnings("unchecked")
    private TableView<List<String>> dataTable(TabPane contents, int rows) {
        return waitFor(() -> {
            TableView<List<String>> table = (TableView<List<String>>) contents.lookup("#vdbDataTable");
            return table != null && table.getItems().size() == rows ? table : null;
        });
    }

    private void doubleClick(Node node) throws Exception {
        moveTo(node);
        recorder.click();
        Platform.runLater(() -> Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 2, false, false, false, false, true, false, false, true, false, false, null)));
    }

    private void waitForInfo() {
        waitFor(() -> {
            TabPane tableTabs = (TabPane) mainView.lookup("#tableTabs");
            Tab tab = tableTabs.getSelectionModel().getSelectedItem();
            Label refreshed = tab instanceof InfoTab ? (Label) tab.getContent().lookup("#infoRefreshedLabel") : null;
            return refreshed != null && !refreshed.getText().isEmpty() ? tab : null;
        });
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
    }

    /**
     * The vector video imports into the schema of the SQL video, it is created for it when it is missing
     */
    private static void checkVectorTarget() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRESQL_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM pg_tables WHERE schemaname = '" + SCHEMA + "'"),
                    "Schema shop of PostgreSQL has tables");
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        }
    }

    /**
     * The sync video creates the schema and its tables by its first run
     */
    private static void checkSyncTarget() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRESQL_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM pg_tables WHERE schemaname = '" + SCHEMA + "'"),
                    "Schema shop of PostgreSQL has tables");
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
