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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: SQLServerMetaDataOperations reads comments, computed/rowversion columns, partitions and sequences of SQL Server
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SQLServerMetaDataOperations extends MetaDataOperations {

    private static final String[] SYSTEM_SCHEMAS = {"sys", "INFORMATION_SCHEMA", "guest", "db_owner",
            "db_accessadmin", "db_securityadmin", "db_ddladmin", "db_backupoperator", "db_datareader",
            "db_datawriter", "db_denydatareader", "db_denydatawriter"};

    public static boolean isSystemSchema(String schemaName) {
        for (String systemSchema : SYSTEM_SCHEMAS) {
            if (systemSchema.equalsIgnoreCase(schemaName)) {
                return true;
            }
        }
        return false;
    }

    private static String catalogPrefix(String catalogName) {
        return StringUtils.isNotBlank(catalogName) ? "[" + catalogName.replace("]", "]]") + "]." : "";
    }

    @Override
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        return super.getSchemaInfos(databaseMetaData, catalogName).stream()
                .filter(info -> !isSystemSchema((String) info.get("TABLE_SCHEM"))).collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        List<Map<String, Object>> tableInfos = super.getTableInfos(databaseMetaData, catalogName, schemaName);
        if (CollectionUtils.isEmpty(tableInfos)) {
            return tableInfos;
        }
        Connection connection = databaseMetaData.getConnection();
        String prefix = catalogPrefix(catalogName);
        Map<String, Object> comments = new HashMap<>();
        String sql = String.format("SELECT t.name AS table_name, CAST(ep.value AS NVARCHAR(4000)) AS comment"
                + " FROM %1$ssys.tables t JOIN %1$ssys.schemas s ON s.schema_id = t.schema_id"
                + " JOIN %1$ssys.extended_properties ep ON ep.major_id = t.object_id AND ep.minor_id = 0"
                + " AND ep.class = 1 AND ep.name = 'MS_Description' WHERE s.name = ?", prefix);
        JdbcUtils.fetchAll(connection, sql, new Object[]{schemaName})
                .forEach(info -> comments.put((String) info.get("table_name"), info.get("comment")));

        sql = String.format("SELECT t.name AS table_name, ps.name AS scheme_name, pf.name AS function_name,"
                + " pf.function_id, pf.boundary_value_on_right, c.name AS column_name"
                + " FROM %1$ssys.tables t JOIN %1$ssys.schemas s ON s.schema_id = t.schema_id"
                + " JOIN %1$ssys.indexes i ON i.object_id = t.object_id AND i.index_id <= 1"
                + " JOIN %1$ssys.partition_schemes ps ON ps.data_space_id = i.data_space_id"
                + " JOIN %1$ssys.partition_functions pf ON pf.function_id = ps.function_id"
                + " JOIN %1$ssys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id"
                + " AND ic.partition_ordinal = 1"
                + " JOIN %1$ssys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id"
                + " WHERE s.name = ?", prefix);
        List<Map<String, Object>> partitionInfos = JdbcUtils.fetchAll(connection, sql, new Object[]{schemaName});
        Map<String, Map<String, Object>> partitionInfoMap = new HashMap<>();
        partitionInfos.forEach(info -> partitionInfoMap.put((String) info.get("table_name"), info));

        for (Map<String, Object> tableInfo : tableInfos) {
            String tableName = (String) tableInfo.get("TABLE_NAME");
            if (StringUtils.isBlank((String) tableInfo.get("REMARKS")) && comments.containsKey(tableName)) {
                tableInfo.put("REMARKS", comments.get(tableName));
            }
            Map<String, Object> partitionInfo = partitionInfoMap.get(tableName);
            if (partitionInfo != null) {
                String functionName = (String) partitionInfo.get("function_name");
                boolean right = Boolean.TRUE.equals(partitionInfo.get("boundary_value_on_right"));
                tableInfo.put("IS_PARTITIONED", true);
                tableInfo.put("PARTITION_INLINE", true);
                tableInfo.put("PARTITION_TYPE", right ? "RANGE RIGHT" : "RANGE LEFT");
                tableInfo.put("PARTITION_COLUMN_NAMES", partitionInfo.get("column_name"));
                tableInfo.put("PARTITION_SCHEME", partitionInfo.get("scheme_name"));
                tableInfo.put("PARTITION_FUNCTION", functionName);
                tableInfo.put("PARTITION_FUNCTION_DDL", getPartitionFunctionDdl(connection, prefix, functionName,
                        ((Number) partitionInfo.get("function_id")).intValue(), right));
            }
        }
        return tableInfos;
    }

    private String getPartitionFunctionDdl(Connection connection, String prefix, String functionName, int functionId,
                                           boolean right) throws SQLException {
        String sql = String.format("SELECT TYPE_NAME(pp.system_type_id) AS type_name, pp.max_length, pp.precision,"
                + " pp.scale FROM %ssys.partition_parameters pp WHERE pp.function_id = ?", prefix);
        Map<String, Object> parameter = JdbcUtils.fetchOne(connection, sql, new Object[]{functionId});
        String typeName = getParameterTypeName(parameter);
        sql = String.format("SELECT CONVERT(NVARCHAR(4000), prv.value, 126) AS value,"
                + " CAST(SQL_VARIANT_PROPERTY(prv.value, 'BaseType') AS NVARCHAR(128)) AS base_type"
                + " FROM %ssys.partition_range_values prv WHERE prv.function_id = ? ORDER BY prv.boundary_id", prefix);
        List<Map<String, Object>> values = JdbcUtils.fetchAll(connection, sql, new Object[]{functionId});
        String boundaries = values.stream().map(value -> {
            String text = (String) value.get("value");
            String baseType = StringUtils.defaultString((String) value.get("base_type")).toLowerCase(Locale.ENGLISH);
            if (text == null) {
                return "NULL";
            }
            if (baseType.matches("(tiny|small|big)?int|decimal|numeric|float|real|money|smallmoney|bit")) {
                return text;
            }
            return "N'" + text.replace("'", "''") + "'";
        }).collect(Collectors.joining(", "));
        return String.format("CREATE PARTITION FUNCTION [%s](%s) AS RANGE %s FOR VALUES (%s)",
                functionName.replace("]", "]]"), typeName, right ? "RIGHT" : "LEFT", boundaries);
    }

    private static String getParameterTypeName(Map<String, Object> parameter) {
        if (parameter == null) {
            return "int";
        }
        String typeName = (String) parameter.get("type_name");
        int maxLength = ((Number) parameter.get("max_length")).intValue();
        int precision = ((Number) parameter.get("precision")).intValue();
        int scale = ((Number) parameter.get("scale")).intValue();
        switch (typeName.toLowerCase(Locale.ENGLISH)) {
            case "decimal":
            case "numeric":
                return String.format("%s(%d,%d)", typeName, precision, scale);
            case "datetime2":
            case "datetimeoffset":
            case "time":
                return String.format("%s(%d)", typeName, scale);
            case "varchar":
            case "char":
            case "varbinary":
            case "binary":
                return String.format("%s(%s)", typeName, maxLength < 0 ? "max" : String.valueOf(maxLength));
            case "nvarchar":
            case "nchar":
                return String.format("%s(%s)", typeName, maxLength < 0 ? "max" : String.valueOf(maxLength / 2));
            default:
                return typeName;
        }
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, catalogName, schemaName,
                tableName);
        if (CollectionUtils.isEmpty(columnInfos)) {
            return columnInfos;
        }
        String sql = String.format("SELECT c.name AS column_name, CAST(ep.value AS NVARCHAR(4000)) AS comment"
                + " FROM %1$ssys.columns c JOIN %1$ssys.tables t ON t.object_id = c.object_id"
                + " JOIN %1$ssys.schemas s ON s.schema_id = t.schema_id"
                + " JOIN %1$ssys.extended_properties ep ON ep.major_id = c.object_id AND ep.minor_id = c.column_id"
                + " AND ep.class = 1 AND ep.name = 'MS_Description' WHERE s.name = ? AND t.name = ?",
                catalogPrefix(catalogName));
        Map<String, Object> comments = new HashMap<>();
        JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql, new Object[]{schemaName, tableName})
                .forEach(info -> comments.put((String) info.get("column_name"), info.get("comment")));
        sql = String.format("SELECT cc.name AS column_name, cc.definition, cc.is_persisted"
                + " FROM %1$ssys.computed_columns cc JOIN %1$ssys.tables t ON t.object_id = cc.object_id"
                + " JOIN %1$ssys.schemas s ON s.schema_id = t.schema_id WHERE s.name = ? AND t.name = ?",
                catalogPrefix(catalogName));
        Map<String, Map<String, Object>> computedColumns = new HashMap<>();
        JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql, new Object[]{schemaName, tableName})
                .forEach(info -> computedColumns.put((String) info.get("column_name"), info));
        for (Map<String, Object> columnInfo : columnInfos) {
            String columnName = (String) columnInfo.get("COLUMN_NAME");
            if (StringUtils.isBlank((String) columnInfo.get("REMARKS")) && comments.containsKey(columnName)) {
                columnInfo.put("REMARKS", comments.get(columnName));
            }
            Map<String, Object> computedColumn = computedColumns.get(columnName);
            String typeName = StringUtils.defaultString((String) columnInfo.get("TYPE_NAME")).toLowerCase(Locale.ENGLISH);
            if (computedColumn != null) {
                columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                columnInfo.put("GENERATION_EXPRESSION", computedColumn.get("definition"));
                columnInfo.put("GENERATION_TYPE", Boolean.TRUE.equals(computedColumn.get("is_persisted")) ? "STORED"
                        : "VIRTUAL");
            } else if (typeName.equals("timestamp") || typeName.equals("rowversion")) {
                // Row version is maintained by database
                columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                columnInfo.put("IS_ROWVERSION", true);
            }
        }
        return columnInfos;
    }

    /**
     * Sequences are supported since SQL Server 2012
     */
    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        if (databaseMetaData.getDatabaseMajorVersion() < 11) {
            return super.getSequenceInfos(databaseMetaData, catalogName, schemaName);
        }
        String sql = String.format("SELECT seq.name AS SEQUENCE_NAME,"
                + " CAST(CASE WHEN seq.is_exhausted = 0 AND seq.current_value IS NOT NULL"
                + " AND seq.current_value <> seq.start_value"
                + " THEN CAST(seq.current_value AS DECIMAL(38,0)) + CAST(seq.increment AS DECIMAL(38,0))"
                + " ELSE CAST(seq.start_value AS DECIMAL(38,0)) END AS NVARCHAR(40)) AS START_VALUE,"
                + " CAST(seq.increment AS NVARCHAR(40)) AS INCREMENT,"
                + " CAST(seq.minimum_value AS NVARCHAR(40)) AS MIN_VALUE,"
                + " CAST(seq.maximum_value AS NVARCHAR(40)) AS MAX_VALUE, seq.is_cycling AS CYCLE,"
                + " seq.cache_size AS CACHE_SIZE, TYPE_NAME(seq.user_type_id) AS DATA_TYPE"
                + " FROM %1$ssys.sequences seq JOIN %1$ssys.schemas s ON s.schema_id = seq.schema_id"
                + " WHERE s.name = ? ORDER BY seq.name", catalogPrefix(catalogName));
        return JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql, new Object[]{schemaName});
    }
}
