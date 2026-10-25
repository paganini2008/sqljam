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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.Banner;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * @Description: Dialogs shows common alerts, errors are shown with expandable stack traces
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class Dialogs {

    private Dialogs() {
    }

    /**
     * Sets the owner of a dialog. JavaFX fails with a NullPointerException for an owner window without a scene,
     * the dialog is then shown without an owner.
     */
    public static void initOwner(Dialog<?> dialog, Window owner) {
        if (owner != null && owner.getScene() != null) {
            dialog.initOwner(owner);
        }
    }

    public static void showError(Window owner, String title, Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && StringUtils.isBlank(cause.getMessage())) {
            cause = cause.getCause();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        initOwner(alert, owner);
        Branding.applyIcons(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(StringUtils.abbreviate(StringUtils.defaultIfBlank(cause.getMessage(),
                cause.getClass().getName()), 1000));

        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        TextArea textArea = new TextArea(writer.toString());
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.getStyleClass().add("mono");
        textArea.setPrefRowCount(16);
        GridPane.setVgrow(textArea, Priority.ALWAYS);
        GridPane.setHgrow(textArea, Priority.ALWAYS);
        GridPane content = new GridPane();
        content.setMaxWidth(Double.MAX_VALUE);
        content.add(new Label(Messages.get("dialog.error.details")), 0, 0);
        content.add(textArea, 0, 1);
        alert.getDialogPane().setExpandableContent(content);
        alert.getDialogPane().setPrefWidth(640);
        alert.showAndWait();
    }

    public static void showInfo(Window owner, String title, String message) {
        show(Alert.AlertType.INFORMATION, owner, title, message);
    }

    public static void showWarning(Window owner, String title, String message) {
        show(Alert.AlertType.WARNING, owner, title, message);
    }

    private static void show(Alert.AlertType type, Window owner, String title, String message) {
        Alert alert = new Alert(type);
        initOwner(alert, owner);
        Branding.applyIcons(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * About SqlJam with the version, the home page, the source repository, the author and the license
     *
     * @param browser opens the links in the browser, null for links without an action
     */
    public static Alert createAbout(Window owner, Consumer<String> browser) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        initOwner(alert, owner);
        Branding.applyIcons(alert);
        alert.setTitle(Messages.get("about.title"));
        alert.setHeaderText(Messages.format("about.header", Banner.getVersion()));
        ImageView logo = Branding.logoView(96);
        logo.setId("aboutLogo");
        alert.setGraphic(logo);
        alert.getDialogPane().setPrefWidth(500);
        Label message = new Label(Messages.get("about.message"));
        message.setWrapText(true);
        GridPane details = new GridPane();
        details.setId("aboutDetails");
        details.setHgap(16);
        details.setVgap(4);
        details.addRow(0, aboutLabel("about.version"), new Label(Banner.getVersion()));
        details.addRow(1, aboutLabel("about.homepage"), aboutLink("aboutHomepage", Branding.HOMEPAGE_URL,
                Branding.HOMEPAGE_URL, browser));
        details.addRow(2, aboutLabel("about.repository"), aboutLink("aboutRepository", Branding.REPOSITORY_URL,
                Branding.REPOSITORY_URL, browser));
        details.addRow(3, aboutLabel("about.author"), new Label(Branding.AUTHOR));
        details.addRow(4, aboutLabel("about.email"), aboutLink("aboutEmail", Branding.EMAIL,
                "mailto:" + Branding.EMAIL, browser));
        details.addRow(5, aboutLabel("about.license"), new Label(Messages.get("about.licenseName")));
        // About and the map of the features
        Tab aboutTab = new Tab(Messages.get("about.tab"), new VBox(14, message, details));
        aboutTab.setId("aboutTab");
        ScrollPane mapPane = new ScrollPane(new FeatureMap());
        mapPane.setFitToWidth(true);
        mapPane.setPrefViewportHeight(560);
        Tab featuresTab = new Tab(Messages.get("about.features"), mapPane);
        featuresTab.setId("featuresTab");
        TabPane tabs = new TabPane(aboutTab, featuresTab);
        tabs.setId("aboutTabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // The map needs room, the dialog grows while it is shown
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, tab) -> {
            double width = tab == featuresTab ? 1000 : 500;
            alert.getDialogPane().setPrefWidth(width);
            // A dialog which is shown takes another width by the dialog, it stays in the middle
            if (alert.isShowing()) {
                double centerX = alert.getX() + alert.getWidth() / 2;
                alert.setWidth(width + alert.getWidth() - alert.getDialogPane().getWidth());
                alert.setX(centerX - alert.getWidth() / 2);
            }
        });
        alert.getDialogPane().setContent(tabs);
        return alert;
    }

    private static Label aboutLabel(String key) {
        Label label = new Label(Messages.get(key));
        label.getStyleClass().add("muted");
        return label;
    }

    private static Hyperlink aboutLink(String id, String text, String url, Consumer<String> browser) {
        Hyperlink link = new Hyperlink(text);
        link.setId(id);
        link.setPadding(Insets.EMPTY);
        link.setOnAction(event -> {
            // Not shown as visited, it is opened again
            link.setVisited(false);
            if (browser != null) {
                browser.accept(url);
            }
        });
        return link;
    }

    public static void showAbout(Window owner, Consumer<String> browser) {
        createAbout(owner, browser).showAndWait();
    }

    public static boolean confirm(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        initOwner(alert, owner);
        Branding.applyIcons(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
