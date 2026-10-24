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

import com.github.sqljam.face.view.Branding;
import com.github.sqljam.face.view.DbIcons;
import com.github.sqljam.face.view.Dialogs;
import com.github.sqljam.face.view.Icons;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.vdb.VdbType;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * @Description: VdbConnectionDialog creates or edits a vector database: its name and type, and the fields of
 *               VdbConnectionForm. Test connects and lists the collections.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbConnectionDialog extends Dialog<VdbProfile> {

    static final double LABEL_WIDTH = 130;

    private final TextField nameField = new TextField();
    private final ComboBox<VdbType> typeCombo = new ComboBox<>();
    private final VdbConnectionForm form = new VdbConnectionForm(LABEL_WIDTH);
    private final Label statusLabel = new Label();

    public VdbConnectionDialog(Window owner, VdbProfile profile, boolean secretRemembered) {
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        setTitle(Messages.get(profile != null ? "vdb.connection.edit" : "vdb.connection.new"));
        setHeaderText(getTitle());
        nameField.setId("vdbNameField");
        typeCombo.setId("vdbTypeCombo");
        statusLabel.setId("vdbStatusLabel");
        nameField.setPromptText(Messages.get("connection.name.prompt"));
        typeCombo.getItems().setAll(VdbType.values());
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setCellFactory(view -> typeCell());
        typeCombo.setButtonCell(typeCell());
        form.setProfile(profile, secretRemembered);
        nameField.setText(profile != null ? profile.getName() : null);
        typeCombo.setValue(form.getType());
        typeCombo.valueProperty().addListener((obs, oldType, type) -> form.setType(type));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(LABEL_WIDTH);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        grid.addRow(0, new Label(Messages.get("connection.name")), nameField);
        grid.addRow(1, new Label(Messages.get("vdb.connection.type")), typeCombo);

        Button testButton = new Button(Messages.get("connection.test"), Icons.of(Icons.CONNECT));
        testButton.setId("vdbTestButton");
        testButton.setOnAction(event -> {
            showStatus("status-pending", Messages.get("connection.connecting"));
            form.test(nameField.getText(), message -> showStatus("status-success", message),
                    error -> showStatus("status-error", error));
        });
        statusLabel.setWrapText(true);
        HBox testBox = new HBox(10, testButton, statusLabel);
        VBox content = new VBox(10, grid, form, testBox);
        content.setPadding(new Insets(4));
        content.setPrefWidth(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String error = form.validate();
            if (error != null) {
                showStatus("status-error", error);
                event.consume();
            }
        });
        setResultConverter(button -> button == ButtonType.OK ? read() : null);
    }

    static ListCell<VdbType> typeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(VdbType type, boolean empty) {
                super.updateItem(type, empty);
                setText(empty || type == null ? null : type.getDisplayName());
                setGraphic(empty || type == null ? null : DbIcons.of(type.getDisplayName()));
            }
        };
    }

    private void showStatus(String styleClass, String text) {
        statusLabel.getStyleClass().setAll("label", styleClass);
        statusLabel.setText(text);
    }

    public boolean isSecretRemembered() {
        return form.isSecretRemembered();
    }

    VdbProfile read() {
        return form.read(nameField.getText());
    }
}
