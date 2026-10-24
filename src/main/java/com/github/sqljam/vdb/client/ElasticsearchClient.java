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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbInfo;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbValues;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: ElasticsearchClient reads Elasticsearch 8.0 and later by its REST API with a user and a password or
 *               an API key. An index is a collection: _id is the id, dense_vector fields are vectors of their
 *               similarity and HNSW options, the top-level fields of the mapping are columns (any field may hold
 *               an array, which is NULL in its column and kept by the payload). Documents are read in a point in
 *               time by search_after.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ElasticsearchClient extends AbstractVdbClient {

    static final String ID = "_id";
    /**
     * Length of _id at most
     */
    static final int MAX_ID_LENGTH = 512;
    static final String KEEP_ALIVE = "5m";

    public ElasticsearchClient(VdbSource source) {
        super(source);
        if (StringUtils.isNotBlank(source.getApiKey())) {
            rest.header("Authorization", "ApiKey " + source.getApiKey());
        } else if (StringUtils.isNotBlank(source.getUsername())) {
            rest.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((source.getUsername() + ":"
                    + StringUtils.defaultString(source.getPassword())).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    public String getVersion() {
        return text(rest.get("/").path("version"), "number");
    }

    /**
     * Indexes which are not hidden or of the system (names of a dot)
     */
    @Override
    public Map<String, Map<String, String>> getOverview() {
        Map<String, Map<String, String>> sections = super.getOverview();
        Map<String, String> server = section(sections, "Server");
        server.put("Cluster", VdbInfo.read(() -> text(rest.get("/"), "cluster_name")));
        server.put("Lucene", VdbInfo.read(() -> text(rest.get("/").path("version"), "lucene_version")));
        Map<String, String> data = section(sections, "Data");
        data.put("Documents", VdbInfo.read(() -> rest.get("/_cluster/stats").path("indices").path("docs")
                .path("count").asText()));
        data.put("Store size", VdbInfo.read(() -> VdbInfo.bytes(rest.get("/_cluster/stats").path("indices")
                .path("store").path("size_in_bytes").asLong())));
        return sections;
    }

    /**
     * Health of the cluster: green, yellow or red, its nodes and shards
     */
    @Override
    public Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = super.getHealth();
        Map<String, String> cluster = section(sections, "Cluster");
        String error = VdbInfo.read(() -> {
            JsonNode health = rest.get("/_cluster/health");
            cluster.put("Status", text(health, "status"));
            cluster.put("Nodes", health.path("number_of_nodes").asText() + ", " + health.path(
                    "number_of_data_nodes").asText() + " data nodes");
            cluster.put("Active shards", health.path("active_shards").asText() + " of which "
                    + health.path("active_primary_shards").asText() + " primary");
            cluster.put("Unassigned shards", health.path("unassigned_shards").asText());
            cluster.put("Pending tasks", health.path("number_of_pending_tasks").asText());
            return "";
        });
        if (error.startsWith(VdbInfo.UNAVAILABLE)) {
            cluster.put("Status", error);
        }
        return sections;
    }

    @Override
    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        for (JsonNode index : rest.get("/_cat/indices?format=json&h=index")) {
            String name = text(index, "index");
            if (name != null && !isSystemIndex(name)) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Whether an index is of the server, hidden indexes start with a dot
     */
    protected boolean isSystemIndex(String name) {
        return name.startsWith(".");
    }

    @Override
    public VdbCollection describe(String name) {
        JsonNode mappings = rest.get("/" + encode(name) + "/_mapping");
        JsonNode properties = mappings.path(name).path("mappings").path("properties");
        if (properties.isMissingNode() && mappings.size() == 1) {
            // An alias answers with the name of its index
            properties = mappings.elements().next().path("mappings").path("properties");
        }
        VdbCollection collection = new VdbCollection(name);
        VdbField id = VdbField.varchar("id", MAX_ID_LENGTH);
        id.setSourceName(ID);
        id.setPrimaryKey(true);
        id.setNullable(false);
        collection.addField(id);
        properties.fields().forEachRemaining(entry -> {
            VdbField field = toField(collection.getUnusedName(entry.getKey()), entry.getValue());
            field.setSourceName(entry.getKey());
            collection.addField(field);
        });
        addPayloadField(collection);
        collection.setCount(count(collection, VdbQuery.all()));
        return collection;
    }

    /**
     * Column of a field of the mapping
     */
    VdbField toField(String name, JsonNode mapping) {
        String type = StringUtils.defaultString(text(mapping, "type"), mapping.has("properties") ? "object" : "");
        if (isVectorType(type)) {
            return toVectorField(name, mapping);
        }
        switch (type) {
            case "boolean":
                return new VdbField(name, VdbField.Kind.BOOLEAN);
            case "byte":
            case "short":
                return new VdbField(name, VdbField.Kind.SMALLINT);
            case "integer":
                return new VdbField(name, VdbField.Kind.INTEGER);
            case "long":
            case "unsigned_long":
                return new VdbField(name, VdbField.Kind.BIGINT);
            case "float":
            case "half_float":
                return new VdbField(name, VdbField.Kind.REAL);
            case "double":
            case "scaled_float":
                return new VdbField(name, VdbField.Kind.DOUBLE);
            case "keyword":
            case "constant_keyword":
            case "wildcard":
            case "text":
            case "match_only_text":
            case "ip":
            case "version":
            case "date":
            case "date_nanos":
                // Dates of a mapping take formats of their own, they are text
                return new VdbField(name, VdbField.Kind.TEXT);
            case "binary":
                return new VdbField(name, VdbField.Kind.BINARY);
            default:
                // Objects, nested documents, geo shapes, ranges, sparse vectors of rank features
                return new VdbField(name, VdbField.Kind.JSON);
        }
    }

    protected boolean isVectorType(String type) {
        return "dense_vector".equals(type);
    }

    /**
     * dense_vector of its element type (float, byte, bit), similarity and HNSW options
     */
    protected VdbField toVectorField(String name, JsonNode mapping) {
        String elementType = StringUtils.defaultIfBlank(text(mapping, "element_type"), "float");
        int dimensions = mapping.path("dims").asInt(0);
        VectorType.Element element = "byte".equals(elementType) ? VectorType.Element.INT8
                : "bit".equals(elementType) ? VectorType.Element.BINARY : VectorType.Element.FLOAT32;
        VdbField field = VdbField.vector(name, VectorType.dense(element, dimensions));
        VectorIndex.Distance distance = toDistance(text(mapping, "similarity"));
        JsonNode indexOptions = mapping.path("index_options");
        if (mapping.path("index").asBoolean(true) && distance != null) {
            Map<String, String> options = new LinkedHashMap<>();
            if (indexOptions.has("m")) {
                options.put("m", indexOptions.path("m").asText());
            }
            if (indexOptions.has("ef_construction")) {
                options.put("ef_construction", indexOptions.path("ef_construction").asText());
            }
            String indexType = StringUtils.defaultString(text(indexOptions, "type"), "hnsw");
            // Flat indexes are scans, HNSW of quantized vectors (int8_hnsw, bbq_hnsw) is HNSW
            if (indexType.contains("hnsw")) {
                field.setVectorIndex(new VectorIndex(VectorIndex.Kind.HNSW, distance, options));
            }
        }
        return field;
    }

    static VectorIndex.Distance toDistance(String similarity) {
        switch (StringUtils.defaultString(similarity).toLowerCase(Locale.ENGLISH)) {
            case "cosine":
                return VectorIndex.Distance.COSINE;
            case "l2_norm":
                return VectorIndex.Distance.EUCLIDEAN;
            case "dot_product":
            case "max_inner_product":
                return VectorIndex.Distance.DOT;
            default:
                return null;
        }
    }

    @Override
    public long count(VdbCollection collection, VdbQuery query) {
        if (query.isNearest()) {
            return query.getLimit();
        }
        ObjectNode body = RestClient.getMapper().createObjectNode();
        JsonNode filter = toQuery(collection, query);
        if (filter != null) {
            body.set("query", filter);
        }
        long count = rest.post("/" + encode(collection.getName()) + "/_count", body).path("count").asLong();
        return query.getLimit() > 0 ? Math.min(count, query.getLimit()) : count;
    }

    @Override
    public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
        if (query.isNearest()) {
            consumer.accept(toRows(collection, rest.post("/" + encode(collection.getName()) + "/_search",
                    toNearestSearch(collection, query, pageSize)).path("hits").path("hits")), true);
            return;
        }
        String pit = openPointInTime(collection.getName());
        try {
            long remaining = query.getLimit() > 0 ? query.getLimit() : Long.MAX_VALUE;
            JsonNode searchAfter = null;
            JsonNode filter = toQuery(collection, query);
            while (remaining > 0) {
                int size = (int) Math.min(pageSize, remaining);
                ObjectNode body = RestClient.getMapper().createObjectNode().put("size", size);
                body.putObject("pit").put("id", pit).put("keep_alive", KEEP_ALIVE);
                body.putArray("sort").addObject().put(getPointInTimeSort(), "asc");
                if (filter != null) {
                    body.set("query", filter);
                }
                if (searchAfter != null) {
                    body.set("search_after", searchAfter);
                }
                JsonNode response = rest.post("/_search", body);
                JsonNode hits = response.path("hits").path("hits");
                String nextPit = text(response, getPointInTimeIdKey());
                if (nextPit != null) {
                    pit = nextPit;
                }
                List<Map<String, Object>> rows = toRows(collection, hits);
                remaining -= rows.size();
                boolean last = rows.size() < size || remaining <= 0;
                if (hits.size() > 0) {
                    searchAfter = hits.get(hits.size() - 1).path("sort");
                }
                if (!consumer.accept(rows, last) || last) {
                    return;
                }
            }
        } finally {
            closePointInTime(pit);
        }
    }

    protected String openPointInTime(String index) {
        return text(rest.post("/" + encode(index) + "/_pit?keep_alive=" + KEEP_ALIVE, null), "id");
    }

    protected void closePointInTime(String pit) {
        try {
            rest.send("DELETE", "/_pit", RestClient.getMapper().createObjectNode().put("id", pit));
        } catch (RuntimeException e) {
            // A point in time which is not closed expires
        }
    }

    protected String getPointInTimeSort() {
        return "_shard_doc";
    }

    protected String getPointInTimeIdKey() {
        return "pit_id";
    }

    /**
     * Search of the nearest neighbors of a vector, or of the vector of a document
     */
    protected ObjectNode toNearestSearch(VdbCollection collection, VdbQuery query, int pageSize) {
        int k = query.getLimit() > 0 ? (int) query.getLimit() : pageSize;
        ObjectNode body = RestClient.getMapper().createObjectNode().put("size", k);
        ObjectNode knn = body.putObject("knn").put("field", sourceNameOf(collection, query.getNearestField()))
                .put("k", k).put("num_candidates", Math.max(k * 10, 100));
        knn.set("query_vector", toVectorNode(nearestVector(collection, query)));
        JsonNode filter = toQuery(collection, query);
        if (filter != null) {
            knn.set("filter", filter);
        }
        return body;
    }

    protected static ArrayNode toVectorNode(float[] vector) {
        ArrayNode node = RestClient.getMapper().createArrayNode();
        for (float value : vector) {
            node.add(value);
        }
        return node;
    }

    /**
     * Vector of the query, or of the document of its id
     */
    protected float[] nearestVector(VdbCollection collection, VdbQuery query) {
        if (query.getNearestVector() != null) {
            return query.getNearestVector();
        }
        String field = sourceNameOf(collection, query.getNearestField());
        JsonNode vector = rest.get("/" + encode(collection.getName()) + "/_doc/" + encode(String.valueOf(
                query.getNearestId()))).path("_source").path(field);
        if (!vector.isArray()) {
            throw new VdbException("Document " + query.getNearestId() + " has no vector " + field);
        }
        float[] values = new float[vector.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = vector.get(i).floatValue();
        }
        return values;
    }

    protected static String sourceNameOf(VdbCollection collection, String column) {
        return collection.findField(column).map(VdbField::getSourceName).orElse(column);
    }

    List<Map<String, Object>> toRows(VdbCollection collection, JsonNode hits) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode document = hit.path("_source");
            Map<String, Object> values = new LinkedHashMap<>();
            ObjectNode payload = RestClient.getMapper().createObjectNode();
            document.fields().forEachRemaining(entry -> payload.set(entry.getKey(), entry.getValue()));
            for (VdbField field : collection.getFields()) {
                if (field.isPrimaryKey()) {
                    values.put(field.getName(), text(hit, ID));
                } else if (field.isVector()) {
                    JsonNode vector = document.get(field.getSourceName());
                    values.put(field.getName(), vector == null || vector.isNull() ? null
                            : field.getVectorType().getElement() == VectorType.Element.BINARY && vector.isTextual()
                            ? VdbValues.toByteText(hexToBytes(vector.textValue())) : VdbValues.toDenseText(vector));
                    payload.remove(field.getSourceName());
                }
            }
            rows.add(toRow(collection, values, payload));
        }
        return rows;
    }

    /**
     * Bytes of a bit vector of Elasticsearch, which is read as hexadecimal text
     */
    static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /**
     * Query of the search: the native query (JSON of the query DSL), or a bool filter of the conditions
     */
    protected JsonNode toQuery(VdbCollection collection, VdbQuery query) {
        if (query.isNative()) {
            try {
                return RestClient.getMapper().readTree(query.getNativeFilter());
            } catch (IOException e) {
                throw new VdbException("The query is not JSON: " + e.getMessage(), e);
            }
        }
        if (query.getConditions().isEmpty()) {
            return null;
        }
        ObjectNode bool = RestClient.getMapper().createObjectNode();
        ObjectNode clauses = bool.putObject("bool");
        ArrayNode filter = clauses.putArray("filter");
        ArrayNode mustNot = clauses.putArray("must_not");
        for (VdbQuery.Condition condition : query.getConditions()) {
            String field = sourceNameOf(collection, condition.getField());
            JsonNode value = RestClient.getMapper().valueToTree(condition.getValue());
            ObjectNode clause = RestClient.getMapper().createObjectNode();
            switch (condition.getOperator()) {
                case EQ:
                    clause.putObject("term").set(field, value);
                    filter.add(clause);
                    break;
                case NE:
                    clause.putObject("term").set(field, value);
                    mustNot.add(clause);
                    break;
                case IN:
                    clause.putObject("terms").set(field, value);
                    filter.add(clause);
                    break;
                default:
                    clause.putObject("range").putObject(field).set(rangeOperator(condition.getOperator()), value);
                    filter.add(clause);
                    break;
            }
        }
        if (mustNot.size() == 0) {
            clauses.remove("must_not");
        }
        return bool;
    }

    static String rangeOperator(VdbQuery.Operator operator) {
        switch (operator) {
            case GT:
                return "gt";
            case GE:
                return "gte";
            case LT:
                return "lt";
            default:
                return "lte";
        }
    }
}
