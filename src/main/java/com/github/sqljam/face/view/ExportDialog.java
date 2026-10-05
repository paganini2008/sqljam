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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.IdentifierCase;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * @Description: ExportDialog collects an export to sql scripts or an import into another database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ExportDialog extends Dialog<TransferRequest> {

    private static final long MB = 1024L * 1024L;
    private static final Map<DbType, List<String>> VERSION_PRESETS = new EnumMap<>(DbType.class);

    static {
        VERSION_PRESETS.put(DbType.MYSQL, List.of("", "5.6", "5.7", "8.0"));
        VERSION_PRESETS.put(DbType.POSTGRESQL, List.of("", "9.6", "10", "12", "16"));
        VERSION_PRESETS.put(DbType.ORACLE, List.of("", "11.2", "12.1", "19", "23"));
        VERSION_PRESETS.put(DbType.SQLSERVER, List.of("", "2008", "2012", "2016", "2019", "2022"));
        VERSION_PRESETS.put(DbType.H2, List.of("", "2.2"));
        VERSION_PRESETS.put(DbType.SQLITE, List.of("", "3"));
    }

    private final AppContext context;

    // Source
    private final ComboBox<ConnectionProfile> sourceCombo = new ComboBox<>();
    private final ComboBox<String> catalogCombo = new ComboBox<>();
    private final ComboBox<String> schemaCombo = new ComboBox<>();
    private final ObservableList<TableChoice> tableChoices = FXCollections.observableArrayList();
    private final FilteredList<TableChoice> filteredChoices = new FilteredList<>(tableChoices, choice -> true);
    private final ListView<TableChoice> tableList = new ListView<>(filteredChoices);
    private final Label tableCountLabel = new Label();

    // Content and options
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final CheckBox recreateCheck = new CheckBox(Messages.get("export.option.recreate"));
    private final CheckBox idReusedCheck = new CheckBox(Messages.get("export.option.idReused"));
    private final CheckBox indexCheck = new CheckBox(Messages.get("export.option.indexes"));
    private final CheckBox foreignKeyCheck = new CheckBox(Messages.get("export.option.foreignKeys"));
    private final CheckBox commentCheck = new CheckBox(Messages.get("export.option.comments"));
    private final CheckBox sequenceCheck = new CheckBox(Messages.get("export.option.sequences"));
    private final CheckBox failFastCheck = new CheckBox(Messages.get("export.option.failFast"));
    private final Spinner<Integer> pageSizeSpinner = new Spinner<>(10, 100000, Exporter.DEFAULT_PAGE_SIZE, 500);
    private final ComboBox<IdentifierCase> identifierCaseCombo = new ComboBox<>();

    // Target
    private final ToggleGroup targetGroup = new ToggleGroup();
    private final RadioButton scriptRadio = new RadioButton(Messages.get("export.target.script"));
    private final RadioButton databaseRadio = new RadioButton(Messages.get("export.target.database"));
    private final TextField directoryField = new TextField();
    private final ComboBox<DbType> scriptDbTypeCombo = new ComboBox<>();
    private final ComboBox<String> scriptVersionCombo = new ComboBox<>();
    private final TextField scriptSchemaField = new TextField();
    private final ToggleGroup strategyGroup = new ToggleGroup();
    private final RadioButton singleFileRadio = new RadioButton(Messages.get("export.script.singleFile"));
    private final RadioButton perTableRadio = new RadioButton(Messages.get("export.script.perTable"));
    private final Spinner<Integer> maxFileSizeSpinner = new Spinner<>(0, 100000, 10, 10);
    private final CheckBox lobSeparatedCheck = new CheckBox(Messages.get("export.script.lobSeparated"));
    private final Label layoutHint = new Label();
    private final ComboBox<ConnectionProfile> targetCombo = new ComboBox<>();
    private final ComboBox<String> targetCatalogCombo = new ComboBox<>();
    private final ComboBox<String> targetSchemaCombo = new ComboBox<>();
    private final CheckBox createSchemaCheck = new CheckBox(Messages.get("export.database.createSchema"));
    private final Label errorLabel = new Label();
    private Button startButton;

    /**
     * Table of the checklist
     */
    private static class TableChoice {

        private final TableInfo table;
        private final BooleanProperty selected = new SimpleBooleanProperty();

        TableChoice(TableInfo table, boolean selected) {
            this.table = table;
            this.selected.set(selected);
        }
    }

    public ExportDialog(Window owner, AppContext context, DbNode node) {
        this.context = context;
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        setTitle(Messages.get("export.title"));
        setHeaderText(Messages.get("export.header"));
        setResizable(true);

        sourceCombo.setId("sourceCombo");
        catalogCombo.setId("catalogCombo");
        schemaCombo.setId("schemaCombo");
        tableList.setId("tableList");
        tableCountLabel.setId("tableCountLabel");
        pageSizeSpinner.setId("pageSizeSpinner");
        identifierCaseCombo.setId("identifierCaseCombo");
        scriptRadio.setId("scriptRadio");
        databaseRadio.setId("databaseRadio");
        directoryField.setId("directoryField");
        scriptDbTypeCombo.setId("scriptDbTypeCombo");
        scriptVersionCombo.setId("scriptVersionCombo");
        scriptSchemaField.setId("scriptSchemaField");
        singleFileRadio.setId("singleFileRadio");
        perTableRadio.setId("perTableRadio");
        maxFileSizeSpinner.setId("maxFileSizeSpinner");
        lobSeparatedCheck.setId("lobSeparatedCheck");
        layoutHint.setId("layoutHint");
        targetCombo.setId("targetCombo");
        targetCatalogCombo.setId("targetCatalogCombo");
        targetSchemaCombo.setId("targetSchemaCombo");
        createSchemaCheck.setId("createSchemaCheck");
        errorLabel.setId("errorLabel");
        recreateCheck.setId("recreateCheck");
        idReusedCheck.setId("idReusedCheck");
        indexCheck.setId("indexCheck");
        foreignKeyCheck.setId("foreignKeyCheck");
        commentCheck.setId("commentCheck");
        sequenceCheck.setId("sequenceCheck");
        failFastCheck.setId("failFastCheck");
        VBox content = new VBox(16, section("export.section.source", createSourcePane()),
                section("export.section.content", createContentPane()),
                section("export.section.options", createOptionsPane()),
                section("export.section.target", createTargetPane()));
        content.setPadding(new Insets(4, 8, 4, 4));
        errorLabel.getStyleClass().add("status-error");
        errorLabel.setWrapText(true);
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefSize(760, 660);
        VBox root = new VBox(8, scrollPane, errorLabel);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        startButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        startButton.setId("startButton");
        startButton.setText(Messages.get("export.start"));
        startButton.setGraphic(Icons.of(Icons.START));
        startButton.addEventFilter(ActionEvent.ACTION, event -> {
            String error = validate();
            if (error != null) {
                errorLabel.setText(error);
                event.consume();
            }
        });
        setResultConverter(buttonType -> buttonType == ButtonType.OK ? buildRequest() : null);
        registerValidation();
        preselect(node);
        updateValidation();
    }

    /**
     * Start is enabled only when the inputs are valid, the reason is shown below the form
     */
    private void registerValidation() {
        javafx.beans.InvalidationListener listener = observable -> updateValidation();
        sourceCombo.valueProperty().addListener(listener);
        targetGroup.selectedToggleProperty().addListener(listener);
        directoryField.textProperty().addListener(listener);
        targetCombo.valueProperty().addListener(listener);
        scriptVersionCombo.getEditor().textProperty().addListener(listener);
        scriptVersionCombo.valueProperty().addListener(listener);
        pageSizeSpinner.getEditor().textProperty().addListener(listener);
        maxFileSizeSpinner.getEditor().textProperty().addListener(listener);
        tableChoices.addListener(listener);
    }

    void updateValidation() {
        String error = validate();
        errorLabel.setText(StringUtils.defaultString(error));
        startButton.setDisable(error != null);
    }

    private static Node section(String key, Node body) {
        Label title = new Label(Messages.get(key));
        title.getStyleClass().add("section-title");
        VBox box = new VBox(8, title, body);
        box.getStyleClass().add("form-section");
        return box;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(150);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        return grid;
    }

    private Node createSourcePane() {
        DbTypeCells.setupProfileCombo(sourceCombo);
        sourceCombo.getItems().setAll(context.getProfileRegistry().getProfiles());
        sourceCombo.setMaxWidth(Double.MAX_VALUE);
        catalogCombo.setMaxWidth(Double.MAX_VALUE);
        schemaCombo.setMaxWidth(Double.MAX_VALUE);
        sourceCombo.valueProperty().addListener((obs, oldProfile, profile) -> loadCatalogs(profile, null, null,
                null));
        catalogCombo.valueProperty().addListener((obs, oldCatalog, catalog) -> {
            if (catalog != null && !catalogCombo.isDisabled()) {
                loadSchemas(catalog, null, null);
            }
        });
        schemaCombo.valueProperty().addListener((obs, oldSchema, schema) -> {
            if (schema != null) {
                loadTables(catalogCombo.getValue(), schema, null);
            }
        });

        TextField filterField = new TextField();
        filterField.setPromptText(Messages.get("export.tables.filter"));
        filterField.textProperty().addListener((obs, oldText, text) -> filteredChoices.setPredicate(choice ->
                StringUtils.isBlank(text) || StringUtils.containsIgnoreCase(choice.table.getName(), text)));
        filterField.setId("tableFilterField");
        Button allButton = new Button(Messages.get("export.tables.all"));
        allButton.setId("selectAllButton");
        allButton.setOnAction(event -> filteredChoices.forEach(choice -> choice.selected.set(true)));
        Button noneButton = new Button(Messages.get("export.tables.none"));
        noneButton.setId("selectNoneButton");
        noneButton.setOnAction(event -> filteredChoices.forEach(choice -> choice.selected.set(false)));
        HBox filterBox = new HBox(8, filterField, allButton, noneButton, tableCountLabel);
        filterBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filterField, Priority.ALWAYS);
        tableList.setCellFactory(CheckBoxListCell.forListView(choice -> choice.selected,
                new StringConverter<>() {
                    @Override
                    public String toString(TableChoice choice) {
                        return choice != null ? choice.table.getName() : "";
                    }

                    @Override
                    public TableChoice fromString(String text) {
                        return null;
                    }
                }));
        tableList.setPrefHeight(200);
        tableList.setPlaceholder(new Label(Messages.get("export.tables.empty")));

        GridPane grid = grid();
        grid.addRow(0, new Label(Messages.get("export.source.connection")), sourceCombo);
        grid.addRow(1, new Label(Messages.get("export.source.catalog")), catalogCombo);
        grid.addRow(2, new Label(Messages.get("export.source.schema")), schemaCombo);
        VBox box = new VBox(8, grid, new Label(Messages.get("export.tables.hint")), filterBox, tableList);
        return box;
    }

    private Node createContentPane() {
        RadioButton ddlRadio = new RadioButton(Messages.get("export.mode.ddl"));
        ddlRadio.setUserData(ExportMode.DDL);
        RadioButton ddlDataRadio = new RadioButton(Messages.get("export.mode.ddlData"));
        ddlDataRadio.setUserData(ExportMode.DDL_DATA);
        RadioButton dataRadio = new RadioButton(Messages.get("export.mode.data"));
        dataRadio.setUserData(ExportMode.DATA);
        ddlRadio.setId("ddlRadio");
        ddlDataRadio.setId("ddlDataRadio");
        dataRadio.setId("dataRadio");
        ddlRadio.setToggleGroup(modeGroup);
        ddlDataRadio.setToggleGroup(modeGroup);
        dataRadio.setToggleGroup(modeGroup);
        ddlDataRadio.setSelected(true);
        return new HBox(16, ddlRadio, ddlDataRadio, dataRadio);
    }

    private Node createOptionsPane() {
        recreateCheck.setSelected(true);
        idReusedCheck.setSelected(true);
        indexCheck.setSelected(true);
        foreignKeyCheck.setSelected(true);
        commentCheck.setSelected(true);
        sequenceCheck.setSelected(true);
        failFastCheck.setSelected(true);
        pageSizeSpinner.setEditable(true);
        pageSizeSpinner.getValueFactory().setValue(Math.max(10, context.getSettings().getTransferPageSize()));
        identifierCaseCombo.getItems().addAll(IdentifierCase.values());
        identifierCaseCombo.setValue(IdentifierCase.AUTO);
        identifierCaseCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(IdentifierCase value) {
                return value != null ? Messages.get("identifierCase." + value.name()) : "";
            }

            @Override
            public IdentifierCase fromString(String text) {
                return null;
            }
        });
        GridPane checks = new GridPane();
        checks.setHgap(24);
        checks.setVgap(8);
        checks.addRow(0, recreateCheck, idReusedCheck, indexCheck);
        checks.addRow(1, foreignKeyCheck, commentCheck, sequenceCheck);
        checks.addRow(2, failFastCheck);
        GridPane grid = grid();
        grid.addRow(0, new Label(Messages.get("export.option.pageSize")), pageSizeSpinner);
        grid.addRow(1, new Label(Messages.get("export.option.identifierCase")), identifierCaseCombo);
        return new VBox(12, checks, grid);
    }

    private Node createTargetPane() {
        scriptRadio.setToggleGroup(targetGroup);
        databaseRadio.setToggleGroup(targetGroup);
        scriptRadio.setSelected(true);

        // Script target
        String lastDirectory = context.getSettings().getLastExportDirectory();
        directoryField.setText(StringUtils.defaultString(lastDirectory));
        directoryField.setPromptText(Messages.get("export.script.directory.prompt"));
        Button browseButton = new Button(null, Icons.of(Icons.FOLDER));
        browseButton.setOnAction(event -> chooseDirectory());
        HBox directoryBox = new HBox(8, directoryField, browseButton);
        HBox.setHgrow(directoryField, Priority.ALWAYS);
        scriptDbTypeCombo.getItems().addAll(DbType.values());
        DbTypeCells.setupDbTypeCombo(scriptDbTypeCombo);
        scriptDbTypeCombo.valueProperty().addListener((obs, oldType, type) -> {
            scriptVersionCombo.getItems().setAll(VERSION_PRESETS.getOrDefault(type, List.of("")));
            scriptVersionCombo.setValue("");
        });
        scriptVersionCombo.setEditable(true);
        scriptVersionCombo.setPromptText(Messages.get("export.script.version.prompt"));
        scriptSchemaField.setPromptText(Messages.get("export.script.schema.prompt"));
        singleFileRadio.setToggleGroup(strategyGroup);
        perTableRadio.setToggleGroup(strategyGroup);
        singleFileRadio.setSelected(true);
        maxFileSizeSpinner.setEditable(true);
        long maxFileSize = context.getSettings().getMaxDataFileSize();
        maxFileSizeSpinner.getValueFactory().setValue(maxFileSize > 0 ? (int) Math.max(1, maxFileSize / MB) : 10);
        lobSeparatedCheck.setSelected(true);
        layoutHint.getStyleClass().addAll("muted", "mono");
        layoutHint.setWrapText(true);
        strategyGroup.selectedToggleProperty().addListener((obs, oldToggle, toggle) -> updateLayoutHint());
        lobSeparatedCheck.selectedProperty().addListener((obs, oldValue, value) -> updateLayoutHint());
        maxFileSizeSpinner.valueProperty().addListener((obs, oldValue, value) -> updateLayoutHint());
        updateLayoutHint();
        GridPane scriptGrid = grid();
        int row = 0;
        scriptGrid.addRow(row++, new Label(Messages.get("export.script.directory")), directoryBox);
        scriptGrid.addRow(row++, new Label(Messages.get("export.script.dbType")), scriptDbTypeCombo);
        scriptGrid.addRow(row++, new Label(Messages.get("export.script.version")), scriptVersionCombo);
        scriptGrid.addRow(row++, new Label(Messages.get("export.script.schema")), scriptSchemaField);
        scriptGrid.addRow(row++, new Label(Messages.get("export.script.strategy")), new HBox(16, singleFileRadio,
                perTableRadio));
        scriptGrid.addRow(row++, new Label(Messages.get("export.script.maxFileSize")), new HBox(8,
                maxFileSizeSpinner, new Label(Messages.get("export.script.maxFileSize.unit"))));
        scriptGrid.add(lobSeparatedCheck, 1, row++);
        scriptGrid.add(layoutHint, 1, row);
        VBox scriptPane = new VBox(scriptGrid);
        scriptPane.getStyleClass().add("target-pane");

        // Database target
        DbTypeCells.setupProfileCombo(targetCombo);
        targetCombo.getItems().setAll(context.getProfileRegistry().getProfiles());
        targetCombo.setMaxWidth(Double.MAX_VALUE);
        targetCatalogCombo.setMaxWidth(Double.MAX_VALUE);
        targetSchemaCombo.setMaxWidth(Double.MAX_VALUE);
        targetSchemaCombo.setEditable(true);
        createSchemaCheck.setSelected(true);
        targetCombo.valueProperty().addListener((obs, oldProfile, profile) -> loadTargetCatalogs(profile));
        targetCatalogCombo.valueProperty().addListener((obs, oldCatalog, catalog) -> {
            ConnectionProfile profile = targetCombo.getValue();
            if (catalog != null && profile != null && profile.getDbType().isSchemaSupported()) {
                loadTargetSchemas(profile, catalog);
            }
        });
        GridPane databaseGrid = grid();
        databaseGrid.addRow(0, new Label(Messages.get("export.database.connection")), targetCombo);
        databaseGrid.addRow(1, new Label(Messages.get("export.database.catalog")), targetCatalogCombo);
        databaseGrid.addRow(2, new Label(Messages.get("export.database.schema")), targetSchemaCombo);
        databaseGrid.add(createSchemaCheck, 1, 3);
        VBox databasePane = new VBox(databaseGrid);
        databasePane.getStyleClass().add("target-pane");

        databasePane.visibleProperty().bind(databaseRadio.selectedProperty());
        databasePane.managedProperty().bind(databaseRadio.selectedProperty());
        scriptPane.visibleProperty().bind(scriptRadio.selectedProperty());
        scriptPane.managedProperty().bind(scriptRadio.selectedProperty());
        return new VBox(10, new HBox(16, scriptRadio, databaseRadio), scriptPane, databasePane);
    }

    private void updateLayoutHint() {
        boolean perTable = perTableRadio.isSelected();
        boolean split = maxFileSizeSpinner.getValue() != null && maxFileSizeSpinner.getValue() > 0;
        StringBuilder hint = new StringBuilder();
        hint.append("schema.sql\n");
        if (perTable) {
            hint.append("data/<table>.sql").append(split ? ", <table>_2.sql ..." : "").append('\n');
        } else {
            hint.append("data.sql").append(split ? ", data_2.sql ..." : "").append('\n');
        }
        if (lobSeparatedCheck.isSelected()) {
            hint.append("lob/<table>/000001_<column>.clob|blob\nlob-manifest.json\n");
        }
        hint.append("constraints.sql\nmanifest.json");
        layoutHint.setText(hint.toString());
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.get("export.script.directory"));
        File current = new File(StringUtils.defaultString(directoryField.getText()));
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        } else if (current.getParentFile() != null && current.getParentFile().isDirectory()) {
            chooser.setInitialDirectory(current.getParentFile());
        }
        File directory = chooser.showDialog(getDialogPane().getScene().getWindow());
        if (directory != null) {
            directoryField.setText(directory.getAbsolutePath());
        }
    }

    private void preselect(DbNode node) {
        if (node == null || node.getProfile() == null) {
            if (!sourceCombo.getItems().isEmpty()) {
                sourceCombo.getSelectionModel().selectFirst();
            }
            return;
        }
        ConnectionProfile profile = sourceCombo.getItems().stream()
                .filter(item -> item.getId().equals(node.getProfile().getId())).findFirst()
                .orElse(node.getProfile());
        String table = node.getTable() != null ? node.getTable().getName() : null;
        // Selecting the profile triggers loading, the node path is passed to keep the selection
        sourceCombo.setValue(null);
        sourceCombo.getSelectionModel().select(profile);
        loadCatalogs(profile, node.getCatalog(), node.getSchema(), table);
    }

    private DatabaseSession getSession(ConnectionProfile profile) {
        return context.getSessionManager().getSession(profile);
    }

    private void loadCatalogs(ConnectionProfile profile, String catalog, String schema, String table) {
        catalogCombo.getItems().clear();
        schemaCombo.getItems().clear();
        tableChoices.clear();
        if (profile == null) {
            return;
        }
        scriptDbTypeCombo.setValue(profile.getDbType());
        DatabaseSession session = getSession(profile);
        int generation = ++loadGeneration;
        TaskRunner.run(session::getCatalogs, catalogs -> {
            if (generation != loadGeneration || !profile.equals(sourceCombo.getValue())) {
                return;
            }
            catalogCombo.setDisable(catalogs.isEmpty());
            if (catalogs.isEmpty()) {
                loadSchemas(null, schema, table);
                return;
            }
            catalogCombo.getItems().setAll(catalogs);
            String selected = catalog != null && catalogs.contains(catalog) ? catalog
                    : catalogs.contains(profile.getDatabase()) ? profile.getDatabase() : catalogs.get(0);
            // Value listener loads schemas without the preselected path
            catalogCombo.setDisable(true);
            catalogCombo.setValue(selected);
            catalogCombo.setDisable(false);
            loadSchemas(selected, schema, table);
        }, this::showError);
    }

    private void loadSchemas(String catalog, String schema, String table) {
        ConnectionProfile profile = sourceCombo.getValue();
        schemaCombo.getItems().clear();
        tableChoices.clear();
        if (profile == null) {
            return;
        }
        if (!profile.getDbType().isSchemaSupported()) {
            schemaCombo.setDisable(true);
            loadTables(catalog, null, table);
            return;
        }
        int generation = ++loadGeneration;
        TaskRunner.run(() -> getSession(profile).getSchemas(catalog), schemas -> {
            if (generation != loadGeneration) {
                return;
            }
            schemaCombo.setDisable(schemas.isEmpty());
            schemaCombo.getItems().setAll(schemas);
            if (schemas.isEmpty()) {
                loadTables(catalog, null, table);
                return;
            }
            String selected = schema != null && schemas.contains(schema) ? schema : getDefaultSchema(profile,
                    schemas);
            if (StringUtils.equals(selected, schemaCombo.getValue())) {
                loadTables(catalog, selected, table);
            } else {
                // Value listener loads tables, the preselected table is applied after loading
                pendingTable = table;
                schemaCombo.setValue(selected);
            }
        }, this::showError);
    }

    private String pendingTable;
    /**
     * Each loading of catalogs, schemas or tables increases the generation, results of stale loadings (e.g. the
     * source changed again before the loading finished) are discarded
     */
    private int loadGeneration;

    private static String getDefaultSchema(ConnectionProfile profile, List<String> schemas) {
        List<String> candidates = new ArrayList<>();
        if (StringUtils.isNotBlank(profile.getUsername())) {
            candidates.add(profile.getUsername());
        }
        candidates.addAll(List.of("public", "dbo", "PUBLIC"));
        for (String candidate : candidates) {
            for (String schema : schemas) {
                if (schema.equalsIgnoreCase(candidate)) {
                    return schema;
                }
            }
        }
        return schemas.get(0);
    }

    private void loadTables(String catalog, String schema, String table) {
        ConnectionProfile profile = sourceCombo.getValue();
        tableChoices.clear();
        if (profile == null) {
            return;
        }
        String preselected = table != null ? table : pendingTable;
        pendingTable = null;
        int generation = ++loadGeneration;
        TaskRunner.run(() -> getSession(profile).getTables(catalog, schema), tables -> {
            if (generation != loadGeneration) {
                return;
            }
            List<TableChoice> choices = tables.stream().filter(info -> !info.isPartition())
                    .map(info -> new TableChoice(info, preselected == null || info.getName().equals(preselected)))
                    .collect(Collectors.toList());
            choices.forEach(choice -> choice.selected.addListener((obs, oldValue, value) -> {
                updateTableCount();
                updateValidation();
            }));
            tableChoices.setAll(choices);
            updateTableCount();
            if (preselected != null) {
                choices.stream().filter(choice -> choice.selected.get()).findFirst().ifPresent(tableList::scrollTo);
            }
        }, this::showError);
    }

    private void updateTableCount() {
        long selected = tableChoices.stream().filter(choice -> choice.selected.get()).count();
        tableCountLabel.setText(Messages.format("export.tables.count", selected, tableChoices.size()));
    }

    private void loadTargetCatalogs(ConnectionProfile profile) {
        targetCatalogCombo.getItems().clear();
        targetSchemaCombo.getItems().clear();
        if (profile == null) {
            return;
        }
        TaskRunner.run(() -> getSession(profile).getCatalogs(), catalogs -> {
            targetCatalogCombo.setDisable(catalogs.isEmpty());
            targetCatalogCombo.getItems().setAll(catalogs);
            if (catalogs.isEmpty()) {
                if (profile.getDbType().isSchemaSupported()) {
                    loadTargetSchemas(profile, null);
                } else {
                    targetSchemaCombo.setDisable(true);
                }
                return;
            }
            targetCatalogCombo.setValue(catalogs.contains(profile.getDatabase()) ? profile.getDatabase()
                    : catalogs.get(0));
            targetSchemaCombo.setDisable(!profile.getDbType().isSchemaSupported());
        }, this::showError);
    }

    private void loadTargetSchemas(ConnectionProfile profile, String catalog) {
        targetSchemaCombo.setDisable(false);
        TaskRunner.run(() -> getSession(profile).getSchemas(catalog), schemas -> {
            targetSchemaCombo.getItems().setAll(schemas);
            if (StringUtils.isBlank(targetSchemaCombo.getEditor().getText()) && !schemas.isEmpty()) {
                targetSchemaCombo.setValue(getDefaultSchema(profile, schemas));
            }
        }, this::showError);
    }

    private void showError(Throwable e) {
        Dialogs.showError(getDialogPane().getScene().getWindow(), Messages.get("export.loadError"), e);
    }

    String validate() {
        if (sourceCombo.getValue() == null) {
            return Messages.get("export.error.source");
        }
        if (!tableChoices.isEmpty() && tableChoices.stream().noneMatch(choice -> choice.selected.get())) {
            return Messages.get("export.error.tables");
        }
        if (!isSpinnerTextValid(pageSizeSpinner)) {
            return Messages.format("export.error.pageSize", String.valueOf(getMin(pageSizeSpinner)),
                    String.valueOf(getMax(pageSizeSpinner)));
        }
        if (scriptRadio.isSelected()) {
            if (!isSpinnerTextValid(maxFileSizeSpinner)) {
                return Messages.format("export.error.maxFileSize", String.valueOf(getMax(maxFileSizeSpinner)));
            }
            String version = StringUtils.trimToEmpty(scriptVersionCombo.getEditor().getText());
            if (!version.isEmpty() && !version.matches("\\d{1,4}(\\.\\d{1,3})?")) {
                return Messages.format("export.error.version", version);
            }
            if (StringUtils.isBlank(directoryField.getText())) {
                return Messages.get("export.error.directory");
            }
            File directory = new File(directoryField.getText().trim());
            if (directory.exists() && !directory.isDirectory()) {
                return Messages.get("export.error.directory");
            }
        } else if (targetCombo.getValue() == null) {
            return Messages.get("export.error.target");
        }
        return null;
    }

    private static int getMin(Spinner<Integer> spinner) {
        return ((javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory())
                .getMin();
    }

    private static int getMax(Spinner<Integer> spinner) {
        return ((javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory())
                .getMax();
    }

    /**
     * Typed text must be an integer within the range of the spinner
     */
    static boolean isSpinnerTextValid(Spinner<Integer> spinner) {
        String text = StringUtils.trim(spinner.getEditor().getText());
        if (!StringUtils.isNumeric(text) || text.length() > 9) {
            return false;
        }
        int value = Integer.parseInt(text);
        return value >= getMin(spinner) && value <= getMax(spinner);
    }

    /**
     * Editable spinners of JavaFX 17 do not commit typed text when losing focus
     */
    static void commitSpinner(Spinner<Integer> spinner) {
        String text = spinner.getEditor().getText();
        if (StringUtils.isNumeric(StringUtils.trim(text))) {
            spinner.getValueFactory().setValue(Integer.parseInt(text.trim()));
        } else {
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
    }

    TransferRequest buildRequest() {
        commitSpinner(pageSizeSpinner);
        commitSpinner(maxFileSizeSpinner);
        TransferRequest request = new TransferRequest();
        ConnectionProfile source = sourceCombo.getValue();
        request.setSource(source);
        request.setSourceCatalog(catalogCombo.isDisabled() ? null : catalogCombo.getValue());
        request.setSourceSchema(schemaCombo.isDisabled() ? null : schemaCombo.getValue());
        List<String> selected = tableChoices.stream().filter(choice -> choice.selected.get())
                .map(choice -> choice.table.getName()).collect(Collectors.toList());
        // All tables selected means the whole schema
        request.setTables(selected.size() == tableChoices.size() ? new ArrayList<>() : selected);
        request.setExportMode((ExportMode) modeGroup.getSelectedToggle().getUserData());
        request.setTableRecreated(recreateCheck.isSelected());
        request.setIdReused(idReusedCheck.isSelected());
        request.setIndexIncluded(indexCheck.isSelected());
        request.setForeignKeyIncluded(foreignKeyCheck.isSelected());
        request.setCommentIncluded(commentCheck.isSelected());
        request.setSequenceIncluded(sequenceCheck.isSelected());
        request.setFailFast(failFastCheck.isSelected());
        request.setPageSize(pageSizeSpinner.getValue());
        request.setLobPageSize(context.getSettings().getLobPageSize());
        request.setIdentifierCase(identifierCaseCombo.getValue());
        if (scriptRadio.isSelected()) {
            request.setTarget(TransferRequest.Target.SCRIPT);
            File directory = new File(directoryField.getText().trim());
            request.setOutputDirectory(directory);
            request.setScriptDbType(scriptDbTypeCombo.getValue());
            request.setScriptDbVersion(StringUtils.trimToNull(scriptVersionCombo.getEditor().getText()));
            request.setScriptSchema(StringUtils.trimToNull(scriptSchemaField.getText()));
            request.setDataFileStrategy(perTableRadio.isSelected() ? DataFileStrategy.FILE_PER_TABLE
                    : DataFileStrategy.SINGLE_FILE);
            request.setMaxFileSize(maxFileSizeSpinner.getValue() * MB);
            request.setLobSeparated(lobSeparatedCheck.isSelected());
            context.getSettings().setLastExportDirectory(directory.getAbsolutePath());
            context.getSettings().setMaxDataFileSize(maxFileSizeSpinner.getValue() * MB);
        } else {
            request.setTarget(TransferRequest.Target.DATABASE);
            request.setTargetProfile(targetCombo.getValue());
            request.setTargetCatalog(targetCatalogCombo.isDisabled() ? null : targetCatalogCombo.getValue());
            request.setTargetSchema(targetSchemaCombo.isDisabled() ? null : StringUtils.trimToNull(
                    targetSchemaCombo.getEditor().getText()));
            request.setTargetSchemaCreated(createSchemaCheck.isSelected());
        }
        context.getSettings().setTransferPageSize(pageSizeSpinner.getValue());
        context.saveSettings();
        return request;
    }

    /**
     * Number of tables of the schema listed in the dialog
     */
    public int getListedTableCount() {
        return tableChoices.size();
    }
}
