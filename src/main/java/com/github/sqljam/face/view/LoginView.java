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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import com.github.sqljam.impexp.DbCategory;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.stage.Window;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.ExampleDatabase;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
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
    /**
     * A source of an extension in the list, e.g. a vector database
     */
    static final class SourceEntry {

        final DataSourceExtension extension;
        final DataSourceExtension.Source source;

        SourceEntry(DataSourceExtension extension, DataSourceExtension.Source source) {
            this.extension = extension;
            this.source = source;
        }

        @Override
        public String toString() {
            return source.getName();
        }
    }

    private final List<DataSourceExtension> extensions;
    /**
     * Data sources (ConnectionProfile) and sources of extensions (SourceEntry)
     */
    private final ObservableList<Object> profiles = FXCollections.observableArrayList();
    private final FilteredList<Object> filteredProfiles = new FilteredList<>(profiles, profile -> true);
    // Grouped by category: relational databases, OLAP databases, then the kinds of the extensions
    private final SortedList<Object> sortedProfiles = new SortedList<>(filteredProfiles,
            Comparator.comparingInt(this::groupIndex));
    private final ListView<Object> profileList = new ListView<>(sortedProfiles);
    private final ConnectionForm connectionForm;
    private final Button loginButton = new Button(Messages.get("login.login"), Icons.of(Icons.CONNECT));
    private final Button testButton = new Button(Messages.get("connection.test"));
    private final Button skipButton = new Button(Messages.get("login.skip"));

    /**
     * @param onLogin called with the logged in profile, or null when skipped
     */
    public LoginView(AppContext context, Consumer<ConnectionProfile> onLogin) {
        this.context = context;
        this.extensions = DataSourceExtension.load(context.getSettingsStore().getConfig());
        this.connectionForm = new ConnectionForm(extensions);
        getStyleClass().add("login-view");

        TextField searchField = new TextField();
        searchField.setId("searchField");
        profileList.setId("profileList");
        loginButton.setId("loginButton");
        testButton.setId("testButton");
        skipButton.setId("skipButton");
        searchField.setPromptText(Messages.get("login.search"));
        searchField.textProperty().addListener((obs, oldText, text) -> filteredProfiles.setPredicate(item ->
                StringUtils.isBlank(text) || StringUtils.containsIgnoreCase(nameOf(item), text)
                        || StringUtils.containsIgnoreCase(typeOf(item), text)));
        setupList();
        // Another category chosen in the form is a new data source, the selection of the list is cleared
        connectionForm.categoryProperty().addListener((obs, oldCategory, category) -> {
            Object selected = profileList.getSelectionModel().getSelectedItem();
            if (selected != null && !groupOf(selected).equals(categoryName(category))) {
                profileList.getSelectionModel().clearSelection();
            }
        });
        profileList.setPlaceholder(new Label(Messages.get("login.noConnections")));
        profileList.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, item) -> {
            if (item instanceof ConnectionProfile) {
                ConnectionProfile profile = (ConnectionProfile) item;
                connectionForm.setProfile(profile, context.getProfileRegistry().isPasswordRemembered(
                        profile.getId()));
            } else if (item instanceof SourceEntry) {
                connectionForm.setSource(((SourceEntry) item).extension, ((SourceEntry) item).source);
            }
        });
        VBox.setVgrow(profileList, Priority.ALWAYS);
        // Icons of the list like DBeaver and DataGrip, the names are their tooltips; texts are never cut off
        Button newButton = new Button(null, Icons.of(Icons.ADD));
        newButton.setId("newButton");
        newButton.setTooltip(new Tooltip(Messages.get("login.new")));
        newButton.setAccessibleText(Messages.get("login.new"));
        newButton.setOnAction(event -> {
            profileList.getSelectionModel().clearSelection();
            connectionForm.setProfile(new ConnectionProfile(), true);
            connectionForm.focusFirstField();
        });
        Button deleteButton = new Button(null, Icons.of(Icons.DELETE));
        deleteButton.setId("deleteButton");
        deleteButton.setTooltip(new Tooltip(Messages.get("login.delete")));
        deleteButton.setAccessibleText(Messages.get("login.delete"));
        deleteButton.disableProperty().bind(profileList.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(event -> deleteSelected());
        // The example database comes back after the user removed it
        Button exampleButton = new Button(Messages.get("example.button"), Icons.of(Icons.CATALOG));
        exampleButton.setId("exampleButton");
        exampleButton.setTooltip(new Tooltip(Messages.get("example.button.tooltip")));
        exampleButton.setOnAction(event -> ExampleActions.restore(getScene().getWindow(), context, profile -> {
            reloadProfiles();
            profileList.getItems().stream().filter(item -> item instanceof ConnectionProfile && ExampleDatabase
                    .isExample((ConnectionProfile) item)).findFirst()
                    .ifPresent(profileList.getSelectionModel()::select);
        }));
        HBox listButtons = new HBox(8, newButton, deleteButton, exampleButton);
        listButtons.getChildren().forEach(button -> ((Button) button).setMinWidth(Region.USE_PREF_SIZE));
        Label savedLabel = new Label(Messages.get("login.savedConnections"));
        savedLabel.getStyleClass().add("section-title");
        VBox left = new VBox(10, savedLabel, searchField, profileList, listButtons);
        left.setPrefWidth(260);
        left.getStyleClass().add("login-sidebar");

        ImageView logo = Branding.logoView(60);
        logo.setId("loginLogo");
        logo.getStyleClass().add("login-logo");
        Label title = new Label("SqlJam");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label(Messages.get("login.subtitle"));
        subtitle.getStyleClass().add("login-subtitle");
        VBox header = new VBox(2, title, subtitle);
        // The GitHub mark of the source repository at the top right, the card keeps its size
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Label repositoryMark = Branding.repositoryMark(context::openDocument);
        HBox headerBox = new HBox(16, logo, header, headerSpacer, repositoryMark);
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
        List<Object> items = new ArrayList<>(context.getProfileRegistry().getProfiles());
        for (DataSourceExtension extension : extensions) {
            extension.getSources().forEach(source -> items.add(new SourceEntry(extension, source)));
        }
        items.sort((a, b) -> StringUtils.compareIgnoreCase(nameOf(a), nameOf(b)));
        profiles.setAll(items);
    }

    /**
     * Order of the group of an item: the categories, then the extensions
     */
    private int groupIndex(Object item) {
        if (item instanceof SourceEntry) {
            return DbCategory.values().length + extensions.indexOf(((SourceEntry) item).extension);
        }
        return DbTypeCells.categoryOf((ConnectionProfile) item).ordinal();
    }

    private static String categoryName(Object category) {
        return category instanceof DataSourceExtension ? ((DataSourceExtension) category).getGroupName()
                : category instanceof DbCategory ? ((DbCategory) category).getDisplayName() : "";
    }

    private static String groupOf(Object item) {
        return item instanceof SourceEntry ? ((SourceEntry) item).extension.getGroupName()
                : DbTypeCells.categoryOf((ConnectionProfile) item).getDisplayName();
    }

    public static String nameOf(Object item) {
        return item instanceof SourceEntry ? ((SourceEntry) item).source.getName()
                : ((ConnectionProfile) item).getName();
    }

    static String typeOf(Object item) {
        if (item instanceof SourceEntry) {
            return ((SourceEntry) item).source.getTypeName();
        }
        ConnectionProfile profile = (ConnectionProfile) item;
        return profile.getDbType() != null ? profile.getDbType().getDisplayName() : "";
    }

    /**
     * Items with the colored icons of their databases and a header before the first item of each group
     */
    private void setupList() {
        profileList.getStyleClass().add("profile-list");
        profileList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                DbTypeCells.setCategoryStart(this, false);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String text = nameOf(item) + "\n" + typeOf(item);
                Node icon = item instanceof SourceEntry ? DbIcons.of(typeOf(item))
                        : DbTypeCells.iconOf((ConnectionProfile) item);
                if (DbTypeCells.isCategoryStart(view.getItems(), getIndex(), LoginView::groupOf)) {
                    DbTypeCells.setCategoryStart(this, true);
                    setText(null);
                    setGraphic(DbTypeCells.withHeader(groupOf(item), new HBox(getGraphicTextGap(), icon,
                            new Label(text))));
                } else {
                    setText(text);
                    setGraphic(icon);
                }
            }
        });
    }

    private void deleteSelected() {
        Object selected = profileList.getSelectionModel().getSelectedItem();
        if (selected instanceof SourceEntry) {
            if (((SourceEntry) selected).source.delete(getScene().getWindow(), context)) {
                reloadProfiles();
                connectionForm.setProfile(new ConnectionProfile(), true);
            }
            return;
        }
        ConnectionProfile profile = (ConnectionProfile) selected;
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
        if (connectionForm.getSelectedExtension() != null) {
            // A source of an extension is saved, the main window opens with its action, e.g. the import of a
            // vector database
            connectionForm.testConnection(message -> {
                DataSourceExtension.Source source;
                try {
                    source = connectionForm.saveSource();
                } catch (IOException e) {
                    Dialogs.showError(getScene().getWindow(), Messages.get("connection.save.error"), e);
                    return;
                }
                Window window = getScene().getWindow();
                onLogin.accept(null);
                Platform.runLater(() -> source.open(window, context));
            });
            return;
        }
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
