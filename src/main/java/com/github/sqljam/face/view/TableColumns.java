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

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * @Description: TableColumns fills the width of a table with its columns: columns narrower than the table together
 *               are widened in proportion to their widths, so that no empty column is left at the right. Columns wider
 *               than the table together keep their widths and the table scrolls.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class TableColumns {

    static final String INSTALLED = "tableColumnsFill";
    static final String NATURAL_WIDTH = "naturalWidth";
    /**
     * Width of the vertical scroll bar and of the borders, which the columns leave free
     */
    static final double RESERVED = 18;

    private TableColumns() {
    }

    /**
     * Fills the width of the table, again when it or its columns change
     */
    /**
     * Rows of the table which its headers sort, the third click of a header shows them in their order again
     *
     * @return the rows to change, the table shows them sorted
     */
    public static <T> ObservableList<T> sortedRows(TableView<T> table) {
        ObservableList<T> rows = FXCollections.observableArrayList();
        SortedList<T> sorted = new SortedList<>(rows);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        return rows;
    }

    public static void fill(TableView<?> table) {
        if (table.getProperties().containsKey(INSTALLED)) {
            return;
        }
        table.getProperties().put(INSTALLED, Boolean.TRUE);
        boolean[] scheduled = {false};
        InvalidationListener listener = observable -> {
            if (!scheduled[0]) {
                scheduled[0] = true;
                Platform.runLater(() -> {
                    scheduled[0] = false;
                    apply(table);
                });
            }
        };
        table.widthProperty().addListener(listener);
        table.getColumns().addListener(listener);
    }

    /**
     * Widths of the columns: their natural widths, widened when they are narrower than the table together
     */
    static void apply(TableView<?> table) {
        double available = table.getWidth() - RESERVED;
        if (available <= 0 || table.getColumns().isEmpty()) {
            return;
        }
        double natural = 0;
        for (TableColumn<?, ?> column : table.getColumns()) {
            natural += naturalWidth(column);
        }
        double factor = natural > 0 && natural < available ? available / natural : 1;
        for (TableColumn<?, ?> column : table.getColumns()) {
            column.setPrefWidth(Math.floor(naturalWidth(column) * factor));
        }
    }

    /**
     * Natural width of a column: its width before the filling, e.g. estimated by its values
     */
    static double naturalWidth(TableColumn<?, ?> column) {
        Object width = column.getProperties().get(NATURAL_WIDTH);
        if (width == null) {
            column.getProperties().put(NATURAL_WIDTH, column.getPrefWidth());
            return column.getPrefWidth();
        }
        return (Double) width;
    }
}
