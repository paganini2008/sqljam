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
package com.github.sqljam.face.vdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import com.github.sqljam.utils.RestException;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.ConnectionDialog;
import com.github.sqljam.face.view.ConnectionTree;
import com.github.sqljam.face.view.DataSourceExtension;
import com.github.sqljam.face.view.DbIcons;
import com.github.sqljam.face.view.DbNode;
import com.github.sqljam.face.view.FxTestSupport;
import com.github.sqljam.impexp.DbCategory;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.vdb.VdbType;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: DataSourceKindsTest verifies the three kinds of data sources of the user interface: the category
 *               of the data source form (relational, OLAP or vector databases) chooses its types and fields, the connection tree groups the data sources by
 *               their kinds with colored icons, and the vector databases of the tree are opened, edited and deleted
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class DataSourceKindsTest {

    @TempDir
    File dir;

    private AppContext context;
    private Stage stage;
    private VdbProfileStore store;
    private VdbDataSourceExtension extension;
    private ConnectionTree tree;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        ConnectionProfile h2 = new ConnectionProfile();
        h2.setName("Shop H2");
        h2.setDbType(DbType.H2);
        h2.setDatabase(new File(dir, "h2/db").getAbsolutePath());
        h2.setUsername("sa");
        h2.setPassword("");
        context.getProfileRegistry().saveProfile(h2, true);
        ConnectionProfile duck = new ConnectionProfile();
        duck.setName("Lake DuckDB");
        duck.setDbType(DbType.DUCKDB);
        duck.setDatabase(new File(dir, "lake.duckdb").getAbsolutePath());
        context.getProfileRegistry().saveProfile(duck, true);
        store = new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME));
        VdbProfile qdrant = new VdbProfile();
        qdrant.setName("Docs Qdrant");
        qdrant.setType(VdbType.QDRANT);
        qdrant.setUrl("http://127.0.0.1:1");
        qdrant.setApiKey("key");
        store.saveProfile(qdrant, true);
        extension = new VdbDataSourceExtension(store);
        tree = new ConnectionTree(context, List.of(extension));
        stage.setScene(new Scene(new StackPane(tree), 500, 600));
        stage.show();
    }

    @AfterEach
    void close() {
        FxTestSupport.closeAllDialogs();
        context.getSessionManager().closeAll();
    }

    private TreeItem<DbNode> group(String label) {
        return FxTestSupport.call(() -> tree.getRoot().getChildren().stream().filter(item -> item.getValue()
                .getLabel().equals(label)).findFirst().orElseThrow());
    }

    private static List<String> labels(TreeItem<DbNode> item) {
        return FxTestSupport.call(() -> item.getChildren().stream().map(child -> child.getValue().getLabel())
                .collect(Collectors.toList()));
    }

    private ContextMenu contextMenu(TreeItem<DbNode> item) {
        return FxTestSupport.call(() -> {
            tree.getSelectionModel().select(item);
            tree.applyCss();
            tree.layout();
            return tree.lookupAll(".tree-cell").stream().map(node -> (TreeCell<?>) node)
                    .filter(cell -> cell.getTreeItem() == item && cell.getContextMenu() != null)
                    .map(TreeCell::getContextMenu).findFirst().orElseThrow();
        });
    }

    private Node graphic(TreeItem<DbNode> item) {
        return FxTestSupport.call(() -> {
            tree.applyCss();
            tree.layout();
            return tree.lookupAll(".tree-cell").stream().map(node -> (TreeCell<?>) node)
                    .filter(cell -> cell.getTreeItem() == item).map(TreeCell::getGraphic).findFirst().orElse(null);
        });
    }

    private static void fire(ContextMenu menu, String text) {
        MenuItem item = FxTestSupport.call(() -> menu.getItems().stream().filter(candidate -> text.equals(
                candidate.getText())).findFirst().orElseThrow());
        Platform.runLater(item::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private DialogPane waitForDialog(String id) {
        FxTestSupport.waitUntil(() -> dialogs().stream().anyMatch(pane -> pane.lookup("#" + id) != null));
        return dialogs().stream().filter(pane -> pane.lookup("#" + id) != null).findFirst().orElseThrow();
    }

    private static List<DialogPane> dialogs() {
        return FxTestSupport.getDialogPanes();
    }

    private static void press(DialogPane pane, ButtonType type) {
        Platform.runLater(() -> Optional.ofNullable((Button) pane.lookupButton(type)).ifPresent(Button::fire));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void groupsDataSourcesByKind() {
        assertEquals(List.of("Relational databases", "OLAP databases", "Vector databases"), FxTestSupport.call(
                () -> tree.getRoot().getChildren().stream().map(item -> item.getValue().getLabel())
                        .collect(Collectors.toList())));
        assertEquals(List.of("Shop H2"), labels(group("Relational databases")));
        assertEquals(List.of("Lake DuckDB"), labels(group("OLAP databases")));
        assertEquals(List.of("Docs Qdrant"), labels(group("Vector databases")));
        assertEquals(2, FxTestSupport.call(() -> tree.getConnectionItems().size()));
        assertEquals(List.of(extension), tree.getExtensions());

        // Colored icons of the groups and of the databases
        assertTrue(graphic(group("Vector databases")).getStyleClass().contains("db-icon"));
        TreeItem<DbNode> qdrant = FxTestSupport.call(() -> group("Vector databases").getChildren().get(0));
        assertTrue(graphic(qdrant).getStyleClass().contains("db-icon"));
        assertTrue(graphic(FxTestSupport.call(() -> group("OLAP databases").getChildren().get(0))).getStyleClass()
                .contains("db-icon"));

        // A group or a vector database is not a node of the relational data sources
        FxTestSupport.run(() -> tree.getSelectionModel().select(qdrant));
        assertNull(tree.getSelectedNode());
        assertEquals("Docs Qdrant", tree.getSelectedSource().getLabel());
        assertEquals("Qdrant", tree.getSelectedSource().getSource().getTypeName());
        FxTestSupport.run(() -> tree.getSelectionModel().select(group("Relational databases")));
        assertNull(tree.getSelectedNode());
        assertNull(tree.getSelectedSource());
        FxTestSupport.run(tree::refreshSelected);
        assertEquals(List.of("Shop H2"), labels(group("Relational databases")));

        assertTrue(DbIcons.has("PostgreSQL"));
        assertTrue(DbIcons.has("chroma"));
        assertFalse(DbIcons.has("Other"));
        assertFalse(DbIcons.has(null));
        assertNotNull(DbIcons.of("Other"));
        assertNotNull(DbIcons.of(null));
    }

    private List<String> groupLabels() {
        return FxTestSupport.call(() -> tree.getRoot().getChildren().stream().map(item -> item.getValue()
                .getLabel()).collect(Collectors.toList()));
    }

    /**
     * A key which the database refused, e.g. one which is not remembered, is asked and saved
     */
    @Test
    void asksRefusedKey() {
        DataSourceExtension.Source source = FxTestSupport.call(() -> extension.getSources().get(0));
        boolean[] asked = new boolean[1];
        Platform.runLater(() -> asked[0] = source.authenticate(stage, new RestException("Qdrant: 401", 401, null)));
        FxTestSupport.waitUntil(() -> dialogs().stream().anyMatch(pane -> pane.lookup("#credentialsSecretField")
                != null));
        DialogPane pane = dialogs().stream().filter(candidate -> candidate.lookup("#credentialsSecretField")
                != null).findFirst().orElseThrow();
        // An API key has no user
        assertNull(pane.lookup("#credentialsUsernameField"));
        FxTestSupport.run(() -> {
            ((TextField) pane.lookup("#credentialsSecretField")).setText("new-key");
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
        });
        FxTestSupport.waitUntil(() -> asked[0]);
        assertEquals("new-key", store.getProfiles().get(0).getApiKey());
        // Other errors ask nothing
        assertFalse(FxTestSupport.call(() -> source.authenticate(stage, new IllegalStateException("down"))));
    }

    @Test
    void filtersDataSources() {
        TreeItem<DbNode> h2 = FxTestSupport.call(() -> group("Relational databases").getChildren().get(0));
        FxTestSupport.run(() -> h2.setExpanded(true));
        // By name or type, ignoring case, groups without matches are hidden
        FxTestSupport.run(() -> tree.setFilter("  QDRANT "));
        assertEquals(List.of("Vector databases"), groupLabels());
        FxTestSupport.run(() -> tree.setFilter("duckdb"));
        assertEquals(List.of("OLAP databases"), groupLabels());
        assertEquals(List.of("Lake DuckDB"), labels(group("OLAP databases")));
        FxTestSupport.run(() -> tree.setFilter("shop"));
        assertEquals(List.of("Relational databases"), groupLabels());
        FxTestSupport.run(() -> tree.setFilter("nothing"));
        assertEquals(List.of(), groupLabels());
        // The items are kept, an expanded connection stays expanded
        FxTestSupport.run(() -> tree.setFilter(null));
        assertEquals(List.of("Relational databases", "OLAP databases", "Vector databases"), groupLabels());
        assertSame(h2, FxTestSupport.call(() -> group("Relational databases").getChildren().get(0)));
        assertTrue(h2.isExpanded());
        assertEquals("", tree.getFilter());

        // Selecting a hidden data source clears the filter
        FxTestSupport.run(() -> tree.setFilter("qdrant"));
        String h2Id = h2.getValue().getProfile().getId();
        FxTestSupport.run(() -> tree.selectConnection(h2Id));
        assertEquals("", tree.getFilter());
        assertSame(h2, FxTestSupport.call(() -> tree.getSelectionModel().getSelectedItem()));
        FxTestSupport.run(() -> tree.setFilter("duck"));
        String qdrantId = FxTestSupport.call(() -> extension.getSources().get(0).getId());
        FxTestSupport.run(() -> tree.selectSource(qdrantId));
        assertEquals("", tree.getFilter());
        assertEquals("Docs Qdrant", tree.getSelectedSource().getLabel());
        // A visible one keeps it
        FxTestSupport.run(() -> tree.setFilter("docs"));
        FxTestSupport.run(() -> tree.selectSource(qdrantId));
        assertEquals("docs", tree.getFilter());
    }

    @Test
    @SuppressWarnings("unchecked")
    void linksCategoryAndTypes() {
        // A new data source of the relational category, the types follow the category
        Platform.runLater(() -> new ConnectionDialog(stage, context, null, null, List.of(extension)).show());
        DialogPane pane = waitForDialog("categoryCombo");
        ComboBox<Object> categories = (ComboBox<Object>) pane.lookup("#categoryCombo");
        ComboBox<DbType> types = (ComboBox<DbType>) pane.lookup("#dbTypeCombo");
        ComboBox<String> sourceTypes = (ComboBox<String>) pane.lookup("#sourceTypeCombo");
        assertEquals(List.of(DbCategory.RELATIONAL, DbCategory.OLAP, extension), FxTestSupport.call(
                () -> List.copyOf(categories.getItems())));
        assertEquals(DbCategory.RELATIONAL, FxTestSupport.call(categories::getValue));
        assertFalse(FxTestSupport.call(() -> types.getItems().contains(DbType.DUCKDB)));
        FxTestSupport.run(() -> categories.setValue(DbCategory.OLAP));
        assertEquals(List.of(DbType.DUCKDB, DbType.CLICKHOUSE), FxTestSupport.call(() -> List.copyOf(types
                .getItems())));
        // The first type of the category is chosen, the type is never empty
        assertEquals(DbType.DUCKDB, FxTestSupport.call(types::getValue));

        // The vector databases are the types of their category, with their own fields
        FxTestSupport.run(() -> categories.setValue(extension));
        assertTrue(FxTestSupport.call(sourceTypes::isVisible));
        assertFalse(FxTestSupport.call(types::isVisible));
        assertEquals(VdbType.values().length, FxTestSupport.call(() -> sourceTypes.getItems().size()));
        assertNotNull(pane.lookup("#vdbUrlField"));
        FxTestSupport.run(() -> {
            sourceTypes.setValue("Chroma");
            ((TextField) pane.lookup("#nameField")).setText("Docs Chroma");
            // A Chroma without authentication
            ((ComboBox<VdbConnectionForm.Authentication>) pane.lookup("#vdbAuthenticationCombo")).setValue(
                    VdbConnectionForm.Authentication.NONE);
        });
        assertEquals(VdbType.CHROMA.getUrl("localhost", 0), FxTestSupport.call(() -> ((TextField) pane.lookup(
                "#vdbUrlField")).getText()));
        press(pane, ButtonType.OK);
        FxTestSupport.waitUntil(() -> store.getProfiles().stream().anyMatch(profile -> "Docs Chroma".equals(
                profile.getName())));

        // A type of another category chooses its category
        Platform.runLater(() -> new ConnectionDialog(stage, context, null, DbCategory.OLAP, List.of(extension))
                .show());
        DialogPane olap = waitForDialog("categoryCombo");
        ComboBox<Object> olapCategories = (ComboBox<Object>) olap.lookup("#categoryCombo");
        ComboBox<DbType> olapTypes = (ComboBox<DbType>) olap.lookup("#dbTypeCombo");
        assertEquals(DbType.DUCKDB, FxTestSupport.call(olapTypes::getValue));
        FxTestSupport.run(() -> olapTypes.setValue(DbType.POSTGRESQL));
        assertEquals(DbCategory.RELATIONAL, FxTestSupport.call(olapCategories::getValue));
        press(olap, ButtonType.CANCEL);

        assertTrue(extension.getOpenText().startsWith("Import"));
        assertTrue(extension.isEnabled(new Config(new File(dir, "kinds.properties"))));
        assertEquals(1, DataSourceExtension.load(context.getSettingsStore().getConfig()).size());
    }

    @Test
    void managesVectorDatabasesOfTree() throws Exception {
        // A new vector database of the group, the form shows the category of the group
        fire(contextMenu(group("Vector databases")), "New...");
        DialogPane pane = waitForDialog("vdbUrlField");
        FxTestSupport.run(() -> {
            ((TextField) pane.lookup("#nameField")).setText("Docs Chroma");
            @SuppressWarnings("unchecked")
            ComboBox<String> type = (ComboBox<String>) pane.lookup("#sourceTypeCombo");
            type.setValue("Chroma");
            ((TextField) pane.lookup("#vdbApiKeyField")).setText("token");
        });
        press(pane, ButtonType.OK);
        FxTestSupport.waitUntil(() -> labels(group("Vector databases")).contains("Docs Chroma"));
        assertEquals(2, store.getProfiles().size());
        assertEquals("Docs Chroma", tree.getSelectedSource().getLabel());

        // Edited and cancelled, then opened in the import dialog
        TreeItem<DbNode> chroma = FxTestSupport.call(() -> group("Vector databases").getChildren().stream().filter(
                item -> "Docs Chroma".equals(item.getValue().getLabel())).findFirst().orElseThrow());
        fire(contextMenu(chroma), "Edit Data Source");
        press(waitForDialog("vdbUrlField"), ButtonType.CANCEL);
        fire(contextMenu(chroma), "Import into Database...");
        DialogPane importPane = waitForDialog("vdbSourceCombo");
        @SuppressWarnings("unchecked")
        ComboBox<VdbProfile> sources = (ComboBox<VdbProfile>) importPane.lookup("#vdbSourceCombo");
        assertEquals("Docs Chroma", FxTestSupport.call(() -> sources.getValue().getName()));
        FxTestSupport.run(() -> importPane.getScene().getWindow().hide());

        // Deleted after the confirmation
        fire(contextMenu(chroma), "Delete Data Source");
        FxTestSupport.waitUntil(() -> !dialogs().isEmpty());
        press(dialogs().get(0), ButtonType.OK);
        FxTestSupport.waitUntil(() -> !labels(group("Vector databases")).contains("Docs Chroma"));
        assertEquals(1, store.getProfiles().size());

        // A relational data source of its group
        fire(contextMenu(group("Relational databases")), "New...");
        DialogPane form = waitForDialog("dbTypeCombo");
        @SuppressWarnings("unchecked")
        ComboBox<DbType> types = (ComboBox<DbType>) form.lookup("#dbTypeCombo");
        assertFalse(FxTestSupport.call(() -> types.getItems().contains(DbType.DUCKDB)));
        press(form, ButtonType.CANCEL);
        assertTrue(Window.getWindows().stream().noneMatch(window -> window.isShowing() && window != stage));
    }

    @Test
    @SuppressWarnings("unchecked")
    void neverOffersVectorDatabasesAsTargets() {
        // The import of a vector database: data sources only, its + creates relational and OLAP ones only
        Platform.runLater(() -> new VdbImportDialog(stage, context, store).show());
        DialogPane wizard = waitForDialog("vdbTargetCombo");
        ComboBox<Object> targets = (ComboBox<Object>) wizard.lookup("#vdbTargetCombo");
        FxTestSupport.waitUntil(() -> !targets.getItems().isEmpty());
        assertTrue(FxTestSupport.call(() -> targets.getItems().stream().allMatch(item -> item
                instanceof ConnectionProfile && ((ConnectionProfile) item).getDbType() == DbType.DUCKDB)));
        Platform.runLater(() -> ((Button) wizard.lookup("#newTargetButton")).fire());
        DialogPane create = waitForDialog("categoryCombo");
        ComboBox<Object> categories = (ComboBox<Object>) create.lookup("#categoryCombo");
        assertEquals(List.of(DbCategory.RELATIONAL, DbCategory.OLAP), FxTestSupport.call(() -> List.copyOf(
                categories.getItems())));
        press(create, ButtonType.CANCEL);
        press(wizard, ButtonType.CANCEL);
        FxTestSupport.waitUntil(() -> dialogs().isEmpty());

        // The export of tables: data sources only
        Platform.runLater(() -> new com.github.sqljam.face.view.ExportDialog(stage, context, null).show());
        DialogPane export = waitForDialog("targetCombo");
        ComboBox<Object> exportTargets = (ComboBox<Object>) export.lookup("#targetCombo");
        assertTrue(FxTestSupport.call(() -> exportTargets.getItems().stream().allMatch(item -> item
                instanceof ConnectionProfile)));
        assertFalse(FxTestSupport.call(() -> exportTargets.getItems().stream().anyMatch(item -> "Docs Qdrant"
                .equals(((ConnectionProfile) item).getName()))));
        press(export, ButtonType.CANCEL);

        // A vector database is imported into a database, nothing is written into it
        TreeItem<DbNode> qdrant = FxTestSupport.call(() -> group("Vector databases").getChildren().get(0));
        assertEquals(List.of("Import into Database...", "Refresh", "Info", "Edit Data Source", "Delete Data Source"),
                FxTestSupport.call(() -> contextMenu(qdrant).getItems().stream().map(MenuItem::getText).filter(
                        text -> text != null).collect(Collectors.toList())));
    }
}
