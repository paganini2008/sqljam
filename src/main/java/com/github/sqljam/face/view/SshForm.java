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

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.utils.SshSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * @Description: SshForm edits the SSH tunnel of a data source: Connect through SSH tunnel shows the SSH server, its
 *               user and a password or a private key with its passphrase. The host and port of the data source are
 *               those which the SSH server reaches, e.g. localhost.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class SshForm extends VBox {

    private final CheckBox enabledCheck = new CheckBox(Messages.get("ssh.enabled"));
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final TextField usernameField = new TextField();
    private final ComboBox<SshSettings.Authentication> authenticationCombo = new ComboBox<>();
    private final PasswordField passwordField = new PasswordField();
    private final TextField keyFileField = new TextField();
    private final Button keyBrowseButton = new Button(null, Icons.of(Icons.FOLDER));
    private final PasswordField passphraseField = new PasswordField();
    private final GridPane grid = new GridPane();
    private final Label passwordLabel = new Label(Messages.get("connection.password"));
    private final Label keyFileLabel = new Label(Messages.get("ssh.keyFile"));
    private final Label passphraseLabel = new Label(Messages.get("ssh.passphrase"));
    private final HBox keyBox;

    public SshForm(double labelWidth) {
        setSpacing(10);
        setId("sshForm");
        grid.setId("sshGrid");
        enabledCheck.setId("sshEnabledCheck");
        hostField.setId("sshHostField");
        portField.setId("sshPortField");
        usernameField.setId("sshUsernameField");
        authenticationCombo.setId("sshAuthenticationCombo");
        passwordField.setId("sshPasswordField");
        keyFileField.setId("sshKeyFileField");
        keyBrowseButton.setId("sshKeyBrowseButton");
        passphraseField.setId("sshPassphraseField");
        hostField.setPromptText(Messages.get("ssh.host.prompt"));
        portField.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches(
                "\\d{0,5}") ? change : null));
        portField.setPrefColumnCount(6);
        keyFileField.setPromptText("~/.ssh/id_ed25519");
        passphraseField.setPromptText(Messages.get("ssh.passphrase.prompt"));
        passwordField.setPromptText(Messages.get("ssh.password.prompt"));
        authenticationCombo.getItems().setAll(SshSettings.Authentication.values());
        authenticationCombo.setMaxWidth(Double.MAX_VALUE);
        authenticationCombo.setCellFactory(view -> authenticationCell());
        authenticationCombo.setButtonCell(authenticationCell());
        authenticationCombo.valueProperty().addListener((obs, oldValue, value) -> updateFields());
        keyBrowseButton.setOnAction(event -> browseKey());

        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(labelWidth);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        Label portLabel = new Label(Messages.get("connection.port"));
        portLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox hostBox = new HBox(8, hostField, portLabel, portField);
        hostBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(hostField, Priority.ALWAYS);
        keyBox = new HBox(8, keyFileField, keyBrowseButton);
        HBox.setHgrow(keyFileField, Priority.ALWAYS);
        int row = 0;
        grid.addRow(row++, new Label(Messages.get("ssh.host")), hostBox);
        grid.addRow(row++, new Label(Messages.get("connection.username")), usernameField);
        grid.addRow(row++, new Label(Messages.get("ssh.authentication")), authenticationCombo);
        grid.addRow(row++, passwordLabel, passwordField);
        grid.addRow(row++, keyFileLabel, keyBox);
        grid.addRow(row, passphraseLabel, passphraseField);
        grid.visibleProperty().bind(enabledCheck.selectedProperty());
        grid.managedProperty().bind(enabledCheck.selectedProperty());
        HBox checkBox = new HBox(enabledCheck);
        checkBox.setPadding(new Insets(0, 0, 0, labelWidth + 10));
        getChildren().addAll(checkBox, grid);
        setSettings(null);
    }

    private static ListCell<SshSettings.Authentication> authenticationCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(SshSettings.Authentication item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Messages.get("ssh.authentication." + item.name()
                        .toLowerCase()));
            }
        };
    }

    /**
     * The fields of the password or of the key only
     */
    private void updateFields() {
        boolean key = authenticationCombo.getValue() == SshSettings.Authentication.KEY;
        for (Node node : new Node[]{keyFileLabel, keyBox, passphraseLabel,
                passphraseField}) {
            node.setVisible(key);
            node.setManaged(key);
        }
        passwordLabel.setVisible(!key);
        passwordLabel.setManaged(!key);
        passwordField.setVisible(!key);
        passwordField.setManaged(!key);
    }

    private void browseKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("ssh.keyFile"));
        File ssh = new File(System.getProperty("user.home"), ".ssh");
        if (ssh.isDirectory()) {
            chooser.setInitialDirectory(ssh);
        }
        File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file != null) {
            keyFileField.setText(file.getAbsolutePath());
        }
    }

    public void setSettings(SshSettings settings) {
        SshSettings shown = settings != null ? settings : new SshSettings();
        enabledCheck.setSelected(shown.isEnabled());
        hostField.setText(StringUtils.defaultString(shown.getHost()));
        portField.setText(String.valueOf(shown.getEffectivePort()));
        usernameField.setText(StringUtils.defaultString(shown.getUsername()));
        authenticationCombo.setValue(shown.getAuthentication() != null ? shown.getAuthentication()
                : SshSettings.Authentication.PASSWORD);
        passwordField.setText(StringUtils.defaultString(shown.getPassword()));
        keyFileField.setText(StringUtils.defaultString(shown.getPrivateKeyFile()));
        passphraseField.setText(StringUtils.defaultString(shown.getPassphrase()));
        updateFields();
    }

    /**
     * Settings of the fields, null if the tunnel is not used and nothing is filled. Only the secret of the chosen
     * authentication is kept.
     */
    public SshSettings getSettings() {
        SshSettings settings = new SshSettings();
        settings.setEnabled(enabledCheck.isSelected());
        settings.setHost(StringUtils.trimToNull(hostField.getText()));
        settings.setPort(StringUtils.isNumeric(portField.getText()) ? Integer.parseInt(portField.getText())
                : SshSettings.DEFAULT_PORT);
        settings.setUsername(StringUtils.trimToNull(usernameField.getText()));
        settings.setAuthentication(authenticationCombo.getValue());
        if (settings.getAuthentication() == SshSettings.Authentication.KEY) {
            settings.setPrivateKeyFile(StringUtils.trimToNull(keyFileField.getText()));
            settings.setPassphrase(StringUtils.defaultIfEmpty(passphraseField.getText(), null));
        } else {
            settings.setPassword(StringUtils.defaultIfEmpty(passwordField.getText(), null));
        }
        if (!settings.isEnabled() && settings.getHost() == null) {
            return null;
        }
        return settings;
    }

    /**
     * The error of the fields of an enabled tunnel, null if there is none
     */
    public String validate() {
        if (!enabledCheck.isSelected()) {
            return null;
        }
        SshSettings settings = getSettings();
        if (settings.getHost() == null) {
            return Messages.get("ssh.error.host");
        }
        if (settings.getPort() <= 0 || settings.getPort() > 65535) {
            return Messages.get("ssh.error.port");
        }
        if (settings.getUsername() == null) {
            return Messages.get("ssh.error.username");
        }
        if (settings.getAuthentication() == SshSettings.Authentication.KEY && settings.getPrivateKeyFile()
                == null) {
            return Messages.get("ssh.error.keyFile");
        }
        return null;
    }

    public boolean isTunnelEnabled() {
        return enabledCheck.isSelected();
    }
}
