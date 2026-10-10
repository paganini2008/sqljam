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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.impexp.TableMetaData;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.utils.CaseInsensitiveMap;

/**
 * @Description: ClickHouseMetaDataOperations reads databases of ClickHouse as schemas, tables of the MergeTree family
 *               and other table engines, columns by system.columns (nullability, defaults, MATERIALIZED and ALIAS
 *               columns, comments) and primary keys by the primary key expression of the table. ClickHouse has no
 *               foreign keys, unique indexes and sequences. Types special to ClickHouse are mapped to standard jdbc
 *               types, their type names are kept for ClickHouse targets.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ClickHouseMetaDataOperations extends MetaDataOperations {

    /**
     * Length of String columns of keys, whose lengths are not declared in ClickHouse
     */
    static final int KEY_COLUMN_SIZE = 255;

    /**
     * Max precision of DECIMAL which other databases hold
     */
    static final int MAX_DECIMAL_PRECISION = 38;

    private static final Set<String> INTERNAL_SCHEMAS = new HashSet<>(Arrays.asList("system",
            "information_schema"));
    private static final Set<String> NULLABLE_TYPES = new HashSet<>(Arrays.asList("variant", "dynamic", "json",
            "object"));
    private static final Pattern WRAPPER = Pattern.compile("^(Nullable|LowCardinality)\\((.*)\\)$");
    private static final Pattern PARAMETERS = Pattern.compile("^\\w+\\((.*)\\)$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[`\"]?([A-Za-z_][A-Za-z0-9_]*)[`\"]?$");

    /**
     * Types which are read as text: nested types, enums, addresses, uuid, json and integers wider than DECIMAL(38)
     */
    private static final Set<String> TEXT_READ_TYPES = new HashSet<>(Arrays.asList("array", "map", "tuple",
            "nested", "ipv4", "ipv6", "enum8", "enum16", "enum", "uuid", "json", "object", "variant", "dynamic",
            "int256", "uint256", "point", "ring", "linestring", "multilinestring", "polygon", "multipolygon",
            "time", "time64", "aggregatefunction", "simpleaggregatefunction", "intervalsecond", "intervalminute",
            "intervalhour", "intervalday", "intervalweek", "intervalmonth", "intervalquarter", "intervalyear",
            "bfloat16"));

    @Override
    protected String[] getTableTypes() {
        return new String[]{"TABLE", "LOG TABLE", "MEMORY TABLE"};
    }

    /**
     * Databases of ClickHouse are schemas, the server is the only catalog
     */
    @Override
    public List<Map<String, Object>> getCatalogInfos(DatabaseMetaData databaseMetaData) {
        Map<String, Object> catalogInfo = new CaseInsensitiveMap<>();
        catalogInfo.put("TABLE_CAT", "");
        List<Map<String, Object>> catalogInfos = new ArrayList<>();
        catalogInfos.add(catalogInfo);
        return catalogInfos;
    }

    @Override
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        return super.getSchemaInfos(databaseMetaData, null).stream()
                .filter(info -> !INTERNAL_SCHEMAS.contains(
                        StringUtils.lowerCase((String) info.get("TABLE_SCHEM"), Locale.ENGLISH)))
                .collect(Collectors.toList());
    }

    /**
     * Tables of the database, inner tables of materialized views are skipped. ENGINE_FULL keeps the table engine
     * with its sorting key, partition and settings for ClickHouse targets.
     */
    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        String schema = getSchema(databaseMetaData, schemaName);
        Map<String, String> engines = new HashMap<>();
        String sql = "SELECT name, engine_full FROM system.tables WHERE database = ?";
        for (Map<String, Object> row : JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{schema})) {
            engines.put((String) row.get("name"), (String) row.get("engine_full"));
        }
        List<Map<String, Object>> tableInfos = new ArrayList<>();
        for (Map<String, Object> info : super.getTableInfos(databaseMetaData, null, schema)) {
            String tableName = (String) info.get("TABLE_NAME");
            if (tableName == null || tableName.startsWith(".inner")) {
                continue;
            }
            Map<String, Object> tableInfo = new LinkedHashMap<>(info);
            tableInfo.put("ENGINE_FULL", engines.get(tableName));
            tableInfos.add(tableInfo);
        }
        return tableInfos;
    }

    /**
     * Columns of the primary key expression in order, expressions of columns (e.g. toDate(time)) are skipped
     */
    @Override
    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName, String tableName) throws SQLException {
        String schema = getSchema(databaseMetaData, schemaName);
        Map<String, Object> row = JdbcUtils.fetchOne(databaseMetaData.getConnection(),
                "SELECT primary_key FROM system.tables WHERE database = ? AND name = ?",
                new Object[]{schema, tableName});
        List<Map<String, Object>> keyInfos = new ArrayList<>();
        int keySeq = 1;
        for (String column : parseKeyColumns(row != null ? (String) row.get("primary_key") : null)) {
            Map<String, Object> keyInfo = new LinkedHashMap<>();
            keyInfo.put("TABLE_SCHEM", schema);
            keyInfo.put("TABLE_NAME", tableName);
            keyInfo.put("COLUMN_NAME", column);
            keyInfo.put("KEY_SEQ", keySeq++);
            keyInfo.put("PK_NAME", null);
            keyInfos.add(keyInfo);
        }
        return keyInfos;
    }

    /**
     * Columns of a key expression, e.g. "event_time, op_type". A key with expressions is not a primary key of
     * other databases, it is skipped.
     */
    static List<String> parseKeyColumns(String keyExpression) {
        String expression = StringUtils.trimToEmpty(keyExpression);
        // A key declared in parentheses is a tuple, e.g. (id) or tuple(a, b)
        if (expression.startsWith("tuple(") && expression.endsWith(")")) {
            expression = expression.substring("tuple".length());
        }
        if (expression.startsWith("(") && expression.endsWith(")")) {
            expression = expression.substring(1, expression.length() - 1).trim();
        }
        if (expression.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> columns = new ArrayList<>();
        for (String part : splitTopLevel(expression)) {
            Matcher matcher = IDENTIFIER.matcher(part.trim());
            if (!matcher.matches()) {
                return Collections.emptyList();
            }
            columns.add(matcher.group(1));
        }
        return columns;
    }

    /**
     * Data skipping indexes of ClickHouse are not indexes of other databases
     */
    @Override
    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName, String tableName) {
        return Collections.emptyList();
    }

    @Override
    public List<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                         String schemaName, String tableName) {
        return Collections.emptyList();
    }

    /**
     * Columns by system.columns: MATERIALIZED and ALIAS columns are generated columns, EPHEMERAL columns have no
     * values and are skipped. Nullable and LowCardinality wrappers are removed from type names, the nullability is
     * kept by IS_NULLABLE.
     */
    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        String schema = getSchema(databaseMetaData, schemaName);
        List<Map<String, Object>> columnInfos = new ArrayList<>(super.getColumnInfos(databaseMetaData, null, schema,
                tableName));
        String sql = "SELECT name, type, default_kind, default_expression, comment, is_in_primary_key"
                + " FROM system.columns WHERE database = ? AND table = ? ORDER BY position";
        Map<String, Map<String, Object>> systemColumns = new HashMap<>();
        for (Map<String, Object> row : JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{schema, tableName})) {
            systemColumns.put((String) row.get("name"), row);
        }
        Set<String> keyColumns = new HashSet<>();
        for (Iterator<Map<String, Object>> it = columnInfos.iterator(); it.hasNext(); ) {
            Map<String, Object> columnInfo = it.next();
            String columnName = (String) columnInfo.get("COLUMN_NAME");
            Map<String, Object> systemColumn = systemColumns.get(columnName);
            if (systemColumn == null) {
                continue;
            }
            String defaultKind = StringUtils.defaultString((String) systemColumn.get("default_kind"))
                    .toUpperCase(Locale.ENGLISH);
            if ("EPHEMERAL".equals(defaultKind)) {
                it.remove();
                continue;
            }
            String expression = StringUtils.trimToNull((String) systemColumn.get("default_expression"));
            columnInfo.put("IS_AUTOINCREMENT", "NO");
            if ("MATERIALIZED".equals(defaultKind) || "ALIAS".equals(defaultKind)) {
                columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                columnInfo.put("GENERATION_EXPRESSION", expression);
                columnInfo.put("GENERATION_TYPE", "ALIAS".equals(defaultKind) ? "VIRTUAL" : "STORED");
                columnInfo.put("COLUMN_DEF", null);
            } else {
                columnInfo.put("IS_GENERATEDCOLUMN", "NO");
                columnInfo.put("COLUMN_DEF", expression);
            }
            columnInfo.put("REMARKS", StringUtils.trimToNull((String) systemColumn.get("comment")));
            normalizeType(columnInfo, (String) systemColumn.get("type"));
            if (isTrue(systemColumn.get("is_in_primary_key"))) {
                keyColumns.add(columnName);
            }
        }
        boundKeyColumns(databaseMetaData, schema, tableName, columnInfos, keyColumns);
        return columnInfos;
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value instanceof Number ? ((Number) value).intValue() != 0 : "1".equals(String.valueOf(value));
    }

    /**
     * String columns of keys get a bounded length, so that other databases can index them: at least 255, or the
     * longest value
     */
    private void boundKeyColumns(DatabaseMetaData databaseMetaData, String schema, String tableName,
                                 List<Map<String, Object>> columnInfos, Set<String> keyColumns) throws SQLException {
        List<Map<String, Object>> stringKeys = columnInfos.stream()
                .filter(info -> keyColumns.contains((String) info.get("COLUMN_NAME")))
                .filter(info -> TableMetaData.getInt(info, "DATA_TYPE") == Types.VARCHAR
                        && TableMetaData.getInt(info, "COLUMN_SIZE") == Integer.MAX_VALUE)
                .collect(Collectors.toList());
        if (stringKeys.isEmpty()) {
            return;
        }
        String lengths = stringKeys.stream().map(info -> String.format("max(lengthUTF8(%s))",
                quote((String) info.get("COLUMN_NAME")))).collect(Collectors.joining(", "));
        List<Object> values = JdbcUtils.fetchAll(databaseMetaData.getConnection(), String.format(
                "SELECT %s FROM %s.%s", lengths, quote(schema), quote(tableName)), new Object[0]).stream()
                .findFirst().map(row -> (List<Object>) new ArrayList<>(row.values())).orElse(List.of());
        for (int i = 0; i < stringKeys.size(); i++) {
            Object length = i < values.size() ? values.get(i) : null;
            int size = length instanceof Number ? ((Number) length).intValue() : 0;
            stringKeys.get(i).put("COLUMN_SIZE", Math.max(KEY_COLUMN_SIZE, size));
        }
    }

    private static String quote(String name) {
        return "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`";
    }

    /**
     * Jdbc type, size and scale of a ClickHouse type, TYPE_NAME is the type without Nullable wrapper
     */
    static void normalizeType(Map<String, Object> columnInfo, String type) {
        String typeName = StringUtils.defaultString(type).trim();
        boolean nullable = false;
        boolean lowCardinality = false;
        Matcher wrapper = WRAPPER.matcher(typeName);
        while (wrapper.matches()) {
            if ("Nullable".equals(wrapper.group(1))) {
                nullable = true;
            } else {
                lowCardinality = true;
            }
            typeName = wrapper.group(2).trim();
            wrapper = WRAPPER.matcher(typeName);
        }
        // Variant, Dynamic and JSON values may be NULL without Nullable
        nullable = nullable || NULLABLE_TYPES.contains(getBaseType(typeName));
        columnInfo.put("IS_NULLABLE", nullable ? "YES" : "NO");
        columnInfo.put("NULLABLE", nullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);
        columnInfo.put("TYPE_NAME", lowCardinality ? "LowCardinality(" + typeName + ")" : typeName);
        String baseType = getBaseType(typeName);
        List<String> parameters = getParameters(typeName);
        int dataType;
        int size = 0;
        int scale = 0;
        switch (baseType) {
            case "bool":
            case "boolean":
                dataType = Types.BOOLEAN;
                size = 1;
                break;
            case "int8":
                dataType = Types.TINYINT;
                size = 3;
                break;
            case "int16":
            case "uint8":
                dataType = Types.SMALLINT;
                size = 5;
                break;
            case "int32":
            case "uint16":
                dataType = Types.INTEGER;
                size = 10;
                break;
            case "int64":
            case "uint32":
                dataType = Types.BIGINT;
                size = 19;
                break;
            case "uint64":
                dataType = Types.NUMERIC;
                size = 20;
                break;
            case "int128":
            case "uint128":
                dataType = Types.NUMERIC;
                size = 38;
                break;
            case "float32":
                dataType = Types.REAL;
                size = 7;
                break;
            case "float64":
                dataType = Types.DOUBLE;
                size = 15;
                break;
            case "decimal":
            case "decimal32":
            case "decimal64":
            case "decimal128":
            case "decimal256":
                dataType = Types.DECIMAL;
                size = getDecimalPrecision(baseType, parameters);
                scale = parameters.isEmpty() ? 0 : toInt(parameters.get(parameters.size() - 1), 0);
                if (size > MAX_DECIMAL_PRECISION) {
                    // Decimals wider than DECIMAL(38) of other databases are text
                    dataType = Types.VARCHAR;
                    size += 2;
                    scale = 0;
                }
                break;
            case "date":
            case "date32":
                dataType = Types.DATE;
                size = 10;
                break;
            case "datetime":
                dataType = Types.TIMESTAMP;
                size = 19;
                break;
            case "datetime64":
                dataType = Types.TIMESTAMP;
                scale = parameters.isEmpty() ? 3 : toInt(parameters.get(0), 3);
                size = 20 + scale;
                break;
            case "fixedstring":
                dataType = Types.CHAR;
                size = parameters.isEmpty() ? 1 : toInt(parameters.get(0), 1);
                break;
            case "string":
                dataType = Types.VARCHAR;
                size = Integer.MAX_VALUE;
                break;
            case "uuid":
                dataType = Types.VARCHAR;
                size = 36;
                columnInfo.put("TYPE_NAME", "UUID");
                break;
            case "ipv4":
                dataType = Types.VARCHAR;
                size = 15;
                break;
            case "ipv6":
                dataType = Types.VARCHAR;
                size = 45;
                break;
            case "enum8":
            case "enum16":
                dataType = Types.VARCHAR;
                size = Math.max(getMaxEnumLength(parameters), 1);
                break;
            default:
                // Nested types, json, geo types and integers wider than DECIMAL(38) are text of other databases
                dataType = Types.VARCHAR;
                size = Integer.MAX_VALUE;
                break;
        }
        columnInfo.put("DATA_TYPE", dataType);
        columnInfo.put("COLUMN_SIZE", size);
        columnInfo.put("DECIMAL_DIGITS", scale);
    }

    private static int getDecimalPrecision(String baseType, List<String> parameters) {
        switch (baseType) {
            case "decimal32":
                return 9;
            case "decimal64":
                return 18;
            case "decimal128":
                return 38;
            case "decimal256":
                return 76;
            default:
                return parameters.isEmpty() ? 10 : toInt(parameters.get(0), 10);
        }
    }

    private static int getMaxEnumLength(List<String> parameters) {
        int max = 0;
        for (String parameter : parameters) {
            String label = parameter.replaceAll("\\s*=\\s*-?\\d+\\s*$", "").trim();
            if (label.length() >= 2 && label.startsWith("'") && label.endsWith("'")) {
                label = label.substring(1, label.length() - 1).replace("\\'", "'").replace("''", "'");
            }
            max = Math.max(max, label.length());
        }
        return max;
    }

    private static int toInt(String text, int defaultValue) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Lower case name of a type without parameters, e.g. decimal of Decimal(18, 2)
     */
    static String getBaseType(String typeName) {
        String type = StringUtils.defaultString(typeName).trim();
        Matcher wrapper = WRAPPER.matcher(type);
        while (wrapper.matches()) {
            type = wrapper.group(2).trim();
            wrapper = WRAPPER.matcher(type);
        }
        int index = type.indexOf('(');
        return (index >= 0 ? type.substring(0, index) : type).trim().toLowerCase(Locale.ENGLISH);
    }

    private static List<String> getParameters(String typeName) {
        Matcher matcher = PARAMETERS.matcher(typeName);
        return matcher.matches() ? splitTopLevel(matcher.group(1)) : Collections.emptyList();
    }

    /**
     * Splits by commas which are not inside parentheses or quotes
     */
    static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && (i == 0 || text.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                parts.add(part.toString().trim());
                part.setLength(0);
                continue;
            }
            part.append(c);
        }
        if (part.length() > 0) {
            parts.add(part.toString().trim());
        }
        return parts;
    }

    /**
     * Whether values of the type are read as text, which every database accepts
     */
    public static boolean isTextReadType(String typeName) {
        String baseType = getBaseType(typeName);
        if (baseType.startsWith("decimal")) {
            Map<String, Object> columnInfo = new HashMap<>();
            normalizeType(columnInfo, typeName);
            return TableMetaData.getInt(columnInfo, "DATA_TYPE") == Types.VARCHAR;
        }
        return TEXT_READ_TYPES.contains(baseType);
    }

    /**
     * The database of the connection if the schema is not given
     */
    private static String getSchema(DatabaseMetaData databaseMetaData, String schemaName) throws SQLException {
        return StringUtils.isNotBlank(schemaName) ? schemaName : databaseMetaData.getConnection().getSchema();
    }
}
