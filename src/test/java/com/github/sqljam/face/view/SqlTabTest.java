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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.stage.Stage;

/**
 * @Description: SqlTabTest verifies the SQL editor: a query of the selected table, the statement at the caret,
 *               selected text, all statements, an error which stops the rest, first rows of a large result, a
 *               cancelled statement, export of the last query, the session of the editor and highlighting
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class SqlTabTest {

    @TempDir
    File dir;

    private AppContext context;
    private ConnectionProfile profile;
    private SqlTab tab;

    @Start
    void start(Stage stage) throws Exception {
        context = FxTestSupport.createContext(dir);
        profile = UiDatabase.create(dir, "Embedded H2");
        context.getProfileRegistry().saveProfile(profile, true);
        TabPane tabPane = new TabPane();
        stage.setScene(new Scene(tabPane, 900, 600));
        stage.show();
    }

    @AfterEach
    void close() {
        if (tab != null) {
            tab.close();
        }
        context.getSessionManager().closeAll();
    }

    private SqlTab open(DbNode node) {
        tab = FxTestSupport.call(() -> {
            SqlTab sqlTab = new SqlTab(context, node, 1);
            TabPane tabPane = (TabPane) FxTestSupport.call(() -> javafx.stage.Window.getWindows().get(0).getScene()
                    .getRoot());
            tabPane.getTabs().setAll(sqlTab);
            return sqlTab;
        });
        return tab;
    }

    private DbNode schemaNode() {
        return DbNode.schema(profile, UiDatabase.CATALOG, UiDatabase.SCHEMA);
    }

    private void type(String text, int caret) {
        FxTestSupport.run(() -> {
            tab.getEditor().setText(text);
            tab.getEditor().getCodeArea().moveTo(caret);
        });
    }

    private void runAndWait(boolean all) {
        FxTestSupport.run(() -> tab.run(all));
        FxTestSupport.waitUntil(() -> !tab.isBusy());
    }

    @SuppressWarnings("unchecked")
    private TableView<List<String>> results() {
        return (TableView<List<String>>) tab.getContent().lookup("#sqlResultTable");
    }

    /**
     * Node of the tab, items of the tool bar are in the scene after its skin is created
     */
    private javafx.scene.Node node(String id) {
        return FxTestSupport.call(() -> {
            tab.getContent().applyCss();
            return tab.getContent().lookup("#" + id);
        });
    }

    private String status() {
        return FxTestSupport.call(() -> ((Label) tab.getContent().lookup("#sqlStatusLabel")).getText());
    }

    @SuppressWarnings("unchecked")
    private List<String> messages() {
        return FxTestSupport.call(() -> ((ListView<Object>) tab.getContent().lookup("#sqlMessageList")).getItems()
                .stream().map(item -> {
                    try {
                        Field field = item.getClass().getDeclaredField("message");
                        field.setAccessible(true);
                        return (String) field.get(item);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }).collect(Collectors.toList()));
    }

    private List<String> columns() {
        return FxTestSupport.call(() -> results().getColumns().stream().map(column -> column.getText())
                .collect(Collectors.toList()));
    }

    @Test
    void queriesSelectedTable() {
        TableInfo table = new TableInfo();
        table.setCatalog(UiDatabase.CATALOG);
        table.setSchema(UiDatabase.SCHEMA);
        table.setName("T_ONE");
        open(DbNode.table(profile, table));
        assertEquals("SELECT * FROM PUBLIC.T_ONE", FxTestSupport.call(() -> tab.getEditor().getText()));
        assertTrue(FxTestSupport.call(() -> tab.getText()).startsWith("SQL 1: Embedded H2"));
        Button export = (Button) node("sqlExportButton");
        assertTrue(FxTestSupport.call(export::isDisabled));
        runAndWait(false);
        assertEquals(List.of("ID", "NAME"), columns());
        assertEquals(5, FxTestSupport.call(() -> results().getItems().size()));
        assertEquals("5 rows", status());
        assertTrue(messages().get(0).startsWith("5 rows in "), messages().toString());
        assertFalse(FxTestSupport.call(export::isDisabled));
        assertEquals("SELECT * FROM PUBLIC.T_ONE", tab.getLastQuery());
    }

    @Test
    void runsStatementAtCaretOrSelection() {
        open(schemaNode());
        String sql = "SELECT 1 AS A;\nSELECT 2 AS B, 3 AS B;\n";
        type(sql, sql.indexOf("2 AS"));
        runAndWait(false);
        // Columns of the same name are numbered
        assertEquals(List.of("B", "B_2"), columns());
        // Only the selected text runs
        FxTestSupport.run(() -> tab.getEditor().getCodeArea().selectRange(0, sql.indexOf(';')));
        runAndWait(false);
        assertEquals(List.of("A"), columns());
        // Nothing to run
        type("  -- only a comment", 3);
        runAndWait(false);
        assertEquals("No statement to run", status());
    }

    /**
     * Statements run one after another in the session of the editor, a local temporary table stays between runs
     */
    @Test
    void runsAllStatementsInOneSession() {
        open(schemaNode());
        type("CREATE LOCAL TEMPORARY TABLE TMP_SQL (ID INT);\nINSERT INTO TMP_SQL VALUES (1), (2);\n"
                + "UPDATE TMP_SQL SET ID = ID + 10;", 0);
        runAndWait(true);
        List<String> messages = messages();
        assertEquals(3, messages.size(), messages.toString());
        assertTrue(messages.get(1).startsWith("2 rows changed in "), messages.toString());
        assertEquals("3 statements done", status());
        assertTrue(tab.getLastQuery() == null);
        type("SELECT ID FROM TMP_SQL ORDER BY ID", 0);
        runAndWait(false);
        assertEquals(List.of("11"), FxTestSupport.call(() -> results().getItems().get(0)));
    }

    @Test
    void stopsAtFirstError() {
        open(schemaNode());
        type("SELECT 1 AS ONE; SELECT MISSING FROM T_ONE; SELECT 3 AS THREE", 0);
        runAndWait(true);
        assertEquals("Failed", status());
        List<String> messages = messages();
        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.get(1).startsWith("Error in SELECT MISSING FROM T_ONE: "), messages.toString());
        // The result of the statement before the error is kept
        assertEquals(List.of("ONE"), columns());
        assertEquals("SELECT 1 AS ONE", tab.getLastQuery());
    }

    @Test
    void showsFirstRowsOfLargeResults() {
        open(schemaNode());
        type("SELECT X FROM SYSTEM_RANGE(1, " + (SqlTab.MAX_ROWS + 100) + ")", 0);
        runAndWait(false);
        assertEquals(SqlTab.MAX_ROWS, FxTestSupport.call(() -> results().getItems().size()));
        assertEquals("First " + SqlTab.MAX_ROWS + " rows, Export reads all of them", status());
        assertTrue(messages().get(0).startsWith("First " + SqlTab.MAX_ROWS + " rows in "));
    }

    @Test
    void cancelsRunningStatement() {
        open(schemaNode());
        type("SELECT COUNT(*) FROM SYSTEM_RANGE(1, 100000) a, SYSTEM_RANGE(1, 100000) b", 0);
        FxTestSupport.run(() -> tab.run(false));
        Button stop = (Button) node("sqlStopButton");
        FxTestSupport.waitUntil(() -> !FxTestSupport.call(stop::isDisabled));
        // Running twice does nothing
        FxTestSupport.run(() -> tab.run(true));
        FxTestSupport.fireLater(stop);
        FxTestSupport.waitUntil(() -> !tab.isBusy());
        assertEquals("Cancelled", status());
    }

    @Test
    void exportsLastQuery() {
        open(schemaNode());
        AtomicReference<DbNode> node = new AtomicReference<>();
        AtomicReference<String> sql = new AtomicReference<>();
        FxTestSupport.run(() -> tab.setOnExport((target, query) -> {
            node.set(target);
            sql.set(query);
        }));
        FxTestSupport.run(tab::export);
        assertNull(sql.get());
        type("SELECT ID, NAME FROM T_ONE WHERE ID > 2", 0);
        runAndWait(false);
        FxTestSupport.fireLater((Button) node("sqlExportButton"));
        FxTestSupport.waitUntil(() -> sql.get() != null);
        assertEquals("SELECT ID, NAME FROM T_ONE WHERE ID > 2", sql.get());
        assertEquals(UiDatabase.SCHEMA, node.get().getSchema());
        assertEquals(DbNode.Kind.SCHEMA, node.get().getKind());
    }

    @Test
    void highlightsSyntax() {
        String sql = "select name, 'it''s' AS \"Label\" -- note\nFROM t WHERE id = 42 /* c */";
        StyleSpans<Collection<String>> spans = SqlEditor.highlight(sql);
        StringBuilder styled = new StringBuilder();
        int position = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (!span.getStyle().isEmpty()) {
                styled.append(span.getStyle().iterator().next()).append('=')
                        .append(sql, position, position + span.getLength()).append(';');
            }
            position += span.getLength();
        }
        assertEquals("sql-keyword=select;sql-string='it''s';sql-keyword=AS;sql-name=\"Label\";"
                + "sql-comment=-- note;sql-keyword=FROM;sql-keyword=WHERE;sql-number=42;sql-comment=/* c */;",
                styled.toString());
        assertEquals(sql.length(), spans.length());
        // The editor highlights its text as it changes
        open(schemaNode());
        type("SELECT 1", 0);
        assertEquals(List.of("sql-keyword"), FxTestSupport.call(() -> List.copyOf(tab.getEditor().getCodeArea()
                .getStyleOfChar(1))));
    }

    /**
     * The schema of the editor is switched for the next statements, the data source keeps its own
     */
    @Test
    @SuppressWarnings("unchecked")
    void switchesSchema() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS SJ_OTHER");
            statement.execute("CREATE TABLE IF NOT EXISTS SJ_OTHER.T_OTHER (ID INT PRIMARY KEY)");
            statement.execute("MERGE INTO SJ_OTHER.T_OTHER KEY (ID) VALUES (7)");
        }
        open(schemaNode());
        javafx.scene.control.ComboBox<String> schemas = (javafx.scene.control.ComboBox<String>) node(
                "sqlSchemaCombo");
        javafx.scene.control.ComboBox<String> catalogs = (javafx.scene.control.ComboBox<String>) node(
                "sqlCatalogCombo");
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> schemas.getItems().contains("SJ_OTHER")
                && UiDatabase.SCHEMA.equals(schemas.getValue())));
        assertEquals(FxTestSupport.call(catalogs::isVisible), true);
        // The table of the other schema is not found in the schema of the tab
        type("SELECT ID FROM T_OTHER", 0);
        runAndWait(false);
        assertTrue(messages().stream().anyMatch(message -> message.contains("T_OTHER")), messages().toString());

        FxTestSupport.run(() -> schemas.setValue("SJ_OTHER"));
        FxTestSupport.waitUntil(() -> messages().stream().anyMatch(message -> message.startsWith(
                "Statements run in")));
        assertEquals("SJ_OTHER", tab.getSchema());
        assertTrue(FxTestSupport.call(() -> tab.getTooltip().getText()).endsWith("SJ_OTHER"));
        runAndWait(false);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> results().getItems().size() == 1));
        assertEquals("7", FxTestSupport.call(() -> results().getItems().get(0).get(0)));
        // The data source keeps its own database and schema
        assertEquals(profile.getDatabase(), context.getProfileRegistry().findProfile(profile.getId()).orElseThrow()
                .getDatabase());
        // Not switched while statements run
        FxTestSupport.run(() -> tab.run(false));
        FxTestSupport.waitUntil(() -> !tab.isBusy());
        assertEquals(false, FxTestSupport.call(schemas::isDisabled));
    }

    /**
     * A switch before the first statement opens the connection in the chosen schema
     */
    @Test
    void switchesBeforeFirstStatement() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS SJ_FIRST");
            statement.execute("CREATE TABLE IF NOT EXISTS SJ_FIRST.T_FIRST (ID INT PRIMARY KEY)");
        }
        open(schemaNode());
        FxTestSupport.run(() -> tab.switchTo(tab.getCatalog(), "SJ_FIRST"));
        type("SELECT COUNT(*) FROM T_FIRST", 0);
        runAndWait(false);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> results().getItems().size() == 1));
        assertEquals("0", FxTestSupport.call(() -> results().getItems().get(0).get(0)));
    }
}
