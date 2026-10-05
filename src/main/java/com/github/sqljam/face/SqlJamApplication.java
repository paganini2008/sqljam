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
package com.github.sqljam.face;

import com.github.sqljam.face.model.AppSettings;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.ConnectionStore;
import com.github.sqljam.face.service.SettingsStore;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.LoginView;
import com.github.sqljam.face.view.MainView;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.face.view.ProfileRegistry;
import com.github.sqljam.face.view.TaskRunner;
import com.github.sqljam.face.view.ThemeManager;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SqlJamApplication is the JavaFX application: database connection login page and then the main window
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class SqlJamApplication extends Application {

    private static final String STYLESHEET = "/com/github/sqljam/face/app.css";

    private AppContext context;
    private Stage stage;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            if (log.isErrorEnabled()) {
                log.error("Uncaught exception in thread {}", thread.getName(), e);
            }
        });
        SettingsStore settingsStore = new SettingsStore();
        context = new AppContext(settingsStore, new ProfileRegistry(new ConnectionStore()), getHostServices());
        AppSettings settings = context.getSettings();
        settings.setTheme(ThemeManager.apply(settings.getTheme()));

        stage.setTitle(Messages.get("app.title"));
        Image icon = loadIcon();
        if (icon != null) {
            stage.getIcons().add(icon);
        }
        showConnectionLogin();
        stage.show();
    }

    private static Image loadIcon() {
        try {
            java.io.InputStream in = SqlJamApplication.class.getResourceAsStream("/com/github/sqljam/face/icon.png");
            return in != null ? new Image(in) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void showPage(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource(STYLESHEET).toExternalForm());
        stage.setScene(scene);
        stage.centerOnScreen();
    }

    private void showConnectionLogin() {
        LoginView loginView = new LoginView(context, this::showMainView);
        showPage(loginView, 860, 620);
        stage.setTitle(Messages.get("app.title"));
    }

    private void showMainView(ConnectionProfile profile) {
        AppSettings settings = context.getSettings();
        MainView mainView = new MainView(context);
        showPage(mainView, settings.getWindowWidth(), settings.getWindowHeight());
        stage.setOnCloseRequest(event -> {
            settings.setWindowWidth(stage.getWidth());
            settings.setWindowHeight(stage.getHeight());
            context.saveSettings();
        });
        mainView.selectConnection(profile);
    }

    @Override
    public void stop() {
        if (context != null) {
            context.getSessionManager().closeAll();
        }
        TaskRunner.shutdown();
    }
}
