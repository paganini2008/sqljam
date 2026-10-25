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
import org.testfx.util.WaitForAsyncUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.impexp.DbType;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

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

    /**
     * A data source of the tree, in the group of its category
     */
    private TreeItem<DbNode> connection(String label) {
        return FxTestSupport.call(() -> tree().getConnectionItems().stream().filter(item -> item.getValue()
                .getLabel().startsWith(label)).findFirst().orElse(null));
    }

    private List<String> connectionLabels() {
        return FxTestSupport.call(() -> tree().getConnectionItems().stream().map(item -> item.getValue()
                .getLabel()).collect(Collectors.toList()));
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
        TreeItem<DbNode> connection = connection("Embedded H2");
        expandAndWait(connection);
        TreeItem<DbNode> catalog = child(connection, UiDatabase.CATALOG);
        assertNotNull(catalog, labels(connection).toString());
        expandAndWait(catalog);
        TreeItem<DbNode> schema = child(catalog, UiDatabase.SCHEMA);
        expandAndWait(schema);
        return schema;
    }

    @Test
    void filtersTreeByField() {
        TextField field = (TextField) mainView.lookup("#treeFilterField");
        Button clear = (Button) mainView.lookup("#treeFilterClearButton");
        assertFalse(FxTestSupport.call(clear::isVisible));
        FxTestSupport.run(() -> field.setText("embedded"));
        assertEquals(List.of("Embedded H2"), connectionLabels().stream().map(label -> label.substring(0, 11))
                .collect(Collectors.toList()));
        assertEquals("embedded", tree().getFilter());
        assertTrue(FxTestSupport.call(clear::isVisible));
        FxTestSupport.fireLater(clear);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("", FxTestSupport.call(field::getText));
        assertTrue(connectionLabels().size() > 1);
        // Escape clears it too
        FxTestSupport.run(() -> field.setText("nothing"));
        assertEquals(List.of(), connectionLabels());
        Platform.runLater(() -> Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("", tree().getFilter());
        // The tree changes the field
        FxTestSupport.run(() -> tree().setFilter("h2"));
        assertEquals("h2", FxTestSupport.call(field::getText));
        FxTestSupport.run(() -> tree().setFilter(""));
    }

    @Test
    void loadsTreeLazily() {
        TreeItem<DbNode> connection = connection("Embedded H2");
        assertEquals(2, FxTestSupport.call(() -> tree().getConnectionItems().size()));
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
        TreeItem<DbNode> unreachable = connection("Unreachable MySQL");
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
        TreeItem<DbNode> unreachable = connection("Unreachable MySQL");
        FxTestSupport.run(() -> tree().getSelectionModel().select(unreachable));
        Button deleteButton = FxTestSupport.call(() -> (Button) mainView.lookup("#action-deleteConnection"));
        assertFalse(FxTestSupport.call(deleteButton::isDisabled));
        FxTestSupport.fireLater(deleteButton);
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> tree().getConnectionItems().size() == 1);
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
        return FxTestSupport.menuItem(mainView, id);
    }

    @Test
    void opensParquetImportFromMenuAndTree() {
        MenuItem importParquet = fileMenuItem("importParquetMenuItem");
        assertEquals("Import Parquet or CSV Files...", importParquet.getText());
        assertEquals("Open Export Package...", fileMenuItem("openPackageMenuItem").getText());
        Platform.runLater(importParquet::fire);
        DialogPane pane = FxTestSupport.waitForDialogPane();
        assertEquals("Load Parquet or CSV files into a table of a database", FxTestSupport.call(pane::getHeaderText));
        assertTrue(FxTestSupport.call(() -> pane.lookupButton(ButtonType.OK).isDisabled()));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        // The context menu of a data source offers both imports
        TreeItem<DbNode> connection = connection("Embedded H2");
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
        assertTrue(items.containsAll(List.of("Import Export Package...", "Import Parquet or CSV Files...")),
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
        TreeItem<DbNode> connection = connection("Embedded H2");
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
        TreeItem<DbNode> unreachable = connection("Unreachable MySQL");
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
        FxTestSupport.waitUntil(() -> connectionLabels().contains("Target H2"));
        assertEquals(3, connectionLabels().size());
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

    /**
     * Copies the schema into the same database by the export dialog of the main window, it starts without a
     * question about the rows, like the import wizards of other tools
     *
     * @param commitEvery whether rows are committed every N rows instead of one transaction
     */
    @SuppressWarnings("unchecked")
    private void startCopy(String schema, boolean commitEvery, boolean confirm) {
        TreeItem<DbNode> schemaItem = expandToSchema();
        FxTestSupport.run(() -> tree().getSelectionModel().select(schemaItem));
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> mainView.lookup("#action-export")));
        DialogPane export = FxTestSupport.waitForDialogPane();
        FxTestSupport.run(() -> ((RadioButton) export.lookup("#databaseRadio")).setSelected(true));
        ComboBox<ConnectionProfile> target = FxTestSupport.call(() -> (ComboBox<ConnectionProfile>) export
                .lookup("#targetCombo"));
        FxTestSupport.run(() -> target.setValue(target.getItems().stream().filter(item -> "Embedded H2".equals(item
                .getName())).findFirst().orElseThrow()));
        ComboBox<String> targetSchema = FxTestSupport.call(() -> (ComboBox<String>) export
                .lookup("#targetSchemaCombo"));
        FxTestSupport.waitUntil(() -> !targetSchema.getItems().isEmpty());
        FxTestSupport.run(() -> targetSchema.getEditor().setText(schema));
        // One transaction by default
        assertTrue(FxTestSupport.call(() -> ((RadioButton) export.lookup("#oneTransactionRadio")).isSelected()));
        if (commitEvery) {
            FxTestSupport.run(() -> ((RadioButton) export.lookup("#commitEveryRadio")).setSelected(true));
        }
        FxTestSupport.run(() -> ((CheckBox) export.lookup("#confirmCommitCheck")).setSelected(confirm));
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> export.lookupButton(ButtonType.OK)));
    }

    private static void press(DialogPane pane, String text) {
        FxTestSupport.fireLater(FxTestSupport.call(() -> pane.getButtonTypes().stream().map(type -> (Button) pane
                .lookupButton(type)).filter(button -> text.equals(button.getText())).findFirst().orElseThrow()));
    }

    private static Label progressStatus() {
        FxTestSupport.waitUntil(() -> Window.getWindows().stream().anyMatch(window -> window.isShowing()
                && window.getScene() != null && window.getScene().getRoot().lookup("#progressStatus") != null));
        return FxTestSupport.call(() -> (Label) Window.getWindows().stream().filter(window -> window.isShowing()
                && window.getScene() != null && window.getScene().getRoot().lookup("#progressStatus") != null)
                .findFirst().orElseThrow().getScene().getRoot().lookup("#progressStatus"));
    }

    @Test
    void commitsCopyEveryRows() {
        startCopy("PAGES", true, false);
        Label status = progressStatus();
        FxTestSupport.waitUntil(() -> "Completed".equals(status.getText()));
        // No question after the import, the rows are committed already
        assertTrue(FxTestSupport.getDialogPanes().stream().noneMatch(pane -> "decisionPane".equals(pane.getId())));
        FxTestSupport.run(() -> status.getScene().getWindow().hide());
    }

    @Test
    void commitsCopyInOneTransaction() {
        startCopy("ONE_TX", false, false);
        Label status = progressStatus();
        // Committed when the import succeeds, like the import wizards of other tools
        FxTestSupport.waitUntil(() -> "Completed".equals(status.getText()));
        assertTrue(FxTestSupport.getDialogPanes().stream().noneMatch(pane -> "decisionPane".equals(pane.getId())));
        FxTestSupport.run(() -> status.getScene().getWindow().hide());
    }

    @Test
    void confirmsCopyBeforeCommit() {
        startCopy("CONFIRMED", false, true);
        Label status = progressStatus();
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> "decisionPane"
                .equals(pane.getId())));
        press(FxTestSupport.getDialogPanes().stream().filter(pane -> "decisionPane".equals(pane.getId()))
                .findFirst().orElseThrow(), "Commit");
        FxTestSupport.waitUntil(() -> "Committed".equals(status.getText()));
        FxTestSupport.run(() -> status.getScene().getWindow().hide());
    }

    /**
     * The SQL editor of the selected schema: a query runs, its rows are exported as a new table of the same
     * schema, the editor is closed with its data source
     */
    @Test
    @SuppressWarnings("unchecked")
    void exportsQueryOfSqlEditor() throws Exception {
        TreeItem<DbNode> schemaItem = expandToSchema();
        assertTrue(FxTestSupport.call(() -> contextMenu(schemaItem).getItems().stream().anyMatch(item -> "SQL Query"
                .equals(item.getText()))));
        FxTestSupport.run(() -> tree().getSelectionModel().select(schemaItem));
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> mainView.lookup("#action-sqlQuery")));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> tabs().getTabs().size()) == 1);
        SqlTab tab = (SqlTab) FxTestSupport.call(() -> tabs().getTabs().get(0));
        FxTestSupport.run(() -> tab.getEditor().setText("SELECT ID, NAME FROM T_ONE WHERE ID <= 3"));
        FxTestSupport.run(() -> tab.run(false));
        FxTestSupport.waitUntil(() -> !tab.isBusy() && tab.getLastQuery() != null);
        // The export wizard waits for the user
        javafx.application.Platform.runLater(tab::export);
        DialogPane export = FxTestSupport.waitForDialogPane();
        FxTestSupport.run(() -> ((TextField) export.lookup("#queryTableField")).setText("SQL_COPY"));
        FxTestSupport.run(() -> ((RadioButton) export.lookup("#databaseRadio")).setSelected(true));
        ComboBox<ConnectionProfile> target = FxTestSupport.call(() -> (ComboBox<ConnectionProfile>) export
                .lookup("#targetCombo"));
        FxTestSupport.run(() -> target.setValue(target.getItems().stream().filter(item -> "Embedded H2".equals(item
                .getName())).findFirst().orElseThrow()));
        ComboBox<String> targetSchema = FxTestSupport.call(() -> (ComboBox<String>) export
                .lookup("#targetSchemaCombo"));
        FxTestSupport.waitUntil(() -> !targetSchema.getItems().isEmpty());
        FxTestSupport.run(() -> targetSchema.getEditor().setText(UiDatabase.SCHEMA));
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> export.lookupButton(ButtonType.OK)));
        Label status = progressStatus();
        FxTestSupport.waitUntil(() -> "Completed".equals(status.getText()));
        FxTestSupport.run(() -> status.getScene().getWindow().hide());
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             java.sql.ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM SQL_COPY")) {
            rs.next();
            assertEquals(3, rs.getInt(1));
        }
        // Closed with its data source
        TreeItem<DbNode> connection = connection("Embedded H2");
        FxTestSupport.run(() -> tree().disconnect(connection));
        assertTrue(FxTestSupport.call(() -> tabs().getTabs().isEmpty()));
        // Nothing selected: the first data source
        FxTestSupport.run(() -> mainView.openSqlTab(null));
        assertTrue(FxTestSupport.call(() -> tabs().getTabs().get(0).getText()).startsWith("SQL 2: "));
        FxTestSupport.run(() -> ((SqlTab) tabs().getTabs().get(0)).close());
    }

    private static boolean disabledOnShowing(MenuItem item) {
        return FxTestSupport.call(() -> {
            item.getParentMenu().getOnShowing().handle(new javafx.event.Event(Menu.ON_SHOWING));
            return item.isDisable();
        });
    }

    /**
     * The menus of the main window: the actions of the toolbar and of the tree, switched off without a data source,
     * the themes in the menu and in the toolbar, tabs closed together and the keys read from the menus
     */
    @Test
    void offersActionsInMenus() {
        assertEquals(List.of("File", "Database", "Transfer", "View", "Help"), FxTestSupport.call(() -> ((MenuBar)
                mainView.lookup(".menu-bar")).getMenus().stream().map(Menu::getText).collect(Collectors.toList())));
        MenuItem edit = FxTestSupport.menuItem(mainView, "action-editConnection-menu");
        MenuItem connect = FxTestSupport.menuItem(mainView, "tree-connect-menu");
        MenuItem disconnect = FxTestSupport.menuItem(mainView, "tree-disconnect-menu");
        MenuItem disconnectAll = FxTestSupport.menuItem(mainView, "action-disconnectAll-menu");
        MenuItem dashboard = FxTestSupport.menuItem(mainView, "action-dashboard-menu");
        FxTestSupport.run(() -> tree().getSelectionModel().clearSelection());
        assertTrue(disabledOnShowing(edit));
        assertTrue(disabledOnShowing(connect));
        assertTrue(disabledOnShowing(dashboard));

        // A data source: connected by the menu, its table selected keeps it, disconnected again
        TreeItem<DbNode> h2 = connection("Embedded H2");
        FxTestSupport.run(() -> tree().getSelectionModel().select(h2));
        assertFalse(disabledOnShowing(edit));
        assertFalse(disabledOnShowing(connect));
        assertTrue(disabledOnShowing(disconnect));
        FxTestSupport.run(connect::fire);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> tree().isConnected(h2)));
        assertTrue(disabledOnShowing(connect));
        assertFalse(disabledOnShowing(disconnectAll));
        FxTestSupport.run(dashboard::fire);
        InfoTab info = FxTestSupport.call(() -> (InfoTab) tabs().getSelectionModel().getSelectedItem());
        assertTrue(FxTestSupport.call(() -> info.getDashboard() != null));
        FxTestSupport.run(disconnectAll::fire);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !tree().isConnected(h2)));

        // Disconnected: its info was closed with it
        assertTrue(FxTestSupport.call(() -> tabs().getTabs().isEmpty()));
        // Tabs: the others, then all of them, closed like by the user
        FxTestSupport.run(() -> {
            mainView.openHistory();
            mainView.openSync();
        });
        assertEquals(2, FxTestSupport.call(() -> tabs().getTabs().size()).intValue());
        FxTestSupport.run(FxTestSupport.menuItem(mainView, "action-closeOtherTabs-menu")::fire);
        assertEquals(List.of("Sync Tasks"), FxTestSupport.call(() -> tabs().getTabs().stream().map(Tab::getText)
                .collect(Collectors.toList())));
        FxTestSupport.run(FxTestSupport.menuItem(mainView, "action-closeAllTabs-menu")::fire);
        assertTrue(FxTestSupport.call(() -> tabs().getTabs().isEmpty()));
        assertTrue(disabledOnShowing(FxTestSupport.menuItem(mainView, "action-closeTab-menu")));

        // The theme of the toolbar is checked in the menu too, Next Theme goes on through the list
        javafx.scene.control.MenuButton themes = FxTestSupport.call(() -> (javafx.scene.control.MenuButton) mainView
                .lookup("#toolbarThemeButton"));
        String second = ThemeManager.getThemeNames().get(1);
        FxTestSupport.run(() -> themes.getItems().stream().filter(item -> second.equals(item.getText())).findFirst()
                .orElseThrow().fire());
        assertEquals(second, context.getSettings().getTheme());
        assertTrue(FxTestSupport.call(() -> ((Menu) FxTestSupport.menuItem(mainView, "themeMenu")).getItems().stream()
                .filter(item -> second.equals(item.getText())).map(item -> ((javafx.scene.control.RadioMenuItem) item)
                        .isSelected()).findFirst().orElseThrow()));
        FxTestSupport.run(FxTestSupport.menuItem(mainView, "action-nextTheme-menu")::fire);
        assertEquals(ThemeManager.getThemeNames().get(2), context.getSettings().getTheme());
        FxTestSupport.run(() -> mainView.applyTheme(ThemeManager.DEFAULT_THEME));

        // The keys of the menus
        List<String[]> shortcuts = FxTestSupport.call(mainView::getShortcuts);
        assertTrue(shortcuts.stream().anyMatch(row -> row[0].equals("Transfer > Export...")), shortcuts.stream().map(
                row -> row[0]).collect(Collectors.joining(", ")));
        assertTrue(shortcuts.stream().anyMatch(row -> row[0].equals("Database > Refresh")));
        FxTestSupport.run(FxTestSupport.menuItem(mainView, "help-shortcuts-menu")::fire);
        javafx.scene.control.DialogPane pane = FxTestSupport.waitForDialogPane();
        assertEquals(shortcuts.size(), FxTestSupport.call(() -> ((javafx.scene.control.TableView<?>) pane.lookup(
                "#shortcutsTable")).getItems().size()).intValue());
        FxTestSupport.closeDialog(javafx.scene.control.ButtonType.OK);
    }
}
