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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.service.SettingsStore;
import javafx.scene.paint.Color;
import javafx.scene.control.TableView;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableColumn;
import javafx.scene.control.Label;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.PrimerDark;

/**
 * @Description: ThemeManagerTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ThemeManagerTest {

    @TempDir
    File dir;

    private AppContext context;
    private MainView mainView;

    @Start
    void start(Stage stage) {
        context = FxTestSupport.createContext(dir);
        mainView = new MainView(context);
        stage.setScene(new Scene(mainView, 1000, 700));
        stage.show();
    }

    @Test
    void listsAllThemes() {
        List<String> names = ThemeManager.getThemeNames();
        assertEquals(7, names.size());
        assertTrue(names.contains(ThemeManager.DEFAULT_THEME));
    }

    @Test
    void appliesTheme() {
        assertEquals("Nord Dark", FxTestSupport.call(() -> ThemeManager.apply("Nord Dark")));
        assertTrue(Application.getUserAgentStylesheet().startsWith("data:text/css;base64,"));
        assertTrue(stylesheet().contains(new NordDark().getUserAgentStylesheet()), stylesheet());
    }

    private static String stylesheet() {
        String url = Application.getUserAgentStylesheet();
        return new String(Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1)),
                StandardCharsets.UTF_8);
    }

    /**
     * Text and selected rows as the themes render them: text of dark themes is never gray, text of light themes
     * has clear contrast, selected rows are blue
     */
    @Test
    void rendersTextAndSelection() {
        for (String name : ThemeManager.getThemeNames()) {
            FxTestSupport.run(() -> ThemeManager.apply(name));
            boolean dark = !name.contains("Light");
            Color[] colors = FxTestSupport.call(() -> {
                Label text = new Label("text");
                Label muted = new Label("muted");
                muted.getStyleClass().add("muted");
                TableView<String> table = new TableView<>();
                TableColumn<String, String> column = new TableColumn<>("c");
                column.setCellValueFactory(features -> new ReadOnlyStringWrapper(features.getValue()));
                table.getColumns().add(column);
                table.getItems().setAll("a", "b");
                VBox box = new VBox(text, muted, table);
                Scene scene = new Scene(box, 300, 300);
                scene.getStylesheets().add(ThemeManagerTest.class.getResource("/com/github/sqljam/face/app.css")
                        .toExternalForm());
                table.getSelectionModel().select(0);
                box.applyCss();
                box.layout();
                TableRow<?> row = (TableRow<?>) table.lookupAll(".table-row-cell").stream().filter(node ->
                        ((TableRow<?>) node).getIndex() == 0).findFirst().orElseThrow();
                Color background = (Color) row.getBackground().getFills().get(row.getBackground().getFills().size()
                        - 1).getFill();
                return new Color[]{(Color) text.getTextFill(), (Color) muted.getTextFill(), background};
            });
            for (int i = 0; i < 2; i++) {
                Color color = colors[i];
                if (dark) {
                    // White or near white, never gray
                    assertTrue(color.getBrightness() > 0.93 && color.getSaturation() < 0.1, name + " " + color);
                } else {
                    assertTrue(color.getBrightness() < 0.26, name + " " + color);
                }
            }
            // Selected rows are blue
            Color selection = colors[2];
            assertTrue(selection.getBlue() > selection.getRed() + 0.1, name + " selection " + selection);
        }
        FxTestSupport.run(() -> ThemeManager.apply(ThemeManager.DEFAULT_THEME));
    }

    @Test
    void unknownThemeFallsBackToDefault() {
        assertEquals("Primer Dark", FxTestSupport.call(() -> ThemeManager.apply("No Such Theme")));
        assertTrue(stylesheet().contains(new PrimerDark().getUserAgentStylesheet()), stylesheet());
        assertEquals("Primer Dark", FxTestSupport.call(() -> ThemeManager.apply(null)));
        assertEquals("Primer Dark", FxTestSupport.call(() -> ThemeManager.apply("")));
    }

    @Test
    void switchingThemePersistsToConfig() throws Exception {
        RadioMenuItem item = FxTestSupport.call(() -> findThemeItem("Cupertino Light"));
        FxTestSupport.run(item::fire);
        assertEquals("Cupertino Light", context.getSettings().getTheme());
        File file = new File(dir, Config.FILE_NAME);
        assertTrue(Files.readString(file.toPath()).contains("sqljam.ui.theme=Cupertino Light"));
        // A new settings store reads the persisted theme
        assertEquals("Cupertino Light", new SettingsStore(new Config(file)).getSettings().getTheme());
        FxTestSupport.run(() -> ThemeManager.apply(ThemeManager.DEFAULT_THEME));
    }

    @Test
    void unknownThemeInConfigFallsBack() throws Exception {
        File file = new File(dir, Config.FILE_NAME);
        Files.writeString(file.toPath(), "sqljam.ui.theme=Solarized\n");
        SettingsStore settingsStore = new SettingsStore(new Config(file));
        assertEquals("Primer Dark", FxTestSupport.call(() -> ThemeManager.apply(
                settingsStore.getSettings().getTheme())));
    }

    private RadioMenuItem findThemeItem(String name) {
        MenuBar menuBar = (MenuBar) ((VBox) mainView.getTop()).getChildren().get(0);
        for (Menu menu : menuBar.getMenus()) {
            for (MenuItem item : menu.getItems()) {
                if (item instanceof Menu) {
                    for (MenuItem themeItem : ((Menu) item).getItems()) {
                        if (name.equals(themeItem.getText())) {
                            return (RadioMenuItem) themeItem;
                        }
                    }
                }
            }
        }
        throw new AssertionError("Theme menu item not found: " + name);
    }
}
