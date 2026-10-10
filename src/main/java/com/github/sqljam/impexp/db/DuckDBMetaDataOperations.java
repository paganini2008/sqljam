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

import java.sql.Array;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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

/**
 * @Description: DuckDBMetaDataOperations reads tables of the database file (internal databases and schemas are
 *               skipped), identity columns (nextval defaults of table_column_seq sequences), generated columns and
 *               sequences of DuckDB. Types special to DuckDB are mapped to standard jdbc types, their type names are
 *               kept for DuckDB targets.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class DuckDBMetaDataOperations extends MetaDataOperations {

    /**
     * Length of VARCHAR columns of keys and indexes, whose lengths are not kept by DuckDB
     */
    static final int KEY_COLUMN_SIZE = 255;

    private static final Set<String> INTERNAL_SCHEMAS = new HashSet<>(Arrays.asList("information_schema",
            "pg_catalog"));
    private static final Pattern NEXTVAL = Pattern.compile("(?i)^nextval\\('(?:\"?([^.'\"]+)\"?\\.)?\"?([^'\"]+)\"?'\\)$");

    @Override
    protected String[] getTableTypes() {
        return new String[]{"BASE TABLE", "TABLE"};
    }

    /**
     * The database of the connection, internal databases (system, temp) are skipped
     */
    @Override
    public List<Map<String, Object>> getCatalogInfos(DatabaseMetaData databaseMetaData) throws SQLException {
        String current = databaseMetaData.getConnection().getCatalog();
        return super.getCatalogInfos(databaseMetaData).stream()
                .filter(info -> StringUtils.equals(current, (String) info.get("TABLE_CAT")))
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        String catalog = getCatalog(databaseMetaData, catalogName);
        return super.getSchemaInfos(databaseMetaData, catalog).stream()
                .filter(info -> catalog.equals(info.get("TABLE_CATALOG")))
                .filter(info -> !INTERNAL_SCHEMAS.contains(
                        StringUtils.lowerCase((String) info.get("TABLE_SCHEM"), Locale.ENGLISH)))
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        return super.getTableInfos(databaseMetaData, getCatalog(databaseMetaData, catalogName), schemaName);
    }

    @Override
    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName, String tableName) throws SQLException {
        return super.getPrimaryKeyInfos(databaseMetaData, getCatalog(databaseMetaData, catalogName), schemaName,
                tableName);
    }

    @Override
    public List<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                         String schemaName, String tableName) throws SQLException {
        return super.getImportedKeyInfos(databaseMetaData, getCatalog(databaseMetaData, catalogName), schemaName,
                tableName);
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        String catalog = getCatalog(databaseMetaData, catalogName);
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, catalog, schemaName,
                tableName);
        String tableSql = getTableSql(databaseMetaData, catalog, schemaName, tableName);
        for (Map<String, Object> columnInfo : columnInfos) {
            String columnName = (String) columnInfo.get("COLUMN_NAME");
            String expression = getGenerationExpression(tableSql, columnName);
            if (expression != null) {
                columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                columnInfo.put("GENERATION_EXPRESSION", expression);
                columnInfo.put("GENERATION_TYPE", "VIRTUAL");
                columnInfo.put("COLUMN_DEF", null);
            } else {
                columnInfo.put("IS_GENERATEDCOLUMN", "NO");
                if (isIdentityDefault((String) columnInfo.get("COLUMN_DEF"), tableName, columnName)) {
                    columnInfo.put("IS_AUTOINCREMENT", "YES");
                    columnInfo.put("COLUMN_DEF", null);
                } else {
                    columnInfo.put("IS_AUTOINCREMENT", "NO");
                }
            }
            normalizeType(columnInfo);
        }
        boundKeyColumns(databaseMetaData, catalog, schemaName, tableName, columnInfos);
        return columnInfos;
    }

    /**
     * VARCHAR columns of primary keys and indexes get a bounded length, so that other databases can index them:
     * at least 255, or the longest value
     */
    private void boundKeyColumns(DatabaseMetaData databaseMetaData, String catalog, String schemaName,
                                 String tableName, List<Map<String, Object>> columnInfos) throws SQLException {
        Set<String> keyColumns = new HashSet<>();
        for (Map<String, Object> keyInfo : getPrimaryKeyInfos(databaseMetaData, catalog, schemaName, tableName)) {
            keyColumns.add((String) keyInfo.get("COLUMN_NAME"));
        }
        for (Map<String, Object> indexInfo : getIndexInfos(databaseMetaData, catalog, schemaName, tableName)) {
            keyColumns.add((String) indexInfo.get("COLUMN_NAME"));
        }
        for (Map<String, Object> columnInfo : columnInfos) {
            String columnName = (String) columnInfo.get("COLUMN_NAME");
            if (!keyColumns.contains(columnName) || TableMetaData.getInt(columnInfo, "DATA_TYPE") != Types.VARCHAR
                    || TableMetaData.getInt(columnInfo, "COLUMN_SIZE") < Integer.MAX_VALUE) {
                continue;
            }
            String sql = String.format("SELECT max(length(%s)) FROM %s.%s", quote(columnName), quote(schemaName),
                    quote(tableName));
            Map<String, Object> row = JdbcUtils.fetchOne(databaseMetaData.getConnection(), sql, new Object[0]);
            Object maxLength = row != null ? row.values().iterator().next() : null;
            long length = maxLength instanceof Number ? ((Number) maxLength).longValue() : 0;
            columnInfo.put("COLUMN_SIZE", (int) Math.max(KEY_COLUMN_SIZE, length));
        }
    }

    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * Indexes and unique constraints: jdbc index info of DuckDB has no column names
     */
    @Override
    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName, String tableName) throws SQLException {
        String catalog = getCatalog(databaseMetaData, catalogName);
        List<Map<String, Object>> indexInfos = new ArrayList<>();
        String indexSql = "SELECT index_name, is_unique, expressions FROM duckdb_indexes()"
                + " WHERE database_name = ? AND schema_name = ? AND table_name = ? ORDER BY index_name";
        for (Map<String, Object> row : JdbcUtils.fetchAll(databaseMetaData.getConnection(), indexSql,
                new Object[]{catalog, schemaName, tableName})) {
            List<String> columns = parseExpressions(String.valueOf(row.get("expressions")));
            if (columns == null) {
                // Indexes on expressions are skipped
                continue;
            }
            addIndexInfos(indexInfos, catalog, schemaName, tableName, (String) row.get("index_name"),
                    Boolean.TRUE.equals(row.get("is_unique")), columns);
        }
        String constraintSql = "SELECT constraint_name, constraint_column_names FROM duckdb_constraints()"
                + " WHERE database_name = ? AND schema_name = ? AND table_name = ? AND constraint_type = 'UNIQUE'"
                + " ORDER BY constraint_index";
        for (Map<String, Object> row : JdbcUtils.fetchAll(databaseMetaData.getConnection(), constraintSql,
                new Object[]{catalog, schemaName, tableName})) {
            List<String> columns = toList(row.get("constraint_column_names"));
            if (!columns.isEmpty()) {
                addIndexInfos(indexInfos, catalog, schemaName, tableName, (String) row.get("constraint_name"), true,
                        columns);
            }
        }
        return indexInfos;
    }

    private static void addIndexInfos(List<Map<String, Object>> indexInfos, String catalog, String schemaName,
                                      String tableName, String indexName, boolean unique, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            Map<String, Object> indexInfo = new LinkedHashMap<>();
            indexInfo.put("TABLE_CAT", catalog);
            indexInfo.put("TABLE_SCHEM", schemaName);
            indexInfo.put("TABLE_NAME", tableName);
            indexInfo.put("NON_UNIQUE", !unique);
            indexInfo.put("INDEX_QUALIFIER", null);
            indexInfo.put("INDEX_NAME", indexName);
            indexInfo.put("TYPE", (int) DatabaseMetaData.tableIndexOther);
            indexInfo.put("ORDINAL_POSITION", i + 1);
            indexInfo.put("COLUMN_NAME", columns.get(i));
            indexInfo.put("ASC_OR_DESC", "A");
            indexInfos.add(indexInfo);
        }
    }

    /**
     * Column names of index expressions, e.g. ['"name"', 'dept_id'], or null if an expression is not a column
     */
    static List<String> parseExpressions(String expressions) {
        String text = StringUtils.strip(StringUtils.defaultString(expressions).trim(), "[]");
        List<String> columns = new ArrayList<>();
        for (String item : text.split(",")) {
            String column = StringUtils.strip(item.trim(), "'").trim();
            if (column.startsWith("\"") && column.endsWith("\"") && column.length() > 1) {
                column = column.substring(1, column.length() - 1).replace("\"\"", "\"");
            } else if (!column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return null;
            }
            columns.add(column);
        }
        return columns.isEmpty() ? null : columns;
    }

    private static List<String> toList(Object value) throws SQLException {
        List<String> list = new ArrayList<>();
        if (value instanceof Array) {
            Object array = ((Array) value).getArray();
            if (array instanceof Object[]) {
                for (Object item : (Object[]) array) {
                    list.add(String.valueOf(item));
                }
            }
        } else if (value != null) {
            for (String item : StringUtils.strip(value.toString(), "[]").split(",")) {
                if (StringUtils.isNotBlank(item)) {
                    list.add(item.trim());
                }
            }
        }
        return list;
    }

    /**
     * Types special to DuckDB get standard jdbc types, their type names are kept
     */
    static void normalizeType(Map<String, Object> columnInfo) {
        String typeName = StringUtils.upperCase((String) columnInfo.get("TYPE_NAME"), Locale.ENGLISH);
        if (typeName == null) {
            return;
        }
        switch (typeName) {
            case "HUGEINT":
            case "UHUGEINT":
            case "VARINT":
            case "BIGNUM":
                // The widest DECIMAL of databases
                setType(columnInfo, Types.NUMERIC, 38, 0);
                break;
            case "UBIGINT":
                setType(columnInfo, Types.NUMERIC, 20, 0);
                break;
            case "UINTEGER":
                setType(columnInfo, Types.BIGINT, 64, 0);
                break;
            case "USMALLINT":
                setType(columnInfo, Types.INTEGER, 32, 0);
                break;
            case "UTINYINT":
                setType(columnInfo, Types.SMALLINT, 16, 0);
                break;
            case "BIT":
                // Bit strings, not booleans
                setType(columnInfo, Types.VARCHAR, Integer.MAX_VALUE, 0);
                break;
            case "FLOAT":
                setType(columnInfo, Types.REAL, 24, 0);
                break;
            case "TIME WITH TIME ZONE":
                setType(columnInfo, Types.TIME_WITH_TIMEZONE, 21, 6);
                break;
            case "TIME":
                setType(columnInfo, Types.TIME, 15, 6);
                break;
            case "TIMESTAMP_S":
                setType(columnInfo, Types.TIMESTAMP, 19, 0);
                break;
            case "TIMESTAMP_MS":
                setType(columnInfo, Types.TIMESTAMP, 23, 3);
                break;
            case "TIMESTAMP_NS":
                setType(columnInfo, Types.TIMESTAMP, 29, 9);
                break;
            case "TIMESTAMP":
                setType(columnInfo, Types.TIMESTAMP, 26, 6);
                break;
            case "TIMESTAMP WITH TIME ZONE":
                setType(columnInfo, Types.TIMESTAMP_WITH_TIMEZONE, 32, 6);
                break;
            default:
                if (isTextType(typeName)) {
                    // Nested types, enums, intervals and json are read as text
                    columnInfo.put("DATA_TYPE", isJsonOrUuid(typeName) ? Types.OTHER : Types.VARCHAR);
                    columnInfo.put("COLUMN_SIZE", Integer.MAX_VALUE);
                } else if (isVarchar(typeName) && TableMetaData.getInt(columnInfo, "COLUMN_SIZE") <= 0) {
                    // Lengths of VARCHAR are not kept by DuckDB, they are unbounded like text
                    columnInfo.put("COLUMN_SIZE", Integer.MAX_VALUE);
                }
                break;
        }
    }

    private static boolean isVarchar(String typeName) {
        return "VARCHAR".equals(typeName) || typeName.startsWith("VARCHAR(") || "TEXT".equals(typeName)
                || "STRING".equals(typeName) || "CHAR".equals(typeName) || "BPCHAR".equals(typeName);
    }

    private static void setType(Map<String, Object> columnInfo, int dataType, int columnSize, int scale) {
        columnInfo.put("DATA_TYPE", dataType);
        columnInfo.put("COLUMN_SIZE", columnSize);
        columnInfo.put("DECIMAL_DIGITS", scale);
    }

    private static boolean isJsonOrUuid(String typeName) {
        return "JSON".equals(typeName) || "UUID".equals(typeName);
    }

    /**
     * Types which are read as text from DuckDB: nested types (lists, structs, maps, unions), enums, intervals,
     * json and uuid
     */
    static boolean isTextType(String typeName) {
        String upper = StringUtils.upperCase(typeName, Locale.ENGLISH);
        return upper != null && (upper.endsWith("]") || upper.startsWith("STRUCT") || upper.startsWith("MAP")
                || upper.startsWith("UNION") || upper.startsWith("ENUM") || upper.equals("INTERVAL")
                || upper.equals("JSON") || upper.equals("UUID") || upper.equals("BIT")
                || upper.equals("TIME WITH TIME ZONE"));
    }

    /**
     * Types which are read as text, keeping their precision: nanoseconds of TIMESTAMP_NS
     */
    static boolean isTextReadType(String typeName) {
        return isTextType(typeName) || "TIMESTAMP_NS".equalsIgnoreCase(typeName);
    }

    /**
     * Identity column: nextval of the sequence named table_column_seq
     */
    static boolean isIdentityDefault(String defaultValue, String tableName, String columnName) {
        if (StringUtils.isBlank(defaultValue)) {
            return false;
        }
        Matcher matcher = NEXTVAL.matcher(defaultValue.trim());
        return matcher.matches() && matcher.group(2).equalsIgnoreCase(tableName + "_" + columnName + "_seq");
    }

    /**
     * Expression of a generated column in the create table statement: name type GENERATED ALWAYS AS(expr)
     */
    static String getGenerationExpression(String tableSql, String columnName) {
        if (StringUtils.isBlank(tableSql)) {
            return null;
        }
        Pattern pattern = Pattern.compile("(?i)(?:\"" + Pattern.quote(columnName.replace("\"", "\"\"")) + "\"|\\b"
                + Pattern.quote(columnName) + "\\b)\\s+[^,(]+?(?:\\([^)]*\\))?\\s+GENERATED\\s+ALWAYS\\s+AS\\s*\\(");
        Matcher matcher = pattern.matcher(tableSql);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end();
        int depth = 1;
        for (int i = start; i < tableSql.length(); i++) {
            char c = tableSql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return tableSql.substring(start, i).trim();
            }
        }
        return null;
    }

    private String getTableSql(DatabaseMetaData databaseMetaData, String catalog, String schemaName,
                               String tableName) {
        String sql = "SELECT sql FROM duckdb_tables() WHERE database_name = ? AND schema_name = ? AND table_name = ?";
        try {
            Map<String, Object> row = JdbcUtils.fetchOne(databaseMetaData.getConnection(), sql,
                    new Object[]{catalog, schemaName, tableName});
            return row != null ? (String) row.get("sql") : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Sequences of the schema except those of identity columns. START_VALUE is the next value.
     */
    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        String catalog = getCatalog(databaseMetaData, catalogName);
        String sql = "SELECT sequence_name, start_value, increment_by, min_value, max_value, cycle, last_value"
                + " FROM duckdb_sequences() WHERE database_name = ? AND schema_name = ? ORDER BY sequence_name";
        List<Map<String, Object>> rows = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{catalog, schemaName});
        Set<String> identitySequences = getIdentitySequenceNames(databaseMetaData, catalog, schemaName);
        List<Map<String, Object>> sequenceInfos = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sequenceName = (String) row.get("sequence_name");
            if (identitySequences.contains(sequenceName.toLowerCase(Locale.ENGLISH))) {
                continue;
            }
            long increment = toLong(row.get("increment_by"), 1);
            Object lastValue = row.get("last_value");
            long startValue = lastValue != null ? toLong(lastValue, 0) + increment : toLong(row.get("start_value"), 1);
            Map<String, Object> sequenceInfo = new LinkedHashMap<>();
            sequenceInfo.put("SEQUENCE_NAME", sequenceName);
            sequenceInfo.put("START_VALUE", startValue);
            sequenceInfo.put("INCREMENT", increment);
            sequenceInfo.put("MIN_VALUE", row.get("min_value"));
            sequenceInfo.put("MAX_VALUE", row.get("max_value"));
            sequenceInfo.put("CYCLE", Boolean.TRUE.equals(row.get("cycle")));
            sequenceInfo.put("CACHE_SIZE", null);
            sequenceInfo.put("DATA_TYPE", null);
            sequenceInfos.add(sequenceInfo);
        }
        return sequenceInfos;
    }

    private Set<String> getIdentitySequenceNames(DatabaseMetaData databaseMetaData, String catalog,
                                                 String schemaName) throws SQLException {
        String sql = "SELECT table_name, column_name, column_default FROM duckdb_columns()"
                + " WHERE database_name = ? AND schema_name = ? AND column_default LIKE 'nextval(%'";
        Set<String> names = new HashSet<>();
        for (Map<String, Object> row : JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{catalog, schemaName})) {
            String tableName = (String) row.get("table_name");
            String columnName = (String) row.get("column_name");
            if (isIdentityDefault((String) row.get("column_default"), tableName, columnName)) {
                names.add((tableName + "_" + columnName + "_seq").toLowerCase(Locale.ENGLISH));
            }
        }
        return names;
    }

    private static long toLong(Object value, long defaultValue) {
        return value instanceof Number ? ((Number) value).longValue() : defaultValue;
    }

    private static String getCatalog(DatabaseMetaData databaseMetaData, String catalogName) throws SQLException {
        return StringUtils.isNotBlank(catalogName) ? catalogName : databaseMetaData.getConnection().getCatalog();
    }
}
