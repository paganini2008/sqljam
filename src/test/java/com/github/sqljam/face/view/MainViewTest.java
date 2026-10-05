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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
import com.github.sqljam.impexp.DbType;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.stage.Stage;

/**
 * @Description: MainViewTest verifies the data source tree and table tabs against an embedded database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class MainViewTest {

    @TempDir
    File dir;

    private AppContext context;
    private MainView mainView;
    private ConnectionProfile profile;

    @Start
    void start(Stage stage) throws Exception {
        context = FxTestSupport.createContext(dir);
        profile = UiDatabase.create(dir, "Embedded H2");
        context.getProfileRegistry().saveProfile(profile, true);
        ConnectionProfile unreachable = new ConnectionProfile();
        unreachable.setName("Unreachable MySQL");
        unreachable.setDbType(DbType.MYSQL);
        unreachable.setHostname("127.0.0.1");
        unreachable.setPort(1);
        context.getProfileRegistry().saveProfile(unreachable, true);
        mainView = new MainView(context);
        stage.setScene(new Scene(mainView, 1200, 800));
        stage.show();
    }

    @AfterEach
    void close() {
        context.getSessionManager().closeAll();
    }

    private ConnectionTree tree() {
        return (ConnectionTree) mainView.lookup("#connectionTree");
    }

    private TabPane tabs() {
        return (TabPane) mainView.lookup("#tableTabs");
    }

    private TreeItem<DbNode> child(TreeItem<DbNode> parent, String label) {
        return FxTestSupport.call(() -> parent.getChildren().stream().filter(item -> item.getValue().getLabel()
                .startsWith(label)).findFirst().orElse(null));
    }

    private static List<String> labels(TreeItem<DbNode> item) {
        return FxTestSupport.call(() -> item.getChildren().stream().map(child -> child.getValue().getLabel())
                .collect(Collectors.toList()));
    }

    private static void expandAndWait(TreeItem<DbNode> item) {
        FxTestSupport.run(() -> item.setExpanded(true));
        FxTestSupport.waitUntil(() -> item.getChildren().stream()
                .noneMatch(child -> child.getValue().getKind() == DbNode.Kind.LOADING));
    }

    private TreeItem<DbNode> expandToSchema() {
        TreeItem<DbNode> connection = child(tree().getRoot(), "Embedded H2");
        expandAndWait(connection);
        TreeItem<DbNode> catalog = child(connection, UiDatabase.CATALOG);
        assertNotNull(catalog, labels(connection).toString());
        expandAndWait(catalog);
        TreeItem<DbNode> schema = child(catalog, UiDatabase.SCHEMA);
        expandAndWait(schema);
        return schema;
    }

    @Test
    void loadsTreeLazily() {
        TreeItem<DbNode> connection = child(tree().getRoot(), "Embedded H2");
        assertEquals(2, FxTestSupport.call(() -> tree().getRoot().getChildren().size()));
        // Placeholder child until expanded
        assertEquals(DbNode.Kind.LOADING, FxTestSupport.call(() -> connection.getChildren().get(0).getValue()
                .getKind()));
        assertFalse(FxTestSupport.call(connection::isLeaf));
        TreeItem<DbNode> schema = expandToSchema();
        assertEquals(List.of("T_EMPTY", "T_ONE", "T_PAGED"), labels(schema));
        assertTrue(FxTestSupport.call(() -> ((javafx.scene.control.Label) mainView.lookup("#statusLabel"))
                .getText()).startsWith("Embedded H2 - H2"));
    }

    @Test
    void refreshesSelectedNode() throws Exception {
        TreeItem<DbNode> schema = expandToSchema();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE T_NEW (ID INT)");
        }
        FxTestSupport.run(() -> {
            tree().getSelectionModel().select(schema);
            tree().refreshSelected();
        });
        FxTestSupport.waitUntil(() -> schema.getChildren().stream().anyMatch(item -> "T_NEW".equals(
                item.getValue().getLabel())));
        // Refreshing a table reloads its parent
        TreeItem<DbNode> table = child(schema, "T_ONE");
        FxTestSupport.run(() -> {
            tree().getSelectionModel().select(table);
            tree().refreshSelected();
        });
        FxTestSupport.waitUntil(() -> schema.getChildren().size() == 4 && schema.getChildren().stream()
                .noneMatch(item -> item.getValue().getKind() == DbNode.Kind.LOADING));
    }

    @Test
    void showsEmptyAndErrorNodes() {
        TreeItem<DbNode> unreachable = child(tree().getRoot(), "Unreachable MySQL");
        FxTestSupport.run(() -> unreachable.setExpanded(true));
        FxTestSupport.waitForDialogPane();
        FxTestSupport.closeAllDialogs();
        FxTestSupport.waitUntil(() -> unreachable.getChildren().get(0).getValue().getKind()
                == DbNode.Kind.MESSAGE);
        assertTrue(labels(unreachable).get(0).startsWith("Error:"));
    }

    @Test
    void opensTableOnceAndClosesTab() {
        TreeItem<DbNode> schema = expandToSchema();
        DbNode table = FxTestSupport.call(() -> child(schema, "T_PAGED").getValue());
        FxTestSupport.run(() -> tree().getSelectionModel().select(child(schema, "T_PAGED")));
        ConnectionTree tree = tree();
        FxTestSupport.run(() -> openTable(tree, table));
        FxTestSupport.run(() -> openTable(tree, table));
        assertEquals(1, FxTestSupport.call(() -> tabs().getTabs().size()));
        DbNode other = FxTestSupport.call(() -> child(schema, "T_ONE").getValue());
        FxTestSupport.run(() -> openTable(tree, other));
        assertEquals(2, FxTestSupport.call(() -> tabs().getTabs().size()));
        // Opening an opened table selects its tab
        FxTestSupport.run(() -> openTable(tree, table));
        assertEquals("T_PAGED", FxTestSupport.call(() -> tabs().getSelectionModel().getSelectedItem().getText()));
        FxTestSupport.run(() -> tabs().getTabs().remove(tabs().getSelectionModel().getSelectedItem()));
        assertEquals(List.of("T_ONE"), FxTestSupport.call(() -> tabs().getTabs().stream().map(Tab::getText)
                .collect(Collectors.toList())));
    }

    /**
     * Opens a table like double clicking it in the tree
     */
    private static void openTable(ConnectionTree tree, DbNode node) {
        try {
            java.lang.reflect.Field field = ConnectionTree.class.getDeclaredField("onOpenTable");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<DbNode> consumer = (java.util.function.Consumer<DbNode>) field.get(tree);
            consumer.accept(node);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void showsTableMetadataDdlAndData() {
        TreeItem<DbNode> schema = expandToSchema();
        DbNode table = FxTestSupport.call(() -> child(schema, "T_PAGED").getValue());
        FxTestSupport.run(() -> openTable(tree(), table));
        TableTab tab = (TableTab) FxTestSupport.call(() -> tabs().getTabs().get(0));
        TabPane inner = FxTestSupport.call(() -> (TabPane) tab.getContent());
        @SuppressWarnings("unchecked")
        TableView<Object> columns = (TableView<Object>) FxTestSupport.call(() -> inner.getTabs().get(0).getContent());
        FxTestSupport.waitUntil(() -> columns.getItems().size() == 5);
        @SuppressWarnings("unchecked")
        TableView<Object> indexes = (TableView<Object>) FxTestSupport.call(() -> inner.getTabs().get(1).getContent());
        FxTestSupport.waitUntil(() -> indexes.getItems().size() >= 2);

        // DDL is loaded when its tab is selected
        FxTestSupport.run(() -> inner.getSelectionModel().select(3));
        TextArea ddl = (TextArea) FxTestSupport.call(() -> inner.getTabs().get(3).getContent().lookup(".text-area"));
        FxTestSupport.waitUntil(() -> ddl.getText().contains("CREATE TABLE"));
        assertTrue(FxTestSupport.call(ddl::getText).contains("T_PAGED"));
        @SuppressWarnings("unchecked")
        ComboBox<DbType> dialect = (ComboBox<DbType>) FxTestSupport.call(() -> inner.getTabs().get(3).getContent()
                .lookup(".combo-box"));
        FxTestSupport.run(() -> dialect.setValue(DbType.MYSQL));
        FxTestSupport.waitUntil(() -> ddl.getText().contains("ENGINE=InnoDB"));

        FxTestSupport.run(() -> inner.getSelectionModel().select(4));
        FxTestSupport.waitUntil(() -> "250 rows".equals(((javafx.scene.control.Label) inner.getTabs().get(4)
                .getContent().lookup("#totalLabel")).getText()));
    }

    @Test
    void deletesDataSource() {
        TreeItem<DbNode> unreachable = child(tree().getRoot(), "Unreachable MySQL");
        FxTestSupport.run(() -> tree().getSelectionModel().select(unreachable));
        Button deleteButton = FxTestSupport.call(() -> (Button) mainView.lookup("#action-deleteConnection"));
        assertFalse(FxTestSupport.call(deleteButton::isDisabled));
        FxTestSupport.fireLater(deleteButton);
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> tree().getRoot().getChildren().size() == 1);
        assertEquals(1, context.getProfileRegistry().getProfiles().size());
    }

    @Test
    void disablesEditOfNonDataSourceNodes() {
        TreeItem<DbNode> schema = expandToSchema();
        FxTestSupport.run(() -> tree().getSelectionModel().select(schema));
        assertTrue(FxTestSupport.call(() -> mainView.lookup("#action-editConnection").isDisabled()));
        assertTrue(FxTestSupport.call(() -> mainView.lookup("#action-deleteConnection").isDisabled()));
    }

    @Test
    void createsTableKey() {
        DbNode node = DbNode.table(profile, new TableInfo("UI", "PUBLIC", "T_ONE", null, false, false));
        assertEquals("Embedded H2.UI.PUBLIC.T_ONE", TableTab.getKey(node));
        DbNode noSchema = DbNode.table(profile, new TableInfo(null, null, "t", null, false, false));
        assertEquals("Embedded H2.t", TableTab.getKey(noSchema));
    }
}
