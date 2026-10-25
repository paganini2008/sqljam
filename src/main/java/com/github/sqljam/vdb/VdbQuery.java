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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: VdbQuery narrows the records of a collection: conditions of payload fields which every database
 *               translates into its own filter, or a native filter of the database (a filter of Qdrant, an
 *               expression of Milvus, a where filter of Weaviate, a filter of Pinecone, a query of Elasticsearch and
 *               OpenSearch), a limit, and the nearest neighbors of a vector or of a record.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
public class VdbQuery {

    public enum Operator {
        EQ, NE, GT, GE, LT, LE, IN
    }

    /**
     * Condition of a field: field operator value, the value of IN is a list
     */
    @Getter
    public static final class Condition {

        private final String field;
        private final Operator operator;
        private final Object value;

        public Condition(String field, Operator operator, Object value) {
            this.field = field;
            this.operator = operator;
            this.value = value;
        }
    }

    /**
     * Conditions which all records match
     */
    private final List<Condition> conditions = new ArrayList<>();
    /**
     * Filter in the language of the database, it takes the place of the conditions
     */
    private String nativeFilter;
    /**
     * Records at most, 0 for all
     */
    private long limit;
    /**
     * Vector field of a search for the nearest neighbors
     */
    private String nearestField;
    /**
     * Vector of the search, or the id of the record whose vector is searched
     */
    private float[] nearestVector;
    private Object nearestId;
    /**
     * Partitions, tenants or namespaces which are read, all when it is empty
     */
    private List<String> partitions = new ArrayList<>();

    public VdbQuery where(String field, Operator operator, Object value) {
        conditions.add(new Condition(field, operator, value));
        return this;
    }

    public boolean isNative() {
        return StringUtils.isNotBlank(nativeFilter);
    }

    public boolean isFiltered() {
        return isNative() || !conditions.isEmpty();
    }

    /**
     * Whether the records are the nearest neighbors of a vector, which are read in one request of the limit
     */
    public boolean isNearest() {
        return StringUtils.isNotBlank(nearestField) && (nearestVector != null || nearestId != null);
    }

    /**
     * Partitions of the collection which are read: the partitions of the query, all when it has none
     */
    public List<String> selectPartitions(List<String> all) {
        if (partitions == null || partitions.isEmpty()) {
            return all;
        }
        List<String> selected = new ArrayList<>(all);
        selected.retainAll(partitions);
        return selected;
    }

    public static VdbQuery all() {
        return new VdbQuery();
    }

    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*([\\p{L}\\p{N}_.$-]+)\\s*(>=|<=|!=|<>|=|>|<|(?i:in))\\s*(.+?)\\s*$");

    /**
     * Conditions of text, one a line or joined by AND: field operator value, e.g. n >= 6, title = 'a b',
     * lang IN ('en', 'zh'). Values in quotes are text, true and false are booleans, numbers are numbers.
     *
     * @throws IllegalArgumentException if a condition is not of this form
     */
    public VdbQuery where(String text) {
        for (String line : StringUtils.defaultString(text).split(
                "(?i)\\s+and\\s+(?=(?:[^']*'[^']*')*[^']*$)|\\R")) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            Matcher matcher = CONDITION.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid condition: " + line.trim());
            }
            String operator = matcher.group(2).toUpperCase(Locale.ENGLISH);
            String value = matcher.group(3);
            if ("IN".equals(operator)) {
                String items = StringUtils.removeEnd(StringUtils.removeStart(value.trim(), "("), ")");
                List<Object> values = new ArrayList<>();
                for (String item : items.split(",(?=(?:[^']*'[^']*')*[^']*$)")) {
                    values.add(parseValue(item));
                }
                where(matcher.group(1), Operator.IN, values);
            } else {
                where(matcher.group(1), toOperator(operator), parseValue(value));
            }
        }
        return this;
    }

    private static Operator toOperator(String operator) {
        switch (operator) {
            case "=":
                return Operator.EQ;
            case "!=":
            case "<>":
                return Operator.NE;
            case ">":
                return Operator.GT;
            case ">=":
                return Operator.GE;
            case "<":
                return Operator.LT;
            default:
                return Operator.LE;
        }
    }

    static Object parseValue(String text) {
        String value = text.trim();
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'") || value.startsWith("\"")
                && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        if (value.matches("-?\\d{1,18}")) {
            return Long.parseLong(value);
        }
        if (NumberUtils.isCreatable(value)) {
            return Double.parseDouble(value);
        }
        return value;
    }
}
