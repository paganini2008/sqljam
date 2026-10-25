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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @Description: PayloadSchema finds the columns of payloads without a schema (Qdrant, Pinecone): every top-level field
 *               of a scalar type is a column of that type, a field of more types is text (numbers and text) or JSON,
 *               objects and arrays are JSON. The whole payload is a JSON column too, so nothing is lost when a value
 *               does not fit its column.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class PayloadSchema {

    /**
     * Length of text up to which a field is VARCHAR, longer text is TEXT
     */
    static final int MAX_VARCHAR_LENGTH = 4000;
    /**
     * Length of VARCHAR at least, values of the next rows of a filtered read may be a little longer
     */
    static final int MIN_VARCHAR_LENGTH = 255;

    private final Map<String, FieldType> fields = new LinkedHashMap<>();
    private long observed;

    private static final class FieldType {
        VdbField.Kind kind;
        int maxLength;
        boolean nullable;
    }

    /**
     * Observes the fields of a payload, a JSON object
     */
    public void observe(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return;
        }
        boolean first = observed == 0;
        observed++;
        Set<String> present = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> it = payload.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            present.add(entry.getKey());
            FieldType type = fields.computeIfAbsent(entry.getKey(), key -> {
                FieldType created = new FieldType();
                // A field which earlier payloads do not have takes NULL
                created.nullable = !first;
                return created;
            });
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                type.nullable = true;
                continue;
            }
            VdbField.Kind kind = kindOf(value);
            type.kind = type.kind == null ? kind : merge(type.kind, kind);
            if (value.isTextual()) {
                type.maxLength = Math.max(type.maxLength, value.textValue().length());
            }
        }
        // Fields which this payload does not have take NULL
        fields.forEach((name, type) -> {
            if (!present.contains(name)) {
                type.nullable = true;
            }
        });
    }

    static VdbField.Kind kindOf(JsonNode value) {
        if (value.isBoolean()) {
            return VdbField.Kind.BOOLEAN;
        } else if (value.isIntegralNumber()) {
            return value.canConvertToInt() ? VdbField.Kind.INTEGER : VdbField.Kind.BIGINT;
        } else if (value.isNumber()) {
            return VdbField.Kind.DOUBLE;
        } else if (value.isTextual()) {
            return VdbField.Kind.VARCHAR;
        }
        return VdbField.Kind.JSON;
    }

    /**
     * Type of a field of two types: integers widen, numbers become doubles, scalars of other types become text
     */
    static VdbField.Kind merge(VdbField.Kind a, VdbField.Kind b) {
        if (a == b) {
            return a;
        }
        if (a == VdbField.Kind.JSON || b == VdbField.Kind.JSON) {
            return VdbField.Kind.JSON;
        }
        boolean integral = (a == VdbField.Kind.INTEGER || a == VdbField.Kind.BIGINT)
                && (b == VdbField.Kind.INTEGER || b == VdbField.Kind.BIGINT);
        if (integral) {
            return VdbField.Kind.BIGINT;
        }
        boolean numeric = a != VdbField.Kind.BOOLEAN && a != VdbField.Kind.VARCHAR && b != VdbField.Kind.BOOLEAN
                && b != VdbField.Kind.VARCHAR;
        return numeric ? VdbField.Kind.DOUBLE : VdbField.Kind.TEXT;
    }

    /**
     * Columns of the observed fields, named as the fields unless a column of the collection has the name
     */
    public List<VdbField> getFields(VdbCollection collection) {
        List<VdbField> columns = new ArrayList<>();
        fields.forEach((name, type) -> {
            VdbField.Kind kind = type.kind != null ? type.kind : VdbField.Kind.TEXT;
            if (kind == VdbField.Kind.VARCHAR && type.maxLength > MAX_VARCHAR_LENGTH) {
                kind = VdbField.Kind.TEXT;
            }
            VdbField field = new VdbField(collection.getUnusedName(name), kind);
            field.setSourceName(name);
            field.setSize(kind == VdbField.Kind.VARCHAR ? Math.max(MIN_VARCHAR_LENGTH, type.maxLength) : 0);
            field.setNullable(type.nullable || type.kind == null);
            columns.add(field);
        });
        return columns;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }
}
