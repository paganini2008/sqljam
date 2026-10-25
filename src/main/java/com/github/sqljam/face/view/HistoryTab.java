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
import java.util.function.Consumer;
import java.util.function.Function;

import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ReportExporter;
import com.github.sqljam.face.service.ReportStore;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;

/**
 * @Description: HistoryTab lists the reports of the imports and exports, newest first: when, what, from where to
 *               where, how it ended, rows and time. A report is opened on its own page, deleted after a confirmation,
 *               and the folder of the reports is opened.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class HistoryTab extends Tab {

    private final AppContext context;
    private final TableView<TransferReport> table = new TableView<>();
    private final Consumer<TransferReport> opener;

    public HistoryTab(AppContext context, Consumer<TransferReport> opener) {
        this.context = context;
        this.opener = opener;
        setText(Messages.get("history.tab"));
        setGraphic(Icons.of(Icons.HISTORY));
        setId("historyTab");
        table.setId("historyTable");
        table.getColumns().add(column(Messages.get("history.started"), report -> ReportExporter.time(report
                .getStartedAt()), 160));
        table.getColumns().add(column(Messages.get("history.kind"), report -> report.getKind() != null
                ? ReportExporter.words(report.getKind()) : "", 120));
        table.getColumns().add(column(Messages.get("history.source"), TransferReport::getSource, 220));
        table.getColumns().add(column(Messages.get("history.target"), TransferReport::getTarget, 220));
        TableColumn<TransferReport, String> status = column(Messages.get("history.status"), report ->
                ReportExporter.words(report.getStatus()), 160);
        status.setCellFactory(tableColumn -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-success", "status-error");
                setText(empty ? null : item);
                TransferReport report = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (report != null) {
                    getStyleClass().add(report.getStatus().isKept() ? "status-success" : "status-error");
                }
            }
        });
        table.getColumns().add(status);
        table.getColumns().add(column(Messages.get("history.rows"), report -> String.format("%,d", report
                .getWrittenRows()), 110));
        table.getColumns().add(column(Messages.get("history.duration"), report -> ReportExporter.duration(report
                .getElapsedMillis()), 110));
        table.setRowFactory(view -> {
            TableRow<TransferReport> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    opener.accept(row.getItem());
                }
            });
            return row;
        });
        table.setPlaceholder(new Label(Messages.get("history.empty")));
        TableColumns.fill(table);

        Button open = new Button(Messages.get("history.open"), Icons.of(Icons.OPEN));
        open.setId("historyOpenButton");
        open.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        open.setOnAction(event -> opener.accept(table.getSelectionModel().getSelectedItem()));
        Button delete = new Button(Messages.get("history.delete"), Icons.of(Icons.DELETE));
        delete.setId("historyDeleteButton");
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(event -> delete(table.getSelectionModel().getSelectedItem()));
        Button refresh = new Button(Messages.get("history.refresh"), Icons.of(Icons.REFRESH));
        refresh.setId("historyRefreshButton");
        refresh.setOnAction(event -> refresh());
        Button folder = new Button(Messages.get("history.folder"), Icons.of(Icons.FOLDER));
        folder.setId("historyFolderButton");
        folder.setOnAction(event -> context.openDocument(context.getReportStore().getDirectory().toURI()
                .toString()));
        BorderPane root = new BorderPane(table);
        root.setTop(new ToolBar(open, delete, refresh, folder));
        setContent(root);
        refresh();
    }

    private static TableColumn<TransferReport, String> column(String title, Function<TransferReport, String> value,
                                                             double width) {
        TableColumn<TransferReport, String> column = new TableColumn<>(title);
        column.setCellValueFactory(features -> new ReadOnlyStringWrapper(value.apply(features.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    /**
     * Reads the reports again, in the background
     */
    public void refresh() {
        ReportStore store = context.getReportStore();
        TaskRunner.run(store::list, reports -> table.getItems().setAll(reports), e -> table.setPlaceholder(
                new Label(Messages.format("tree.error", e.getMessage()))));
    }

    void delete(TransferReport report) {
        if (report == null || !Dialogs.confirm(table.getScene() != null ? table.getScene().getWindow() : null,
                Messages.get("history.delete"), Messages.format("history.delete.confirm", ReportExporter.time(report
                        .getStartedAt())))) {
            return;
        }
        TaskRunner.run(() -> context.getReportStore().delete(report.getId()), deleted -> refresh(),
                e -> Dialogs.showError(table.getScene() != null ? table.getScene().getWindow() : null,
                        Messages.get("history.delete"), e));
    }

    List<TransferReport> getReports() {
        return table.getItems();
    }

    TableView<TransferReport> getTable() {
        return table;
    }
}
