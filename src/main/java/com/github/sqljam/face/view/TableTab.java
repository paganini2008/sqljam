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
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ColumnInfo;
import com.github.sqljam.face.model.ForeignKeyInfo;
import com.github.sqljam.face.model.IndexInfo;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.TableQuery;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * @Description: TableTab shows metadata, DDL and rows of a table
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class TableTab extends Tab {

    private static final String CHECK = "✓";

    private final DbNode node;
    private final DatabaseSession session;
    private final TableView<ColumnInfo> columnTable = new TableView<>();
    private final TableView<IndexInfo> indexTable = new TableView<>();
    private final TableView<ForeignKeyInfo> foreignKeyTable = new TableView<>();
    private final TextArea ddlArea = new TextArea();
    private final ComboBox<DbType> ddlDbTypeCombo = new ComboBox<>();
    private final DataPane dataPane;
    private boolean ddlLoaded;
    private boolean dataLoaded;

    /**
     * Rows narrowed in the data pane are exported by the consumer
     */
    public void setOnExport(BiConsumer<DbNode, TableQuery> onExport) {
        dataPane.setOnExport(query -> onExport.accept(node, query));
    }

    DataPane getDataPane() {
        return dataPane;
    }

    public TableTab(AppContext context, DbNode node) {
        this.node = node;
        this.session = context.getSessionManager().getSession(node.getProfile());
        setText(node.getTable().getName());
        setGraphic(Icons.of(Icons.TABLE));
        setTooltip(new Tooltip(getKey(node)));
        dataPane = new DataPane(session, node.getCatalog(), node.getSchema(), node.getTable().getName(),
                context.getSettings().getDataPageSize());

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab columnsTab = new Tab(Messages.get("table.columns"), columnTable);
        Tab indexesTab = new Tab(Messages.get("table.indexes"), indexTable);
        Tab foreignKeysTab = new Tab(Messages.get("table.foreignKeys"), foreignKeyTable);
        Tab ddlTab = new Tab(Messages.get("table.ddl"), createDdlPane());
        Tab dataTab = new Tab(Messages.get("table.data"), dataPane);
        tabs.getTabs().addAll(columnsTab, indexesTab, foreignKeysTab, ddlTab, dataTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, tab) -> {
            if (tab == ddlTab && !ddlLoaded) {
                loadDdl();
            } else if (tab == dataTab && !dataLoaded) {
                dataLoaded = true;
                dataPane.load(1);
            }
        });
        setContent(tabs);

        setupColumnTable();
        setupIndexTable();
        setupForeignKeyTable();
        loadMetadata();
    }

    /**
     * Unique key of the table, an opened table is reused
     */
    public static String getKey(DbNode node) {
        return Stream.of(node.getProfile().getName(), node.getCatalog(), node.getSchema(),
                node.getTable().getName()).filter(StringUtils::isNotBlank).collect(Collectors.joining("."));
    }

    private static <S, T> TableColumn<S, T> column(String key, Function<S, T> getter, double width) {
        TableColumn<S, T> column = new TableColumn<>(Messages.get(key));
        column.setCellValueFactory(features -> (ObservableValue<T>) new ReadOnlyObjectWrapper<>(
                getter.apply(features.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static String check(boolean value) {
        return value ? CHECK : "";
    }

    private void setupColumnTable() {
        columnTable.getColumns().addAll(List.of(
                column("column.position", ColumnInfo::getPosition, 40),
                column("column.name", ColumnInfo::getName, 150),
                column("column.type", ColumnInfo::getTypeName, 120),
                column("column.size", ColumnInfo::getSize, 70),
                column("column.scale", ColumnInfo::getScale, 55),
                column("column.nullable", (ColumnInfo info) -> check(info.isNullable()), 70),
                column("column.default", ColumnInfo::getDefaultValue, 120),
                column("column.primaryKey", (ColumnInfo info) -> check(info.isPrimaryKey()), 40),
                column("column.autoIncrement", (ColumnInfo info) -> check(info.isAutoIncrement()), 70),
                column("column.generated", (ColumnInfo info) -> check(info.isGenerated()), 80),
                column("column.remarks", ColumnInfo::getRemarks, 180)));
        columnTable.setPlaceholder(new Label(Messages.get("tree.loading")));
    }

    private void setupIndexTable() {
        indexTable.getColumns().addAll(List.of(
                column("index.name", IndexInfo::getName, 240),
                column("index.unique", (IndexInfo info) -> check(info.isUnique()), 70),
                column("index.columns", (IndexInfo info) -> String.join(", ", info.getColumns()), 360)));
        indexTable.setPlaceholder(new Label(Messages.get("table.none")));
    }

    private void setupForeignKeyTable() {
        foreignKeyTable.getColumns().addAll(List.of(
                column("fk.name", ForeignKeyInfo::getName, 200),
                column("fk.columns", (ForeignKeyInfo info) -> String.join(", ", info.getColumns()), 160),
                column("fk.referencedTable", (ForeignKeyInfo info) -> StringUtils.isNotBlank(
                        info.getReferencedSchema()) ? info.getReferencedSchema() + "." + info.getReferencedTable()
                        : info.getReferencedTable(), 200),
                column("fk.referencedColumns", (ForeignKeyInfo info) -> String.join(", ",
                        info.getReferencedColumns()), 160),
                column("fk.updateRule", ForeignKeyInfo::getUpdateRule, 100),
                column("fk.deleteRule", ForeignKeyInfo::getDeleteRule, 100)));
        foreignKeyTable.setPlaceholder(new Label(Messages.get("table.none")));
    }

    private BorderPane createDdlPane() {
        ddlArea.setEditable(false);
        ddlArea.getStyleClass().add("mono");
        ddlDbTypeCombo.getItems().addAll(DbType.values());
        DbTypeCells.setupDbTypeCombo(ddlDbTypeCombo);
        ddlDbTypeCombo.setValue(node.getProfile().getDbType());
        ddlDbTypeCombo.valueProperty().addListener((obs, oldType, type) -> loadDdl());
        Button copyButton = new Button(Messages.get("action.copy"), Icons.of(Icons.COPY));
        copyButton.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(ddlArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Button refreshButton = new Button(null, Icons.of(Icons.REFRESH));
        refreshButton.setOnAction(event -> loadDdl());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, new Label(Messages.get("ddl.dialect")), ddlDbTypeCombo, refreshButton, spacer,
                copyButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));
        BorderPane pane = new BorderPane(ddlArea);
        pane.setTop(toolbar);
        return pane;
    }

    private void loadMetadata() {
        String catalog = node.getCatalog();
        String schema = node.getSchema();
        String table = node.getTable().getName();
        TaskRunner.run(() -> session.getColumns(catalog, schema, table), columns -> {
            columnTable.getItems().setAll(columns);
            columnTable.setPlaceholder(new Label(Messages.get("table.none")));
        }, this::showError);
        TaskRunner.run(() -> session.getIndexes(catalog, schema, table), indexTable.getItems()::setAll,
                this::showError);
        TaskRunner.run(() -> session.getForeignKeys(catalog, schema, table), foreignKeyTable.getItems()::setAll,
                this::showError);
    }

    private void loadDdl() {
        ddlLoaded = true;
        ddlArea.setText(Messages.get("tree.loading"));
        DbType dbType = ddlDbTypeCombo.getValue();
        TaskRunner.run(() -> session.getDdl(node.getCatalog(), node.getSchema(), node.getTable().getName(), dbType),
                ddlArea::setText, e -> {
                    ddlArea.setText("");
                    showError(e);
                });
    }

    private void showError(Throwable e) {
        Dialogs.showError(getTabPane() != null ? getTabPane().getScene().getWindow() : null,
                Messages.format("table.loadError", node.getTable().getName()), e);
    }
}
