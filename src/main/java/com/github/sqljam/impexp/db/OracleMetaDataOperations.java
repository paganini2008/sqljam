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
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.utils.CaseInsensitiveMap;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: OracleMetaDataOperations reads comments, identity/virtual columns, partitions and sequences of Oracle
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class OracleMetaDataOperations extends MetaDataOperations {

    /**
     * Oracle has no catalogs, an empty catalog name is used as the only one
     */
    @Override
    public List<Map<String, Object>> getCatalogInfos(DatabaseMetaData databaseMetaData) throws SQLException {
        Map<String, Object> catalogInfo = new CaseInsensitiveMap<>();
        catalogInfo.put("TABLE_CAT", "");
        List<Map<String, Object>> catalogInfos = new ArrayList<>();
        catalogInfos.add(catalogInfo);
        return catalogInfos;
    }

    @Override
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        List<Map<String, Object>> schemaInfos = super.getSchemaInfos(databaseMetaData, null);
        Set<String> userSchemas = getUserSchemas(databaseMetaData.getConnection());
        if (userSchemas.isEmpty()) {
            return schemaInfos;
        }
        return schemaInfos.stream().filter(info -> userSchemas.contains((String) info.get("TABLE_SCHEM")))
                .collect(Collectors.toList());
    }

    private Set<String> getUserSchemas(Connection connection) {
        try {
            List<Map<String, Object>> users = JdbcUtils.fetchAll(connection,
                    "SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'N'");
            return users.stream().map(user -> (String) user.get("USERNAME")).collect(Collectors.toSet());
        } catch (SQLException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to query user schemas: {}", e.getMessage());
            }
            return Collections.emptySet();
        }
    }

    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        List<Map<String, Object>> tableInfos = super.getTableInfos(databaseMetaData, null, schemaName).stream()
                .filter(info -> !StringUtils.startsWith((String) info.get("TABLE_NAME"), "BIN$"))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(tableInfos)) {
            return tableInfos;
        }
        Connection connection = databaseMetaData.getConnection();
        List<Map<String, Object>> comments = JdbcUtils.fetchAll(connection,
                "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND COMMENTS IS NOT NULL",
                new Object[]{schemaName});
        Map<String, Object> commentMap = new HashMap<>();
        comments.forEach(comment -> commentMap.put((String) comment.get("TABLE_NAME"), comment.get("COMMENTS")));

        List<Map<String, Object>> partitionTables = JdbcUtils.fetchAll(connection,
                "SELECT TABLE_NAME, PARTITIONING_TYPE, INTERVAL FROM ALL_PART_TABLES WHERE OWNER = ?",
                new Object[]{schemaName});
        Map<String, Map<String, Object>> partitionTableMap = partitionTables.stream().collect(
                Collectors.toMap(info -> (String) info.get("TABLE_NAME"), Function.identity(), (a, b) -> a));
        for (Map<String, Object> tableInfo : tableInfos) {
            String tableName = (String) tableInfo.get("TABLE_NAME");
            if (StringUtils.isBlank((String) tableInfo.get("REMARKS")) && commentMap.containsKey(tableName)) {
                tableInfo.put("REMARKS", commentMap.get(tableName));
            }
            Map<String, Object> partitionTable = partitionTableMap.get(tableName);
            if (partitionTable != null) {
                String partitionType = (String) partitionTable.get("PARTITIONING_TYPE");
                String columnNames = getPartitionColumnNames(connection, schemaName, tableName);
                tableInfo.put("IS_PARTITIONED", true);
                tableInfo.put("PARTITION_INLINE", true);
                tableInfo.put("PARTITION_TYPE", partitionType);
                tableInfo.put("PARTITION_COLUMN_NAMES", columnNames);
                tableInfo.put("PARTITION_CLAUSE", getPartitionClause(connection, schemaName, tableName,
                        partitionType, columnNames, (String) partitionTable.get("INTERVAL")));
            }
        }
        return tableInfos;
    }

    private String getPartitionColumnNames(Connection connection, String schemaName, String tableName)
            throws SQLException {
        List<Map<String, Object>> columns = JdbcUtils.fetchAll(connection,
                "SELECT COLUMN_NAME FROM ALL_PART_KEY_COLUMNS WHERE OWNER = ? AND NAME = ? AND OBJECT_TYPE = 'TABLE'"
                        + " ORDER BY COLUMN_POSITION", new Object[]{schemaName, tableName});
        return columns.stream().map(column -> quote((String) column.get("COLUMN_NAME")))
                .collect(Collectors.joining(","));
    }

    /**
     * Builds partition clause, e.g. PARTITION BY RANGE (ID) (PARTITION P1 VALUES LESS THAN (100))
     */
    private String getPartitionClause(Connection connection, String schemaName, String tableName, String partitionType,
                                      String columnNames, String interval) throws SQLException {
        List<Map<String, Object>> partitions = JdbcUtils.fetchAll(connection,
                "SELECT PARTITION_NAME, HIGH_VALUE FROM ALL_TAB_PARTITIONS WHERE TABLE_OWNER = ? AND TABLE_NAME = ?"
                        + " ORDER BY PARTITION_POSITION", new Object[]{schemaName, tableName});
        StringBuilder clause = new StringBuilder();
        clause.append(String.format("PARTITION BY %s (%s)", partitionType, columnNames));
        if (StringUtils.isNotBlank(interval)) {
            clause.append(String.format(" INTERVAL (%s)", interval));
        }
        List<String> definitions = new ArrayList<>();
        for (Map<String, Object> partition : partitions) {
            String partitionName = quote((String) partition.get("PARTITION_NAME"));
            String highValue = StringUtils.trim((String) partition.get("HIGH_VALUE"));
            switch (partitionType.toUpperCase(Locale.ENGLISH)) {
                case "RANGE":
                    // System generated partitions of interval partitioning are created automatically
                    if (StringUtils.isNotBlank(interval) && partitionName.startsWith("SYS_P")) {
                        continue;
                    }
                    definitions.add(String.format("PARTITION %s VALUES LESS THAN (%s)", partitionName, highValue));
                    break;
                case "LIST":
                    definitions.add(String.format("PARTITION %s VALUES (%s)", partitionName, highValue));
                    break;
                default:
                    definitions.add(String.format("PARTITION %s", partitionName));
                    break;
            }
        }
        if (!definitions.isEmpty()) {
            clause.append(" (").append(StringUtils.join(definitions, ", ")).append(")");
        }
        return clause.toString();
    }

    private static String quote(String name) {
        return name.matches("[A-Z][A-Z0-9_$#]*") ? name : "\"" + name + "\"";
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, null, schemaName, tableName);
        if (CollectionUtils.isEmpty(columnInfos)) {
            return columnInfos;
        }
        String sql = "SELECT c.COLUMN_NAME, c.IDENTITY_COLUMN, c.VIRTUAL_COLUMN, m.COMMENTS FROM ALL_TAB_COLS c"
                + " LEFT JOIN ALL_COL_COMMENTS m ON m.OWNER = c.OWNER AND m.TABLE_NAME = c.TABLE_NAME"
                + " AND m.COLUMN_NAME = c.COLUMN_NAME WHERE c.OWNER = ? AND c.TABLE_NAME = ?";
        List<Map<String, Object>> columnDetails = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{schemaName, tableName});
        Map<String, Map<String, Object>> columnDetailMap = columnDetails.stream().collect(
                Collectors.toMap(info -> (String) info.get("COLUMN_NAME"), Function.identity(), (a, b) -> a));
        for (Map<String, Object> columnInfo : columnInfos) {
            String defaultValue = (String) columnInfo.get("COLUMN_DEF");
            if (defaultValue != null) {
                columnInfo.put("COLUMN_DEF", StringUtils.trimToNull(defaultValue));
            }
            Map<String, Object> columnDetail = columnDetailMap.get(columnInfo.get("COLUMN_NAME"));
            if (columnDetail == null) {
                continue;
            }
            if ("YES".equalsIgnoreCase((String) columnDetail.get("VIRTUAL_COLUMN"))) {
                columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                columnInfo.put("GENERATION_EXPRESSION", columnInfo.get("COLUMN_DEF"));
                columnInfo.put("GENERATION_TYPE", "VIRTUAL");
                columnInfo.put("COLUMN_DEF", null);
            } else {
                columnInfo.put("IS_GENERATEDCOLUMN", "NO");
            }
            if ("YES".equalsIgnoreCase((String) columnDetail.get("IDENTITY_COLUMN"))) {
                columnInfo.put("IS_AUTOINCREMENT", "YES");
                columnInfo.put("COLUMN_DEF", null);
            } else {
                columnInfo.put("IS_AUTOINCREMENT", "NO");
            }
            if (StringUtils.isBlank((String) columnInfo.get("REMARKS"))) {
                columnInfo.put("REMARKS", columnDetail.get("COMMENTS"));
            }
            if ("VECTOR".equalsIgnoreCase((String) columnInfo.get("TYPE_NAME"))) {
                // Vectors are text of other databases, e.g. [1.0E+000,2.5E+000]
                columnInfo.put("DATA_TYPE", Types.LONGVARCHAR);
                columnInfo.put("COLUMN_SIZE", Integer.MAX_VALUE);
            }
        }
        return columnInfos;
    }

    @Override
    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName, String tableName) throws SQLException {
        return super.getPrimaryKeyInfos(databaseMetaData, null, schemaName, tableName);
    }

    @Override
    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> indexInfos = super.getIndexInfos(databaseMetaData, null, schemaName, tableName);
        // Function based indexes (hidden columns SYS_NC...) and LOB indexes can not be migrated
        Set<String> excludedIndexNames = new HashSet<>();
        for (Map<String, Object> indexInfo : indexInfos) {
            String columnName = (String) indexInfo.get("COLUMN_NAME");
            String indexName = (String) indexInfo.get("INDEX_NAME");
            if (columnName.startsWith("SYS_NC") || columnName.startsWith("\"") || indexName.startsWith("SYS_IL")) {
                excludedIndexNames.add(indexName);
            }
        }
        return indexInfos.stream().filter(info -> !excludedIndexNames.contains((String) info.get("INDEX_NAME")))
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                         String schemaName, String tableName) throws SQLException {
        return super.getImportedKeyInfos(databaseMetaData, null, schemaName, tableName);
    }

    /**
     * Sequences of identity columns (ISEQ$$_) are recreated with the columns
     */
    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        Connection connection = databaseMetaData.getConnection();
        String sql = "SELECT SEQUENCE_NAME, LAST_NUMBER AS START_VALUE, INCREMENT_BY AS \"INCREMENT\","
                + " MIN_VALUE, MAX_VALUE, CYCLE_FLAG, CACHE_SIZE FROM ALL_SEQUENCES"
                + " WHERE SEQUENCE_OWNER = ? AND SEQUENCE_NAME NOT LIKE 'ISEQ$$%' ORDER BY SEQUENCE_NAME";
        List<Map<String, Object>> sequenceInfos = JdbcUtils.fetchAll(connection, sql, new Object[]{schemaName});
        if (sequenceInfos.isEmpty()) {
            return sequenceInfos;
        }
        // Tables using sequences by triggers
        sql = "SELECT d.REFERENCED_NAME AS SEQUENCE_NAME, t.TABLE_NAME FROM ALL_DEPENDENCIES d"
                + " JOIN ALL_TRIGGERS t ON t.OWNER = d.OWNER AND t.TRIGGER_NAME = d.NAME"
                + " WHERE d.OWNER = ? AND d.TYPE = 'TRIGGER' AND d.REFERENCED_TYPE = 'SEQUENCE'";
        Map<String, List<String>> referencedTableNames = new HashMap<>();
        for (Map<String, Object> dependency : JdbcUtils.fetchAll(connection, sql, new Object[]{schemaName})) {
            referencedTableNames.computeIfAbsent((String) dependency.get("SEQUENCE_NAME"), k -> new ArrayList<>())
                    .add((String) dependency.get("TABLE_NAME"));
        }
        for (Map<String, Object> sequenceInfo : sequenceInfos) {
            sequenceInfo.put("CYCLE", "Y".equals(sequenceInfo.get("CYCLE_FLAG")));
            List<String> tableNames = referencedTableNames.get(sequenceInfo.get("SEQUENCE_NAME"));
            if (tableNames != null) {
                sequenceInfo.put("REFERENCED_TABLE_NAMES", tableNames.toArray(new String[0]));
            }
        }
        return sequenceInfos;
    }
}
