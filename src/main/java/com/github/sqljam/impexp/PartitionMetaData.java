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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: PartitionMetaData generates the partition clause of a partitioned table and its partitions (PostgreSQL)
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class PartitionMetaData implements TiedMetaData {

    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;
    
    private final List<PartitionTableMetaData> partitionTableMetaDatas = new ArrayList<>(); 

    public PartitionMetaData(Map<String, Object> detail, TiedMetaData tiedMetaData) {
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
        // Partition definition can not be migrated across databases, the table is created as a normal table
        if (!isPartitionMigratable()) {
            if (log.isWarnEnabled()) {
                log.warn("Partition definition of table '{}' is not supported by target dialect: {}", getTableName(),
                        getDialect().getDbType());
            }
            return;
        }
        String[] partitionTableNames = (String[]) detail.get("PARTITION_TABLE_NAMES");
        if (ArrayUtils.isNotEmpty(partitionTableNames)) {
            for (String partitionTableName : partitionTableNames) {
                Optional<TableMetaData> opt = tiedMetaData.unwrap(SchemaMetaData.class)
                        .findTableMetaData(partitionTableName);
                if (opt.isPresent()) {
                    partitionTableMetaDatas.add(new PartitionTableMetaData(partitionTableName, opt.get().getDetail(),
                            this));
                }
            }
        }
        for (PartitionTableMetaData partitionTableMetaData : partitionTableMetaDatas) {
            partitionTableMetaData.accept(visitor);
        }
        visitor.visit(this);
    }

    public boolean isPartitionMigratable() throws SQLException {
        String[] statements = getStatements();
        return statements != null && StringUtils.isNotBlank(statements[0]);
    }

    public List<PartitionTableMetaData> getPartitionTableMetaDatas() {
        return partitionTableMetaDatas;
    }

    @Override
    public String[] getStatements() throws SQLException {
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        String tableName = getDialect().getTargetTableName(getTableName());
        String partitionType = (String) detail.get("PARTITION_TYPE");
        String columnNames = (String) detail.get("PARTITION_COLUMN_NAMES");
        String statement = getDialect().getDefinePartitionTableStatement(catalogName, schemaName, tableName,
                partitionType, columnNames, detail);
        return new String[]{statement};
    }

}