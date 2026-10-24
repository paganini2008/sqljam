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
package com.github.sqljam.vdb;

import org.apache.commons.lang3.StringUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: VdbSource is the connection of a vector database: its REST url and its credentials, an API key or a
 *               token (Qdrant api-key, Weaviate and Milvus bearer tokens, Pinecone Api-Key) or a user and a password
 *               (Elasticsearch, OpenSearch). Certificates of servers may be trusted without verification, e.g. the
 *               self-signed certificate of a development OpenSearch.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
@ToString(exclude = {"apiKey", "password"})
public class VdbSource {

    private VdbType type;
    /**
     * Base url of the REST API, e.g. http://localhost:6333
     */
    private String url;
    private String apiKey;
    private String username;
    private String password;
    /**
     * Database of Milvus and Chroma, null for the default one
     */
    private String database;
    /**
     * Tenant of Chroma, null for the default one
     */
    private String tenant;
    private boolean certificateTrusted;
    private int timeoutSeconds = 60;

    /**
     * Whether an API key or a user is given, blank texts are none
     */
    public boolean hasCredentials() {
        return StringUtils.isNotBlank(apiKey) || StringUtils.isNotBlank(username);
    }

    /**
     * The source of another database of the same server
     */
    public VdbSource withDatabase(String otherDatabase) {
        VdbSource copy = new VdbSource(type, url);
        copy.setApiKey(apiKey);
        copy.setUsername(username);
        copy.setPassword(password);
        copy.setDatabase(otherDatabase);
        copy.setTenant(tenant);
        copy.setCertificateTrusted(certificateTrusted);
        copy.setTimeoutSeconds(timeoutSeconds);
        return copy;
    }

    public VdbSource() {
    }

    public VdbSource(VdbType type, String url) {
        this.type = type;
        this.url = url;
    }
}
