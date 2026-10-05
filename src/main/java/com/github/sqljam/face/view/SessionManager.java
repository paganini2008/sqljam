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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.DatabaseSession;

/**
 * @Description: SessionManager keeps one browsing session per connection profile
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SessionManager {

    private final Map<String, DatabaseSession> sessions = new ConcurrentHashMap<>();

    public DatabaseSession getSession(ConnectionProfile profile) {
        return sessions.computeIfAbsent(profile.getId(), id -> new DatabaseSession(profile));
    }

    /**
     * Closes the session, e.g. after the profile is edited or deleted
     */
    public void closeSession(String profileId) {
        DatabaseSession session = sessions.remove(profileId);
        if (session != null) {
            TaskRunner.execute(session::close);
        }
    }

    public void closeAll() {
        sessions.values().forEach(DatabaseSession::close);
        sessions.clear();
    }
}
