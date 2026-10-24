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

import java.util.function.Function;

import com.github.sqljam.vdb.client.ChromaClient;
import com.github.sqljam.vdb.client.ElasticsearchClient;
import com.github.sqljam.vdb.client.MilvusClient;
import com.github.sqljam.vdb.client.OpenSearchClient;
import com.github.sqljam.vdb.client.PineconeClient;
import com.github.sqljam.vdb.client.QdrantClient;
import com.github.sqljam.vdb.client.WeaviateClient;

/**
 * @Description: VdbType is a vector database which SqlJam reads by its REST API. Vector databases are sources only,
 *               their collections are imported straight into a relational database with vectors, never the other
 *               way round, and they are not written into export packages. Supported versions:
 *               <ul>
 *               <li>Milvus 2.4 and later (REST API v2)</li>
 *               <li>Qdrant 1.10 and later (scroll, named and sparse vectors)</li>
 *               <li>Weaviate 1.24 and later (named vectors, cursor of objects)</li>
 *               <li>Pinecone, and Pinecone Local (API version 2025-01)</li>
 *               <li>Elasticsearch 8.0 and later (dense_vector, point in time)</li>
 *               <li>OpenSearch 2.4 and later (k-NN plugin, point in time)</li>
 *               <li>Chroma 1.0 and later (REST API v2, tenants and databases)</li>
 *               </ul>
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public enum VdbType {

    MILVUS("Milvus", 19530, false, true, true, MilvusClient::new),
    QDRANT("Qdrant", 6333, false, true, false, QdrantClient::new),
    WEAVIATE("Weaviate", 8080, false, true, false, WeaviateClient::new),
    PINECONE("Pinecone", 5080, false, true, false, PineconeClient::new),
    ELASTICSEARCH("Elasticsearch", 9200, false, true, true, ElasticsearchClient::new),
    OPENSEARCH("OpenSearch", 9200, true, false, true, OpenSearchClient::new),
    CHROMA("Chroma", 8000, false, true, false, ChromaClient::new);

    private final String displayName;
    private final int defaultPort;
    private final boolean secureByDefault;
    private final boolean apiKeySupported;
    private final boolean userSupported;
    private final Function<VdbSource, VdbClient> clientFactory;

    VdbType(String displayName, int defaultPort, boolean secureByDefault, boolean apiKeySupported,
            boolean userSupported, Function<VdbSource, VdbClient> clientFactory) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.secureByDefault = secureByDefault;
        this.apiKeySupported = apiKeySupported;
        this.userSupported = userSupported;
        this.clientFactory = clientFactory;
    }

    /**
     * Whether the database is authenticated by an API key (a token of Milvus, a bearer token of Weaviate and
     * Chroma, an API key of Qdrant, Pinecone and Elasticsearch)
     */
    public boolean isApiKeySupported() {
        return apiKeySupported;
    }

    /**
     * Whether the database is authenticated by a user and a password (Milvus, Elasticsearch, OpenSearch)
     */
    public boolean isUserSupported() {
        return userSupported;
    }

    /**
     * Whether the database has databases of its own (Milvus, Chroma)
     */
    public boolean isDatabaseSupported() {
        return this == MILVUS || this == CHROMA;
    }

    /**
     * Name of the parts of a collection which are read apart: partitions of Milvus, tenants of Weaviate,
     * namespaces of Pinecone, null for the databases without them
     */
    public String getPartitionName() {
        switch (this) {
            case MILVUS:
                return "Partition";
            case WEAVIATE:
                return "Tenant";
            case PINECONE:
                return "Namespace";
            default:
                return null;
        }
    }

    /**
     * Whether the database has tenants (Chroma)
     */
    public boolean isTenantSupported() {
        return this == CHROMA;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    /**
     * Whether the server listens on HTTPS by default, e.g. OpenSearch with its security plugin
     */
    public boolean isSecureByDefault() {
        return secureByDefault;
    }

    /**
     * Base url of a server, e.g. http://localhost:6333
     */
    public String getUrl(String hostname, int port) {
        return String.format("%s://%s:%d", secureByDefault ? "https" : "http", hostname,
                port > 0 ? port : defaultPort);
    }

    public VdbClient createClient(VdbSource source) {
        return clientFactory.apply(source);
    }
}
