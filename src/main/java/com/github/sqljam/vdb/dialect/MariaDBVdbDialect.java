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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.math.NumberUtils;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: MariaDBVdbDialect writes into MariaDB 11.7 and later: VECTOR columns like MySQL, vector indexes of
 *               NOT NULL columns by HNSW (M of 3 to 200) with the Euclidean or the cosine distance, upserts by
 *               ON DUPLICATE KEY UPDATE of VALUES(). Rules are copied from MariaDBDialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class MariaDBVdbDialect extends MySQLVdbDialect {

    static final int MIN_VECTOR_NEIGHBORS = 3;
    static final int MAX_VECTOR_NEIGHBORS = 200;

    @Override
    public String getName() {
        return "MariaDB";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        super.detectFeatures(connection, features);
        if (features.contains(VdbFeature.VECTOR)) {
            features.add(VdbFeature.HNSW_INDEX);
        }
    }

    @Override
    protected String getVectorTextFunction() {
        return "VEC_ToText";
    }

    @Override
    protected String getTextVectorFunction() {
        return "VEC_FromText";
    }

    @Override
    protected boolean isRowAliasSupported() {
        return false;
    }

    /**
     * Bytes of a primary key of a table with a vector index at most
     */
    static final int MAX_VECTOR_INDEX_KEY_BYTES = 256;

    /**
     * The key of a vector index is the primary key, text of utf8mb4 takes 4 bytes a character
     */
    @Override
    public boolean isVectorIndexAllowed(List<Column> keyColumns) {
        int bytes = 0;
        for (Column column : keyColumns) {
            switch (column.getField().getKind()) {
                case BOOLEAN:
                    bytes += 1;
                    break;
                case SMALLINT:
                    bytes += Short.BYTES;
                    break;
                case INTEGER:
                case REAL:
                    bytes += Integer.BYTES;
                    break;
                case BIGINT:
                case DOUBLE:
                case TIMESTAMP:
                    bytes += Long.BYTES;
                    break;
                case VARCHAR:
                    bytes += column.getField().getSize() * 4;
                    break;
                default:
                    return false;
            }
        }
        return bytes <= MAX_VECTOR_INDEX_KEY_BYTES;
    }

    @Override
    public int getMaxVectorIndexes() {
        return 1;
    }

    @Override
    public String getCreateVectorIndexStatement(String schema, String tableName, String indexName, String columnName,
                                                String columnTypeName, VectorType vectorType, boolean nullable,
                                                VectorIndex vectorIndex) {
        VectorIndex.Distance distance = vectorIndex.getDistance();
        if (!hasFeature(VdbFeature.HNSW_INDEX) || nullable || (distance != VectorIndex.Distance.EUCLIDEAN
                && distance != VectorIndex.Distance.COSINE)) {
            return null;
        }
        int neighbors = NumberUtils.toInt(vectorIndex.getOption("m"), 0);
        return String.format("CREATE VECTOR INDEX %s ON %s (%s)%s DISTANCE=%s", getIdentifier(indexName),
                getQualifiedTableName(schema, tableName), getIdentifier(columnName),
                neighbors >= MIN_VECTOR_NEIGHBORS && neighbors <= MAX_VECTOR_NEIGHBORS ? " M=" + neighbors : "",
                distance.name().toLowerCase(Locale.ENGLISH));
    }
}
