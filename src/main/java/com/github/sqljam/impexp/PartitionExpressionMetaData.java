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

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: PartitionExpressionMetaData is the bound expression of a partition of PostgreSQL, with the
 *               partition clause of a partition which is partitioned again
 * @Author: Fred Feng
 * @Date: 17/05/2023
 * @Version 1.0.0
 */
public class PartitionExpressionMetaData implements TiedMetaData {

    private final String tableName;
    private final TiedMetaData tiedMetaData;

    public PartitionExpressionMetaData(String tableName, TiedMetaData tiedMetaData) {
        this.tableName = tableName;
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
        return tableName;
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
    public MetaDataOperations getMetaDataOperations() {
        return tiedMetaData.getMetaDataOperations();
    }

    @Override
    public Dialect getDialect() {
        return tiedMetaData.getDialect();
    }

    @Override
    public Map<String, Object> getDetail() {
        return tiedMetaData.getDetail();
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

    /**
     * The bound of the partition, followed by its own partition clause if it is partitioned again, e.g.
     * FOR VALUES FROM ('2024-01-01') TO ('2025-01-01') PARTITION BY LIST (region)
     */
    @Override
    public String[] getStatements() throws SQLException {
        Map<String, Object> detail = getDetail();
        String partitionExpression = (String) detail.get("PARTITION_EXPRESSION");
        if (!Boolean.TRUE.equals(detail.get("IS_PARTITIONED"))) {
            return new String[]{partitionExpression};
        }
        String partitionClause = getDialect().getDefinePartitionTableStatement(getCatalogName(), getSchemaName(),
                getDialect().getTargetTableName(tableName), (String) detail.get("PARTITION_TYPE"),
                (String) detail.get("PARTITION_COLUMN_NAMES"), detail);
        return StringUtils.isBlank(partitionClause) ? new String[]{partitionExpression}
                : new String[]{partitionExpression, partitionClause};
    }
}