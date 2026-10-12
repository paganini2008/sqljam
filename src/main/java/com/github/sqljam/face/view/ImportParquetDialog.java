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
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.TransferService;
import com.github.sqljam.impexp.ParquetExporter;
import javafx.scene.control.CheckBox;
import com.github.sqljam.impexp.DuckDBWorkspace;
import com.github.sqljam.impexp.CsvSplitter;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.CsvOptions;
import com.github.sqljam.impexp.ParquetImporter;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.Getter;

/**
 * @Description: ImportParquetDialog loads Parquet files (of SqlJam or other tools) into a table of a database. Files
 *               of one table are chosen together, columns and the first rows are previewed, the table is created
 *               (or replaced) or the rows are appended to an existing table.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ImportParquetDialog extends Dialog<ImportParquetDialog.ParquetRequest> {

    public static final int PREVIEW_ROWS = 50;

    private final AppContext context;
    private final ListView<File> fileList = new ListView<>();
    private final Label previewLabel = new Label();
    private final TableView<List<String>> previewTable = new TableView<>();
    private final TextField tableField = new TextField();
    private final RadioButton createRadio = new RadioButton(Messages.get("parquet.mode.create"));
    private final RadioButton appendRadio = new RadioButton(Messages.get("parquet.mode.append"));
    private final RadioButton upsertRadio = new RadioButton(Messages.get("parquet.mode.upsert"));
    private final RadioButton skipExistingRadio = new RadioButton(Messages.get("parquet.mode.skip"));
    /**
     * Key columns of upserts, comma separated, empty for the primary key of the table
     */
    private final TextField keyColumnsField = new TextField();
    /**
     * Options of CSV files, shown when the files are CSV files, the delimiter and dates are detected by default
     */
    private final CsvOptionsPane csvOptionsPane = new CsvOptionsPane(detectedCsvOptions(), true);
    private VBox csvOptionsBox;
    private final ComboBox<ConnectionProfile> targetCombo = new ComboBox<>();
    private final TransactionOption transactionOption;
    private final ComboBox<String> catalogCombo = new ComboBox<>();
    private final ComboBox<String> schemaCombo = new ComboBox<>();
    private final Label warningLabel = new Label();
    private final Button startButton;
    private boolean filesValid;
    private int previewVersion;

    /**
     * Load of Parquet files into a table of the target connection
     */
    @Getter
    public static class ParquetRequest {

        private final List<File> files;
        private final String tableName;
        private final ParquetImporter.Mode mode;
        /**
         * Key columns of upserts, empty for the primary key of the table
         */
        private final List<String> keyColumns;
        /**
         * Options of CSV files, null for Parquet files
         */
        private final CsvOptions csvOptions;
        private final ConnectionProfile target;
        private final String catalog;
        private final String schema;
        /**
         * The import runs in one transaction which the user commits or rolls back
         */
        private final boolean oneTransaction;
        /**
         * A successful import in one transaction waits for the user to commit or roll back
         */
        private final boolean confirmCommit;
        /**
         * Rows with errors are skipped, otherwise an error rolls back one transaction or stops the import
         */
        private final boolean skipErrors;
        /**
         * Rows per commit when the import does not run in one transaction, 0 for the default page size
         */
        private final int commitRows;

        ParquetRequest(List<File> files, String tableName, ParquetImporter.Mode mode, List<String> keyColumns,
                       CsvOptions csvOptions, ConnectionProfile target, String catalog, String schema,
                       boolean oneTransaction, boolean confirmCommit, boolean skipErrors, int commitRows) {
            this.files = files;
            this.tableName = tableName;
            this.mode = mode;
            this.keyColumns = keyColumns;
            this.csvOptions = csvOptions;
            this.target = target;
            this.catalog = catalog;
            this.schema = schema;
            this.oneTransaction = oneTransaction;
            this.confirmCommit = confirmCommit;
            this.skipErrors = skipErrors;
            this.commitRows = commitRows;
        }
    }

    /**
     * Options of CSV files of other tools: the delimiter and the dates are detected
     */
    private static CsvOptions detectedCsvOptions() {
        CsvOptions options = new CsvOptions();
        options.setDelimiter("");
        return options;
    }

    public ImportParquetDialog(Window owner, AppContext context, DbNode node) {
        this.context = context;
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        setTitle(Messages.get("parquet.title"));
        setHeaderText(Messages.get("parquet.header"));
        setResizable(true);

        fileList.setId("parquetFiles");
        previewLabel.setId("parquetPreviewLabel");
        previewTable.setId("parquetPreview");
        tableField.setId("tableField");
        createRadio.setId("createRadio");
        appendRadio.setId("appendRadio");
        upsertRadio.setId("upsertRadio");
        skipExistingRadio.setId("skipExistingRadio");
        keyColumnsField.setId("keyColumnsField");
        targetCombo.setId("targetCombo");
        catalogCombo.setId("catalogCombo");
        schemaCombo.setId("schemaCombo");
        warningLabel.setId("warningLabel");

        fileList.setPrefHeight(110);
        // The name of a file with its folder, the whole path is shown by the tooltip
        fileList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                if (empty || file == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                Label folder = new Label(file.getParent());
                folder.getStyleClass().add("muted");
                setText(null);
                setGraphic(new HBox(8, Icons.of(Icons.TABLE), new Label(file.getName()), folder));
                setTooltip(new Tooltip(file.getAbsolutePath()));
            }
        });
        fileList.setPlaceholder(new Label(Messages.get("parquet.files.empty")));
        fileList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Button addButton = new Button(Messages.get("parquet.files.add"), Icons.of(Icons.ADD));
        addButton.setId("addFilesButton");
        addButton.setOnAction(event -> chooseFiles());
        Button removeButton = new Button(Messages.get("parquet.files.remove"), Icons.of(Icons.DELETE));
        removeButton.setId("removeFilesButton");
        removeButton.disableProperty().bind(fileList.getSelectionModel().selectedItemProperty().isNull());
        removeButton.setOnAction(event -> removeFiles(new ArrayList<>(fileList.getSelectionModel()
                .getSelectedItems())));
        VBox fileButtons = new VBox(8, addButton, removeButton);
        addButton.setMaxWidth(Double.MAX_VALUE);
        removeButton.setMaxWidth(Double.MAX_VALUE);
        HBox fileBox = new HBox(8, fileList, fileButtons);
        HBox.setHgrow(fileList, Priority.ALWAYS);

        previewLabel.getStyleClass().add("muted");
        previewTable.setPrefHeight(200);
        previewTable.setPlaceholder(new Label(Messages.get("package.noRows")));
        Label filesTitle = new Label(Messages.get("parquet.section.files"));
        filesTitle.getStyleClass().add("section-title");
        Label csvTitle = new Label(Messages.get("csv.options"));
        csvTitle.getStyleClass().add("muted");
        csvOptionsBox = new VBox(6, csvTitle, csvOptionsPane.getNode());
        csvOptionsBox.setId("csvOptionsBox");
        csvOptionsBox.setVisible(false);
        csvOptionsBox.setManaged(false);
        csvOptionsPane.setOnChange(this::loadPreview);
        VBox filesPane = new VBox(8, filesTitle, fileBox, csvOptionsBox, previewLabel, previewTable);
        filesPane.getStyleClass().add("form-section");

        ToggleGroup modeGroup = new ToggleGroup();
        createRadio.setToggleGroup(modeGroup);
        appendRadio.setToggleGroup(modeGroup);
        upsertRadio.setToggleGroup(modeGroup);
        skipExistingRadio.setToggleGroup(modeGroup);
        createRadio.setSelected(true);
        keyColumnsField.setPromptText(Messages.get("parquet.keys.prompt"));
        keyColumnsField.setTooltip(new Tooltip(Messages.get("parquet.keys.tooltip")));
        keyColumnsField.disableProperty().bind(upsertRadio.selectedProperty().not().and(skipExistingRadio
                .selectedProperty().not()));
        DbTypeCells.setupProfileCombo(targetCombo);
        targetCombo.setMaxWidth(Double.MAX_VALUE);
        catalogCombo.setMaxWidth(Double.MAX_VALUE);
        schemaCombo.setMaxWidth(Double.MAX_VALUE);
        schemaCombo.setEditable(true);
        schemaCombo.setPromptText(Messages.get("import.schema.prompt"));
        tableField.setPromptText(Messages.get("parquet.table.prompt"));
        tableField.textProperty().addListener((obs, oldText, text) -> validate());
        targetCombo.valueProperty().addListener((obs, oldProfile, profile) -> {
            loadCatalogs(profile);
            validate();
        });
        catalogCombo.valueProperty().addListener((obs, oldCatalog, catalog) -> {
            ConnectionProfile profile = targetCombo.getValue();
            if (catalog != null && profile != null && profile.getDbType().isSchemaSupported()) {
                loadSchemas(profile, catalog);
            }
        });
        GridPane targetGrid = grid();
        targetGrid.addRow(0, new Label(Messages.get("export.database.connection")), ConnectionDialog.targetField(
                targetCombo, context, profile -> {
                    targetCombo.getItems().setAll(context.getProfileRegistry().getProfiles());
                    ConnectionDialog.selectProfile(targetCombo, profile);
                }));
        targetGrid.addRow(1, new Label(Messages.get("export.database.catalog")), catalogCombo);
        targetGrid.addRow(2, new Label(Messages.get("export.database.schema")), schemaCombo);
        targetGrid.addRow(3, new Label(Messages.get("parquet.table")), tableField);
        targetGrid.addRow(4, new Label(Messages.get("parquet.mode")), new VBox(6, createRadio, appendRadio,
                upsertRadio, skipExistingRadio, keyColumnsField));
        // ClickHouse can not match rows by key
        targetCombo.valueProperty().addListener((obs, oldProfile, profile) -> {
            boolean supported = profile == null || profile.getDbType().createDialect().isUpsertSupported();
            if (!supported && (upsertRadio.isSelected() || skipExistingRadio.isSelected())) {
                appendRadio.setSelected(true);
            }
            upsertRadio.setDisable(!supported);
            skipExistingRadio.setDisable(!supported);
        });
        transactionOption = new TransactionOption(context, targetCombo.valueProperty(), context.getSettings()
                .getTransferPageSize());
        targetGrid.addRow(5, new Label(Messages.get("import.transaction")), transactionOption.getNode());
        Label targetTitle = new Label(Messages.get("import.section.target"));
        targetTitle.getStyleClass().add("section-title");
        VBox targetPane = new VBox(8, targetTitle, targetGrid);
        targetPane.getStyleClass().add("form-section");

        warningLabel.getStyleClass().add("status-error");
        warningLabel.setWrapText(true);
        VBox content = new VBox(14, filesPane, targetPane, warningLabel);
        content.setPadding(new Insets(4));
        content.setPrefWidth(760);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        startButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        startButton.setId("startButton");
        startButton.setText(Messages.get("import.start"));
        startButton.setGraphic(Icons.of(Icons.START));
        startButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!isReady()) {
                event.consume();
            }
        });
        setResultConverter(buttonType -> buttonType == ButtonType.OK ? buildRequest() : null);

        targetCombo.getItems().setAll(context.getProfileRegistry().getProfiles());
        ConnectionProfile selected = node != null && node.getProfile() != null ? targetCombo.getItems().stream()
                .filter(profile -> profile.getId().equals(node.getProfile().getId())).findFirst().orElse(null) : null;
        targetCombo.setValue(selected != null ? selected : targetCombo.getItems().isEmpty() ? null
                : targetCombo.getItems().get(0));
        showPreview(null);
        validate();
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

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("parquet.files.add"));
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(Messages.get("parquet.files.filter"),
                "*" + ParquetExporter.PARQUET_EXTENSION, "*" + CsvExporter.CSV_EXTENSION, "*.tsv", "*.txt"),
                new FileChooser.ExtensionFilter(Messages.get("parquet.files.filter.parquet"),
                        "*" + ParquetExporter.PARQUET_EXTENSION),
                new FileChooser.ExtensionFilter(Messages.get("parquet.files.filter.csv"),
                        "*" + CsvExporter.CSV_EXTENSION, "*.tsv", "*.txt"));
        String lastDirectory = context.getSettings().getLastImportDirectory();
        if (StringUtils.isNotBlank(lastDirectory) && new File(lastDirectory).isDirectory()) {
            chooser.setInitialDirectory(new File(lastDirectory));
        }
        List<File> files = chooser.showOpenMultipleDialog(getDialogPane().getScene().getWindow());
        if (files != null && !files.isEmpty()) {
            context.getSettings().setLastImportDirectory(files.get(0).getParentFile().getAbsolutePath());
            context.saveSettings();
            addFiles(files);
        }
    }

    /**
     * Adds Parquet or CSV files of a table, the table name is the base name of the first file by default. Files
     * are listed in the order of importing: orders.csv, orders_2.csv ... orders_n.csv
     */
    public void addFiles(List<File> files) {
        for (File file : files) {
            if (!fileList.getItems().contains(file)) {
                fileList.getItems().add(file);
            }
        }
        fileList.getItems().setAll(CsvSplitter.sortParts(fileList.getItems()));
        if (StringUtils.isBlank(tableField.getText()) && !fileList.getItems().isEmpty()) {
            tableField.setText(getTableName(fileList.getItems().get(0)));
        }
        loadPreview();
    }

    void removeFiles(List<File> files) {
        fileList.getItems().removeAll(files);
        loadPreview();
    }

    /**
     * Base name of a Parquet file as an identifier: other characters are replaced by underscores
     */
    static String getTableName(File file) {
        String name = FilenameUtils.getBaseName(file.getName());
        // Files of a schema qualified table are named schema.table.parquet
        if (name.contains(".")) {
            name = name.substring(name.lastIndexOf('.') + 1);
        }
        name = name.replaceAll("[^\\p{L}\\p{N}_]", "_");
        return name.isEmpty() || Character.isDigit(name.charAt(0)) ? "t_" + name : name;
    }

    private void loadPreview() {
        List<File> files = new ArrayList<>(fileList.getItems());
        int version = ++previewVersion;
        filesValid = false;
        if (files.isEmpty()) {
            showPreview(null);
            validate();
            return;
        }
        // Files of one table are all Parquet or all CSV
        long csvFiles = files.stream().filter(DuckDBWorkspace::isCsv).count();
        boolean csv = csvFiles > 0 && csvFiles == files.size();
        csvOptionsBox.setVisible(csv);
        csvOptionsBox.setManaged(csv);
        if (csvFiles > 0 && csvFiles < files.size()) {
            showPreview(null);
            validate();
            warningLabel.setText(Messages.get("parquet.error.mixed"));
            return;
        }
        previewLabel.setText(Messages.get("package.loading"));
        previewTable.getColumns().clear();
        previewTable.getItems().clear();
        validate();
        CsvOptions csvOptions = csv ? csvOptionsPane.getOptions() : null;
        TaskRunner.run(() -> context.getTransferService().previewParquet(files, PREVIEW_ROWS, csvOptions),
                preview -> {
            if (version == previewVersion) {
                filesValid = true;
                showPreview(preview);
                validate();
            }
        }, e -> {
            if (version == previewVersion) {
                showPreview(null);
                warningLabel.setText(Messages.format("parquet.error.read", StringUtils.defaultIfBlank(
                        e.getMessage(), e.getClass().getSimpleName())));
                startButton.setDisable(true);
            }
        });
    }

    private void showPreview(TransferService.ParquetPreview preview) {
        previewTable.getColumns().clear();
        previewTable.getItems().clear();
        if (preview == null) {
            previewLabel.setText(Messages.get("parquet.files.hint"));
            return;
        }
        List<String[]> columns = preview.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(columns.get(i)[0]);
            Label header = new Label(columns.get(i)[0] + "\n" + columns.get(i)[1].toLowerCase(Locale.ENGLISH));
            column.setText(null);
            column.setGraphic(header);
            column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(index < features.getValue().size()
                    ? features.getValue().get(index) : null));
            column.setPrefWidth(120);
            previewTable.getColumns().add(column);
        }
        previewTable.getItems().setAll(preview.getFirstRows());
        previewLabel.setText(Messages.format("parquet.preview", columns.size(), preview.getRows(),
                preview.getFirstRows().size()));
    }

    private boolean isReady() {
        return filesValid && targetCombo.getValue() != null && StringUtils.isNotBlank(tableField.getText());
    }

    private void validate() {
        String warning = "";
        if (context.getProfileRegistry().getProfiles().isEmpty()) {
            warning = Messages.get("export.error.noConnections");
        } else if (!fileList.getItems().isEmpty() && filesValid && StringUtils.isBlank(tableField.getText())) {
            warning = Messages.get("parquet.error.table");
        }
        warningLabel.setText(warning);
        if (startButton != null) {
            startButton.setDisable(!isReady());
        }
    }

    private void loadCatalogs(ConnectionProfile profile) {
        catalogCombo.getItems().clear();
        schemaCombo.getItems().clear();
        if (profile == null) {
            return;
        }
        TaskRunner.run(() -> context.getSessionManager().getSession(profile).getCatalogs(), catalogs -> {
            catalogCombo.setDisable(catalogs.isEmpty());
            catalogCombo.getItems().setAll(catalogs);
            if (catalogs.isEmpty()) {
                schemaCombo.setDisable(!profile.getDbType().isSchemaSupported());
                if (profile.getDbType().isSchemaSupported()) {
                    loadSchemas(profile, null);
                }
                return;
            }
            catalogCombo.setValue(catalogs.contains(profile.getDatabase()) ? profile.getDatabase()
                    : catalogs.get(0));
            schemaCombo.setDisable(!profile.getDbType().isSchemaSupported());
        }, this::showLoadError);
    }

    /**
     * Errors of loading after the dialog is closed are not shown
     */
    private void showLoadError(Throwable e) {
        if (isShowing()) {
            Dialogs.showError(getDialogPane().getScene().getWindow(), Messages.get("export.loadError"), e);
        }
    }

    private void loadSchemas(ConnectionProfile profile, String catalog) {
        schemaCombo.setDisable(false);
        TaskRunner.run(() -> context.getSessionManager().getSession(profile).getSchemas(catalog),
                schemas -> schemaCombo.getItems().setAll(schemas),
                this::showLoadError);
    }

    ParquetRequest buildRequest() {
        return new ParquetRequest(new ArrayList<>(fileList.getItems()), tableField.getText().trim(),
                upsertRadio.isSelected() ? ParquetImporter.Mode.UPSERT : skipExistingRadio.isSelected()
                        ? ParquetImporter.Mode.SKIP_EXISTING : appendRadio.isSelected()
                        ? ParquetImporter.Mode.APPEND : ParquetImporter.Mode.CREATE, KeyColumnsDialog.parse(
                        keyColumnsField.getText()), csvOptionsBox.isVisible() ? csvOptionsPane.getOptions() : null,
                targetCombo.getValue(), catalogCombo.isDisabled() ? null : catalogCombo.getValue(),
                schemaCombo.isDisabled() ? null : StringUtils.trimToNull(schemaCombo.getEditor().getText()),
                transactionOption.isOneTransaction(), transactionOption.isConfirmCommit(),
                transactionOption.isSkipErrors(), transactionOption.isOneTransaction() ? 0
                : transactionOption.getCommitRows());
    }
}
