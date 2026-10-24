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
package com.github.sqljam.impexp.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.math.NumberUtils;
import com.github.sqljam.impexp.DatabaseFeature;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.VectorIndex;
import com.github.sqljam.impexp.VectorType;

/**
 * @Description: MariaDBDialect generates ddl/dml of MariaDB, which is compatible with MySQL. Differences of MariaDB:
 *               sequences (CREATE SEQUENCE, nextval defaults), native UUID, INET4 and INET6 types, JSON stored as
 *               LONGTEXT with a json_valid check, system versioned tables.
 *               MariaDB 10.3 and later are supported. VECTOR and VECTOR INDEX (HNSW) of MariaDB 11.7 and later,
 *               detected on the connection.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class MariaDBDialect extends MySQLDialect {

    @Override
    public DbType getDbType() {
        return DbType.MARIADB;
    }

    /**
     * System versioned tables of MariaDB are created with system versioning, their SYSTEM_TIME partitions require it
     */
    @Override
    public String getTableOptions(String catalog, String schema, String tableName, Map<String, Object> detail) {
        String options = super.getTableOptions(catalog, schema, tableName, detail);
        if (!isCrossDatabase() && detail != null && Boolean.TRUE.equals(detail.get("SYSTEM_VERSIONED"))) {
            return options + " WITH SYSTEM VERSIONING";
        }
        return options;
    }

    /**
     * Native types of MariaDB are kept for MariaDB, UUID values of other databases are UUID
     */
    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").trim();
        switch (baseTypeName) {
            case "uuid":
            case "uniqueidentifier":
                return "uuid";
            case "inet4":
            case "inet6":
                return isCrossDatabase() ? "varchar(45)" : baseTypeName;
            default:
                break;
        }
        return super.getSpecialTypeName(dataType, sourceTypeName, columnSize, columnScale);
    }

    @Override
    protected String getVectorTextFunction() {
        return "VEC_ToText";
    }

    @Override
    protected String getTextVectorFunction() {
        return "VEC_FromText";
    }

    /**
     * VECTOR of MariaDB 11.7 and later
     */
    @Override
    protected boolean isVectorTypeSupported() {
        return isVersionAtLeast(11, 7);
    }

    /**
     * VECTOR and VECTOR INDEX (HNSW) come together in MariaDB
     */
    @Override
    protected void detectDatabaseFeatures(Connection connection, Set<DatabaseFeature> features) throws SQLException {
        super.detectDatabaseFeatures(connection, features);
        if (features.contains(DatabaseFeature.VECTOR)) {
            features.add(DatabaseFeature.HNSW_INDEX);
        }
    }

    /**
     * VECTOR INDEX (HNSW) of a NOT NULL column for the euclidean or cosine distance, M is the number of neighbors
     */
    @Override
    public String getCreateVectorIndexStatement(String catalog, String schema, String tableName, String indexName,
                                                String columnName, String columnTypeName, VectorType vectorType,
                                                boolean nullable, VectorIndex vectorIndex) {
        VectorIndex.Distance distance = vectorIndex.getDistance();
        if (!hasDatabaseFeature(DatabaseFeature.HNSW_INDEX, isVectorTypeSupported()) || nullable
                || (distance != VectorIndex.Distance.EUCLIDEAN && distance != VectorIndex.Distance.COSINE)) {
            return null;
        }
        int neighbors = NumberUtils.toInt(vectorIndex.getOption("m"), 0);
        return String.format("CREATE VECTOR INDEX %s ON %s (%s)%s DISTANCE=%s", quoteIdentifier(indexName),
                getQualifiedTableName(catalog, schema, tableName), getIdentifier(columnName),
                neighbors >= MIN_VECTOR_NEIGHBORS && neighbors <= MAX_VECTOR_NEIGHBORS ? " M=" + neighbors : "",
                distance.name().toLowerCase(Locale.ENGLISH));
    }

    /**
     * Neighbors (M) of a VECTOR INDEX
     */
    static final int MIN_VECTOR_NEIGHBORS = 3;
    static final int MAX_VECTOR_NEIGHBORS = 200;

    @Override
    public boolean isSequenceSupported() {
        return true;
    }

    @Override
    public String[] getStatementBeforeSequenceCreated(String catalog, String schema, String sequenceName) {
        return new String[]{String.format("DROP SEQUENCE IF EXISTS %s",
                getQualifiedSequenceName(catalog, schema, sequenceName))};
    }

    /**
     * MAXVALUE of MariaDB is a BIGINT, larger values of other databases (e.g. NUMBER of Oracle) are left out
     */
    @Override
    public String getCreateSequenceStatement(String catalog, String schema, String sequenceName, long startValue,
                                             long increment, Long minValue, Long maxValue, boolean cycle,
                                             Long cacheSize, String dataType) {
        return super.getCreateSequenceStatement(catalog, schema, sequenceName, startValue, increment, minValue,
                maxValue != null && maxValue >= Long.MAX_VALUE - 1 ? null : maxValue, cycle, cacheSize, dataType);
    }

    /**
     * NULL of columns reported as OTHER (UUID, INET4, INET6) is bound as VARCHAR
     */
    @Override
    public int getNullSqlType(int sqlType) {
        return sqlType == Types.OTHER ? Types.VARCHAR : super.getNullSqlType(sqlType);
    }

    /**
     * MariaDB has no row alias of INSERT, VALUES() is the value of the row
     */
    @Override
    protected boolean isRowAliasSupported() {
        return false;
    }

    /**
     * MariaDB Connector/J streams the rows by the fetch size
     */
    @Override
    public boolean prepareStreaming(Connection connection, Statement statement, int fetchSize) throws SQLException {
        statement.setFetchSize(fetchSize);
        return false;
    }
}
