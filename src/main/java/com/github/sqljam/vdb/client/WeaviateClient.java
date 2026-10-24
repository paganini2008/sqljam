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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
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
 * @Description: WeaviateClient reads Weaviate 1.24 and later by its REST API and GraphQL with a bearer API key. A
 *               class is a collection: the UUID is the id, named vectors (or the vector of a class without named
 *               vectors, column vector) are vectors of their index (HNSW, flat or dynamic) and distance, properties
 *               are columns of their data types. The schema has no dimensions of vectors, they are read of an
 *               object. Objects are read by the cursor of the REST API, filters, searches and tenants by GraphQL.
 *               Each tenant of a multi tenant class is read, the tenant is a column of the key.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class WeaviateClient extends AbstractVdbClient {

    static final String UNNAMED_VECTOR = "vector";
    static final String TENANT = "tenant";
    static final String NAMED = "named";
    static final String TENANTS = "tenants";
    static final String PROPERTIES = "properties";
    /**
     * Objects of a GraphQL query at most (QUERY_MAXIMUM_RESULTS of the server, 10000 by default)
     */
    static final int MAX_QUERY_RESULTS = 10000;

    public WeaviateClient(VdbSource source) {
        super(source);
        rest.header("Authorization", StringUtils.isNotBlank(source.getApiKey()) ? "Bearer " + source.getApiKey()
                : null);
    }

    @Override
    public String getVersion() {
        return text(rest.get("/v1/meta"), "version");
    }

    @Override
    public Map<String, Map<String, String>> getOverview() {
        Map<String, Map<String, String>> sections = super.getOverview();
        section(sections, "Server").put("Modules", VdbInfo.read(() -> {
            List<String> modules = new ArrayList<>();
            rest.get("/v1/meta").path("modules").fieldNames().forEachRemaining(modules::add);
            return modules.isEmpty() ? "None" : String.join(", ", modules);
        }));
        return sections;
    }

    /**
     * Readiness and liveness probes, the status, objects and shards of each node
     */
    @Override
    public Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = super.getHealth();
        Map<String, String> health = section(sections, "Weaviate");
        health.put("Ready", VdbInfo.read(() -> {
            rest.get("/v1/.well-known/ready");
            return "Yes";
        }));
        health.put("Live", VdbInfo.read(() -> {
            rest.get("/v1/.well-known/live");
            return "Yes";
        }));
        Map<String, String> nodes = section(sections, "Nodes");
        String error = VdbInfo.read(() -> {
            for (JsonNode node : rest.get("/v1/nodes?output=verbose").path("nodes")) {
                nodes.put(text(node, "name"), String.format("%s, version %s, %d objects, %d shards", text(node,
                        "status"), text(node, "version"), node.path("stats").path("objectCount").asLong(),
                        node.path("stats").path("shardCount").asLong()));
            }
            return "";
        });
        if (error.startsWith(VdbInfo.UNAVAILABLE)) {
            nodes.put("Nodes", error);
        }
        return sections;
    }

    @Override
    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        rest.get("/v1/schema").path("classes").forEach(node -> names.add(text(node, "class")));
        return names;
    }

    @Override
    public VdbCollection describe(String name) {
        JsonNode schema = rest.get("/v1/schema/" + encode(name));
        VdbCollection collection = new VdbCollection(name);
        collection.setRemarks(StringUtils.trimToNull(text(schema, "description")));
        List<String> tenants = new ArrayList<>();
        if (schema.path("multiTenancyConfig").path("enabled").asBoolean(false)) {
            rest.get("/v1/schema/" + encode(name) + "/tenants").forEach(tenant -> tenants.add(text(tenant,
                    "name")));
            VdbField tenant = VdbField.varchar(TENANT, 64);
            tenant.setPrimaryKey(true);
            tenant.setNullable(false);
            collection.addField(tenant);
        }
        collection.getProperties().put(TENANTS, tenants);
        VdbField id = VdbField.varchar("id", 36);
        id.setPrimaryKey(true);
        id.setNullable(false);
        collection.addField(id);

        Map<String, Integer> dimensions = getDimensions(name, tenants);
        JsonNode vectorConfig = schema.path("vectorConfig");
        boolean named = vectorConfig.isObject() && vectorConfig.size() > 0;
        collection.getProperties().put(NAMED, named);
        if (named) {
            vectorConfig.fields().forEachRemaining(entry -> collection.addField(toVectorField(entry.getKey(),
                    text(entry.getValue(), "vectorIndexType"), entry.getValue().path("vectorIndexConfig"),
                    dimensions.getOrDefault(entry.getKey(), 0))));
        } else if (dimensions.containsKey(UNNAMED_VECTOR) || schema.has("vectorIndexConfig")) {
            collection.addField(toVectorField(UNNAMED_VECTOR, text(schema, "vectorIndexType"),
                    schema.path("vectorIndexConfig"), dimensions.getOrDefault(UNNAMED_VECTOR, 0)));
        }
        List<JsonNode> properties = new ArrayList<>();
        for (JsonNode property : schema.path("properties")) {
            properties.add(property);
            VdbField field = toField(collection.getUnusedName(text(property, "name")), property);
            field.setSourceName(text(property, "name"));
            collection.addField(field);
        }
        collection.getProperties().put(PROPERTIES, properties);
        addPayloadField(collection);
        collection.setCount(count(collection, VdbQuery.all()));
        return collection;
    }

    /**
     * Dimensions of the vectors, read of an object
     */
    private Map<String, Integer> getDimensions(String name, List<String> tenants) {
        Map<String, Integer> dimensions = new LinkedHashMap<>();
        for (String tenant : tenants.isEmpty() ? List.of("") : tenants) {
            JsonNode objects = rest.get("/v1/objects?class=" + encode(name) + "&limit=1&include=vector"
                    + (tenant.isEmpty() ? "" : "&tenant=" + encode(tenant))).path("objects");
            if (objects.size() > 0) {
                JsonNode object = objects.get(0);
                object.path("vectors").fields().forEachRemaining(entry -> dimensions.put(entry.getKey(),
                        entry.getValue().size()));
                if (object.path("vector").isArray() && object.path("vector").size() > 0) {
                    dimensions.put(UNNAMED_VECTOR, object.path("vector").size());
                }
                break;
            }
        }
        return dimensions;
    }

    /**
     * Column of a vector, JSON of a multivector. HNSW and dynamic indexes are HNSW, flat indexes are scans.
     */
    static VdbField toVectorField(String name, String indexType, JsonNode config, int dimensions) {
        if (config.path("multivector").path("enabled").asBoolean(false)) {
            VdbField field = new VdbField(name, VdbField.Kind.JSON);
            field.setRemarks("Multivector of Weaviate");
            return field;
        }
        VdbField field = VdbField.vector(name, VectorType.dense(VectorType.Element.FLOAT32, dimensions));
        String type = StringUtils.defaultIfBlank(indexType, "hnsw").toLowerCase(Locale.ENGLISH);
        JsonNode hnsw = "dynamic".equals(type) ? config.path("hnsw") : config;
        VectorIndex.Distance distance = toDistance(StringUtils.defaultIfBlank(text(config, "distance"),
                text(hnsw, "distance")));
        if (distance != null && !"flat".equals(type)) {
            Map<String, String> options = new LinkedHashMap<>();
            if (hnsw.path("maxConnections").asInt(0) > 0) {
                options.put("m", hnsw.path("maxConnections").asText());
            }
            if (hnsw.path("efConstruction").asInt(0) > 0) {
                options.put("ef_construction", hnsw.path("efConstruction").asText());
            }
            field.setVectorIndex(new VectorIndex(VectorIndex.Kind.HNSW, distance, options));
        }
        return field;
    }

    static VectorIndex.Distance toDistance(String distance) {
        switch (StringUtils.defaultIfBlank(distance, "cosine").toLowerCase(Locale.ENGLISH)) {
            case "cosine":
                return VectorIndex.Distance.COSINE;
            case "l2-squared":
                return VectorIndex.Distance.EUCLIDEAN;
            case "dot":
                return VectorIndex.Distance.DOT;
            case "manhattan":
                return VectorIndex.Distance.MANHATTAN;
            case "hamming":
                return VectorIndex.Distance.HAMMING;
            default:
                return null;
        }
    }

    /**
     * Column of a property of its data type, arrays and objects are JSON
     */
    static VdbField toField(String name, JsonNode property) {
        String dataType = StringUtils.defaultString(property.path("dataType").path(0).asText());
        VdbField field;
        switch (dataType) {
            case "text":
            case "string":
                field = new VdbField(name, VdbField.Kind.TEXT);
                break;
            case "int":
                field = new VdbField(name, VdbField.Kind.BIGINT);
                break;
            case "number":
                field = new VdbField(name, VdbField.Kind.DOUBLE);
                break;
            case "boolean":
                field = new VdbField(name, VdbField.Kind.BOOLEAN);
                break;
            case "date":
                field = new VdbField(name, VdbField.Kind.TIMESTAMP);
                break;
            case "uuid":
                field = VdbField.varchar(name, 36);
                break;
            case "blob":
                field = new VdbField(name, VdbField.Kind.BINARY);
                break;
            default:
                // Arrays, objects, geo coordinates, phone numbers and references
                field = new VdbField(name, VdbField.Kind.JSON);
                break;
        }
        field.setRemarks(StringUtils.trimToNull(text(property, "description")));
        return field;
    }

    @SuppressWarnings("unchecked")
    @Override
    public long count(VdbCollection collection, VdbQuery query) {
        if (query.isNearest()) {
            return query.getLimit();
        }
        long count = 0;
        for (String tenant : getParts(collection, query)) {
            String arguments = arguments(tenant, toWhere(collection, query), null);
            JsonNode result = graphQl(String.format("{ Aggregate { %s%s { meta { count } } } }", collection.getName(),
                    arguments.isEmpty() ? "" : "(" + arguments + ")"));
            JsonNode aggregate = result.path("Aggregate").path(collection.getName());
            count += aggregate.size() > 0 ? aggregate.get(0).path("meta").path("count").asLong() : 0;
        }
        return query.getLimit() > 0 ? Math.min(count, query.getLimit()) : count;
    }

    @Override
    public List<String> listPartitions(String collection) {
        List<String> tenants = new ArrayList<>();
        if (rest.get("/v1/schema/" + encode(collection)).path("multiTenancyConfig").path("enabled").asBoolean(false)) {
            rest.get("/v1/schema/" + encode(collection) + "/tenants").forEach(tenant -> tenants.add(text(tenant,
                    "name")));
        }
        return tenants;
    }

    /**
     * Tenants of a multi-tenant class
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<String> getPartitions(VdbCollection collection) {
        return (List<String>) collection.getProperties().get(TENANTS);
    }

    /**
     * Tenants which are read, the tenants of the query or all, the class itself when it has no tenants
     */
    private List<String> getParts(VdbCollection collection, VdbQuery query) {
        List<String> tenants = getPartitions(collection);
        return tenants.isEmpty() ? List.of("") : query.selectPartitions(tenants);
    }

    private JsonNode graphQl(String query) {
        JsonNode response = rest.post("/v1/graphql", RestClient.getMapper().createObjectNode().put("query", query));
        if (response.path("errors").size() > 0) {
            throw new VdbException("Weaviate: " + response.path("errors").get(0).path("message").asText());
        }
        return response.path("data");
    }

    @SuppressWarnings("unchecked")
    @Override
    public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
        List<String> parts = getParts(collection, query);
        if (parts.isEmpty()) {
            consumer.accept(List.of(), true);
            return;
        }
        long[] remaining = {query.getLimit() > 0 ? query.getLimit() : Long.MAX_VALUE};
        for (int i = 0; i < parts.size() && remaining[0] > 0; i++) {
            String tenant = parts.get(i);
            boolean lastTenant = i == parts.size() - 1;
            boolean[] stopped = {false};
            PageConsumer partConsumer = (records, last) -> {
                remaining[0] -= records.size();
                boolean end = last && lastTenant || remaining[0] <= 0;
                if (!consumer.accept(records, end)) {
                    stopped[0] = true;
                    return false;
                }
                return true;
            };
            if (!query.isFiltered() && !query.isNearest()) {
                readByCursor(collection, tenant, remaining[0], pageSize, partConsumer);
            } else {
                readByGraphQl(collection, query, tenant, remaining[0], pageSize, partConsumer);
            }
            if (stopped[0]) {
                return;
            }
        }
    }

    private void readByCursor(VdbCollection collection, String tenant, long limit, int pageSize,
                              PageConsumer consumer) {
        String after = null;
        long remaining = limit;
        while (remaining > 0) {
            int size = (int) Math.min(pageSize, remaining);
            String path = "/v1/objects?class=" + encode(collection.getName()) + "&limit=" + size + "&include=vector"
                    + (tenant.isEmpty() ? "" : "&tenant=" + encode(tenant)) + (after == null ? "" : "&after="
                    + encode(after));
            JsonNode objects = rest.get(path).path("objects");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode object : objects) {
                rows.add(toRow(collection, tenant, text(object, "id"), object.path("vectors"), object.path("vector"),
                        object.path("properties")));
                after = text(object, "id");
            }
            remaining -= rows.size();
            boolean last = rows.size() < size || remaining <= 0;
            if (!consumer.accept(rows, last) || last) {
                return;
            }
        }
    }

    /**
     * Objects of a filter or a search by GraphQL: pages by offset, QUERY_MAXIMUM_RESULTS of the server limits them
     */
    @SuppressWarnings("unchecked")
    private void readByGraphQl(VdbCollection collection, VdbQuery query, String tenant, long limit, int pageSize,
                               PageConsumer consumer) {
        boolean named = Boolean.TRUE.equals(collection.getProperties().get(NAMED));
        List<JsonNode> properties = (List<JsonNode>) collection.getProperties().get(PROPERTIES);
        StringBuilder selection = new StringBuilder();
        properties.forEach(property -> selection.append(selectionOf(property)).append(' '));
        List<String> vectors = new ArrayList<>();
        collection.getVectorFields().forEach(field -> vectors.add(field.getSourceName()));
        collection.getFields().stream().filter(field -> field.getKind() == VdbField.Kind.JSON
                && "Multivector of Weaviate".equals(field.getRemarks())).forEach(field -> vectors.add(field.getName()));
        String additional = named ? "_additional { id vectors { " + String.join(" ", vectors) + " } }"
                : "_additional { id vector }";
        String where = toWhere(collection, query);
        String search = query.isNearest() ? toSearch(query, named) : null;
        long remaining = Math.min(limit, query.isNearest() && query.getLimit() <= 0 ? pageSize : limit);
        int offset = 0;
        while (remaining > 0) {
            int size = (int) Math.min(Math.min(pageSize, remaining), MAX_QUERY_RESULTS - offset);
            if (size <= 0) {
                throw new VdbException("Weaviate reads " + MAX_QUERY_RESULTS + " objects of a filter at most"
                        + " (QUERY_MAXIMUM_RESULTS), please narrow the filter or read the whole class");
            }
            // Pages of a filter are sorted by the ids, the order of a filter alone is not stable
            String arguments = arguments(tenant, where, search) + (search == null ? ", sort: [{ path: [\"_id\"], "
                    + "order: asc }], offset: " + offset : "") + ", limit: " + size;
            JsonNode objects = graphQl(String.format("{ Get { %s(%s) { %s%s } } }", collection.getName(),
                    StringUtils.removeStart(arguments, ", "), selection, additional)).path("Get")
                    .path(collection.getName());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode object : objects) {
                JsonNode extra = object.path("_additional");
                ObjectNode propertiesNode = ((ObjectNode) object.deepCopy());
                propertiesNode.remove("_additional");
                rows.add(toRow(collection, tenant, text(extra, "id"), extra.path("vectors"), extra.path("vector"),
                        propertiesNode));
            }
            remaining -= rows.size();
            offset += rows.size();
            boolean last = rows.size() < size || remaining <= 0 || search != null;
            if (!consumer.accept(rows, last) || last) {
                return;
            }
        }
    }

    /**
     * Selection of a property in GraphQL, objects select their nested properties
     */
    static String selectionOf(JsonNode property) {
        String name = text(property, "name");
        JsonNode nested = property.path("nestedProperties");
        String dataType = property.path("dataType").path(0).asText();
        if (nested.size() > 0) {
            StringBuilder selection = new StringBuilder(name).append(" { ");
            nested.forEach(child -> selection.append(selectionOf(child)).append(' '));
            return selection.append('}').toString();
        }
        if ("geoCoordinates".equals(dataType)) {
            return name + " { latitude longitude }";
        } else if ("phoneNumber".equals(dataType)) {
            return name + " { input internationalFormatted countryCode national nationalFormatted valid }";
        } else if (Character.isUpperCase(dataType.isEmpty() ? 'a' : dataType.charAt(0))) {
            // A reference to another class
            return name + " { ... on " + dataType + " { _additional { id } } }";
        }
        return name;
    }

    private static String arguments(String tenant, String where, String search) {
        StringBuilder arguments = new StringBuilder();
        if (!tenant.isEmpty()) {
            arguments.append(", tenant: ").append(quote(tenant));
        }
        if (where != null) {
            arguments.append(", where: ").append(where);
        }
        if (search != null) {
            arguments.append(", ").append(search);
        }
        return StringUtils.removeStart(arguments.toString(), ", ");
    }

    private static String toSearch(VdbQuery query, boolean named) {
        String target = named ? ", targetVectors: [" + quote(query.getNearestField()) + "]" : "";
        if (query.getNearestVector() != null) {
            return "nearVector: { vector: " + VdbValues.toDenseText(query.getNearestVector()) + target + " }";
        }
        return "nearObject: { id: " + quote(String.valueOf(query.getNearestId())) + target + " }";
    }

    private Map<String, Object> toRow(VdbCollection collection, String tenant, String id, JsonNode vectors,
                                      JsonNode vector, JsonNode properties) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        if (!tenant.isEmpty()) {
            values.put(TENANT, tenant);
        }
        for (VdbField field : collection.getFields()) {
            if (field.isVector()) {
                JsonNode value = UNNAMED_VECTOR.equals(field.getName()) && vector.isArray() ? vector
                        : vectors.get(field.getSourceName());
                values.put(field.getName(), VdbValues.toDenseText(value));
            } else if ("Multivector of Weaviate".equals(field.getRemarks())) {
                values.put(field.getName(), VdbValues.toJson(vectors.get(field.getName())));
            }
        }
        return toRow(collection, values, properties);
    }

    /**
     * Where filter of GraphQL: the native filter, or the conditions joined by And
     */
    static String toWhere(VdbCollection collection, VdbQuery query) {
        if (query.isNative()) {
            return query.getNativeFilter().trim();
        }
        if (query.getConditions().isEmpty()) {
            return null;
        }
        List<String> operands = new ArrayList<>();
        for (VdbQuery.Condition condition : query.getConditions()) {
            String path = collection.findField(condition.getField()).map(VdbField::getSourceName)
                    .orElse(condition.getField());
            Object value = condition.getValue();
            String operator;
            switch (condition.getOperator()) {
                case EQ:
                    operator = "Equal";
                    break;
                case NE:
                    operator = "NotEqual";
                    break;
                case GT:
                    operator = "GreaterThan";
                    break;
                case GE:
                    operator = "GreaterThanEqual";
                    break;
                case LT:
                    operator = "LessThan";
                    break;
                case LE:
                    operator = "LessThanEqual";
                    break;
                default:
                    operator = "ContainsAny";
                    break;
            }
            operands.add(String.format("{ path: [%s], operator: %s, %s }", quote(path), operator,
                    valueOf(value)));
        }
        return operands.size() == 1 ? operands.get(0) : "{ operator: And, operands: [" + String.join(", ", operands)
                + "] }";
    }

    private static String valueOf(Object value) {
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<>();
            Iterator<?> it = ((Iterable<?>) value).iterator();
            Object first = null;
            while (it.hasNext()) {
                Object item = it.next();
                first = first == null ? item : first;
                items.add(item instanceof Number || item instanceof Boolean ? item.toString() : quote(String.valueOf(
                        item)));
            }
            String key = first instanceof Integer || first instanceof Long ? "valueIntArray"
                    : first instanceof Number ? "valueNumberArray" : first instanceof Boolean ? "valueBooleanArray"
                    : "valueTextArray";
            return key + ": [" + String.join(", ", items) + "]";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "valueInt: " + value;
        } else if (value instanceof Number) {
            return "valueNumber: " + value;
        } else if (value instanceof Boolean) {
            return "valueBoolean: " + value;
        }
        return "valueText: " + quote(String.valueOf(value));
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
