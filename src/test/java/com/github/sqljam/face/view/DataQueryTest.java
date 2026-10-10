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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.TableQuery;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: DataQueryTest verifies the query of the data pane: selected columns, WHERE, GROUP BY, ORDER BY,
 *               paging of narrowed rows, invalid and empty inputs, clearing and exporting the rows of the query
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class DataQueryTest {

    @TempDir
    File dir;

    private DatabaseSession session;
    private StackPane root;

    @Start
    void start(Stage stage) throws Exception {
        FxTestSupport.createContext(dir);
        ConnectionProfile profile = UiDatabase.create(dir, "ui");
        session = new DatabaseSession(profile);
        root = new StackPane();
        stage.setScene(new Scene(root, 1200, 600));
        stage.show();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private DataPane show(String table, int pageSize) {
        DataPane pane = FxTestSupport.call(() -> {
            DataPane dataPane = new DataPane(session, UiDatabase.CATALOG, UiDatabase.SCHEMA, table, pageSize);
            root.getChildren().setAll(dataPane);
            dataPane.load(1);
            return dataPane;
        });
        waitLoaded(pane);
        // Columns of the choices are loaded with the first page
        FxTestSupport.waitUntil(() -> !FxTestSupport.call(() -> pane.lookup("#columnsButton").isDisabled()));
        return pane;
    }

    private static void waitLoaded(DataPane pane) {
        FxTestSupport.waitUntil(() -> !((Button) pane.lookup("#refreshButton")).isDisabled());
    }

    private static String text(DataPane pane, String id) {
        return FxTestSupport.call(() -> ((Label) pane.lookup("#" + id)).getText());
    }

    @SuppressWarnings("unchecked")
    private static TableView<List<String>> table(DataPane pane) {
        return (TableView<List<String>>) pane.lookup("#dataTable");
    }

    private static List<String> headers(DataPane pane) {
        return FxTestSupport.call(() -> table(pane).getColumns().stream().map(TableColumn::getText)
                .collect(Collectors.toList()));
    }

    private static List<String> firstColumn(DataPane pane) {
        return FxTestSupport.call(() -> table(pane).getItems().stream().map(row -> row.get(0))
                .collect(Collectors.toList()));
    }

    private static void setText(DataPane pane, String id, String text) {
        FxTestSupport.run(() -> ((TextField) pane.lookup("#" + id)).setText(text));
    }

    /**
     * Checks a column of the columns or group by menu
     */
    private static void check(DataPane pane, String menuId, String column, boolean selected) {
        FxTestSupport.run(() -> ((MenuButton) pane.lookup("#" + menuId)).getItems().stream()
                .filter(item -> item instanceof CustomMenuItem
                        && ((CustomMenuItem) item).getContent() instanceof CheckBox)
                .map(item -> (CheckBox) ((CustomMenuItem) item).getContent())
                .filter(checkBox -> column.equals(checkBox.getText())).findFirst().orElseThrow()
                .setSelected(selected));
    }

    private static void apply(DataPane pane) {
        FxTestSupport.run(() -> ((Button) pane.lookup("#applyQueryButton")).fire());
        waitLoaded(pane);
    }

    private static String queryError(DataPane pane) {
        return FxTestSupport.call(() -> {
            Label label = (Label) pane.lookup("#queryErrorLabel");
            return label.isVisible() ? label.getText() : null;
        });
    }

    private static String buttonText(DataPane pane, String id) {
        return FxTestSupport.call(() -> ((MenuButton) pane.lookup("#" + id)).getText());
    }

    @Test
    void selectsColumns() {
        DataPane pane = show("T_PAGED", 50);
        assertEquals("All columns", buttonText(pane, "columnsButton"));
        assertEquals(List.of("ID", "NAME", "NOTE", "DATA", "CREATED"), headers(pane));
        check(pane, "columnsButton", "NOTE", false);
        check(pane, "columnsButton", "DATA", false);
        assertEquals("3 of 5 columns", buttonText(pane, "columnsButton"));
        apply(pane);
        assertEquals(List.of("ID", "NAME", "CREATED"), headers(pane));
        assertEquals(List.of("ID", "NAME", "CREATED"), FxTestSupport.call(() -> pane.getQuery().getColumns()));
        // No column is not a query
        for (String column : new String[]{"ID", "NAME", "CREATED"}) {
            check(pane, "columnsButton", column, false);
        }
        apply(pane);
        assertEquals("Please select at least one column", queryError(pane));
        assertEquals(List.of("ID", "NAME", "CREATED"), headers(pane));
        // All columns again by the All check
        FxTestSupport.run(() -> ((CheckBox) ((CustomMenuItem) ((MenuButton) pane.lookup("#columnsButton"))
                .getItems().get(0)).getContent()).fire());
        apply(pane);
        assertNull(queryError(pane));
        assertEquals(5, headers(pane).size());
        assertTrue(FxTestSupport.call(() -> pane.getQuery().getColumns().isEmpty()));
    }

    @Test
    void filtersAndPagesRows() {
        DataPane pane = show("T_PAGED", 50);
        assertEquals("Page 1 / 5", text(pane, "pageLabel"));
        setText(pane, "whereField", "ID > 100");
        apply(pane);
        assertEquals("150 rows", text(pane, "totalLabel"));
        assertEquals("Page 1 / 3", text(pane, "pageLabel"));
        assertEquals("101", firstColumn(pane).get(0));
        FxTestSupport.run(() -> ((Button) pane.lookup("#lastButton")).fire());
        waitLoaded(pane);
        assertEquals("Page 3 / 3", text(pane, "pageLabel"));
        assertEquals("250", firstColumn(pane).get(49));
        // The WHERE keyword and a trailing semicolon are accepted
        setText(pane, "whereField", "where ID <= 3;");
        apply(pane);
        assertEquals("3 rows", text(pane, "totalLabel"));
        assertEquals("ID <= 3", FxTestSupport.call(() -> pane.getQuery().getWhere()));
        // Conditions of NULL and semicolons inside quotes
        setText(pane, "whereField", "NAME IS NULL OR NAME = 'a;b'");
        apply(pane);
        assertEquals("1 rows", text(pane, "totalLabel"));
        assertEquals("2", firstColumn(pane).get(0));
    }

    @Test
    void showsNoRows() {
        DataPane pane = show("T_PAGED", 50);
        setText(pane, "whereField", "1 = 0");
        apply(pane);
        assertEquals("0 rows", text(pane, "totalLabel"));
        assertEquals("Page 1 / 1", text(pane, "pageLabel"));
        assertTrue(FxTestSupport.call(() -> pane.lookup("#nextButton").isDisabled()));
        assertEquals("No rows", FxTestSupport.call(() -> ((Label) table(pane).getPlaceholder()).getText()));
    }

    @Test
    void ordersRows() {
        DataPane pane = show("T_PAGED", 50);
        setText(pane, "orderField", "ID DESC");
        apply(pane);
        assertEquals("250", firstColumn(pane).get(0));
        // Rows with the same value are ordered by the primary key
        setText(pane, "orderField", "order by CREATED");
        apply(pane);
        assertEquals("1", firstColumn(pane).get(0));
        assertEquals("CREATED", FxTestSupport.call(() -> pane.getQuery().getOrderBy()));
    }

    @Test
    void groupsRows() {
        DataPane pane = show("T_PAGED", 50);
        assertEquals("Group by", buttonText(pane, "groupByButton"));
        check(pane, "groupByButton", "CREATED", true);
        assertEquals("Group by CREATED", buttonText(pane, "groupByButton"));
        // Grouped rows have the group columns and the count, they are not exported
        assertTrue(FxTestSupport.call(() -> pane.lookup("#columnsButton").isDisabled()));
        apply(pane);
        assertEquals(List.of("CREATED", "ROW_COUNT"), headers(pane).stream().map(String::toUpperCase)
                .collect(Collectors.toList()));
        assertEquals("1 rows", text(pane, "totalLabel"));
        assertEquals("250", FxTestSupport.call(() -> table(pane).getItems().get(0).get(1)));
        assertTrue(FxTestSupport.call(() -> pane.lookup("#exportQueryButton").isDisabled()));
        // Groups with a condition and an order by the count
        check(pane, "groupByButton", "CREATED", false);
        check(pane, "groupByButton", "NAME", true);
        setText(pane, "whereField", "ID <= 10");
        setText(pane, "orderField", TableQuery.COUNT_COLUMN + " DESC, NAME");
        apply(pane);
        assertNull(queryError(pane));
        assertEquals("10 rows", text(pane, "totalLabel"));
    }

    @Test
    void reportsInvalidQuery() {
        DataPane pane = show("T_PAGED", 50);
        setText(pane, "whereField", "NO_SUCH_COLUMN > 1");
        apply(pane);
        String error = queryError(pane);
        assertTrue(error != null && error.startsWith("Invalid query: "), error);
        assertTrue(FxTestSupport.call(() -> table(pane).getItems().isEmpty()));
        // A statement separator is rejected before reading
        setText(pane, "whereField", "ID > 1; DROP TABLE T_PAGED");
        apply(pane);
        assertEquals("A clause must not contain ';': ID > 1; DROP TABLE T_PAGED", queryError(pane));
        setText(pane, "whereField", "");
        apply(pane);
        assertNull(queryError(pane));
        assertEquals("250 rows", text(pane, "totalLabel"));
    }

    @Test
    void clearsQuery() {
        DataPane pane = show("T_PAGED", 50);
        setText(pane, "whereField", "ID > 200");
        setText(pane, "orderField", "ID DESC");
        check(pane, "columnsButton", "NOTE", false);
        apply(pane);
        assertEquals("50 rows", text(pane, "totalLabel"));
        FxTestSupport.run(() -> ((Button) pane.lookup("#clearQueryButton")).fire());
        waitLoaded(pane);
        assertEquals("250 rows", text(pane, "totalLabel"));
        assertEquals("", FxTestSupport.call(() -> ((TextField) pane.lookup("#whereField")).getText()));
        assertEquals(5, headers(pane).size());
        assertTrue(FxTestSupport.call(() -> pane.getQuery().isEmpty()));
    }

    @Test
    void exportsRowsOfQuery() {
        DataPane pane = show("T_PAGED", 50);
        AtomicReference<TableQuery> exported = new AtomicReference<>();
        FxTestSupport.run(() -> pane.setOnExport(exported::set));
        setText(pane, "whereField", "ID > 200");
        check(pane, "columnsButton", "NOTE", false);
        apply(pane);
        assertFalse(FxTestSupport.call(() -> pane.lookup("#exportQueryButton").isDisabled()));
        FxTestSupport.run(() -> ((Button) pane.lookup("#exportQueryButton")).fire());
        assertEquals("ID > 200", exported.get().getWhere());
        assertEquals(List.of("ID", "NAME", "DATA", "CREATED"), exported.get().getColumns());
        assertEquals("SELECT ID, NAME, DATA, CREATED WHERE ID > 200", exported.get().toString());
    }

    @Test
    void reportsInvalidOrder() {
        DataPane pane = show("T_PAGED", 50);
        setText(pane, "orderField", "NO_SUCH_COLUMN DESC");
        apply(pane);
        String error = queryError(pane);
        assertTrue(error != null && error.startsWith("Invalid query: "), error);
        setText(pane, "orderField", "ID; DELETE FROM T_PAGED");
        apply(pane);
        assertEquals("A clause must not contain ';': ID; DELETE FROM T_PAGED", queryError(pane));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsQueryAcrossPageSizes() {
        DataPane pane = show("T_PAGED", 50);
        setText(pane, "whereField", "ID > 100");
        apply(pane);
        assertEquals("Page 1 / 3", text(pane, "pageLabel"));
        FxTestSupport.run(() -> ((ComboBox<Integer>) pane.lookup("#pageSizeCombo")).setValue(100));
        waitLoaded(pane);
        // The condition stays, rows are paged by the new size
        assertEquals("150 rows", text(pane, "totalLabel"));
        assertEquals("Page 1 / 2", text(pane, "pageLabel"));
    }

    @Test
    void groupsNoRows() {
        DataPane pane = show("T_PAGED", 50);
        check(pane, "groupByButton", "NAME", true);
        setText(pane, "whereField", "ID < 0");
        apply(pane);
        assertNull(queryError(pane));
        assertEquals("0 rows", text(pane, "totalLabel"));
        assertEquals("Page 1 / 1", text(pane, "pageLabel"));
    }
}
