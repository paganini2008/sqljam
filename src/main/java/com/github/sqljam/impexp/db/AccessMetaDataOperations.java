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

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.utils.CaseInsensitiveMap;
import io.github.spannm.jackcess.Column;
import io.github.spannm.jackcess.DataType;
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.PropertyMap;
import io.github.spannm.jackcess.Table;
import lombok.extern.slf4j.Slf4j;
import net.ucanaccess.jdbc.UcanaccessConnection;

/**
 * @Description: AccessMetaDataOperations reads Microsoft Access files by UCanAccess, completed by the column
 *               definitions of the file (Jackcess): the types of Access (TEXT, MEMO, CURRENCY, SINGLE, BYTE, GUID) as
 *               standard types which every database reads alike (VARCHAR, CLOB, DECIMAL(19,4), REAL, SMALLINT),
 *               autonumbers and the descriptions of tables and columns. Access has no catalogs, schemas or sequences.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class AccessMetaDataOperations extends MetaDataOperations {

    /**
     * An Access file has no catalogs, an empty catalog name is used as the only one
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
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName) {
        return Collections.emptyList();
    }

    /**
     * Tables with the descriptions of Access as remarks
     */
    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        List<Map<String, Object>> tableInfos = super.getTableInfos(databaseMetaData, null, null);
        Database database = getDatabase(databaseMetaData.getConnection());
        if (database != null) {
            for (Map<String, Object> tableInfo : tableInfos) {
                Table table = getTable(database, (String) tableInfo.get("TABLE_NAME"));
                if (table != null && StringUtils.isBlank((String) tableInfo.get("REMARKS"))) {
                    tableInfo.put("REMARKS", getDescription(table::getProperties));
                }
            }
        }
        return tableInfos;
    }

    /**
     * Columns of UCanAccess (its HSQLDB types) are completed by the columns of the Access file: type, size,
     * autonumber and description
     */
    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, null, null, tableName);
        Database database = getDatabase(databaseMetaData.getConnection());
        Table table = database != null ? getTable(database, tableName) : null;
        if (table == null) {
            return columnInfos;
        }
        for (Map<String, Object> columnInfo : columnInfos) {
            Column column = getColumn(table, (String) columnInfo.get("COLUMN_NAME"));
            if (column != null) {
                normalizeColumn(columnInfo, column);
            }
        }
        return columnInfos;
    }

    /**
     * Type of an Access column as the jdbc type with a standard type name, size and scale. Names of Access (TEXT,
     * LONG, CURRENCY) mean other types in other databases (text of MySQL, LONG of Oracle).
     */
    static void normalizeColumn(Map<String, Object> columnInfo, Column column) {
        boolean autoNumber = column.isAutoNumber();
        switch (column.getType()) {
            case BOOLEAN:
                setType(columnInfo, Types.BOOLEAN, "BOOLEAN", 1, 0);
                // Yes/No fields of Access are never null
                columnInfo.put("IS_NULLABLE", "NO");
                columnInfo.put("NULLABLE", DatabaseMetaData.columnNoNulls);
                break;
            case BYTE:
                // Unsigned 0-255, a small integer of other databases
                setType(columnInfo, Types.SMALLINT, "SMALLINT", 3, 0);
                break;
            case INT:
                setType(columnInfo, Types.SMALLINT, "SMALLINT", 5, 0);
                break;
            case LONG:
                setType(columnInfo, Types.INTEGER, "INTEGER", 10, 0);
                break;
            case BIG_INT:
                setType(columnInfo, Types.BIGINT, "BIGINT", 19, 0);
                break;
            case MONEY:
                setType(columnInfo, Types.DECIMAL, "DECIMAL", 19, 4);
                break;
            case FLOAT:
                setType(columnInfo, Types.REAL, "REAL", 7, 0);
                break;
            case DOUBLE:
                setType(columnInfo, Types.DOUBLE, "DOUBLE", 15, 0);
                break;
            case NUMERIC:
                setType(columnInfo, Types.DECIMAL, "DECIMAL", column.getPrecision(), column.getScale());
                break;
            case SHORT_DATE_TIME:
            case EXT_DATE_TIME:
                setType(columnInfo, Types.TIMESTAMP, "TIMESTAMP", 23, 3);
                break;
            case TEXT:
                setType(columnInfo, Types.VARCHAR, "VARCHAR", column.getLengthInUnits(), 0);
                break;
            case MEMO:
                setType(columnInfo, Types.CLOB, "CLOB", Integer.MAX_VALUE, 0);
                break;
            case BINARY:
                setType(columnInfo, Types.VARBINARY, "VARBINARY", column.getLength(), 0);
                break;
            case OLE:
                setType(columnInfo, Types.BLOB, "BLOB", Integer.MAX_VALUE, 0);
                break;
            case GUID:
                // Replication ids are generated by Access, they are not identities of other databases
                setType(columnInfo, Types.VARCHAR, "VARCHAR", 38, 0);
                autoNumber = false;
                break;
            default:
                break;
        }
        columnInfo.put("IS_AUTOINCREMENT", autoNumber ? "YES" : "NO");
        columnInfo.put("COLUMN_DEF", autoNumber ? null : normalizeDefault((String) columnInfo.get("COLUMN_DEF"),
                column.getType() == DataType.TEXT || column.getType() == DataType.MEMO));
        String description = getDescription(column::getProperties);
        if (StringUtils.isBlank((String) columnInfo.get("REMARKS")) && description != null) {
            columnInfo.put("REMARKS", description);
        }
    }

    /**
     * Default value of Access as SQL: an expression starts with = (=Now()), strings are quoted by double quotes
     * ("n/a") or by nothing (n/a, written by UCanAccess), Date() is the current date
     */
    static String normalizeDefault(String defaultValue, boolean text) {
        String value = StringUtils.trimToNull(defaultValue);
        if (value == null) {
            return null;
        }
        if (value.startsWith("=")) {
            value = value.substring(1).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return "'" + value.substring(1, value.length() - 1).replace("\"\"", "\"").replace("'", "''") + "'";
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value;
        }
        if (value.matches("(?i)date\\s*\\(\\s*\\)")) {
            return "CURRENT_DATE";
        }
        if (text && !value.matches("(?i)[a-z_]+\\s*\\(.*\\)")) {
            return "'" + value.replace("'", "''") + "'";
        }
        return value;
    }

    private static void setType(Map<String, Object> columnInfo, int dataType, String typeName, int size, int scale) {
        columnInfo.put("DATA_TYPE", dataType);
        columnInfo.put("TYPE_NAME", typeName);
        columnInfo.put("COLUMN_SIZE", size);
        columnInfo.put("DECIMAL_DIGITS", scale);
    }

    /**
     * Indexes of the relationships of Access (hidden indexes named .rB...) are not indexes of the table
     */
    @Override
    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName, String tableName) throws SQLException {
        return super.getIndexInfos(databaseMetaData, null, null, tableName).stream()
                .filter(info -> !StringUtils.startsWith((String) info.get("INDEX_NAME"), "."))
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName, String tableName) throws SQLException {
        return super.getPrimaryKeyInfos(databaseMetaData, null, null, tableName);
    }

    @Override
    public List<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                         String schemaName, String tableName) throws SQLException {
        return super.getImportedKeyInfos(databaseMetaData, null, null, tableName);
    }

    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) {
        return Collections.emptyList();
    }

    /**
     * The Access file of a connection of UCanAccess, also of a pooled connection; null if it is not available
     */
    static Database getDatabase(Connection connection) {
        try {
            if (connection instanceof UcanaccessConnection) {
                return ((UcanaccessConnection) connection).getDbIO();
            }
            if (connection != null && connection.isWrapperFor(UcanaccessConnection.class)) {
                return connection.unwrap(UcanaccessConnection.class).getDbIO();
            }
        } catch (SQLException e) {
            if (log.isDebugEnabled()) {
                log.debug("The Access file of the connection is not available: {}", e.getMessage());
            }
        }
        return null;
    }

    private static Table getTable(Database database, String tableName) {
        try {
            return tableName != null ? database.getTable(tableName) : null;
        } catch (IOException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("Unable to read the Access table {}: {}", tableName, e.getMessage());
            }
            return null;
        }
    }

    private static Column getColumn(Table table, String columnName) {
        return table.getColumns().stream().filter(column -> column.getName().equalsIgnoreCase(columnName))
                .findFirst().orElse(null);
    }

    /**
     * Properties of a table or a column, read from the Access file
     */
    interface PropertySource {
        PropertyMap getProperties() throws IOException;
    }

    /**
     * Description of Access (the remark of a table or a column), null if it has none
     */
    static String getDescription(PropertySource source) {
        try {
            PropertyMap properties = source.getProperties();
            Object description = properties != null ? properties.getValue(PropertyMap.DESCRIPTION_PROP) : null;
            return description != null ? StringUtils.trimToNull(description.toString()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
