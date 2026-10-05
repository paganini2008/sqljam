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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;

/**
 * @Description: ConnectionStore keeps connection profiles in the file configured by sqljam.connections.file
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ConnectionStore {

    private final JsonStore jsonStore;
    private final String fileName;
    private final List<ConnectionProfile> profiles = new ArrayList<>();

    public ConnectionStore() {
        this(Config.getInstance().getFile("sqljam.connections.file",
                new File(JsonStore.DEFAULT_DIRECTORY, "connections.json")));
    }

    public ConnectionStore(File file) {
        this.jsonStore = new JsonStore(file.getAbsoluteFile().getParentFile());
        this.fileName = file.getName();
        ConnectionProfile[] saved = jsonStore.read(fileName, ConnectionProfile[].class, new ConnectionProfile[0]);
        profiles.addAll(Arrays.asList(saved));
    }

    public synchronized List<ConnectionProfile> getProfiles() {
        return new ArrayList<>(profiles);
    }

    public synchronized Optional<ConnectionProfile> findProfile(String id) {
        return profiles.stream().filter(profile -> profile.getId().equals(id)).findFirst();
    }

    /**
     * Adds a new profile or replaces the profile with the same id
     */
    public synchronized void saveProfile(ConnectionProfile profile) throws IOException {
        profiles.removeIf(saved -> saved.getId().equals(profile.getId()));
        profiles.add(profile);
        jsonStore.write(fileName, profiles);
    }

    public synchronized void removeProfile(String id) throws IOException {
        profiles.removeIf(saved -> saved.getId().equals(id));
        jsonStore.write(fileName, profiles);
    }
}
