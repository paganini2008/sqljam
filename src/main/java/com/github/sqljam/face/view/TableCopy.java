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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * @Description: TableCopy copies the selected rows of a data table to the clipboard as CSV, TSV, JSON or Markdown, in
 *               the order and with the headers of the visible columns. Shortcut+C copies the values tab separated,
 *               which spreadsheets paste into cells, Shortcut+Shift+C with the headers, like DBeaver and DataGrip.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class TableCopy {

    static final String INSTALLED = "tableCopyInstalled";
    static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination COPY_WITH_HEADERS = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN,
            KeyCombination.SHIFT_DOWN);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public enum Format {
        CSV("CSV"), TSV("TSV"), JSON("JSON"), MARKDOWN("Markdown");

        private final String displayName;

        Format(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private TableCopy() {
    }

    /**
     * Rows of the table are selected several at a time and copied by the keys, installed once per table
     */
    static void install(TableView<List<String>> table) {
        if (table == null || table.getProperties().containsKey(INSTALLED)) {
            return;
        }
        table.getProperties().put(INSTALLED, Boolean.TRUE);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (COPY_WITH_HEADERS.match(event)) {
                ValueCell.copy(format(table, Format.TSV, true));
                event.consume();
            } else if (COPY.match(event)) {
                ValueCell.copy(format(table, Format.TSV, false));
                event.consume();
            }
        });
    }

    /**
     * Copy Rows As of the context menu of a cell of the table
     */
    static Menu createMenu(TableView<List<String>> table) {
        Menu menu = new Menu(Messages.get("value.copyRows"), Icons.of(Icons.COPY));
        menu.setId("copyRowsMenu");
        for (Format format : Format.values()) {
            MenuItem item = new MenuItem(format.getDisplayName());
            item.setId("copyRows" + format.getDisplayName());
            item.setOnAction(event -> ValueCell.copy(format(table, format, true)));
            menu.getItems().add(item);
        }
        return menu;
    }

    /**
     * Selected rows of the table in the format, in the order of the table
     */
    static String format(TableView<List<String>> table, Format format, boolean headers) {
        List<Integer> indexes = new ArrayList<>(table.getSelectionModel().getSelectedIndices());
        indexes.removeIf(index -> index == null || index < 0);
        indexes.sort(null);
        List<TableColumn<List<String>, ?>> columns = table.getVisibleLeafColumns();
        List<String> names = columns.stream().map(TableColumn::getText).collect(Collectors.toList());
        List<List<String>> rows = new ArrayList<>();
        for (int index : indexes) {
            List<String> row = new ArrayList<>();
            for (TableColumn<List<String>, ?> column : columns) {
                Object value = column.getCellData(index);
                row.add(value != null ? value.toString() : null);
            }
            rows.add(row);
        }
        return format(names, rows, format, headers);
    }

    public static String format(List<String> names, List<List<String>> rows, Format format, boolean headers) {
        switch (format) {
            case CSV:
                return delimited(names, rows, headers, ",");
            case TSV:
                return delimited(names, rows, headers, "\t");
            case JSON:
                return json(names, rows);
            default:
                return markdown(names, rows);
        }
    }

    private static String delimited(List<String> names, List<List<String>> rows, boolean headers, String delimiter) {
        List<String> lines = new ArrayList<>();
        if (headers) {
            lines.add(names.stream().map(name -> quote(name, delimiter)).collect(Collectors.joining(delimiter)));
        }
        for (List<String> row : rows) {
            lines.add(row.stream().map(value -> quote(value, delimiter)).collect(Collectors.joining(delimiter)));
        }
        return String.join("\n", lines);
    }

    /**
     * A value of CSV or TSV, quoted as RFC 4180 tells when it has the delimiter, quotes or line breaks, NULL is empty
     */
    static String quote(String value, String delimiter) {
        if (value == null) {
            return "";
        }
        if (value.contains(delimiter) || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String json(List<String> names, List<List<String>> rows) {
        List<Map<String, String>> objects = new ArrayList<>();
        for (List<String> row : rows) {
            Map<String, String> object = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                object.put(names.get(i), row.get(i));
            }
            objects.add(object);
        }
        try {
            return MAPPER.writeValueAsString(objects);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String markdown(List<String> names, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(markdownRow(names));
        lines.add(names.stream().map(name -> "---").collect(Collectors.joining(" | ", "| ", " |")));
        for (List<String> row : rows) {
            lines.add(markdownRow(row.stream().map(value -> value != null ? value : ValueCell.NULL_TEXT)
                    .collect(Collectors.toList())));
        }
        return String.join("\n", lines);
    }

    private static String markdownRow(List<String> values) {
        return values.stream().map(value -> value.replace("\\", "\\\\").replace("|", "\\|").replaceAll("\\R",
                "<br>")).collect(Collectors.joining(" | ", "| ", " |"));
    }
}
