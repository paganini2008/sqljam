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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Description: PartitionTableMetaData is a partition of a partitioned table of PostgreSQL
 * @Author: Fred Feng
 * @Date: 17/05/2023
 * @Version 1.0.0
 */
public class PartitionTableMetaData implements TiedMetaData {

    private final String tableName;
    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;

    public PartitionTableMetaData(String tableName, Map<String, Object> detail, TiedMetaData tiedMetaData) {
        this.tableName = tableName;
        this.detail = detail;
        this.tiedMetaData = tiedMetaData;
    }

    private final List<PrimaryKeyMetaData> primaryKeyMetaDatas = new ArrayList<>();

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
        return tableName;
    }

    @Override
    public <T extends TiedMetaData> T unwrap(Class<T> clz) {
        try {
        return clz.cast(tiedMetaData);
        }catch (RuntimeException e) {
            return tiedMetaData.unwrap(clz);
        }
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return tiedMetaData.getMetaData();
    }

    @Override
    public MetaDataOperations getMetaDataOperations() {
        return tiedMetaData.getMetaDataOperations();
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
    public void accept(MetaDataVisitor visitor) throws SQLException {
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        String tableName = getTableName();
        List<Map<String, Object>> infoList = getMetaDataOperations().getPrimaryKeyInfos(getMetaData(), catalogName,
                schemaName, tableName);
        if (!infoList.isEmpty()) {
            // Composite primary key is one constraint
            List<Map<String, Object>> pkInfos = new ArrayList<>(infoList);
            pkInfos.sort(Comparator.comparingInt(info -> TableMetaData.getInt(info, "KEY_SEQ")));
            String columnNames = pkInfos.stream().map(info -> (String) info.get("COLUMN_NAME"))
                    .collect(Collectors.joining(","));
            primaryKeyMetaDatas.add(new PrimaryKeyMetaData(columnNames, pkInfos.get(0), this));
        }
        for (PrimaryKeyMetaData primaryKeyMetaData : primaryKeyMetaDatas) {
            primaryKeyMetaData.accept(visitor);
        }
        new PartitionExpressionMetaData(tableName, this).accept(visitor);

        visitor.visit(this);
    }

    @Override
    public String[] getStatements() throws SQLException {
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        Dialect dialect = getDialect();
        String tableName = dialect.getTargetTableName(getTableName());
        String inheritedTableName = dialect.getTargetTableName((String) detail.get("INHERITED_TABLE_NAME"));
        String statement = dialect.getCreatePartitionTableStatement(catalogName, schemaName, tableName,
                inheritedTableName);
        return new String[]{statement};
    }
}