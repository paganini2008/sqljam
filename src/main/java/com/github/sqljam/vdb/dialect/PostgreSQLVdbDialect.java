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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: PostgreSQLVdbDialect writes into PostgreSQL with pgvector 0.5.0 and later: vector, halfvec (0.7.0),
 *               sparsevec (0.7.0), HNSW (0.5.0) and IVFFlat indexes, upserts by ON CONFLICT. Rules are copied from
 *               PostgreSQLDialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class PostgreSQLVdbDialect extends VdbDialect {

    static final int MAX_VECTOR_DIMENSIONS = 16000;
    /**
     * Dimensions of vector and halfvec columns which HNSW and IVFFlat index at most
     */
    static final int MAX_VECTOR_INDEX_DIMENSIONS = 2000;
    static final int MAX_HALFVEC_INDEX_DIMENSIONS = 4000;

    @Override
    public String getName() {
        return "PostgreSQL";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        prepareSession(connection);
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT to_regtype('vector') IS NOT NULL, to_regtype('halfvec') IS NOT NULL,"
                        + " to_regtype('sparsevec') IS NOT NULL,"
                        + " EXISTS (SELECT 1 FROM pg_am WHERE amname = 'hnsw'),"
                        + " EXISTS (SELECT 1 FROM pg_am WHERE amname = 'ivfflat')")) {
            if (rs.next()) {
                VdbFeature[] columns = {VdbFeature.VECTOR, VdbFeature.HALF_VECTOR, VdbFeature.SPARSE_VECTOR,
                        VdbFeature.HNSW_INDEX, VdbFeature.IVF_INDEX};
                for (int i = 0; i < columns.length; i++) {
                    if (rs.getBoolean(i + 1)) {
                        features.add(columns[i]);
                    }
                }
            }
        }
    }

    /**
     * Adds the schema of pgvector to the search path when it is not there, its types are found then
     */
    @Override
    public void prepareSession(Connection connection) {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT n.nspname, current_setting('search_path') FROM pg_extension e"
                        + " JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'vector'"
                        + " AND NOT n.nspname = ANY (current_schemas(false))")) {
            if (rs.next()) {
                String path = StringUtils.trimToEmpty(rs.getString(2));
                String schema = "\"" + rs.getString(1).replace("\"", "\"\"") + "\"";
                try (Statement set = connection.createStatement()) {
                    set.execute("SET search_path TO " + (path.isEmpty() ? schema : path + ", " + schema));
                }
            }
        } catch (SQLException e) {
            // The search path is kept, vectors are found if pgvector is in it
        }
    }

    @Override
    public String foldIdentifier(String name) {
        return name.toLowerCase(Locale.ENGLISH);
    }

    @Override
    public int getMaxIdentifierLength() {
        return 63;
    }

    @Override
    protected String getScalarTypeName(VdbField field) {
        switch (field.getKind()) {
            case BOOLEAN:
                return "boolean";
            case SMALLINT:
                return "smallint";
            case INTEGER:
                return "integer";
            case BIGINT:
                return "bigint";
            case REAL:
                return "real";
            case DOUBLE:
                return "double precision";
            case VARCHAR:
                return field.getSize() > 0 && field.getSize() <= 10485760 ? "varchar(" + field.getSize() + ")" : "text";
            case TIMESTAMP:
                return "timestamptz";
            case BINARY:
                return "bytea";
            case JSON:
                return "jsonb";
            default:
                return "text";
        }
    }

    @Override
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        if (vectorType.getDimensions() > MAX_VECTOR_DIMENSIONS || !vectorType.isFloat32Compatible()) {
            return null;
        }
        String dimensions = vectorType.getDimensions() > 0 ? "(" + vectorType.getDimensions() + ")" : "";
        if (vectorType.isSparse()) {
            // sparsevec of pgvector 0.7.0 and later
            return hasFeature(VdbFeature.SPARSE_VECTOR) ? "sparsevec" + dimensions : null;
        }
        if (vectorType.getElement() == VectorType.Element.FLOAT16 && hasFeature(VdbFeature.HALF_VECTOR)) {
            return "halfvec" + dimensions;
        }
        // 16-bit floats are kept by vectors of 32-bit floats of older versions
        return hasFeature(VdbFeature.VECTOR) ? "vector" + dimensions : null;
    }

    @Override
    protected Object getVectorValue(VectorType.Values values, VectorType vectorType) {
        return vectorType.isSparse() ? values.toPostgreSqlSparseText() : values.toDenseText();
    }

    /**
     * Vectors and JSON are cast from text, the connection may send text as varchar
     */
    @Override
    public String getParameterMarker(Column column) {
        String type = column.getTypeName();
        if (type.startsWith("vector") || type.startsWith("halfvec") || type.startsWith("sparsevec")
                || "jsonb".equals(type) || "timestamptz".equals(type)) {
            return "CAST(? AS " + type + ")";
        }
        return "?";
    }

    @Override
    public Object getJdbcValue(Object value, Column column) {
        Object jdbcValue = super.getJdbcValue(value, column);
        // Times are cast from their text
        return jdbcValue instanceof OffsetDateTime ? ((OffsetDateTime) jdbcValue).format(OFFSET_TIMESTAMP_FORMAT)
                : jdbcValue;
    }

    @Override
    public int getNullSqlType(Column column) {
        return "timestamptz".equals(column.getTypeName()) ? Types.VARCHAR : super.getNullSqlType(column);
    }

    @Override
    public String getCreateVectorIndexStatement(String schema, String tableName, String indexName, String columnName,
                                                String columnTypeName, VectorType vectorType, boolean nullable,
                                                VectorIndex vectorIndex) {
        String type = StringUtils.substringBefore(StringUtils.defaultString(columnTypeName), "(").trim()
                .toLowerCase(Locale.ENGLISH);
        int dimensions = NumberUtils.toInt(StringUtils.substringBetween(columnTypeName, "(", ")"), 0);
        String operator = getVectorOperator(vectorIndex.getDistance());
        if (operator == null || dimensions <= 0 || ("vector".equals(type) && dimensions > MAX_VECTOR_INDEX_DIMENSIONS)
                || ("halfvec".equals(type) && dimensions > MAX_HALFVEC_INDEX_DIMENSIONS)) {
            return null;
        }
        // HNSW of pgvector 0.5.0 and later, IVFFlat has no sparse vectors: the other kind is taken if the database
        // has not the kind of the index or not for the vectors. IVFFlat has no Manhattan distance (l1).
        boolean hnswAvailable = hasFeature(VdbFeature.HNSW_INDEX);
        boolean ivfAvailable = hasFeature(VdbFeature.IVF_INDEX) && !"sparsevec".equals(type)
                && vectorIndex.getDistance() != VectorIndex.Distance.MANHATTAN;
        if (!hnswAvailable && !ivfAvailable) {
            return null;
        }
        boolean ivf = ivfAvailable && (vectorIndex.getKind() == VectorIndex.Kind.IVF || !hnswAvailable);
        List<String> options = new ArrayList<>();
        for (String option : ivf ? new String[]{"lists"} : new String[]{"m", "ef_construction"}) {
            String value = vectorIndex.getOption(option);
            if (NumberUtils.isDigits(value)) {
                options.add(option + " = " + value);
            }
        }
        return String.format("CREATE INDEX IF NOT EXISTS %s ON %s USING %s (%s %s_%s_ops)%s",
                getIdentifier(indexName), getQualifiedTableName(schema, tableName), ivf ? "ivfflat" : "hnsw",
                getIdentifier(columnName), type, operator, options.isEmpty() ? "" : " WITH (" + String.join(", ",
                        options) + ")");
    }

    static String getVectorOperator(VectorIndex.Distance distance) {
        switch (distance) {
            case EUCLIDEAN:
                return "l2";
            case COSINE:
                return "cosine";
            case DOT:
                return "ip";
            case MANHATTAN:
                return "l1";
            default:
                // Hamming and Jaccard distances are of bit vectors
                return null;
        }
    }
}
