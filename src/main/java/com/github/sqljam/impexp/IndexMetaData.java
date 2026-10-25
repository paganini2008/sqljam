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

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description: IndexMetaData is an index of one column
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class IndexMetaData implements TiedMetaData {

    /**
     * Access method of the index, e.g. btree, gin, hnsw of PostgreSQL
     */
    public static final String INDEX_METHOD = "INDEX_METHOD";
    /**
     * Operator class of the column, null for the default one, e.g. vector_cosine_ops
     */
    public static final String OPERATOR_CLASS = "OPERATOR_CLASS";
    /**
     * Storage options of the index, e.g. m=16, ef_construction=64
     */
    public static final String INDEX_OPTIONS = "INDEX_OPTIONS";

    private final String indexName;
    private final String columnName;
    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;

    public IndexMetaData(String indexName, String columnName, Map<String, Object> detail, TiedMetaData tiedMetaData) {
        this.indexName = indexName;
        this.columnName = columnName;
        this.detail = detail;
        this.tiedMetaData = tiedMetaData;
    }

    @Override
    public String getCatalogName() {
        return tiedMetaData.getCatalogName();
    }

    @Override
    public String getSchemaName() {
        return tiedMetaData.getSchemaName();
    }

    @Override
    public String getTableName() {
        return tiedMetaData.getTableName();
    }

    public String getColumnName() {
        return columnName;
    }

    public String getIndexName() {
        return indexName;
    }

    @Override
    public <T extends TiedMetaData> T unwrap(Class<T> clz) {
        try {
            return clz.cast(tiedMetaData);
        } catch (RuntimeException e) {
            return tiedMetaData.unwrap(clz);
        }
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return tiedMetaData.getMetaData();
    }

    @Override
    public Dialect getDialect() {
        return tiedMetaData.getDialect();
    }

    @Override
    public Map<String, Object> getDetail() {
        return detail;
    }

    @Override
    public MetaDataOperations getMetaDataOperations() {
        return tiedMetaData.getMetaDataOperations();
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

    /**
     * NON_UNIQUE is reported as boolean, number or string by different drivers
     */
    static boolean isUnique(Map<String, Object> detail) {
        Object nonUnique = detail.get("NON_UNIQUE");
        if (nonUnique instanceof Boolean) {
            return !(Boolean) nonUnique;
        } else if (nonUnique instanceof Number) {
            return ((Number) nonUnique).intValue() == 0;
        } else if (nonUnique != null) {
            String text = nonUnique.toString().trim();
            return "0".equals(text) || "false".equalsIgnoreCase(text);
        }
        return false;
    }

    @Override
    public String[] getStatements() throws SQLException {
        TableMetaData tableMetaData = unwrap(TableMetaData.class);
        if (tableMetaData.isPrimaryKeyColumn(columnName) || tableMetaData.isAutoIncrementColumn(columnName)) {
            return null;
        }

        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        String tableName = getDialect().getTargetTableName(getTableName());
        boolean partition = tableMetaData.isPartitionTable();
        int typeIndex = TableMetaData.getInt(detail, "TYPE");
        String indexType = typeIndex == DatabaseMetaData.tableIndexHashed ? "HASH" : null;
        String method = getIndexMethod(getDialect(), detail);
        if (method != null) {
            indexType = method;
        }
        boolean unique = isUnique(detail);
        // Column of the target, as the user mapped it
        String targetColumnName = getDialect().getTargetColumnName(getTableName(), columnName);
        String indexName = getDialect().getIndexNameStatement(catalogName, schemaName, tableName,
                new String[]{targetColumnName}, unique, indexType);
        VectorIndexing vectorIndexing = getVectorIndexing(tableMetaData, new String[]{columnName});
        if (vectorIndexing == VectorIndexing.SKIPPED) {
            log.info("Index of vector column is skipped, the target has no index for it: {}.{}", tableName,
                    columnName);
            return null;
        }
        VectorIndex vectorIndex = VectorIndex.of(detail);
        if (vectorIndexing == VectorIndexing.VECTOR_INDEX
                || (vectorIndexing == VectorIndexing.SAME && method == null && vectorIndex != null)) {
            // An ordinary index of another database type can not hold vectors, a vector index of the same type
            // without access method (MariaDB, Oracle) is created by its kind and distance
            if (vectorIndex == null) {
                log.info("Index of vector column is skipped, it is not a vector index: {}.{}", tableName, columnName);
                return null;
            }
            ColumnMetaData column = tableMetaData.findColumnMetaData(columnName).orElse(null);
            String columnTypeName = ColumnMetaData.getTargetVectorTypeName(getDialect(), column.getDetail());
            boolean nullable = !"NO".equalsIgnoreCase((String) column.getDetail().get("IS_NULLABLE"));
            String statement = getDialect().getCreateVectorIndexStatement(catalogName, schemaName, tableName,
                    indexName, targetColumnName, columnTypeName, VectorType.of(column.getDetail()), nullable,
                    vectorIndex);
            if (statement == null) {
                log.info("Vector index is skipped, the target has no index of {}: {}.{}", vectorIndex, tableName,
                        columnName);
                return null;
            }
            return new String[]{statement};
        }
        String statement = method != null ? getDialect().getCreateIndexStatement(catalogName, schemaName, tableName,
                partition, new String[]{targetColumnName}, indexName, unique, indexType,
                new String[]{(String) detail.get(OPERATOR_CLASS)}, (String) detail.get(INDEX_OPTIONS))
                : getDialect().getCreateIndexStatement(catalogName, schemaName, tableName, partition,
                new String[]{targetColumnName}, indexName, unique, indexType);
        return new String[]{statement};
    }

    /**
     * How an index on columns of vectors is created
     */
    enum VectorIndexing {
        /**
         * No column is a vector, the index is an ordinary one
         */
        NONE,
        /**
         * The index of the same database type is created as it is, the columns keep their vector types
         */
        SAME,
        /**
         * A vector index of the target is created, or none
         */
        VECTOR_INDEX,
        /**
         * The index is not created: the target keeps the vectors as text, or has no index of vectors
         */
        SKIPPED
    }

    static VectorIndexing getVectorIndexing(TableMetaData tableMetaData, String[] columnNames) {
        Dialect dialect = tableMetaData.getDialect();
        int vectors = 0;
        for (String columnName : columnNames) {
            ColumnMetaData column = tableMetaData.findColumnMetaData(columnName).orElse(null);
            VectorType vectorType = column != null ? VectorType.of(column.getDetail()) : null;
            if (vectorType == null) {
                continue;
            }
            if (ColumnMetaData.getTargetVectorTypeName(dialect, column.getDetail()) == null) {
                return VectorIndexing.SKIPPED;
            }
            vectors++;
        }
        if (vectors == 0) {
            return VectorIndexing.NONE;
        }
        if (!dialect.isCrossDatabase()) {
            return VectorIndexing.SAME;
        }
        return columnNames.length == 1 ? VectorIndexing.VECTOR_INDEX : VectorIndexing.SKIPPED;
    }

    /**
     * Access method of an index of the same database type which is not the default one, e.g. gin or hnsw of
     * PostgreSQL, null for other indexes
     */
    static String getIndexMethod(Dialect dialect, Map<String, Object> detail) {
        String method = (String) detail.get(INDEX_METHOD);
        return !dialect.isCrossDatabase() && method != null && !"btree".equalsIgnoreCase(method) ? method : null;
    }
}