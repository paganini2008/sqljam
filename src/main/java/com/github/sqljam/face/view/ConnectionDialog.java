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
import java.util.Optional;
import java.util.function.Consumer;

import com.github.sqljam.face.model.ConnectionProfile;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/**
 * @Description: ConnectionDialog creates or edits a saved connection
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ConnectionDialog extends Dialog<ConnectionProfile> {

    public ConnectionDialog(Window owner, AppContext context, ConnectionProfile profile) {
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        boolean editing = profile != null;
        setTitle(Messages.get(editing ? "connection.edit.title" : "connection.new.title"));
        setHeaderText(getTitle());
        setResizable(true);
        ConnectionForm form = new ConnectionForm();
        if (editing) {
            form.setProfile(profile, context.getProfileRegistry().isPasswordRemembered(profile.getId()));
        }
        form.setPrefWidth(520);
        getDialogPane().setContent(form);

        ButtonType testType = new ButtonType(Messages.get("connection.test"), ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(testType, ButtonType.OK, ButtonType.CANCEL);
        Button testButton = (Button) getDialogPane().lookupButton(testType);
        testButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            form.testConnection(null);
        });
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText(Messages.get("button.save"));
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String error = form.validate();
            if (error != null) {
                form.showError(error);
                event.consume();
                return;
            }
            try {
                context.getProfileRegistry().saveProfile(form.getProfile(), form.isPasswordRemembered());
            } catch (IOException e) {
                event.consume();
                Dialogs.showError(getOwner(), Messages.get("connection.save.error"), e);
            }
        });
        setResultConverter(buttonType -> buttonType == ButtonType.OK ? form.getProfile() : null);
    }

    /**
     * Target data source of an import: the combo box and a button creating a new data source, which is passed to
     * the consumer to be listed and selected
     */
    public static HBox targetField(ComboBox<ConnectionProfile> combo, AppContext context,
                                   Consumer<ConnectionProfile> onCreated) {
        Button newButton = new Button(null, Icons.of(Icons.ADD));
        newButton.setId("newTargetButton");
        newButton.setTooltip(new Tooltip(Messages.get("action.newTargetConnection")));
        newButton.setOnAction(event -> show(combo.getScene() != null ? combo.getScene().getWindow() : null, context,
                null).ifPresent(onCreated));
        combo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(combo, Priority.ALWAYS);
        HBox field = new HBox(6, combo, newButton);
        field.setAlignment(Pos.CENTER_LEFT);
        return field;
    }

    /**
     * Selects the data source of the same id in the combo box, the selection is kept if it is not listed
     */
    public static void selectProfile(ComboBox<ConnectionProfile> combo, ConnectionProfile profile) {
        combo.getItems().stream().filter(item -> item.getId().equals(profile.getId())).findFirst()
                .ifPresent(combo::setValue);
    }

    public static Optional<ConnectionProfile> show(Window owner, AppContext context, ConnectionProfile profile) {
        return new ConnectionDialog(owner, context, profile).showAndWait();
    }
}
