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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.it.ItDatabase;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: MainViewIT runs the main flow against the MySQL fixture database: login, browse a table, view a data
 *               page and export tables as an export package with the wizard
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class MainViewIT {

    @TempDir
    File dir;

    private AppContext context;
    private StackPane root;
    private final AtomicReference<MainView> mainView = new AtomicReference<>();

    @Start
    void start(Stage stage) {
        context = FxTestSupport.createContext(dir);
        root = new StackPane();
        stage.setScene(new Scene(root, 1280, 800));
        stage.show();
    }

    @AfterEach
    void close() {
        context.getSessionManager().closeAll();
    }

    private static TreeItem<DbNode> child(TreeItem<DbNode> parent, String label) {
        return FxTestSupport.call(() -> parent.getChildren().stream().filter(item -> label.equals(item.getValue()
                .getLabel())).findFirst().orElse(null));
    }

    private static void waitLoaded(TreeItem<DbNode> item) {
        FxTestSupport.waitUntil(() -> item.isExpanded() && item.getChildren().stream()
                .noneMatch(child -> child.getValue().getKind() == DbNode.Kind.LOADING));
    }

    @Test
    void browsesAndExportsTables(FxRobot robot) throws Exception {
        ItDatabase database = ItDatabase.MYSQL;
        assumeTrue(database.isAvailable());
        database.loadFixture();
        ConnectionProfile profile = database.getSourceProfile();
        profile.setName("MySQL fixture");
        context.getProfileRegistry().saveProfile(profile, true);

        // Login
        LoginView loginView = FxTestSupport.call(() -> {
            LoginView view = new LoginView(context, loggedIn -> {
                MainView main = new MainView(context);
                root.getChildren().setAll(main);
                main.selectConnection(loggedIn);
                mainView.set(main);
            });
            root.getChildren().setAll(view);
            view.applyCss();
            view.layout();
            return view;
        });
        FxTestSupport.run(() -> ((Button) loginView.lookup("#loginButton")).fire());
        FxTestSupport.waitUntil(() -> mainView.get() != null);

        // Browse: data source -> database -> table
        ConnectionTree tree = (ConnectionTree) FxTestSupport.call(() -> mainView.get().lookup("#connectionTree"));
        TreeItem<DbNode> connection = FxTestSupport.call(() -> tree.getRoot().getChildren().get(0));
        waitLoaded(connection);
        TreeItem<DbNode> catalog = child(connection, database.getSourceCatalog());
        FxTestSupport.run(() -> catalog.setExpanded(true));
        waitLoaded(catalog);
        TreeItem<DbNode> table = child(catalog, "sjm_emp");
        FxTestSupport.run(() -> {
            tree.getSelectionModel().select(table);
            tree.scrollTo(tree.getRow(table));
        });
        Optional<TreeCell<DbNode>> cell = FxTestSupport.call(() -> {
            tree.layout();
            return tree.lookupAll(".tree-cell").stream().map(node -> {
                @SuppressWarnings("unchecked")
                TreeCell<DbNode> treeCell = (TreeCell<DbNode>) node;
                return treeCell;
            }).filter(treeCell -> treeCell.getItem() != null && "sjm_emp".equals(treeCell.getItem().getLabel()))
                    .findFirst();
        });
        assertTrue(cell.isPresent());
        robot.doubleClickOn(cell.get());
        TabPane tabs = (TabPane) FxTestSupport.call(() -> mainView.get().lookup("#tableTabs"));
        FxTestSupport.waitUntil(() -> tabs.getTabs().size() == 1);

        // Data page 2, default page size is 200 rows
        TabPane inner = FxTestSupport.call(() -> (TabPane) tabs.getTabs().get(0).getContent());
        FxTestSupport.run(() -> inner.getSelectionModel().select(4));
        Parent dataPane = FxTestSupport.call(() -> (Parent) inner.getTabs().get(4).getContent());
        FxTestSupport.waitUntil(() -> "Page 1 / 2".equals(((Label) dataPane.lookup("#pageLabel")).getText()));
        FxTestSupport.run(() -> ((Button) dataPane.lookup("#nextButton")).fire());
        FxTestSupport.waitUntil(() -> "Page 2 / 2".equals(((Label) dataPane.lookup("#pageLabel")).getText()));
        @SuppressWarnings("unchecked")
        TableView<List<String>> rows = (TableView<List<String>>) dataPane.lookup("#dataTable");
        assertEquals("201", FxTestSupport.call(() -> rows.getItems().get(0).get(0)));

        // Export sjm_* tables with the wizard
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> mainView.get().lookup("#action-export")));
        DialogPane exportPane = FxTestSupport.waitForDialogPane();
        FxTestSupport.waitUntil(() -> {
            Label count = (Label) exportPane.lookup("#tableCountLabel");
            return count != null && count.getText().startsWith("1 /");
        });
        File output = new File(dir, "export");
        FxTestSupport.run(() -> {
            ((TextField) exportPane.lookup("#tableFilterField")).setText("sjm_");
            ((Button) exportPane.lookup("#selectAllButton")).fire();
            ((TextField) exportPane.lookup("#tableFilterField")).setText("");
            ((TextField) exportPane.lookup("#directoryField")).setText(output.getAbsolutePath());
        });
        assertTrue(FxTestSupport.call(() -> !exportPane.lookupButton(ButtonType.OK).isDisabled()));
        Platform.runLater(() -> ((Button) exportPane.lookupButton(ButtonType.OK)).fire());

        // Progress reaches 100%
        AtomicReference<Parent> progress = new AtomicReference<>();
        FxTestSupport.waitUntil(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot().lookup("#progressStatus") != null) {
                    progress.set(window.getScene().getRoot());
                    return true;
                }
            }
            return false;
        });
        FxTestSupport.waitUntil(() -> {
            String status = ((Label) progress.get().lookup("#progressStatus")).getText();
            return !"Running...".equals(status);
        });
        assertEquals("Completed", FxTestSupport.call(() -> ((Label) progress.get().lookup("#progressStatus"))
                .getText()));
        assertEquals("Overall 100%", FxTestSupport.call(() -> ((Label) progress.get().lookup("#percentLabel"))
                .getText()));

        ExportManifest manifest = ExportManifest.read(output);
        assertEquals(ExportManifest.Status.COMPLETED, manifest.getStatus());
        Set<String> tables = manifest.getTables().stream().map(ExportManifest.TableEntry::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("sjm_dept", "sjm_emp", "sjm_emp_tag", "sjm_sales", "sjm_types"), tables);
        assertTrue(new File(output, "schema.sql").exists());
        FxTestSupport.run(() -> progress.get().getScene().getWindow().hide());
    }
}
