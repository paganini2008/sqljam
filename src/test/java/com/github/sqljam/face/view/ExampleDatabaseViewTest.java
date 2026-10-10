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
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.service.ExampleDatabase;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: ExampleDatabaseViewTest verifies the first start of a new user: the example data source is selected
 *               on the login page, its tables are browsed and its rows are queried
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ExampleDatabaseViewTest {

    @TempDir
    File dir;

    private AppContext context;
    private StackPane root;
    private ConnectionProfile example;

    @Start
    void start(Stage stage) throws Exception {
        context = FxTestSupport.createContext(dir);
        example = new ExampleDatabase(true, new File(dir, "example"))
                .createAtFirstStart(new File(dir, "connections.json")).orElseThrow();
        context.getProfileRegistry().saveProfile(example, true);
        root = new StackPane();
        stage.setScene(new Scene(root, 1200, 760));
        stage.show();
    }

    @AfterEach
    void close() {
        context.getSessionManager().closeAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectsExampleOnLoginPage() {
        LoginView view = FxTestSupport.call(() -> {
            LoginView loginView = new LoginView(context, profile -> {
            });
            root.getChildren().setAll(loginView);
            loginView.applyCss();
            loginView.layout();
            return loginView;
        });
        ListView<ConnectionProfile> list = FxTestSupport.call(() -> (ListView<ConnectionProfile>) view.lookup(
                "#profileList"));
        assertEquals(List.of(ExampleDatabase.PROFILE_NAME), FxTestSupport.call(() -> list.getItems().stream()
                .map(ConnectionProfile::getName).collect(Collectors.toList())));
        assertEquals(ExampleDatabase.PROFILE_NAME, FxTestSupport.call(() -> list.getSelectionModel()
                .getSelectedItem().getName()));
        // The example is connected without a password
        assertEquals("sa", FxTestSupport.call(() -> ((TextField) view.lookup("#usernameField")).getText()));
    }

    @Test
    void browsesAndQueriesExampleTables() {
        MainView mainView = FxTestSupport.call(() -> {
            MainView view = new MainView(context);
            root.getChildren().setAll(view);
            view.applyCss();
            view.layout();
            return view;
        });
        ConnectionTree tree = FxTestSupport.call(() -> (ConnectionTree) mainView.lookup("#connectionTree"));
        TreeItem<DbNode> connection = FxTestSupport.call(() -> tree.getRoot().getChildren().get(0));
        TreeItem<DbNode> schema = expand(expand(connection).getChildren().get(0));
        TreeItem<DbNode> publicSchema = FxTestSupport.call(() -> schema.getChildren().stream()
                .filter(item -> "PUBLIC".equals(item.getValue().getLabel())).findFirst().orElse(schema));
        TreeItem<DbNode> tables = expand(publicSchema);
        List<String> names = FxTestSupport.call(() -> tables.getChildren().stream()
                .map(item -> item.getValue().getLabel()).collect(Collectors.toList()));
        assertTrue(names.containsAll(List.of("CATEGORIES", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "PRODUCTS")),
                names.toString());
        // Paid orders of the data viewer
        FxTestSupport.run(() -> mainView.openTable(DbNode.table(example, new TableInfo(publicSchema.getParent()
                .getValue().getLabel(), "PUBLIC", "ORDERS", null, false, false))));
        FxTestSupport.run(() -> {
            TabPane tabs = (TabPane) mainView.lookup("#tableTabs");
            ((TabPane) tabs.getSelectionModel().getSelectedItem().getContent()).getSelectionModel().select(4);
        });
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> {
            Label total = (Label) mainView.lookup("#totalLabel");
            return total != null && "300 rows".equals(total.getText());
        }));
        FxTestSupport.run(() -> {
            ((TextField) mainView.lookup("#whereField")).setText("status = 'PAID'");
            ((Button) mainView.lookup("#applyQueryButton")).fire();
        });
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> "60 rows".equals(((Label) mainView
                .lookup("#totalLabel")).getText())));
    }

    private static TreeItem<DbNode> expand(TreeItem<DbNode> item) {
        FxTestSupport.run(() -> item.setExpanded(true));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !item.getChildren().isEmpty() && item.getChildren()
                .stream().noneMatch(child -> child.getValue().getKind() == DbNode.Kind.LOADING)));
        return item;
    }

    private LoginView showLogin() {
        return FxTestSupport.call(() -> {
            LoginView loginView = new LoginView(context, profile -> {
            });
            root.getChildren().setAll(loginView);
            loginView.applyCss();
            loginView.layout();
            return loginView;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(LoginView view) {
        return FxTestSupport.call(() -> ((ListView<ConnectionProfile>) view.lookup("#profileList")).getItems()
                .stream().map(ConnectionProfile::getName).collect(Collectors.toList()));
    }

    private long countOrders() throws Exception {
        try (Connection connection = DriverManager.getConnection(example.getJdbcUrl(), "sa", "");
             ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresRemovedExampleOnLoginPage() throws Exception {
        // The user removed the example data source
        context.getProfileRegistry().removeProfile(ExampleDatabase.PROFILE_ID);
        LoginView view = showLogin();
        assertTrue(names(view).isEmpty());
        // No confirmation, nothing is replaced
        FxTestSupport.run(() -> ((Button) view.lookup("#exampleButton")).fire());
        FxTestSupport.waitUntil(() -> names(view).contains(ExampleDatabase.PROFILE_NAME));
        assertEquals(ExampleDatabase.PROFILE_NAME, FxTestSupport.call(() -> ((ListView<ConnectionProfile>) view
                .lookup("#profileList")).getSelectionModel().getSelectedItem().getName()));
        assertEquals(1, context.getProfileRegistry().getProfiles().size());
        assertEquals(300, countOrders());
    }

    @Test
    void replacesExampleAfterConfirmation() throws Exception {
        try (Connection connection = DriverManager.getConnection(example.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM order_items");
            statement.execute("DELETE FROM orders");
        }
        LoginView view = showLogin();
        // Cancelled, the changed database is kept
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> view.lookup("#exampleButton")));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        assertEquals(0, countOrders());
        // Confirmed, the example is created again with one data source
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> view.lookup("#exampleButton")));
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> {
            try {
                return countOrders() == 300;
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(List.of(ExampleDatabase.PROFILE_NAME), names(view));
    }

    @Test
    void restoresExampleFromMenu() {
        MainView mainView = FxTestSupport.call(() -> {
            MainView view = new MainView(context);
            root.getChildren().setAll(view);
            view.applyCss();
            view.layout();
            return view;
        });
        FxTestSupport.run(() -> mainView.openTable(DbNode.table(example, new TableInfo(null, "PUBLIC", "ORDERS",
                null, false, false))));
        assertEquals(1, FxTestSupport.call(() -> ((TabPane) mainView.lookup("#tableTabs")).getTabs().size()));
        MenuItem restore = FxTestSupport.call(() -> ((MenuBar) mainView
                .lookup(".menu-bar")).getMenus().stream().flatMap(menu -> menu.getItems().stream())
                .filter(item -> "restoreExampleMenuItem".equals(item.getId())).findFirst().orElseThrow());
        assertEquals("Restore Example Database", restore.getText());
        Platform.runLater(restore::fire);
        FxTestSupport.closeDialog(ButtonType.OK);
        // Tabs of the replaced database are closed
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> ((TabPane) mainView.lookup("#tableTabs")).getTabs()
                .isEmpty()));
    }
}
