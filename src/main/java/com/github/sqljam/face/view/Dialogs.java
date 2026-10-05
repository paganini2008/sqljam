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

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.Banner;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
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

    public static void showError(Window owner, String title, Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && StringUtils.isBlank(cause.getMessage())) {
            cause = cause.getCause();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
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
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        Branding.applyIcons(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * About dialog with the logo and the version
     */
    public static Alert createAbout(Window owner) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        Branding.applyIcons(alert);
        alert.setTitle(Messages.get("about.title"));
        alert.setHeaderText(Messages.format("about.header", Banner.getVersion()));
        alert.setContentText(Messages.get("about.message"));
        ImageView logo = Branding.logoView(96);
        logo.setId("aboutLogo");
        alert.setGraphic(logo);
        alert.getDialogPane().setPrefWidth(460);
        return alert;
    }

    public static void showAbout(Window owner) {
        createAbout(owner).showAndWait();
    }

    public static boolean confirm(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        Branding.applyIcons(alert);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
