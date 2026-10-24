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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbInfo;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.VdbValues;

/**
 * @Description: AbstractVdbClient is the REST client of a vector database with the records of its collections as
 *               rows: the id and the vectors are columns, the top-level fields of the payload are columns of their
 *               types (a value which does not fit is NULL there), nested fields are JSON, and the whole payload is a
 *               JSON column which keeps every value.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public abstract class AbstractVdbClient implements VdbClient {

    protected final VdbSource source;
    protected final RestClient rest;

    protected AbstractVdbClient(VdbSource source) {
        this.source = source;
        this.rest = new RestClient(source.getUrl(), source.isCertificateTrusted(), source.getTimeoutSeconds());
        rest.accessDeniedHint(getAccessDeniedHint(source));
    }

    /**
     * Hint of a refused request: credentials are missing, or they are refused or may not read
     */
    static String getAccessDeniedHint(VdbSource source) {
        return source.hasCredentials() ? "the API key, or the user and the password, are refused or may not read"
                : "no API key or user is given, the server requires credentials";
    }

    /**
     * Overview of the defaults with the url and the authentication of the source
     */
    @Override
    public Map<String, Map<String, String>> getOverview() {
        Map<String, Map<String, String>> sections = VdbClient.super.getOverview();
        Map<String, String> server = sections.get("Server");
        server.put("URL", source.getUrl());
        server.put("Authentication", StringUtils.isNotBlank(source.getApiKey()) ? "API key"
                : StringUtils.isNotBlank(source.getUsername()) ? "User " + source.getUsername() : "None");
        if (StringUtils.isNotBlank(source.getDatabase())) {
            server.put("Database", source.getDatabase());
        }
        return sections;
    }

    @Override
    public Map<String, Map<String, String>> getHealth() {
        return VdbClient.super.getHealth();
    }

    /**
     * Section of the overview or of the health, created when it is missing
     */
    protected static Map<String, String> section(Map<String, Map<String, String>> sections, String name) {
        return sections.computeIfAbsent(name, key -> new LinkedHashMap<>());
    }

    @Override
    public VdbType getType() {
        return source.getType();
    }

    protected static String encode(String name) {
        return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Adds the column of the whole payload
     */
    protected static void addPayloadField(VdbCollection collection) {
        VdbField payload = new VdbField(collection.getUnusedName(VdbField.PAYLOAD), VdbField.Kind.JSON);
        payload.setSourceName(VdbField.PAYLOAD);
        payload.setRemarks("Payload of the record as JSON");
        collection.addField(payload);
    }

    /**
     * Row of a record: the values of the id and of the vectors, the scalar columns taken from the payload, the
     * payload as JSON
     *
     * @param values  values of the id, of the vectors and of other columns, by the names of the columns
     * @param payload fields of the record which are not its id and its vectors, a JSON object, or null
     */
    protected static Map<String, Object> toRow(VdbCollection collection, Map<String, Object> values,
                                               JsonNode payload) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (VdbField field : collection.getFields()) {
            if (values.containsKey(field.getName())) {
                row.put(field.getName(), values.get(field.getName()));
            } else if (field.getKind() == VdbField.Kind.JSON && VdbField.PAYLOAD.equals(field.getSourceName())) {
                row.put(field.getName(), payload != null && payload.isObject() && payload.size() > 0
                        ? VdbValues.toJson(payload) : null);
            } else if (!field.isVector() && !field.isPrimaryKey()) {
                row.put(field.getName(), payload != null ? toValue(payload.get(field.getSourceName()), field) : null);
            } else {
                row.put(field.getName(), null);
            }
        }
        return row;
    }

    /**
     * Value of a JSON value for a column, NULL if it does not fit, e.g. an array of a number column
     */
    protected static Object toValue(JsonNode value, VdbField field) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        switch (field.getKind()) {
            case BOOLEAN:
                return value.isBoolean() ? value.booleanValue() : null;
            case SMALLINT:
            case INTEGER:
                return value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
            case BIGINT:
                return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
            case REAL:
                return value.isNumber() ? value.floatValue() : null;
            case DOUBLE:
                return value.isNumber() ? value.doubleValue() : null;
            case VARCHAR:
            case TEXT:
                // Numbers and booleans of a field of more types are text
                return value.isValueNode() ? value.asText() : VdbValues.toJson(value);
            case TIMESTAMP:
                try {
                    return value.isTextual() ? OffsetDateTime.parse(value.textValue()) : null;
                } catch (DateTimeParseException e) {
                    return null;
                }
            case BINARY:
                try {
                    return value.isTextual() ? Base64.getDecoder().decode(value.textValue()) : null;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            case JSON:
                return VdbValues.toJson(value);
            default:
                return value.isValueNode() ? value.asText() : VdbValues.toJson(value);
        }
    }

    /**
     * Text of a JSON node of a response, null if it is missing
     */
    protected static String text(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && !value.isNull() ? value.asText() : null;
    }

    protected static String required(String value, String name) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(name + " must be given");
        }
        return value;
    }

    /**
     * Filter of operators ($eq, $gte, $in ...) of Pinecone and Chroma: the native filter as JSON, or the conditions
     * joined by $and, null without conditions
     */
    protected static JsonNode toOperatorFilter(VdbCollection collection, VdbQuery query, String database) {
        if (query.isNative()) {
            try {
                return RestClient.getMapper().readTree(query.getNativeFilter());
            } catch (IOException e) {
                throw new VdbException("Filter of " + database + " is not JSON: " + e.getMessage(), e);
            }
        }
        if (query.getConditions().isEmpty()) {
            return null;
        }
        ArrayNode and = RestClient.getMapper().createArrayNode();
        for (VdbQuery.Condition condition : query.getConditions()) {
            String name = collection.findField(condition.getField()).map(VdbField::getSourceName)
                    .orElse(condition.getField());
            String operator = "$" + condition.getOperator().name().toLowerCase(Locale.ENGLISH)
                    .replace("ge", "gte").replace("le", "lte");
            and.addObject().putObject(name).set(operator, RestClient.getMapper().valueToTree(condition.getValue()));
        }
        if (and.size() == 1) {
            return and.get(0);
        }
        ObjectNode filter = RestClient.getMapper().createObjectNode();
        filter.set("$and", and);
        return filter;
    }
}
