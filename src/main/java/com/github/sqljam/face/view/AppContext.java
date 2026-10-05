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

import java.io.IOException;

import com.github.sqljam.face.model.AppSettings;
import com.github.sqljam.face.service.SettingsStore;
import com.github.sqljam.face.service.TransferService;
import javafx.application.HostServices;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: AppContext holds the shared services of the user interface
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
@Getter
public class AppContext {

    private final SettingsStore settingsStore;
    private final ProfileRegistry profileRegistry;
    private final SessionManager sessionManager = new SessionManager();
    private final TransferService transferService = new TransferService();
    private final HostServices hostServices;

    public AppContext(SettingsStore settingsStore, ProfileRegistry profileRegistry, HostServices hostServices) {
        this.settingsStore = settingsStore;
        this.profileRegistry = profileRegistry;
        this.hostServices = hostServices;
    }

    public AppSettings getSettings() {
        return settingsStore.getSettings();
    }

    public void saveSettings() {
        try {
            settingsStore.save();
        } catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("Unable to save settings", e);
            }
        }
    }
}
