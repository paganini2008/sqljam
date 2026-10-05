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

import java.util.Locale;

import com.github.sqljam.config.Config;
import javafx.application.Application;

/**
 * @Description: Launcher starts the JavaFX application from a class which does not extend Application, so that the
 *               application runs from the classpath (shaded jar)
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class Launcher {

    private static final String PRELOADER_PROPERTY = "javafx.preloader";

    private Launcher() {
    }

    public static void main(String[] args) {
        // Started by double click: start again with the JVM options of sqljam.vmoptions
        if (JvmOptions.relaunchIfNeeded(args)) {
            return;
        }
        // The user interface is in English only, including texts of JavaFX dialogs
        Locale.setDefault(Locale.ENGLISH);
        Config config = Config.getInstance();
        Banner.print(config, System.out);
        // The splash screen is a JavaFX preloader, shown while the application is loading
        if (config.getBoolean(SplashPreloader.ENABLED_KEY, true)
                && System.getProperty(PRELOADER_PROPERTY) == null) {
            System.setProperty(PRELOADER_PROPERTY, SplashPreloader.class.getName());
        }
        Application.launch(SqlJamApplication.class, args);
    }
}
