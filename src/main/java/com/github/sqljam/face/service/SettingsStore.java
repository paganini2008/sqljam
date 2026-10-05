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
package com.github.sqljam.face.service;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.AppSettings;

/**
 * @Description: SettingsStore maps user preferences to sqljam.properties
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SettingsStore {

    private final Config config;
    private final AppSettings settings = new AppSettings();

    public SettingsStore() {
        this(Config.getInstance());
    }

    public SettingsStore(Config config) {
        this.config = config;
        settings.setTheme(config.getString("sqljam.ui.theme", AppSettings.DEFAULT_THEME));
        settings.setWindowWidth(config.getDouble("sqljam.ui.window.width", settings.getWindowWidth()));
        settings.setWindowHeight(config.getDouble("sqljam.ui.window.height", settings.getWindowHeight()));
        settings.setDataPageSize(config.getInt("sqljam.ui.data.page-size", settings.getDataPageSize()));
        settings.setTransferPageSize(config.getInt("sqljam.export.page-size", settings.getTransferPageSize()));
        settings.setLobPageSize(config.getInt("sqljam.export.lob-page-size", settings.getLobPageSize()));
        settings.setMaxDataFileSize(config.getLong("sqljam.export.max-file-size", settings.getMaxDataFileSize()));
        settings.setLastExportDirectory(StringUtils.trimToNull(config.getString("sqljam.export.last-directory",
                null)));
        settings.setLastImportDirectory(StringUtils.trimToNull(config.getString("sqljam.import.last-directory",
                null)));
    }

    public AppSettings getSettings() {
        return settings;
    }

    public Config getConfig() {
        return config;
    }

    public synchronized void save() throws IOException {
        config.set("sqljam.ui.theme", settings.getTheme());
        config.set("sqljam.ui.window.width", (long) settings.getWindowWidth());
        config.set("sqljam.ui.window.height", (long) settings.getWindowHeight());
        config.set("sqljam.ui.data.page-size", settings.getDataPageSize());
        config.set("sqljam.export.page-size", settings.getTransferPageSize());
        config.set("sqljam.export.max-file-size", settings.getMaxDataFileSize());
        config.set("sqljam.export.last-directory", settings.getLastExportDirectory());
        config.set("sqljam.import.last-directory", settings.getLastImportDirectory());
        config.save();
    }
}
