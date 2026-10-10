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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @Description: CombinedIndexMetaData is an index of multiple columns
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class CombinedIndexMetaData implements TiedMetaData {

    private final String indexName;
    private final List<IndexMetaData> indexMetaDatas;
    private final TiedMetaData tiedMetaData;

    public CombinedIndexMetaData(String indexName, List<IndexMetaData> indexMetaDatas, TiedMetaData tiedMetaData) {
        this.indexName = indexName;
        this.indexMetaDatas = indexMetaDatas;
        this.tiedMetaData = tiedMetaData;
    }

    public String getIndexName() {
        return indexName;
    }

    public List<IndexMetaData> getIndexMetaDatas() {
        return indexMetaDatas;
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

    @Override
    public DatabaseMetaData getMetaData() {
        return tiedMetaData.getMetaData();
    }

    @Override
    public Dialect getDialect() {
        return tiedMetaData.getDialect();
    }

    @Override
    public MetaDataOperations getMetaDataOperations() {
        return tiedMetaData.getMetaDataOperations();
    }

    @Override
    public Map<String, Object> getDetail() {
        return indexMetaDatas.get(0).getDetail();
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

    @Override
    public String[] getStatements() throws SQLException {
        TableMetaData tableMetaData = unwrap(TableMetaData.class);
        String[] columnNames = indexMetaDatas.stream().map(md -> md.getColumnName()).toArray(l -> new String[l]);
        if (Arrays.stream(columnNames).allMatch(columnName -> tableMetaData.isPrimaryKeyColumn(columnName))) {
            return null;
        }
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        String tableName = getDialect().getTargetTableName(getTableName());
        boolean partition = tableMetaData.isPartitioned();
        boolean unique = IndexMetaData.isUnique(getDetail());
        String indexName = getDialect().getIndexNameStatement(catalogName, schemaName, tableName, columnNames, unique,
                null);
        String statement = getDialect().getCreateIndexStatement(catalogName, schemaName, tableName, partition, columnNames,
                indexName, unique, null);
        return new String[]{statement};
    }
}