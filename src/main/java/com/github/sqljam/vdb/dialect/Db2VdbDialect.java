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
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: Db2VdbDialect writes into Db2 12.1.2 and later: VECTOR columns of FLOAT32 and INT8 elements, vector
 *               indexes (12.1.5) of keys up to 8000 bytes with the Euclidean or the cosine distance, upserts by
 *               MERGE. Rules are copied from Db2Dialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class Db2VdbDialect extends VdbDialect {

    static final int MAX_VECTOR_DIMENSIONS = 8168;
    static final int MAX_VECTOR_INDEX_BYTES = 8000;
    private static final int BYTES_PER_CHAR = 3;
    private static final int MAX_VARCHAR_BYTES = 32672;

    @Override
    public String getName() {
        return "Db2";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        if (probe(connection, "SELECT VECTOR('[1]', 1, FLOAT32) FROM sysibm.sysdummy1")) {
            features.add(VdbFeature.VECTOR);
            features.add(VdbFeature.HNSW_INDEX);
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
                return "SMALLINT";
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case REAL:
                return "REAL";
            case DOUBLE:
                return "DOUBLE";
            case VARCHAR:
                long bytes = (long) field.getSize() * BYTES_PER_CHAR;
                return field.getSize() > 0 && bytes <= MAX_VARCHAR_BYTES ? "VARCHAR(" + bytes + ")" : "CLOB(2G)";
            case TIMESTAMP:
                return "TIMESTAMP(6)";
            case BINARY:
                return "BLOB(2G)";
            default:
                return "CLOB(2G)";
        }
    }

    @Override
    protected boolean isTimeZoneSupported() {
        return false;
    }

    @Override
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        int dimensions = vectorType.getDimensions();
        if (!hasFeature(VdbFeature.VECTOR) || vectorType.isSparse() || !vectorType.isFloat32Compatible()
                || dimensions < 1 || dimensions > MAX_VECTOR_DIMENSIONS) {
            return null;
        }
        return String.format("VECTOR(%d, %s)", dimensions, vectorType.getElement() == VectorType.Element.INT8 ? "INT8"
                : "FLOAT32");
    }

    /**
     * Vectors are converted from their text, parameters of MERGE are cast to the types of their columns
     */
    @Override
    public String getParameterMarker(Column column) {
        String type = column.getTypeName();
        if (type.startsWith("VECTOR(")) {
            return "VECTOR(CAST(? AS CLOB(2G)), " + StringUtils.substringBetween(type, "(", ")") + ")";
        }
        return "CAST(? AS " + type + ")";
    }

    @Override
    public int getNullSqlType(Column column) {
        return column.getTypeName().startsWith("VECTOR(") ? Types.CLOB : super.getNullSqlType(column);
    }

    @Override
    public String getUpsertStatement(String schema, String tableName, List<Column> columns,
                                     List<String> keyColumnNames) {
        String source = "(VALUES (" + columns.stream().map(this::getParameterMarker).collect(Collectors.joining(
                ", ")) + ")) AS src (" + getIdentifiers(names(columns)) + ")";
        return getMergeStatement(schema, tableName, columns, keyColumnNames, source, "");
    }

    @Override
    public String getCreateVectorIndexStatement(String schema, String tableName, String indexName, String columnName,
                                                String columnTypeName, VectorType vectorType, boolean nullable,
                                                VectorIndex vectorIndex) {
        VectorIndex.Distance distance = vectorIndex.getDistance();
        int bytes = vectorType == null ? 0 : vectorType.getDimensions() * (vectorType.getElement()
                == VectorType.Element.INT8 ? 1 : Float.BYTES);
        if (!hasFeature(VdbFeature.HNSW_INDEX) || bytes > MAX_VECTOR_INDEX_BYTES
                || (distance != VectorIndex.Distance.EUCLIDEAN && distance != VectorIndex.Distance.COSINE)) {
            return null;
        }
        String index = StringUtils.isNotBlank(schema) ? getIdentifier(schema) + "." + getIdentifier(indexName)
                : getIdentifier(indexName);
        return String.format("CREATE VECTOR INDEX %s ON %s (%s) WITH DISTANCE %s", index,
                getQualifiedTableName(schema, tableName), getIdentifier(columnName), distance);
    }
}
