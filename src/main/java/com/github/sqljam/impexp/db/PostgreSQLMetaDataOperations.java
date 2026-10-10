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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.ImpExpException;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.utils.MapUtils;

/**
 * @Description: PostgreSQLMetaDataOperations reads partitions, comments and sequences of PostgreSQL
 * @Author: Fred Feng
 * @Date: 29/03/2023
 * @Version 1.0.0
 */
public class PostgreSQLMetaDataOperations extends MetaDataOperations {

    private static final String PARTITION_SQL = "SELECT c.relname, c.relkind, c.relispartition,"
            + " CASE WHEN c.relkind = 'p' THEN pg_get_partkeydef(c.oid) END AS partkey,"
            + " pc.relname AS parentname, pg_get_expr(c.relpartbound, c.oid) AS expr"
            + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " LEFT JOIN pg_inherits i ON i.inhrelid = c.oid AND c.relispartition"
            + " LEFT JOIN pg_class pc ON pc.oid = i.inhparent"
            + " WHERE n.nspname = ? AND (c.relkind = 'p' OR c.relispartition) ORDER BY c.relname";

    @Override
    protected String[] getTableTypes() {
        return new String[]{"TABLE", "PARTITIONED TABLE"};
    }

    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName, String schemaName)
            throws SQLException {
        List<Map<String, Object>> tableInfos = super.getTableInfos(databaseMetaData, catalogName, schemaName);
        // Declarative partitioning is supported since PostgreSQL 10
        if (databaseMetaData.getDatabaseMajorVersion() < 10) {
            return tableInfos;
        }
        List<Map<String, Object>> partitionInfos = JdbcUtils.fetchAll(databaseMetaData.getConnection(), PARTITION_SQL,
                new Object[]{schemaName});
        if (CollectionUtils.isEmpty(partitionInfos)) {
            return tableInfos;
        }
        Map<String, Map<String, Object>> tableInfoMap = tableInfos.stream().collect(
                Collectors.toMap(info -> (String) info.get("TABLE_NAME"), Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        Map<String, List<String>> partitionTableNames = new LinkedHashMap<>();
        for (Map<String, Object> partitionInfo : partitionInfos) {
            String tableName = (String) partitionInfo.get("relname");
            Map<String, Object> tableInfo = tableInfoMap.get(tableName);
            if (tableInfo == null) {
                continue;
            }
            String partitionKey = (String) partitionInfo.get("partkey");
            if (StringUtils.isNotBlank(partitionKey)) {
                // e.g. RANGE (created_at), LIST (lower(region))
                tableInfo.put("IS_PARTITIONED", true);
                tableInfo.put("PARTITION_TYPE", StringUtils.substringBefore(partitionKey, " ").trim());
                tableInfo.put("PARTITION_COLUMN_NAMES", StringUtils.substringBeforeLast(
                        StringUtils.substringAfter(partitionKey, "("), ")").trim());
                tableInfo.put("PARTITION_CLAUSE", "PARTITION BY " + partitionKey);
                MapUtils.getOrCreate(partitionTableNames, tableName, ArrayList::new);
            }
            if (Boolean.TRUE.equals(partitionInfo.get("relispartition"))) {
                String parentName = (String) partitionInfo.get("parentname");
                tableInfo.put("IS_PARTITION_TABLE", true);
                tableInfo.put("INHERITED_TABLE_NAME", parentName);
                tableInfo.put("PARTITION_EXPRESSION", partitionInfo.get("expr"));
                MapUtils.getOrCreate(partitionTableNames, parentName, ArrayList::new).add(tableName);
            }
        }
        for (Map.Entry<String, List<String>> entry : partitionTableNames.entrySet()) {
            Map<String, Object> tableInfo = tableInfoMap.get(entry.getKey());
            if (tableInfo != null) {
                tableInfo.put("PARTITION_TABLE_NAMES", entry.getValue().toArray(new String[0]));
            }
        }
        return tableInfos;
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName,
                                                    String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, catalogName, schemaName, tableName);
        boolean partitionTable = false;
        if (CollectionUtils.isEmpty(columnInfos) &&
                (partitionTable = isPartitionTable(databaseMetaData, schemaName, tableName))) {
            List<String> partitionTableNames = findPartitionTableNamesByInheritedTable(databaseMetaData, schemaName,
                    tableName);
            for (String partitionTableName : partitionTableNames) {
                List<Map<String, Object>> infos = super.getColumnInfos(databaseMetaData, catalogName, schemaName,
                        partitionTableName);
                if (CollectionUtils.isNotEmpty(infos)) {
                    columnInfos = infos;
                    break;
                }
            }
        }
        if (CollectionUtils.isEmpty(columnInfos)) {
            throw new ImpExpException("No columns found in table: " + tableName);
        }
        String sql = "SELECT a.attname, b.description FROM pg_attribute a"
                + " JOIN pg_class c ON a.attrelid = c.oid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " LEFT OUTER JOIN pg_description b ON a.attrelid = b.objoid AND a.attnum = b.objsubid"
                + " WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped";
        List<Map<String, Object>> columnDetails = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{schemaName, tableName});
        Map<String, Map<String, Object>> mutableColumnInfos = columnDetails.stream()
                .collect(Collectors.toMap(info -> (String) info.get("attname"), Function.identity(), (a, b) -> a));
        for (Map<String, Object> columnInfo : columnInfos) {
            if (StringUtils.isBlank((String) columnInfo.get("REMARKS"))) {
                Map<String, Object> mutableColumnInfo = mutableColumnInfos.get(columnInfo.get("COLUMN_NAME"));
                if (mutableColumnInfo != null) {
                    columnInfo.put("REMARKS", mutableColumnInfo.get("description"));
                }
            }
            if (partitionTable) {
                columnInfo.put("TABLE_NAME", tableName);
            }
            if ("YES".equalsIgnoreCase((String) columnInfo.get("IS_GENERATEDCOLUMN"))) {
                // Generation expression is reported as default value
                columnInfo.put("GENERATION_EXPRESSION", columnInfo.get("COLUMN_DEF"));
                columnInfo.put("GENERATION_TYPE", "STORED");
            }
        }
        applyUserTypes(databaseMetaData, schemaName, tableName, columnInfos);
        return columnInfos;
    }

    /**
     * Name, precision and scale of a type, e.g. numeric(10,2), timestamp(3) without time zone
     */
    private static final Pattern BASE_TYPE = Pattern.compile("^([a-z ]+?)\\s*(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?(.*)$");

    /**
     * Columns of enum and domain types: the definition of the type is kept for PostgreSQL targets, enums are text
     * and domains are their base types for other databases
     */
    private void applyUserTypes(DatabaseMetaData databaseMetaData, String schemaName, String tableName,
                                List<Map<String, Object>> columnInfos) throws SQLException {
        String sql = "SELECT a.attname, t.typname, t.typtype, tn.nspname,"
                + " format_type(t.typbasetype, t.typtypmod) AS base_type,"
                + " (SELECT string_agg(pg_get_constraintdef(k.oid), ' ') FROM pg_constraint k"
                + " WHERE k.contypid = t.oid) AS checks,"
                + " (SELECT string_agg(quote_literal(e.enumlabel), ', ' ORDER BY e.enumsortorder) FROM pg_enum e"
                + " WHERE e.enumtypid = t.oid) AS labels,"
                + " (SELECT max(length(e.enumlabel)) FROM pg_enum e WHERE e.enumtypid = t.oid) AS label_length"
                + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_type t ON t.oid = a.atttypid"
                + " JOIN pg_namespace tn ON tn.oid = t.typnamespace"
                + " WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped"
                + " AND t.typtype IN ('e', 'd')";
        Map<String, Map<String, Object>> userTypes = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{schemaName, tableName}).stream().collect(Collectors.toMap(
                row -> (String) row.get("attname"), Function.identity(), (a, b) -> a));
        if (userTypes.isEmpty()) {
            return;
        }
        for (Map<String, Object> columnInfo : columnInfos) {
            Map<String, Object> userType = userTypes.get((String) columnInfo.get("COLUMN_NAME"));
            if (userType == null) {
                continue;
            }
            String typeName = (String) userType.get("typname");
            boolean enumType = "e".equals(String.valueOf(userType.get("typtype")));
            String definition = enumType ? String.format("ENUM (%s)", userType.get("labels"))
                    : String.format("%s%s", userType.get("base_type"), StringUtils.isNotBlank(
                    (String) userType.get("checks")) ? " " + userType.get("checks") : "");
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("KIND", enumType ? "ENUM" : "DOMAIN");
            type.put("NAME", typeName);
            type.put("DEFINITION", definition);
            columnInfo.put("USER_TYPE", type);
            columnInfo.put("TYPE_NAME", typeName);
            if (enumType) {
                Object length = userType.get("label_length");
                columnInfo.put("DATA_TYPE", Types.VARCHAR);
                columnInfo.put("COLUMN_SIZE", length instanceof Number ? ((Number) length).intValue() : 255);
                columnInfo.put("DECIMAL_DIGITS", 0);
            } else {
                applyBaseType(columnInfo, (String) userType.get("base_type"));
            }
        }
    }

    /**
     * Jdbc type, size and scale of the base type of a domain, e.g. character varying(100), numeric(10,2)
     */
    static void applyBaseType(Map<String, Object> columnInfo, String baseType) {
        String type = StringUtils.defaultString(baseType).trim().toLowerCase(Locale.ENGLISH);
        Matcher matcher = BASE_TYPE.matcher(type);
        String name = matcher.matches() ? matcher.group(1).trim() : type;
        int size = matcher.matches() && matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
        int scale = matcher.matches() && matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        int dataType;
        switch (name) {
            case "character varying":
                dataType = Types.VARCHAR;
                size = size > 0 ? size : Integer.MAX_VALUE;
                break;
            case "character":
                dataType = Types.CHAR;
                size = Math.max(size, 1);
                break;
            case "smallint":
                dataType = Types.SMALLINT;
                break;
            case "integer":
                dataType = Types.INTEGER;
                break;
            case "bigint":
                dataType = Types.BIGINT;
                break;
            case "numeric":
                dataType = Types.NUMERIC;
                break;
            case "real":
                dataType = Types.REAL;
                break;
            case "double precision":
                dataType = Types.DOUBLE;
                break;
            case "boolean":
                dataType = Types.BOOLEAN;
                break;
            case "date":
                dataType = Types.DATE;
                break;
            case "timestamp without time zone":
            case "timestamp":
                dataType = Types.TIMESTAMP;
                scale = size > 0 ? size : 6;
                break;
            case "timestamp with time zone":
                dataType = Types.TIMESTAMP_WITH_TIMEZONE;
                scale = size > 0 ? size : 6;
                break;
            default:
                dataType = Types.VARCHAR;
                size = Integer.MAX_VALUE;
                break;
        }
        columnInfo.put("DATA_TYPE", dataType);
        columnInfo.put("COLUMN_SIZE", size);
        columnInfo.put("DECIMAL_DIGITS", scale);
    }

    @Override
    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName, String schemaName,
                                                   String tableName) throws SQLException {
        List<Map<String, Object>> indexInfos = super.getIndexInfos(databaseMetaData, catalogName, schemaName, tableName);
        if (CollectionUtils.isNotEmpty(indexInfos)) {
            return indexInfos;
        }
        if (isPartitionTable(databaseMetaData, schemaName, tableName)) {
            List<String> partitionTableNames = findPartitionTableNamesByInheritedTable(databaseMetaData, schemaName,
                    tableName);
            for (String partitionTableName : partitionTableNames) {
                List<Map<String, Object>> infos = super.getIndexInfos(databaseMetaData, catalogName, schemaName,
                        partitionTableName);
                if (CollectionUtils.isNotEmpty(infos)) {
                    indexInfos = infos;
                    break;
                }
            }
        }
        return indexInfos;
    }

    private boolean isPartitionTable(DatabaseMetaData databaseMetaData, String schemaName, String tableName)
            throws SQLException {
        String sql = "SELECT count(*) FROM pg_class a JOIN pg_namespace n ON n.oid = a.relnamespace"
                + " WHERE n.nspname = ? AND a.relkind = 'p' AND a.relname = ?";
        Integer count = JdbcUtils.fetchOne(databaseMetaData.getConnection(), sql, new Object[]{schemaName, tableName},
                Integer.class);
        return count != null && count > 0;
    }

    private List<String> findPartitionTableNamesByInheritedTable(DatabaseMetaData databaseMetaData, String schemaName,
                                                                 String tableName) throws SQLException {
        String sql = "SELECT c.relname FROM pg_class c JOIN pg_inherits i ON c.oid = i.inhrelid"
                + " JOIN pg_class pc ON pc.oid = i.inhparent JOIN pg_namespace pn ON pn.oid = pc.relnamespace"
                + " WHERE pn.nspname = ? AND pc.relkind = 'p' AND pc.relname = ? ORDER BY c.relname";
        List<Map<String, Object>> partitionTableInfos = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{schemaName, tableName});
        return partitionTableInfos.stream().map(data -> (String) data.get("relname")).collect(Collectors.toList());
    }

    /**
     * Sequences owned by serial or identity columns (dependency type 'a' or 'i') are recreated with the columns
     */
    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        if (databaseMetaData.getDatabaseMajorVersion() < 10) {
            return super.getSequenceInfos(databaseMetaData, catalogName, schemaName);
        }
        String sql = "SELECT s.sequencename AS SEQUENCE_NAME, COALESCE(s.last_value + s.increment_by, s.start_value)"
                + " AS START_VALUE, s.increment_by AS INCREMENT, s.min_value AS MIN_VALUE, s.max_value AS MAX_VALUE,"
                + " s.cycle AS CYCLE, s.cache_size AS CACHE_SIZE, s.data_type::text AS DATA_TYPE"
                + " FROM pg_sequences s JOIN pg_namespace n ON n.nspname = s.schemaname"
                + " JOIN pg_class c ON c.relname = s.sequencename AND c.relnamespace = n.oid"
                + " WHERE s.schemaname = ? AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid"
                + " AND d.classid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')) ORDER BY s.sequencename";
        return JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql, new Object[]{schemaName});
    }

    /**
     * System schemas are excluded
     */
    @Override
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        return super.getSchemaInfos(databaseMetaData, catalogName).stream().filter(info -> {
            String schemaName = (String) info.get("TABLE_SCHEM");
            return !"information_schema".equals(schemaName) && !schemaName.startsWith("pg_");
        }).collect(Collectors.toList());
    }
}
