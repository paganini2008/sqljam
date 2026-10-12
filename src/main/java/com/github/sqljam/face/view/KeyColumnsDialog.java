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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ColumnInfo;
import com.github.sqljam.face.service.DatabaseSession;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * @Description: KeyColumnsDialog chooses the columns which identify a row of each table when rows are upserted. An
 *               empty field is the primary key of the table, a table without primary key and chosen columns is
 *               inserted. Chosen columns must be columns of the table.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class KeyColumnsDialog extends Dialog<Map<String, List<String>>> {

    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
    private final Label errorLabel = new Label();
    private final Button okButton;

    /**
     * @param chosen key columns chosen before, by table name
     */
    public KeyColumnsDialog(Window owner, DatabaseSession session, String catalog, String schema, List<String> tables,
                            Map<String, List<String>> chosen) {
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        setTitle(Messages.get("keys.title"));
        setHeaderText(Messages.get("keys.header"));
        setResizable(true);
        GridPane grid = new GridPane();
        grid.setId("keyColumnsGrid");
        grid.setHgap(12);
        grid.setVgap(8);
        ColumnConstraints tableColumn = new ColumnConstraints();
        tableColumn.setMinWidth(160);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(tableColumn, fieldColumn);
        int row = 0;
        for (String table : tables) {
            TextField field = new TextField(String.join(", ", chosen.getOrDefault(table, List.of())));
            field.setId("keyColumns-" + table);
            field.setPromptText(Messages.get("keys.loading"));
            field.textProperty().addListener((obs, oldText, text) -> validate());
            fields.put(table, field);
            grid.addRow(row++, new Label(table), field);
        }
        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefSize(560, Math.min(420, 60 + tables.size() * 40));
        errorLabel.setId("keyColumnsError");
        errorLabel.getStyleClass().add("status-error");
        errorLabel.setWrapText(true);
        Label hint = new Label(Messages.get("keys.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        // Chosen columns should be unique in the target table
        Label uniqueHint = new Label(Messages.get("keys.uniqueHint"), Icons.of(Icons.INFO));
        uniqueHint.setId("keyColumnsUniqueHint");
        uniqueHint.getStyleClass().add("muted");
        uniqueHint.setWrapText(true);
        VBox content = new VBox(10, hint, uniqueHint, scrollPane, errorLabel);
        content.setPadding(new Insets(4));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (validate() != null) {
                event.consume();
            }
        });
        setResultConverter(type -> type == ButtonType.OK ? getKeyColumns() : null);
        // Primary keys and columns of the tables, the primary key is the placeholder of an empty field
        TaskRunner.run(() -> {
            Map<String, List<ColumnInfo>> columns = new LinkedHashMap<>();
            for (String table : tables) {
                columns.put(table, session.getColumns(catalog, schema, table));
            }
            return columns;
        }, columns -> columns.forEach((table, infos) -> {
            columnsByTable.put(table, infos.stream().map(ColumnInfo::getName).collect(Collectors.toList()));
            String primaryKey = infos.stream().filter(ColumnInfo::isPrimaryKey).map(ColumnInfo::getName)
                    .collect(Collectors.joining(", "));
            fields.get(table).setPromptText(primaryKey.isEmpty() ? Messages.get("keys.none")
                    : Messages.format("keys.primaryKey", primaryKey));
            validate();
        }), e -> errorLabel.setText(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName())));
    }

    /**
     * The first error of the chosen columns, null if all of them are columns of their tables
     */
    String validate() {
        String error = null;
        for (Map.Entry<String, TextField> entry : fields.entrySet()) {
            List<String> columns = columnsByTable.get(entry.getKey());
            if (columns == null) {
                continue;
            }
            for (String key : parse(entry.getValue().getText())) {
                if (columns.stream().noneMatch(key::equalsIgnoreCase)) {
                    error = Messages.format("keys.error.column", key, entry.getKey());
                    break;
                }
            }
            if (error != null) {
                break;
            }
        }
        errorLabel.setText(error);
        okButton.setDisable(error != null);
        return error;
    }

    /**
     * Chosen key columns by table, tables of an empty field use their primary keys
     */
    Map<String, List<String>> getKeyColumns() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        fields.forEach((table, field) -> {
            List<String> columns = parse(field.getText());
            if (!columns.isEmpty()) {
                keys.put(table, columns);
            }
        });
        return keys;
    }

    static List<String> parse(String text) {
        return new ArrayList<>(Arrays.stream(StringUtils.defaultString(text).split(",")).map(String::trim)
                .filter(StringUtils::isNotEmpty).collect(Collectors.toList()));
    }
}
