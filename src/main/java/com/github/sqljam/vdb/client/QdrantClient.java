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
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbInfo;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbValues;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: QdrantClient reads Qdrant 1.10 and later by its REST API with the api-key header (a read-only key
 *               is enough). Points are read by scroll in the order of their ids. A collection has an unnamed vector
 *               (column vector) or named vectors, dense vectors of float32, float16 or uint8 and sparse vectors.
 *               Payloads have no schema: the points are read once without their dense vectors to find the types of
 *               the payload fields, the type of the ids (integers or UUIDs) and the dimensions of the sparse
 *               vectors. Multivectors (late interaction) are JSON.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class QdrantClient extends AbstractVdbClient {

    /**
     * Name of the column of an unnamed vector
     */
    static final String UNNAMED_VECTOR = "vector";
    static final String UNNAMED = "unnamed";
    static final String SPARSE_NAMES = "sparseNames";
    private static final int SCAN_PAGE_SIZE = 1000;

    public QdrantClient(VdbSource source) {
        super(source);
        rest.header("api-key", source.getApiKey());
    }

    @Override
    public String getVersion() {
        return text(rest.get("/"), "version");
    }

    @Override
    public Map<String, Map<String, String>> getOverview() {
        Map<String, Map<String, String>> sections = super.getOverview();
        section(sections, "Server").put("Commit", VdbInfo.read(() -> text(rest.get("/"), "commit")));
        return sections;
    }

    /**
     * Readiness and liveness probes, the state of the cluster of distributed deployments
     */
    @Override
    public long countRecords(String collection) {
        return rest.post("/collections/" + encode(collection) + "/points/count", RestClient.getMapper()
                .createObjectNode().put("exact", true)).path("result").path("count").asLong(-1);
    }

    /**
     * The common metrics, and of /metrics: the REST and gRPC requests and the memory of the server
     */
    @Override
    public Map<String, Double> getMetrics() {
        Map<String, Double> metrics = super.getMetrics();
        try {
            Map<String, Double> values = VdbInfo.prometheus(rest.get("/metrics").asText());
            // Newer servers count the requests in their histograms of durations
            double requests = 0;
            boolean counted = false;
            for (String name : new String[]{"rest_responses_total", "grpc_responses_total",
                    "rest_responses_duration_seconds_count", "grpc_responses_duration_seconds_count"}) {
                if (values.containsKey(name) && !(name.endsWith("_count") && values.containsKey(name.replace(
                        "_duration_seconds_count", "_total")))) {
                    requests += values.get(name);
                    counted = true;
                }
            }
            if (counted) {
                metrics.put("requests", requests);
            }
            putIfPresent(metrics, "cpu.cores", values.get("cpu_cores_used"));
            putIfPresent(metrics, "memory.resident", values.get("memory_resident_bytes"));
            putIfPresent(metrics, "memory.allocated", values.get("memory_allocated_bytes"));
        } catch (RuntimeException e) {
            // Metrics switched off or not allowed, the common ones stay
        }
        return metrics;
    }

    private static void putIfPresent(Map<String, Double> metrics, String key, Double value) {
        if (value != null) {
            metrics.put(key, value);
        }
    }

    @Override
    public Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = super.getHealth();
        Map<String, String> health = section(sections, "Qdrant");
        health.put("Ready (/readyz)", VdbInfo.read(() -> rest.get("/readyz").asText()));
        health.put("Live (/livez)", VdbInfo.read(() -> rest.get("/livez").asText()));
        health.put("Cluster", VdbInfo.read(() -> {
            JsonNode cluster = rest.get("/cluster").path("result");
            return "enabled".equals(text(cluster, "status")) ? "Enabled, " + cluster.path("peers").size() + " peers"
                    : "Disabled, a single node";
        }));
        return sections;
    }

    @Override
    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        rest.get("/collections").path("result").path("collections").forEach(node -> names.add(text(node, "name")));
        return names;
    }

    @Override
    public VdbCollection describe(String name) {
        JsonNode config = rest.get("/collections/" + encode(name)).path("result").path("config");
        JsonNode params = config.path("params");
        JsonNode defaultHnsw = config.path("hnsw_config");
        VdbCollection collection = new VdbCollection(name);
        Scan scan = scan(name, params.path("sparse_vectors"));

        VdbField id = scan.integerIds ? new VdbField("id", VdbField.Kind.BIGINT) : VdbField.varchar("id", 36);
        id.setPrimaryKey(true);
        id.setNullable(false);
        collection.addField(id);

        JsonNode vectors = params.path("vectors");
        boolean unnamed = vectors.has("size");
        collection.getProperties().put(UNNAMED, unnamed);
        if (unnamed) {
            collection.addField(toVectorField(UNNAMED_VECTOR, vectors, defaultHnsw));
        } else {
            vectors.fields().forEachRemaining(entry -> {
                // A vector named by the empty text (LangChain and others) is the default vector, its column is vector
                VdbField field = toVectorField(entry.getKey().isEmpty() ? collection.getUnusedName(UNNAMED_VECTOR)
                        : entry.getKey(), entry.getValue(), defaultHnsw);
                field.setSourceName(entry.getKey());
                collection.addField(field);
            });
        }
        List<String> sparseNames = new ArrayList<>();
        params.path("sparse_vectors").fieldNames().forEachRemaining(sparseName -> {
            sparseNames.add(sparseName);
            long dimensions = scan.sparseDimensions.getOrDefault(sparseName, 0L);
            VdbField field = VdbField.vector(sparseName, new VectorType(VectorType.Element.FLOAT32,
                    (int) Math.min(dimensions, Integer.MAX_VALUE), true));
            collection.addField(field);
        });
        collection.getProperties().put(SPARSE_NAMES, sparseNames);
        collection.getFields().addAll(scan.payloads.getFields(collection));
        addPayloadField(collection);
        collection.setCount(scan.points);
        return collection;
    }

    /**
     * Column of a dense vector, JSON of a multivector. Its index is HNSW of its distance, with the options of the
     * vector or of the collection.
     */
    static VdbField toVectorField(String name, JsonNode vector, JsonNode defaultHnsw) {
        if (vector.has("multivector_config")) {
            VdbField field = new VdbField(name, VdbField.Kind.JSON);
            field.setRemarks("Multivector of Qdrant");
            return field;
        }
        String datatype = StringUtils.defaultIfBlank(text(vector, "datatype"), "float32");
        // Vectors of uint8 (0 to 255) are kept by 32-bit floats, int8 of other databases is signed
        VectorType.Element element = "float16".equalsIgnoreCase(datatype) ? VectorType.Element.FLOAT16
                : VectorType.Element.FLOAT32;
        VdbField field = VdbField.vector(name, VectorType.dense(element, vector.path("size").asInt()));
        VectorIndex.Distance distance = toDistance(text(vector, "distance"));
        if (distance != null) {
            JsonNode hnsw = vector.path("hnsw_config");
            Map<String, String> options = new LinkedHashMap<>();
            String m = hnsw.has("m") ? hnsw.path("m").asText() : text(defaultHnsw, "m");
            String ef = hnsw.has("ef_construct") ? hnsw.path("ef_construct").asText() : text(defaultHnsw,
                    "ef_construct");
            if (m != null && !"0".equals(m)) {
                options.put("m", m);
            }
            if (ef != null) {
                options.put("ef_construction", ef);
            }
            field.setVectorIndex(new VectorIndex(VectorIndex.Kind.HNSW, distance, options));
        }
        return field;
    }

    static VectorIndex.Distance toDistance(String distance) {
        switch (StringUtils.defaultString(distance).toLowerCase(Locale.ENGLISH)) {
            case "cosine":
                return VectorIndex.Distance.COSINE;
            case "euclid":
                return VectorIndex.Distance.EUCLIDEAN;
            case "dot":
                return VectorIndex.Distance.DOT;
            case "manhattan":
                return VectorIndex.Distance.MANHATTAN;
            default:
                return null;
        }
    }

    /**
     * What the points tell of the collection: the types of the ids and the payload fields, the dimensions of the
     * sparse vectors, the number of points
     */
    static final class Scan {
        boolean integerIds = true;
        final PayloadSchema payloads = new PayloadSchema();
        final Map<String, Long> sparseDimensions = new LinkedHashMap<>();
        long points;
    }

    private Scan scan(String name, JsonNode sparseVectors) {
        Scan scan = new Scan();
        List<String> sparseNames = new ArrayList<>();
        sparseVectors.fieldNames().forEachRemaining(sparseNames::add);
        JsonNode offset = null;
        do {
            ObjectNode body = RestClient.getMapper().createObjectNode().put("limit", SCAN_PAGE_SIZE)
                    .put("with_payload", true);
            if (sparseNames.isEmpty()) {
                body.put("with_vector", false);
            } else {
                // Sparse vectors only, dense vectors are not needed to find the dimensions
                ArrayNode with = body.putArray("with_vector");
                sparseNames.forEach(with::add);
            }
            if (offset != null) {
                body.set("offset", offset);
            }
            JsonNode result = rest.post("/collections/" + encode(name) + "/points/scroll", body).path("result");
            for (JsonNode point : result.path("points")) {
                scan.points++;
                if (!point.path("id").isIntegralNumber()) {
                    scan.integerIds = false;
                }
                scan.payloads.observe(point.path("payload"));
                for (String sparseName : sparseNames) {
                    for (JsonNode index : point.path("vector").path(sparseName).path("indices")) {
                        scan.sparseDimensions.merge(sparseName, index.asLong() + 1, Math::max);
                    }
                }
            }
            offset = result.path("next_page_offset");
        } while (offset != null && !offset.isNull() && !offset.isMissingNode());
        return scan;
    }

    @Override
    public long count(VdbCollection collection, VdbQuery query) {
        ObjectNode body = RestClient.getMapper().createObjectNode().put("exact", true);
        JsonNode filter = toFilter(collection, query);
        if (filter != null) {
            body.set("filter", filter);
        }
        long count = rest.post("/collections/" + encode(collection.getName()) + "/points/count", body)
                .path("result").path("count").asLong();
        return query.isNearest() || query.getLimit() > 0 ? Math.min(count, query.getLimit() > 0 ? query.getLimit()
                : count) : count;
    }

    @Override
    public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
        String path = "/collections/" + encode(collection.getName()) + "/points/";
        JsonNode filter = toFilter(collection, query);
        if (query.isNearest()) {
            ObjectNode body = RestClient.getMapper().createObjectNode().put("with_payload", true)
                    .put("with_vector", true).put("limit", query.getLimit() > 0 ? query.getLimit() : pageSize);
            if (query.getNearestVector() != null) {
                ArrayNode vector = body.putArray("query");
                for (float value : query.getNearestVector()) {
                    vector.add(value);
                }
            } else {
                body.set("query", RestClient.getMapper().valueToTree(query.getNearestId()));
            }
            if (!Boolean.TRUE.equals(collection.getProperties().get(UNNAMED))) {
                String using = collection.findField(query.getNearestField()).map(VdbField::getSourceName)
                        .orElse(query.getNearestField());
                if (!using.isEmpty()) {
                    body.put("using", using);
                }
            }
            if (filter != null) {
                body.set("filter", filter);
            }
            consumer.accept(toRows(collection, rest.post(path + "query", body).path("result").path("points")),
                    true);
            return;
        }
        long remaining = query.getLimit() > 0 ? query.getLimit() : Long.MAX_VALUE;
        JsonNode offset = null;
        while (remaining > 0) {
            ObjectNode body = RestClient.getMapper().createObjectNode().put("with_payload", true)
                    .put("with_vector", true).put("limit", (int) Math.min(pageSize, remaining));
            if (offset != null) {
                body.set("offset", offset);
            }
            if (filter != null) {
                body.set("filter", filter);
            }
            JsonNode result = rest.post(path + "scroll", body).path("result");
            List<Map<String, Object>> rows = toRows(collection, result.path("points"));
            remaining -= rows.size();
            offset = result.path("next_page_offset");
            boolean last = rows.isEmpty() || offset.isNull() || offset.isMissingNode() || remaining <= 0;
            if (!consumer.accept(rows, last) || last) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> toRows(VdbCollection collection, JsonNode points) {
        boolean unnamed = Boolean.TRUE.equals(collection.getProperties().get(UNNAMED));
        List<String> sparseNames = (List<String>) collection.getProperties().getOrDefault(SPARSE_NAMES,
                new ArrayList<String>());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode point : points) {
            Map<String, Object> values = new LinkedHashMap<>();
            JsonNode id = point.path("id");
            values.put("id", id.isIntegralNumber() ? (Object) id.longValue() : id.asText());
            JsonNode vector = point.path("vector");
            for (VdbField field : collection.getFields()) {
                if (!field.isVector() && field.getKind() != VdbField.Kind.JSON) {
                    continue;
                }
                if (unnamed && UNNAMED_VECTOR.equals(field.getName())) {
                    values.put(field.getName(), VdbValues.toDenseText(vector));
                } else if (sparseNames.contains(field.getName())) {
                    JsonNode sparse = vector.path(field.getName());
                    values.put(field.getName(), sparse.isMissingNode() ? null : VdbValues.toSparseText(
                            VdbValues.getSparseElements(sparse.path("indices"), sparse.path("values")),
                            Math.max(field.getVectorType().getDimensions(), 1)));
                } else if (field.isVector()) {
                    // The vector of the empty name is answered as the vector itself
                    JsonNode value = field.getSourceName().isEmpty() && vector.isArray() ? vector
                            : vector.get(field.getSourceName());
                    values.put(field.getName(), VdbValues.toDenseText(value));
                } else if (vector.has(field.getSourceName())) {
                    // A multivector
                    values.put(field.getName(), VdbValues.toJson(vector.get(field.getSourceName())));
                }
            }
            rows.add(toRow(collection, values, point.path("payload")));
        }
        return rows;
    }

    /**
     * Filter of Qdrant: the native filter, or must conditions of the payload fields
     */
    static JsonNode toFilter(VdbCollection collection, VdbQuery query) {
        if (query.isNative()) {
            try {
                return RestClient.getMapper().readTree(query.getNativeFilter());
            } catch (IOException e) {
                throw new VdbException("The filter of Qdrant is not JSON: " + e.getMessage(), e);
            }
        }
        if (query.getConditions().isEmpty()) {
            return null;
        }
        ObjectNode filter = RestClient.getMapper().createObjectNode();
        ArrayNode must = filter.putArray("must");
        ArrayNode mustNot = filter.putArray("must_not");
        for (VdbQuery.Condition condition : query.getConditions()) {
            String key = collection.findField(condition.getField()).map(VdbField::getSourceName)
                    .orElse(condition.getField());
            ObjectNode clause = RestClient.getMapper().createObjectNode().put("key", key);
            JsonNode value = RestClient.getMapper().valueToTree(condition.getValue());
            switch (condition.getOperator()) {
                case EQ:
                    clause.putObject("match").set("value", value);
                    must.add(clause);
                    break;
                case NE:
                    clause.putObject("match").set("value", value);
                    mustNot.add(clause);
                    break;
                case IN:
                    clause.putObject("match").set("any", value);
                    must.add(clause);
                    break;
                default:
                    clause.putObject("range").set(condition.getOperator().name().toLowerCase(Locale.ENGLISH)
                            .replace("ge", "gte").replace("le", "lte"), value);
                    must.add(clause);
                    break;
            }
        }
        if (mustNot.size() == 0) {
            filter.remove("must_not");
        }
        return filter;
    }
}
