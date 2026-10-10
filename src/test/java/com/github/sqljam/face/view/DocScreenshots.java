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

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.TableQuery;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.image.PixelReader;
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
        mysql = saveProfile("MySQL test", DbType.MYSQL, "localhost", 3306, "test", "fengy");
        mysql.setPassword("12345678");
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
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", file);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void login() throws IOException {
        LoginView loginView = FxTestSupport.call(() -> new LoginView(context, profile -> {
        }));
        render(loginView, 860, 620, "login.png", () -> {
            @SuppressWarnings("unchecked")
            ListView<ConnectionProfile> list = (ListView<ConnectionProfile>) loginView.lookup("#profileList");
            list.getItems().stream().filter(profile -> "MySQL test".equals(profile.getName())).findFirst()
                    .ifPresent(list.getSelectionModel()::select);
        });
    }


    /**
     * Node of a fixture table of the MySQL test database
     */
    private DbNode tableNode(String table) {
        return DbNode.table(mysql, new TableInfo("test", null, table, null, false, false));
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
        TreeItem<DbNode> connection = FxTestSupport.call(() -> tree.getRoot().getChildren().stream()
                .filter(item -> "MySQL test".equals(item.getValue().getLabel())).findFirst().orElseThrow());
        expand(connection);
        TreeItem<DbNode> catalog = FxTestSupport.call(() -> connection.getChildren().stream()
                .filter(item -> "test".equals(item.getValue().getLabel())).findFirst().orElseThrow());
        expand(catalog);
        FxTestSupport.run(() -> {
            tree.getSelectionModel().select(catalog);
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
        request.setSourceCatalog("test");
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
}
