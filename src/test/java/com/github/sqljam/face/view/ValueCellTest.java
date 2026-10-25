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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * @Description: ValueCellTest verifies the cells of the data tables: one line of a long value, NULL, and the whole
 *               value by a double click, by Shift+Enter and by the context menu, with indented JSON, the dimensions
 *               of vectors and Copy
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ValueCellTest {

    static final String LONG = "line one\nline two " + "x".repeat(2000);
    private TableView<List<String>> table;

    @Start
    void start(Stage stage) {
        FxTestSupport.applyAppStyle();
        table = new TableView<>();
        table.setFixedCellSize(26);
        TableColumn<List<String>, String> value = new TableColumn<>("payload");
        value.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().get(0)));
        value.setCellFactory(column -> new DataPane.NullableCell());
        value.setPrefWidth(300);
        table.getColumns().add(value);
        table.getItems().setAll(Arrays.asList(Arrays.asList(LONG), Arrays.asList((String) null),
                Arrays.asList("{\"a\": {\"b\": 1}}"), Arrays.asList("[0.5, 1.5, 2]"), Arrays.asList(
                        "2024-01-01T08:30:00")));
        stage.setScene(new Scene(table, 400, 300));
        stage.show();
    }

    @AfterEach
    void close() {
        FxTestSupport.closeAllDialogs();
    }

    @SuppressWarnings("unchecked")
    private TableCell<List<String>, String> cell(int row) {
        return FxTestSupport.call(() -> {
            table.applyCss();
            table.layout();
            return table.lookupAll(".value-cell").stream().map(node -> (TableCell<List<String>, String>) node)
                    .filter(cell -> cell.getIndex() == row && !cell.isEmpty()).findFirst().orElseThrow();
        });
    }

    private static DialogPane valueDialog() {
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> pane.lookup(
                "#valueArea") != null));
        return FxTestSupport.getDialogPanes().stream().filter(pane -> pane.lookup("#valueArea") != null)
                .findFirst().orElseThrow();
    }

    @Test
    void showsOneLine() {
        TableCell<List<String>, String> cell = cell(0);
        String text = FxTestSupport.call(cell::getText);
        assertFalse(text.contains("\n"));
        assertTrue(text.startsWith("line one line two"));
        assertEquals(ValueCell.MAX_CELL_LENGTH + 1, text.length());
        assertFalse(cell.isWrapText());
        assertNotNull(FxTestSupport.call(cell::getTooltip));
        assertEquals("NULL", FxTestSupport.call(() -> cell(1).getText()));
        assertTrue(FxTestSupport.call(() -> cell(1).getStyleClass().contains("null-cell")));
        // Times of the data source are shown without their T
        assertEquals("2024-01-01 08:30:00", FxTestSupport.call(() -> cell(4).getText()));
        assertNull(FxTestSupport.call(() -> cell(4).getTooltip()));
    }

    @Test
    void showsWholeValueByDoubleClick() {
        TableCell<List<String>, String> cell = cell(0);
        Platform.runLater(() -> Event.fireEvent(cell, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 2, false, false, false, false, true, false, false, true, false, false, null)));
        DialogPane pane = valueDialog();
        assertEquals(LONG, FxTestSupport.call(() -> ((TextArea) pane.lookup("#valueArea")).getText()));
        assertTrue(pane.getHeaderText().contains("payload"));
        assertTrue(pane.getHeaderText().contains(LONG.length() + " characters"));
        // Copy puts the value on the clipboard and keeps the dialog
        FxTestSupport.fireLater((javafx.scene.control.Button) pane.lookup("#copyValueButton"));
        assertEquals(LONG, FxTestSupport.call(() -> Clipboard.getSystemClipboard().getString()));
        assertTrue(FxTestSupport.call(() -> pane.getScene().getWindow().isShowing()));
    }

    @Test
    void showsValueByShiftEnterAndMenu() {
        // JSON is indented
        FxTestSupport.run(() -> {
            table.requestFocus();
            table.getSelectionModel().select(2, table.getColumns().get(0));
            table.getFocusModel().focus(2, table.getColumns().get(0));
        });
        Platform.runLater(() -> Event.fireEvent(table, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, true,
                false, false, false)));
        DialogPane json = valueDialog();
        String text = FxTestSupport.call(() -> ((TextArea) json.lookup("#valueArea")).getText());
        assertTrue(text.contains("\n") && text.contains("\"b\" : 1"), text);
        FxTestSupport.closeAllDialogs();

        // A vector tells its dimensions, View Value of the context menu shows it
        ContextMenu menu = FxTestSupport.call(() -> cell(3).getContextMenu());
        List<String> items = menu.getItems().stream().map(MenuItem::getText).collect(Collectors.toList());
        assertEquals(Arrays.asList("View Value", "Copy Value", null, "Copy Rows As"), items);
        FxTestSupport.run(() -> menu.getItems().get(0).fire());
        DialogPane vector = valueDialog();
        assertTrue(vector.getHeaderText().contains("Vector of 3 dimensions"), vector.getHeaderText());
        assertEquals("[0.5, 1.5, 2]", FxTestSupport.call(() -> ((TextArea) vector.lookup("#valueArea")).getText()));
        FxTestSupport.run(() -> menu.getItems().get(1).fire());
        assertEquals("[0.5, 1.5, 2]", FxTestSupport.call(() -> Clipboard.getSystemClipboard().getString()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void describesValues() {
        assertEquals("Sparse vector of 8 dimensions", ValueDialog.describe("{1:0.5,3:2}/8"));
        assertEquals("3 characters", ValueDialog.describe("abc"));
        assertEquals("[1, \"a\"]".length() + " characters", ValueDialog.describe("[1, \"a\"]"));
        assertEquals("not json {", ValueDialog.format("not json {"));
        assertEquals("plain", ValueDialog.format("plain"));
        assertTrue(ValueDialog.format("[{\"a\": 1}]").contains("\n"));
        List<String> lines = new ArrayList<>(List.of("a\r\nb"));
        assertEquals("a b", ValueCell.oneLine(lines.get(0)));
        // Headers sort NULL first, numbers by their values and other values as texts
        List<String> values = new ArrayList<>(Arrays.asList("10", null, "9", "-1.5", "1e3", "b", "a", ""));
        values.sort(ValueCell.COMPARATOR);
        assertEquals(Arrays.asList(null, "-1.5", "9", "10", "1e3", "", "a", "b"), values);
        assertEquals(0, ValueCell.COMPARATOR.compare(null, null));
        assertNull(ValueCell.toNumber("1".repeat(65)));
        assertNull(ValueCell.toNumber("12abc"));
        // NULL in the dialog
        Platform.runLater(() -> ValueDialog.show(null, "c", null));
        DialogPane pane = valueDialog();
        assertEquals("NULL", FxTestSupport.call(() -> ((TextArea) pane.lookup("#valueArea")).getText()));
    }
}
