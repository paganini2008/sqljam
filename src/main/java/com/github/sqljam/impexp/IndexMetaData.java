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

/**
 * @Description: IndexMetaData is an index of one column
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public class IndexMetaData implements TiedMetaData {

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
        boolean unique = isUnique(detail);
        String indexName = getDialect().getIndexNameStatement(catalogName, schemaName, tableName, new String[]{columnName},
                unique,
                indexType);
        String statement = getDialect().getCreateIndexStatement(catalogName, schemaName, tableName, partition,
                new String[]{columnName}, indexName,
                unique, indexType);
        return new String[]{statement};
    }
}