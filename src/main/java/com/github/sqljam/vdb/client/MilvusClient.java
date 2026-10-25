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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.utils.RestException;
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
 * @Description: MilvusClient reads Milvus 2.4 and later by its REST API v2 with a bearer token (user:password or an
 *               API key). Collections have a schema: scalar fields, JSON, arrays, dense vectors (FloatVector,
 *               Float16Vector, BFloat16Vector, Int8Vector, BinaryVector) and sparse vectors. Fields of the dynamic
 *               field ($meta) are kept by the payload column. Rows are read by their primary key (id &gt; last
 *               id), offset and limit of a query are limited to 16384, with the strong consistency level, so that
 *               the rows of the last writes are read.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class MilvusClient extends AbstractVdbClient {

    static final String PRIMARY_KEY = "primaryKey";
    static final String PRIMARY_KEY_TEXT = "primaryKeyText";
    static final String DYNAMIC = "dynamic";
    static final String MILVUS_TYPE = "milvusType";
    static final String PARTITIONS = "partitions";
    /**
     * Code of a request without valid credentials
     */
    static final int AUTHENTICATION_ERROR = 1800;
    /**
     * Rows of a query at most, offset and limit together
     */
    static final int MAX_QUERY_WINDOW = 16384;

    public MilvusClient(VdbSource source) {
        super(source);
        String token = StringUtils.isNotBlank(source.getApiKey()) ? source.getApiKey()
                : StringUtils.isNotBlank(source.getUsername()) ? source.getUsername() + ":"
                + StringUtils.defaultString(source.getPassword()) : null;
        rest.header("Authorization", token != null ? "Bearer " + token : null);
    }

    private JsonNode call(String path, ObjectNode body) {
        if (StringUtils.isNotBlank(source.getDatabase())) {
            body.put("dbName", source.getDatabase());
        }
        JsonNode response = rest.post("/v2/vectordb" + path, body);
        int code = response.path("code").asInt(0);
        String message = StringUtils.defaultString(text(response, "message"));
        if (code == AUTHENTICATION_ERROR || StringUtils.containsIgnoreCase(message, "privilege")
                || StringUtils.containsIgnoreCase(message, "permission denied")) {
            // Errors of credentials are answered with HTTP 200 too, they are 401 and 403 like other databases
            throw new RestException(String.format("Milvus %s: %s (code %d, %s)", path, message, code,
                    rest.getAccessDeniedHint()), code == AUTHENTICATION_ERROR ? 401 : 403, null);
        }
        if (code != 0) {
            // Errors of Milvus are answered with HTTP 200 and a code
            throw new VdbException(String.format("Milvus %s: %s (code %d)", path, text(response, "message"), code));
        }
        return response.path("data");
    }

    private static ObjectNode body(String collection) {
        ObjectNode body = RestClient.getMapper().createObjectNode();
        if (collection != null) {
            body.put("collectionName", collection);
        }
        return body;
    }

    /**
     * Version of the server, reported by its health endpoint of the REST API
     */
    @Override
    public String getVersion() {
        try {
            JsonNode response = rest.get("/v2/vectordb/server/version");
            String version = text(response.path("data"), "version");
            if (version != null) {
                return version;
            }
        } catch (RuntimeException e) {
            // Servers without the endpoint
        }
        call("/collections/list", body(null));
        return "";
    }

    @Override
    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        call("/collections/list", body(null)).forEach(node -> names.add(node.asText()));
        return names;
    }

    @Override
    public VdbCollection describe(String name) {
        JsonNode data = call("/collections/describe", body(name));
        VdbCollection collection = new VdbCollection(name);
        collection.setRemarks(StringUtils.trimToNull(text(data, "description")));
        Map<String, JsonNode> indexes = getIndexes(name);
        List<String> partitions = new ArrayList<>();
        try {
            call("/partitions/list", body(name)).forEach(partition -> partitions.add(partition.asText()));
        } catch (RestException e) {
            // Partitions are extra, a server or a proxy without the route reads the collection whole
        }
        collection.getProperties().put(PARTITIONS, partitions);
        for (JsonNode field : data.path("fields")) {
            VdbField column = toField(field);
            if (column == null) {
                continue;
            }
            JsonNode index = indexes.get(column.getName());
            if (column.isVector() && index != null) {
                column.setVectorIndex(toVectorIndex(index));
            }
            if (column.isPrimaryKey()) {
                collection.getProperties().put(PRIMARY_KEY, column.getName());
                collection.getProperties().put(PRIMARY_KEY_TEXT, column.getKind() == VdbField.Kind.VARCHAR);
            }
            collection.addField(column);
        }
        collection.getProperties().put(DYNAMIC, data.path("enableDynamicField").asBoolean(false));
        if (collection.getPrimaryKeyFields().isEmpty()) {
            throw new VdbException("Collection " + name + " of Milvus has no primary key");
        }
        addPayloadField(collection);
        collection.setCount(count(collection, VdbQuery.all()));
        return collection;
    }

    /**
     * Column of a field of a collection, null for a field which keeps no values of its own (a function output)
     */
    static VdbField toField(JsonNode field) {
        String name = text(field, "name");
        String type = StringUtils.defaultString(text(field, "type"));
        Map<String, String> params = new LinkedHashMap<>();
        field.path("params").forEach(param -> params.put(text(param, "key"), text(param, "value")));
        int dimensions = Integer.parseInt(params.getOrDefault("dim", "0"));
        VdbField column;
        switch (type) {
            case "Bool":
                column = new VdbField(name, VdbField.Kind.BOOLEAN);
                break;
            case "Int8":
            case "Int16":
                column = new VdbField(name, VdbField.Kind.SMALLINT);
                break;
            case "Int32":
                column = new VdbField(name, VdbField.Kind.INTEGER);
                break;
            case "Int64":
                column = new VdbField(name, VdbField.Kind.BIGINT);
                break;
            case "Float":
                column = new VdbField(name, VdbField.Kind.REAL);
                break;
            case "Double":
                column = new VdbField(name, VdbField.Kind.DOUBLE);
                break;
            case "VarChar":
                column = VdbField.varchar(name, Integer.parseInt(params.getOrDefault("max_length", "65535")));
                if (column.getSize() > 4000) {
                    column.setKind(VdbField.Kind.TEXT);
                }
                break;
            case "String":
            case "Text":
                column = new VdbField(name, VdbField.Kind.TEXT);
                break;
            case "Timestamptz":
                column = new VdbField(name, VdbField.Kind.TIMESTAMP);
                break;
            case "JSON":
            case "Array":
            case "Geometry":
            case "Struct":
                column = new VdbField(name, VdbField.Kind.JSON);
                break;
            case "FloatVector":
                column = VdbField.vector(name, VectorType.dense(VectorType.Element.FLOAT32, dimensions));
                break;
            case "Float16Vector":
            case "BFloat16Vector":
                column = VdbField.vector(name, VectorType.dense(VectorType.Element.FLOAT16, dimensions));
                break;
            case "Int8Vector":
                column = VdbField.vector(name, VectorType.dense(VectorType.Element.INT8, dimensions));
                break;
            case "BinaryVector":
                column = VdbField.vector(name, VectorType.dense(VectorType.Element.BINARY, dimensions));
                break;
            case "SparseFloatVector":
                column = VdbField.vector(name, new VectorType(VectorType.Element.FLOAT32, 0, true));
                break;
            default:
                column = new VdbField(name, VdbField.Kind.TEXT);
                break;
        }
        column.setPrimaryKey(field.path("primaryKey").asBoolean(false));
        column.setNullable(field.path("nullable").asBoolean(false) && !column.isPrimaryKey());
        column.setRemarks(StringUtils.trimToNull(text(field, "description")));
        // The type of Milvus is kept to read the values, the name of the column is the name of the field
        column.getProperties().put(MILVUS_TYPE, type);
        column.setSourceName(name);
        return column;
    }

    private Map<String, JsonNode> getIndexes(String collection) {
        Map<String, JsonNode> indexes = new LinkedHashMap<>();
        for (JsonNode indexName : call("/indexes/list", body(collection))) {
            ObjectNode body = body(collection);
            body.put("indexName", indexName.asText());
            for (JsonNode index : call("/indexes/describe", body)) {
                indexes.put(text(index, "fieldName"), index);
            }
        }
        return indexes;
    }

    /**
     * Vector index of a Milvus index: HNSW (HNSW_SQ, HNSW_PQ ...) or IVF (IVF_FLAT, IVF_SQ8, IVF_PQ ...) of the
     * metric, DISKANN is a graph like HNSW. Indexes of sparse vectors and of other metrics are not vector indexes.
     */
    static VectorIndex toVectorIndex(JsonNode index) {
        String type = StringUtils.defaultString(text(index, "indexType")).toUpperCase(Locale.ENGLISH);
        VectorIndex.Distance distance = toDistance(text(index, "metricType"));
        if (distance == null) {
            return null;
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (JsonNode param : index.path("indexParams")) {
            if ("params".equals(text(param, "key"))) {
                try {
                    JsonNode values = RestClient.getMapper().readTree(text(param, "value"));
                    putOption(options, "m", values.get("M"));
                    putOption(options, "ef_construction", values.get("efConstruction"));
                    putOption(options, "lists", values.get("nlist"));
                } catch (IOException e) {
                    // Options which are not JSON are left out
                }
            }
        }
        if (type.startsWith("HNSW") || type.equals("DISKANN")) {
            return new VectorIndex(VectorIndex.Kind.HNSW, distance, options);
        } else if (type.startsWith("IVF") || type.startsWith("SCANN")) {
            return new VectorIndex(VectorIndex.Kind.IVF, distance, options);
        }
        return null;
    }

    private static void putOption(Map<String, String> options, String name, JsonNode value) {
        if (value != null && value.isNumber()) {
            options.put(name, value.asText());
        }
    }

    static VectorIndex.Distance toDistance(String metric) {
        switch (StringUtils.defaultString(metric).toUpperCase(Locale.ENGLISH)) {
            case "COSINE":
                return VectorIndex.Distance.COSINE;
            case "L2":
                return VectorIndex.Distance.EUCLIDEAN;
            case "IP":
                return VectorIndex.Distance.DOT;
            case "HAMMING":
                return VectorIndex.Distance.HAMMING;
            case "JACCARD":
                return VectorIndex.Distance.JACCARD;
            default:
                return null;
        }
    }

    /**
     * Load states of the collections, a collection which is not loaded is not read
     */
    @Override
    public Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = super.getHealth();
        Map<String, String> loads = section(sections, "Load states");
        for (String collection : getCollections()) {
            loads.put(collection, VdbInfo.read(() -> text(call("/collections/get_load_state", body(collection)),
                    "loadState")));
        }
        return sections;
    }

    /**
     * Databases of the server
     */
    @Override
    public List<String> getDatabases() {
        List<String> names = new ArrayList<>();
        call("/databases/list", body(null)).forEach(name -> names.add(name.asText()));
        return names;
    }

    @Override
    public List<String> listPartitions(String collection) {
        List<String> partitions = new ArrayList<>();
        call("/partitions/list", body(collection)).forEach(partition -> partitions.add(partition.asText()));
        return partitions;
    }

    /**
     * Partitions of the collection, _default included
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<String> getPartitions(VdbCollection collection) {
        return (List<String>) collection.getProperties().getOrDefault(PARTITIONS, List.of());
    }

    /**
     * Partitions of the query, all partitions without them
     */
    private void addPartitions(ObjectNode body, VdbCollection collection, VdbQuery query) {
        if (query.getPartitions() != null && !query.getPartitions().isEmpty()) {
            ArrayNode names = body.putArray("partitionNames");
            query.selectPartitions(getPartitions(collection)).forEach(names::add);
        }
    }

    @Override
    public long count(VdbCollection collection, VdbQuery query) {
        if (query.isNearest()) {
            return query.getLimit();
        }
        ObjectNode body = body(collection.getName());
        body.put("filter", toFilter(collection, query));
        body.putArray("outputFields").add("count(*)");
        addPartitions(body, collection, query);
        body.put("consistencyLevel", "Strong");
        JsonNode data = call("/entities/query", body);
        long count = data.size() > 0 ? data.get(0).path("count(*)").asLong() : 0;
        return query.getLimit() > 0 ? Math.min(count, query.getLimit()) : count;
    }

    @Override
    public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
        if (query.isNearest()) {
            consumer.accept(search(collection, query, pageSize), true);
            return;
        }
        String primaryKey = (String) collection.getProperties().get(PRIMARY_KEY);
        boolean textKey = Boolean.TRUE.equals(collection.getProperties().get(PRIMARY_KEY_TEXT));
        long remaining = query.getLimit() > 0 ? query.getLimit() : Long.MAX_VALUE;
        String filter = toFilter(collection, query);
        Object last = null;
        int size = Math.min(pageSize, MAX_QUERY_WINDOW);
        while (remaining > 0) {
            ObjectNode body = body(collection.getName());
            String after = last == null ? null : textKey ? String.format("%s > \"%s\"", primaryKey,
                    last.toString().replace("\\", "\\\\").replace("\"", "\\\"")) : primaryKey + " > " + last;
            body.put("filter", combine(filter, after));
            body.putArray("outputFields").add("*");
            addPartitions(body, collection, query);
            body.put("limit", (int) Math.min(size, remaining));
            body.put("consistencyLevel", "Strong");
            List<Map<String, Object>> rows = toRows(collection, call("/entities/query", body));
            // Rows of a query by the primary key are in the order of the key
            rows.sort((a, b) -> compareKeys(a.get(primaryKey), b.get(primaryKey)));
            remaining -= rows.size();
            boolean lastPage = rows.size() < size || remaining <= 0;
            if (!rows.isEmpty()) {
                last = rows.get(rows.size() - 1).get(primaryKey);
            }
            if (!consumer.accept(rows, lastPage) || lastPage) {
                return;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Object a, Object b) {
        return a instanceof Comparable && b != null ? ((Comparable) a).compareTo(b) : 0;
    }

    private List<Map<String, Object>> search(VdbCollection collection, VdbQuery query, int pageSize) {
        ObjectNode body = body(collection.getName());
        ArrayNode data = body.putArray("data");
        if (query.getNearestVector() != null) {
            ArrayNode vector = data.addArray();
            for (float value : query.getNearestVector()) {
                vector.add(value);
            }
        } else {
            // The vector of a record is searched
            String primaryKey = (String) collection.getProperties().get(PRIMARY_KEY);
            ObjectNode get = body(collection.getName());
            get.set("id", RestClient.getMapper().valueToTree(List.of(query.getNearestId())));
            get.putArray("outputFields").add(query.getNearestField());
            JsonNode records = call("/entities/get", get);
            if (records.size() == 0) {
                throw new VdbException("No record of " + primaryKey + " " + query.getNearestId());
            }
            data.add(records.get(0).get(query.getNearestField()));
        }
        body.put("annsField", query.getNearestField());
        body.put("limit", query.getLimit() > 0 ? (int) query.getLimit() : pageSize);
        body.putArray("outputFields").add("*");
        String filter = toFilter(collection, query);
        if (StringUtils.isNotBlank(filter)) {
            body.put("filter", filter);
        }
        body.put("consistencyLevel", "Strong");
        addPartitions(body, collection, query);
        return toRows(collection, call("/entities/search", body));
    }

    List<Map<String, Object>> toRows(VdbCollection collection, JsonNode records) {
        boolean dynamic = Boolean.TRUE.equals(collection.getProperties().get(DYNAMIC));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode record : records) {
            Map<String, Object> values = new LinkedHashMap<>();
            ObjectNode payload = RestClient.getMapper().createObjectNode();
            for (VdbField field : collection.getFields()) {
                if (VdbField.PAYLOAD.equals(field.getSourceName()) && field.getKind() == VdbField.Kind.JSON
                        && !record.has(field.getName())) {
                    continue;
                }
                JsonNode value = normalize(record.get(field.getSourceName()), field);
                if (field.isVector()) {
                    values.put(field.getName(), toVectorText(value, field));
                } else if (field.isPrimaryKey()) {
                    values.put(field.getName(), value == null ? null : value.isIntegralNumber() ? (Object)
                            value.longValue() : value.asText());
                } else {
                    values.put(field.getName(), toValue(value, field));
                    if (value != null) {
                        payload.set(field.getSourceName(), value);
                    }
                }
            }
            if (dynamic) {
                // Keys of the dynamic field are the keys of the record which are not fields
                List<String> names = collection.getFields().stream().map(VdbField::getSourceName)
                        .collect(Collectors.toList());
                record.fields().forEachRemaining(entry -> {
                    if (!names.contains(entry.getKey()) && !"distance".equals(entry.getKey())) {
                        payload.set(entry.getKey(), entry.getValue());
                    }
                });
            }
            rows.add(toRow(collection, values, payload));
        }
        return rows;
    }

    /**
     * Value of a scalar field as JSON: REST returns JSON fields as text and arrays as {"Data": {"StringData":
     * {"data": [..]}}} of its protocol buffers
     */
    static JsonNode normalize(JsonNode value, VdbField field) {
        if (value == null || field.isVector()) {
            return value;
        }
        String type = (String) field.getProperties().get(MILVUS_TYPE);
        if ("JSON".equals(type) && value.isTextual()) {
            try {
                return RestClient.getMapper().readTree(value.textValue());
            } catch (IOException e) {
                return value;
            }
        }
        if ("Array".equals(type) && value.path("Data").isObject() && value.path("Data").size() == 1) {
            JsonNode data = value.path("Data").elements().next().path("data");
            return data.isArray() ? data : RestClient.getMapper().createArrayNode();
        }
        return value;
    }

    /**
     * Text of a vector of a record: dense vectors are arrays of numbers, 16-bit floats and int8 are base64 binaries,
     * sparse vectors are objects of indexes
     */
    static String toVectorText(JsonNode value, VdbField field) {
        if (value == null || value.isNull()) {
            return null;
        }
        String type = String.valueOf(field.getProperties().get(MILVUS_TYPE));
        switch (type) {
            case "Float16Vector":
                return value.isTextual() ? VdbValues.toDenseText(VdbValues.decodeHalves(value.textValue(), false))
                        : VdbValues.toDenseText(value);
            case "BFloat16Vector":
                return value.isTextual() ? VdbValues.toDenseText(VdbValues.decodeHalves(value.textValue(), true))
                        : VdbValues.toDenseText(value);
            case "Int8Vector":
                if (value.isTextual()) {
                    byte[] bytes = Base64.getDecoder().decode(value.textValue());
                    float[] floats = new float[bytes.length];
                    for (int i = 0; i < bytes.length; i++) {
                        floats[i] = bytes[i];
                    }
                    return VdbValues.toDenseText(floats);
                }
                return VdbValues.toDenseText(value);
            case "BinaryVector":
                return value.isTextual() ? VdbValues.toByteText(Base64.getDecoder().decode(value.textValue()))
                        : VdbValues.toJson(value);
            case "SparseFloatVector":
                Map<Long, Float> elements = VdbValues.getSparseElements(value);
                long dimensions = elements.keySet().stream().mapToLong(index -> index + 1).max().orElse(1);
                return VdbValues.toSparseText(elements, dimensions);
            default:
                return VdbValues.toDenseText(value);
        }
    }

    /**
     * Expression of Milvus: the native expression, or the conditions joined by and
     */
    static String toFilter(VdbCollection collection, VdbQuery query) {
        if (query.isNative()) {
            return query.getNativeFilter().trim();
        }
        List<String> conditions = new ArrayList<>();
        for (VdbQuery.Condition condition : query.getConditions()) {
            String field = collection.findField(condition.getField()).map(VdbField::getSourceName)
                    .orElse(condition.getField());
            String value = literal(condition.getValue());
            switch (condition.getOperator()) {
                case EQ:
                    conditions.add(field + " == " + value);
                    break;
                case NE:
                    conditions.add(field + " != " + value);
                    break;
                case GT:
                    conditions.add(field + " > " + value);
                    break;
                case GE:
                    conditions.add(field + " >= " + value);
                    break;
                case LT:
                    conditions.add(field + " < " + value);
                    break;
                case LE:
                    conditions.add(field + " <= " + value);
                    break;
                default:
                    conditions.add(field + " in " + value);
                    break;
            }
        }
        return String.join(" and ", conditions);
    }

    private static String combine(String filter, String after) {
        if (StringUtils.isBlank(filter)) {
            return StringUtils.defaultString(after);
        }
        return after == null ? filter : "(" + filter + ") and " + after;
    }

    static String literal(Object value) {
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<>();
            ((Iterable<?>) value).forEach(item -> items.add(literal(item)));
            return "[" + String.join(", ", items) + "]";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
