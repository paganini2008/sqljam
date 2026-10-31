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

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.sqljam.utils.RestClient;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

/**
 * @Description: ValueDialog shows the whole value of a cell of a table, whose cells show one line only: JSON is
 *               indented, a vector tells its dimensions, long text wraps, and Copy puts the value on the clipboard
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class ValueDialog {

    /**
     * JSON of more characters is shown as it is, indenting it would take long
     */
    static final int MAX_INDENTED_LENGTH = 1_000_000;

    private ValueDialog() {
    }

    /**
     * Text of the value in the dialog: JSON objects and arrays of objects are indented, other values are as they are
     */
    static String format(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > MAX_INDENTED_LENGTH || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return value;
        }
        try {
            JsonNode node = RestClient.getMapper().readTree(trimmed);
            if (isVector(node)) {
                return value;
            }
            return RestClient.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Whether the value is an array of numbers, e.g. a vector
     */
    static boolean isVector(JsonNode node) {
        if (!node.isArray() || node.size() == 0) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isNumber()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Summary of the value: its characters, or the dimensions of a vector
     */
    static String describe(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() <= MAX_INDENTED_LENGTH) {
            try {
                JsonNode node = RestClient.getMapper().readTree(trimmed);
                if (isVector(node)) {
                    return Messages.format("value.vector", String.valueOf(node.size()));
                }
            } catch (Exception e) {
                // Not JSON
            }
        }
        if (trimmed.startsWith("{") && trimmed.contains("}/")) {
            // A sparse vector of pgvector, {1:0.5,3:2}/8
            return Messages.format("value.sparse", StringUtils.substringAfterLast(trimmed, "}/"));
        }
        return Messages.format("value.length", String.valueOf(value.length()));
    }

    public static void show(Window owner, String column, String value) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        Dialogs.initOwner(alert, owner);
        Branding.applyIcons(alert);
        alert.setTitle(column);
        alert.setHeaderText(column + "  ·  " + (value == null ? "NULL" : describe(value)));
        TextArea area = new TextArea(value == null ? "NULL" : format(value));
        area.setId("valueArea");
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("mono");
        area.setPrefSize(720, 420);
        alert.getDialogPane().setContent(area);
        alert.setResizable(true);
        ButtonType copy = new ButtonType(Messages.get("action.copy"), ButtonBar.ButtonData.LEFT);
        alert.getButtonTypes().addAll(copy, ButtonType.CLOSE);
        Button copyButton = (Button) alert.getDialogPane().lookupButton(copy);
        copyButton.setId("copyValueButton");
        copyButton.setGraphic(Icons.of(Icons.COPY));
        copyButton.addEventFilter(ActionEvent.ACTION, event -> {
            // The dialog stays open
            event.consume();
            ValueCell.copy(value);
        });
        alert.show();
    }
}
