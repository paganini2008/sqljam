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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.DbNode;
import com.github.sqljam.face.view.FxTestSupport;
import com.github.sqljam.face.view.SourceItem;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.client.FakeServer;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

/**
 * @Description: VdbSqlTabTest runs SQL on the indexes of a fake Elasticsearch: the first rows of a query, the
 *               errors of the server, an export which reads all rows into a DuckDB file and opens the export with
 *               the name of the index and the note that rows go as a table, and a query with vectors which asks
 *               first and tells that vectors take Import Vectors into Database
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class VdbSqlTabTest {

    @TempDir
    File dir;

    private Stage stage;
    private AppContext context;
    private FakeServer server;
    private VdbSqlTab tab;
    private final AtomicReference<Object[]> exported = new AtomicReference<>();

    @Start
    void start(Stage stage) {
        this.stage = stage;
        FxTestSupport.applyAppStyle();
        stage.setScene(new Scene(new TabPane(), 1000, 700));
        stage.show();
    }

    @AfterEach
    void stop() {
        if (tab != null) {
            FxTestSupport.run(tab::close);
        }
        FxTestSupport.closeAllDialogs();
        if (server != null) {
            server.close();
        }
    }

    private void open(String index, String response) throws Exception {
        context = FxTestSupport.createContext(dir);
        server = new FakeServer();
        server.route("POST /_sql?format=json", body -> body.contains("\"cursor\"")
                ? "{\"rows\": [[\"b\", 2, \"[3,4]\"]]}" : response)
                .route("POST /_sql/close", "{\"succeeded\": true}");
        VdbProfile profile = new VdbProfile();
        profile.setName("Search");
        profile.setType(VdbType.ELASTICSEARCH);
        profile.setUrl(server.getUrl());
        profile.setTimeoutSeconds(5);
        tab = FxTestSupport.call(() -> {
            VdbSqlTab created = new VdbSqlTab(context, profile, 1, index, (node, sql, table, note) -> exported.set(
                    new Object[]{node, sql, table, note}));
            ((TabPane) stage.getScene().getRoot()).getTabs().setAll(created);
            return created;
        });
    }

    /**
     * Text of a label of the tab, nodes in the split pane are in the scene after its skin is created
     */
    private String text(String id) {
        return FxTestSupport.call(() -> {
            tab.getContent().applyCss();
            return ((Label) tab.getContent().lookup("#" + id)).getText();
        });
    }

    private Button button(String id) {
        return (Button) toolBarItem(id);
    }

    /**
     * An item of the tool bar, also when a narrow window puts it into the overflow menu
     */
    private javafx.scene.Node toolBarItem(String id) {
        return FxTestSupport.call(() -> {
            tab.getContent().applyCss();
            return tab.getContent().lookupAll(".tool-bar").stream().flatMap(bar -> ((javafx.scene.control
                    .ToolBar) bar).getItems().stream()).filter(item -> id.equals(item.getId())).findFirst()
                    .orElseThrow();
        });
    }

    @SuppressWarnings("unchecked")
    private TableView<List<String>> results() {
        return FxTestSupport.call(() -> {
            tab.getContent().applyCss();
            return (TableView<List<String>>) tab.getContent().lookup("#sqlResultTable");
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> messages() {
        return FxTestSupport.call(() -> {
            tab.getContent().applyCss();
            return ((ListView<String[]>) tab.getContent().lookup("#sqlMessageList")).getItems().stream()
                    .map(item -> item[0]).toList();
        });
    }

    private static final String DOCS = "{\"columns\": [{\"name\": \"id\", \"type\": \"keyword\"}, {\"name\": \"n\","
            + " \"type\": \"long\"}, {\"name\": \"note\", \"type\": \"text\"}], \"rows\": [[\"a\", 1, \"x\"]],"
            + " \"cursor\": \"c1\"}";

    @Test
    void runsQueriesAndExportsRowsAsTable() throws Exception {
        open("docs", DOCS);
        assertEquals("SELECT * FROM \"docs\"", FxTestSupport.call(() -> tab.getEditor().getText()));
        assertTrue(FxTestSupport.call(() -> ((Label) toolBarItem("vdbSqlHint")).getText()).contains(
                "Import Vectors into Database"));
        assertTrue(FxTestSupport.call(() -> button("sqlExportButton").isDisabled()));
        FxTestSupport.run(() -> tab.run(false));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !results().getItems().isEmpty()));
        assertEquals(List.of("a", "1", "x"), FxTestSupport.call(() -> results().getItems().get(0)));
        assertEquals("First 1 rows, Export reads all of them", text("sqlStatusLabel"));
        // The first page is shown, its cursor is closed
        assertEquals(1, server.requests("POST /_sql/close").size());
        assertEquals("SELECT * FROM \"docs\"", tab.getLastQuery());

        FxTestSupport.run(tab::export);
        FxTestSupport.waitUntil(() -> exported.get() != null);
        DbNode node = (DbNode) exported.get()[0];
        assertEquals(DbType.DUCKDB, node.getProfile().getDbType());
        assertEquals("Search query results", node.getProfile().getName());
        assertTrue(new File(node.getProfile().getDatabase()).isFile());
        assertEquals("SELECT * FROM \"docs\"", exported.get()[1]);
        assertEquals("docs", exported.get()[2]);
        assertTrue(((String) exported.get()[3]).startsWith("The rows of the index are exported as a table"));
        assertEquals("2 rows", text("sqlStatusLabel"));
    }

    @Test
    void asksBeforeExportingVectorsAsText() throws Exception {
        open(null, "{\"columns\": [{\"name\": \"id\", \"type\": \"keyword\"}, {\"name\": \"n\", \"type\": \"long\"},"
                + " {\"name\": \"embedding\", \"type\": \"nested\"}], \"rows\": [[\"a\", 1, [0.5, -1]]]}");
        FxTestSupport.run(() -> tab.getEditor().setText("SELECT * FROM vectors"));
        FxTestSupport.run(() -> tab.run(true));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !results().getItems().isEmpty()));
        FxTestSupport.fireLater(button("sqlExportButton"));
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> pane
                .getContentText() != null && pane.getContentText().contains("Columns embedding hold vectors")));
        // Cancelled, nothing is exported
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        assertNull(exported.get());
        FxTestSupport.fireLater(button("sqlExportButton"));
        FxTestSupport.waitForDialogPane();
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> exported.get() != null);
        assertTrue(((String) exported.get()[3]).contains("Import Vectors into Database"));
        assertEquals("vectors", exported.get()[2]);
    }

    @Test
    void showsErrorsOfTheServer() throws Exception {
        open(null, DOCS);
        server.route("POST /_sql?format=json", 400, "{\"error\": {\"root_cause\": [{\"reason\":"
                + " \"Unknown index [missing]\"}]}}");
        FxTestSupport.run(() -> tab.getEditor().setText("SELECT * FROM missing"));
        FxTestSupport.run(() -> tab.run(false));
        FxTestSupport.waitUntil(() -> "Failed".equals(text("sqlStatusLabel")));
        assertTrue(messages().get(0).contains("Unknown index [missing]"), messages().toString());
        assertTrue(FxTestSupport.call(() -> button("sqlExportButton").isDisabled()));
        // Nothing to run
        FxTestSupport.run(() -> tab.getEditor().setText("  "));
        FxTestSupport.run(() -> tab.run(false));
        assertEquals("No statement to run", text("sqlStatusLabel"));
        FxTestSupport.run(tab::export);
        assertNull(exported.get());
        assertNotNull(tab.getProfile());
        assertFalse(FxTestSupport.call(() -> tab.getText().isEmpty()));
    }

    /**
     * Elasticsearch and OpenSearch sources open SQL editors, other vector databases have none
     */
    @Test
    void sourcesOfIndexesRunSql() throws Exception {
        context = FxTestSupport.createContext(dir);
        VdbProfileStore store = new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME));
        for (VdbType type : VdbType.values()) {
            VdbProfile profile = new VdbProfile();
            profile.setName(type.name());
            profile.setType(type);
            profile.setUrl("http://127.0.0.1:1");
            VdbDataSourceExtension.VdbSourceItem source = new VdbDataSourceExtension.VdbSourceItem(profile, store);
            boolean sql = type == VdbType.ELASTICSEARCH || type == VdbType.OPENSEARCH;
            assertEquals(sql, source.isSqlSupported(), type.name());
            Object opened = FxTestSupport.call(() -> source.openSqlTab(context, 3, new SourceItem(SourceItem.Kind
                    .COLLECTION, List.of("orders"), "Index", true), (node, query, table, note) -> { }));
            assertEquals(sql, opened != null, type.name());
            if (opened != null) {
                assertEquals("SELECT * FROM \"orders\"", FxTestSupport.call(() -> ((VdbSqlTab) opened).getEditor()
                        .getText()));
                FxTestSupport.run(((VdbSqlTab) opened)::close);
            }
        }
    }
}
