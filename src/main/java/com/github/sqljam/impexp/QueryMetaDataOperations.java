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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: QueryMetaDataOperations presents free queries as tables, e.g. results of the SQL editor: catalogs
 *               and schemas are the ones of the database, the tables of a schema are the queries, their columns come
 *               from the metadata of the result set. Queries have no keys, indexes, foreign keys, partitions or
 *               sequences, their tables are created from their columns.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class QueryMetaDataOperations extends MetaDataOperations {

    /**
     * Label of the column in the result set, it differs from the column name when the label was used before
     */
    public static final String QUERY_LABEL = "QUERY_LABEL";

    private final MetaDataOperations delegate;
    private final Map<String, String> queries;
    private final Map<String, List<Map<String, Object>>> columnInfos = new HashMap<>();
    private String listedSchema;

    /**
     * @param queries SQL of each query by its table name
     */
    public QueryMetaDataOperations(MetaDataOperations delegate, Map<String, String> queries) {
        this.delegate = delegate;
        this.queries = queries;
    }

    @Override
    public List<Map<String, Object>> getCatalogInfos(DatabaseMetaData databaseMetaData) throws SQLException {
        return delegate.getCatalogInfos(databaseMetaData);
    }

    @Override
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        return delegate.getSchemaInfos(databaseMetaData, catalogName);
    }

    /**
     * The queries are tables of the first schema which is read, the export names its catalog and schema
     */
    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) {
        String schema = catalogName + "." + schemaName;
        if (listedSchema != null && !listedSchema.equals(schema)) {
            return Collections.emptyList();
        }
        listedSchema = schema;
        List<Map<String, Object>> tableInfos = new ArrayList<>();
        for (String tableName : queries.keySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("TABLE_CAT", catalogName);
            info.put("TABLE_SCHEM", schemaName);
            info.put("TABLE_NAME", tableName);
            info.put("TABLE_TYPE", "TABLE");
            tableInfos.add(info);
        }
        return tableInfos;
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        String sql = queries.get(tableName);
        if (sql == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> infos = columnInfos.get(tableName);
        if (infos == null) {
            infos = describe(databaseMetaData.getConnection(), sql, catalogName, schemaName, tableName);
            columnInfos.put(tableName, infos);
        }
        return infos;
    }

    @Override
    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName, String tableName) {
        return Collections.emptyList();
    }

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

    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) {
        return Collections.emptyList();
    }

    /**
     * Columns of the query like DatabaseMetaData.getColumns: the metadata of the prepared statement, or of the
     * first row when the driver can not describe a statement before it runs (MySQL) or types values by row
     * (SQLite)
     */
    List<Map<String, Object>> describe(Connection connection, String sql, String catalogName, String schemaName,
                                       String tableName) throws SQLException {
        DatabaseMetaData databaseMetaData = connection.getMetaData();
        boolean typedByRow = StringUtils.containsIgnoreCase(databaseMetaData.getDatabaseProductName(), "SQLite");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ResultSetMetaData metaData = null;
            if (!typedByRow) {
                try {
                    metaData = ps.getMetaData();
                } catch (SQLException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Unable to describe the query before it runs: {}", e.getMessage());
                    }
                }
            }
            if (metaData != null && metaData.getColumnCount() > 0) {
                return toColumnInfos(databaseMetaData, metaData, catalogName, schemaName, tableName);
            }
            ps.setMaxRows(1);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return toColumnInfos(databaseMetaData, rs.getMetaData(), catalogName, schemaName, tableName);
            }
        }
    }

    /**
     * Columns of the result: a column of a table is described like the column of its table (types of each
     * database are known best by its table), an expression by the result set
     */
    List<Map<String, Object>> toColumnInfos(DatabaseMetaData databaseMetaData, ResultSetMetaData metaData,
                                            String catalogName, String schemaName, String tableName)
            throws SQLException {
        List<Map<String, Object>> infos = new ArrayList<>();
        List<String> names = getColumnNames(metaData);
        Map<String, List<Map<String, Object>>> tableColumns = new HashMap<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            Map<String, Object> info = getTableColumnInfo(databaseMetaData, metaData, i, catalogName, schemaName,
                    tableColumns);
            if (info == null) {
                info = toColumnInfo(metaData, i);
                delegate.normalizeQueryColumnInfo(info);
            }
            info.put("TABLE_CAT", catalogName);
            info.put("TABLE_SCHEM", schemaName);
            info.put("TABLE_NAME", tableName);
            info.put("COLUMN_NAME", names.get(i - 1));
            // A renamed label can not be selected from the query, see Exporter.getQueryStatement
            info.put(QUERY_LABEL, metaData.getColumnLabel(i));
            info.put("ORDINAL_POSITION", i);
            // The table of the query has no identities, defaults or generated columns of its own, a row version
            // is maintained by the target database like the one of a table
            info.put("IS_AUTOINCREMENT", "NO");
            info.put("IS_GENERATEDCOLUMN", Boolean.TRUE.equals(info.get("IS_ROWVERSION")) ? "YES" : "NO");
            info.remove("GENERATION_EXPRESSION");
            info.remove("GENERATION_TYPE");
            info.put("COLUMN_DEF", null);
            infos.add(info);
        }
        return infos;
    }

    /**
     * Column of the table which the result column comes from, null for an expression or when the driver does not
     * name the table (Oracle)
     */
    private Map<String, Object> getTableColumnInfo(DatabaseMetaData databaseMetaData, ResultSetMetaData metaData,
                                                   int column, String catalogName, String schemaName,
                                                   Map<String, List<Map<String, Object>>> tableColumns) {
        try {
            String table = metaData.getTableName(column);
            String columnName = metaData.getColumnName(column);
            if (StringUtils.isBlank(table) || StringUtils.isBlank(columnName)) {
                return null;
            }
            String catalog = StringUtils.defaultIfBlank(metaData.getCatalogName(column), catalogName);
            String schema = StringUtils.defaultIfBlank(metaData.getSchemaName(column), schemaName);
            String key = catalog + "." + schema + "." + table;
            List<Map<String, Object>> columns = tableColumns.get(key);
            if (columns == null) {
                columns = delegate.getColumnInfos(databaseMetaData, catalog, schema, table);
                tableColumns.put(key, columns);
            }
            for (Map<String, Object> info : columns) {
                // Types of a result set and of a table differ for some types (inet4, ENUM of MariaDB), the table
                // knows them best
                if (columnName.equalsIgnoreCase(String.valueOf(info.get("COLUMN_NAME")))) {
                    Map<String, Object> copy = new LinkedHashMap<>(info);
                    // NULL of an outer join is a value of the query
                    if (metaData.isNullable(column) != ResultSetMetaData.columnNoNulls) {
                        copy.put("IS_NULLABLE", "YES");
                        copy.put("NULLABLE", DatabaseMetaData.columnNullable);
                    }
                    return copy;
                }
            }
        } catch (SQLException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("Column {} is described by the result set: {}", column, e.getMessage());
            }
        }
        return null;
    }

    static Map<String, Object> toColumnInfo(ResultSetMetaData metaData, int i) throws SQLException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("DATA_TYPE", metaData.getColumnType(i));
        info.put("TYPE_NAME", metaData.getColumnTypeName(i));
        int precision = metaData.getPrecision(i);
        int scale = metaData.getScale(i);
        // Unknown sizes of expressions are left out, e.g. NUMBER of Oracle with scale -127
        info.put("COLUMN_SIZE", precision > 0 ? precision : null);
        info.put("DECIMAL_DIGITS", scale >= 0 && precision > 0 ? scale : null);
        info.put("NULLABLE", metaData.isNullable(i) == ResultSetMetaData.columnNoNulls
                ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable);
        info.put("IS_NULLABLE", metaData.isNullable(i) == ResultSetMetaData.columnNoNulls ? "NO" : "YES");
        info.put("REMARKS", null);
        return info;
    }

    /**
     * Error of a value which the driver can not read, e.g. XMLTYPE of Oracle without its XML libraries, which a
     * table export reads as text. A query selects such a value as text itself.
     */
    public static SQLException toReadError(ResultSetMetaData metaData, int column, LinkageError e)
            throws SQLException {
        String typeName = metaData.getColumnTypeName(column);
        String hint = StringUtils.endsWithIgnoreCase(typeName, "XMLTYPE")
                ? ", select it as text, e.g. XMLSERIALIZE(CONTENT column AS CLOB)" : "";
        return new SQLException(String.format("Values of column %s (%s) can not be read by the driver%s: %s",
                metaData.getColumnLabel(column), typeName, hint, e), e);
    }

    /**
     * Labels of the columns, a label used before gets a number, e.g. id and id_2 of a join
     */
    public static List<String> getColumnNames(ResultSetMetaData metaData) throws SQLException {
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String label = metaData.getColumnLabel(i);
            if (label == null || label.isBlank()) {
                label = "column_" + i;
            }
            String name = label;
            for (int n = 2; !used.add(name.toLowerCase(Locale.ENGLISH)); n++) {
                name = label + "_" + n;
            }
            names.add(name);
        }
        return names;
    }
}
