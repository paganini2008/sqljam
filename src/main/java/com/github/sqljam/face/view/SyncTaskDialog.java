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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sqljam.face.model.ColumnInfo;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TableInfo;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * @Description: SyncTaskDialog creates or edits a sync task: the source (a data source and its schema, or a vector
 *               database and its database), its tables with their watermark columns, the target data source and
 *               schema, the schedule and the attempts after errors of the connection. The watermark column is
 *               guessed: a time column named like an update time, otherwise a number primary key. A table whose
 *               watermark column changes copies all its rows again.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class SyncTaskDialog extends Dialog<SyncTask> {

    static final Pattern WATERMARK_TYPES = Pattern.compile("(?i).*(date|time|int|serial|number|numeric|decimal|long"
            + "|float|double|real).*");
    static final Pattern TIME_TYPES = Pattern.compile("(?i).*(date|time).*");
    static final Pattern UPDATED_NAMES = Pattern.compile("(?i).*(updated|update_time|updatetime|modified|modify_time|"
            + "changed|last_update|gmt_modified|mtime|ts).*");
    private static final ObjectMapper MAPPER = new ObjectMapper().disable(DeserializationFeature
            .FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * A table or a collection of the source, checked to be synced
     */
    static final class TableRow {

        final String name;
        final BooleanProperty selected = new SimpleBooleanProperty();
        final StringProperty watermarkColumn = new SimpleStringProperty();
        final ObjectProperty<SyncTask.WatermarkType> watermarkType = new SimpleObjectProperty<>(SyncTask
                .WatermarkType.UPDATED_AT);
        final StringProperty keyColumns = new SimpleStringProperty("");
        /**
         * Columns which can be watermarks, null before they are read
         */
        List<String> candidates;
        SyncTask.Table saved;

        TableRow(String name) {
            this.name = name;
        }
    }

    private final AppContext context;
    private final SyncTask original;
    private final DataSourceExtension.SyncSupport vectorSupport;
    private final TextField nameField = new TextField();
    private final ComboBox<SyncTask.Kind> kindCombo = new ComboBox<>();
    private final ComboBox<ConnectionProfile> sourceCombo = new ComboBox<>();
    private final ComboBox<DataSourceExtension.Source> vectorSourceCombo = new ComboBox<>();
    private final ComboBox<String> sourceCatalogCombo = new ComboBox<>();
    private final ComboBox<String> sourceSchemaCombo = new ComboBox<>();
    private final TableView<TableRow> tableView = new TableView<>();
    private final ComboBox<String> watermarkCombo = new ComboBox<>();
    private final ComboBox<SyncTask.WatermarkType> watermarkTypeCombo = new ComboBox<>();
    private final TextField keyField = new TextField();
    private final ComboBox<ConnectionProfile> targetCombo = new ComboBox<>();
    private final ComboBox<String> targetCatalogCombo = new ComboBox<>();
    private final ComboBox<String> targetSchemaCombo = new ComboBox<>();
    private final ComboBox<SyncTask.ScheduleType> scheduleCombo = new ComboBox<>();
    private final ComboBox<SyncTask.Runner> runnerCombo = new ComboBox<>();
    private final Spinner<Integer> intervalSpinner = new Spinner<>(1, 7 * 24 * 60, 60);
    private final TextField dailyField = new TextField("02:00");
    private final Spinner<Integer> retriesSpinner = new Spinner<>(0, 10, 3);
    private final Label statusLabel = new Label();
    private boolean loading;

    /**
     * @param task the task to edit, null for a new task
     */
    public SyncTaskDialog(Window owner, AppContext context, SyncTask task) {
        this.context = context;
        this.original = task;
        this.vectorSupport = DataSourceExtension.load(context.getSettingsStore().getConfig()).stream().map(
                DataSourceExtension::getSyncSupport).filter(support -> support != null && support.getKind()
                == SyncTask.Kind.VECTOR).findFirst().orElse(null);
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        setTitle(Messages.get(task == null ? "sync.new" : "sync.edit"));
        setHeaderText(Messages.get("sync.header"));
        setResizable(true);
        nameField.setId("syncNameField");
        kindCombo.setId("syncKindCombo");
        sourceCombo.setId("syncSourceCombo");
        vectorSourceCombo.setId("syncVectorSourceCombo");
        sourceCatalogCombo.setId("syncSourceCatalogCombo");
        sourceSchemaCombo.setId("syncSourceSchemaCombo");
        tableView.setId("syncTables");
        watermarkCombo.setId("syncWatermarkCombo");
        watermarkTypeCombo.setId("syncWatermarkTypeCombo");
        keyField.setId("syncKeyField");
        targetCombo.setId("syncTargetCombo");
        targetCatalogCombo.setId("syncTargetCatalogCombo");
        targetSchemaCombo.setId("syncTargetSchemaCombo");
        scheduleCombo.setId("syncScheduleCombo");
        runnerCombo.setId("syncRunnerCombo");
        runnerCombo.getItems().setAll(SyncTask.Runner.values());
        runnerCombo.setConverter(converter(runner -> Messages.get("sync.runner." + runner.name().toLowerCase(Locale
                .ENGLISH))));
        runnerCombo.setValue(SyncTask.Runner.SQLJAM);
        intervalSpinner.setId("syncIntervalSpinner");
        dailyField.setId("syncDailyField");
        retriesSpinner.setId("syncRetriesSpinner");
        statusLabel.setId("syncStatusLabel");
        statusLabel.setWrapText(true);

        kindCombo.getItems().add(SyncTask.Kind.DATABASE);
        if (vectorSupport != null) {
            kindCombo.getItems().add(SyncTask.Kind.VECTOR);
        }
        kindCombo.setConverter(converter(kind -> Messages.get("sync.kind." + kind.name().toLowerCase(Locale
                .ENGLISH))));
        kindCombo.setValue(SyncTask.Kind.DATABASE);
        List<ConnectionProfile> profiles = context.getProfileRegistry().getProfiles();
        sourceCombo.getItems().setAll(profiles);
        DbTypeCells.setupProfileCombo(sourceCombo);
        targetCombo.getItems().setAll(profiles);
        DbTypeCells.setupProfileCombo(targetCombo);
        if (vectorSupport != null) {
            for (DataSourceExtension extension : DataSourceExtension.load(context.getSettingsStore().getConfig())) {
                if (extension.getSyncSupport() != null && extension.getSyncSupport().getKind()
                        == SyncTask.Kind.VECTOR) {
                    vectorSourceCombo.getItems().addAll(extension.getSources());
                }
            }
        }
        vectorSourceCombo.setConverter(converter(source -> source.getName() + " (" + source.getTypeName() + ")"));
        vectorSourceCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(DataSourceExtension.Source item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getTypeName() + ")");
            }
        });
        for (ComboBox<String> combo : List.of(sourceCatalogCombo, sourceSchemaCombo, targetCatalogCombo,
                targetSchemaCombo)) {
            combo.setEditable(true);
            combo.setMaxWidth(Double.MAX_VALUE);
        }
        watermarkCombo.setMaxWidth(Double.MAX_VALUE);
        watermarkCombo.setEditable(true);
        watermarkTypeCombo.getItems().setAll(SyncTask.WatermarkType.values());
        watermarkTypeCombo.setConverter(converter(type -> Messages.get("sync.watermark." + type.name().toLowerCase(
                Locale.ENGLISH))));
        scheduleCombo.getItems().setAll(SyncTask.ScheduleType.values());
        scheduleCombo.setConverter(converter(type -> Messages.get("sync.schedule." + type.name().toLowerCase(Locale
                .ENGLISH))));
        scheduleCombo.setValue(SyncTask.ScheduleType.INTERVAL);
        intervalSpinner.setEditable(true);
        retriesSpinner.setEditable(true);
        dailyField.setPrefColumnCount(5);

        TableColumn<TableRow, Boolean> checkColumn = new TableColumn<>();
        checkColumn.setCellValueFactory(features -> features.getValue().selected);
        checkColumn.setCellFactory(CheckBoxTableCell.forTableColumn(checkColumn));
        checkColumn.setPrefWidth(36);
        TableColumn<TableRow, String> nameColumn = new TableColumn<>(Messages.get("sync.table"));
        nameColumn.setCellValueFactory(features -> new SimpleStringProperty(features.getValue().name));
        nameColumn.setPrefWidth(240);
        TableColumn<TableRow, String> watermarkColumn = new TableColumn<>(Messages.get("sync.watermark"));
        watermarkColumn.setCellValueFactory(features -> features.getValue().watermarkColumn);
        watermarkColumn.setPrefWidth(200);
        tableView.getColumns().add(checkColumn);
        tableView.getColumns().add(nameColumn);
        tableView.getColumns().add(watermarkColumn);
        tableView.setEditable(true);
        tableView.setPrefHeight(220);
        // The tables stay in view when the dialog is short, the window scrolls instead
        tableView.setMinHeight(170);
        tableView.setPlaceholder(new Label(Messages.get("sync.noTables")));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(130);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        int row = 0;
        grid.addRow(row++, new Label(Messages.get("sync.name")), nameField);
        if (vectorSupport != null) {
            grid.addRow(row++, new Label(Messages.get("sync.kind")), kindCombo);
        }
        HBox sources = new HBox(8, sourceCombo, vectorSourceCombo);
        HBox.setHgrow(sourceCombo, Priority.ALWAYS);
        HBox.setHgrow(vectorSourceCombo, Priority.ALWAYS);
        sourceCombo.setMaxWidth(Double.MAX_VALUE);
        vectorSourceCombo.setMaxWidth(Double.MAX_VALUE);
        grid.addRow(row++, new Label(Messages.get("sync.source")), sources);
        grid.addRow(row++, new Label(Messages.get("sync.sourceCatalog")), sourceCatalogCombo);
        Label schemaLabel = new Label(Messages.get("sync.sourceSchema"));
        grid.addRow(row++, schemaLabel, sourceSchemaCombo);
        grid.add(tableView, 0, row++, 2, 1);
        Label watermarkLabel = new Label(Messages.get("sync.watermark"));
        Label keysLabel = new Label(Messages.get("sync.keys"));
        watermarkLabel.setMinWidth(Region.USE_PREF_SIZE);
        keysLabel.setMinWidth(Region.USE_PREF_SIZE);
        watermarkTypeCombo.setMinWidth(Region.USE_PREF_SIZE);
        HBox detail = new HBox(8, watermarkLabel, watermarkCombo, watermarkTypeCombo, keysLabel, keyField);
        detail.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(watermarkCombo, Priority.ALWAYS);
        HBox.setHgrow(keyField, Priority.ALWAYS);
        detail.setId("syncTableDetail");
        grid.add(detail, 0, row++, 2, 1);
        grid.addRow(row++, new Label(Messages.get("sync.target")), targetCombo);
        targetCombo.setMaxWidth(Double.MAX_VALUE);
        grid.addRow(row++, new Label(Messages.get("sync.targetCatalog")), targetCatalogCombo);
        grid.addRow(row++, new Label(Messages.get("sync.targetSchema")), targetSchemaCombo);
        HBox schedule = new HBox(8, scheduleCombo, intervalSpinner, new Label(Messages.get("sync.minutes")),
                dailyField);
        grid.addRow(row++, new Label(Messages.get("sync.schedule")), schedule);
        grid.addRow(row++, new Label(Messages.get("sync.runner")), runnerCombo);
        grid.addRow(row++, new Label(Messages.get("sync.retries")), retriesSpinner);
        Label note = new Label(Messages.get("sync.note"));
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(10, grid, note, statusLabel);
        content.setPadding(new Insets(4));
        content.setPrefWidth(760);
        // A short screen scrolls the fields, the buttons of the dialog stay in view
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        getDialogPane().setContent(scroll);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        kindCombo.valueProperty().addListener((observable, old, kind) -> kindChanged());
        sourceCombo.valueProperty().addListener((observable, old, profile) -> loadCatalogs());
        vectorSourceCombo.valueProperty().addListener((observable, old, source) -> loadCatalogs());
        sourceCatalogCombo.valueProperty().addListener((observable, old, catalog) -> {
            if (!loading) {
                loadSchemas();
            }
        });
        sourceSchemaCombo.valueProperty().addListener((observable, old, schema) -> {
            if (!loading) {
                loadTables();
            }
        });
        tableView.getSelectionModel().selectedItemProperty().addListener((observable, old, item) -> showRow(old,
                item));
        targetCombo.valueProperty().addListener((observable, old, profile) -> loadTargetSchemas());
        scheduleCombo.valueProperty().addListener((observable, old, type) -> scheduleChanged());
        getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, event -> {
            saveRow(tableView.getSelectionModel().getSelectedItem());
            String error = validate();
            if (error != null) {
                showStatus("status-error", error);
                event.consume();
            }
        });
        setResultConverter(button -> button == ButtonType.OK ? buildTask() : null);
        kindChanged();
        scheduleChanged();
        if (task != null) {
            fill(task);
        }
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> text) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : text.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    private boolean isVector() {
        return kindCombo.getValue() == SyncTask.Kind.VECTOR;
    }

    private void kindChanged() {
        boolean vector = isVector();
        sourceCombo.setVisible(!vector);
        sourceCombo.setManaged(!vector);
        vectorSourceCombo.setVisible(vector);
        vectorSourceCombo.setManaged(vector);
        sourceSchemaCombo.setDisable(vector);
        // Fields of vector databases are compared as they are, keys are their ids
        watermarkTypeCombo.setDisable(false);
        keyField.setDisable(vector);
        tableView.getItems().clear();
        loadCatalogs();
    }

    private void scheduleChanged() {
        SyncTask.ScheduleType type = scheduleCombo.getValue();
        intervalSpinner.setDisable(type != SyncTask.ScheduleType.INTERVAL);
        runnerCombo.setDisable(type == SyncTask.ScheduleType.MANUAL);
        dailyField.setDisable(type != SyncTask.ScheduleType.DAILY);
    }

    private void showStatus(String styleClass, String text) {
        statusLabel.getStyleClass().setAll("label", styleClass);
        statusLabel.setText(text);
    }

    private void fill(SyncTask task) {
        nameField.setText(task.getName());
        kindCombo.setValue(task.getKind());
        loading = true;
        if (task.getKind() == SyncTask.Kind.VECTOR) {
            vectorSourceCombo.getItems().stream().filter(source -> source.getId().equals(task.getSourceId()))
                    .findFirst().ifPresent(vectorSourceCombo::setValue);
        } else {
            sourceCombo.getItems().stream().filter(profile -> profile.getId().equals(task.getSourceId())).findFirst()
                    .ifPresent(sourceCombo::setValue);
        }
        sourceCatalogCombo.setValue(task.getSourceCatalog());
        sourceSchemaCombo.setValue(task.getSourceSchema());
        loading = false;
        loadTables();
        targetCombo.getItems().stream().filter(profile -> profile.getId().equals(task.getTargetId())).findFirst()
                .ifPresent(targetCombo::setValue);
        targetCatalogCombo.setValue(task.getTargetCatalog());
        targetSchemaCombo.setValue(task.getTargetSchema());
        scheduleCombo.setValue(task.getScheduleType());
        runnerCombo.setValue(task.getRunner());
        intervalSpinner.getValueFactory().setValue(task.getIntervalMinutes());
        dailyField.setText(task.getDailyTime());
        retriesSpinner.getValueFactory().setValue(task.getRetries());
    }

    private String sourceId() {
        return isVector() ? (vectorSourceCombo.getValue() != null ? vectorSourceCombo.getValue().getId() : null)
                : (sourceCombo.getValue() != null ? sourceCombo.getValue().getId() : null);
    }

    private void loadCatalogs() {
        if (loading) {
            return;
        }
        sourceCatalogCombo.getItems().clear();
        sourceSchemaCombo.getItems().clear();
        tableView.getItems().clear();
        String sourceId = sourceId();
        if (sourceId == null) {
            return;
        }
        ConnectionProfile profile = sourceCombo.getValue();
        boolean vector = isVector();
        TaskRunner.run(() -> vector ? vectorSupport.getDatabases(sourceId) : context.getSessionManager()
                .getSession(profile).getCatalogs(), catalogs -> {
            if (!sourceId.equals(sourceId())) {
                return;
            }
            sourceCatalogCombo.getItems().setAll(catalogs);
            String current = vector ? null : profile.getDatabase();
            sourceCatalogCombo.setValue(catalogs.contains(current) ? current : catalogs.isEmpty() ? null
                    : vector ? null : catalogs.get(0));
            if (catalogs.isEmpty() || vector) {
                loadSchemas();
            }
        }, e -> showStatus("status-error", Messages.format("tree.error", e.getMessage())));
    }

    private void loadSchemas() {
        String sourceId = sourceId();
        if (sourceId == null) {
            return;
        }
        if (isVector()) {
            loadTables();
            return;
        }
        ConnectionProfile profile = sourceCombo.getValue();
        String catalog = sourceCatalogCombo.getValue();
        TaskRunner.run(() -> context.getSessionManager().getSession(profile).getSchemas(catalog), schemas -> {
            if (!sourceId.equals(sourceId())) {
                return;
            }
            sourceSchemaCombo.getItems().setAll(schemas);
            sourceSchemaCombo.setValue(schemas.isEmpty() ? null : schemas.contains("public") ? "public"
                    : schemas.contains("PUBLIC") ? "PUBLIC" : schemas.contains("dbo") ? "dbo" : schemas.get(0));
            if (schemas.isEmpty()) {
                loadTables();
            }
        }, e -> showStatus("status-error", Messages.format("tree.error", e.getMessage())));
    }

    private void loadTables() {
        String sourceId = sourceId();
        tableView.getItems().clear();
        if (sourceId == null) {
            return;
        }
        boolean vector = isVector();
        ConnectionProfile profile = sourceCombo.getValue();
        String catalog = StringUtils.trimToNull(sourceCatalogCombo.getValue());
        String schema = StringUtils.trimToNull(sourceSchemaCombo.getValue());
        TaskRunner.run(() -> vector ? vectorSupport.getCollections(sourceId, catalog) : context.getSessionManager()
                .getSession(profile).getTables(catalog, schema).stream().map(TableInfo::getName).collect(Collectors
                        .toList()), names -> {
            if (!sourceId.equals(sourceId())) {
                return;
            }
            List<TableRow> rows = new ArrayList<>();
            for (String name : names) {
                TableRow row = new TableRow(name);
                SyncTask.Table saved = original != null && original.getKind() == (vector ? SyncTask.Kind.VECTOR
                        : SyncTask.Kind.DATABASE) && sourceId.equals(original.getSourceId()) ? original.findTable(
                        name) : null;
                if (saved != null) {
                    row.saved = saved;
                    row.selected.set(true);
                    row.watermarkColumn.set(saved.getWatermarkColumn());
                    row.watermarkType.set(saved.getWatermarkType());
                    row.keyColumns.set(String.join(", ", saved.getKeyColumns()));
                }
                // A checked table shows its watermark, its columns are read
                row.selected.addListener((observable, was, checked) -> {
                    if (checked) {
                        tableView.getSelectionModel().select(row);
                    }
                });
                rows.add(row);
            }
            tableView.getItems().setAll(rows);
            if (!rows.isEmpty()) {
                tableView.getSelectionModel().select(0);
            }
        }, e -> showStatus("status-error", Messages.format("tree.error", e.getMessage())));
    }

    private void loadTargetSchemas() {
        ConnectionProfile profile = targetCombo.getValue();
        if (profile == null) {
            return;
        }
        TaskRunner.run(() -> {
            List<String> catalogs = context.getSessionManager().getSession(profile).getCatalogs();
            String catalog = StringUtils.defaultIfBlank(targetCatalogCombo.getValue(), catalogs.contains(profile
                    .getDatabase()) ? profile.getDatabase() : null);
            return new Object[]{catalogs, catalog, profile.getDbType().isSchemaSupported() ? context
                    .getSessionManager().getSession(profile).getSchemas(catalog) : List.of()};
        }, result -> {
            if (profile != targetCombo.getValue()) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> catalogs = (List<String>) result[0];
            @SuppressWarnings("unchecked")
            List<String> schemas = (List<String>) result[2];
            String catalog = targetCatalogCombo.getValue();
            String schema = targetSchemaCombo.getValue();
            targetCatalogCombo.getItems().setAll(catalogs);
            targetCatalogCombo.setValue(StringUtils.defaultIfBlank(catalog, (String) result[1]));
            targetSchemaCombo.getItems().setAll(schemas);
            targetSchemaCombo.setValue(StringUtils.isNotBlank(schema) ? schema : schemas.contains("public")
                    ? "public" : schemas.contains("PUBLIC") ? "PUBLIC" : schemas.contains("dbo") ? "dbo" : null);
        }, e -> showStatus("status-error", Messages.format("tree.error", e.getMessage())));
    }

    /**
     * Shows the watermark of the row, its candidates are read the first time
     */
    private void showRow(TableRow old, TableRow row) {
        saveRow(old);
        watermarkCombo.getItems().clear();
        watermarkCombo.setValue(null);
        keyField.clear();
        if (row == null) {
            return;
        }
        watermarkTypeCombo.setValue(row.watermarkType.get());
        keyField.setText(row.keyColumns.get());
        if (row.candidates != null) {
            watermarkCombo.getItems().setAll(row.candidates);
            watermarkCombo.setValue(row.watermarkColumn.get());
            return;
        }
        boolean vector = isVector();
        String sourceId = sourceId();
        ConnectionProfile profile = sourceCombo.getValue();
        String catalog = StringUtils.trimToNull(sourceCatalogCombo.getValue());
        String schema = StringUtils.trimToNull(sourceSchemaCombo.getValue());
        TaskRunner.run(() -> vector ? vectorSupport.getWatermarkFields(sourceId, catalog, row.name) : columns(context
                .getSessionManager().getSession(profile).getColumns(catalog, schema, row.name), row), candidates -> {
            row.candidates = candidates;
            if (row.watermarkColumn.get() == null && !candidates.isEmpty()) {
                row.watermarkColumn.set(candidates.get(0));
            }
            if (tableView.getSelectionModel().getSelectedItem() == row) {
                watermarkCombo.getItems().setAll(candidates);
                watermarkCombo.setValue(row.watermarkColumn.get());
                watermarkTypeCombo.setValue(row.watermarkType.get());
            }
        }, e -> showStatus("status-error", Messages.format("tree.error", e.getMessage())));
    }

    /**
     * Columns which can be watermarks, the guessed one first: a time named like an update time, a number key
     */
    static List<String> columns(List<ColumnInfo> columns, TableRow row) {
        List<String> candidates = columns.stream().filter(column -> WATERMARK_TYPES.matcher(StringUtils
                .defaultString(column.getTypeName())).matches()).map(ColumnInfo::getName).collect(Collectors
                .toCollection(ArrayList::new));
        ColumnInfo updated = columns.stream().filter(column -> TIME_TYPES.matcher(StringUtils.defaultString(column
                .getTypeName())).matches() && UPDATED_NAMES.matcher(column.getName()).matches()).findFirst()
                .orElse(null);
        ColumnInfo key = columns.stream().filter(column -> column.isPrimaryKey() && !TIME_TYPES.matcher(StringUtils
                .defaultString(column.getTypeName())).matches() && WATERMARK_TYPES.matcher(StringUtils.defaultString(
                column.getTypeName())).matches()).findFirst().orElse(null);
        ColumnInfo guess = updated != null ? updated : key;
        if (guess != null && row.watermarkColumn.get() == null) {
            candidates.remove(guess.getName());
            candidates.add(0, guess.getName());
            row.watermarkType.set(guess == updated ? SyncTask.WatermarkType.UPDATED_AT
                    : SyncTask.WatermarkType.INCREASING_KEY);
        }
        return candidates;
    }

    private void saveRow(TableRow row) {
        if (row == null) {
            return;
        }
        String column = StringUtils.trimToNull(watermarkCombo.getEditor() != null && watermarkCombo.isEditable()
                ? watermarkCombo.getEditor().getText() : watermarkCombo.getValue());
        if (column == null) {
            column = watermarkCombo.getValue();
        }
        if (column != null) {
            row.watermarkColumn.set(column);
        }
        if (watermarkTypeCombo.getValue() != null) {
            row.watermarkType.set(watermarkTypeCombo.getValue());
        }
        row.keyColumns.set(StringUtils.defaultString(keyField.getText()).trim());
    }

    List<TableRow> getSelectedRows() {
        return tableView.getItems().stream().filter(row -> row.selected.get()).collect(Collectors.toList());
    }

    String validate() {
        if (StringUtils.isBlank(nameField.getText())) {
            return Messages.get("sync.error.name");
        }
        if (sourceId() == null) {
            return Messages.get("sync.error.source");
        }
        if (targetCombo.getValue() == null) {
            return Messages.get("sync.error.target");
        }
        List<TableRow> rows = getSelectedRows();
        if (rows.isEmpty()) {
            return Messages.get("sync.error.tables");
        }
        for (TableRow row : rows) {
            if (StringUtils.isBlank(row.watermarkColumn.get())) {
                return Messages.format("sync.error.watermark", row.name);
            }
        }
        if (!isVector() && targetCombo.getValue().getId().equals(sourceId()) && StringUtils.equals(StringUtils
                .trimToNull(sourceCatalogCombo.getValue()), StringUtils.trimToNull(targetCatalogCombo.getValue()))
                && StringUtils.equalsIgnoreCase(StringUtils.trimToNull(sourceSchemaCombo.getValue()), StringUtils
                .trimToNull(targetSchemaCombo.getValue()))) {
            return Messages.get("sync.error.same");
        }
        if (scheduleCombo.getValue() == SyncTask.ScheduleType.DAILY && !dailyField.getText().trim().matches(
                "([01]?\\d|2[0-3]):[0-5]\\d")) {
            return Messages.get("sync.error.time");
        }
        return null;
    }

    /**
     * The task of the dialog: an edited task keeps its id, its runs and the watermarks of the tables whose
     * watermark column stays
     */
    SyncTask buildTask() {
        SyncTask task;
        try {
            task = original != null ? MAPPER.readValue(MAPPER.writeValueAsString(original), SyncTask.class)
                    : new SyncTask();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        task.setName(nameField.getText().trim());
        task.setKind(kindCombo.getValue());
        task.setSourceId(sourceId());
        task.setSourceCatalog(StringUtils.trimToNull(sourceCatalogCombo.getValue()));
        task.setSourceSchema(isVector() ? null : StringUtils.trimToNull(sourceSchemaCombo.getValue()));
        task.setTargetId(targetCombo.getValue().getId());
        task.setTargetCatalog(StringUtils.trimToNull(targetCatalogCombo.getValue()));
        task.setTargetSchema(StringUtils.trimToNull(targetSchemaCombo.getValue()));
        List<SyncTask.Table> tables = new ArrayList<>();
        for (TableRow row : getSelectedRows()) {
            SyncTask.Table table = new SyncTask.Table(row.name, row.watermarkColumn.get().trim(), row.watermarkType
                    .get());
            table.setKeyColumns(Arrays.stream(StringUtils.defaultString(row.keyColumns.get()).split(",")).map(
                    String::trim).filter(StringUtils::isNotEmpty).collect(Collectors.toList()));
            SyncTask.Table saved = original != null ? original.findTable(row.name) : null;
            boolean sameSource = original != null && StringUtils.equals(original.getSourceId(), task.getSourceId());
            if (saved != null && sameSource && saved.getWatermarkColumn().equals(table.getWatermarkColumn())
                    && saved.getWatermarkType() == table.getWatermarkType()) {
                table.setWatermark(saved.getWatermark());
            }
            tables.add(table);
        }
        task.setTables(tables);
        task.setScheduleType(scheduleCombo.getValue());
        task.setRunner(runnerCombo.getValue());
        task.setIntervalMinutes(intervalSpinner.getValue());
        task.setDailyTime(dailyField.getText().trim());
        task.setRetries(retriesSpinner.getValue());
        task.setPageSize(context.getSettings().getTransferPageSize());
        return task;
    }

    /**
     * The fields of the dialog, in its scroll pane
     */
    Node getFields() {
        return ((javafx.scene.control.ScrollPane) getDialogPane().getContent()).getContent();
    }
}
