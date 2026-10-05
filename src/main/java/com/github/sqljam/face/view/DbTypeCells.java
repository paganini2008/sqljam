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

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.util.StringConverter;

/**
 * @Description: DbTypeCells renders database types and connection profiles in lists and combo boxes
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class DbTypeCells {

    private DbTypeCells() {
    }

    public static ListCell<ConnectionProfile> profileCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getName());
                    setGraphic(Icons.of(Icons.CONNECTION));
                }
            }
        };
    }

    public static void setupProfileCombo(ComboBox<ConnectionProfile> combo) {
        combo.setCellFactory(listView -> profileCell());
        combo.setButtonCell(profileCell());
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ConnectionProfile profile) {
                return profile != null ? profile.getName() : "";
            }

            @Override
            public ConnectionProfile fromString(String text) {
                return null;
            }
        });
    }

    public static void setupProfileList(ListView<ConnectionProfile> listView) {
        listView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getName() + "\n" + (item.getDbType() != null ? item.getDbType().getDisplayName()
                            : ""));
                    setGraphic(Icons.of(Icons.CONNECTION));
                }
            }
        });
    }

    public static void setupDbTypeCombo(ComboBox<DbType> combo) {
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DbType dbType) {
                return dbType != null ? dbType.getDisplayName() : "";
            }

            @Override
            public DbType fromString(String text) {
                return null;
            }
        });
    }
}
