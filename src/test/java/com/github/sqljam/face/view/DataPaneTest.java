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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.DatabaseSession;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: DataPaneTest verifies paging of table rows: empty table, one page, several pages, page size, NULL,
 *               long and binary values
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class DataPaneTest {

    @TempDir
    File dir;

    private DatabaseSession session;
    private StackPane root;

    @Start
    void start(Stage stage) throws Exception {
        FxTestSupport.createContext(dir);
        ConnectionProfile profile = UiDatabase.create(dir, "ui");
        session = new DatabaseSession(profile);
        root = new StackPane();
        stage.setScene(new Scene(root, 1000, 600));
        stage.show();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private DataPane show(String table, int pageSize) {
        DataPane pane = FxTestSupport.call(() -> {
            DataPane dataPane = new DataPane(session, UiDatabase.CATALOG, UiDatabase.SCHEMA, table, pageSize);
            root.getChildren().setAll(dataPane);
            dataPane.load(1);
            return dataPane;
        });
        waitLoaded(pane);
        return pane;
    }

    private static void waitLoaded(DataPane pane) {
        FxTestSupport.waitUntil(() -> !((Button) pane.lookup("#refreshButton")).isDisabled());
    }

    private static boolean disabled(DataPane pane, String id) {
        return FxTestSupport.call(() -> pane.lookup("#" + id).isDisabled());
    }

    private static String text(DataPane pane, String id) {
        return FxTestSupport.call(() -> ((Label) pane.lookup("#" + id)).getText());
    }

    @SuppressWarnings("unchecked")
    private static TableView<List<String>> table(DataPane pane) {
        return (TableView<List<String>>) pane.lookup("#dataTable");
    }

    private static void click(DataPane pane, String id) {
        FxTestSupport.run(() -> ((Button) pane.lookup("#" + id)).fire());
        waitLoaded(pane);
    }

    @Test
    void showsEmptyTable() {
        DataPane pane = show("T_EMPTY", 100);
        assertEquals("Page 1 / 1", text(pane, "pageLabel"));
        assertEquals("0 rows", text(pane, "totalLabel"));
        assertEquals(0, FxTestSupport.call(() -> table(pane).getItems().size()));
        assertEquals(2, FxTestSupport.call(() -> table(pane).getColumns().size()));
        for (String id : new String[]{"firstButton", "previousButton", "nextButton", "lastButton"}) {
            assertTrue(disabled(pane, id), id);
        }
        assertEquals("No rows", FxTestSupport.call(() -> ((Label) table(pane).getPlaceholder()).getText()));
    }

    @Test
    void showsExactlyOnePage() {
        DataPane pane = show("T_ONE", 5);
        assertEquals("Page 1 / 1", text(pane, "pageLabel"));
        assertEquals(5, FxTestSupport.call(() -> table(pane).getItems().size()));
        assertTrue(disabled(pane, "nextButton"));
        assertTrue(disabled(pane, "lastButton"));
    }

    @Test
    void pagesThroughRows() {
        DataPane pane = show("T_PAGED", 100);
        assertEquals("Page 1 / 3", text(pane, "pageLabel"));
        assertEquals("250 rows", text(pane, "totalLabel"));
        assertTrue(disabled(pane, "firstButton"));
        assertTrue(disabled(pane, "previousButton"));
        assertFalse(disabled(pane, "nextButton"));
        assertFalse(disabled(pane, "lastButton"));

        click(pane, "nextButton");
        assertEquals("Page 2 / 3", text(pane, "pageLabel"));
        assertEquals("101", FxTestSupport.call(() -> table(pane).getItems().get(0).get(0)));
        assertFalse(disabled(pane, "previousButton"));

        click(pane, "lastButton");
        assertEquals("Page 3 / 3", text(pane, "pageLabel"));
        assertEquals(50, FxTestSupport.call(() -> table(pane).getItems().size()));
        assertTrue(disabled(pane, "nextButton"));
        assertTrue(disabled(pane, "lastButton"));

        click(pane, "previousButton");
        assertEquals("Page 2 / 3", text(pane, "pageLabel"));
        click(pane, "firstButton");
        assertEquals("Page 1 / 3", text(pane, "pageLabel"));
        click(pane, "refreshButton");
        assertEquals("Page 1 / 3", text(pane, "pageLabel"));
    }

    @Test
    void pageSizeChangeResetsToFirstPage() {
        DataPane pane = show("T_PAGED", 100);
        click(pane, "lastButton");
        @SuppressWarnings("unchecked")
        ComboBox<Integer> pageSizeCombo = (ComboBox<Integer>) pane.lookup("#pageSizeCombo");
        FxTestSupport.run(() -> pageSizeCombo.setValue(50));
        waitLoaded(pane);
        FxTestSupport.waitUntil(() -> "Page 1 / 5".equals(((Label) pane.lookup("#pageLabel")).getText()));
        assertEquals(50, FxTestSupport.call(() -> table(pane).getItems().size()));
    }

    @Test
    void unsupportedPageSizeFallsBackTo200() {
        DataPane pane = show("T_PAGED", 37);
        assertEquals("Page 1 / 2", text(pane, "pageLabel"));
    }

    @Test
    void rendersNullLongAndBinaryValues() {
        DataPane pane = show("T_PAGED", 100);
        List<List<String>> rows = FxTestSupport.call(() -> table(pane).getItems());
        // ID, NAME, NOTE, DATA, CREATED
        assertNull(rows.get(1).get(1));
        assertEquals(1003, rows.get(0).get(2).length());
        assertTrue(rows.get(0).get(2).endsWith("..."));
        assertTrue(rows.get(2).get(3).startsWith("(40 bytes) 0x00000000"), rows.get(2).get(3));
        assertTrue(rows.get(2).get(3).endsWith("..."));
        FxTestSupport.waitUntil(() -> !table(pane).lookupAll(".null-cell").isEmpty());
        assertEquals("NULL", FxTestSupport.call(() -> ((Labeled) table(pane)
                .lookupAll(".null-cell").iterator().next()).getText()));
    }

    @Test
    void formatsCellText() {
        assertEquals("2024-01-01 10:00", DataPane.format("2024-01-01T10:00"));
        assertEquals("2024-01-01 10:00:00.123", DataPane.format("2024-01-01T10:00:00.123"));
        assertEquals("a b c", DataPane.format("a\nb\rc"));
        assertEquals("T10", DataPane.format("T10"));
    }

    @Test
    void showsErrorOfMissingTable() {
        DataPane pane = FxTestSupport.call(() -> {
            DataPane dataPane = new DataPane(session, UiDatabase.CATALOG, UiDatabase.SCHEMA, "NO_SUCH_TABLE", 100);
            root.getChildren().setAll(dataPane);
            Platform.runLater(() -> dataPane.load(1));
            return dataPane;
        });
        FxTestSupport.waitForDialogPane();
        FxTestSupport.closeAllDialogs();
        waitLoaded(pane);
        assertTrue(FxTestSupport.call(() -> ((Label) table(pane).getPlaceholder()).getText()).startsWith("Error:"));
    }
}
