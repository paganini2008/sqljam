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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ReportExporter;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * @Description: ReportTab shows the report of a transfer on its own page: its status, a summary, the tables with
 *               their rows and verdicts, the objects which were created, the rows which were skipped and the error
 *               which ended it. Export writes it as Markdown, HTML or JSON.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ReportTab extends Tab {

    private final TransferReport report;

    public ReportTab(TransferReport report) {
        this.report = report;
        setText(Messages.format("report.tab", ReportExporter.time(report.getStartedAt())));
        setGraphic(Icons.of(Icons.INFO));
        setTooltip(new Tooltip(StringUtils.defaultString(report.getTitle())));
        setId("reportTab");

        Label status = new Label(ReportExporter.words(report.getStatus()));
        status.setId("reportStatus");
        status.getStyleClass().addAll("report-status", report.getStatus().isKept() ? "status-success"
                : "status-error");
        status.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label title = new Label(StringUtils.defaultString(report.getTitle()));
        title.setStyle("-fx-font-size: 15px;");

        VBox content = new VBox(14, new VBox(4, status, title), summary());
        if (!report.getOptions().isEmpty()) {
            content.getChildren().add(section(Messages.get("report.options"), options(), false));
        }
        content.getChildren().add(section(Messages.format("report.tables", report.getTables().size()), tables(),
                true));
        if (!report.getObjects().isEmpty() || report.getFailedStatements() > 0) {
            content.getChildren().add(section(Messages.get("report.objects"), objects(), true));
        }
        if (report.getSkippedRowCount() > 0) {
            content.getChildren().add(section(Messages.format("report.skipped", report.getSkippedRowCount()),
                    skippedRows(), true));
        }
        if (StringUtils.isNotBlank(report.getFailureTrace())) {
            TextArea trace = new TextArea(report.getFailureTrace());
            trace.setId("reportTrace");
            trace.setEditable(false);
            trace.setPrefRowCount(14);
            trace.getStyleClass().add("mono");
            content.getChildren().add(section(Messages.get("report.error"), trace, false));
        }
        content.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        BorderPane root = new BorderPane(scroll);
        root.setTop(toolBar());
        root.getStyleClass().add("report-tab");
        setContent(root);
    }

    public TransferReport getReport() {
        return report;
    }

    private ToolBar toolBar() {
        MenuButton export = new MenuButton(Messages.get("report.export"), Icons.of(Icons.EXPORT));
        export.setId("reportExportButton");
        for (ReportExporter.Format format : ReportExporter.Format.values()) {
            MenuItem item = new MenuItem(format == ReportExporter.Format.MARKDOWN ? "Markdown" : format.name());
            item.setId("reportExport" + format.name());
            item.setOnAction(event -> export(format));
            export.getItems().add(item);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label id = new Label(report.getId());
        id.getStyleClass().add("muted");
        return new ToolBar(export, spacer, id);
    }

    private void export(ReportExporter.Format format) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("report.export"));
        chooser.setInitialFileName("sqljam-report-" + report.getId().substring(0, 8) + "." + format
                .getExtension());
        File file = chooser.showSaveDialog(getTabPane() != null && getTabPane().getScene() != null ? getTabPane()
                .getScene().getWindow() : null);
        if (file != null) {
            exportTo(file, format);
        }
    }

    /**
     * Writes the report as the format to the file, an error is shown
     */
    void exportTo(File file, ReportExporter.Format format) {
        try {
            Files.writeString(file.toPath(), ReportExporter.export(report, format), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Dialogs.showError(getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene()
                    .getWindow() : null, Messages.get("report.export"), e);
        }
    }

    private static TitledPane section(String title, Node content, boolean expanded) {
        TitledPane pane = new TitledPane(title, content);
        pane.setExpanded(expanded);
        pane.setAnimated(false);
        return pane;
    }

    private Node summary() {
        GridPane grid = new GridPane();
        grid.setId("reportSummary");
        grid.setHgap(18);
        grid.setVgap(6);
        int row = 0;
        for (String[] item : ReportExporter.summary(report)) {
            Label name = new Label(item[0]);
            name.getStyleClass().add("muted");
            Label value = new Label(item[1]);
            value.setWrapText(true);
            grid.addRow(row++, name, value);
        }
        return grid;
    }

    private Node options() {
        List<String[]> rows = new ArrayList<>();
        report.getOptions().forEach((name, value) -> rows.add(new String[]{name, value}));
        return table("reportOptions", new String[]{Messages.get("report.option"), Messages.get("report.value")},
                rows);
    }

    private Node tables() {
        List<String[]> rows = new ArrayList<>();
        report.getTables().forEach(table -> rows.add(ReportExporter.tableRow(table)));
        return table("reportTables", ReportExporter.TABLE_HEADERS, rows);
    }

    private Node objects() {
        List<String[]> rows = new ArrayList<>();
        report.getObjects().forEach((kind, count) -> rows.add(new String[]{kind, String.valueOf(count)}));
        if (report.getFailedStatements() > 0) {
            rows.add(new String[]{Messages.get("report.failedStatements"), String.valueOf(report
                    .getFailedStatements())});
        }
        return table("reportObjects", new String[]{Messages.get("report.kind"), Messages.get("report.count")}, rows);
    }

    private Node skippedRows() {
        List<String[]> rows = new ArrayList<>();
        report.getSkippedRows().forEach(row -> rows.add(new String[]{row.getTable(), row.getKey(),
                row.getError()}));
        VBox box = new VBox(6, table("reportSkippedRows", new String[]{Messages.get("report.table"), Messages.get(
                "report.row"), Messages.get("report.error")}, rows));
        long more = report.getSkippedRowCount() - report.getSkippedRows().size();
        if (more > 0) {
            Label label = new Label(Messages.format("report.moreSkipped", more));
            label.getStyleClass().add("muted");
            box.getChildren().add(label);
        }
        return box;
    }

    /**
     * A table of text rows, as high as its rows up to a limit; long values are shown whole by a double click
     */
    private static TableView<List<String>> table(String id, String[] headers, List<String[]> rows) {
        TableView<List<String>> table = new TableView<>();
        table.setId(id);
        for (int i = 0; i < headers.length; i++) {
            int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(headers[i]);
            column.setCellValueFactory(features -> new ReadOnlyStringWrapper(index < features.getValue().size()
                    ? features.getValue().get(index) : null));
            column.setCellFactory(tableColumn -> new ValueCell());
            column.setComparator(ValueCell.COMPARATOR);
            column.setPrefWidth(i == 0 ? 220 : 120);
            table.getColumns().add(column);
        }
        rows.forEach(row -> table.getItems().add(Arrays.asList(row)));
        table.setFixedCellSize(28);
        // Rows, the header and the horizontal scroll bar of wide tables
        table.setPrefHeight(Math.min(28 * rows.size() + 52, 440));
        TableColumns.fill(table);
        table.setPlaceholder(new Label(Messages.get("report.none")));
        VBox.setVgrow(table, Priority.NEVER);
        table.setMinHeight(Region.USE_PREF_SIZE);
        return table;
    }
}
