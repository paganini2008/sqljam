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
package com.github.sqljam.impexp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: VectorIndex is an index for the nearest neighbors of a vector column: its kind (HNSW graph or IVF
 *               partitions), the distance which it is built for and its options (m, ef_construction of HNSW, lists of
 *               IVF). It is kept in the details of an index as text, e.g. HNSW,COSINE,m=16;ef_construction=64.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class VectorIndex {

    /**
     * Key of the vector index in the details of an index
     */
    public static final String DETAIL_KEY = "VECTOR_INDEX";

    public enum Kind {
        HNSW, IVF
    }

    public enum Distance {
        EUCLIDEAN, COSINE, DOT, MANHATTAN, HAMMING, JACCARD
    }

    private final Kind kind;
    private final Distance distance;
    private final Map<String, String> options;

    public VectorIndex(Kind kind, Distance distance, Map<String, String> options) {
        this.kind = Objects.requireNonNull(kind);
        this.distance = Objects.requireNonNull(distance);
        this.options = options != null ? new LinkedHashMap<>(options) : new LinkedHashMap<>();
    }

    public Kind getKind() {
        return kind;
    }

    public Distance getDistance() {
        return distance;
    }

    /**
     * Options of the index by their names in lower case, e.g. m, ef_construction, lists
     */
    public Map<String, String> getOptions() {
        return options;
    }

    public String getOption(String name) {
        return options.get(name.toLowerCase(Locale.ENGLISH));
    }

    /**
     * Vector index of the details of an index, null if the index is not a vector index
     */
    public static VectorIndex of(Map<String, Object> detail) {
        Object value = detail != null ? detail.get(DETAIL_KEY) : null;
        return value != null ? parse(value.toString()) : null;
    }

    public static VectorIndex parse(String text) {
        String[] parts = StringUtils.defaultString(text).split(",", 3);
        if (parts.length < 2) {
            return null;
        }
        try {
            Map<String, String> options = new LinkedHashMap<>();
            if (parts.length == 3) {
                for (String option : parts[2].split(";")) {
                    String[] pair = option.split("=", 2);
                    if (pair.length == 2 && StringUtils.isNotBlank(pair[0])) {
                        options.put(pair[0].trim().toLowerCase(Locale.ENGLISH), pair[1].trim());
                    }
                }
            }
            return new VectorIndex(Kind.valueOf(parts[0].trim().toUpperCase(Locale.ENGLISH)),
                    Distance.valueOf(parts[1].trim().toUpperCase(Locale.ENGLISH)), options);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(kind + "," + distance + ",");
        options.forEach((name, value) -> text.append(text.charAt(text.length() - 1) == ',' ? "" : ";").append(name)
                .append('=').append(value));
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VectorIndex && toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }
}
