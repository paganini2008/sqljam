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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * @Description: ColumnMappingDialog maps the columns of the tables of an import into a database: a column is renamed,
 *               gets a type of the target written as it is, or is left out. Columns which are not changed are
 *               imported as they are, so an import without mappings is the import of before, like the column mapping
 *               of DBeaver. Two columns of a table can not have the same name, a required column (the id of a
 *               collection) is always imported. It knows the columns only, the lines of SQL and of vector databases
 *               turn its results into their own mappings.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ColumnMappingDialog extends Dialog<Map<String, Map<String, ColumnMappingDialog.Mapping>>> {

    /**
     * A source column, required when it can not be left out
     */
    public static final class Column {

        private final String name;
        private final String type;
        private final boolean required;

        public Column(String name, String type, boolean required) {
            this.name = name;
            this.type = type;
            this.required = required;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public boolean isRequired() {
            return required;
        }
    }

    /**
     * The target of a column which the user changed: its name and type, null when unchanged, or left out
     */
    public static final class Mapping {

        private final String name;
        private final String type;
        private final boolean skipped;

        public Mapping(String name, String type, boolean skipped) {
            this.name = StringUtils.trimToNull(name);
            this.type = StringUtils.trimToNull(type);
            this.skipped = skipped;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public boolean isSkipped() {
            return skipped;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Mapping)) {
                return false;
            }
            Mapping mapping = (Mapping) other;
            return skipped == mapping.skipped && Objects.equals(name, mapping.name) && Objects.equals(type,
                    mapping.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, skipped);
        }

        @Override
        public String toString() {
            return skipped ? "skipped" : StringUtils.defaultString(name) + " " + StringUtils.defaultString(type);
        }
    }

    /**
     * Reads the columns of a table, in the background
     */
    @FunctionalInterface
    public interface ColumnLoader {
        List<Column> load(String table) throws Exception;
    }

    /**
     * A row of the table of columns
     */
    public static final class Row {

        final Column column;
        final BooleanProperty imported = new SimpleBooleanProperty(true);
        final StringProperty targetName = new SimpleStringProperty("");
        final StringProperty targetType = new SimpleStringProperty("");

        Row(Column column, Mapping mapping) {
            this.column = column;
            if (mapping != null) {
                imported.set(!mapping.isSkipped() || column.isRequired());
                targetName.set(StringUtils.defaultString(mapping.getName()));
                targetType.set(StringUtils.defaultString(mapping.getType()));
            }
        }

        /**
         * The mapping of the row, null when it changes nothing
         */
        Mapping toMapping() {
            if (!imported.get()) {
                return new Mapping(null, null, true);
            }
            String name = StringUtils.trimToNull(targetName.get());
            if (name != null && name.equals(column.getName())) {
                name = null;
            }
            String type = StringUtils.trimToNull(targetType.get());
            return name == null && type == null ? null : new Mapping(name, type, false);
        }

        String getTargetName() {
            return StringUtils.defaultIfBlank(StringUtils.trim(targetName.get()), column.getName());
        }

        public Column getColumn() {
            return column;
        }

        public BooleanProperty importedProperty() {
            return imported;
        }

        public StringProperty targetNameProperty() {
            return targetName;
        }

        public StringProperty targetTypeProperty() {
            return targetType;
        }
    }

    private final ComboBox<String> tableCombo = new ComboBox<>();
    private final TableView<Row> columnTable = new TableView<>();
    private final Label errorLabel = new Label();
    private final Label statusLabel = new Label();
    private final Map<String, List<Row>> rowsByTable = new LinkedHashMap<>();
    private final Map<String, Map<String, Mapping>> chosen;
    private final ColumnLoader loader;
    private final Button okButton;

    /**
     * @param tables tables (or collections) of the import
     * @param chosen mappings chosen before, by table and column
     */
    public ColumnMappingDialog(Window owner, List<String> tables, ColumnLoader loader,
                               Map<String, Map<String, Mapping>> chosen) {
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        this.loader = loader;
        this.chosen = new LinkedHashMap<>();
        chosen.forEach((table, mappings) -> this.chosen.put(table, new LinkedHashMap<>(mappings)));
        setTitle(Messages.get("mapping.title"));
        setHeaderText(Messages.get("mapping.header"));
        setResizable(true);
        tableCombo.setId("mappingTableCombo");
        columnTable.setId("mappingColumnTable");
        errorLabel.setId("mappingError");
        statusLabel.setId("mappingStatus");
        errorLabel.getStyleClass().add("status-error");
        errorLabel.setWrapText(true);
        statusLabel.getStyleClass().add("muted");

        tableCombo.getItems().setAll(tables);
        tableCombo.setMaxWidth(Double.MAX_VALUE);
        tableCombo.valueProperty().addListener((obs, oldTable, table) -> showTable(table));
        Label tableLabel = new Label(Messages.get("mapping.table"));
        tableLabel.setMinWidth(70);
        HBox tableBox = new HBox(10, tableLabel, tableCombo);
        tableBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tableCombo, Priority.ALWAYS);

        columnTable.setEditable(true);
        columnTable.getColumns().add(importColumn());
        columnTable.getColumns().add(textColumn(Messages.get("mapping.source"), row -> row.column.getName()));
        columnTable.getColumns().add(textColumn(Messages.get("mapping.sourceType"), row -> row.column.getType()));
        columnTable.getColumns().add(editColumn(Messages.get("mapping.target"), "mappingTarget", true));
        columnTable.getColumns().add(editColumn(Messages.get("mapping.targetType"), "mappingType", false));
        columnTable.setPlaceholder(new Label(Messages.get("mapping.loading")));
        columnTable.setPrefSize(760, 380);
        TableColumns.fill(columnTable);

        VBox content = new VBox(10, tableBox, columnTable, errorLabel, statusLabel);
        content.setPadding(new Insets(4));
        getDialogPane().setContent(content);
        ButtonType reset = new ButtonType(Messages.get("mapping.reset"), ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(reset, ButtonType.OK, ButtonType.CANCEL);
        Button resetButton = (Button) getDialogPane().lookupButton(reset);
        resetButton.setId("mappingResetButton");
        resetButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            resetTable();
        });
        okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (validate() != null) {
                event.consume();
            }
        });
        setResultConverter(type -> type == ButtonType.OK ? getMappings() : null);
        // One table needs no choice of tables
        tableBox.setVisible(tables.size() > 1);
        tableBox.setManaged(tables.size() > 1);
        if (!tables.isEmpty()) {
            tableCombo.setValue(tables.get(0));
        }
    }

    private TableColumn<Row, Boolean> importColumn() {
        TableColumn<Row, Boolean> column = new TableColumn<>(Messages.get("mapping.import"));
        column.setPrefWidth(70);
        column.setSortable(false);
        column.setCellFactory(tableColumn -> new TableCell<>() {
            private final CheckBox check = new CheckBox();

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    setGraphic(null);
                    return;
                }
                check.setSelected(row.imported.get());
                check.setOnAction(event -> {
                    row.imported.set(check.isSelected());
                    validate();
                });
                // The id of a collection is always imported
                check.setDisable(row.column.isRequired());
                check.setTooltip(row.column.isRequired() ? new Tooltip(Messages.format("mapping.required",
                        row.column.getName())) : null);
                setGraphic(check);
            }
        });
        column.setCellValueFactory(features -> features.getValue().imported.asObject());
        return column;
    }

    private static TableColumn<Row, String> textColumn(String title, Function<Row, String> value) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setPrefWidth(170);
        column.setCellValueFactory(features -> new ReadOnlyStringWrapper(value.apply(features.getValue())));
        return column;
    }

    /**
     * A column of text fields which are always editable, a value is kept as it is typed
     */
    private TableColumn<Row, String> editColumn(String title, String id, boolean name) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setPrefWidth(180);
        column.setSortable(false);
        column.setCellValueFactory(features -> name ? features.getValue().targetName : features.getValue()
                .targetType);
        column.setCellFactory(tableColumn -> new TableCell<>() {
            private final TextField field = new TextField();
            private StringProperty bound;

            {
                field.setId(id);
                field.textProperty().addListener((obs, oldText, text) -> validate());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (bound != null) {
                    field.textProperty().unbindBidirectional(bound);
                    bound = null;
                }
                Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    setGraphic(null);
                    return;
                }
                bound = name ? row.targetName : row.targetType;
                field.textProperty().bindBidirectional(bound);
                field.setPromptText(name ? row.column.getName() : Messages.get("mapping.automatic"));
                field.disableProperty().bind(row.imported.not());
                setGraphic(field);
            }
        });
        return column;
    }

    private void showTable(String table) {
        if (table == null) {
            return;
        }
        List<Row> rows = rowsByTable.get(table);
        if (rows != null) {
            columnTable.getItems().setAll(rows);
            validate();
            return;
        }
        columnTable.getItems().clear();
        columnTable.setPlaceholder(new Label(Messages.get("mapping.loading")));
        TaskRunner.run(() -> loader.load(table), columns -> {
            Map<String, Mapping> mappings = chosen.getOrDefault(table, Map.of());
            List<Row> loaded = new ArrayList<>();
            columns.forEach(column -> loaded.add(new Row(column, mappings.get(column.getName()))));
            rowsByTable.put(table, loaded);
            if (table.equals(tableCombo.getValue())) {
                columnTable.getItems().setAll(loaded);
                columnTable.setPlaceholder(new Label(Messages.get("mapping.empty")));
            }
            validate();
        }, e -> columnTable.setPlaceholder(new Label(Messages.format("tree.error", e.getMessage()))));
    }

    /**
     * The columns of the table as they are
     */
    void resetTable() {
        List<Row> rows = rowsByTable.get(tableCombo.getValue());
        if (rows != null) {
            for (Row row : rows) {
                row.imported.set(true);
                row.targetName.set("");
                row.targetType.set("");
            }
            columnTable.refresh();
            validate();
        }
    }

    /**
     * The first error of the mappings, null if they can be imported: two columns with the same name, a table
     * without columns
     */
    String validate() {
        String error = null;
        for (Map.Entry<String, List<Row>> entry : rowsByTable.entrySet()) {
            Map<String, String> names = new HashMap<>();
            boolean anyImported = false;
            for (Row row : entry.getValue()) {
                if (!row.imported.get()) {
                    continue;
                }
                anyImported = true;
                String target = row.getTargetName();
                String other = names.putIfAbsent(target.toLowerCase(Locale.ENGLISH), row.column.getName());
                if (other != null && error == null) {
                    error = Messages.format("mapping.error.duplicate", other, row.column.getName(), target,
                            entry.getKey());
                }
            }
            if (!anyImported && !entry.getValue().isEmpty() && error == null) {
                error = Messages.format("mapping.error.noColumns", entry.getKey());
            }
        }
        errorLabel.setText(error);
        errorLabel.setVisible(error != null);
        errorLabel.setManaged(error != null);
        okButton.setDisable(error != null);
        long changed = getMappings().values().stream().mapToLong(Map::size).sum();
        statusLabel.setText(changed == 0 ? Messages.get("mapping.unchanged") : Messages.format("mapping.changed",
                changed));
        return error;
    }

    /**
     * Mappings of the columns which are changed, by table; tables which were not opened keep their mappings
     */
    Map<String, Map<String, Mapping>> getMappings() {
        Map<String, Map<String, Mapping>> mappings = new LinkedHashMap<>();
        chosen.forEach((table, columns) -> {
            if (!rowsByTable.containsKey(table) && !columns.isEmpty()) {
                mappings.put(table, new LinkedHashMap<>(columns));
            }
        });
        rowsByTable.forEach((table, rows) -> {
            Map<String, Mapping> columns = new LinkedHashMap<>();
            rows.forEach(row -> {
                Mapping mapping = row.toMapping();
                if (mapping != null) {
                    columns.put(row.column.getName(), mapping);
                }
            });
            if (!columns.isEmpty()) {
                mappings.put(table, columns);
            }
        });
        return mappings;
    }

    String getTable() {
        return tableCombo.getValue();
    }

    void setTable(String table) {
        tableCombo.setValue(table);
    }

    List<Row> getRows() {
        return columnTable.getItems();
    }
}
