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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.face.view.TaskRunner;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbType;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

/**
 * @Description: VdbConnectionForm is the fields of a vector database below its name and type: the url of the REST
 *               API, the authentication which the type supports (none, an API key, or a user and a password) with
 *               the fields of that authentication only, the database (Milvus, Chroma) and the tenant (Chroma) for the
 *               types which have them, a self-signed certificate and whether the credentials are saved. The url of
 *               the default port follows the type until it is edited. Used by the data source form of the login page
 *               and of New Data Source, and by VdbConnectionDialog.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbConnectionForm extends GridPane {

    /**
     * Authentication of a vector database, a type supports some of them
     */
    public enum Authentication {
        NONE, API_KEY, USER_PASSWORD
    }

    private final TextField urlField = new TextField();
    private final ComboBox<Authentication> authenticationCombo = new ComboBox<>();
    private final PasswordField apiKeyField = new PasswordField();
    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField databaseField = new TextField();
    private final TextField tenantField = new TextField();
    private final CheckBox trustCheck = new CheckBox(Messages.get("vdb.connection.trust"));
    private final CheckBox rememberCheck = new CheckBox(Messages.get("vdb.connection.remember"));
    private final Label apiKeyLabel = new Label(Messages.get("vdb.connection.apiKey"));
    private final Label usernameLabel = new Label(Messages.get("connection.username"));
    private final Label passwordLabel = new Label(Messages.get("connection.password"));
    private final Label databaseLabel = new Label(Messages.get("connection.database"));
    private final Label tenantLabel = new Label(Messages.get("vdb.connection.tenant"));
    private VdbProfile profile = new VdbProfile();
    private VdbType type;

    /**
     * @param labelWidth width of the labels, the same as the labels of the form around it
     */
    public VdbConnectionForm(double labelWidth) {
        setHgap(10);
        setVgap(10);
        urlField.setId("vdbUrlField");
        authenticationCombo.setId("vdbAuthenticationCombo");
        apiKeyField.setId("vdbApiKeyField");
        usernameField.setId("vdbUsernameField");
        passwordField.setId("vdbPasswordField");
        databaseField.setId("vdbDatabaseField");
        tenantField.setId("vdbTenantField");
        trustCheck.setId("vdbTrustCheck");
        rememberCheck.setId("vdbRememberCheck");
        urlField.setPromptText("http://localhost:6333");
        apiKeyField.setPromptText(Messages.get("vdb.connection.apiKey.prompt"));
        databaseField.setPromptText(Messages.get("vdb.connection.database.prompt"));
        tenantField.setPromptText("default_tenant");
        authenticationCombo.setMaxWidth(Double.MAX_VALUE);
        authenticationCombo.setCellFactory(view -> authenticationCell());
        authenticationCombo.setButtonCell(authenticationCell());
        authenticationCombo.valueProperty().addListener((obs, oldValue, value) -> showAuthentication(value));
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(labelWidth);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        getColumnConstraints().addAll(labelColumn, fieldColumn);
        addRow(0, new Label(Messages.get("vdb.connection.url")), urlField);
        addRow(1, new Label(Messages.get("vdb.connection.authentication")), authenticationCombo);
        addRow(2, apiKeyLabel, apiKeyField);
        addRow(3, usernameLabel, usernameField);
        addRow(4, passwordLabel, passwordField);
        addRow(5, databaseLabel, databaseField);
        addRow(6, tenantLabel, tenantField);
        add(trustCheck, 1, 7);
        add(rememberCheck, 1, 8);
        rememberCheck.setSelected(true);
        setType(VdbType.QDRANT);
    }

    private static ListCell<Authentication> authenticationCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Authentication item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Messages.get("vdb.authentication." + item.name()
                        .toLowerCase(Locale.ENGLISH)));
            }
        };
    }

    private static void show(boolean visible, Node... nodes) {
        for (Node node : nodes) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }

    private void showAuthentication(Authentication authentication) {
        show(authentication == Authentication.API_KEY, apiKeyLabel, apiKeyField);
        show(authentication == Authentication.USER_PASSWORD, usernameLabel, usernameField, passwordLabel,
                passwordField);
        show(authentication != null && authentication != Authentication.NONE, rememberCheck);
    }

    /**
     * Authentications of a type: none, and the ones which the type supports
     */
    static List<Authentication> getAuthentications(VdbType type) {
        List<Authentication> authentications = new ArrayList<>();
        authentications.add(Authentication.NONE);
        if (type != null && type.isApiKeySupported()) {
            authentications.add(Authentication.API_KEY);
        }
        if (type != null && type.isUserSupported()) {
            authentications.add(Authentication.USER_PASSWORD);
        }
        return authentications;
    }

    /**
     * Type of the vector database: the url of the old default port is replaced, the authentications, the database
     * and the tenant follow the type
     */
    public void setType(VdbType newType) {
        VdbType oldType = type;
        type = newType;
        if (newType != null && (StringUtils.isBlank(urlField.getText()) || (oldType != null && urlField.getText()
                .equals(oldType.getUrl("localhost", 0))))) {
            urlField.setText(newType.getUrl("localhost", 0));
        }
        Authentication selected = authenticationCombo.getValue();
        List<Authentication> authentications = getAuthentications(newType);
        authenticationCombo.getItems().setAll(authentications);
        // The authentication is kept if the type has it, otherwise the first one with credentials
        authenticationCombo.setValue(selected != null && authentications.contains(selected) ? selected
                : authentications.get(authentications.size() > 1 ? 1 : 0));
        show(newType != null && newType.isDatabaseSupported(), databaseLabel, databaseField);
        show(newType != null && newType.isTenantSupported(), tenantLabel, tenantField);
    }

    public VdbType getType() {
        return type;
    }

    public Authentication getAuthentication() {
        return authenticationCombo.getValue();
    }

    public void setAuthentication(Authentication authentication) {
        authenticationCombo.setValue(authentication);
    }

    /**
     * Shows a vector database, a new one if it is null
     */
    public void setProfile(VdbProfile profile, boolean secretRemembered) {
        this.profile = profile != null ? profile.copy() : new VdbProfile();
        urlField.setText(this.profile.getUrl());
        authenticationCombo.setValue(null);
        setType(this.profile.getType() != null ? this.profile.getType() : VdbType.QDRANT);
        apiKeyField.setText(this.profile.getApiKey());
        usernameField.setText(this.profile.getUsername());
        passwordField.setText(this.profile.getPassword());
        databaseField.setText(this.profile.getDatabase());
        tenantField.setText(this.profile.getTenant());
        trustCheck.setSelected(this.profile.isCertificateTrusted());
        rememberCheck.setSelected(secretRemembered);
        if (profile != null) {
            // A saved database shows the authentication of its credentials, without them it has none
            authenticationCombo.setValue(StringUtils.isNotBlank(this.profile.getUsername())
                    && type.isUserSupported() ? Authentication.USER_PASSWORD : this.profile.getApiKey() != null
                    || (!secretRemembered && type.isApiKeySupported()) ? Authentication.API_KEY
                    : Authentication.NONE);
        }
    }

    public boolean isSecretRemembered() {
        return rememberCheck.isSelected();
    }

    /**
     * Error of the fields, null if they are valid
     */
    public String validate() {
        if (type == null) {
            return Messages.get("connection.error.dbType");
        }
        String url = StringUtils.trimToEmpty(urlField.getText());
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Messages.get("vdb.connection.error.url");
        }
        if (getAuthentication() == Authentication.API_KEY && StringUtils.isBlank(apiKeyField.getText())) {
            return Messages.get("vdb.connection.error.apiKey");
        }
        if (getAuthentication() == Authentication.USER_PASSWORD && StringUtils.isBlank(usernameField.getText())) {
            return Messages.get("vdb.connection.error.username");
        }
        return null;
    }

    /**
     * The vector database of the fields, named by the type and the url when the name is blank. Only the
     * credentials of the authentication are kept, fields of the type only.
     */
    public VdbProfile read(String name) {
        Authentication authentication = getAuthentication();
        profile.setType(type);
        profile.setUrl(StringUtils.removeEnd(StringUtils.trimToEmpty(urlField.getText()), "/"));
        profile.setName(StringUtils.defaultIfBlank(StringUtils.trimToNull(name), type.getDisplayName() + " "
                + StringUtils.substringAfter(profile.getUrl(), "://")));
        profile.setApiKey(authentication == Authentication.API_KEY ? StringUtils.trimToNull(apiKeyField.getText())
                : null);
        boolean user = authentication == Authentication.USER_PASSWORD;
        profile.setUsername(user ? StringUtils.trimToNull(usernameField.getText()) : null);
        profile.setPassword(user && !StringUtils.isEmpty(passwordField.getText()) ? passwordField.getText() : null);
        profile.setDatabase(type.isDatabaseSupported() ? StringUtils.trimToNull(databaseField.getText()) : null);
        profile.setTenant(type.isTenantSupported() ? StringUtils.trimToNull(tenantField.getText()) : null);
        profile.setCertificateTrusted(trustCheck.isSelected());
        return profile.copy();
    }

    /**
     * Connects in the background and lists the collections
     */
    public void test(String name, Consumer<String> onSuccess, Consumer<String> onError) {
        String error = validate();
        if (error != null) {
            onError.accept(error);
            return;
        }
        VdbProfile tested = read(name);
        TaskRunner.run(() -> {
            try (VdbClient client = tested.getType().createClient(tested.toSource())) {
                String version = client.getVersion();
                int collections = client.getCollections().size();
                return Messages.format("vdb.connection.success", tested.getType().getDisplayName(),
                        StringUtils.defaultIfBlank(version, "?"), collections);
            }
        }, onSuccess, e -> onError.accept(Messages.format("connection.failure", e.getMessage())));
    }

    public void focusFirstField() {
        urlField.requestFocus();
    }
}
