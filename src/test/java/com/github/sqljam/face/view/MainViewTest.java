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
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.function.Consumer;
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
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
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
        assertTrue(FxTestSupport.call(() -> ((Label) mainView.lookup("#statusLabel"))
                .getText()).startsWith("Embedded H2 - H2"));
    }

    @Test
    void refreshesSelectedNode() throws Exception {
        TreeItem<DbNode> schema = expandToSchema();
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
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
            Field field = ConnectionTree.class.getDeclaredField("onOpenTable");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Consumer<DbNode> consumer = (Consumer<DbNode>) field.get(tree);
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
        FxTestSupport.waitUntil(() -> "250 rows".equals(((Label) inner.getTabs().get(4)
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

    private MenuItem fileMenuItem(String id) {
        return FxTestSupport.call(() -> {
            Menu fileMenu = ((MenuBar) mainView.lookup(".menu-bar")).getMenus().get(0);
            return fileMenu.getItems().stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
        });
    }

    @Test
    void opensParquetImportFromMenuAndTree() {
        MenuItem importParquet = fileMenuItem("importParquetMenuItem");
        assertEquals("Import Parquet Files...", importParquet.getText());
        assertEquals("Open Export Package...", fileMenuItem("openPackageMenuItem").getText());
        Platform.runLater(importParquet::fire);
        DialogPane pane = FxTestSupport.waitForDialogPane();
        assertEquals("Load Parquet files into a table of a database", FxTestSupport.call(pane::getHeaderText));
        assertTrue(FxTestSupport.call(() -> pane.lookupButton(ButtonType.OK).isDisabled()));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        // The context menu of a data source offers both imports
        TreeItem<DbNode> connection = child(tree().getRoot(), "Embedded H2");
        FxTestSupport.run(() -> tree().getSelectionModel().select(connection));
        List<String> items = FxTestSupport.call(() -> {
            tree().applyCss();
            tree().layout();
            return tree().lookupAll(".tree-cell").stream()
                    .map(node -> (TreeCell<?>) node)
                    .filter(cell -> cell.getTreeItem() == connection && cell.getContextMenu() != null)
                    .flatMap(cell -> cell.getContextMenu().getItems().stream()).map(MenuItem::getText)
                    .filter(text -> text != null).collect(Collectors.toList());
        });
        assertTrue(items.containsAll(List.of("Import Export Package...", "Import Parquet Files...")),
                items.toString());
    }

    @Test
    void showsRepositoryMark() {
        Label mark = FxTestSupport.call(() -> (Label) mainView.lookup("#repositoryMark"));
        assertNotNull(mark);
        assertEquals(Branding.REPOSITORY_URL, FxTestSupport.call(() -> mark.getTooltip().getText()));
    }

    @Test
    void opensRepositoryFromMark() {
        Label mark = FxTestSupport.call(() -> (Label) mainView.lookup("#repositoryMark"));
        assertEquals(Cursor.HAND, FxTestSupport.call(mark::getCursor));
        assertNotNull(FxTestSupport.call(mark::getOnMouseClicked));
    }

    private ContextMenu contextMenu(TreeItem<DbNode> item) {
        return FxTestSupport.call(() -> {
            tree().getSelectionModel().select(item);
            tree().applyCss();
            tree().layout();
            return tree().lookupAll(".tree-cell").stream().map(node -> (TreeCell<?>) node)
                    .filter(cell -> cell.getTreeItem() == item && cell.getContextMenu() != null)
                    .map(TreeCell::getContextMenu).findFirst().orElseThrow();
        });
    }

    private static MenuItem menuItem(ContextMenu menu, String id) {
        return menu.getItems().stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow();
    }

    /**
     * Visibility of connect and disconnect when the menu is shown
     */
    private static List<Boolean> connectState(ContextMenu menu) {
        return FxTestSupport.call(() -> {
            menu.getOnShowing().handle(null);
            return List.of(menuItem(menu, "connectMenuItem").isVisible(), menuItem(menu, "disconnectMenuItem")
                    .isVisible());
        });
    }

    @Test
    void disconnectsOpenedConnection() {
        TreeItem<DbNode> connection = child(tree().getRoot(), "Embedded H2");
        assertEquals(List.of(true, false), connectState(contextMenu(connection)));
        expandAndWait(connection);
        assertTrue(FxTestSupport.call(() -> tree().isConnected(connection)));
        ContextMenu menu = contextMenu(connection);
        assertEquals(List.of(false, true), connectState(menu));
        FxTestSupport.run(() -> mainView.openTable(DbNode.table(profile, new TableInfo(null, "PUBLIC", "T_ONE", null,
                false, false))));
        assertEquals(1, FxTestSupport.call(() -> tabs().getTabs().size()));
        FxTestSupport.run(() -> menuItem(menu, "disconnectMenuItem").fire());
        // Collapsed, tabs of the data source are closed, connect is offered again
        assertFalse(FxTestSupport.call(connection::isExpanded));
        assertFalse(FxTestSupport.call(() -> tree().isConnected(connection)));
        assertTrue(FxTestSupport.call(() -> tabs().getTabs().isEmpty()));
        assertEquals("Disconnected: Embedded H2", FxTestSupport.call(() -> ((Label) mainView.lookup("#statusLabel"))
                .getText()));
        assertEquals(List.of(true, false), connectState(contextMenu(connection)));
        // Connected again by expanding it
        expandAndWait(connection);
        assertTrue(FxTestSupport.call(() -> tree().isConnected(connection)));
        assertTrue(labels(connection).size() > 0);
        // Not connected, nothing happens
        TreeItem<DbNode> unreachable = child(tree().getRoot(), "Unreachable MySQL");
        FxTestSupport.run(() -> tree().disconnect(unreachable));
        assertFalse(FxTestSupport.call(() -> tree().isConnected(unreachable)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsTargetDataSourceInImportDialog() {
        Platform.runLater(fileMenuItem("importParquetMenuItem")::fire);
        DialogPane importPane = FxTestSupport.waitForDialogPane();
        Button newButton = FxTestSupport.call(() -> (Button) importPane.lookup("#newTargetButton"));
        assertEquals("New data source as the target", FxTestSupport.call(() -> newButton.getTooltip().getText()));
        FxTestSupport.fireLater(newButton);
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().size() == 2);
        DialogPane connectionPane = FxTestSupport.getDialogPanes().stream().filter(pane -> pane != importPane)
                .findFirst().orElseThrow();
        File target = new File(dir, "target");
        FxTestSupport.run(() -> {
            ((TextField) connectionPane.lookup("#nameField")).setText("Target H2");
            ((ComboBox<DbType>) connectionPane.lookup("#dbTypeCombo")).setValue(DbType.H2);
            ((TextField) connectionPane.lookup("#databaseField")).setText(target.getAbsolutePath());
        });
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> connectionPane.lookupButton(ButtonType.OK)));
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().size() == 1);
        // The created data source is the selected target
        ComboBox<ConnectionProfile> targetCombo = FxTestSupport.call(() -> (ComboBox<ConnectionProfile>) importPane
                .lookup("#targetCombo"));
        assertEquals("Target H2", FxTestSupport.call(() -> targetCombo.getValue().getName()));
        assertEquals(3, FxTestSupport.call(() -> targetCombo.getItems().size()));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        // The tree lists it after the dialog, opened connections are kept
        FxTestSupport.waitUntil(() -> labels(tree().getRoot()).contains("Target H2"));
        assertEquals(3, labels(tree().getRoot()).size());
    }

    @Test
    void createsFirstDataSourceInImportDialog() throws Exception {
        for (ConnectionProfile saved : context.getProfileRegistry().getProfiles()) {
            context.getProfileRegistry().removeProfile(saved.getId());
        }
        // Without data sources the import dialog is opened, the target is created there
        Platform.runLater(fileMenuItem("importParquetMenuItem")::fire);
        DialogPane importPane = FxTestSupport.waitForDialogPane();
        assertNotNull(FxTestSupport.call(() -> importPane.lookup("#newTargetButton")));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
    }

    @Test
    void showsExitButton() {
        Button exit = FxTestSupport.call(() -> (Button) mainView.lookup("#action-exit"));
        assertEquals("Exit", FxTestSupport.call(exit::getText));
        MenuItem exitItem = FxTestSupport.call(() -> ((MenuBar) mainView.lookup(".menu-bar")).getMenus().get(0)
                .getItems().stream().filter(item -> "Exit".equals(item.getText())).findFirst().orElseThrow());
        assertNotNull(exitItem.getGraphic());
    }
}
