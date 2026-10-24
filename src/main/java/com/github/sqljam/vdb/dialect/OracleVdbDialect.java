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
package com.github.sqljam.vdb.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: OracleVdbDialect writes into Oracle 23ai and later: VECTOR columns of FLOAT32, FLOAT64, INT8 and
 *               BINARY elements, sparse vectors (23.7), IVF indexes, HNSW indexes when the vector memory is set,
 *               upserts by MERGE. Rules are copied from OracleDialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class OracleVdbDialect extends VdbDialect {

    static final int MAX_VECTOR_DIMENSIONS = 65535;
    static final int MAX_VARCHAR_LENGTH = 4000;

    @Override
    public String getName() {
        return "Oracle";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        if (!probe(connection, "SELECT TO_VECTOR('[1]') FROM dual")) {
            return;
        }
        features.add(VdbFeature.VECTOR);
        features.add(VdbFeature.IVF_INDEX);
        if (probe(connection, "SELECT TO_VECTOR('[1,[0],[1]]', 1, FLOAT32, SPARSE) FROM dual")) {
            features.add(VdbFeature.SPARSE_VECTOR);
        }
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT value FROM v$parameter WHERE name = 'vector_memory_size'")) {
            if (rs.next() && NumberUtils.toLong(rs.getString(1), 0) > 0) {
                features.add(VdbFeature.HNSW_INDEX);
            }
        } catch (SQLException e) {
            // Without vector memory, or without the privilege to read it, HNSW indexes are created as IVF indexes
        }
    }

    @Override
    public String foldIdentifier(String name) {
        return name.toUpperCase(Locale.ENGLISH);
    }

    @Override
    protected String getScalarTypeName(VdbField field) {
        switch (field.getKind()) {
            case BOOLEAN:
                return "BOOLEAN";
            case SMALLINT:
                return "NUMBER(5)";
            case INTEGER:
                return "NUMBER(10)";
            case BIGINT:
                return "NUMBER(19)";
            case REAL:
                return "BINARY_FLOAT";
            case DOUBLE:
                return "BINARY_DOUBLE";
            case VARCHAR:
                return field.getSize() > 0 && field.getSize() <= MAX_VARCHAR_LENGTH ? "VARCHAR2(" + field.getSize()
                        + " CHAR)" : "CLOB";
            case TIMESTAMP:
                return "TIMESTAMP(6) WITH TIME ZONE";
            case BINARY:
                return "BLOB";
            case JSON:
                return "JSON";
            default:
                return "CLOB";
        }
    }

    @Override
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        int dimensions = vectorType.getDimensions();
        if (!hasFeature(VdbFeature.VECTOR) || dimensions > MAX_VECTOR_DIMENSIONS
                || (vectorType.isSparse() && !hasFeature(VdbFeature.SPARSE_VECTOR))) {
            return null;
        }
        VectorType.Element element = vectorType.getElement() == VectorType.Element.FLOAT16
                ? VectorType.Element.FLOAT32 : vectorType.getElement();
        if (element == VectorType.Element.BINARY && (vectorType.isSparse() || dimensions % 8 != 0)) {
            return null;
        }
        return String.format("VECTOR(%s, %s%s)", dimensions > 0 ? String.valueOf(dimensions) : "*", element,
                vectorType.isSparse() ? ", SPARSE" : "");
    }

    @Override
    protected Object getVectorValue(VectorType.Values values, VectorType vectorType) {
        return vectorType.isSparse() ? values.toOracleSparseText() : values.toDenseText();
    }

    /**
     * Vectors are converted from their text by the format of their column, the text may be longer than VARCHAR2
     */
    @Override
    public String getParameterMarker(Column column) {
        String type = column.getTypeName();
        if (type.startsWith("VECTOR(")) {
            return "TO_VECTOR(?, " + StringUtils.substringBetween(type, "(", ")") + ")";
        }
        return "?";
    }

    @Override
    public int getNullSqlType(Column column) {
        return column.getTypeName().startsWith("VECTOR(") || "JSON".equals(column.getTypeName())
                ? Types.CLOB : super.getNullSqlType(column);
    }

    @Override
    public String getUpsertStatement(String schema, String tableName, List<Column> columns,
                                     List<String> keyColumnNames) {
        String source = "(SELECT " + columns.stream().map(column -> getParameterMarker(column) + " "
                + getIdentifier(column.getName())).collect(Collectors.joining(", ")) + " FROM dual) src";
        return getMergeStatement(schema, tableName, columns, keyColumnNames, source, "");
    }

    @Override
    public String getCreateVectorIndexStatement(String schema, String tableName, String indexName, String columnName,
                                                String columnTypeName, VectorType vectorType, boolean nullable,
                                                VectorIndex vectorIndex) {
        if (StringUtils.containsIgnoreCase(columnTypeName, "SPARSE")) {
            return null;
        }
        boolean hnsw = vectorIndex.getKind() == VectorIndex.Kind.HNSW && hasFeature(VdbFeature.HNSW_INDEX);
        if (!hnsw && !hasFeature(VdbFeature.IVF_INDEX)) {
            return null;
        }
        List<String> parameters = new ArrayList<>();
        if (hnsw) {
            addParameter(parameters, "NEIGHBORS", vectorIndex.getOption("m"));
            addParameter(parameters, "EFCONSTRUCTION", vectorIndex.getOption("ef_construction"));
        } else {
            addParameter(parameters, "NEIGHBOR PARTITIONS", vectorIndex.getOption("lists"));
        }
        if (!parameters.isEmpty()) {
            parameters.add(0, hnsw ? "TYPE HNSW" : "TYPE IVF");
        }
        return String.format("CREATE VECTOR INDEX %s ON %s (%s) ORGANIZATION %s DISTANCE %s%s",
                getIdentifier(indexName), getQualifiedTableName(schema, tableName), getIdentifier(columnName),
                hnsw ? "INMEMORY NEIGHBOR GRAPH" : "NEIGHBOR PARTITIONS", vectorIndex.getDistance(),
                parameters.isEmpty() ? "" : " PARAMETERS (" + String.join(", ", parameters) + ")");
    }

    private static void addParameter(List<String> parameters, String name, String value) {
        if (NumberUtils.isDigits(value)) {
            parameters.add(name + " " + value);
        }
    }
}
