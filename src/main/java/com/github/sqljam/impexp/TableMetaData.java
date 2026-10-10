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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.utils.MapUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: TableMetaData is a table of a schema, it loads columns, primary keys, indexes, comments, foreign keys and partitions
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class TableMetaData implements TiedMetaData {

    private final String tableName;
    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;

    public TableMetaData(String tableName, Map<String, Object> detail, TiedMetaData tiedMetaData) {
        this.tableName = tableName;
        this.detail = detail;
        this.tiedMetaData = tiedMetaData;
    }

    private final List<ColumnMetaData> columnMetaDatas = new ArrayList<>();
    private final List<PrimaryKeyMetaData> primaryKeyMetaDatas = new ArrayList<>();
    private final Map<String, List<IndexMetaData>> indexMetaDatas = new LinkedHashMap<>();
    private final List<PartitionMetaData> partitionMetaDatas = new ArrayList<>();
    private final List<ForeignKeyMetaData> foreignKeyMetaDatas = new ArrayList<>();
    private final List<String> primaryKeyColumnNames = new ArrayList<>();

    public Optional<ColumnMetaData> findColumnMetaData(String columnName) {
        return columnMetaDatas.stream().filter(md -> md.getColumnName().equals(columnName)).findFirst();
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        if (isPartitionTable()) {
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("Begin to process table: {}", tableName);
        }
        Exporter.ExportConfiguration configuration = visitor.getConfiguration();
        TableQuery query = configuration != null ? configuration.getTableQuery(tableName) : null;
        // Selected columns of a query, all columns if none
        Predicate<String> selected = columnName -> query == null || query.getColumns().isEmpty()
                || query.getColumns().contains(columnName);
        final boolean partitioned = isPartitioned();
        DatabaseMetaData databaseMetaData = getMetaData();
        List<Map<String, Object>> infoList = null;
        // Primary keys are loaded before columns, identity columns depend on them
        if (!partitioned || isInlinePartitioned()) {
            infoList = getMetaDataOperations().getPrimaryKeyInfos(databaseMetaData, getCatalogName(),
                    getSchemaName(), tableName);
            if (CollectionUtils.isNotEmpty(infoList)) {
                List<Map<String, Object>> pkInfos = infoList.stream()
                        .sorted(Comparator.comparingInt(info -> getInt(info, "KEY_SEQ")))
                        .collect(Collectors.toList());
                List<String> keyColumnNames = pkInfos.stream().map(info -> (String) info.get("COLUMN_NAME"))
                        .collect(Collectors.toList());
                // A primary key is kept if all of its columns are selected
                if (keyColumnNames.stream().allMatch(selected)) {
                    primaryKeyColumnNames.addAll(keyColumnNames);
                    // Composite primary key is one constraint
                    String columnNames = StringUtils.join(primaryKeyColumnNames, ",");
                    primaryKeyMetaDatas.add(new PrimaryKeyMetaData(columnNames, pkInfos.get(0), this));
                }
            }
        }

        infoList = getMetaDataOperations().getColumnInfos(databaseMetaData,
                getCatalogName(), getSchemaName(), tableName);
        for (Map<String, Object> columnInfo : infoList) {
            String columnName = (String) columnInfo.get("COLUMN_NAME");
            if (selected.test(columnName)) {
                columnMetaDatas.add(new ColumnMetaData(columnName, columnInfo, this));
            }
        }
        for (ColumnMetaData columnMetaData : columnMetaDatas) {
            columnMetaData.accept(visitor);
        }

        if (!partitioned || isInlinePartitioned()) {
            for (PrimaryKeyMetaData primaryKeyMetaData : primaryKeyMetaDatas) {
                primaryKeyMetaData.accept(visitor);
            }
        }

        if (configuration == null || configuration.isIndexIncluded()) {
            infoList = getMetaDataOperations().getIndexInfos(databaseMetaData, getCatalogName(),
                    getSchemaName(), tableName);
            for (Map<String, Object> indexInfo : infoList) {
                String indexName = (String) indexInfo.get("INDEX_NAME");
                String columnName = (String) indexInfo.get("COLUMN_NAME");
                List<IndexMetaData> subList = MapUtils.getOrCreate(indexMetaDatas, indexName, ArrayList::new);
                subList.add(new IndexMetaData(indexName, columnName, indexInfo, this));
            }
            // Indexes are kept if all of their columns are selected
            indexMetaDatas.values().removeIf(indexes -> !indexes.stream().map(IndexMetaData::getColumnName)
                    .allMatch(selected));
            removeDuplicateIndexes();
            for (Map.Entry<String, List<IndexMetaData>> entry : indexMetaDatas.entrySet()) {
                if (entry.getValue().size() == 1) {
                    entry.getValue().get(0).accept(visitor);
                } else if (entry.getValue().size() > 1) {
                    new CombinedIndexMetaData(entry.getKey(), entry.getValue(), this).accept(visitor);
                }
            }
        }

        String remarks = (String) detail.get("REMARKS");
        if (StringUtils.isNotBlank(remarks) && (configuration == null || configuration.isCommentIncluded())) {
            new CommentMetaData(null, detail, this).accept(visitor);
        }

        if (configuration == null || configuration.isForeignKeyIncluded()) {
            infoList = getMetaDataOperations().getImportedKeyInfos(databaseMetaData, getCatalogName(),
                    getSchemaName(), tableName);
            Map<String, List<Map<String, Object>>> fkGroups = new LinkedHashMap<>();
            for (Map<String, Object> fkInfo : infoList) {
                String fkName = (String) fkInfo.get("FK_NAME");
                if (StringUtils.isBlank(fkName)) {
                    fkName = String.format("fk_%s_%s", tableName, fkInfo.get("PKTABLE_NAME"));
                }
                MapUtils.getOrCreate(fkGroups, fkName, ArrayList::new).add(fkInfo);
            }
            // Foreign keys are kept if all of their columns are selected
            fkGroups.values().removeIf(fkInfos -> !fkInfos.stream().map(info -> (String) info.get("FKCOLUMN_NAME"))
                    .allMatch(selected));
            for (Map.Entry<String, List<Map<String, Object>>> entry : fkGroups.entrySet()) {
                List<Map<String, Object>> fkInfos = entry.getValue().stream()
                        .sorted(Comparator.comparingInt(info -> getInt(info, "KEY_SEQ")))
                        .collect(Collectors.toList());
                foreignKeyMetaDatas.add(new ForeignKeyMetaData(entry.getKey(), fkInfos, this));
            }
            for (ForeignKeyMetaData foreignKeyMetaData : foreignKeyMetaDatas) {
                foreignKeyMetaData.accept(visitor);
            }
        }

        if (partitioned) {
            partitionMetaDatas.add(new PartitionMetaData(detail, this));
        }
        for (PartitionMetaData partitionMetaData : partitionMetaDatas) {
            partitionMetaData.accept(visitor);
        }

        visitor.visit(this);

        if (log.isInfoEnabled()) {
            log.info("End to process table: {}", tableName);
        }
    }

    public static int getInt(Map<String, Object> info, String key) {
        Object value = info.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value != null && StringUtils.isNumeric(value.toString())) {
            return Integer.parseInt(value.toString());
        }
        return 0;
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
    public Map<String, Object> getDetail() {
        return detail;
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
    public String[] getStatements() throws SQLException {
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        String tableName = getDialect().getTargetTableName(getTableName());
        String statement = getDialect().getCreateTableStatement(catalogName, schemaName, tableName);
        return new String[]{statement};
    }

    public List<ColumnMetaData> getColumnMetaDatas() {
        return columnMetaDatas;
    }

    public List<PrimaryKeyMetaData> getPrimaryKeyMetaDatas() {
        return primaryKeyMetaDatas;
    }

    public List<ForeignKeyMetaData> getForeignKeyMetaDatas() {
        return foreignKeyMetaDatas;
    }

    public List<String> getPrimaryKeyColumnNames() {
        return primaryKeyColumnNames;
    }

    /**
     * Indexes of the same columns, e.g. an index of H2 for a foreign key next to an index created by the user, would
     * get the same name in the target database, which also refuses a second index of the same columns. One index of
     * the columns is kept, a unique index before others.
     */
    private void removeDuplicateIndexes() {
        Map<List<String>, String> indexNames = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexMetaData>> entry : new ArrayList<>(indexMetaDatas.entrySet())) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            List<String> columnNames = entry.getValue().stream().map(IndexMetaData::getColumnName)
                    .collect(Collectors.toList());
            String existing = indexNames.get(columnNames);
            if (existing == null) {
                indexNames.put(columnNames, entry.getKey());
            } else if (isUniqueIndex(entry.getValue()) && !isUniqueIndex(indexMetaDatas.get(existing))) {
                indexMetaDatas.remove(existing);
                indexNames.put(columnNames, entry.getKey());
            } else {
                indexMetaDatas.remove(entry.getKey());
            }
        }
    }

    private static boolean isUniqueIndex(List<IndexMetaData> indexes) {
        return IndexMetaData.isUnique(indexes.get(0).getDetail());
    }

    public List<IndexMetaData> getIndexMetaDatas() {
        List<IndexMetaData> total = new ArrayList<>();
        for (List<IndexMetaData> subList : indexMetaDatas.values()) {
            total.addAll(subList);
        }
        return total;
    }

    public boolean isAutoIncrementColumn(String columnName) {
        return columnMetaDatas.stream().anyMatch(
                md -> md.getColumnName().equals(columnName) &&
                        "YES".equalsIgnoreCase((String) md.getDetail().get("IS_AUTOINCREMENT")));
    }

    public List<String> getAutoIncrementColumnNames() {
        return columnMetaDatas.stream()
                .filter(md -> "YES".equalsIgnoreCase((String) md.getDetail().get("IS_AUTOINCREMENT")))
                .map(ColumnMetaData::getColumnName).collect(Collectors.toList());
    }

    /**
     * Whether the column value should be written into target table. Generated columns of the same database type are
     * computed by target database.
     */
    public boolean isInsertableColumn(String columnName, Dialect dialect) {
        Optional<ColumnMetaData> opt = findColumnMetaData(columnName);
        return !(opt.isPresent() && opt.get().isMaintainedByDatabase(dialect));
    }

    /**
     * Identity columns rendered by the dialect. Some databases allow only one identity column per table (MySQL,
     * SQL Server, Oracle, H2) and some require it to be the primary key (MySQL, SQLite), other auto increment
     * columns are created as normal columns.
     */
    public List<String> getIncrementalColumnNames(Dialect dialect) {
        List<String> columnNames = getAutoIncrementColumnNames();
        if (columnNames.isEmpty()) {
            return columnNames;
        }
        String primaryKey = primaryKeyColumnNames.size() == 1 ? primaryKeyColumnNames.get(0) : null;
        if (dialect.isIncrementalColumnKeyRequired()) {
            return primaryKey != null && columnNames.contains(primaryKey) ? List.of(primaryKey) : List.of();
        }
        if (dialect.isSingleIncrementalColumn() && columnNames.size() > 1) {
            return List.of(primaryKey != null && columnNames.contains(primaryKey) ? primaryKey : columnNames.get(0));
        }
        return columnNames;
    }

    public boolean isPrimaryKeyColumn(String columnName) {
        return primaryKeyColumnNames.contains(columnName);
    }

    public boolean isPartitioned() {
        return detail.containsKey("IS_PARTITIONED") && (Boolean) detail.get("IS_PARTITIONED");
    }

    /**
     * Partitions are defined inside the create table statement (MySQL, Oracle, SQL Server), while PostgreSQL defines
     * partitions as separated tables.
     */
    public boolean isInlinePartitioned() {
        return detail.containsKey("PARTITION_INLINE") && (Boolean) detail.get("PARTITION_INLINE");
    }

    public boolean isPartitionTable() {
        return detail.containsKey("IS_PARTITION_TABLE") && (Boolean) detail.get("IS_PARTITION_TABLE");
    }
}
