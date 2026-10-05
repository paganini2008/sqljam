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

import com.github.sqljam.face.model.ConnectionProfile;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

/**
 * @Description: ConnectionDialog creates or edits a saved connection
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ConnectionDialog extends Dialog<ConnectionProfile> {

    public ConnectionDialog(Window owner, AppContext context, ConnectionProfile profile) {
        initOwner(owner);
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

    public static Optional<ConnectionProfile> show(Window owner, AppContext context, ConnectionProfile profile) {
        return new ConnectionDialog(owner, context, profile).showAndWait();
    }
}
