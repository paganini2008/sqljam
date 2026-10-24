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
package com.github.sqljam.face.vdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.github.sqljam.config.Config;
import com.github.sqljam.face.service.JsonStore;

/**
 * @Description: VdbProfileStore keeps the vector databases in their own file (vdb-connections.json next to the
 *               connections), apart from the data sources of the jdbc line. Secrets which are not remembered are kept
 *               for this session only.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbProfileStore {

    public static final String FILE_NAME = "vdb-connections.json";

    private final JsonStore jsonStore;
    private final String fileName;
    private final List<VdbProfile> profiles = new ArrayList<>();
    private final Map<String, VdbProfile> sessionSecrets = new ConcurrentHashMap<>();

    private static VdbProfileStore instance;
    private static Config instanceConfig;

    /**
     * The store of the configuration of the application, shared by the menu, the data source form, the connection
     * tree and the dialogs
     */
    public static synchronized VdbProfileStore getInstance() {
        Config config = Config.getInstance();
        if (instance == null || instanceConfig != config) {
            instance = new VdbProfileStore();
            instanceConfig = config;
        }
        return instance;
    }

    public VdbProfileStore() {
        this(Config.getInstance().getFile("sqljam.vdb.connections.file", new File(Config.getInstance().getFile(
                "sqljam.connections.file", new File(JsonStore.DEFAULT_DIRECTORY, "connections.json"))
                .getAbsoluteFile().getParentFile(), FILE_NAME)));
    }

    public VdbProfileStore(File file) {
        this.jsonStore = new JsonStore(file.getAbsoluteFile().getParentFile());
        this.fileName = file.getName();
        profiles.addAll(Arrays.asList(jsonStore.read(fileName, VdbProfile[].class, new VdbProfile[0])));
    }

    /**
     * Profiles with the secrets of this session filled in
     */
    public synchronized List<VdbProfile> getProfiles() {
        return profiles.stream().map(this::withSessionSecret).collect(Collectors.toList());
    }

    private VdbProfile withSessionSecret(VdbProfile profile) {
        VdbProfile copy = profile.copy();
        VdbProfile secret = sessionSecrets.get(profile.getId());
        if (!copy.hasSecret() && secret != null) {
            copy.setApiKey(secret.getApiKey());
            copy.setPassword(secret.getPassword());
        }
        return copy;
    }

    public synchronized boolean isSecretRemembered(String id) {
        return profiles.stream().filter(profile -> profile.getId().equals(id)).findFirst().map(VdbProfile::hasSecret)
                .orElse(true);
    }

    /**
     * Adds or replaces a profile, its API key and password are saved only if remembered
     */
    public synchronized void saveProfile(VdbProfile profile, boolean secretRemembered) throws IOException {
        VdbProfile saved = profile.copy();
        if (secretRemembered) {
            sessionSecrets.remove(profile.getId());
        } else {
            saved.setApiKey(null);
            saved.setPassword(null);
            sessionSecrets.put(profile.getId(), profile.copy());
        }
        profiles.removeIf(existing -> existing.getId().equals(profile.getId()));
        profiles.add(saved);
        jsonStore.write(fileName, profiles);
    }

    public synchronized void removeProfile(String id) throws IOException {
        sessionSecrets.remove(id);
        profiles.removeIf(existing -> existing.getId().equals(id));
        jsonStore.write(fileName, profiles);
    }
}
