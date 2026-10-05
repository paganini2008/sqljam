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

import java.io.IOException;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.javafx.FontIcon;
import com.github.sqljam.face.model.ConnectionProfile;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * @Description: LoginView is the startup page to choose a saved connection or connect a new database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class LoginView extends StackPane {

    private final AppContext context;
    private final ObservableList<ConnectionProfile> profiles = FXCollections.observableArrayList();
    private final FilteredList<ConnectionProfile> filteredProfiles = new FilteredList<>(profiles, profile -> true);
    private final ListView<ConnectionProfile> profileList = new ListView<>(filteredProfiles);
    private final ConnectionForm connectionForm = new ConnectionForm();
    private final Button loginButton = new Button(Messages.get("login.login"), Icons.of(Icons.CONNECT));
    private final Button testButton = new Button(Messages.get("connection.test"));
    private final Button skipButton = new Button(Messages.get("login.skip"));

    /**
     * @param onLogin called with the logged in profile, or null when skipped
     */
    public LoginView(AppContext context, Consumer<ConnectionProfile> onLogin) {
        this.context = context;
        getStyleClass().add("login-view");

        TextField searchField = new TextField();
        searchField.setId("searchField");
        profileList.setId("profileList");
        loginButton.setId("loginButton");
        testButton.setId("testButton");
        skipButton.setId("skipButton");
        searchField.setPromptText(Messages.get("login.search"));
        searchField.textProperty().addListener((obs, oldText, text) -> filteredProfiles.setPredicate(profile ->
                StringUtils.isBlank(text) || StringUtils.containsIgnoreCase(profile.getName(), text)
                        || (profile.getDbType() != null && StringUtils.containsIgnoreCase(
                        profile.getDbType().getDisplayName(), text))));
        DbTypeCells.setupProfileList(profileList);
        profileList.setPlaceholder(new Label(Messages.get("login.noConnections")));
        profileList.getSelectionModel().selectedItemProperty().addListener((obs, oldProfile, profile) -> {
            if (profile != null) {
                connectionForm.setProfile(profile, context.getProfileRegistry().isPasswordRemembered(
                        profile.getId()));
            }
        });
        VBox.setVgrow(profileList, Priority.ALWAYS);
        Button newButton = new Button(Messages.get("login.new"), Icons.of(Icons.ADD));
        newButton.setId("newButton");
        newButton.setOnAction(event -> {
            profileList.getSelectionModel().clearSelection();
            connectionForm.setProfile(new ConnectionProfile(), true);
            connectionForm.focusFirstField();
        });
        Button deleteButton = new Button(Messages.get("login.delete"), Icons.of(Icons.DELETE));
        deleteButton.setId("deleteButton");
        deleteButton.disableProperty().bind(profileList.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(event -> deleteSelected());
        HBox listButtons = new HBox(8, newButton, deleteButton);
        Label savedLabel = new Label(Messages.get("login.savedConnections"));
        savedLabel.getStyleClass().add("section-title");
        VBox left = new VBox(10, savedLabel, searchField, profileList, listButtons);
        left.setPrefWidth(260);
        left.getStyleClass().add("login-sidebar");

        FontIcon logo = Icons.of("fth-database");
        logo.getStyleClass().add("login-logo");
        Label title = new Label("SqlJam");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label(Messages.get("login.subtitle"));
        subtitle.getStyleClass().add("login-subtitle");
        VBox header = new VBox(2, title, subtitle);
        HBox headerBox = new HBox(12, logo, header);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        testButton.setOnAction(event -> connectionForm.testConnection(null));
        loginButton.setDefaultButton(true);
        loginButton.getStyleClass().add("accent");
        loginButton.setOnAction(event -> login(onLogin));
        skipButton.setOnAction(event -> onLogin.accept(null));
        testButton.disableProperty().bind(connectionForm.busyProperty());
        loginButton.disableProperty().bind(connectionForm.busyProperty());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, testButton, spacer, skipButton, loginButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox right = new VBox(16, headerBox, new Separator(), connectionForm, buttons);
        right.setPrefWidth(480);
        right.getStyleClass().add("login-form");

        HBox card = new HBox(0, left, right);
        card.getStyleClass().add("login-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        HBox.setHgrow(right, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(new BorderPane(card));
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        BorderPane wrapper = (BorderPane) scrollPane.getContent();
        BorderPane.setAlignment(card, Pos.CENTER);
        BorderPane.setMargin(card, new Insets(24));
        getChildren().add(scrollPane);

        reloadProfiles();
        if (!profiles.isEmpty()) {
            profileList.getSelectionModel().selectFirst();
        }
        wrapper.getStyleClass().add("login-background");
    }

    private void reloadProfiles() {
        profiles.setAll(context.getProfileRegistry().getProfiles());
        profiles.sort((a, b) -> StringUtils.compareIgnoreCase(a.getName(), b.getName()));
    }

    private void deleteSelected() {
        ConnectionProfile profile = profileList.getSelectionModel().getSelectedItem();
        if (profile == null || !Dialogs.confirm(getScene().getWindow(), Messages.get("connection.delete.title"),
                Messages.format("connection.delete.message", profile.getName()))) {
            return;
        }
        try {
            context.getProfileRegistry().removeProfile(profile.getId());
            reloadProfiles();
            connectionForm.setProfile(new ConnectionProfile(), true);
        } catch (IOException e) {
            Dialogs.showError(getScene().getWindow(), Messages.get("connection.save.error"), e);
        }
    }

    private void login(Consumer<ConnectionProfile> onLogin) {
        connectionForm.testConnection(product -> {
            ConnectionProfile profile = connectionForm.getProfile();
            try {
                context.getProfileRegistry().saveProfile(profile, connectionForm.isPasswordRemembered());
            } catch (IOException e) {
                Dialogs.showError(getScene().getWindow(), Messages.get("connection.save.error"), e);
                return;
            }
            onLogin.accept(profile);
        });
    }
}
