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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbCategory;
import com.github.sqljam.impexp.DbType;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * @Description: DbTypeCells renders database types and connection profiles in lists and combo boxes, grouped by
 *               categories (relational and OLAP databases) with a header before the first item of each category
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class DbTypeCells {

    /**
     * Style class of a cell starting a category
     */
    static final String CATEGORY_START = "category-start";

    private DbTypeCells() {
    }

    /**
     * Database types ordered by category, the order of DbType is kept in a category
     */
    public static List<DbType> getDbTypesByCategory() {
        return Arrays.stream(DbType.values()).sorted(Comparator.comparing(DbType::getCategory))
                .collect(Collectors.toList());
    }

    /**
     * Category of a profile, relational if the type is unknown
     */
    public static DbCategory categoryOf(ConnectionProfile profile) {
        return profile != null && profile.getDbType() != null ? profile.getDbType().getCategory()
                : DbCategory.RELATIONAL;
    }

    /**
     * Whether the item at the index starts a category (or a group) of the list
     */
    static <T> boolean isCategoryStart(List<T> items, int index, Function<T, ?> category) {
        if (index < 0 || index >= items.size()) {
            return false;
        }
        return index == 0 || !Objects.equals(category.apply(items.get(index - 1)), category.apply(items.get(
                index)));
    }

    /**
     * Cells of themes have a fixed height, a cell starting a category is higher for its header
     */
    static void setCategoryStart(ListCell<?> cell, boolean start) {
        cell.getStyleClass().remove(CATEGORY_START);
        // Inline, dialogs have the theme without the stylesheet of the application
        cell.setStyle(start ? "-fx-cell-size: " + (cell.getListView() != null
                && cell.getListView().getStyleClass().contains("profile-list") ? "4.6em" : "4em") : null);
        if (start) {
            cell.getStyleClass().add(CATEGORY_START);
        }
    }

    /**
     * Content with the category header above it
     */
    static Node withHeader(DbCategory category, Node content) {
        return withHeader(category.getDisplayName(), content);
    }

    /**
     * Content with the header of a group above it, e.g. Vector databases
     */
    static Node withHeader(String group, Node content) {
        Label header = new Label(group, DbIcons.of(group));
        header.getStyleClass().add("category-header");
        header.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;"
                + " -fx-padding: 4 0 2 0;");
        return new VBox(2, header, content);
    }

    /**
     * Colored icon of the database of a profile, the icon of a connection if its type is unknown
     */
    public static Node iconOf(ConnectionProfile profile) {
        return profile != null && profile.getDbType() != null ? DbIcons.of(profile.getDbType().getDisplayName())
                : Icons.of(Icons.CONNECTION);
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
                    setGraphic(iconOf(item));
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

    /**
     * Profiles ordered by category have a category header before the first profile of each category
     */
    public static void setupProfileList(ListView<ConnectionProfile> listView) {
        listView.getStyleClass().add("profile-list");
        listView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile item, boolean empty) {
                super.updateItem(item, empty);
                setCategoryStart(this, false);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String text = item.getName() + "\n" + (item.getDbType() != null ? item.getDbType().getDisplayName()
                        : "");
                if (isCategoryStart(view.getItems(), getIndex(), DbTypeCells::categoryOf)) {
                    setCategoryStart(this, true);
                    setText(null);
                    // The gap of the icon and text of a cell
                    setGraphic(withHeader(categoryOf(item), new HBox(getGraphicTextGap(), iconOf(item),
                            new Label(text))));
                } else {
                    setText(text);
                    setGraphic(iconOf(item));
                }
            }
        });
    }

    /**
     * Types of the combo have a category header before the first type of each category, the selected type is shown
     * without header
     */
    public static void setupDbTypeCombo(ComboBox<DbType> combo) {
        combo.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DbType item, boolean empty) {
                super.updateItem(item, empty);
                setCategoryStart(this, false);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else if (isCategoryStart(view.getItems(), getIndex(), DbType::getCategory) && view.getItems()
                        .stream().map(DbType::getCategory).distinct().count() > 1) {
                    setCategoryStart(this, true);
                    setText(null);
                    HBox type = new HBox(getGraphicTextGap(), DbIcons.of(item.getDisplayName()),
                            new Label(item.getDisplayName()));
                    type.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(withHeader(item.getCategory(), type));
                } else {
                    setText(item.getDisplayName());
                    setGraphic(DbIcons.of(item.getDisplayName()));
                }
            }
        });
        // The selected type shows its icon too
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(DbType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayName());
                setGraphic(empty || item == null ? null : DbIcons.of(item.getDisplayName()));
            }
        });
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
