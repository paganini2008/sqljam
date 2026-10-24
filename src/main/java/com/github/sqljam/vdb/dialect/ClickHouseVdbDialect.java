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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: ClickHouseVdbDialect writes into ClickHouse 24.8 and later: vectors are arrays (Array(Float32),
 *               Array(Float64)) which take no NULL, a missing vector is an empty array. Vector similarity indexes
 *               (HNSW) of NOT NULL columns are created when the settings allow them. Tables are MergeTree ordered
 *               by their keys, ClickHouse has no upserts. Rules are copied from ClickHouseDialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ClickHouseVdbDialect extends VdbDialect {

    static final long VECTOR_INDEX_GRANULARITY = 100_000_000L;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss.SSSSSS");

    @Override
    public String getName() {
        return "ClickHouse";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        if (probe(connection, "SELECT CAST([1.0] AS Array(Float32))")) {
            features.add(VdbFeature.VECTOR);
        }
        if (probe(connection, "SELECT 1 FROM system.settings WHERE name IN"
                + " ('allow_experimental_vector_similarity_index', 'enable_vector_similarity_index') AND value = '1'")) {
            features.add(VdbFeature.HNSW_INDEX);
        }
    }

    @Override
    protected String getOpenQuote() {
        return "`";
    }

    @Override
    protected String getCloseQuote() {
        return "`";
    }

    @Override
    protected boolean isCatalogQualifier() {
        return true;
    }

    /**
     * Scalars which take NULL are Nullable, NOT NULL is not written
     */
    @Override
    protected String getScalarTypeName(VdbField field) {
        String type;
        switch (field.getKind()) {
            case BOOLEAN:
                type = "Bool";
                break;
            case SMALLINT:
                type = "Int16";
                break;
            case INTEGER:
                type = "Int32";
                break;
            case BIGINT:
                type = "Int64";
                break;
            case REAL:
                type = "Float32";
                break;
            case DOUBLE:
                type = "Float64";
                break;
            case TIMESTAMP:
                type = "DateTime64(6, 'UTC')";
                break;
            default:
                type = "String";
                break;
        }
        return field.isNullable() && !field.isPrimaryKey() ? "Nullable(" + type + ")" : type;
    }

    @Override
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        if (!hasFeature(VdbFeature.VECTOR) || vectorType.isSparse()
                || vectorType.getElement() == VectorType.Element.BINARY) {
            return null;
        }
        return vectorType.getElement() == VectorType.Element.FLOAT64 ? "Array(Float64)" : "Array(Float32)";
    }

    @Override
    public String getColumnDefinition(Column column) {
        String remarks = column.getField().getRemarks();
        return getIdentifier(column.getName()) + " " + column.getTypeName() + (StringUtils.isNotBlank(remarks)
                ? " COMMENT " + quoteLiteral(remarks) : "");
    }

    @Override
    public String getPrimaryKeyDefinition(String tableName, List<String> keyColumnNames) {
        return null;
    }

    @Override
    public String getTableOptions(List<String> keyColumnNames) {
        return " ENGINE = MergeTree ORDER BY " + (keyColumnNames.isEmpty() ? "tuple()" : "(" + getIdentifiers(
                keyColumnNames) + ")");
    }

    @Override
    protected String quoteLiteralText(String text) {
        return text.replace("\\", "\\\\");
    }

    @Override
    public String getTableCommentStatement(String schema, String tableName, String comment) {
        return String.format("ALTER TABLE %s MODIFY COMMENT %s", getQualifiedTableName(schema, tableName),
                quoteLiteral(comment));
    }

    /**
     * Comments of columns are in their definitions
     */
    @Override
    public String getColumnCommentStatement(String schema, String tableName, String columnName, String comment) {
        return null;
    }

    @Override
    public boolean isTransactionSupported() {
        return false;
    }

    @Override
    public boolean isUpsertSupported() {
        return false;
    }

    @Override
    public String getUpsertStatement(String schema, String tableName, List<Column> columns,
                                     List<String> keyColumnNames) {
        throw new UnsupportedOperationException("ClickHouse has no upserts");
    }

    /**
     * A missing vector is an empty array, times are text of UTC
     */
    @Override
    public Object getJdbcValue(Object value, Column column) {
        if (value == null) {
            return column.getTypeName().startsWith("Array(") ? "[]" : null;
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).withOffsetSameInstant(ZoneOffset.UTC).format(TIMESTAMP_FORMAT);
        }
        return super.getJdbcValue(value, column);
    }

    @Override
    public String getCreateVectorIndexStatement(String schema, String tableName, String indexName, String columnName,
                                                String columnTypeName, VectorType vectorType, boolean nullable,
                                                VectorIndex vectorIndex) {
        VectorIndex.Distance distance = vectorIndex.getDistance();
        // Empty arrays of missing vectors are of other dimensions, they would fail the index
        if (!hasFeature(VdbFeature.HNSW_INDEX) || nullable || vectorType == null || vectorType.getDimensions() < 1
                || (distance != VectorIndex.Distance.COSINE && distance != VectorIndex.Distance.EUCLIDEAN)) {
            return null;
        }
        String neighbors = vectorIndex.getOption("m");
        String candidates = vectorIndex.getOption("ef_construction");
        String options = StringUtils.isNumeric(neighbors) && StringUtils.isNumeric(candidates)
                ? String.format(", 'bf16', %s, %s", neighbors, candidates) : "";
        return String.format("ALTER TABLE %s ADD INDEX %s %s TYPE vector_similarity('hnsw', '%s', %d%s)"
                        + " GRANULARITY %d", getQualifiedTableName(schema, tableName), getIdentifier(indexName),
                getIdentifier(columnName), distance == VectorIndex.Distance.COSINE ? "cosineDistance" : "L2Distance",
                vectorType.getDimensions(), options, VECTOR_INDEX_GRANULARITY);
    }
}
