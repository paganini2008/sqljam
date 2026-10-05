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

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.impexp.TableMetaData;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: MySQLMetaDataOperations completes column types, default values, generated columns and partitions of MySQL
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class MySQLMetaDataOperations extends MetaDataOperations {

    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        List<Map<String, Object>> tableInfos = super.getTableInfos(databaseMetaData, catalogName, schemaName);
        if (CollectionUtils.isEmpty(tableInfos)) {
            return tableInfos;
        }
        String sql = "SELECT TABLE_NAME, MAX(PARTITION_METHOD) AS PARTITION_METHOD,"
                + " MAX(PARTITION_EXPRESSION) AS PARTITION_EXPRESSION FROM information_schema.PARTITIONS"
                + " WHERE TABLE_SCHEMA = ? AND PARTITION_NAME IS NOT NULL GROUP BY TABLE_NAME";
        List<Map<String, Object>> partitionInfos = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{catalogName});
        if (CollectionUtils.isEmpty(partitionInfos)) {
            return tableInfos;
        }
        Map<String, Map<String, Object>> partitionInfoMap = partitionInfos.stream().collect(
                Collectors.toMap(info -> (String) info.get("TABLE_NAME"), Function.identity(), (a, b) -> a));
        for (Map<String, Object> tableInfo : tableInfos) {
            String tableName = (String) tableInfo.get("TABLE_NAME");
            Map<String, Object> partitionInfo = partitionInfoMap.get(tableName);
            if (partitionInfo == null) {
                continue;
            }
            tableInfo.put("IS_PARTITIONED", true);
            tableInfo.put("PARTITION_INLINE", true);
            tableInfo.put("PARTITION_TYPE", partitionInfo.get("PARTITION_METHOD"));
            tableInfo.put("PARTITION_COLUMN_NAMES", partitionInfo.get("PARTITION_EXPRESSION"));
            tableInfo.put("PARTITION_CLAUSE", getPartitionClause(databaseMetaData, catalogName, tableName));
        }
        return tableInfos;
    }

    /**
     * Partition clause in 'SHOW CREATE TABLE', e.g. PARTITION BY RANGE (`id`) (PARTITION p0 VALUES LESS THAN (100))
     */
    private String getPartitionClause(DatabaseMetaData databaseMetaData, String catalogName, String tableName)
            throws SQLException {
        String sql = String.format("SHOW CREATE TABLE `%s`.`%s`", catalogName.replace("`", "``"),
                tableName.replace("`", "``"));
        Map<String, Object> data = JdbcUtils.fetchOne(databaseMetaData.getConnection(), sql);
        String ddl = data != null ? (String) data.get("Create Table") : null;
        if (StringUtils.isBlank(ddl)) {
            return null;
        }
        int index = ddl.indexOf("PARTITION BY");
        if (index < 0) {
            return null;
        }
        String clause = ddl.substring(index).trim();
        if (clause.endsWith("*/")) {
            clause = clause.substring(0, clause.length() - 2).trim();
        }
        // Remove version comments inside partition clause
        return clause.replaceAll("/\\*!\\d+", "").replace("*/", "").replaceAll("\\s+", " ").trim();
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, catalogName, schemaName,
                tableName);
        if (CollectionUtils.isEmpty(columnInfos)) {
            return columnInfos;
        }
        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT, EXTRA, GENERATION_EXPRESSION"
                + " FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        List<Map<String, Object>> columnDetails = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{catalogName, tableName});
        Map<String, Map<String, Object>> columnDetailMap = columnDetails.stream().collect(
                Collectors.toMap(info -> (String) info.get("COLUMN_NAME"), Function.identity(), (a, b) -> a));
        for (Map<String, Object> columnInfo : columnInfos) {
            Map<String, Object> columnDetail = columnDetailMap.get(columnInfo.get("COLUMN_NAME"));
            if (columnDetail == null) {
                continue;
            }
            String columnType = (String) columnDetail.get("COLUMN_TYPE");
            if (StringUtils.isNotBlank(columnType)) {
                columnInfo.put("TYPE_NAME", columnType);
            }
            String extra = StringUtils.defaultString((String) columnDetail.get("EXTRA"));
            String expression = (String) columnDetail.get("GENERATION_EXPRESSION");
            if (StringUtils.isNotBlank(expression)) {
                columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                // information_schema escapes quotes in generation expressions
                columnInfo.put("GENERATION_EXPRESSION", expression.replace("\\'", "'"));
                columnInfo.put("GENERATION_TYPE", extra.toUpperCase(Locale.ENGLISH).contains("VIRTUAL") ? "VIRTUAL"
                        : "STORED");
                columnInfo.put("COLUMN_DEF", null);
                continue;
            }
            columnInfo.put("COLUMN_DEF", getDefaultValue((String) columnDetail.get("COLUMN_DEFAULT"), extra,
                    TableMetaData.getInt(columnInfo, "DATA_TYPE"), columnType));
        }
        return columnInfos;
    }

    /**
     * MySQL returns literal default values without quotes
     */
    static String getDefaultValue(String defaultValue, String extra, int dataType, String columnType) {
        if (defaultValue == null) {
            return null;
        }
        String result;
        String lowerExtra = extra.toLowerCase(Locale.ENGLISH);
        String lowerColumnType = StringUtils.defaultString(columnType).toLowerCase(Locale.ENGLISH);
        // MySQL 5.x has no DEFAULT_GENERATED flag for CURRENT_TIMESTAMP
        boolean currentTimestamp = defaultValue.toUpperCase(Locale.ENGLISH).matches(
                "CURRENT_TIMESTAMP(\\(\\d*\\))?|NOW\\(\\d*\\)|LOCALTIMESTAMP(\\(\\d*\\))?");
        if (currentTimestamp) {
            result = defaultValue;
        } else if (lowerExtra.contains("default_generated") || "null".equalsIgnoreCase(defaultValue)) {
            result = defaultValue;
            if (!result.startsWith("(") && !result.toUpperCase(Locale.ENGLISH).startsWith("CURRENT_")
                    && !result.toLowerCase(Locale.ENGLISH).startsWith("now(")) {
                result = "(" + result + ")";
            }
        } else if (isNumber(dataType) || lowerColumnType.startsWith("bit") || defaultValue.startsWith("b'")
                || lowerColumnType.matches("(tiny|small|medium|big)?int.*|decimal.*|float.*|double.*")) {
            result = defaultValue;
        } else {
            result = "'" + defaultValue.replace("'", "''") + "'";
        }
        int index = lowerExtra.indexOf("on update ");
        if (index >= 0) {
            result += " " + extra.substring(index).trim();
        }
        return result;
    }

    private static boolean isNumber(int dataType) {
        switch (dataType) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.BOOLEAN:
                return true;
            default:
                return false;
        }
    }
}
