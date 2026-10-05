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

import java.util.List;

import com.github.sqljam.face.model.DataPage;
import com.github.sqljam.face.service.DatabaseSession;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * @Description: DataPane shows rows of a table page by page
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
        setCenter(dataTable);
        setBottom(pager);
        updatePager(false);
    }

    public void load(int page) {
        int targetPage = Math.max(1, Math.min(page, Math.max(totalPages, 1)));
        int pageSize = pageSizeCombo.getValue();
        updatePager(true);
        TaskRunner.run(() -> session.getData(catalog, schema, table, targetPage, pageSize), this::show, e -> {
            updatePager(false);
            dataTable.setPlaceholder(new Label(Messages.format("tree.error", e.getMessage())));
            Dialogs.showError(getScene() != null ? getScene().getWindow() : null,
                    Messages.format("table.loadError", table), e);
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

    private static final java.util.regex.Pattern ISO_DATE_TIME = java.util.regex.Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}.*)$");

    /**
     * Text of a cell in one line, ISO date time is shown with a space separator
     */
    static String format(String value) {
        java.util.regex.Matcher matcher = ISO_DATE_TIME.matcher(value);
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
