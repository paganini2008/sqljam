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
import java.io.IOException;
import java.util.Arrays;
import java.util.List;


import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.vdb.VdbImportDialog;
import com.github.sqljam.face.vdb.VdbProfile;
import com.github.sqljam.face.vdb.VdbProfileStore;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.TableQuery;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.utils.SshSettings;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.it.VdbServer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: DocScreenshots renders screenshots of the user interface in the default theme for the documents.
 *               It is not a test of the build (the name does not end with Test), run it by
 *               mvn test -Dtest=DocScreenshots -Dscreenshots.dir=docs/assets
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class DocScreenshots {

    private static final String STYLESHEET = "/com/github/sqljam/face/app.css";
    /**
     * Database of the fixture tables of MySQL
     */
    private static final String CATALOG = ItDatabase.MYSQL.getSourceCatalog();

    @TempDir
    File dir;

    private Stage stage;
    private AppContext context;
    private ConnectionProfile mysql;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        FxTestSupport.run(() -> ThemeManager.apply(ThemeManager.DEFAULT_THEME));
        context = FxTestSupport.createContext(dir);
        ConnectionProfile h2 = UiDatabase.create(dir, "H2 demo");
        context.getProfileRegistry().saveProfile(h2, true);
        // The MySQL of the integration tests with its fixture tables (sjm_...), 192.168.0.63 by default
        ItDatabase.MYSQL.loadFixture();
        mysql = ItDatabase.MYSQL.getSourceProfile();
        mysql.setName("MySQL test");
        context.getProfileRegistry().saveProfile(mysql, true);
        saveProfile("MariaDB demo", DbType.MARIADB, "localhost", 3307, "demo", "fengy");
        saveProfile("PostgreSQL demo", DbType.POSTGRESQL, "localhost", 5432, "demo", "fengy");
        saveProfile("Oracle demo", DbType.ORACLE, "localhost", 1521, "demo", "fengy");
        saveProfile("ClickHouse app", DbType.CLICKHOUSE, "localhost", 18123, "app", "fengy");
        ConnectionProfile duck = new ConnectionProfile();
        duck.setName("DuckDB lake");
        duck.setDbType(DbType.DUCKDB);
        duck.setDatabase(new File(dir, "lake.duckdb").getAbsolutePath());
        context.getProfileRegistry().saveProfile(duck, true);
        stage.setScene(new Scene(new StackPane(), 400, 300));
        stage.show();
    }

    private ConnectionProfile saveProfile(String name, DbType dbType, String host, int port, String database,
                                          String username) throws IOException {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setDbType(dbType);
        profile.setHostname(host);
        profile.setPort(port);
        profile.setDatabase(database);
        profile.setUsername(username);
        profile.setPassword("secret");
        context.getProfileRegistry().saveProfile(profile, true);
        return profile;
    }

    private static File outputDir() {
        File output = new File(System.getProperty("screenshots.dir", "target/screenshots"));
        output.mkdirs();
        return output;
    }

    /**
     * Shows the root in a scene of the size with the stylesheet of the application and writes its snapshot
     */
    private void render(Parent root, double width, double height, String name) throws IOException {
        render(root, width, height, name, null);
    }

    /**
     * @param prepare runs after the root is shown, e.g. selecting a row
     */
    private void render(Parent root, double width, double height, String name, Runnable prepare)
            throws IOException {
        WritableImage image = FxTestSupport.call(() -> {
            Scene scene = new Scene(root, width, height);
            scene.getStylesheets().add(DocScreenshots.class.getResource(STYLESHEET).toExternalForm());
            stage.setScene(scene);
            stage.setWidth(width);
            stage.setHeight(height);
            root.applyCss();
            root.layout();
            if (prepare != null) {
                prepare.run();
            }
            return null;
        });
        // Asynchronous loading (tree, columns, rows) settles before the snapshot, no field shows the focus
        sleep(1500);
        FxTestSupport.run(root::requestFocus);
        image = FxTestSupport.call(() -> stage.getScene().snapshot(null));
        write(image, new File(outputDir(), name));
    }

    static void write(WritableImage image, File file) throws IOException {
        DocImages.write(image, file);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Splash screen while starting, with the logo and the slogan
     */
    @Test
    void splash() throws IOException {
        SplashView splash = FxTestSupport.call(SplashView::new);
        render(splash, SplashView.WIDTH, SplashView.HEIGHT, "splash.png");
    }

    /**
     * The data source dialog: its category and type, the fields of the type and its SSH tunnel
     */
    @Test
    void dataSource() throws IOException {
        ConnectionProfile profile = context.getProfileRegistry().getProfiles().stream().filter(candidate ->
                "PostgreSQL demo".equals(candidate.getName())).findFirst().orElseThrow();
        SshSettings ssh = new SshSettings();
        ssh.setEnabled(true);
        ssh.setHost("bastion.example.com");
        ssh.setUsername("deploy");
        ssh.setAuthentication(SshSettings.Authentication.KEY);
        ssh.setPrivateKeyFile("~/.ssh/id_ed25519");
        profile.setSsh(ssh);
        ConnectionDialog dialog = FxTestSupport.call(() -> new ConnectionDialog(stage, context, profile));
        snapshotDialog(dialog, 640, 760, "data-source.png");
    }


    /**
     * Node of a fixture table of the MySQL test database
     */
    private DbNode tableNode(String table) {
        return DbNode.table(mysql, new TableInfo(CATALOG, null, table, null, false, false));
    }

    /**
     * Main window with the MySQL connection expanded and a table opened
     */
    private MainView openTable(String table, double width, double height) {
        MainView mainView = FxTestSupport.call(() -> new MainView(context));
        FxTestSupport.run(() -> {
            Scene scene = new Scene(mainView, width, height);
            scene.getStylesheets().add(DocScreenshots.class.getResource(STYLESHEET).toExternalForm());
            stage.setScene(scene);
            stage.setWidth(width);
            stage.setHeight(height);
            // Children of split panes exist with their skins
            mainView.applyCss();
            mainView.layout();
        });
        ConnectionTree tree = FxTestSupport.call(() -> (ConnectionTree) mainView.lookup("#connectionTree"));
        TreeItem<DbNode> connection = FxTestSupport.call(() -> tree.getConnectionItems().stream()
                .filter(item -> "MySQL test".equals(item.getValue().getLabel())).findFirst().orElseThrow());
        expand(connection);
        TreeItem<DbNode> catalog = FxTestSupport.call(() -> connection.getChildren().stream()
                .filter(item -> CATALOG.equals(item.getValue().getLabel())).findFirst().orElseThrow());
        expand(catalog);
        FxTestSupport.run(() -> {
            // The fixture tables with the partitioned ones (sjm_pt_..., sjm_sales) are in view, the table selected
            TreeItem<DbNode> first = catalog.getChildren().stream()
                    .filter(item -> item.getValue().getLabel().startsWith("sjm_")).findFirst().orElse(catalog);
            TreeItem<DbNode> opened = catalog.getChildren().stream()
                    .filter(item -> table.equals(item.getValue().getLabel())).findFirst().orElse(catalog);
            tree.getSelectionModel().select(opened);
            tree.scrollTo(Math.max(0, tree.getRow(first) - 1));
            mainView.openTable(tableNode(table));
        });
        return mainView;
    }

    private static void expand(TreeItem<DbNode> item) {
        FxTestSupport.run(() -> item.setExpanded(true));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !item.getChildren().isEmpty() && item.getChildren()
                .stream().noneMatch(child -> child.getValue().getKind() == DbNode.Kind.LOADING)));
    }

    private void snapshot(String name) throws IOException {
        sleep(2000);
        WritableImage image = FxTestSupport.call(() -> stage.getScene().snapshot(null));
        write(image, new File(outputDir(), name));
    }

    /**
     * Content tab of the opened table: 0 columns, 4 data
     */
    private static void selectTableTab(MainView mainView, int index) {
        FxTestSupport.run(() -> {
            TabPane tables = (TabPane) mainView.lookup("#tableTabs");
            TabPane contents = (TabPane) tables.getSelectionModel().getSelectedItem().getContent();
            contents.getSelectionModel().select(index);
        });
    }

    @Test
    void mainWindowWithTable() throws IOException {
        MainView mainView = openTable("sjm_emp", 1280, 753);
        selectTableTab(mainView, 0);
        snapshot("main-dark.png");
        for (String[] theme : new String[][]{{"Primer Light", "main-light.png"}, {"Nord Dark", "main-nord.png"}}) {
            FxTestSupport.run(() -> ThemeManager.apply(theme[0]));
            snapshot(theme[1]);
        }
        FxTestSupport.run(() -> ThemeManager.apply(ThemeManager.DEFAULT_THEME));
    }

    @Test
    void tableDataWithQuery() throws IOException {
        MainView mainView = openTable("sjm_emp", 1280, 753);
        selectTableTab(mainView, 4);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !mainView.lookup("#columnsButton").isDisabled()));
        FxTestSupport.run(() -> {
            ((TextField) mainView.lookup("#whereField")).setText("salary > 1000 AND active = 1");
            ((TextField) mainView.lookup("#orderField")).setText("salary DESC");
            ((Button) mainView.lookup("#applyQueryButton")).fire();
        });
        snapshot("table-data.png");
    }

    /**
     * SQL editor of the MySQL database: a highlighted query with a join and its first rows
     */
    @Test
    void sqlEditor() throws IOException {
        MainView mainView = openTable("sjm_emp", 1280, 753);
        FxTestSupport.run(() -> {
            TabPane tables = (TabPane) mainView.lookup("#tableTabs");
            tables.getTabs().clear();
            mainView.openSqlTab(DbNode.catalog(mysql, CATALOG));
        });
        SqlTab tab = FxTestSupport.call(() -> (SqlTab) ((TabPane) mainView.lookup("#tableTabs")).getTabs().get(0));
        FxTestSupport.run(() -> tab.getEditor().setText("-- Employees with their departments\n"
                + "SELECT e.id, e.name, d.name AS dept, e.salary,\n"
                + "       CASE WHEN e.salary > 5000 THEN 'senior' ELSE 'junior' END AS level\n"
                + "FROM sjm_emp e\n"
                + "JOIN sjm_dept d ON d.id = e.dept_id\n"
                + "WHERE e.active = 1 AND e.name LIKE 'Emp%'\n"
                + "ORDER BY e.salary DESC;"));
        FxTestSupport.run(() -> tab.run(false));
        FxTestSupport.waitUntil(() -> !tab.isBusy() && tab.getLastQuery() != null);
        snapshot("sql-editor.png");
        FxTestSupport.run(tab::close);
    }

    /**
     * Dialogs are rendered by their own window
     */
    private void snapshotDialog(Dialog<?> dialog, double width, double height, String name)
            throws IOException {
        snapshotDialog(dialog, width, height, name, null);
    }

    /**
     * @param prepare runs after the dialog is shown, when the controls of scroll panes exist
     */
    private void snapshotDialog(Dialog<?> dialog, double width, double height, String name, Runnable prepare)
            throws IOException {
        FxTestSupport.run(() -> {
            dialog.getDialogPane().setPrefSize(width, height);
            dialog.show();
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            if (prepare != null) {
                prepare.run();
            }
        });
        sleep(2500);
        WritableImage image = FxTestSupport.call(() -> dialog.getDialogPane().getScene().snapshot(null));
        write(image, new File(outputDir(), name));
        FxTestSupport.run(dialog::close);
    }

    @Test
    void exportWizard() throws IOException {
        FxTestSupport.run(() -> stage.setScene(new Scene(new StackPane(), 400, 300)));
        ExportDialog dialog = FxTestSupport.call(() -> new ExportDialog(stage, context, tableNode("sjm_emp"),
                new TableQuery(List.of("id", "name", "email", "salary", "hired"), "salary > 1000", null,
                        "salary DESC")));
        snapshotDialog(dialog, 600, 753, "export-wizard.png", () -> {
            ((RadioButton) dialog.getDialogPane().lookup("#parquetFormatRadio")).setSelected(true);
            ((TextField) dialog.getDialogPane().lookup("#directoryField")).setText(new File(dir, "export")
                    .getAbsolutePath());
        });
    }

    /**
     * A Parquet package of the fixture tables of MySQL
     */
    private File exportParquetPackage() throws Exception {
        File packageDir = new File(dir, "mysql-parquet");
        TransferRequest request = new TransferRequest();
        request.setSource(mysql);
        request.setSourceCatalog(CATALOG);
        request.setTables(List.of("sjm_dept", "sjm_emp", "sjm_emp_tag", "sjm_sales"));
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(packageDir);
        request.setScriptDbType(DbType.POSTGRESQL);
        request.setDataFormat(DataFormat.PARQUET);
        context.getTransferService().transfer(request, ExportListener.NONE);
        return packageDir;
    }

    @Test
    void parquetPackage() throws Exception {
        File packageDir = exportParquetPackage();
        FxTestSupport.run(() -> stage.setScene(new Scene(new StackPane(), 400, 300)));
        context.getSettings().setLastImportDirectory(packageDir.getAbsolutePath());
        ImportPackageDialog importDialog = FxTestSupport.call(() -> new ImportPackageDialog(stage, context, null));
        snapshotDialog(importDialog, 600, 753, "import-package.png");

        PackageViewer viewer = FxTestSupport.call(() -> new PackageViewer(stage, context, packageDir));
        FxTestSupport.run(() -> {
            viewer.show();
            viewer.getStage().setWidth(1280);
            viewer.getStage().setHeight(753);
            viewer.selectFile(new File(packageDir, "data/sjm_emp.parquet"));
        });
        sleep(3000);
        WritableImage image = FxTestSupport.call(() -> viewer.getStage().getScene().snapshot(null));
        write(image, new File(outputDir(), "package-viewer.png"));
        FxTestSupport.run(() -> viewer.getStage().close());

        ImportParquetDialog parquetDialog = FxTestSupport.call(() -> new ImportParquetDialog(stage, context, null));
        FxTestSupport.run(() -> parquetDialog.addFiles(List.of(new File(packageDir, "data/sjm_emp.parquet"))));
        snapshotDialog(parquetDialog, 760, 753, "import-parquet.png");
    }

    /**
     * A Qdrant collection of the tree in its tab, its Info page and the wizard which imports it
     */
    @Test
    void vectorDatabase() throws Exception {
        VdbServer server = VdbServer.QDRANT;
        Assumptions.assumeTrue(server.isAvailable());
        server.createFixture();
        try {
            VdbProfile profile = new VdbProfile();
            profile.setName("Qdrant docs");
            profile.setType(VdbType.QDRANT);
            profile.setUrl(server.getSource().getUrl());
            profile.setApiKey(server.getSource().getApiKey());
            VdbProfileStore.getInstance().saveProfile(profile, true);
            MainView mainView = FxTestSupport.call(() -> new MainView(context));
            FxTestSupport.run(() -> {
                Scene scene = new Scene(mainView, 1280, 753);
                scene.getStylesheets().add(DocScreenshots.class.getResource(STYLESHEET).toExternalForm());
                stage.setScene(scene);
                stage.setWidth(1280);
                stage.setHeight(753);
                mainView.applyCss();
                mainView.layout();
            });
            ConnectionTree tree = FxTestSupport.call(() -> (ConnectionTree) mainView.lookup("#connectionTree"));
            TreeItem<DbNode> qdrant = FxTestSupport.call(() -> tree.getRoot().getChildren().stream().flatMap(group
                    -> group.getChildren().stream()).filter(item -> "Qdrant docs".equals(item.getValue().getLabel()))
                    .findFirst().orElseThrow());
            expand(qdrant);
            TreeItem<DbNode> collection = FxTestSupport.call(() -> qdrant.getChildren().stream().filter(item -> server
                    .getCollection().equals(item.getValue().getLabel())).findFirst().orElseThrow());
            FxTestSupport.run(() -> {
                tree.getSelectionModel().select(collection);
                mainView.openSourceItem(collection.getValue());
            });
            TabPane contents = FxTestSupport.call(() -> (TabPane) ((TabPane) mainView.lookup("#tableTabs"))
                    .getSelectionModel().getSelectedItem().getContent());
            FxTestSupport.run(() -> contents.getSelectionModel().select(1));
            FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> {
                TableView<?> data = (TableView<?>) contents.lookup("#vdbDataTable");
                return data != null && data.getItems().size() == VdbServer.RECORDS;
            }));
            snapshot("vdb-collection.png");

            FxTestSupport.run(() -> {
                tree.getSelectionModel().select(qdrant);
                mainView.openInfo(qdrant.getValue());
            });
            FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> {
                Label refreshed = (Label) mainView.lookup("#infoRefreshedLabel");
                return refreshed != null && !refreshed.getText().isEmpty();
            }));
            // The address of the server of the tests is not shown in the documents
            FxTestSupport.run(() -> {
                @SuppressWarnings("unchecked")
                TableView<List<String>> overview = (TableView<List<String>>) mainView.lookup("#infoOverviewTable");
                overview.getItems().replaceAll(row -> "URL".equals(row.get(0).trim()) ? Arrays.asList(row.get(0),
                        "http://qdrant-host:6333", row.get(2)) : row);
            });
            snapshot("database-info.png");

            VdbImportDialog dialog = FxTestSupport.call(() -> new VdbImportDialog(stage, context,
                    VdbProfileStore.getInstance(), profile, null, List.of(server.getCollection()), null));
            // The targets are probed in the background, a target with vectors is chosen when they are known
            FxTestSupport.run(() -> {
                dialog.getDialogPane().setPrefSize(900, 860);
                dialog.show();
            });
            @SuppressWarnings("unchecked")
            ComboBox<ConnectionProfile> targets = FxTestSupport.call(() -> (ComboBox<ConnectionProfile>) dialog
                    .getDialogPane().lookup("#vdbTargetCombo"));
            FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> targets.getItems().stream().anyMatch(target
                    -> target.getDbType() == DbType.DUCKDB)));
            FxTestSupport.run(() -> targets.getItems().stream().filter(target -> target.getDbType() == DbType.DUCKDB)
                    .findFirst().ifPresent(targets::setValue));
            FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> dialog.getDialogPane().lookup("#vdbStatus")
                    .getStyleClass().contains("status-success")));
            sleep(1500);
            write(FxTestSupport.call(() -> dialog.getDialogPane().getScene().snapshot(null)), new File(outputDir(),
                    "import-vdb.png"));
            FxTestSupport.run(dialog::close);
        } finally {
            server.dropFixture();
        }
    }

    private MainView mainWindow(double width, double height) {
        MainView mainView = FxTestSupport.call(() -> new MainView(context));
        FxTestSupport.run(() -> {
            Scene scene = new Scene(mainView, width, height);
            scene.getStylesheets().add(DocScreenshots.class.getResource(STYLESHEET).toExternalForm());
            stage.setScene(scene);
            stage.setWidth(width);
            stage.setHeight(height);
            mainView.applyCss();
            mainView.layout();
        });
        return mainView;
    }

    private ConnectionProfile profile(String name) {
        return context.getProfileRegistry().getProfiles().stream().filter(profile -> name.equals(profile.getName()))
                .findFirst().orElseThrow();
    }

    /**
     * The migration report of a copy of the MySQL fixture into H2, opened by View Report
     */
    @Test
    void migrationReport() throws Exception {
        MainView mainView = mainWindow(1280, 753);
        com.github.sqljam.face.model.TransferRequest request = new com.github.sqljam.face.model.TransferRequest();
        request.setSource(mysql);
        request.setSourceCatalog(CATALOG);
        request.setTables(List.of("sjm_dept", "sjm_emp", "sjm_emp_tag"));
        request.setTarget(com.github.sqljam.face.model.TransferRequest.Target.DATABASE);
        request.setTargetProfile(profile("H2 demo"));
        request.setTargetSchema("PUBLIC");
        request.setFailFast(false);
        ProgressDialog progress = FxTestSupport.call(() -> mainView.startTransfer(request, 3));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> progress.getStage().getScene().getRoot().lookup(
                "#viewReportButton").isVisible()));
        FxTestSupport.run(() -> {
            ((Button) progress.getStage().getScene().getRoot().lookup("#viewReportButton")).fire();
            progress.getStage().close();
        });
        snapshot("report.png");
        // The history of the imports lists the report with the earlier ones
        FxTestSupport.run(mainView::openHistory);
        snapshot("import-history.png");
    }

    /**
     * Columns of the employees mapped for the target: a new name, a type of the target and a column left out
     */
    @Test
    void columnMapping() throws Exception {
        DatabaseSession session = context.getSessionManager().getSession(mysql);
        ColumnMappingDialog.ColumnLoader loader = table -> session.getColumns(CATALOG, null, table).stream().map(
                column -> new ColumnMappingDialog.Column(column.getName(), column.getTypeName() + (column.getSize()
                        > 0 && column.getTypeName().toUpperCase(java.util.Locale.ENGLISH).matches(
                        "VARCHAR|CHAR|DECIMAL") ? "(" + column.getSize() + (column.getScale() > 0 ? "," + column
                        .getScale() : "") + ")" : ""), column.isPrimaryKey())).collect(java.util.stream.Collectors
                .toList());
        java.util.Map<String, java.util.Map<String, ColumnMappingDialog.Mapping>> chosen = java.util.Map.of(
                "sjm_emp", java.util.Map.of("name", new ColumnMappingDialog.Mapping("full_name", null, false),
                        "salary", new ColumnMappingDialog.Mapping(null, "DECIMAL(14,2)", false),
                        "hired", new ColumnMappingDialog.Mapping(null, null, true)));
        ColumnMappingDialog dialog = FxTestSupport.call(() -> new ColumnMappingDialog(stage, List.of("sjm_emp",
                "sjm_dept"), loader, chosen));
        // Wide enough for the target types
        snapshotDialog(dialog, 980, 600, "column-mapping.png", () -> {
            javafx.stage.Window window = dialog.getDialogPane().getScene().getWindow();
            window.setWidth(980);
            window.setHeight(620);
        });
    }

    /**
     * The feature map of the About dialog
     */
    @Test
    void featureMap() throws Exception {
        javafx.scene.control.Alert about = FxTestSupport.call(() -> Dialogs.createAbout(stage, null));
        FxTestSupport.run(() -> {
            about.show();
            ((TabPane) about.getDialogPane().lookup("#aboutTabs")).getSelectionModel().select(1);
        });
        sleep(2500);
        // The whole map, also the part which the dialog scrolls
        write(FxTestSupport.call(() -> {
            Node map = about.getDialogPane().lookup("#featureMap");
            javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
            parameters.setFill(javafx.scene.paint.Color.web("#0d1117"));
            return map.snapshot(parameters, null);
        }), new File(outputDir(), "feature-map.png"));
        FxTestSupport.run(about::close);
    }

    /**
     * Sync tasks of the MySQL fixture into H2, one run now, one run every night
     */
    @Test
    void syncTasks() throws Exception {
        MainView mainView = mainWindow(1280, 753);
        ConnectionProfile h2 = profile("H2 demo");
        com.github.sqljam.face.model.SyncTask hourly = new com.github.sqljam.face.model.SyncTask();
        hourly.setName("Employees to H2");
        hourly.setSourceId(mysql.getId());
        hourly.setSourceCatalog(CATALOG);
        hourly.setTargetId(h2.getId());
        hourly.setTargetSchema("PUBLIC");
        hourly.getTables().add(new com.github.sqljam.face.model.SyncTask.Table("sjm_dept", "id",
                com.github.sqljam.face.model.SyncTask.WatermarkType.INCREASING_KEY));
        hourly.getTables().add(new com.github.sqljam.face.model.SyncTask.Table("sjm_emp", "id",
                com.github.sqljam.face.model.SyncTask.WatermarkType.INCREASING_KEY));
        hourly.setIntervalMinutes(15);
        context.getSyncScheduler().getStore().saveTask(hourly);
        com.github.sqljam.face.model.SyncTask nightly = new com.github.sqljam.face.model.SyncTask();
        nightly.setName("Sales every night");
        nightly.setSourceId(mysql.getId());
        nightly.setSourceCatalog(CATALOG);
        nightly.setTargetId(h2.getId());
        nightly.setTargetSchema("PUBLIC");
        nightly.getTables().add(new com.github.sqljam.face.model.SyncTask.Table("sjm_sales", "id",
                com.github.sqljam.face.model.SyncTask.WatermarkType.INCREASING_KEY));
        nightly.setScheduleType(com.github.sqljam.face.model.SyncTask.ScheduleType.DAILY);
        nightly.setDailyTime("02:00");
        context.getSyncScheduler().getStore().saveTask(nightly);
        context.getSyncScheduler().runNow(hourly.getId()).get(120, java.util.concurrent.TimeUnit.SECONDS);
        FxTestSupport.run(mainView::openSync);
        snapshot("sync-tasks.png");
    }

    /**
     * The live dashboard of the MySQL of the tests, sampled every second for a while
     */
    @Test
    void dashboard() throws Exception {
        MainView mainView = mainWindow(1280, 753);
        ConnectionTree tree = FxTestSupport.call(() -> (ConnectionTree) mainView.lookup("#connectionTree"));
        TreeItem<DbNode> connection = FxTestSupport.call(() -> tree.getConnectionItems().stream()
                .filter(item -> "MySQL test".equals(item.getValue().getLabel())).findFirst().orElseThrow());
        FxTestSupport.run(() -> {
            tree.getSelectionModel().select(connection);
            mainView.openInfo(connection.getValue());
        });
        InfoTab info = FxTestSupport.call(() -> (InfoTab) ((TabPane) mainView.lookup("#tableTabs"))
                .getSelectionModel().getSelectedItem());
        FxTestSupport.run(() -> {
            ((ComboBox<Integer>) info.getDashboard().lookup("#dashboardIntervalCombo")).setValue(1);
            info.showDashboard();
        });
        // Curves of half a minute, with some work of the server meanwhile
        for (int i = 0; i < 30; i++) {
            try (java.sql.Connection jdbc = ItDatabase.MYSQL.getSourceConnection();
                 java.sql.Statement statement = jdbc.createStatement()) {
                statement.executeQuery("SELECT COUNT(*) FROM " + CATALOG + ".sjm_emp WHERE salary > " + i).close();
            }
            sleep(1000);
        }
        snapshot("dashboard.png");
        FxTestSupport.run(() -> info.getTabPane().getTabs().remove(info));
    }
}
