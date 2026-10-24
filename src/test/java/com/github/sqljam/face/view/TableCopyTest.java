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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import com.github.sqljam.face.view.TableCopy.Format;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/**
 * @Description: TableCopyTest verifies the copy of the selected rows of a data table as CSV, TSV, JSON and Markdown:
 *               the order of the rows and of the visible columns, quotes, NULL, and the keys and the menu of the cells
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class TableCopyTest {

    private TableView<List<String>> table;

    @Start
    void start(Stage stage) {
        FxTestSupport.applyAppStyle();
        table = new TableView<>();
        table.setFixedCellSize(26);
        for (String name : List.of("id", "name", "note")) {
            int index = table.getColumns().size();
            TableColumn<List<String>, String> column = new TableColumn<>(name);
            column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().get(index)));
            column.setCellFactory(tableColumn -> new DataPane.NullableCell());
            table.getColumns().add(column);
        }
        table.getItems().setAll(Arrays.asList(Arrays.asList("1", "Ann", "a, \"b\""), Arrays.asList("2", "Bob",
                null), Arrays.asList("3", "C|D", "line\nnext")));
        stage.setScene(new Scene(table, 400, 300));
        stage.show();
    }

    @AfterEach
    void close() {
        FxTestSupport.closeAllDialogs();
    }

    private void select(int... rows) {
        FxTestSupport.run(() -> {
            table.getSelectionModel().clearSelection();
            for (int row : rows) {
                table.getSelectionModel().select(row);
            }
        });
    }

    private String copy(Format format) {
        return FxTestSupport.call(() -> TableCopy.format(table, format, true));
    }

    @Test
    void copiesSelectedRowsInFormats() {
        assertEquals(SelectionMode.MULTIPLE, FxTestSupport.call(() -> {
            table.applyCss();
            table.layout();
            return table.getSelectionModel().getSelectionMode();
        }));
        // Rows are copied in the order of the table, whatever the order of the selection
        select(2, 0);
        assertEquals("id,name,note\n1,Ann,\"a, \"\"b\"\"\"\n3,C|D,\"line\nnext\"", copy(Format.CSV));
        assertEquals("id\tname\tnote\n1\tAnn\t\"a, \"\"b\"\"\"\n3\tC|D\t\"line\nnext\"", copy(Format.TSV));
        assertEquals("| id | name | note |\n| --- | --- | --- |\n| 1 | Ann | a, \"b\" |\n| 3 | C\\|D | line<br>next |",
                copy(Format.MARKDOWN));
        // NULL is empty in CSV, null in JSON and NULL in Markdown
        select(1);
        assertEquals("id,name,note\n2,Bob,", copy(Format.CSV));
        assertEquals("[ {\n  \"id\" : \"2\",\n  \"name\" : \"Bob\",\n  \"note\" : null\n} ]", copy(Format.JSON)
                .replace("\r\n", "\n"));
        assertEquals("| id | name | note |\n| --- | --- | --- |\n| 2 | Bob | NULL |", copy(Format.MARKDOWN));
        // Columns in their visible order, hidden ones are left out
        FxTestSupport.run(() -> {
            TableColumn<List<String>, ?> id = table.getColumns().remove(0);
            table.getColumns().add(id);
            table.getColumns().get(1).setVisible(false);
        });
        assertEquals("name,id\nBob,2", copy(Format.CSV));
        FxTestSupport.run(() -> table.getSelectionModel().clearSelection());
        assertEquals("name,id", copy(Format.CSV));
        assertEquals("[ ]", copy(Format.JSON));
        assertEquals(List.of("CSV", "TSV", "JSON", "Markdown"), Arrays.stream(Format.values())
                .map(Format::getDisplayName).collect(Collectors.toList()));
    }

    @Test
    void copiesByKeysAndMenu() {
        select(0, 1);
        FxTestSupport.run(table::requestFocus);
        Platform.runLater(() -> Event.fireEvent(table, shortcutC(false)));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("1\tAnn\t\"a, \"\"b\"\"\"\n2\tBob\t", clipboard());
        // Shift adds the headers
        Platform.runLater(() -> Event.fireEvent(table, shortcutC(true)));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("id\tname\tnote\n1\tAnn\t\"a, \"\"b\"\"\"\n2\tBob\t", clipboard());

        // Copy Rows As of the context menu of a cell
        @SuppressWarnings("unchecked")
        ContextMenu menu = FxTestSupport.call(() -> {
            table.applyCss();
            table.layout();
            return table.lookupAll(".value-cell").stream().map(node -> (TableCell<List<String>, String>) node)
                    .filter(cell -> !cell.isEmpty()).findFirst().orElseThrow().getContextMenu();
        });
        Menu copyRows = (Menu) menu.getItems().get(3);
        assertEquals("Copy Rows As", copyRows.getText());
        MenuItem markdown = copyRows.getItems().get(3);
        FxTestSupport.run(markdown::fire);
        assertEquals(copy(Format.MARKDOWN), clipboard());
        FxTestSupport.run(() -> copyRows.getItems().get(0).fire());
        assertEquals(copy(Format.CSV), clipboard());
        // Installed once
        FxTestSupport.run(() -> TableCopy.install(table));
        FxTestSupport.run(() -> TableCopy.install(null));
    }

    /**
     * Shortcut+C, Command on macOS and Ctrl elsewhere
     */
    private static KeyEvent shortcutC(boolean shift) {
        boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, shift, !mac, false, mac);
    }

    private static String clipboard() {
        return FxTestSupport.call(() -> Clipboard.getSystemClipboard().getString());
    }
}
