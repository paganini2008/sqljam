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

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/**
 * @Description: CredentialsDialog asks for the secret of a data source when it is connected without one, or when
 *               the database refused it: the password of a database, the password or passphrase of its SSH tunnel,
 *               or the API key of a vector database. Save keeps it, otherwise it is kept for the session, like the
 *               authentication dialog of DBeaver.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class CredentialsDialog extends Dialog<CredentialsDialog.Credentials> {

    /**
     * The user and the secret which were entered, and whether they are saved
     */
    public static final class Credentials {

        private final String username;
        private final String secret;
        private final boolean saved;

        public Credentials(String username, String secret, boolean saved) {
            this.username = username;
            this.secret = secret;
            this.saved = saved;
        }

        public String getUsername() {
            return username;
        }

        public String getSecret() {
            return secret;
        }

        public boolean isSaved() {
            return saved;
        }
    }

    private final TextField usernameField = new TextField();
    private final PasswordField secretField = new PasswordField();
    private final CheckBox saveCheck = new CheckBox(Messages.get("credentials.save"));

    /**
     * @param name          name of the data source
     * @param secretLabel   label of the secret, e.g. Password, API key or Passphrase
     * @param username      user which is shown, null for a secret without a user (API key, passphrase)
     * @param message       why it is asked, e.g. the error of the database, null when the secret is missing
     */
    public CredentialsDialog(Window owner, String name, String secretLabel, String username, String message) {
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        setTitle(Messages.get("credentials.title"));
        setHeaderText(Messages.format("credentials.header", name));
        usernameField.setId("credentialsUsernameField");
        secretField.setId("credentialsSecretField");
        saveCheck.setId("credentialsSaveCheck");
        usernameField.setText(StringUtils.defaultString(username));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(110);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        int row = 0;
        if (message != null) {
            Label messageLabel = new Label(message);
            messageLabel.setId("credentialsMessageLabel");
            messageLabel.getStyleClass().add("status-error");
            messageLabel.setWrapText(true);
            messageLabel.setMaxWidth(420);
            grid.add(messageLabel, 0, row++, 2, 1);
        }
        if (username != null) {
            grid.addRow(row++, new Label(Messages.get("connection.username")), usernameField);
        }
        grid.addRow(row++, new Label(secretLabel), secretField);
        grid.add(saveCheck, 1, row);
        grid.setPrefWidth(440);
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(button -> button == ButtonType.OK ? new Credentials(username != null ? StringUtils
                .trimToNull(usernameField.getText()) : null, secretField.getText(), saveCheck.isSelected()) : null);
        Platform.runLater(secretField::requestFocus);
    }

    /**
     * Asks for the secret, empty when it is cancelled
     */
    public static Optional<Credentials> ask(Window owner, String name, String secretLabel, String username,
                                            String message) {
        return new CredentialsDialog(owner, name, secretLabel, username, message).showAndWait();
    }
}
