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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * @Description: InfoTab shows the overview and the health of a data source or of a vector database by sections,
 *               read in the background when it opens and again by Refresh, never by itself. Values of one line are
 *               shown whole by a double click. It serves imports and exports, it manages nothing.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class InfoTab extends Tab {

    static final String SECTION = "section";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Callable<Map<String, Map<String, String>>> overview;
    private final Callable<Map<String, Map<String, String>>> health;
    private final TableView<List<String>> overviewTable = table("infoOverviewTable");
    private final TableView<List<String>> healthTable = table("infoHealthTable");
    private final Label refreshedLabel = new Label();
    private final Button refreshButton = new Button(Messages.get("action.refresh"), Icons.of(Icons.REFRESH));

    /**
     * @param overview sections of the overview, read in the background
     * @param health   sections of the health, read in the background
     */
    public InfoTab(String name, Node graphic, Callable<Map<String, Map<String, String>>> overview,
                   Callable<Map<String, Map<String, String>>> health) {
        this.overview = overview;
        this.health = health;
        setText(Messages.format("info.title", name));
        setGraphic(graphic);
        setTooltip(new Tooltip(Messages.format("info.tooltip", name)));
        refreshButton.setId("infoRefreshButton");
        refreshedLabel.setId("infoRefreshedLabel");
        refreshedLabel.getStyleClass().add("muted");
        refreshButton.setOnAction(event -> refresh());

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab overviewTab = new Tab(Messages.get("info.overview"), overviewTable);
        overviewTab.setGraphic(Icons.of(Icons.INFO));
        Tab healthTab = new Tab(Messages.get("info.health"), healthTable);
        healthTab.setGraphic(Icons.of(Icons.HEALTH));
        tabs.getTabs().addAll(overviewTab, healthTab);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, spacer, refreshedLabel, refreshButton);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8));
        bar.getStyleClass().add("pager");
        BorderPane content = new BorderPane(tabs);
        content.setTop(bar);
        setContent(content);
        refresh();
    }

    private static TableView<List<String>> table(String id) {
        TableView<List<String>> table = new TableView<>();
        table.setId(id);
        TableColumns.fill(table);
        table.setFixedCellSize(26);
        table.getStyleClass().add("data-table");
        TableColumn<List<String>, String> item = new TableColumn<>(Messages.get("info.item"));
        item.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().get(0)));
        item.setPrefWidth(220);
        item.setSortable(false);
        TableColumn<List<String>, String> value = new TableColumn<>(Messages.get("info.value"));
        value.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().get(1)));
        value.setCellFactory(column -> new ValueCell());
        value.setPrefWidth(760);
        value.setSortable(false);
        table.getColumns().add(item);
        table.getColumns().add(value);
        // Sections are rows of a title, the items follow them
        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(List<String> row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("info-section");
                setStyle(null);
                if (!empty && row != null && SECTION.equals(row.get(2))) {
                    getStyleClass().add("info-section");
                    setStyle("-fx-font-weight: bold;");
                }
            }
        });
        table.setPlaceholder(new Label(Messages.get("tree.loading")));
        return table;
    }

    /**
     * Rows of the sections: a title row of each section, then its items
     */
    static List<List<String>> rows(Map<String, Map<String, String>> sections) {
        List<List<String>> rows = new ArrayList<>();
        sections.forEach((section, items) -> {
            rows.add(Arrays.asList(section, "", SECTION));
            items.forEach((name, value) -> rows.add(Arrays.asList("    " + name, value, "")));
        });
        return rows;
    }

    /**
     * Reads the overview and the health again
     */
    public void refresh() {
        refreshButton.setDisable(true);
        refreshedLabel.setText(Messages.get("info.refreshing"));
        load(overviewTable, overview, false);
        load(healthTable, health, true);
    }

    private void load(TableView<List<String>> table, Callable<Map<String, Map<String, String>>> reader,
                      boolean last) {
        table.setPlaceholder(new Label(Messages.get("tree.loading")));
        TaskRunner.run(reader, sections -> {
            table.getItems().setAll(rows(sections));
            if (last) {
                done();
            }
        }, e -> {
            table.getItems().clear();
            table.setPlaceholder(new Label(Messages.format("tree.error", e.getMessage())));
            if (last) {
                done();
            }
        });
    }

    private void done() {
        refreshButton.setDisable(false);
        refreshedLabel.setText(Messages.format("info.refreshed", LocalTime.now().format(TIME)));
    }
}
