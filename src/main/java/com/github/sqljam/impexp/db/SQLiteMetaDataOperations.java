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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.impexp.TableMetaData;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.utils.CaseInsensitiveMap;

/**
 * @Description: SQLiteMetaDataOperations derives jdbc types from declared types and reads generated columns of SQLite
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SQLiteMetaDataOperations extends MetaDataOperations {

    /**
     * SQLite database file has no catalogs, an empty catalog name is used as the only one
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
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        return super.getTableInfos(databaseMetaData, null, null).stream()
                .filter(info -> !StringUtils.startsWith((String) info.get("TABLE_NAME"), "sqlite_"))
                .collect(Collectors.toList());
    }

    /**
     * SQLite driver reports most columns as VARCHAR, the jdbc type is derived from the declared type name by the
     * type affinity rules of SQLite
     */
    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, null, null, tableName);
        String createSql = null;
        for (Map<String, Object> columnInfo : columnInfos) {
            resolveColumnType(columnInfo);
            if ("YES".equalsIgnoreCase((String) columnInfo.get("IS_GENERATEDCOLUMN"))) {
                if (createSql == null) {
                    Map<String, Object> table = JdbcUtils.fetchOne(databaseMetaData.getConnection(),
                            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", new Object[]{tableName});
                    createSql = table != null ? StringUtils.defaultString((String) table.get("sql")) : "";
                }
                String[] generation = parseGeneration(createSql, (String) columnInfo.get("COLUMN_NAME"));
                if (generation != null) {
                    columnInfo.put("GENERATION_EXPRESSION", generation[0]);
                    columnInfo.put("GENERATION_TYPE", generation[1]);
                }
            }
        }
        return columnInfos;
    }

    public static void resolveColumnType(Map<String, Object> columnInfo) {
        String declaredType = StringUtils.defaultString((String) columnInfo.get("TYPE_NAME")).trim()
                .toUpperCase(Locale.ENGLISH);
        String baseType = declaredType.replaceAll("\\(.*\\)", "").trim();
        int[] sizes = parseSizes(declaredType);
        if (declaredType.indexOf('(') < 0) {
            // Driver reports declared size as COLUMN_SIZE, unknown size as 2000000000
            int columnSize = TableMetaData.getInt(columnInfo, "COLUMN_SIZE");
            int scale = TableMetaData.getInt(columnInfo, "DECIMAL_DIGITS");
            sizes = columnSize > 0 && columnSize < 2000000000 ? new int[]{columnSize, scale} : new int[]{0, 0};
        }
        int dataType;
        int columnSize = sizes[0];
        int scale = sizes[1];
        switch (baseType) {
            case "INTEGER":
            case "BIGINT":
            case "INT8":
            case "UNSIGNED BIG INT":
                dataType = Types.BIGINT;
                break;
            case "INT":
            case "MEDIUMINT":
            case "INT2":
                dataType = baseType.equals("INT2") ? Types.SMALLINT : Types.INTEGER;
                break;
            case "TINYINT":
                dataType = Types.TINYINT;
                break;
            case "SMALLINT":
                dataType = Types.SMALLINT;
                break;
            case "REAL":
            case "FLOAT":
            case "DOUBLE":
            case "DOUBLE PRECISION":
                dataType = Types.DOUBLE;
                break;
            case "NUMERIC":
            case "DECIMAL":
                dataType = Types.NUMERIC;
                break;
            case "BOOLEAN":
            case "BOOL":
                dataType = Types.BOOLEAN;
                break;
            case "DATE":
                dataType = Types.DATE;
                break;
            case "DATETIME":
            case "TIMESTAMP":
                dataType = Types.TIMESTAMP;
                break;
            case "TIME":
                dataType = Types.TIME;
                break;
            case "BLOB":
                dataType = Types.BLOB;
                break;
            case "TEXT":
            case "CLOB":
                dataType = Types.CLOB;
                break;
            case "CHAR":
            case "CHARACTER":
            case "NCHAR":
            case "NATIVE CHARACTER":
                dataType = Types.CHAR;
                break;
            default:
                // Type affinity rules of SQLite
                if (baseType.contains("INT")) {
                    dataType = Types.BIGINT;
                } else if (baseType.contains("CHAR") || baseType.contains("CLOB") || baseType.contains("TEXT")) {
                    dataType = columnSize > 0 ? Types.VARCHAR : Types.CLOB;
                } else if (baseType.isEmpty() || "ANY".equals(baseType)) {
                    // Columns without type (or ANY) keep values of any type, they are text of other databases
                    dataType = Types.VARCHAR;
                    columnSize = Integer.MAX_VALUE;
                } else if (baseType.contains("BLOB")) {
                    dataType = Types.BLOB;
                } else if (baseType.contains("REAL") || baseType.contains("FLOA") || baseType.contains("DOUB")) {
                    dataType = Types.DOUBLE;
                } else {
                    dataType = Types.VARCHAR;
                }
                break;
        }
        columnInfo.put("DATA_TYPE", dataType);
        columnInfo.put("COLUMN_SIZE", columnSize);
        columnInfo.put("DECIMAL_DIGITS", scale);
    }

    /**
     * Expression and type (STORED/VIRTUAL) of a generated column parsed from CREATE TABLE statement
     */
    public static String[] parseGeneration(String createSql, String columnName) {
        Matcher matcher = Pattern.compile("(?is)[\\s(,][\"`\\[]?"
                + Pattern.quote(columnName) + "[\"`\\]]?\\s[^,]*?\\bAS\\s*\\(").matcher(createSql);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end();
        int depth = 1;
        int index = start;
        while (index < createSql.length() && depth > 0) {
            char c = createSql.charAt(index);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            index++;
        }
        String expression = createSql.substring(start, index - 1).trim();
        String rest = createSql.substring(index).trim().toUpperCase(Locale.ENGLISH);
        return new String[]{expression, rest.startsWith("STORED") ? "STORED" : "VIRTUAL"};
    }

    private static int[] parseSizes(String declaredType) {
        int start = declaredType.indexOf('(');
        int end = declaredType.indexOf(')');
        if (start < 0 || end < start) {
            return new int[]{0, 0};
        }
        String[] parts = declaredType.substring(start + 1, end).split(",");
        try {
            int size = Integer.parseInt(parts[0].trim());
            int scale = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return new int[]{size, scale};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    @Override
    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName, String tableName) throws SQLException {
        return super.getPrimaryKeyInfos(databaseMetaData, null, null, tableName);
    }

    @Override
    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName, String tableName) throws SQLException {
        return super.getIndexInfos(databaseMetaData, null, null, tableName);
    }

    @Override
    public List<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                         String schemaName, String tableName) throws SQLException {
        return super.getImportedKeyInfos(databaseMetaData, null, null, tableName);
    }
}
