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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.sqljam.face.model.ColumnInfo;
import com.github.sqljam.face.model.DataPage;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.TableQuery;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * @Description: DataPane shows rows of a table page by page. Rows are narrowed by a simple query: selected
 *               columns, a WHERE condition, GROUP BY columns (with the count of rows) and ORDER BY. The narrowed
 *               rows can be exported (grouped rows are for viewing).
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class DataPane extends BorderPane {

    private static final String NULL_TEXT = "NULL";

    private final DatabaseSession session;
    private final String catalog;
    private final String schema;
    private final String table;
    private final TableView<List<String>> dataTable = new TableView<>();
    private final Button firstButton = new Button(null, Icons.of(Icons.FIRST));
    private final Button previousButton = new Button(null, Icons.of(Icons.PREVIOUS));
    private final Button nextButton = new Button(null, Icons.of(Icons.NEXT));
    private final Button lastButton = new Button(null, Icons.of(Icons.LAST));
    private final Button refreshButton = new Button(null, Icons.of(Icons.REFRESH));
    private final Label pageLabel = new Label();
    private final Label totalLabel = new Label();
    private final ComboBox<Integer> pageSizeCombo = new ComboBox<>();
    private final MenuButton columnsButton = new MenuButton();
    private final MenuButton groupByButton = new MenuButton();
    private final TextField whereField = new TextField();
    private final TextField orderField = new TextField();
    private final Button applyButton = new Button(Messages.get("data.query.apply"), Icons.of(Icons.START));
    private final Button clearButton = new Button(Messages.get("data.query.clear"), Icons.of(Icons.CANCEL));
    private final Button exportButton = new Button(Messages.get("action.export"), Icons.of(Icons.EXPORT));
    private final Label queryErrorLabel = new Label();
    private final Map<String, CheckBox> columnChecks = new LinkedHashMap<>();
    private final Map<String, CheckBox> groupChecks = new LinkedHashMap<>();
    private TableQuery query = new TableQuery();
    private Consumer<TableQuery> onExport;
    private boolean columnsLoaded;
    private int pageNumber = 1;
    private int totalPages = 1;
    private List<String> columns;

    public DataPane(DatabaseSession session, String catalog, String schema, String table, int pageSize) {
        this.session = session;
        this.catalog = catalog;
        this.schema = schema;
        this.table = table;
        dataTable.setPlaceholder(new Label(Messages.get("tree.loading")));
        dataTable.getStyleClass().add("data-table");
        dataTable.setId("dataTable");
        firstButton.setId("firstButton");
        previousButton.setId("previousButton");
        nextButton.setId("nextButton");
        lastButton.setId("lastButton");
        refreshButton.setId("refreshButton");
        pageLabel.setId("pageLabel");
        totalLabel.setId("totalLabel");
        pageSizeCombo.setId("pageSizeCombo");
        pageSizeCombo.getItems().addAll(50, 100, 200, 500);
        pageSizeCombo.setValue(pageSizeCombo.getItems().contains(pageSize) ? pageSize : 200);
        pageSizeCombo.valueProperty().addListener((obs, oldSize, size) -> load(1));

        firstButton.setOnAction(event -> load(1));
        previousButton.setOnAction(event -> load(pageNumber - 1));
        nextButton.setOnAction(event -> load(pageNumber + 1));
        lastButton.setOnAction(event -> load(totalPages));
        refreshButton.setOnAction(event -> load(pageNumber));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox pager = new HBox(6, firstButton, previousButton, pageLabel, nextButton, lastButton, refreshButton,
                spacer, totalLabel, new Label(Messages.get("data.pageSize")), pageSizeCombo);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.setPadding(new Insets(8));
        pager.getStyleClass().add("pager");
        setTop(createQueryBar());
        setCenter(dataTable);
        setBottom(pager);
        updatePager(false);
    }

    /**
     * Columns, WHERE, GROUP BY and ORDER BY of the rows, applied by Apply or Enter
     */
    private VBox createQueryBar() {
        columnsButton.setId("columnsButton");
        groupByButton.setId("groupByButton");
        whereField.setId("whereField");
        orderField.setId("orderField");
        applyButton.setId("applyQueryButton");
        clearButton.setId("clearQueryButton");
        exportButton.setId("exportQueryButton");
        queryErrorLabel.setId("queryErrorLabel");
        whereField.setPromptText(Messages.get("data.query.where"));
        orderField.setPromptText(Messages.get("data.query.orderBy"));
        whereField.setOnAction(event -> applyQuery());
        orderField.setOnAction(event -> applyQuery());
        applyButton.setOnAction(event -> applyQuery());
        clearButton.setOnAction(event -> clearQuery());
        exportButton.setOnAction(event -> {
            if (onExport != null) {
                onExport.accept(query);
            }
        });
        HBox.setHgrow(whereField, Priority.ALWAYS);
        orderField.setPrefWidth(200);
        updateQueryButtons();
        HBox bar = new HBox(6, columnsButton, whereField, groupByButton, orderField, applyButton, clearButton,
                exportButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        queryErrorLabel.getStyleClass().add("status-error");
        queryErrorLabel.setWrapText(true);
        queryErrorLabel.setVisible(false);
        queryErrorLabel.setManaged(false);
        VBox top = new VBox(4, bar, queryErrorLabel);
        top.setPadding(new Insets(8, 8, 0, 8));
        top.getStyleClass().add("query-bar");
        return top;
    }

    /**
     * Rows narrowed by the query are exported by the consumer, e.g. the export dialog
     */
    public void setOnExport(Consumer<TableQuery> onExport) {
        this.onExport = onExport;
    }

    public TableQuery getQuery() {
        return query;
    }

    /**
     * Columns of the table for the column and group choices
     */
    void setColumns(List<String> columnNames) {
        columnChecks.clear();
        groupChecks.clear();
        columnsButton.getItems().clear();
        groupByButton.getItems().clear();
        CheckBox allCheck = new CheckBox(Messages.get("data.query.allColumns"));
        allCheck.setId("allColumnsCheck");
        allCheck.setSelected(true);
        allCheck.setOnAction(event -> {
            // Checks of columns update the All check, its state is read first
            boolean all = allCheck.isSelected();
            columnChecks.values().forEach(check -> check.setSelected(all));
        });
        columnsButton.getItems().addAll(menuItem(allCheck), new SeparatorMenuItem());
        for (String columnName : columnNames) {
            CheckBox columnCheck = new CheckBox(columnName);
            columnCheck.setSelected(true);
            columnCheck.selectedProperty().addListener((obs, oldValue, value) -> {
                allCheck.setSelected(columnChecks.values().stream().allMatch(CheckBox::isSelected));
                updateQueryButtons();
            });
            columnChecks.put(columnName, columnCheck);
            columnsButton.getItems().add(menuItem(columnCheck));
            CheckBox groupCheck = new CheckBox(columnName);
            groupCheck.selectedProperty().addListener((obs, oldValue, value) -> updateQueryButtons());
            groupChecks.put(columnName, groupCheck);
            groupByButton.getItems().add(menuItem(groupCheck));
        }
        columnsLoaded = true;
        updateQueryButtons();
    }

    private static CustomMenuItem menuItem(CheckBox check) {
        CustomMenuItem item = new CustomMenuItem(check);
        // The menu stays open while columns are checked
        item.setHideOnClick(false);
        return item;
    }

    /**
     * Query of the inputs, all checked columns mean all columns
     */
    TableQuery buildQuery() {
        List<String> columns = columnChecks.values().stream().allMatch(CheckBox::isSelected) ? new ArrayList<>()
                : columnChecks.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey).collect(Collectors.toList());
        List<String> groupBy = groupChecks.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey).collect(Collectors.toList());
        return new TableQuery(columns, whereField.getText(), groupBy, orderField.getText());
    }

    void applyQuery() {
        TableQuery newQuery;
        try {
            newQuery = buildQuery();
        } catch (IllegalArgumentException e) {
            showQueryError(e.getMessage());
            return;
        }
        if (!newQuery.isGrouped() && !columnChecks.isEmpty() && columnChecks.values().stream()
                .noneMatch(CheckBox::isSelected)) {
            showQueryError(Messages.get("data.query.noColumns"));
            return;
        }
        showQueryError(null);
        query = newQuery;
        columns = null;
        updateQueryButtons();
        load(1);
    }

    void clearQuery() {
        whereField.clear();
        orderField.clear();
        columnChecks.values().forEach(check -> check.setSelected(true));
        groupChecks.values().forEach(check -> check.setSelected(false));
        applyQuery();
    }

    private void showQueryError(String message) {
        queryErrorLabel.setText(message != null ? message : "");
        queryErrorLabel.setVisible(message != null);
        queryErrorLabel.setManaged(message != null);
    }

    private void updateQueryButtons() {
        long selectedColumns = columnChecks.values().stream().filter(CheckBox::isSelected).count();
        columnsButton.setText(selectedColumns == columnChecks.size() ? Messages.get("data.query.allColumns")
                : Messages.format("data.query.columns", selectedColumns, columnChecks.size()));
        List<String> groups = groupChecks.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey).collect(Collectors.toList());
        groupByButton.setText(groups.isEmpty() ? Messages.get("data.query.groupBy")
                : Messages.format("data.query.groupedBy", String.join(", ", groups)));
        // Grouped rows have the group columns and the count
        columnsButton.setDisable(!groups.isEmpty() || !columnsLoaded);
        groupByButton.setDisable(!columnsLoaded);
        exportButton.setDisable(query.isGrouped());
        exportButton.setTooltip(new Tooltip(Messages.get(query.isGrouped() ? "data.query.exportGrouped"
                : "data.query.export")));
    }

    public void load(int page) {
        int targetPage = Math.max(1, Math.min(page, Math.max(totalPages, 1)));
        int pageSize = pageSizeCombo.getValue();
        TableQuery currentQuery = query;
        updatePager(true);
        if (!columnsLoaded) {
            TaskRunner.run(() -> session.getColumns(catalog, schema, table), columnInfos -> setColumns(
                    columnInfos.stream().map(ColumnInfo::getName).collect(Collectors.toList())), e -> {
                    });
        }
        TaskRunner.run(() -> session.getData(catalog, schema, table, currentQuery, targetPage, pageSize),
                this::show, e -> {
                    updatePager(false);
                    dataTable.getItems().clear();
                    dataTable.setPlaceholder(new Label(Messages.format("tree.error", e.getMessage())));
                    if (currentQuery.isEmpty()) {
                        Dialogs.showError(getScene() != null ? getScene().getWindow() : null,
                                Messages.format("table.loadError", table), e);
                    } else {
                        // An invalid condition or order is shown below the query
                        showQueryError(Messages.format("data.query.error", e.getMessage()));
                    }
                });
    }

    private void show(DataPage page) {
        pageNumber = page.getPageNumber();
        totalPages = Math.max(page.getTotalPages(), 1);
        if (!page.getColumns().equals(columns)) {
            columns = page.getColumns();
            dataTable.getColumns().clear();
            for (int i = 0; i < columns.size(); i++) {
                final int index = i;
                TableColumn<List<String>, String> column = new TableColumn<>(columns.get(i));
                column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().get(index)));
                column.setCellFactory(tableColumn -> new NullableCell());
                column.setPrefWidth(getColumnWidth(columns.get(i), page.getRows(), index));
                dataTable.getColumns().add(column);
            }
        }
        dataTable.getItems().setAll(page.getRows());
        dataTable.setPlaceholder(new Label(Messages.get("data.empty")));
        totalLabel.setText(Messages.format("data.total", page.getTotalRows()));
        updatePager(false);
    }

    /**
     * Column width estimated by the header and values of the first rows
     */
    private static double getColumnWidth(String header, List<List<String>> rows, int index) {
        int length = header.length();
        for (int i = 0; i < Math.min(rows.size(), 50); i++) {
            String value = rows.get(i).get(index);
            length = Math.max(length, value != null ? Math.min(value.length(), 40) : NULL_TEXT.length());
        }
        return Math.max(70, Math.min(360, length * 9 + 40));
    }

    private void updatePager(boolean busy) {
        pageLabel.setText(Messages.format("data.page", pageNumber, totalPages));
        firstButton.setDisable(busy || pageNumber <= 1);
        previousButton.setDisable(busy || pageNumber <= 1);
        nextButton.setDisable(busy || pageNumber >= totalPages);
        lastButton.setDisable(busy || pageNumber >= totalPages);
        refreshButton.setDisable(busy);
    }

    private static final Pattern ISO_DATE_TIME = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}.*)$");

    /**
     * Text of a cell in one line, ISO date time is shown with a space separator
     */
    static String format(String value) {
        Matcher matcher = ISO_DATE_TIME.matcher(value);
        String text = matcher.matches() ? matcher.group(1) + " " + matcher.group(2) : value;
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Cell showing NULL values in a dimmed style
     */
    private static class NullableCell extends TableCell<List<String>, String> {

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("null-cell");
            if (empty) {
                setText(null);
            } else if (item == null) {
                setText(NULL_TEXT);
                getStyleClass().add("null-cell");
            } else {
                setText(format(item));
            }
        }
    }
}
