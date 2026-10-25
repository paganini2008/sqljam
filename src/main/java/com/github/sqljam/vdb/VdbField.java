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

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: VdbField is a column of a collection of a vector database: its id, a vector, a scalar field of the
 *               payload (a column of the target table), the JSON of a nested field, or the payload as a whole. The
 *               type of a scalar is one of {@link Kind}, the target maps it to its own types.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
@ToString
public class VdbField {

    /**
     * Name of the column of the whole payload, a JSON object of the fields of a record except its id and vectors
     */
    public static final String PAYLOAD = "payload";

    /**
     * Type of the values of a field, as the target keeps them
     */
    public enum Kind {
        BOOLEAN,
        /**
         * 16-bit integers
         */
        SMALLINT,
        INTEGER,
        BIGINT,
        REAL,
        DOUBLE,
        /**
         * Text of a known maximum length (size), e.g. VarChar(512) of Milvus
         */
        VARCHAR,
        /**
         * Text of any length
         */
        TEXT,
        /**
         * Time with a time zone, e.g. date of Weaviate (RFC 3339)
         */
        TIMESTAMP,
        BINARY,
        /**
         * JSON text of objects and arrays
         */
        JSON,
        VECTOR
    }

    private final String name;
    private Kind kind;
    /**
     * Maximum length of VARCHAR
     */
    private int size;
    private boolean nullable = true;
    private boolean primaryKey;
    private VectorType vectorType;
    private VectorIndex vectorIndex;
    /**
     * Name of the field in the records of the database, e.g. _id of Elasticsearch, if it differs from the column
     */
    private String sourceName;
    private String remarks;
    /**
     * Details of the database for reading the values, e.g. the type of a field of Milvus
     */
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public VdbField(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    public static VdbField vector(String name, VectorType vectorType) {
        VdbField field = new VdbField(name, Kind.VECTOR);
        field.setVectorType(vectorType);
        return field;
    }

    public static VdbField varchar(String name, int size) {
        VdbField field = new VdbField(name, Kind.VARCHAR);
        field.setSize(size);
        return field;
    }

    public String getSourceName() {
        return sourceName != null ? sourceName : name;
    }

    public boolean isVector() {
        return kind == Kind.VECTOR;
    }
}
