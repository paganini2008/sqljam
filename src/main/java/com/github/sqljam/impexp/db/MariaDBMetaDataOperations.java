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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: MariaDBMetaDataOperations reads MariaDB like MySQL, with the differences of MariaDB: column defaults
 *               of information_schema are sql already (quoted literals, NULL, current_timestamp(), nextval), JSON
 *               columns are LONGTEXT with a json_valid check, sequences are tables of type SEQUENCE.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class MariaDBMetaDataOperations extends MySQLMetaDataOperations {

    private static final Set<String> SPATIAL_TYPES = new HashSet<>(Arrays.asList("geometry", "point",
            "linestring", "polygon", "multipoint", "multilinestring", "multipolygon", "geometrycollection"));
    private static final Pattern FRACTION = Pattern.compile("^(?:datetime|timestamp|time)\\((\\d)\\)$");
    private static final Pattern JSON_VALID = Pattern.compile("(?i)^\\s*json_valid\\(\\s*`?([^`)]+)`?\\s*\\)\\s*$");

    /**
     * JSON columns of MariaDB are LONGTEXT checked by json_valid, they are JSON of other databases
     */
    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, catalogName, schemaName,
                tableName);
        if (columnInfos.isEmpty()) {
            return columnInfos;
        }
        String sql = "SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = ?";
        Set<String> jsonColumns = new HashSet<>();
        for (Map<String, Object> row : JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{catalogName, tableName})) {
            Matcher matcher = JSON_VALID.matcher(StringUtils.defaultString((String) row.get("CHECK_CLAUSE")));
            if (matcher.matches()) {
                jsonColumns.add(matcher.group(1));
            }
        }
        for (Map<String, Object> columnInfo : columnInfos) {
            String typeName = StringUtils.defaultString((String) columnInfo.get("TYPE_NAME"))
                    .toLowerCase(Locale.ENGLISH);
            if (jsonColumns.contains((String) columnInfo.get("COLUMN_NAME"))) {
                columnInfo.put("TYPE_NAME", "json");
            }
            // The driver reports no fractional seconds, they are in the column type, e.g. datetime(6)
            Matcher fraction = FRACTION.matcher(typeName);
            if (fraction.matches()) {
                columnInfo.put("DECIMAL_DIGITS", Integer.parseInt(fraction.group(1)));
            }
            if (SPATIAL_TYPES.contains(typeName)) {
                // Spatial values are binary (WKB) as reported by the driver of MySQL
                columnInfo.put("DATA_TYPE", Types.BINARY);
                columnInfo.put("COLUMN_SIZE", 65535);
            } else if (typeName.equals("inet4") || typeName.equals("inet6")) {
                columnInfo.put("DATA_TYPE", Types.VARCHAR);
                columnInfo.put("COLUMN_SIZE", typeName.equals("inet4") ? 15 : 45);
            } else if (typeName.equals("uuid")) {
                columnInfo.put("COLUMN_SIZE", 36);
            }
        }
        return columnInfos;
    }

    /**
     * COLUMN_DEFAULT of MariaDB is sql: 'text', 1.5, NULL, current_timestamp(), nextval(`db`.`seq`)
     */
    @Override
    protected String getColumnDefault(String defaultValue, String extra, int dataType, String columnType) {
        if (defaultValue == null || "NULL".equalsIgnoreCase(defaultValue.trim())) {
            return null;
        }
        String result = defaultValue.trim();
        String lowerExtra = StringUtils.defaultString(extra).toLowerCase(Locale.ENGLISH);
        int index = lowerExtra.indexOf("on update ");
        if (index >= 0) {
            result += " " + extra.substring(index).trim();
        }
        return result;
    }

    /**
     * Sequences of the database (tables of type SEQUENCE), START_VALUE is the next value
     */
    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        String catalog = StringUtils.isNotBlank(catalogName) ? catalogName
                : databaseMetaData.getConnection().getCatalog();
        List<Map<String, Object>> sequenceInfos = new ArrayList<>();
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?"
                + " AND TABLE_TYPE = 'SEQUENCE' ORDER BY TABLE_NAME";
        for (Map<String, Object> row : JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                new Object[]{catalog})) {
            String sequenceName = (String) row.get("TABLE_NAME");
            Map<String, Object> state = JdbcUtils.fetchOne(databaseMetaData.getConnection(), String.format(
                    "SELECT next_not_cached_value, minimum_value, maximum_value, increment, cycle_option, cache_size"
                            + " FROM `%s`.`%s`", catalog.replace("`", "``"), sequenceName.replace("`", "``")),
                    new Object[0]);
            if (state == null) {
                continue;
            }
            Map<String, Object> sequenceInfo = new LinkedHashMap<>();
            sequenceInfo.put("SEQUENCE_NAME", sequenceName);
            sequenceInfo.put("START_VALUE", state.get("next_not_cached_value"));
            sequenceInfo.put("INCREMENT", state.get("increment"));
            sequenceInfo.put("MIN_VALUE", state.get("minimum_value"));
            sequenceInfo.put("MAX_VALUE", state.get("maximum_value"));
            Object cycle = state.get("cycle_option");
            sequenceInfo.put("CYCLE", cycle instanceof Number ? ((Number) cycle).intValue() != 0
                    : Boolean.TRUE.equals(cycle));
            sequenceInfo.put("CACHE_SIZE", state.get("cache_size"));
            sequenceInfo.put("DATA_TYPE", "bigint");
            sequenceInfos.add(sequenceInfo);
        }
        return sequenceInfos;
    }
}
