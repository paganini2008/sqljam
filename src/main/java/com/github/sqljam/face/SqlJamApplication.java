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

import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.AppSettings;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.ConnectionStore;
import com.github.sqljam.face.service.SettingsStore;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.Branding;
import com.github.sqljam.face.view.LoginView;
import com.github.sqljam.face.view.MainView;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.face.view.ProfileRegistry;
import com.github.sqljam.face.view.TaskRunner;
import com.github.sqljam.face.view.ThemeManager;
import com.github.sqljam.impexp.DbType;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SqlJamApplication is the JavaFX application: database connection login page and then the main window.
 *               Configuration, data sources and JDBC drivers are loaded in init() while the splash screen
 *               ({@link SplashPreloader}) shows the progress.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class SqlJamApplication extends Application {

    private static final String STYLESHEET = "/com/github/sqljam/face/app.css";

    private AppContext context;
    private Stage stage;
    private SettingsStore settingsStore;
    private ProfileRegistry profileRegistry;

    /**
     * Loads everything which does not need the JavaFX thread, the splash screen shows each step
     */
    @Override
    public void init() {
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            if (log.isErrorEnabled()) {
                log.error("Uncaught exception in thread {}", thread.getName(), e);
            }
        });
        notifyPreloader(new SplashPreloader.Status(Messages.get("splash.loadingConfiguration"), 0.15));
        Config.getInstance();
        settingsStore = new SettingsStore();

        notifyPreloader(new SplashPreloader.Status(Messages.get("splash.loadingDataSources"), 0.35));
        profileRegistry = new ProfileRegistry(new ConnectionStore());

        // Loading JDBC drivers takes a while, it is done here instead of at the first connection
        DbType[] dbTypes = DbType.values();
        for (int i = 0; i < dbTypes.length; i++) {
            notifyPreloader(new SplashPreloader.Status(
                    Messages.format("splash.loadingDriver", dbTypes[i].getDisplayName()),
                    0.4 + 0.4 * i / dbTypes.length));
            try {
                Class.forName(dbTypes[i].getDriverClassName());
            } catch (ClassNotFoundException | LinkageError e) {
                if (log.isWarnEnabled()) {
                    log.warn("JDBC driver is not available: {}", dbTypes[i].getDriverClassName());
                }
            }
        }

        notifyPreloader(new SplashPreloader.Status(Messages.get("splash.preparingUi"), 0.85));
        Branding.getLogo();
        Branding.getIcons();
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        context = new AppContext(settingsStore, profileRegistry, getHostServices());
        AppSettings settings = context.getSettings();
        settings.setTheme(ThemeManager.apply(settings.getTheme()));

        stage.setTitle(Messages.get("app.title"));
        Branding.applyIcons(stage);
        showConnectionLogin();
        if (SplashPreloader.isActive()) {
            notifyPreloader(new SplashPreloader.Status(Messages.get("splash.ready"), 1));
            notifyPreloader(new SplashPreloader.Ready(stage::show));
        } else {
            stage.show();
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
