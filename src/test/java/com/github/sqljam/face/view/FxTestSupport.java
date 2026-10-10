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

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.testfx.util.WaitForAsyncUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.service.ConnectionStore;
import com.github.sqljam.face.service.ExampleDatabase;
import com.github.sqljam.face.service.SettingsStore;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

/**
 * @Description: FxTestSupport creates an isolated application context (temporary sqljam.properties and data source
 *               file, never ~/.sqljam) and helps to run code on the FX thread
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class FxTestSupport {

    public static final long TIMEOUT_SECONDS = 30;

    private FxTestSupport() {
    }

    private static final String STYLESHEET = "/com/github/sqljam/face/app.css";
    private static boolean styled;

    /**
     * Windows of tests look like the application: the default theme, and the stylesheet of the application on
     * windows other than dialogs (dialogs of the application have the theme only)
     */
    public static synchronized void applyAppStyle() {
        if (styled) {
            return;
        }
        styled = true;
        Runnable install = () -> {
            ThemeManager.apply(ThemeManager.DEFAULT_THEME);
            Window.getWindows().forEach(FxTestSupport::styleWindow);
            Window.getWindows().addListener((ListChangeListener<Window>) change -> {
                while (change.next()) {
                    change.getAddedSubList().forEach(FxTestSupport::styleWindow);
                }
            });
        };
        if (Platform.isFxApplicationThread()) {
            install.run();
        } else {
            Platform.runLater(install);
        }
    }

    private static void styleWindow(Window window) {
        styleScene(window.getScene());
        window.sceneProperty().addListener((obs, oldScene, scene) -> styleScene(scene));
    }

    private static void styleScene(Scene scene) {
        if (scene == null || scene.getRoot() instanceof DialogPane) {
            return;
        }
        String stylesheet = FxTestSupport.class.getResource(STYLESHEET).toExternalForm();
        if (!scene.getStylesheets().contains(stylesheet)) {
            scene.getStylesheets().add(stylesheet);
        }
    }

    public static AppContext createContext(File dir) {
        applyAppStyle();
        Config config = new Config(new File(dir, Config.FILE_NAME));
        Config.setInstance(config);
        AppContext context = new AppContext(new SettingsStore(config), new ProfileRegistry(new ConnectionStore(
                new File(dir, "connections.json"))), null);
        // The example database of tests is not created in the home directory of the user
        context.setExampleDatabase(new ExampleDatabase(true, new File(dir, "example")));
        return context;
    }

    public static <T> T call(Callable<T> callable) {
        try {
            return WaitForAsyncUtils.asyncFx(callable).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public static void run(Runnable runnable) {
        call(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Fires the button later, so that the caller is not blocked by modal dialogs opened by the button
     */
    public static void fireLater(Button button) {
        Platform.runLater(button::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Waits until the condition evaluated on the FX thread is true
     */
    public static void waitUntil(BooleanSupplier condition) {
        try {
            WaitForAsyncUtils.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS, () -> call(condition::getAsBoolean));
        } catch (TimeoutException e) {
            throw new AssertionError("Condition is not met in " + TIMEOUT_SECONDS + " seconds", e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Dialog panes of the showing windows
     */
    public static List<DialogPane> getDialogPanes() {
        return call(() -> Window.getWindows().stream().filter(Window::isShowing)
                .filter(window -> window.getScene() != null && window.getScene().getRoot() instanceof DialogPane)
                .map(window -> (DialogPane) window.getScene().getRoot()).collect(Collectors.toList()));
    }

    public static DialogPane waitForDialogPane() {
        waitUntil(() -> !Window.getWindows().stream().filter(Window::isShowing)
                .filter(window -> window.getScene() != null && window.getScene().getRoot() instanceof DialogPane)
                .collect(Collectors.toList()).isEmpty());
        return getDialogPanes().get(0);
    }

    /**
     * Closes the showing alert with the button type, e.g. OK of a confirmation
     */
    public static void closeDialog(ButtonType buttonType) {
        DialogPane pane = waitForDialogPane();
        Platform.runLater(() -> Optional.ofNullable((Button) pane.lookupButton(buttonType)).ifPresent(Button::fire));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Closes all showing dialogs, e.g. error alerts
     */
    public static void closeAllDialogs() {
        for (DialogPane pane : getDialogPanes()) {
            Platform.runLater(() -> pane.getScene().getWindow().hide());
        }
        WaitForAsyncUtils.waitForFxEvents();
    }
}
