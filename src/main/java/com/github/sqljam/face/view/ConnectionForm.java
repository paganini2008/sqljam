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
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.DbType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * @Description: ConnectionForm edits a data source (connection profile), it's shared by the login page and the data
 *               source dialog
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ConnectionForm extends VBox {

    private final TextField nameField = new TextField();
    private final ComboBox<DbType> dbTypeCombo = new ComboBox<>();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final Label databaseLabel = new Label();
    private final TextField databaseField = new TextField();
    private final Button browseButton = new Button(null, Icons.of(Icons.FOLDER));
    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final CheckBox rememberPasswordCheck = new CheckBox(Messages.get("connection.rememberPassword"));
    private final TextField urlField = new TextField();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final Label hostLabel = new Label(Messages.get("connection.host"));
    private final Label portLabel = new Label(Messages.get("connection.port"));
    private final HBox hostBox;

    private ConnectionProfile profile = new ConnectionProfile();

    public ConnectionForm() {
        setSpacing(10);
        getStyleClass().add("connection-form");
        nameField.setId("nameField");
        dbTypeCombo.setId("dbTypeCombo");
        hostField.setId("hostField");
        portField.setId("portField");
        databaseLabel.setId("databaseLabel");
        databaseField.setId("databaseField");
        browseButton.setId("browseButton");
        usernameField.setId("usernameField");
        passwordField.setId("passwordField");
        rememberPasswordCheck.setId("rememberPasswordCheck");
        urlField.setId("urlField");
        statusLabel.setId("formStatusLabel");

        dbTypeCombo.getItems().addAll(DbType.values());
        dbTypeCombo.setMaxWidth(Double.MAX_VALUE);
        dbTypeCombo.valueProperty().addListener((obs, oldType, newType) -> onDbTypeChanged(oldType, newType));
        portField.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches("\\d{0,5}")
                ? change : null));
        portField.setPrefColumnCount(6);
        browseButton.setOnAction(event -> {
            DbType dbType = dbTypeCombo.getValue();
            if (dbType != null && dbType.isFileBased()) {
                browseFile();
            } else {
                browseDatabases();
            }
        });
        rememberPasswordCheck.setSelected(true);
        urlField.setPromptText(Messages.get("connection.url.prompt"));
        nameField.setPromptText(Messages.get("connection.name.prompt"));
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("form-status");
        progressIndicator.setPrefSize(18, 18);
        progressIndicator.visibleProperty().bind(busy);
        progressIndicator.managedProperty().bind(busy);

        hostBox = new HBox(8, hostField, portLabel, portField);
        hostBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        portLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox.setHgrow(hostField, Priority.ALWAYS);
        HBox databaseBox = new HBox(8, databaseField, browseButton);
        HBox.setHgrow(databaseField, Priority.ALWAYS);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(110);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        int row = 0;
        grid.addRow(row++, new Label(Messages.get("connection.name")), nameField);
        grid.addRow(row++, new Label(Messages.get("connection.dbType")), dbTypeCombo);
        grid.addRow(row++, hostLabel, hostBox);
        grid.addRow(row++, databaseLabel, databaseBox);
        grid.addRow(row++, new Label(Messages.get("connection.username")), usernameField);
        grid.addRow(row++, new Label(Messages.get("connection.password")), passwordField);
        grid.add(rememberPasswordCheck, 1, row++);
        grid.addRow(row, new Label(Messages.get("connection.url")), urlField);

        HBox statusBox = new HBox(8, progressIndicator, statusLabel);
        statusBox.setPadding(new Insets(4, 0, 0, 0));
        getChildren().addAll(grid, statusBox);
        setProfile(new ConnectionProfile(), true);
    }

    private void onDbTypeChanged(DbType oldType, DbType newType) {
        if (newType == null) {
            return;
        }
        String oldPort = oldType != null ? String.valueOf(oldType.getDefaultPort()) : "";
        if (StringUtils.isBlank(portField.getText()) || portField.getText().equals(oldPort)) {
            portField.setText(newType.getDefaultPort() > 0 ? String.valueOf(newType.getDefaultPort()) : "");
        }
        boolean fileBased = newType.isFileBased();
        // H2 may run as a tcp server, SQLite is always a file
        boolean hostVisible = newType != DbType.SQLITE;
        hostLabel.setVisible(hostVisible);
        hostLabel.setManaged(hostVisible);
        hostBox.setVisible(hostVisible);
        hostBox.setManaged(hostVisible);
        hostField.setPromptText(newType == DbType.H2 ? Messages.get("connection.host.h2.prompt") : "localhost");
        // Files are chosen for H2/SQLite, databases of the server for MySQL/PostgreSQL/SQL Server
        boolean browsable = fileBased || newType.isCatalogSupported();
        browseButton.setVisible(browsable);
        browseButton.setManaged(browsable);
        browseButton.setGraphic(Icons.of(fileBased ? Icons.FOLDER : "fth-more-horizontal"));
        browseButton.setTooltip(fileBased ? null : new javafx.scene.control.Tooltip(
                Messages.get("connection.browseDatabases")));
        if (newType == DbType.ORACLE) {
            databaseLabel.setText(Messages.get("connection.serviceName"));
        } else if (fileBased) {
            databaseLabel.setText(Messages.get("connection.file"));
        } else {
            databaseLabel.setText(Messages.get("connection.database"));
        }
    }

    private void browseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(databaseLabel.getText());
        if (StringUtils.isNotBlank(databaseField.getText())) {
            File current = new File(databaseField.getText());
            if (current.getParentFile() != null && current.getParentFile().isDirectory()) {
                chooser.setInitialDirectory(current.getParentFile());
            }
        }
        File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file != null) {
            String path = file.getAbsolutePath();
            // H2 file url excludes the .mv.db suffix
            if (dbTypeCombo.getValue() == DbType.H2 && path.endsWith(".mv.db")) {
                path = path.substring(0, path.length() - ".mv.db".length());
            }
            databaseField.setText(path);
            if (StringUtils.isBlank(nameField.getText())) {
                nameField.setText(file.getName());
            }
        }
    }

    /**
     * Lists databases of the server with the entered credentials and lets the user pick one
     */
    private void browseDatabases() {
        ConnectionProfile edited = getProfile();
        if (StringUtils.isBlank(edited.getUrl()) && StringUtils.isBlank(edited.getHostname())) {
            showError(Messages.get("connection.error.host"));
            return;
        }
        busy.set(true);
        showInfo(Messages.get("connection.connecting"));
        TaskRunner.run(() -> DatabaseSession.getDatabases(edited), databases -> {
            busy.set(false);
            clearStatus();
            if (databases.isEmpty()) {
                showError(Messages.get("connection.browseDatabases.empty"));
                return;
            }
            String current = databases.contains(databaseField.getText()) ? databaseField.getText() : databases.get(0);
            javafx.scene.control.ChoiceDialog<String> dialog = new javafx.scene.control.ChoiceDialog<>(current,
                    databases);
            dialog.initOwner(getScene() != null ? getScene().getWindow() : null);
            Branding.applyIcons(dialog);
            dialog.setTitle(Messages.get("connection.browseDatabases.title"));
            dialog.setHeaderText(Messages.get("connection.browseDatabases"));
            dialog.showAndWait().ifPresent(databaseField::setText);
        }, e -> {
            busy.set(false);
            showError(Messages.get("connection.browseDatabases.error") + ": " + StringUtils.defaultIfBlank(
                    e.getMessage(), e.getClass().getSimpleName()));
        });
    }

    /**
     * Fills the form with the profile
     */
    public void setProfile(ConnectionProfile profile, boolean passwordRemembered) {
        this.profile = profile.copy();
        DbType dbType = profile.getDbType() != null ? profile.getDbType() : DbType.MYSQL;
        dbTypeCombo.setValue(null);
        portField.setText(profile.getPort() > 0 ? String.valueOf(profile.getPort()) : "");
        dbTypeCombo.setValue(dbType);
        nameField.setText(StringUtils.defaultString(profile.getName()));
        hostField.setText(Objects.toString(profile.getHostname(), profile.getDbType() == null ? "localhost" : ""));
        databaseField.setText(StringUtils.defaultString(profile.getDatabase()));
        usernameField.setText(StringUtils.defaultString(profile.getUsername()));
        passwordField.setText(StringUtils.defaultString(profile.getPassword()));
        rememberPasswordCheck.setSelected(passwordRemembered);
        urlField.setText(StringUtils.defaultString(profile.getUrl()));
        clearStatus();
    }

    /**
     * Profile edited by the form, the id of the original profile is kept
     */
    public ConnectionProfile getProfile() {
        ConnectionProfile result = profile.copy();
        DbType dbType = dbTypeCombo.getValue();
        result.setDbType(dbType);
        result.setHostname(StringUtils.trimToNull(hostField.getText()));
        result.setPort(StringUtils.isNumeric(portField.getText()) ? Integer.parseInt(portField.getText()) : 0);
        result.setDatabase(StringUtils.trimToNull(databaseField.getText()));
        result.setUsername(StringUtils.trimToNull(usernameField.getText()));
        result.setPassword(passwordField.getText());
        result.setUrl(StringUtils.trimToNull(urlField.getText()));
        String name = StringUtils.trimToNull(nameField.getText());
        if (name == null) {
            name = buildDefaultName(result);
        }
        result.setName(name);
        return result;
    }

    private static String buildDefaultName(ConnectionProfile profile) {
        if (profile.getDbType() == null) {
            return "";
        }
        if (profile.getDbType().isFileBased() && StringUtils.isBlank(profile.getHostname())) {
            return profile.getDbType().getDisplayName() + " - " + new File(StringUtils.defaultString(
                    profile.getDatabase())).getName();
        }
        return String.format("%s - %s%s", profile.getDbType().getDisplayName(),
                Objects.toString(profile.getHostname(), "localhost"),
                StringUtils.isNotBlank(profile.getDatabase()) ? "/" + profile.getDatabase() : "");
    }

    public boolean isPasswordRemembered() {
        return rememberPasswordCheck.isSelected();
    }

    public void setRememberPasswordVisible(boolean visible) {
        rememberPasswordCheck.setVisible(visible);
        rememberPasswordCheck.setManaged(visible);
    }

    /**
     * Validates required fields, returns the error message or null
     */
    public String validate() {
        ConnectionProfile edited = getProfile();
        if (edited.getDbType() == null) {
            return Messages.get("connection.error.dbType");
        }
        DbType dbType = edited.getDbType();
        // PostgreSQL connects to a database, Oracle to a service, H2/SQLite to a file; the database of MySQL and
        // SQL Server is the optional default database
        boolean databaseRequired = dbType == DbType.POSTGRESQL || dbType == DbType.ORACLE || dbType.isFileBased();
        if (databaseRequired && StringUtils.isBlank(edited.getDatabase()) && StringUtils.isBlank(edited.getUrl())) {
            return Messages.format("connection.error.database", databaseLabel.getText());
        }
        if (StringUtils.isNotBlank(edited.getUrl())) {
            return null;
        }
        boolean fileMode = dbType == DbType.SQLITE || (dbType == DbType.H2 && StringUtils.isBlank(
                edited.getHostname()));
        if (!fileMode && StringUtils.isBlank(edited.getHostname())) {
            return Messages.get("connection.error.host");
        }
        if (!fileMode && (edited.getPort() <= 0 || edited.getPort() > 65535)) {
            return Messages.get("connection.error.port");
        }
        return null;
    }

    public BooleanProperty busyProperty() {
        return busy;
    }

    public void showError(String message) {
        statusLabel.getStyleClass().removeAll("status-success");
        if (!statusLabel.getStyleClass().contains("status-error")) {
            statusLabel.getStyleClass().add("status-error");
        }
        statusLabel.setText(message);
    }

    public void showSuccess(String message) {
        statusLabel.getStyleClass().removeAll("status-error");
        if (!statusLabel.getStyleClass().contains("status-success")) {
            statusLabel.getStyleClass().add("status-success");
        }
        statusLabel.setText(message);
    }

    public void showInfo(String message) {
        statusLabel.getStyleClass().removeAll("status-error", "status-success");
        statusLabel.setText(message);
    }

    public void clearStatus() {
        showInfo("");
    }

    /**
     * Tests the connection asynchronously and shows the result in the form
     *
     * @param onSuccess called with product name and version
     */
    public void testConnection(java.util.function.Consumer<String> onSuccess) {
        String error = validate();
        if (error != null) {
            showError(error);
            return;
        }
        ConnectionProfile edited = getProfile();
        busy.set(true);
        showInfo(Messages.get("connection.connecting"));
        TaskRunner.run(() -> DatabaseSession.testConnection(edited), product -> {
            busy.set(false);
            showSuccess(Messages.format("connection.success", product));
            if (onSuccess != null) {
                onSuccess.accept(product);
            }
        }, e -> {
            busy.set(false);
            Throwable cause = e;
            while (cause.getCause() != null && StringUtils.isBlank(cause.getMessage())) {
                cause = cause.getCause();
            }
            showError(Messages.format("connection.failure", StringUtils.defaultIfBlank(cause.getMessage(),
                    cause.getClass().getSimpleName())));
        });
    }

    public void focusFirstField() {
        nameField.requestFocus();
    }
}
