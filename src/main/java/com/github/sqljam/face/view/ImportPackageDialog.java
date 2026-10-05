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
import java.util.List;
import java.util.Objects;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportManifest;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import lombok.Getter;

/**
 * @Description: ImportPackageDialog imports an export package (directory with manifest.json) into a database. The
 *               manifest is shown so that the user can check the source, target and tables before importing.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ImportPackageDialog extends Dialog<ImportPackageDialog.ImportRequest> {

    private final AppContext context;
    private final TextField directoryField = new TextField();
    private final Label statusValue = new Label();
    private final Label createdValue = new Label();
    private final Label sourceValue = new Label();
    private final Label targetValue = new Label();
    private final Label filesValue = new Label();
    private final TableView<ExportManifest.TableEntry> tableView = new TableView<>();
    private final VBox summaryPane;
    private final ComboBox<ConnectionProfile> targetCombo = new ComboBox<>();
    private final ComboBox<String> catalogCombo = new ComboBox<>();
    private final ComboBox<String> schemaCombo = new ComboBox<>();
    private final CheckBox stopOnErrorCheck = new CheckBox(Messages.get("import.stopOnError"));
    private final Label warningLabel = new Label();
    private final Button startButton;
    private ExportManifest manifest;
    private boolean directoryValid;

    /**
     * Import of a package directory into the target connection
     */
    @Getter
    public static class ImportRequest {

        private final File directory;
        private final ConnectionProfile target;
        private final String catalog;
        private final String schema;
        private final boolean stopOnError;
        private final ExportManifest manifest;

        ImportRequest(File directory, ConnectionProfile target, String catalog, String schema, boolean stopOnError,
                      ExportManifest manifest) {
            this.directory = directory;
            this.target = target;
            this.catalog = catalog;
            this.schema = schema;
            this.stopOnError = stopOnError;
            this.manifest = manifest;
        }
    }

    public ImportPackageDialog(Window owner, AppContext context, DbNode node) {
        this.context = context;
        initOwner(owner);
        Branding.applyIcons(this);
        setTitle(Messages.get("import.title"));
        setHeaderText(Messages.get("import.header"));
        setResizable(true);

        directoryField.setPromptText(Messages.get("import.directory.prompt"));
        directoryField.setId("directoryField");
        statusValue.setId("statusValue");
        sourceValue.setId("sourceValue");
        targetValue.setId("targetValue");
        filesValue.setId("filesValue");
        tableView.setId("packageTables");
        targetCombo.setId("targetCombo");
        catalogCombo.setId("catalogCombo");
        schemaCombo.setId("schemaCombo");
        stopOnErrorCheck.setId("stopOnErrorCheck");
        warningLabel.setId("warningLabel");
        directoryField.setOnAction(event -> loadManifest());
        directoryField.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused) {
                loadManifest();
            }
        });
        Button browseButton = new Button(null, Icons.of(Icons.FOLDER));
        browseButton.setOnAction(event -> chooseDirectory());
        HBox directoryBox = new HBox(8, directoryField, browseButton);
        HBox.setHgrow(directoryField, Priority.ALWAYS);

        GridPane summaryGrid = grid();
        summaryGrid.addRow(0, new Label(Messages.get("import.manifest.status")), statusValue);
        summaryGrid.addRow(1, new Label(Messages.get("import.manifest.created")), createdValue);
        summaryGrid.addRow(2, new Label(Messages.get("import.manifest.source")), sourceValue);
        summaryGrid.addRow(3, new Label(Messages.get("import.manifest.target")), targetValue);
        summaryGrid.addRow(4, new Label(Messages.get("import.manifest.files")), filesValue);
        setupTableView();
        Label summaryTitle = new Label(Messages.get("import.section.package"));
        summaryTitle.getStyleClass().add("section-title");
        summaryPane = new VBox(8, summaryTitle, summaryGrid, tableView);
        summaryPane.getStyleClass().add("form-section");

        DbTypeCells.setupProfileCombo(targetCombo);
        targetCombo.setMaxWidth(Double.MAX_VALUE);
        catalogCombo.setMaxWidth(Double.MAX_VALUE);
        schemaCombo.setMaxWidth(Double.MAX_VALUE);
        schemaCombo.setEditable(true);
        schemaCombo.setPromptText(Messages.get("import.schema.prompt"));
        stopOnErrorCheck.setSelected(true);
        targetCombo.valueProperty().addListener((obs, oldProfile, profile) -> {
            loadCatalogs(profile);
            validateTarget();
        });
        catalogCombo.valueProperty().addListener((obs, oldCatalog, catalog) -> {
            ConnectionProfile profile = targetCombo.getValue();
            if (catalog != null && profile != null && profile.getDbType().isSchemaSupported()) {
                loadSchemas(profile, catalog);
            }
        });
        GridPane targetGrid = grid();
        targetGrid.addRow(0, new Label(Messages.get("export.database.connection")), targetCombo);
        targetGrid.addRow(1, new Label(Messages.get("export.database.catalog")), catalogCombo);
        targetGrid.addRow(2, new Label(Messages.get("export.database.schema")), schemaCombo);
        targetGrid.add(stopOnErrorCheck, 1, 3);
        Label targetTitle = new Label(Messages.get("import.section.target"));
        targetTitle.getStyleClass().add("section-title");
        VBox targetPane = new VBox(8, targetTitle, targetGrid);
        targetPane.getStyleClass().add("form-section");

        warningLabel.getStyleClass().add("status-error");
        warningLabel.setWrapText(true);
        Label directoryTitle = new Label(Messages.get("import.section.directory"));
        directoryTitle.getStyleClass().add("section-title");
        VBox content = new VBox(14, new VBox(8, directoryTitle, directoryBox), summaryPane, targetPane, warningLabel);
        content.setPadding(new Insets(4));
        content.setPrefWidth(720);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        startButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        startButton.setId("startButton");
        startButton.setText(Messages.get("import.start"));
        startButton.setGraphic(Icons.of(Icons.START));
        startButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!directoryValid || targetCombo.getValue() == null) {
                warningLabel.setText(Messages.get(directoryValid ? "export.error.target" : "import.error.directory"));
                event.consume();
            }
        });
        setResultConverter(buttonType -> buttonType == ButtonType.OK ? buildRequest() : null);

        filterTargets();
        if (node != null && node.getProfile() != null) {
            targetCombo.getItems().stream().filter(profile -> profile.getId().equals(node.getProfile().getId()))
                    .findFirst().ifPresent(targetCombo::setValue);
        }
        String lastDirectory = context.getSettings().getLastImportDirectory();
        if (StringUtils.isNotBlank(lastDirectory) && new File(lastDirectory).isDirectory()) {
            directoryField.setText(lastDirectory);
            loadManifest();
        } else {
            showSummary(null, false);
        }
        validateTarget();
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

    private void setupTableView() {
        TableColumn<ExportManifest.TableEntry, String> nameColumn = new TableColumn<>(
                Messages.get("import.table.name"));
        nameColumn.setCellValueFactory(features -> {
            ExportManifest.TableEntry entry = features.getValue();
            String prefix = StringUtils.isNotBlank(entry.getSchema()) ? entry.getSchema() + "."
                    : StringUtils.isNotBlank(entry.getCatalog()) ? entry.getCatalog() + "." : "";
            return new ReadOnlyObjectWrapper<>(prefix + entry.getName());
        });
        nameColumn.setPrefWidth(300);
        TableColumn<ExportManifest.TableEntry, Long> rowsColumn = new TableColumn<>(
                Messages.get("import.table.rows"));
        rowsColumn.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().getRows()));
        rowsColumn.setPrefWidth(120);
        TableColumn<ExportManifest.TableEntry, Long> lobsColumn = new TableColumn<>(
                Messages.get("import.table.lobs"));
        lobsColumn.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().getLobFiles()));
        lobsColumn.setPrefWidth(100);
        TableColumn<ExportManifest.TableEntry, Integer> filesColumn = new TableColumn<>(
                Messages.get("import.table.dataFiles"));
        filesColumn.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(
                features.getValue().getDataFiles().size()));
        filesColumn.setPrefWidth(100);
        tableView.getColumns().addAll(List.of(nameColumn, rowsColumn, lobsColumn, filesColumn));
        tableView.setPrefHeight(180);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setPlaceholder(new Label(Messages.get("import.table.empty")));
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.get("import.directory"));
        File current = new File(StringUtils.defaultString(directoryField.getText()));
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current.getParentFile() != null ? current.getParentFile() : current);
        }
        File directory = chooser.showDialog(getDialogPane().getScene().getWindow());
        if (directory != null) {
            directoryField.setText(directory.getAbsolutePath());
            loadManifest();
        }
    }

    private String lastLoadedDirectory;

    private void loadManifest() {
        String path = StringUtils.trimToEmpty(directoryField.getText());
        if (path.equals(lastLoadedDirectory)) {
            return;
        }
        lastLoadedDirectory = path;
        File directory = new File(path);
        if (path.isEmpty() || !directory.isDirectory()) {
            directoryValid = false;
            showSummary(null, false);
            warningLabel.setText(path.isEmpty() ? "" : Messages.get("import.error.directory"));
            return;
        }
        statusValue.setText(Messages.get("tree.loading"));
        TaskRunner.run(() -> new Object[]{ExportManifest.read(directory), isScriptDirectory(directory)}, result -> {
            directoryValid = (Boolean) result[1] || result[0] != null;
            showSummary((ExportManifest) result[0], directoryValid);
            validateTarget();
        }, e -> {
            directoryValid = false;
            showSummary(null, false);
            warningLabel.setText(Messages.format("import.error.manifest", e.getMessage()));
        });
    }

    static boolean isScriptDirectory(File directory) {
        return new File(directory, "schema.sql").exists() || new File(directory, "data.sql").exists()
                || new File(directory, "data").isDirectory();
    }

    private void showSummary(ExportManifest manifest, boolean valid) {
        this.manifest = manifest;
        tableView.getItems().clear();
        statusValue.getStyleClass().removeAll("status-error", "status-success");
        if (manifest == null) {
            statusValue.setText(valid ? Messages.get("import.manifest.legacy") : "-");
            createdValue.setText("-");
            sourceValue.setText("-");
            targetValue.setText("-");
            filesValue.setText("-");
            filterTargets();
            return;
        }
        String status = manifest.getStatus() != null ? manifest.getStatus().name() : "-";
        statusValue.setText(status);
        statusValue.getStyleClass().add(manifest.getStatus() == ExportManifest.Status.COMPLETED ? "status-success"
                : "status-error");
        createdValue.setText(Objects.toString(manifest.getCreatedAt(), "-").replace('T', ' '));
        sourceValue.setText(describe(manifest.getSource(), true));
        targetValue.setText(describe(manifest.getTarget(), false));
        long totalSize = manifest.getFiles().stream().mapToLong(ExportManifest.FileEntry::getSize).sum();
        filesValue.setText(Messages.format("import.manifest.filesValue", manifest.getFiles().size(),
                FileUtils.byteCountToDisplaySize(totalSize)));
        tableView.getItems().setAll(manifest.getTables());
        filterTargets();
    }

    private static String describe(ExportManifest.Database database, boolean source) {
        if (database == null) {
            return "-";
        }
        StringBuilder text = new StringBuilder();
        if (source && StringUtils.isNotBlank(database.getProduct())) {
            text.append(database.getProduct());
            if (StringUtils.isNotBlank(database.getVersion()) && !database.getProduct().contains(
                    database.getVersion())) {
                text.append(' ').append(database.getVersion());
            }
        } else if (database.getDbType() != null) {
            text.append(database.getDbType().getDisplayName());
            if (StringUtils.isNotBlank(database.getVersion())) {
                text.append(' ').append(database.getVersion());
            }
        }
        List<String> names = source ? database.getSchemas().isEmpty() ? database.getCatalogs() : database.getSchemas()
                : null;
        if (names != null && !names.isEmpty()) {
            text.append(" / ").append(String.join(", ", names));
        }
        if (!source) {
            String location = StringUtils.defaultIfBlank(database.getSchema(), database.getCatalog());
            if (StringUtils.isNotBlank(location)) {
                text.append(" / ").append(location);
            }
        }
        return text.length() > 0 ? text.toString() : "-";
    }

    /**
     * Target data sources, the target database type is decided when exporting (manifest), only data sources of that
     * type are offered. Legacy packages without manifest can be imported into any data source.
     */
    private void filterTargets() {
        ConnectionProfile selected = targetCombo.getValue();
        DbType dbType = getManifestDbType();
        List<ConnectionProfile> profiles = context.getProfileRegistry().getProfiles();
        if (dbType != null) {
            profiles.removeIf(profile -> profile.getDbType() != dbType);
        }
        ConnectionProfile match = selected == null ? null : profiles.stream()
                .filter(profile -> profile.getId().equals(selected.getId())).findFirst().orElse(null);
        targetCombo.getItems().setAll(profiles);
        // The selected connection is kept if it matches, otherwise the first matching connection is selected
        ConnectionProfile value = match != null ? match : profiles.isEmpty() ? null : profiles.get(0);
        targetCombo.setValue(null);
        targetCombo.setValue(value);
        validateTarget();
    }

    private DbType getManifestDbType() {
        return manifest != null && manifest.getTarget() != null ? manifest.getTarget().getDbType() : null;
    }

    private void validateTarget() {
        String warning = null;
        boolean disabled = false;
        if (manifest != null && manifest.getStatus() != ExportManifest.Status.COMPLETED) {
            warning = Messages.format("import.warning.status", manifest.getStatus());
            disabled = true;
        }
        DbType dbType = getManifestDbType();
        if (!disabled && dbType != null && targetCombo.getItems().isEmpty()) {
            warning = Messages.format("import.warning.noDataSource", dbType.getDisplayName());
            disabled = true;
        }
        warningLabel.setText(StringUtils.defaultString(warning));
        if (startButton != null) {
            // A valid package directory and a target data source are required
            startButton.setDisable(disabled || !directoryValid || targetCombo.getValue() == null);
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
            String preferred = manifest != null && manifest.getTarget() != null
                    && catalogs.contains(manifest.getTarget().getCatalog()) ? manifest.getTarget().getCatalog()
                    : catalogs.contains(profile.getDatabase()) ? profile.getDatabase() : catalogs.get(0);
            catalogCombo.setValue(preferred);
            schemaCombo.setDisable(!profile.getDbType().isSchemaSupported());
        }, e -> Dialogs.showError(getDialogPane().getScene().getWindow(), Messages.get("export.loadError"), e));
    }

    private void loadSchemas(ConnectionProfile profile, String catalog) {
        schemaCombo.setDisable(false);
        TaskRunner.run(() -> context.getSessionManager().getSession(profile).getSchemas(catalog), schemas -> {
            schemaCombo.getItems().setAll(schemas);
            String preferred = manifest != null && manifest.getTarget() != null ? manifest.getTarget().getSchema()
                    : null;
            if (StringUtils.isNotBlank(preferred)) {
                schemaCombo.setValue(preferred);
            }
        }, e -> Dialogs.showError(getDialogPane().getScene().getWindow(), Messages.get("export.loadError"), e));
    }

    ImportRequest buildRequest() {
        File directory = new File(directoryField.getText().trim());
        context.getSettings().setLastImportDirectory(directory.getAbsolutePath());
        context.saveSettings();
        return new ImportRequest(directory, targetCombo.getValue(), catalogCombo.isDisabled() ? null
                : catalogCombo.getValue(), schemaCombo.isDisabled() ? null
                : StringUtils.trimToNull(schemaCombo.getEditor().getText()), stopOnErrorCheck.isSelected(), manifest);
    }
}
