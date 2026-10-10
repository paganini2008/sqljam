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
        assertEquals(new NordDark().getUserAgentStylesheet(), Application.getUserAgentStylesheet());
    }

    @Test
    void unknownThemeFallsBackToDefault() {
        assertEquals("Primer Dark", FxTestSupport.call(() -> ThemeManager.apply("No Such Theme")));
        assertEquals(new PrimerDark().getUserAgentStylesheet(), Application.getUserAgentStylesheet());
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
