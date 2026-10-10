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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbCategory;
import com.github.sqljam.impexp.DbType;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: DbTypeCellsTest verifies data source categories: relational databases before OLAP databases, a
 *               header before the first item of each category in type combos and data source lists
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class DbTypeCellsTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new Scene(new StackPane(), 400, 600));
        stage.show();
    }

    private static ConnectionProfile profile(String name, DbType dbType) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setDbType(dbType);
        return profile;
    }

    /**
     * Texts of category headers of a shown list
     */
    private List<String> headers(ListView<?> listView) {
        FxTestSupport.run(() -> {
            if (listView.getScene() == null) {
                stage.getScene().setRoot(new StackPane(listView));
            }
            listView.refresh();
        });
        // Cells are created by the next layout pass
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !listView.lookupAll(".category-header").isEmpty()));
        return FxTestSupport.call(() -> listView.lookupAll(".category-header").stream()
                .map(node -> ((Label) node).getText()).collect(Collectors.toList()));
    }

    @Test
    void ordersTypesByCategory() {
        List<DbType> types = DbTypeCells.getDbTypesByCategory();
        assertEquals(DbType.values().length, types.size());
        int firstOlap = types.indexOf(DbType.DUCKDB);
        assertTrue(types.subList(0, firstOlap).stream().allMatch(type -> type.getCategory() == DbCategory.RELATIONAL));
        assertTrue(types.subList(firstOlap, types.size()).stream()
                .allMatch(type -> type.getCategory() == DbCategory.OLAP));
        assertEquals(DbCategory.OLAP, DbType.DUCKDB.getCategory());
        assertEquals(DbCategory.OLAP, DbType.CLICKHOUSE.getCategory());
        assertEquals(DbCategory.RELATIONAL, DbType.MYSQL.getCategory());
        assertEquals(DbCategory.RELATIONAL, DbTypeCells.categoryOf(null));
        assertEquals(DbCategory.RELATIONAL, DbTypeCells.categoryOf(new ConnectionProfile()));
    }

    @Test
    void startsCategories() {
        List<DbType> types = List.of(DbType.MYSQL, DbType.H2, DbType.DUCKDB);
        assertTrue(DbTypeCells.isCategoryStart(types, 0, DbType::getCategory));
        assertFalse(DbTypeCells.isCategoryStart(types, 1, DbType::getCategory));
        assertTrue(DbTypeCells.isCategoryStart(types, 2, DbType::getCategory));
        assertFalse(DbTypeCells.isCategoryStart(types, -1, DbType::getCategory));
        assertFalse(DbTypeCells.isCategoryStart(types, 3, DbType::getCategory));
    }

    @Test
    void showsHeadersInTypeCombo() {
        List<String> headers = FxTestSupport.call(() -> {
            ComboBox<DbType> combo = new ComboBox<>();
            DbTypeCells.setupDbTypeCombo(combo);
            ListView<DbType> listView = new ListView<>();
            listView.getItems().setAll(DbTypeCells.getDbTypesByCategory());
            listView.setCellFactory(combo.getCellFactory());
            return List.of(listView);
        }).stream().flatMap(listView -> headers(listView).stream()).collect(Collectors.toList());
        assertEquals(List.of("Relational databases", "OLAP databases"), headers);
    }

    @Test
    void showsHeadersInProfileList() {
        ListView<ConnectionProfile> listView = FxTestSupport.call(() -> {
            ListView<ConnectionProfile> profiles = new ListView<>();
            DbTypeCells.setupProfileList(profiles);
            profiles.getItems().setAll(profile("Orders", DbType.MYSQL), profile("Local", DbType.H2),
                    profile("Lake", DbType.DUCKDB));
            return profiles;
        });
        assertEquals(List.of("Relational databases", "OLAP databases"), headers(listView));
        // Without OLAP data sources only one header is shown
        FxTestSupport.run(() -> listView.getItems().remove(2));
        assertEquals(List.of("Relational databases"), headers(listView));
    }
}
