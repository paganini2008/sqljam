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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
 * @Description: PineconeClient reads Pinecone and Pinecone Local by the REST API of the version 2025-01 with an
 *               API key. An index is a collection: the id, the dense values of its dimension and metric, the sparse
 *               values, and the fields of the metadata are columns. An index of more namespaces has a namespace
 *               column in its key. Vectors are listed by their ids and fetched in pages; metadata filters of the
 *               conditions are applied to the fetched vectors, native filters and searches are queries.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class PineconeClient extends AbstractVdbClient {

    static final String API_VERSION = "2025-01";
    static final String NAMESPACE = "namespace";
    static final String DEFAULT_NAMESPACE = "__default__";
    static final String VALUES = "values";
    static final String SPARSE_VALUES = "sparse_values";
    static final String HOST = "host";
    static final String NAMESPACES = "namespaces";
    static final String METRIC = "metric";
    /**
     * Ids of a page of a list and of a fetch at most
     */
    static final int MAX_PAGE_SIZE = 100;
    /**
     * Records of a query at most (topK)
     */
    static final int MAX_TOP_K = 10000;

    public PineconeClient(VdbSource source) {
        super(source);
        rest.header("Api-Key", source.getApiKey());
        rest.header("X-Pinecone-API-Version", API_VERSION);
    }

    /**
     * Pinecone tells no version of its server, it is the version of the API
     */
    @Override
    public String getVersion() {
        rest.get("/indexes");
        return API_VERSION;
    }

    /**
     * Each index: its dimensions, metric, state and vectors, how full it is
     */
    @Override
    public Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = super.getHealth();
        Map<String, String> indexes = section(sections, "Indexes");
        for (String name : getCollections()) {
            indexes.put(name, VdbInfo.read(() -> {
                JsonNode index = rest.get("/indexes/" + encode(name));
                JsonNode stats = rest.post(toHost(text(index, HOST)) + "/describe_index_stats",
                        RestClient.getMapper().createObjectNode());
                return String.format("%s, %s dimensions, %s, %d vectors in %d namespaces, %.0f%% full",
                        StringUtils.defaultIfBlank(text(index.path("status"), "state"), "?"), text(index,
                                "dimension"), text(index, METRIC), stats.path("totalVectorCount").asLong(),
                        stats.path(NAMESPACES).size(), stats.path("indexFullness").asDouble() * 100);
            }));
        }
        return sections;
    }

    @Override
    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        rest.get("/indexes").path("indexes").forEach(node -> names.add(text(node, "name")));
        return names;
    }

    @Override
    public VdbCollection describe(String name) {
        JsonNode index = rest.get("/indexes/" + encode(name));
        VdbCollection collection = new VdbCollection(name);
        String host = toHost(required(text(index, HOST), "Host of the index " + name));
        collection.getProperties().put(HOST, host);
        String metric = StringUtils.defaultIfBlank(text(index, METRIC), "cosine");
        collection.getProperties().put(METRIC, metric);

        List<String> namespaces = new ArrayList<>();
        rest.post(host + "/describe_index_stats", RestClient.getMapper().createObjectNode()).path(NAMESPACES)
                .fieldNames().forEachRemaining(namespaces::add);
        if (namespaces.isEmpty()) {
            namespaces.add("");
        }
        collection.getProperties().put(NAMESPACES, namespaces);
        boolean named = namespaces.size() > 1 || !isDefaultNamespace(namespaces.get(0));
        if (named) {
            VdbField namespace = VdbField.varchar(NAMESPACE, 64);
            namespace.setPrimaryKey(true);
            namespace.setNullable(false);
            collection.addField(namespace);
        }
        VdbField id = VdbField.varchar("id", 512);
        id.setPrimaryKey(true);
        id.setNullable(false);
        collection.addField(id);

        boolean sparseIndex = "sparse".equalsIgnoreCase(text(index, "vector_type"));
        if (!sparseIndex) {
            VdbField values = VdbField.vector(VALUES, VectorType.dense(VectorType.Element.FLOAT32,
                    index.path("dimension").asInt(0)));
            VectorIndex.Distance distance = toDistance(metric);
            if (distance != null) {
                values.setVectorIndex(new VectorIndex(VectorIndex.Kind.HNSW, distance, null));
            }
            collection.addField(values);
        }
        Scan scan = scan(collection);
        if (sparseIndex || scan.sparseDimensions > 0) {
            VdbField sparse = VdbField.vector(SPARSE_VALUES, new VectorType(VectorType.Element.FLOAT32,
                    (int) Math.min(scan.sparseDimensions, Integer.MAX_VALUE), true));
            sparse.setSourceName("sparseValues");
            collection.addField(sparse);
        }
        collection.getFields().addAll(scan.payloads.getFields(collection));
        addPayloadField(collection);
        collection.setCount(scan.vectors);
        return collection;
    }

    /**
     * Host of an index: Pinecone Local tells localhost, which is the host of the source
     */
    String toHost(String host) {
        URI url = URI.create(source.getUrl());
        String hostName = StringUtils.substringBefore(host, ":");
        if ("localhost".equalsIgnoreCase(hostName) || "127.0.0.1".equals(hostName) || "0.0.0.0".equals(hostName)) {
            String port = host.contains(":") ? ":" + StringUtils.substringAfterLast(host, ":") : "";
            return url.getScheme() + "://" + url.getHost() + port;
        }
        return host.contains("://") ? host : "https://" + host;
    }

    static boolean isDefaultNamespace(String namespace) {
        return namespace.isEmpty() || DEFAULT_NAMESPACE.equals(namespace);
    }

    static VectorIndex.Distance toDistance(String metric) {
        switch (StringUtils.defaultString(metric).toLowerCase(Locale.ENGLISH)) {
            case "cosine":
                return VectorIndex.Distance.COSINE;
            case "euclidean":
                return VectorIndex.Distance.EUCLIDEAN;
            case "dotproduct":
                return VectorIndex.Distance.DOT;
            default:
                return null;
        }
    }

    /**
     * What the vectors tell of the index: the types of the metadata fields, the dimensions of the sparse values, the
     * number of vectors
     */
    static final class Scan {
        final PayloadSchema payloads = new PayloadSchema();
        long sparseDimensions;
        long vectors;
    }

    private Scan scan(VdbCollection collection) {
        Scan scan = new Scan();
        for (String namespace : getNamespaces(collection)) {
            listAndFetch(collection, namespace, (ids, vectors) -> {
                for (JsonNode vector : vectors) {
                    scan.vectors++;
                    scan.payloads.observe(vector.path("metadata"));
                    for (JsonNode index : vector.path("sparseValues").path("indices")) {
                        scan.sparseDimensions = Math.max(scan.sparseDimensions, index.asLong() + 1);
                    }
                }
                return true;
            });
        }
        return scan;
    }

    @Override
    public List<String> listPartitions(String collection) {
        String host = toHost(required(text(rest.get("/indexes/" + encode(collection)), HOST), "Host of the index "
                + collection));
        List<String> names = new ArrayList<>();
        rest.post(host + "/describe_index_stats", RestClient.getMapper().createObjectNode()).path(NAMESPACES)
                .fieldNames().forEachRemaining(namespace -> names.add(isDefaultNamespace(namespace)
                        ? DEFAULT_NAMESPACE : namespace));
        return names;
    }

    /**
     * Namespaces of the index, the default one is __default__
     */
    @Override
    public List<String> getPartitions(VdbCollection collection) {
        List<String> names = new ArrayList<>();
        getNamespaces(collection).forEach(namespace -> names.add(isDefaultNamespace(namespace) ? DEFAULT_NAMESPACE
                : namespace));
        return names;
    }

    /**
     * Namespaces which are read, the namespaces of the query or all
     */
    static List<String> getNamespaces(VdbCollection collection, VdbQuery query) {
        List<String> selected = new ArrayList<>();
        for (String namespace : getNamespaces(collection)) {
            String name = isDefaultNamespace(namespace) ? DEFAULT_NAMESPACE : namespace;
            if (!query.selectPartitions(List.of(name)).isEmpty()) {
                selected.add(namespace);
            }
        }
        return selected;
    }

    @SuppressWarnings("unchecked")
    static List<String> getNamespaces(VdbCollection collection) {
        return (List<String>) collection.getProperties().get(NAMESPACES);
    }

    @FunctionalInterface
    private interface FetchConsumer {
        /**
         * @param vectors fetched vectors in the order of the list
         * @return whether the next page is fetched
         */
        boolean accept(List<String> ids, List<JsonNode> vectors);
    }

    /**
     * Lists the ids of a namespace page by page and fetches their vectors
     */
    private void listAndFetch(VdbCollection collection, String namespace, FetchConsumer consumer) {
        String host = (String) collection.getProperties().get(HOST);
        String token = null;
        do {
            JsonNode page = rest.get(host + "/vectors/list?namespace=" + encode(namespace) + "&limit="
                    + MAX_PAGE_SIZE + (token == null ? "" : "&paginationToken=" + encode(token)));
            List<String> ids = new ArrayList<>();
            page.path("vectors").forEach(vector -> ids.add(text(vector, "id")));
            token = StringUtils.trimToNull(text(page.path("pagination"), "next"));
            if (ids.isEmpty()) {
                return;
            }
            if (!consumer.accept(ids, fetch(host, namespace, ids))) {
                return;
            }
        } while (token != null);
    }

    private List<JsonNode> fetch(String host, String namespace, Collection<String> ids) {
        StringBuilder path = new StringBuilder(host).append("/vectors/fetch?namespace=").append(encode(namespace));
        ids.forEach(id -> path.append("&ids=").append(encode(id)));
        JsonNode fetched = rest.get(path.toString()).path("vectors");
        List<JsonNode> vectors = new ArrayList<>();
        for (String id : ids) {
            // A vector deleted between the list and the fetch is not there
            if (fetched.has(id)) {
                vectors.add(fetched.get(id));
            }
        }
        return vectors;
    }

    @Override
    public long count(VdbCollection collection, VdbQuery query) {
        if (query.isNearest()) {
            return query.getLimit() > 0 ? Math.min(query.getLimit(), MAX_TOP_K) : MAX_TOP_K;
        }
        if (query.isFiltered()) {
            return -1;
        }
        String host = (String) collection.getProperties().get(HOST);
        long count = 0;
        JsonNode namespaces = rest.post(host + "/describe_index_stats", RestClient.getMapper().createObjectNode())
                .path(NAMESPACES);
        for (String namespace : getNamespaces(collection, query)) {
            count += namespaces.path(namespace).path("vectorCount").asLong(0);
        }
        return query.getLimit() > 0 ? Math.min(count, query.getLimit()) : count;
    }

    @Override
    public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
        if (query.isNearest()) {
            List<Map<String, Object>> rows = search(collection, query);
            consumer.accept(rows, true);
            return;
        }
        if (query.isNative()) {
            throw new VdbException("Pinecone filters the metadata of a search, please give a vector or an id to "
                    + "search with the filter");
        }
        List<String> namespaces = getNamespaces(collection, query);
        long[] remaining = {query.getLimit() > 0 ? query.getLimit() : Long.MAX_VALUE};
        boolean[] stopped = {false};
        List<Map<String, Object>> buffer = new ArrayList<>();
        int size = Math.max(1, pageSize);
        for (String namespace : namespaces) {
            listAndFetch(collection, namespace, (ids, vectors) -> {
                for (JsonNode vector : vectors) {
                    if (remaining[0] <= 0) {
                        break;
                    }
                    if (matches(collection, vector.path("metadata"), query.getConditions())) {
                        buffer.add(toRow(collection, namespace, vector));
                        remaining[0]--;
                    }
                }
                if (buffer.size() >= size && remaining[0] > 0) {
                    List<Map<String, Object>> rows = new ArrayList<>(buffer);
                    buffer.clear();
                    if (!consumer.accept(rows, false)) {
                        stopped[0] = true;
                    }
                }
                return !stopped[0] && remaining[0] > 0;
            });
            if (stopped[0] || remaining[0] <= 0) {
                break;
            }
        }
        if (!stopped[0]) {
            consumer.accept(buffer, true);
        }
    }

    /**
     * Nearest vectors of each namespace, merged by their scores
     */
    private List<Map<String, Object>> search(VdbCollection collection, VdbQuery query) {
        String host = (String) collection.getProperties().get(HOST);
        int topK = (int) (query.getLimit() > 0 ? Math.min(query.getLimit(), MAX_TOP_K) : MAX_TOP_K);
        JsonNode filter = toOperatorFilter(collection, query, "Pinecone");
        List<JsonNode[]> matches = new ArrayList<>();
        for (String namespace : getNamespaces(collection, query)) {
            ObjectNode body = RestClient.getMapper().createObjectNode().put(NAMESPACE, namespace).put("topK", topK)
                    .put("includeValues", true).put("includeMetadata", true);
            if (query.getNearestVector() != null) {
                ArrayNode vector = body.putArray("vector");
                for (float value : query.getNearestVector()) {
                    vector.add(value);
                }
            } else {
                body.put("id", String.valueOf(query.getNearestId()));
            }
            if (filter != null) {
                body.set("filter", filter);
            }
            JsonNode namespaceNode = RestClient.getMapper().getNodeFactory().textNode(namespace);
            rest.post(host + "/query", body).path("matches").forEach(match -> matches.add(new JsonNode[]{match,
                    namespaceNode}));
        }
        // Higher scores are nearer, except the distances of euclidean
        Comparator<JsonNode[]> byScore = Comparator.comparingDouble(match -> match[0].path("score").asDouble());
        boolean ascending = "euclidean".equalsIgnoreCase((String) collection.getProperties().get(METRIC));
        matches.sort(ascending ? byScore : byScore.reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < matches.size() && i < topK; i++) {
            rows.add(toRow(collection, matches.get(i)[1].asText(), matches.get(i)[0]));
        }
        return rows;
    }

    private Map<String, Object> toRow(VdbCollection collection, String namespace, JsonNode vector) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(NAMESPACE, isDefaultNamespace(namespace) ? DEFAULT_NAMESPACE : namespace);
        values.put("id", text(vector, "id"));
        JsonNode dense = vector.path(VALUES);
        values.put(VALUES, dense.isArray() && dense.size() > 0 ? VdbValues.toDenseText(dense) : null);
        collection.findField(SPARSE_VALUES).ifPresent(field -> {
            JsonNode sparse = vector.path("sparseValues");
            values.put(SPARSE_VALUES, sparse.isObject() ? VdbValues.toSparseText(VdbValues.getSparseElements(
                    sparse.path("indices"), sparse.path(VALUES)), field.getVectorType().getDimensions()) : null);
        });
        return toRow(collection, values, vector.path("metadata"));
    }

    /**
     * Whether the metadata matches all the conditions, a missing field matches none but NE
     */
    static boolean matches(VdbCollection collection, JsonNode metadata, List<VdbQuery.Condition> conditions) {
        for (VdbQuery.Condition condition : conditions) {
            String name = collection.findField(condition.getField()).map(VdbField::getSourceName)
                    .orElse(condition.getField());
            JsonNode value = metadata.get(name);
            if (!matches(value, condition.getOperator(), condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    static boolean matches(JsonNode value, VdbQuery.Operator operator, Object expected) {
        if (value == null || value.isNull()) {
            return operator == VdbQuery.Operator.NE;
        }
        if (operator == VdbQuery.Operator.IN) {
            Iterable<?> items = expected instanceof Iterable ? (Iterable<?>) expected : List.of(expected);
            for (Object item : items) {
                if (compare(value, item) == 0) {
                    return true;
                }
            }
            return false;
        }
        // A list of strings of the metadata matches EQ if it contains the value, as Pinecone does
        if (value.isArray() && (operator == VdbQuery.Operator.EQ || operator == VdbQuery.Operator.NE)) {
            boolean contains = false;
            for (JsonNode item : value) {
                contains |= compare(item, expected) == 0;
            }
            return operator == VdbQuery.Operator.EQ ? contains : !contains;
        }
        Integer result = compare(value, expected);
        if (result == null) {
            return operator == VdbQuery.Operator.NE;
        }
        switch (operator) {
            case EQ:
                return result == 0;
            case NE:
                return result != 0;
            case GT:
                return result > 0;
            case GE:
                return result >= 0;
            case LT:
                return result < 0;
            default:
                return result <= 0;
        }
    }

    /**
     * Comparison of a value of the metadata with a value of a condition, null if they are of different types
     */
    static Integer compare(JsonNode value, Object expected) {
        if (value.isNumber() && expected instanceof Number) {
            return Double.compare(value.doubleValue(), ((Number) expected).doubleValue());
        } else if (value.isBoolean() && expected instanceof Boolean) {
            return Boolean.compare(value.booleanValue(), (Boolean) expected);
        } else if (value.isTextual() && expected != null && !(expected instanceof Number)
                && !(expected instanceof Boolean)) {
            return value.textValue().compareTo(String.valueOf(expected));
        }
        return null;
    }
}
