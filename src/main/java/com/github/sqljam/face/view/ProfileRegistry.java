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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.ConnectionStore;

/**
 * @Description: ProfileRegistry wraps the connection store and keeps passwords which are not remembered only in memory
 *               for the current session
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ProfileRegistry {

    private final ConnectionStore connectionStore;
    private final Map<String, String> sessionPasswords = new ConcurrentHashMap<>();

    public ProfileRegistry(ConnectionStore connectionStore) {
        this.connectionStore = connectionStore;
    }

    /**
     * Profiles with passwords of this session filled in
     */
    public List<ConnectionProfile> getProfiles() {
        return connectionStore.getProfiles().stream().map(this::withSessionPassword).collect(Collectors.toList());
    }

    public Optional<ConnectionProfile> findProfile(String id) {
        return connectionStore.findProfile(id).map(this::withSessionPassword);
    }

    private ConnectionProfile withSessionPassword(ConnectionProfile profile) {
        ConnectionProfile copy = profile.copy();
        String password = sessionPasswords.get(profile.getId());
        if (copy.getPassword() == null && password != null) {
            copy.setPassword(password);
        }
        return copy;
    }

    /**
     * Whether the password of the profile is saved in the store
     */
    public boolean isPasswordRemembered(String id) {
        return connectionStore.findProfile(id).map(profile -> profile.getPassword() != null).orElse(true);
    }

    /**
     * Saves the profile, the password is persisted only if remembered
     */
    public void saveProfile(ConnectionProfile profile, boolean passwordRemembered) throws IOException {
        ConnectionProfile saved = profile.copy();
        if (passwordRemembered) {
            sessionPasswords.remove(profile.getId());
        } else {
            saved.setPassword(null);
            if (profile.getPassword() != null) {
                sessionPasswords.put(profile.getId(), profile.getPassword());
            }
        }
        connectionStore.saveProfile(saved);
    }

    public void removeProfile(String id) throws IOException {
        sessionPasswords.remove(id);
        connectionStore.removeProfile(id);
    }
}
