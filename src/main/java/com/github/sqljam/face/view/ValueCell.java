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

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;

/**
 * @Description: ValueCell shows a value of a table in one line, however long it is: line breaks are spaces and the
 *               end of a long value is an ellipsis. A double click shows the whole value in ValueDialog.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ValueCell extends TableCell<List<String>, String> {

    static final String NULL_TEXT = "NULL";
    /**
     * Characters of a value in a cell at most, the cell shows one line of them
     */
    static final int MAX_CELL_LENGTH = 1000;

    /**
     * Key of the property of a table whose Shift+Enter shows the value of its focused cell
     */
    static final String KEYS_INSTALLED = "valueCellKeys";

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    /**
     * Order of the values of a column sorted by its header: NULL first, then numbers by their values, then the
     * others as texts
     */
    public static final Comparator<String> COMPARATOR = (left, right) -> {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : -1) : 1;
        }
        BigDecimal leftNumber = toNumber(left);
        BigDecimal rightNumber = toNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        if (leftNumber != null || rightNumber != null) {
            // Numbers before texts, so that the order stays the same whatever values a column mixes
            return leftNumber != null ? -1 : 1;
        }
        return left.compareTo(right);
    };

    static BigDecimal toNumber(String value) {
        if (value.isEmpty() || value.length() > 64 || !NUMBER.matcher(value).matches()) {
            return null;
        }
        return new BigDecimal(value);
    }

    public ValueCell() {
        setWrapText(false);
        getStyleClass().add("value-cell");
        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isEmpty()) {
                showValue();
            }
        });
        // View Value and Copy Value of the cell, like the value viewers of DBeaver and DataGrip
        MenuItem view = new MenuItem(Messages.get("value.view"), Icons.of(Icons.OPEN));
        view.setId("viewValueMenuItem");
        view.setOnAction(event -> showValue());
        MenuItem copy = new MenuItem(Messages.get("value.copy"), Icons.of(Icons.COPY));
        copy.setId("copyValueMenuItem");
        copy.setOnAction(event -> copy(getItem()));
        ContextMenu menu = new ContextMenu(view, copy);
        emptyProperty().addListener((obs, wasEmpty, empty) -> setContextMenu(empty ? null : menu));
        tableViewProperty().addListener((obs, oldTable, table) -> {
            installKeys(table);
            // Copy Rows As of the selected rows of the table
            menu.getItems().setAll(view, copy);
            if (table != null) {
                menu.getItems().addAll(new SeparatorMenuItem(), TableCopy.createMenu(table));
            }
        });
    }

    private void showValue() {
        ValueDialog.show(getScene() != null ? getScene().getWindow() : null, getTableColumn().getText(), getItem());
    }

    static void copy(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value == null ? "" : value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Shift+Enter shows the value of the focused cell of the table, installed once per table
     */
    @SuppressWarnings("unchecked")
    static void installKeys(TableView<List<String>> table) {
        if (table == null || table.getProperties().containsKey(KEYS_INSTALLED)) {
            return;
        }
        table.getProperties().put(KEYS_INSTALLED, Boolean.TRUE);
        TableCopy.install(table);
        table.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            TablePosition<List<String>, ?> focused = table.getFocusModel().getFocusedCell();
            if (event.getCode() == KeyCode.ENTER && event.isShiftDown() && focused != null && focused.getRow() >= 0
                    && focused.getTableColumn() != null) {
                Object value = focused.getTableColumn().getCellData(focused.getRow());
                ValueDialog.show(table.getScene() != null ? table.getScene().getWindow() : null,
                        focused.getTableColumn().getText(), value != null ? value.toString() : null);
                event.consume();
            }
        });
    }

    /**
     * One line of a value
     */
    static String oneLine(String value) {
        String text = value.length() > MAX_CELL_LENGTH ? value.substring(0, MAX_CELL_LENGTH) + "…" : value;
        return text.replaceAll("\\R", " ");
    }

    /**
     * Text of a value in the cell, e.g. a time without its T
     */
    protected String display(String item) {
        return item;
    }

    @Override
    protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        getStyleClass().remove("null-cell");
        setTooltip(null);
        if (empty) {
            setText(null);
        } else if (item == null) {
            setText(NULL_TEXT);
            getStyleClass().add("null-cell");
        } else {
            setText(oneLine(display(item)));
            if (item.length() > 80) {
                setTooltip(new Tooltip(Messages.get("value.doubleClick")));
            }
        }
    }
}
