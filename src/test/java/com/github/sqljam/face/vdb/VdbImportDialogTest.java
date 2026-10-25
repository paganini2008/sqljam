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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.ColumnMappingDialog;
import com.github.sqljam.face.view.FxTestSupport;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbFieldMapping;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.it.VdbServer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: VdbImportDialogTest imports the Qdrant fixture into DuckDB from the dialog of the vdb line: vector
 *               databases are created, edited and tested, collections and their fields are listed, queries are
 *               previewed, only data sources of databases with vectors are targets, the SQL is shown and the import
 *               runs. The menu item is switched by sqljam.vdb.enabled.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class VdbImportDialogTest {

    static final VdbServer SERVER = VdbServer.QDRANT;

    @TempDir
    File dir;

    private AppContext context;
    private Stage stage;
    private VdbProfileStore store;
    private VdbImportDialog dialog;
    private File duckdb;

    @BeforeAll
    static void createFixture() throws Exception {
        if (SERVER.isAvailable()) {
            SERVER.createFixture();
        }
    }

    @AfterAll
    static void dropFixture() {
        if (SERVER.isAvailable()) {
            SERVER.dropFixture();
        }
    }

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        duckdb = new File(dir, "target.duckdb");
        ConnectionProfile target = new ConnectionProfile();
        target.setName("Target DuckDB");
        target.setDbType(DbType.DUCKDB);
        target.setDatabase(duckdb.getAbsolutePath());
        context.getProfileRegistry().saveProfile(target, true);
        ConnectionProfile h2 = new ConnectionProfile();
        h2.setName("No vectors H2");
        h2.setDbType(DbType.H2);
        h2.setDatabase(new File(dir, "h2/db").getAbsolutePath());
        h2.setUsername("sa");
        h2.setPassword("");
        context.getProfileRegistry().saveProfile(h2, true);
        store = new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME));
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();
    }

    @AfterEach
    void close() {
        if (dialog != null) {
            FxTestSupport.run(dialog::close);
        }
        FxTestSupport.closeAllDialogs();
    }

    static VdbProfile profile() {
        VdbProfile profile = new VdbProfile();
        profile.setName("Qdrant of the tests");
        profile.setType(VdbType.QDRANT);
        profile.setUrl(SERVER.getSource().getUrl());
        profile.setApiKey(SERVER.getSource().getApiKey());
        return profile;
    }

    @SuppressWarnings("unchecked")
    private <T extends Node> T node(String id) {
        return (T) dialog.getDialogPane().lookup("#" + id);
    }

    private String status() {
        return FxTestSupport.call(() -> ((Label) node("vdbStatus")).getText());
    }

    private boolean statusIs(String styleClass) {
        return ((Label) node("vdbStatus")).getStyleClass().contains(styleClass);
    }

    /**
     * Dialog pane of an alert above the import dialog
     */
    private DialogPane waitForAlert() {
        FxTestSupport.waitUntil(() -> alerts().size() > 0);
        return alerts().get(0);
    }

    private List<DialogPane> alerts() {
        return FxTestSupport.getDialogPanes().stream().filter(pane -> pane != dialog.getDialogPane())
                .collect(Collectors.toList());
    }

    private void fireAlert(DialogPane pane, ButtonType type) {
        Platform.runLater(() -> Optional.ofNullable((Button) pane.lookupButton(type)).ifPresent(Button::fire));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void open() {
        dialog = FxTestSupport.call(() -> {
            VdbImportDialog importDialog = new VdbImportDialog(stage, context, store);
            importDialog.show();
            return importDialog;
        });
    }

    @SuppressWarnings("unchecked")
    private <T extends Node> T node(Node root, String id) {
        return (T) root.lookup("#" + id);
    }

    private static long count(File file, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:" + file.getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM main." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String text(Node content, String id) {
        return FxTestSupport.call(() -> ((Label) node(content, id)).getText());
    }

    private boolean disabled(Node content, String id) {
        return FxTestSupport.call(() -> node(content, id).isDisabled());
    }

    private static List<String> column(TableView<List<String>> data, String name) {
        return FxTestSupport.call(() -> {
            TableColumn<List<String>, ?> column = data.getColumns().stream().filter(candidate -> candidate
                    .getText().equals(name)).findFirst().orElseThrow();
            return data.getItems().stream().map(row -> (String) column.getCellData(row)).collect(Collectors
                    .toList());
        });
    }

    /**
     * Records page by page, by the cursor of the database, and sorted on the page by their headers
     */
    @Test
    @SuppressWarnings("unchecked")
    void pagesAndSortsRecords() {
        assumeTrue(SERVER.isAvailable());
        VdbCollectionTab tab = FxTestSupport.call(() -> {
            VdbCollectionTab created = new VdbCollectionTab(profile(), SERVER.getCollection());
            stage.setScene(new Scene(new TabPane(created), 1200, 700));
            return created;
        });
        Node content = tab.getContent();
        FxTestSupport.run(() -> ((TabPane) content).getSelectionModel().select(1));
        TableView<List<String>> data = node(content, "vdbDataTable");
        FxTestSupport.waitUntil(() -> data.getItems().size() == VdbServer.RECORDS);
        assertEquals("Page 1 / 1", text(content, "vdbPageLabel"));
        assertTrue(disabled(content, "vdbNextButton") && disabled(content, "vdbPreviousButton"));

        // Three records a page
        FxTestSupport.run(() -> ((ComboBox<Integer>) node(content, "vdbLimitCombo")).setValue(3));
        FxTestSupport.waitUntil(() -> data.getItems().size() == 3 && "Page 1 / 3".equals(text(content,
                "vdbPageLabel")));
        assertTrue(text(content, "vdbTotalLabel").startsWith("Records 1 to 3 of 8"));
        List<String> first = column(data, "n");
        FxTestSupport.fireLater((Button) node(content, "vdbNextButton"));
        FxTestSupport.waitUntil(() -> "Page 2 / 3".equals(text(content, "vdbPageLabel")) && !disabled(content,
                "vdbRefreshButton"));
        assertTrue(text(content, "vdbTotalLabel").startsWith("Records 4 to 6 of 8"));
        List<String> second = column(data, "n");
        assertTrue(second.stream().noneMatch(first::contains), first + " " + second);
        FxTestSupport.fireLater((Button) node(content, "vdbLastButton"));
        FxTestSupport.waitUntil(() -> "Page 3 / 3".equals(text(content, "vdbPageLabel")) && !disabled(content,
                "vdbRefreshButton"));
        assertEquals(2, data.getItems().size());
        assertTrue(disabled(content, "vdbNextButton") && disabled(content, "vdbLastButton"));
        assertTrue(text(content, "vdbTotalLabel").startsWith("Records 7 to 8 of 8"));
        FxTestSupport.fireLater((Button) node(content, "vdbPreviousButton"));
        FxTestSupport.waitUntil(() -> "Page 2 / 3".equals(text(content, "vdbPageLabel")) && !disabled(content,
                "vdbRefreshButton"));
        FxTestSupport.fireLater((Button) node(content, "vdbRefreshButton"));
        FxTestSupport.waitUntil(() -> "Page 2 / 3".equals(text(content, "vdbPageLabel")) && !disabled(content,
                "vdbRefreshButton"));
        assertEquals(second, column(data, "n"));
        FxTestSupport.fireLater((Button) node(content, "vdbFirstButton"));
        FxTestSupport.waitUntil(() -> "Page 1 / 3".equals(text(content, "vdbPageLabel")) && !disabled(content,
                "vdbRefreshButton"));
        assertEquals(first, column(data, "n"));
        assertEquals(3, tab.getLastPage());

        // A header sorts the page by numbers, the third click shows the order of the database again
        FxTestSupport.run(() -> {
            TableColumn<List<String>, ?> n = data.getColumns().stream().filter(column -> column.getText()
                    .equals("n")).findFirst().orElseThrow();
            n.setSortType(TableColumn.SortType.DESCENDING);
            data.getSortOrder().setAll(n);
        });
        List<String> descending = new java.util.ArrayList<>(first);
        descending.sort(Comparator.comparing(Integer::valueOf, Comparator.reverseOrder()));
        assertEquals(descending, column(data, "n"));
        FxTestSupport.run(() -> data.getSortOrder().clear());
        assertEquals(first, column(data, "n"));

        // The nearest neighbors are one page
        FxTestSupport.run(() -> ((TextField) node(content, "vdbNearestField")).setText("2"));
        FxTestSupport.fireLater((Button) node(content, "vdbApplyButton"));
        FxTestSupport.waitUntil(() -> "Page 1 / 1".equals(text(content, "vdbPageLabel")) && !disabled(content,
                "vdbRefreshButton"));
        assertTrue(disabled(content, "vdbNextButton"));
    }

    /**
     * The fields and the records of a collection in its tab, the records of a query are imported by the wizard
     */
    @Test
    @SuppressWarnings("unchecked")
    void browsesCollectionAndImportsQuery() throws Exception {
        assumeTrue(SERVER.isAvailable());
        VdbProfile profile = profile();
        store.saveProfile(profile, true);
        VdbCollectionTab tab = FxTestSupport.call(() -> {
            VdbCollectionTab created = new VdbCollectionTab(profile, SERVER.getCollection());
            TabPane tabs = new TabPane(created);
            stage.setScene(new Scene(tabs, 1200, 700));
            return created;
        });
        Node content = tab.getContent();
        // The data tab chosen before the fields are read reads its records when they are
        FxTestSupport.run(() -> ((TabPane) content).getSelectionModel().select(1));
        TableView<List<String>> early = node(content, "vdbDataTable");
        FxTestSupport.waitUntil(() -> early.getItems().size() == VdbServer.RECORDS);
        FxTestSupport.run(() -> ((TabPane) content).getSelectionModel().select(0));
        TableView<VdbField> fields = node(content, "vdbFieldTable");
        FxTestSupport.waitUntil(() -> !fields.getItems().isEmpty());
        assertEquals("VECTOR FLOAT32(1024)", VdbCollectionTab.describeType(tab.getCollection().findField(
                "embedding").get()));
        // Qdrant has no partitions
        assertFalse(node(content, "vdbPartitionsButton").isVisible());

        // The records of the data tab, narrowed by conditions, a native filter or a search
        FxTestSupport.run(() -> ((TabPane) content).getSelectionModel().select(1));
        TableView<List<String>> data = node(content, "vdbDataTable");
        FxTestSupport.waitUntil(() -> data.getItems().size() == VdbServer.RECORDS);
        assertTrue(FxTestSupport.call(() -> ((Label) node(content, "vdbTotalLabel")).getText()).startsWith(
                "Records 1 to 8 of 8"));
        TextField filter = node(content, "vdbFilterField");
        Button apply = node(content, "vdbApplyButton");
        FxTestSupport.run(() -> filter.setText("n >= 6"));
        FxTestSupport.fireLater(apply);
        FxTestSupport.waitUntil(() -> data.getItems().size() == 3);
        ComboBox<String> filterType = node(content, "vdbFilterTypeCombo");
        FxTestSupport.run(() -> {
            filterType.getSelectionModel().select(1);
            filter.setText("{\"must\": [{\"key\": \"n\", \"match\": {\"value\": 2}}]}");
        });
        FxTestSupport.fireLater(apply);
        FxTestSupport.waitUntil(() -> data.getItems().size() == 1);
        FxTestSupport.run(() -> {
            filter.clear();
            ((TextField) node(content, "vdbNearestField")).setText("[1, 2");
        });
        FxTestSupport.fireLater(apply);
        FxTestSupport.waitUntil(() -> node(content, "vdbQueryErrorLabel").isVisible());
        FxTestSupport.run(() -> ((TextField) node(content, "vdbNearestField")).setText("2"));
        FxTestSupport.fireLater(apply);
        FxTestSupport.waitUntil(() -> !node(content, "vdbQueryErrorLabel").isVisible() && data.getItems().size()
                > 0 && data.getItems().size() < VdbServer.RECORDS);
        FxTestSupport.fireLater((Button) node(content, "vdbClearButton"));
        FxTestSupport.waitUntil(() -> data.getItems().size() == VdbServer.RECORDS);
        FxTestSupport.run(() -> {
            filterType.getSelectionModel().select(0);
            filter.setText("n >= 6");
        });
        FxTestSupport.fireLater(apply);
        FxTestSupport.waitUntil(() -> data.getItems().size() == 3);

        // Import... opens the wizard of the collection and of the query
        String[] importedCollection = new String[1];
        VdbQuery[] importedQuery = new VdbQuery[1];
        tab.setOnImport((collection, query) -> {
            importedCollection[0] = collection;
            importedQuery[0] = query;
        });
        FxTestSupport.fireLater((Button) node(content, "vdbImportButton"));
        assertEquals(SERVER.getCollection(), importedCollection[0]);
        assertEquals(1, importedQuery[0].getConditions().size());

        dialog = FxTestSupport.call(() -> new VdbImportDialog(stage, context, store, profile, null,
                List.of(importedCollection[0]), importedQuery[0]));
        Platform.runLater(dialog::showAndImport);
        WaitForAsyncUtils.waitForFxEvents();
        TableView<VdbImportDialog.CollectionItem> collections = node("vdbCollections");
        FxTestSupport.waitUntil(() -> collections.getItems().stream().anyMatch(item -> item.selected.get()));
        assertTrue(FxTestSupport.call(() -> ((Label) node("vdbQueryLabel")).getText()).contains("n GE 6"));
        assertFalse(node("vdbDatabaseCombo").isVisible());
        // Only data sources with vectors are targets, they are probed in the background
        ComboBox<ConnectionProfile> targets = node("vdbTargetCombo");
        FxTestSupport.waitUntil(() -> !targets.getItems().isEmpty());
        assertEquals(List.of("Target DuckDB"), FxTestSupport.call(() -> targets.getItems().stream()
                .map(ConnectionProfile::getName).collect(Collectors.toList())));
        Button start = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        FxTestSupport.run(() -> targets.setValue(targets.getItems().get(0)));
        FxTestSupport.waitUntil(() -> statusIs("status-success") && status().contains("DuckDB"));
        assertFalse(start.isDisable());
        FxTestSupport.run(() -> {
            collections.getItems().stream().filter(item -> item.selected.get()).forEach(item -> item.tableName
                    .set("sjvt_ui"));
        });

        // The SQL of the tables
        Button showSql = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes()
                .get(0));
        FxTestSupport.fireLater(showSql);
        DialogPane sql = waitForAlert();
        String scripts = FxTestSupport.call(() -> ((TextArea) sql.getContent()).getText());
        assertTrue(scripts.contains("CREATE TABLE \"main\".\"sjvt_ui\""), scripts);
        fireAlert(sql, ButtonType.OK);
        FxTestSupport.waitUntil(() -> alerts().isEmpty());

        // The import, after the confirmation of the drops, runs in the progress dialog
        FxTestSupport.run(() -> ((CheckBox) node("vdbAllCheck")).fire());
        FxTestSupport.run(() -> ((CheckBox) node("vdbAllCheck")).fire());
        FxTestSupport.run(() -> collections.getItems().forEach(item -> item.selected.set(item.name.equals(
                SERVER.getCollection()))));
        FxTestSupport.fireLater(start);
        fireAlert(waitForAlert(), ButtonType.OK);
        FxTestSupport.waitUntil(() -> {
            try {
                return count(duckdb, "sjvt_ui") == 3;
            } catch (Exception e) {
                return false;
            }
        });
        FxTestSupport.waitUntil(() -> Window.getWindows().stream().anyMatch(window -> window.isShowing()
                && window.getScene() != null && window.getScene().getRoot().lookup("#progressStatus") != null));
        dialog = null;

        // The report of the import is in the history: the collection is its table, its rows are counted
        FxTestSupport.waitUntil(() -> !context.getReportStore().list().isEmpty());
        TransferReport report = context.getReportStore().list().get(0);
        assertEquals(TransferReport.Kind.VECTOR_IMPORT, report.getKind());
        assertEquals(TransferReport.Status.COMPLETED, report.getStatus(), report.getFailure());
        assertTrue(report.getSource().startsWith(profile.getName() + " (QDRANT)"), report.getSource());
        assertTrue(report.getTarget().startsWith("Target DuckDB"), report.getTarget());
        assertEquals("true", report.getOptions().get("Recreate tables"));
        TransferReport.TableReport table = report.getTables().stream().filter(item -> "sjvt_ui".equals(item
                .getName())).findFirst().orElseThrow();
        assertEquals(3, table.getWritten());
        assertEquals(3L, table.getTargetRows());
        assertTrue(table.getVerified());
    }

    /**
     * Fields of the checked collections are mapped, the id is always imported, the mappings go with the import
     */
    @Test
    @SuppressWarnings("unchecked")
    void mapsFieldsOfCollections() throws Exception {
        assumeTrue(SERVER.isAvailable());
        VdbProfile profile = profile();
        store.saveProfile(profile, true);
        dialog = FxTestSupport.call(() -> {
            VdbImportDialog created = new VdbImportDialog(stage, context, store, profile, null, List.of(), null);
            created.show();
            return created;
        });
        TableView<VdbImportDialog.CollectionItem> collections = node("vdbCollections");
        FxTestSupport.waitUntil(() -> !collections.getItems().isEmpty());
        // Nothing is checked
        FxTestSupport.run(() -> ((Button) node("vdbMapColumnsButton")).fire());
        assertTrue(status().contains("Check the collections"), status());
        FxTestSupport.run(() -> collections.getItems().stream().filter(item -> item.name.equals(SERVER
                .getCollection())).forEach(item -> item.selected.set(true)));
        FxTestSupport.fireLater((Button) node("vdbMapColumnsButton"));
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> pane.lookup(
                "#mappingColumnTable") != null));
        DialogPane mapping = FxTestSupport.getDialogPanes().stream().filter(pane -> pane.lookup(
                "#mappingColumnTable") != null).findFirst().orElseThrow();
        TableView<ColumnMappingDialog.Row> fields = (TableView<ColumnMappingDialog.Row>) mapping.lookup(
                "#mappingColumnTable");
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !fields.getItems().isEmpty()));
        assertTrue(FxTestSupport.call(() -> fields.getItems().stream().anyMatch(row -> row.getColumn().isRequired())));
        FxTestSupport.run(() -> {
            fields.getItems().stream().filter(row -> row.getColumn().getName().equals("title")).forEach(row -> row
                    .targetNameProperty().set("headline"));
            fields.getItems().stream().filter(row -> row.getColumn().getName().equals("tags")).forEach(row -> row
                    .importedProperty().set(false));
            ((Button) mapping.lookupButton(ButtonType.OK)).fire();
        });
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> ((Label) node("vdbMappingLabel")).getText().equals(
                "2 columns are mapped")));
        VdbExportConfiguration configuration = FxTestSupport.call(dialog::buildConfiguration);
        assertEquals(new VdbFieldMapping("headline", null, false), configuration.getMappings().get(0).getFields()
                .get("title"));
        assertEquals(VdbFieldMapping.skip(), configuration.getMappings().get(0).getFields().get("tags"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void editsVectorDatabases() throws Exception {
        open();
        ComboBox<VdbProfile> sources = node("vdbSourceCombo");
        assertTrue(FxTestSupport.call(() -> sources.getItems().isEmpty()));
        VdbConnectionDialog connectionDialog = FxTestSupport.call(() -> {
            VdbConnectionDialog created = new VdbConnectionDialog(stage, null, true);
            created.show();
            return created;
        });
        DialogPane pane = connectionDialog.getDialogPane();
        ComboBox<VdbType> type = (ComboBox<VdbType>) pane.lookup("#vdbTypeCombo");
        TextField url = (TextField) pane.lookup("#vdbUrlField");
        assertEquals(VdbType.QDRANT.getUrl("localhost", 0), FxTestSupport.call(url::getText));
        // The url of the default port follows the type
        FxTestSupport.run(() -> type.setValue(VdbType.CHROMA));
        assertEquals(VdbType.CHROMA.getUrl("localhost", 0), FxTestSupport.call(url::getText));
        assertTrue(pane.lookup("#vdbTenantField").isVisible());
        assertTrue(pane.lookup("#vdbDatabaseField").isVisible());
        FxTestSupport.run(() -> type.setValue(VdbType.QDRANT));
        assertFalse(pane.lookup("#vdbTenantField").isVisible());

        // An url which is not of HTTP is refused
        FxTestSupport.run(() -> url.setText("ftp://x"));
        FxTestSupport.fireLater((Button) pane.lookup("#vdbTestButton"));
        Label status = (Label) pane.lookup("#vdbStatusLabel");
        FxTestSupport.waitUntil(() -> status.getStyleClass().contains("status-error"));
        Button ok = (Button) pane.lookupButton(ButtonType.OK);
        FxTestSupport.fireLater(ok);
        assertTrue(FxTestSupport.call(connectionDialog::isShowing));

        // A wrong key fails, the right key connects
        if (SERVER.isAvailable()) {
            FxTestSupport.run(() -> {
                url.setText(SERVER.getSource().getUrl() + "/");
                ((TextField) pane.lookup("#vdbApiKeyField")).setText("wrong");
            });
            FxTestSupport.fireLater((Button) pane.lookup("#vdbTestButton"));
            FxTestSupport.waitUntil(() -> status.getStyleClass().contains("status-error") && status.getText()
                    .contains("40"));
            FxTestSupport.run(() -> ((TextField) pane.lookup("#vdbApiKeyField")).setText(SERVER.getSource()
                    .getApiKey()));
            FxTestSupport.fireLater((Button) pane.lookup("#vdbTestButton"));
            FxTestSupport.waitUntil(() -> status.getStyleClass().contains("status-success"));
        }
        FxTestSupport.run(() -> {
            ((CheckBox) pane.lookup("#vdbRememberCheck")).setSelected(false);
            ((CheckBox) pane.lookup("#vdbTrustCheck")).setSelected(true);
            // Qdrant has no users, a username of another authentication is not kept
            ((TextField) pane.lookup("#vdbUsernameField")).setText(" user ");
        });
        VdbProfile read = FxTestSupport.call(connectionDialog::read);
        assertFalse(connectionDialog.isSecretRemembered());
        assertTrue(read.getName().startsWith("Qdrant "), read.getName());
        assertFalse(read.getUrl().endsWith("/"));
        assertNull(read.getUsername());
        assertTrue(read.isCertificateTrusted());
        assertNull(read.getTenant());
        FxTestSupport.run(connectionDialog::close);

        // Secrets which are not remembered are kept for the session only
        store.saveProfile(read, false);
        VdbProfileStore reloaded = new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME));
        assertNull(reloaded.getProfiles().get(0).getApiKey());
        assertEquals(read.getApiKey(), store.getProfiles().get(0).getApiKey());
        assertFalse(store.isSecretRemembered(read.getId()));
        assertTrue(store.isSecretRemembered("missing"));
        store.saveProfile(read, true);
        assertEquals(read.getApiKey(), new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME)).getProfiles()
                .get(0).getApiKey());
        // Secrets are never in the text of a profile, whether the server of the tests is reachable or not
        VdbProfile secret = read.copy();
        secret.setApiKey("sj-api-key-secret");
        secret.setPassword("sj-password-secret");
        assertFalse(secret.toString().contains("sj-api-key-secret") || secret.toString().contains(
                "sj-password-secret"), secret.toString());
        assertEquals(read, read.copy());
        assertEquals(read.hashCode(), read.copy().hashCode());
        store.removeProfile(read.getId());
        assertTrue(store.getProfiles().isEmpty());
    }

    @Test
    void switchesMenuItem() {
        VdbMenuExtension extension = new VdbMenuExtension();
        Config config = new Config(new File(dir, "switch.properties"));
        assertTrue(extension.isEnabled(config));
        config.set(VdbMenuExtension.ENABLED_KEY, "false");
        assertFalse(extension.isEnabled(config));
        assertEquals("vdbImportMenuItem", extension.getId());
        assertTrue(extension.getText().contains("Vector"));
        Platform.runLater(() -> extension.show(stage, context));
        FxTestSupport.waitUntil(() -> Window.getWindows().stream().anyMatch(window -> window.getScene() != null
                && window.getScene().getRoot() instanceof DialogPane && ((DialogPane) window.getScene().getRoot())
                .lookup("#vdbSourceCombo") != null));
        FxTestSupport.closeAllDialogs();
    }

    /**
     * The authentications follow the type: an API key or a user and a password, only the fields of the chosen one
     * are shown and kept
     */
    @Test
    void choosesAuthenticationOfType() {
        VdbConnectionForm form = FxTestSupport.call(() -> new VdbConnectionForm(110));
        TextField apiKey = (TextField) form.lookup("#vdbApiKeyField");
        TextField username = (TextField) form.lookup("#vdbUsernameField");
        TextField database = (TextField) form.lookup("#vdbDatabaseField");
        TextField tenant = (TextField) form.lookup("#vdbTenantField");
        // Qdrant: an API key, no users, databases or tenants
        assertEquals(List.of(VdbConnectionForm.Authentication.NONE, VdbConnectionForm.Authentication.API_KEY),
                VdbConnectionForm.getAuthentications(VdbType.QDRANT));
        assertEquals(VdbConnectionForm.Authentication.API_KEY, form.getAuthentication());
        assertTrue(apiKey.isVisible());
        assertFalse(username.isVisible());
        assertFalse(database.isVisible());
        assertFalse(tenant.isVisible());
        FxTestSupport.run(() -> form.setProfile(null, true));
        assertEquals("API key is required, or choose None", FxTestSupport.call(form::validate));
        // OpenSearch: users only, the authentication changes with the type
        FxTestSupport.run(() -> form.setType(VdbType.OPENSEARCH));
        assertEquals(VdbConnectionForm.Authentication.USER_PASSWORD, form.getAuthentication());
        assertFalse(apiKey.isVisible());
        assertTrue(username.isVisible());
        assertEquals("Username is required, or choose None", FxTestSupport.call(form::validate));
        // Elasticsearch has both, the chosen one is kept
        FxTestSupport.run(() -> form.setType(VdbType.ELASTICSEARCH));
        assertEquals(VdbConnectionForm.Authentication.USER_PASSWORD, form.getAuthentication());
        FxTestSupport.run(() -> {
            username.setText("elastic");
            apiKey.setText("hidden key");
        });
        VdbProfile user = FxTestSupport.call(() -> form.read(null));
        assertEquals("elastic", user.getUsername());
        assertNull(user.getApiKey());
        FxTestSupport.run(() -> form.setAuthentication(VdbConnectionForm.Authentication.API_KEY));
        VdbProfile key = FxTestSupport.call(() -> form.read(null));
        assertEquals("hidden key", key.getApiKey());
        assertNull(key.getUsername());
        // None keeps no credentials, the remember box is hidden
        FxTestSupport.run(() -> form.setAuthentication(VdbConnectionForm.Authentication.NONE));
        assertNull(FxTestSupport.call(form::validate));
        VdbProfile none = FxTestSupport.call(() -> form.read(null));
        assertFalse(none.hasSecret());
        assertNull(none.getUsername());
        assertFalse(form.lookup("#vdbRememberCheck").isVisible());
        // Chroma has databases and tenants, Milvus databases
        FxTestSupport.run(() -> form.setType(VdbType.CHROMA));
        assertTrue(database.isVisible());
        assertTrue(tenant.isVisible());
        FxTestSupport.run(() -> form.setType(VdbType.MILVUS));
        assertTrue(database.isVisible());
        assertFalse(tenant.isVisible());

        // A saved database shows the authentication of its credentials
        VdbProfile milvus = new VdbProfile();
        milvus.setType(VdbType.MILVUS);
        milvus.setUrl("http://h:19530");
        milvus.setUsername("root");
        FxTestSupport.run(() -> form.setProfile(milvus, true));
        assertEquals(VdbConnectionForm.Authentication.USER_PASSWORD, form.getAuthentication());
        milvus.setUsername(null);
        milvus.setApiKey("token");
        FxTestSupport.run(() -> form.setProfile(milvus, true));
        assertEquals(VdbConnectionForm.Authentication.API_KEY, form.getAuthentication());
        milvus.setApiKey(null);
        FxTestSupport.run(() -> form.setProfile(milvus, true));
        assertEquals(VdbConnectionForm.Authentication.NONE, form.getAuthentication());
        // Its key which is kept for the session only shows the API key
        FxTestSupport.run(() -> form.setProfile(milvus, false));
        assertEquals(VdbConnectionForm.Authentication.API_KEY, form.getAuthentication());
    }
}
