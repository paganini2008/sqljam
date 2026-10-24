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
package com.github.sqljam.vdb.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.vdb.PayloadSchema;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbInfo;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbValues;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: ChromaClient reads Chroma 1.0 and later by the REST API v2 with a token, in a tenant and a database
 *               (default_tenant and default_database by default). A collection is a table of the id, the embedding
 *               of its dimension and HNSW space (cosine, l2, ip), the document and the fields of the metadata.
 *               Records are read by offsets, filters are where filters of Chroma, searches are queries.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ChromaClient extends AbstractVdbClient {

    static final String DEFAULT_TENANT = "default_tenant";
    static final String DEFAULT_DATABASE = "default_database";
    static final String EMBEDDING = "embedding";
    static final String DOCUMENT = "document";
    static final String ID = "collectionId";
    private static final int SCAN_PAGE_SIZE = 1000;
    /**
     * Collections of a page of the list
     */
    private static final int LIST_PAGE_SIZE = 100;

    private final String basePath;

    public ChromaClient(VdbSource source) {
        super(source);
        if (StringUtils.isNotBlank(source.getApiKey())) {
            rest.header("Authorization", "Bearer " + source.getApiKey());
        }
        basePath = "/api/v2/tenants/" + encode(StringUtils.defaultIfBlank(source.getTenant(), DEFAULT_TENANT))
                + "/databases/" + encode(StringUtils.defaultIfBlank(source.getDatabase(), DEFAULT_DATABASE));
    }

    /**
     * Version of the API, Chroma tells no version of its server
     */
    @Override
    public String getVersion() {
        return rest.get("/api/v2/version").asText();
    }

    /**
     * Heartbeat and the limits of the server, the tenant and databases of the credentials
     */
    @Override
    public Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = super.getHealth();
        Map<String, String> health = section(sections, "Chroma");
        health.put("Heartbeat", VdbInfo.read(() -> rest.get("/api/v2/heartbeat").elements().hasNext()
                ? "Yes" : "No"));
        health.put("Max batch size", VdbInfo.read(() -> text(rest.get("/api/v2/pre-flight-checks"),
                "max_batch_size")));
        health.put("Identity", VdbInfo.read(() -> {
            JsonNode identity = rest.get("/api/v2/auth/identity");
            return "tenant " + text(identity, "tenant") + ", databases " + identity.path("databases");
        }));
        return sections;
    }

    /**
     * Databases of the tenant
     */
    @Override
    public List<String> getDatabases() {
        List<String> names = new ArrayList<>();
        rest.get("/api/v2/tenants/" + encode(StringUtils.defaultIfBlank(source.getTenant(), DEFAULT_TENANT))
                + "/databases").forEach(database -> names.add(text(database, "name")));
        return names;
    }

    @Override
    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        for (int offset = 0; ; offset += LIST_PAGE_SIZE) {
            JsonNode page = rest.get(basePath + "/collections?limit=" + LIST_PAGE_SIZE + "&offset=" + offset);
            page.forEach(node -> names.add(text(node, "name")));
            if (page.size() < LIST_PAGE_SIZE) {
                return names;
            }
        }
    }

    @Override
    public VdbCollection describe(String name) {
        JsonNode node = rest.get(basePath + "/collections/" + encode(name));
        VdbCollection collection = new VdbCollection(name);
        String id = required(text(node, "id"), "Id of the collection " + name);
        collection.getProperties().put(ID, id);
        collection.setRemarks(StringUtils.trimToNull(text(node.path("metadata"), "description")));
        VdbField idField = VdbField.varchar("id", 512);
        idField.setPrimaryKey(true);
        idField.setNullable(false);
        collection.addField(idField);

        Scan scan = scan(id);
        int dimensions = node.path("dimension").asInt(0) > 0 ? node.path("dimension").asInt() : scan.dimensions;
        VdbField embedding = VdbField.vector(EMBEDDING, VectorType.dense(VectorType.Element.FLOAT32, dimensions));
        embedding.setVectorIndex(toVectorIndex(node.path("configuration_json")));
        embedding.setNullable(scan.missingEmbeddings);
        collection.addField(embedding);
        VdbField document = new VdbField(DOCUMENT, VdbField.Kind.TEXT);
        document.setNullable(true);
        collection.addField(document);
        collection.getFields().addAll(scan.metadata.getFields(collection));
        addPayloadField(collection);
        collection.setCount(scan.records);
        return collection;
    }

    /**
     * HNSW index of the configuration, or of SPANN of Chroma Cloud
     */
    static VectorIndex toVectorIndex(JsonNode configuration) {
        JsonNode hnsw = configuration.path("hnsw");
        JsonNode spann = configuration.path("spann");
        String space = StringUtils.defaultIfBlank(text(hnsw, "space"), text(spann, "space"));
        VectorIndex.Distance distance = toDistance(StringUtils.defaultIfBlank(space, "l2"));
        if (distance == null) {
            return null;
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (hnsw.path("max_neighbors").asInt(0) > 0) {
            options.put("m", hnsw.path("max_neighbors").asText());
        }
        if (hnsw.path("ef_construction").asInt(0) > 0) {
            options.put("ef_construction", hnsw.path("ef_construction").asText());
        }
        return new VectorIndex(VectorIndex.Kind.HNSW, distance, options);
    }

    static VectorIndex.Distance toDistance(String space) {
        switch (StringUtils.defaultString(space).toLowerCase(Locale.ENGLISH)) {
            case "cosine":
                return VectorIndex.Distance.COSINE;
            case "l2":
                return VectorIndex.Distance.EUCLIDEAN;
            case "ip":
                return VectorIndex.Distance.DOT;
            default:
                return null;
        }
    }

    /**
     * What the records tell of the collection: the types of the metadata fields, the dimensions of the embeddings,
     * the number of records
     */
    static final class Scan {
        final PayloadSchema metadata = new PayloadSchema();
        int dimensions;
        boolean missingEmbeddings;
        long records;
    }

    private Scan scan(String id) {
        Scan scan = new Scan();
        for (int offset = 0; ; offset += SCAN_PAGE_SIZE) {
            ObjectNode body = RestClient.getMapper().createObjectNode().put("limit", SCAN_PAGE_SIZE)
                    .put("offset", offset);
            // Embeddings of the first page only, for the dimensions
            ArrayNode include = body.putArray("include").add("metadatas");
            if (offset == 0) {
                include.add("embeddings");
            }
            JsonNode page = rest.post(basePath + "/collections/" + id + "/get", body);
            JsonNode metadatas = page.path("metadatas");
            for (int i = 0; i < page.path("ids").size(); i++) {
                scan.records++;
                scan.metadata.observe(metadatas.path(i));
                JsonNode embedding = page.path("embeddings").path(i);
                if (offset == 0) {
                    if (embedding.isArray() && embedding.size() > 0) {
                        scan.dimensions = Math.max(scan.dimensions, embedding.size());
                    } else {
                        scan.missingEmbeddings = true;
                    }
                }
            }
            if (page.path("ids").size() < SCAN_PAGE_SIZE) {
                return scan;
            }
        }
    }

    private String getPath(VdbCollection collection, String operation) {
        return basePath + "/collections/" + collection.getProperties().get(ID) + "/" + operation;
    }

    @Override
    public long count(VdbCollection collection, VdbQuery query) {
        if (query.isNearest()) {
            return query.getLimit();
        }
        long count;
        if (query.isFiltered()) {
            ObjectNode body = RestClient.getMapper().createObjectNode();
            body.set("where", toOperatorFilter(collection, query, "Chroma"));
            body.putArray("include");
            count = rest.post(getPath(collection, "get"), body).path("ids").size();
        } else {
            count = rest.get(getPath(collection, "count")).asLong();
        }
        return query.getLimit() > 0 ? Math.min(count, query.getLimit()) : count;
    }

    @Override
    public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
        JsonNode where = toOperatorFilter(collection, query, "Chroma");
        if (query.isNearest()) {
            consumer.accept(search(collection, query, where, pageSize), true);
            return;
        }
        long remaining = query.getLimit() > 0 ? query.getLimit() : Long.MAX_VALUE;
        for (long offset = 0; remaining > 0; ) {
            int size = (int) Math.min(pageSize, remaining);
            ObjectNode body = RestClient.getMapper().createObjectNode().put("limit", size).put("offset", offset);
            body.putArray("include").add("embeddings").add("metadatas").add("documents");
            if (where != null) {
                body.set("where", where);
            }
            JsonNode page = rest.post(getPath(collection, "get"), body);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < page.path("ids").size(); i++) {
                rows.add(toRow(collection, page.path("ids").path(i), page.path("embeddings").path(i),
                        page.path("documents").path(i), page.path("metadatas").path(i)));
            }
            offset += rows.size();
            remaining -= rows.size();
            boolean last = rows.size() < size || remaining <= 0;
            if (!consumer.accept(rows, last) || last) {
                return;
            }
        }
    }

    private List<Map<String, Object>> search(VdbCollection collection, VdbQuery query, JsonNode where,
                                             int pageSize) {
        ObjectNode body = RestClient.getMapper().createObjectNode().put("n_results", query.getLimit() > 0
                ? (int) query.getLimit() : pageSize);
        ArrayNode vector = body.putArray("query_embeddings").addArray();
        if (query.getNearestVector() != null) {
            for (float value : query.getNearestVector()) {
                vector.add(value);
            }
        } else {
            ObjectNode get = RestClient.getMapper().createObjectNode();
            get.putArray("ids").add(String.valueOf(query.getNearestId()));
            get.putArray("include").add("embeddings");
            vector.addAll((ArrayNode) rest.post(getPath(collection, "get"), get).path("embeddings").path(0));
        }
        body.putArray("include").add("embeddings").add("metadatas").add("documents");
        if (where != null) {
            body.set("where", where);
        }
        JsonNode result = rest.post(getPath(collection, "query"), body);
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode ids = result.path("ids").path(0);
        for (int i = 0; i < ids.size(); i++) {
            rows.add(toRow(collection, ids.path(i), result.path("embeddings").path(0).path(i),
                    result.path("documents").path(0).path(i), result.path("metadatas").path(0).path(i)));
        }
        return rows;
    }

    private Map<String, Object> toRow(VdbCollection collection, JsonNode id, JsonNode embedding, JsonNode document,
                                      JsonNode metadata) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id.asText());
        values.put(EMBEDDING, embedding.isArray() && embedding.size() > 0 ? VdbValues.toDenseText(embedding) : null);
        values.put(DOCUMENT, document.isTextual() ? document.textValue() : null);
        return toRow(collection, values, metadata.isObject() ? metadata : null);
    }
}
