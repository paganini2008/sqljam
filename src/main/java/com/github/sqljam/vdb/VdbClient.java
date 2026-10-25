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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: VdbClient reads a vector database by its REST API: its collections, the structure of a collection
 *               as a table, and the records of a collection page by page. Values of records are keyed by the names
 *               of the fields: vectors are text ([1,2.5,-3] dense, {1:1.5,3:2}/5 sparse), JSON fields and the
 *               payload are JSON text, scalars are Java values.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public interface VdbClient extends AutoCloseable {

    VdbType getType();

    /**
     * Version of the server, e.g. 1.19.2
     */
    String getVersion();

    /**
     * Databases of the server (Milvus, the tenant of Chroma), empty for servers without databases. A client of a
     * database is created by a source of that database, see {@link VdbSource#withDatabase}.
     */
    default List<String> getDatabases() {
        return List.of();
    }

    /**
     * Parts of a collection which are read apart (partitions of Milvus, tenants of Weaviate, namespaces of
     * Pinecone), empty for collections without them. A query reads some of them by {@link VdbQuery#getPartitions}.
     */
    default List<String> getPartitions(VdbCollection collection) {
        return List.of();
    }

    /**
     * Partitions of a collection by its name without describing it, e.g. for the connection tree
     */
    default List<String> listPartitions(String collection) {
        return List.of();
    }

    /**
     * Names of the collections which the credentials may read
     */
    List<String> getCollections();

    /**
     * Structure of a collection. Payloads without a schema (Qdrant, Pinecone) are read once without vectors to find
     * their fields and types.
     */
    VdbCollection describe(String collection);

    /**
     * Records of the query, -1 if they can not be counted without reading them
     */
    long count(VdbCollection collection, VdbQuery query);

    /**
     * Reads the records of the query page by page, a consumer which returns false stops reading
     */
    void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer);

    /**
     * Information of the server by sections, e.g. Server: version, url; a value which is not readable tells why
     */
    default Map<String, Map<String, String>> getOverview() {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> server = new LinkedHashMap<>();
        server.put("Type", getType().getDisplayName());
        server.put("Version", VdbInfo.read(() -> {
            String version = getVersion();
            return version == null || version.isBlank() ? "Not told by the REST API" : version;
        }));
        sections.put("Server", server);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("Databases", VdbInfo.read(() -> getType().isDatabaseSupported() ? String.join(", ", getDatabases())
                : "None, collections only"));
        data.put("Collections", VdbInfo.read(() -> {
            List<String> collections = getCollections();
            return collections.size() + (collections.isEmpty() ? "" : ": " + String.join(", ", collections));
        }));
        sections.put("Data", data);
        return sections;
    }

    /**
     * Health of the server by sections: whether it answers and how fast, the health endpoints of the database
     */
    default Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> connection = new LinkedHashMap<>();
        long start = System.nanoTime();
        String reachable = VdbInfo.read(() -> {
            getCollections();
            return "Yes";
        });
        connection.put("Reachable", reachable);
        connection.put("Response time", (System.nanoTime() - start) / 1_000_000 + " ms");
        sections.put("Connection", connection);
        return sections;
    }

    @FunctionalInterface
    interface PageConsumer {
        /**
         * @param records records of a page, by the names of the fields
         * @param last    whether it is the last page
         * @return whether the next page is read
         */
        boolean accept(List<Map<String, Object>> records, boolean last);
    }

    @Override
    default void close() {
    }
}
