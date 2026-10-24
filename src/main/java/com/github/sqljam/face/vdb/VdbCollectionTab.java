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
package com.github.sqljam.face.vdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.view.DataPane;
import com.github.sqljam.face.view.Icons;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.face.view.TableColumns;
import com.github.sqljam.face.view.TaskRunner;
import com.github.sqljam.face.view.ValueCell;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VectorType;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
 * @Description: VdbCollectionTab shows a collection of a vector database like a table of a data source: its fields
 *               (the columns of its table in a target) and its records, narrowed by conditions or a native filter,
 *               or the nearest neighbors of a vector or of a record. Import... imports the records of the query.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbCollectionTab extends Tab {

    private static final String CHECK = "✓";
    /**
     * Records which pages read at most, a page is read by the cursor of the database from the first record
     */
    static final int MAX_RECORDS = 10000;

    private final VdbProfile profile;
    private final String collectionName;
    private final TableView<VdbField> fieldTable = new TableView<>();
    private final TableView<List<String>> dataTable = new TableView<>();
    private final ComboBox<String> filterTypeCombo = new ComboBox<>();
    private final TextField filterField = new TextField();
    private final ComboBox<String> nearestFieldCombo = new ComboBox<>();
    private final TextField nearestField = new TextField();
    private final Button applyButton = new Button(Messages.get("data.query.apply"), Icons.of(Icons.START));
    private final Button clearButton = new Button(Messages.get("data.query.clear"), Icons.of(Icons.CANCEL));
    private final Button importButton = new Button(Messages.get("vdb.import"), Icons.of(Icons.IMPORT));
    private final Button refreshButton = new Button(null, Icons.of(Icons.REFRESH));
    private final Button firstButton = new Button(null, Icons.of(Icons.FIRST));
    private final Button previousButton = new Button(null, Icons.of(Icons.PREVIOUS));
    private final Button nextButton = new Button(null, Icons.of(Icons.NEXT));
    private final Button lastButton = new Button(null, Icons.of(Icons.LAST));
    private final Label pageLabel = new Label();
    /**
     * Records of the page, its headers sort them on the page
     */
    private final ObservableList<List<String>> dataRows = TableColumns.sortedRows(dataTable);
    private int pageNumber = 1;
    /**
     * Pages of the query, 0 while its count is unknown
     */
    private int totalPages;
    private boolean hasNextPage;
    private final ComboBox<Integer> limitCombo = new ComboBox<>();
    private final Label totalLabel = new Label();
    private final Label errorLabel = new Label();
    /**
     * Partitions, tenants or namespaces of the collection which are read, all when none is checked
     */
    private final MenuButton partitionsButton = new MenuButton();
    private final Map<String, CheckBox> partitionChecks = new LinkedHashMap<>();
    private final List<String> initialPartitions;
    private VdbCollection collection;
    private VdbQuery query = new VdbQuery();
    private boolean dataLoaded;
    private Tab dataTab;
    private BiConsumer<String, VdbQuery> onImport;

    public VdbCollectionTab(VdbProfile profile, String collectionName) {
        this(profile, collectionName, List.of());
    }

    /**
     * @param partitions partitions, tenants or namespaces which are read first, all if it is empty
     */
    public VdbCollectionTab(VdbProfile profile, String collectionName, List<String> partitions) {
        this.profile = profile;
        this.collectionName = collectionName;
        this.initialPartitions = partitions;
        String partitionName = profile.getType().getPartitionName();
        setText(partitions.size() == 1 ? collectionName + " / " + partitions.get(0) : collectionName);
        setGraphic(Icons.of(Icons.TABLE));
        setTooltip(new Tooltip(profile.getName() + "." + collectionName));
        fieldTable.setId("vdbFieldTable");
        dataTable.setId("vdbDataTable");
        filterTypeCombo.setId("vdbFilterTypeCombo");
        filterField.setId("vdbFilterField");
        nearestFieldCombo.setId("vdbNearestFieldCombo");
        nearestField.setId("vdbNearestField");
        applyButton.setId("vdbApplyButton");
        clearButton.setId("vdbClearButton");
        importButton.setId("vdbImportButton");
        refreshButton.setId("vdbRefreshButton");
        firstButton.setId("vdbFirstButton");
        previousButton.setId("vdbPreviousButton");
        nextButton.setId("vdbNextButton");
        lastButton.setId("vdbLastButton");
        pageLabel.setId("vdbPageLabel");
        limitCombo.setId("vdbLimitCombo");
        totalLabel.setId("vdbTotalLabel");
        errorLabel.setId("vdbQueryErrorLabel");
        partitionsButton.setId("vdbPartitionsButton");
        partitionsButton.setVisible(false);
        partitionsButton.setManaged(false);
        partitionsButton.setText(partitionName != null ? Messages.format("vdb.partitions.all", partitionName)
                : null);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab fieldsTab = new Tab(Messages.get("vdb.fields"), fieldTable);
        dataTab = new Tab(Messages.get("table.data"), createDataPane());
        fieldsTab.setGraphic(Icons.of(Icons.COLUMNS));
        dataTab.setGraphic(Icons.of(Icons.DATA));
        tabs.getTabs().addAll(fieldsTab, dataTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, tab) -> {
            if (tab == dataTab && !dataLoaded && collection != null) {
                dataLoaded = true;
                load();
            }
        });
        setContent(tabs);
        setupFieldTable();
        describe();
    }

    /**
     * The records of the query are imported by the consumer, e.g. the import dialog
     */
    public void setOnImport(BiConsumer<String, VdbQuery> onImport) {
        this.onImport = onImport;
    }

    VdbCollection getCollection() {
        return collection;
    }

    private static <T> TableColumn<VdbField, T> column(String key, Function<VdbField, T> getter, double width) {
        TableColumn<VdbField, T> column = new TableColumn<>(Messages.get(key));
        column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(getter.apply(features.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private void setupFieldTable() {
        fieldTable.getColumns().setAll(List.of(
                column("column.name", VdbField::getName, 180),
                column("column.type", VdbCollectionTab::describeType, 200),
                column("column.nullable", field -> field.isNullable() && !field.isPrimaryKey() ? CHECK : "",
                        80),
                column("column.primaryKey", field -> field.isPrimaryKey() ? CHECK : "", 90),
                column("vdb.field.index", field -> field.getVectorIndex() == null ? "" : field.getVectorIndex()
                        .getKind() + " " + field.getVectorIndex().getDistance(), 160),
                column("column.remarks", field -> StringUtils.defaultString(field.getRemarks()), 260)));
        fieldTable.setPlaceholder(new Label(Messages.get("tree.loading")));
    }

    /**
     * Type of a field: a vector of its elements and dimensions, or the kind of a scalar
     */
    static String describeType(VdbField field) {
        if (field.isVector()) {
            VectorType type = field.getVectorType();
            return (type.isSparse() ? "SPARSE " : "VECTOR ") + type.getElement() + (type.getDimensions() > 0
                    ? "(" + type.getDimensions() + ")" : "");
        }
        return field.getKind() == VdbField.Kind.VARCHAR ? "VARCHAR(" + field.getSize() + ")" : field.getKind()
                .name();
    }

    /**
     * Conditions or a native filter, the nearest neighbors and the records at most, like the query bar of a table
     */
    private BorderPane createDataPane() {
        filterTypeCombo.getItems().setAll(Messages.get("vdb.query.conditions"), Messages.get("vdb.query.native"));
        filterTypeCombo.setValue(filterTypeCombo.getItems().get(0));
        filterTypeCombo.valueProperty().addListener((obs, oldType, type) -> updateFilterPrompt());
        updateFilterPrompt();
        filterField.setOnAction(event -> applyQuery());
        nearestField.setOnAction(event -> applyQuery());
        nearestField.setPromptText(Messages.get("vdb.query.nearest.prompt"));
        nearestField.setPrefWidth(220);
        nearestFieldCombo.setPromptText(Messages.get("vdb.query.nearest"));
        applyButton.setOnAction(event -> applyQuery());
        clearButton.setOnAction(event -> clearQuery());
        importButton.setOnAction(event -> {
            if (onImport != null) {
                onImport.accept(collectionName, query);
            }
        });
        HBox.setHgrow(filterField, Priority.ALWAYS);
        HBox bar = new HBox(6, partitionsButton, filterTypeCombo, filterField, nearestFieldCombo, nearestField,
                applyButton, clearButton, importButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        errorLabel.getStyleClass().add("status-error");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        VBox top = new VBox(4, bar, errorLabel);
        top.setPadding(new Insets(8, 8, 0, 8));
        top.getStyleClass().add("query-bar");

        dataTable.getStyleClass().add("data-table");
        // Rows of one line, long values are shown by a double click
        dataTable.setFixedCellSize(26);
        // The columns fill the width, no empty column is left at the right
        TableColumns.fill(dataTable);
        TableColumns.fill(fieldTable);
        dataTable.setPlaceholder(new Label(Messages.get("tree.loading")));
        limitCombo.getItems().addAll(50, 100, 200, 500);
        limitCombo.setValue(50);
        limitCombo.valueProperty().addListener((obs, oldLimit, limit) -> load());
        refreshButton.setOnAction(event -> load(pageNumber));
        refreshButton.setTooltip(new Tooltip(Messages.get("action.refresh")));
        firstButton.setOnAction(event -> load(1));
        previousButton.setOnAction(event -> load(pageNumber - 1));
        nextButton.setOnAction(event -> load(pageNumber + 1));
        lastButton.setOnAction(event -> load(getLastPage()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox pager = new HBox(6, firstButton, previousButton, pageLabel, nextButton, lastButton, refreshButton,
                spacer, totalLabel, new Label(Messages.get("data.pageSize")), limitCombo);
        updatePager(true);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.setPadding(new Insets(8));
        pager.getStyleClass().add("pager");
        BorderPane pane = new BorderPane(dataTable);
        pane.setTop(top);
        pane.setBottom(pager);
        return pane;
    }

    private boolean isNativeFilter() {
        return filterTypeCombo.getSelectionModel().getSelectedIndex() == 1;
    }

    private void updateFilterPrompt() {
        filterField.setPromptText(Messages.get(isNativeFilter() ? "vdb.query.native.prompt"
                : "vdb.query.conditions.prompt"));
    }

    private void describe() {
        TaskRunner.run(() -> {
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                return client.describe(collectionName);
            }
        }, described -> {
            collection = described;
            showPartitions(described);
            fieldTable.getItems().setAll(described.getFields());
            fieldTable.setPlaceholder(new Label(Messages.get("vdb.fields.empty")));
            nearestFieldCombo.getItems().setAll(described.getVectorFields().stream().map(VdbField::getName)
                    .toList());
            if (!nearestFieldCombo.getItems().isEmpty()) {
                nearestFieldCombo.setValue(nearestFieldCombo.getItems().get(0));
            }
            setTooltip(new Tooltip(Messages.format("vdb.collection.tooltip", profile.getName(), collectionName,
                    described.getCount())));
            // The data tab which was chosen before the fields were read reads its records now
            if (dataTab.isSelected() && !dataLoaded) {
                dataLoaded = true;
                load();
            }
        }, e -> {
            fieldTable.setPlaceholder(new Label(Messages.format("tree.error", e.getMessage())));
            dataTable.setPlaceholder(new Label(Messages.format("tree.error", e.getMessage())));
        });
    }

    /**
     * Partitions of the collection to choose from, the partitions of the tab are checked
     */
    private void showPartitions(VdbCollection described) {
        List<String> partitions;
        try (VdbClient client = profile.getType().createClient(profile.toSource())) {
            partitions = client.getPartitions(described);
        }
        partitionChecks.clear();
        partitionsButton.getItems().clear();
        boolean visible = !partitions.isEmpty();
        partitionsButton.setVisible(visible);
        partitionsButton.setManaged(visible);
        for (String partition : partitions) {
            CheckBox check = new CheckBox(partition);
            check.setSelected(initialPartitions.contains(partition));
            check.selectedProperty().addListener((obs, oldValue, value) -> updatePartitionsText());
            partitionChecks.put(partition, check);
            CustomMenuItem item = new CustomMenuItem(check);
            // The menu stays open while partitions are checked
            item.setHideOnClick(false);
            partitionsButton.getItems().add(item);
        }
        query.getPartitions().addAll(getCheckedPartitions());
        updatePartitionsText();
    }

    private List<String> getCheckedPartitions() {
        List<String> checked = new ArrayList<>();
        partitionChecks.forEach((partition, check) -> {
            if (check.isSelected()) {
                checked.add(partition);
            }
        });
        return checked;
    }

    private void updatePartitionsText() {
        List<String> checked = getCheckedPartitions();
        String partitionName = profile.getType().getPartitionName();
        partitionsButton.setText(checked.isEmpty() ? Messages.format("vdb.partitions.all", partitionName)
                : checked.size() == 1 ? partitionName + ": " + checked.get(0) : Messages.format(
                "vdb.partitions.some", partitionName, checked.size()));
    }

    /**
     * Query of the inputs
     *
     * @throws IllegalArgumentException if a condition or the vector is invalid
     */
    VdbQuery buildQuery() {
        VdbQuery built = new VdbQuery();
        String filter = StringUtils.trimToNull(filterField.getText());
        if (filter != null) {
            if (isNativeFilter()) {
                built.setNativeFilter(filter);
            } else {
                built.where(filter);
            }
        }
        String nearest = StringUtils.trimToNull(nearestField.getText());
        if (nearest != null) {
            built.setNearestField(nearestFieldCombo.getValue());
            if (nearest.startsWith("[")) {
                VectorType.Values values = VectorType.parseValues(nearest);
                if (values == null || values.isSparse()) {
                    throw new IllegalArgumentException(Messages.get("vdb.query.nearest.error"));
                }
                built.setNearestVector(values.toFloats());
            } else {
                built.setNearestId(nearest.matches("\\d{1,18}") ? (Object) Long.parseLong(nearest) : nearest);
            }
            built.setLimit(10);
        }
        built.getPartitions().addAll(getCheckedPartitions());
        return built;
    }

    private void applyQuery() {
        try {
            query = buildQuery();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        showError(null);
        load();
    }

    private void clearQuery() {
        filterField.clear();
        nearestField.clear();
        query = new VdbQuery();
        query.getPartitions().addAll(getCheckedPartitions());
        showError(null);
        load();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(message != null);
        errorLabel.setManaged(message != null);
    }

    /**
     * Reads the first page of the query
     */
    void load() {
        load(1);
    }

    /**
     * Pages of the records which are read at most, all pages of a known count within MAX_RECORDS
     */
    int getLastPage() {
        int maxPages = Math.max(1, MAX_RECORDS / limitCombo.getValue());
        return totalPages > 0 ? Math.min(totalPages, maxPages) : maxPages;
    }

    /**
     * Reads the records of the page of the query. The nearest neighbors are one page, other records are read by
     * the cursor of the database from the first one and those of the page are kept, as far as MAX_RECORDS.
     */
    void load(int page) {
        if (collection == null) {
            return;
        }
        int limit = limitCombo.getValue();
        boolean nearest = query.getNearestField() != null && (query.getNearestVector() != null || query
                .getNearestId() != null);
        int targetPage = nearest ? 1 : Math.max(1, Math.min(page, getLastPage()));
        int offset = (targetPage - 1) * limit;
        VdbQuery read = new VdbQuery();
        read.getConditions().addAll(query.getConditions());
        read.setNativeFilter(query.getNativeFilter());
        read.setNearestField(query.getNearestField());
        read.setNearestVector(query.getNearestVector());
        read.setNearestId(query.getNearestId());
        read.getPartitions().addAll(query.getPartitions());
        // One more record tells whether a next page follows
        int wanted = offset + limit + 1;
        read.setLimit(query.getLimit() > 0 ? Math.min(query.getLimit(), wanted) : wanted);
        VdbCollection described = collection;
        dataTable.setPlaceholder(new Label(Messages.get("tree.loading")));
        updatePager(true);
        long start = System.currentTimeMillis();
        TaskRunner.run(() -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            int[] seen = {0};
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                client.read(described, read, Math.min(wanted, 500), (records, last) -> {
                    for (Map<String, Object> record : records) {
                        if (seen[0] >= offset && seen[0] < wanted) {
                            rows.add(record);
                        }
                        seen[0]++;
                    }
                    return seen[0] < wanted;
                });
                long count = client.count(described, query);
                return new Object[]{rows, count};
            }
        }, result -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result[0];
            long count = (Long) result[1];
            if (rows.isEmpty() && targetPage > 1) {
                // The records of the page were deleted meanwhile, the last page which has records is shown
                totalPages = targetPage - 1;
                load(targetPage - 1);
                return;
            }
            pageNumber = targetPage;
            hasNextPage = !nearest && rows.size() > limit && offset + limit < MAX_RECORDS;
            List<Map<String, Object>> shown = rows.size() > limit ? rows.subList(0, limit) : rows;
            totalPages = nearest ? 1 : count >= 0 ? (int) Math.max(1, (count + limit - 1) / limit) : 0;
            showRows(shown, offset, count, System.currentTimeMillis() - start);
            updatePager(false);
        }, e -> {
            dataRows.clear();
            dataTable.setPlaceholder(new Label(Messages.get("package.noRows")));
            showError(e.getMessage());
            updatePager(false);
        });
    }

    private void updatePager(boolean busy) {
        pageLabel.setText(totalPages > 0 ? Messages.format("data.page", pageNumber, totalPages) : Messages.format(
                "vdb.page", pageNumber));
        firstButton.setDisable(busy || pageNumber <= 1);
        previousButton.setDisable(busy || pageNumber <= 1);
        nextButton.setDisable(busy || !hasNextPage);
        lastButton.setDisable(busy || !hasNextPage || totalPages <= 0);
        refreshButton.setDisable(busy);
    }

    private void showRows(List<Map<String, Object>> rows, int offset, long count, long millis) {
        List<VdbField> fields = collection.getFields();
        List<List<String>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (VdbField field : fields) {
                Object value = row.get(field.getName());
                values.add(value == null ? null : value.toString());
            }
            items.add(values);
        }
        dataTable.getColumns().clear();
        for (int i = 0; i < fields.size(); i++) {
            int index = i;
            VdbField field = fields.get(i);
            TableColumn<List<String>, String> column = new TableColumn<>(field.getName());
            column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().get(index)));
            // One line of a value, a double click shows it whole
            column.setCellFactory(tableColumn -> new ValueCell());
            // Widths estimated by the header and the values, like the columns of a table
            column.setPrefWidth(DataPane.getColumnWidth(field.getName(), items, index));
            column.setComparator(ValueCell.COMPARATOR);
            dataTable.getColumns().add(column);
        }
        dataRows.setAll(items);
        dataTable.setPlaceholder(new Label(Messages.get("package.noRows")));
        int from = rows.isEmpty() ? 0 : offset + 1;
        totalLabel.setText(count >= 0 ? Messages.format("vdb.records.shown", from, offset + rows.size(), count,
                millis) : Messages.format("vdb.records.shownUnknown", from, offset + rows.size(), millis));
        // Pages after the records which are read at most are reached by a narrower query
        totalLabel.setTooltip(count > MAX_RECORDS ? new Tooltip(Messages.format("vdb.records.max", MAX_RECORDS))
                : null);
    }
}
