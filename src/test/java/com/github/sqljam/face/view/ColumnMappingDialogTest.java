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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.view.ColumnMappingDialog.Mapping;
import com.github.sqljam.impexp.ColumnMapping;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: ColumnMappingDialogTest maps the columns of two tables in the dialog: names, types and columns left
 *               out, a required column which is always imported, two columns of the same name and a table without
 *               columns which are refused, reset, and the mappings of the dialog as mappings of an import
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ColumnMappingDialogTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new Scene(new StackPane(), 400, 300));
        stage.show();
    }

    @AfterEach
    void close() {
        FxTestSupport.closeAllDialogs();
    }

    private static List<ColumnMappingDialog.Column> columns(String table) {
        if ("ORDERS".equals(table)) {
            return List.of(new ColumnMappingDialog.Column("ID", "BIGINT", true), new ColumnMappingDialog.Column(
                    "NOTE", "VARCHAR(100)", false), new ColumnMappingDialog.Column("SECRET", "VARCHAR(20)", false));
        }
        if ("BROKEN".equals(table)) {
            throw new IllegalStateException("no access");
        }
        return List.of(new ColumnMappingDialog.Column("NAME", "VARCHAR(20)", false));
    }

    private ColumnMappingDialog open(Map<String, Map<String, Mapping>> chosen,
                                     AtomicReference<Optional<Map<String, Map<String, Mapping>>>> result) {
        ColumnMappingDialog dialog = FxTestSupport.call(() -> new ColumnMappingDialog(stage, List.of("ORDERS",
                "CUSTOMERS", "BROKEN"), ColumnMappingDialogTest::columns, chosen));
        Platform.runLater(() -> result.set(dialog.showAndWait()));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> dialog.getRows().size() == 3));
        return dialog;
    }

    private static ColumnMappingDialog.Row row(ColumnMappingDialog dialog, String column) {
        return FxTestSupport.call(() -> dialog.getRows().stream().filter(row -> row.column.getName().equals(column))
                .findFirst().orElseThrow());
    }

    private static String error(ColumnMappingDialog dialog) {
        return FxTestSupport.call(() -> ((Label) dialog.getDialogPane().lookup("#mappingError")).getText());
    }

    @Test
    void mapsColumnsOfTables() {
        AtomicReference<Optional<Map<String, Map<String, Mapping>>>> result = new AtomicReference<>();
        // A mapping chosen before is shown again
        ColumnMappingDialog dialog = open(Map.of("ORDERS", Map.of("NOTE", new Mapping("REMARK", null, false))),
                result);
        assertEquals("REMARK", FxTestSupport.call(() -> row(dialog, "NOTE").targetName.get()));
        assertEquals("1 columns are mapped", FxTestSupport.call(() -> ((Label) dialog.getDialogPane().lookup(
                "#mappingStatus")).getText()));
        FxTestSupport.run(() -> {
            row(dialog, "NOTE").targetType.set("TEXT");
            row(dialog, "SECRET").imported.set(false);
            dialog.validate();
        });
        // The required id is always imported, its check box is disabled
        FxTestSupport.run(() -> {
            TableView<?> table = (TableView<?>) dialog.getDialogPane().lookup("#mappingColumnTable");
            table.applyCss();
            table.layout();
        });
        assertTrue(FxTestSupport.call(() -> dialog.getDialogPane().lookupAll(".check-box").stream().anyMatch(
                node -> node.isDisabled())));

        // Another table keeps the mappings of the first one
        ComboBox<?> tables = FxTestSupport.call(() -> (ComboBox<?>) dialog.getDialogPane().lookup(
                "#mappingTableCombo"));
        assertTrue(FxTestSupport.call(tables::isVisible));
        FxTestSupport.run(() -> dialog.setTable("CUSTOMERS"));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> dialog.getRows().size() == 1));
        FxTestSupport.run(() -> row(dialog, "NAME").targetName.set("FULL_NAME"));
        // A table which can not be read shows its error
        FxTestSupport.run(() -> dialog.setTable("BROKEN"));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> ((TableView<?>) dialog.getDialogPane().lookup(
                "#mappingColumnTable")).getPlaceholder() instanceof Label && ((Label) ((TableView<?>) dialog
                .getDialogPane().lookup("#mappingColumnTable")).getPlaceholder()).getText().contains("no access")));
        FxTestSupport.run(() -> dialog.setTable("ORDERS"));
        assertEquals("TEXT", FxTestSupport.call(() -> row(dialog, "NOTE").targetType.get()));

        FxTestSupport.run(() -> ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).fire());
        FxTestSupport.waitUntil(() -> result.get() != null);
        Map<String, Map<String, Mapping>> mappings = result.get().orElseThrow();
        assertEquals(new Mapping("REMARK", "TEXT", false), mappings.get("ORDERS").get("NOTE"));
        assertEquals(new Mapping(null, null, true), mappings.get("ORDERS").get("SECRET"));
        assertFalse(mappings.get("ORDERS").containsKey("ID"));
        assertEquals(new Mapping("FULL_NAME", null, false), mappings.get("CUSTOMERS").get("NAME"));

        // As mappings of an import
        Map<String, Map<String, ColumnMapping>> converted = ExportDialog.toColumnMappings(mappings);
        assertEquals(new ColumnMapping("REMARK", "TEXT", false), converted.get("ORDERS").get("NOTE"));
        assertEquals(ColumnMapping.skip(), converted.get("ORDERS").get("SECRET"));
        assertEquals("skipped", new Mapping(null, null, true).toString());
        assertEquals("A TEXT", new Mapping("A", "TEXT", false).toString());
        assertEquals(new Mapping("A", null, false).hashCode(), new Mapping(" A ", "", false).hashCode());
        assertFalse(new Mapping("A", null, false).equals("A"));
    }

    @Test
    void refusesMappingsWhichCanNotBeImported() {
        AtomicReference<Optional<Map<String, Map<String, Mapping>>>> result = new AtomicReference<>();
        ColumnMappingDialog dialog = open(Map.of(), result);
        Button ok = FxTestSupport.call(() -> (Button) dialog.getDialogPane().lookupButton(ButtonType.OK));
        // Two columns of the same name, in another case too
        FxTestSupport.run(() -> {
            row(dialog, "SECRET").targetName.set("note");
            dialog.validate();
        });
        assertTrue(error(dialog).contains("are both mapped to note"), error(dialog));
        assertTrue(FxTestSupport.call(ok::isDisabled));
        // A column of the same name as its source is no mapping
        FxTestSupport.run(() -> {
            row(dialog, "SECRET").targetName.set("SECRET");
            dialog.validate();
        });
        assertFalse(FxTestSupport.call(ok::isDisabled));
        assertTrue(FxTestSupport.call(() -> dialog.getMappings().isEmpty()));

        // No column of a table is imported
        FxTestSupport.run(() -> dialog.setTable("CUSTOMERS"));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> dialog.getRows().size() == 1));
        FxTestSupport.run(() -> {
            row(dialog, "NAME").imported.set(false);
            dialog.validate();
        });
        assertTrue(error(dialog).contains("No column of CUSTOMERS is imported"), error(dialog));

        // Reset shows the columns as they are
        FxTestSupport.run(() -> dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().get(0))
                .fireEvent(new ActionEvent()));
        assertTrue(FxTestSupport.call(() -> row(dialog, "NAME").imported.get()));
        assertEquals("All columns are imported as they are", FxTestSupport.call(() -> ((Label) dialog
                .getDialogPane().lookup("#mappingStatus")).getText()));

        // Cancel keeps nothing
        FxTestSupport.run(() -> ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).fire());
        FxTestSupport.waitUntil(() -> result.get() != null);
        assertTrue(result.get().isEmpty());
    }

    @Test
    void hidesChoiceOfOneTable() {
        ColumnMappingDialog dialog = FxTestSupport.call(() -> new ColumnMappingDialog(stage, List.of("CUSTOMERS"),
                ColumnMappingDialogTest::columns, Map.of("ORDERS", Map.of("NOTE", new Mapping("REMARK", null,
                        false)))));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> dialog.getRows().size() == 1));
        assertFalse(FxTestSupport.call(() -> dialog.getDialogPane().lookup("#mappingTableCombo").getParent()
                .isVisible()));
        assertEquals("CUSTOMERS", FxTestSupport.call(dialog::getTable));
        // Mappings of tables which were not opened are kept
        assertEquals(new Mapping("REMARK", null, false), FxTestSupport.call(dialog::getMappings).get("ORDERS")
                .get("NOTE"));
    }
}
