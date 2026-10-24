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

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbType;

/**
 * @Description: VdbProfile is a saved vector database: its type, the url of its REST API, its credentials (an API
 *               key, or a user and a password), the database and the tenant
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
@ToString(exclude = {"apiKey", "password"})
public class VdbProfile {

    private String id = UUID.randomUUID().toString();
    private String name;
    private VdbType type;
    private String url;
    private String apiKey;
    private String username;
    private String password;
    private String database;
    private String tenant;
    private boolean certificateTrusted;
    private int timeoutSeconds = 60;

    public VdbSource toSource() {
        VdbSource source = new VdbSource(type, url);
        source.setApiKey(apiKey);
        source.setUsername(username);
        source.setPassword(password);
        source.setDatabase(database);
        source.setTenant(tenant);
        source.setCertificateTrusted(certificateTrusted);
        source.setTimeoutSeconds(timeoutSeconds);
        return source;
    }

    public VdbProfile copy() {
        VdbProfile copy = new VdbProfile();
        copy.setId(id);
        copy.setName(name);
        copy.setType(type);
        copy.setUrl(url);
        copy.setApiKey(apiKey);
        copy.setUsername(username);
        copy.setPassword(password);
        copy.setDatabase(database);
        copy.setTenant(tenant);
        copy.setCertificateTrusted(certificateTrusted);
        copy.setTimeoutSeconds(timeoutSeconds);
        return copy;
    }

    /**
     * Whether the API key or the password is saved
     */
    public boolean hasSecret() {
        return apiKey != null || password != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VdbProfile && id.equals(((VdbProfile) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
