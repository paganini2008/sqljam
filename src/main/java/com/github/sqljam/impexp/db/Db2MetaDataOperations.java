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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.impexp.TableMetaData;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.utils.CaseInsensitiveMap;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: Db2MetaDataOperations reads the catalog of Db2 (SYSCAT) where the driver is not exact: names padded
 *               with blanks, sizes of GRAPHIC columns in bytes, identity columns reported as generated, DECFLOAT and
 *               XML without jdbc types, expressions of generated columns, range partitions and sequences. The
 *               database is the only catalog, system schemas are left out.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class Db2MetaDataOperations extends MetaDataOperations {

    /**
     * Schemas of Db2 itself
     */
    static boolean isSystemSchema(String schema) {
        String name = StringUtils.trimToEmpty(schema).toUpperCase(Locale.ENGLISH);
        return name.startsWith("SYS") || name.equals("NULLID") || name.equals("SQLJ");
    }

    /**
     * The database of the connection is the only catalog, it has no name of its own in the metadata
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
        schemaInfos.forEach(info -> info.put("TABLE_SCHEM", StringUtils.trim((String) info.get("TABLE_SCHEM"))));
        return schemaInfos.stream().filter(info -> !isSystemSchema((String) info.get("TABLE_SCHEM")))
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName) throws SQLException {
        List<Map<String, Object>> tableInfos = super.getTableInfos(databaseMetaData, null, schemaName);
        if (CollectionUtils.isEmpty(tableInfos)) {
            return tableInfos;
        }
        Connection connection = databaseMetaData.getConnection();
        Map<String, List<Map<String, Object>>> partitions = new HashMap<>();
        for (Map<String, Object> partition : JdbcUtils.fetchAll(connection,
                "SELECT TABNAME, DATAPARTITIONNAME, LOWVALUE, LOWINCLUSIVE, HIGHVALUE, HIGHINCLUSIVE"
                        + " FROM SYSCAT.DATAPARTITIONS WHERE TABSCHEMA = ? ORDER BY TABNAME, SEQNO",
                new Object[]{schemaName})) {
            partitions.computeIfAbsent(StringUtils.trim((String) partition.get("TABNAME")), k -> new ArrayList<>())
                    .add(partition);
        }
        Map<String, List<String>> partitionColumns = new HashMap<>();
        for (Map<String, Object> expression : JdbcUtils.fetchAll(connection,
                // Expressions are CLOB in the catalog
                "SELECT TABNAME, CAST(DATAPARTITIONEXPRESSION AS VARCHAR(1000)) AS DATAPARTITIONEXPRESSION"
                        + " FROM SYSCAT.DATAPARTITIONEXPRESSION WHERE TABSCHEMA = ? ORDER BY TABNAME, DATAPARTITIONKEYSEQ",
                new Object[]{schemaName})) {
            partitionColumns.computeIfAbsent(StringUtils.trim((String) expression.get("TABNAME")),
                    k -> new ArrayList<>()).add(StringUtils.trim(String.valueOf(expression.get(
                    "DATAPARTITIONEXPRESSION"))));
        }
        for (Map<String, Object> tableInfo : tableInfos) {
            tableInfo.put("TABLE_SCHEM", StringUtils.trim((String) tableInfo.get("TABLE_SCHEM")));
            String tableName = (String) tableInfo.get("TABLE_NAME");
            List<String> columns = partitionColumns.get(tableName);
            // Every table has one data partition, a partitioned table has its partition key
            if (columns != null && !columns.isEmpty()) {
                String columnNames = String.join(",", columns);
                tableInfo.put("IS_PARTITIONED", true);
                tableInfo.put("PARTITION_INLINE", true);
                tableInfo.put("PARTITION_TYPE", "RANGE");
                tableInfo.put("PARTITION_COLUMN_NAMES", columnNames);
                tableInfo.put("PARTITION_CLAUSE", getPartitionClause(columnNames, partitions.getOrDefault(tableName,
                        new ArrayList<>())));
            }
        }
        return tableInfos;
    }

    /**
     * Range partitions as they are created, e.g. PARTITION BY RANGE (SALE_YEAR) (PARTITION P2023 STARTING
     * (MINVALUE) ENDING (2023) INCLUSIVE)
     */
    static String getPartitionClause(String columnNames, List<Map<String, Object>> partitions) {
        List<String> definitions = new ArrayList<>();
        for (Map<String, Object> partition : partitions) {
            String name = StringUtils.trim((String) partition.get("DATAPARTITIONNAME"));
            definitions.add(String.format("PARTITION %s STARTING (%s)%s ENDING (%s)%s", quote(name),
                    StringUtils.trim((String) partition.get("LOWVALUE")), bound(partition.get("LOWINCLUSIVE")),
                    StringUtils.trim((String) partition.get("HIGHVALUE")), bound(partition.get("HIGHINCLUSIVE"))));
        }
        return String.format("PARTITION BY RANGE (%s) (%s)", columnNames, String.join(", ", definitions));
    }

    private static String bound(Object inclusive) {
        return "Y".equals(StringUtils.trim(String.valueOf(inclusive))) ? " INCLUSIVE" : " EXCLUSIVE";
    }

    private static String quote(String name) {
        return name.matches("[A-Z][A-Z0-9_$#@]*") ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, null, schemaName, tableName);
        if (CollectionUtils.isEmpty(columnInfos)) {
            return columnInfos;
        }
        Map<String, Map<String, Object>> catalogColumns = new HashMap<>();
        for (Map<String, Object> column : JdbcUtils.fetchAll(databaseMetaData.getConnection(),
                // TEXT of generated columns is CLOB in the catalog
                "SELECT COLNAME, TYPENAME, LENGTH, SCALE, CODEPAGE, IDENTITY, GENERATED,"
                        + " CAST(TEXT AS VARCHAR(4000)) AS TEXT FROM SYSCAT.COLUMNS WHERE TABSCHEMA = ? AND TABNAME = ?",
                new Object[]{schemaName, tableName})) {
            catalogColumns.put(StringUtils.trim((String) column.get("COLNAME")), column);
        }
        for (Map<String, Object> columnInfo : columnInfos) {
            columnInfo.put("TABLE_SCHEM", StringUtils.trim((String) columnInfo.get("TABLE_SCHEM")));
            Map<String, Object> column = catalogColumns.get((String) columnInfo.get("COLUMN_NAME"));
            boolean identity = column != null && "Y".equals(StringUtils.trim((String) column.get("IDENTITY")));
            String expression = column != null && "A".equals(StringUtils.trim((String) column.get("GENERATED")))
                    && !identity ? getGenerationExpression((String) column.get("TEXT")) : null;
            if (expression != null) {
                columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                columnInfo.put("GENERATION_EXPRESSION", expression);
                columnInfo.put("GENERATION_TYPE", "STORED");
                columnInfo.put("COLUMN_DEF", null);
            } else {
                // Identity columns are generated for the driver, here they are identities only
                columnInfo.put("IS_GENERATEDCOLUMN", "NO");
            }
            columnInfo.put("IS_AUTOINCREMENT", identity ? "YES" : "NO");
            if (identity) {
                columnInfo.put("COLUMN_DEF", null);
            }
            if (column != null) {
                normalizeType(columnInfo, StringUtils.trim((String) column.get("TYPENAME")),
                        TableMetaData.getInt(column, "LENGTH"), TableMetaData.getInt(column, "CODEPAGE"));
            } else {
                normalizeQueryColumnInfo(columnInfo);
            }
        }
        return columnInfos;
    }

    /**
     * Expression of a generated column, TEXT of the catalog is AS (expression)
     */
    static String getGenerationExpression(String text) {
        String expression = StringUtils.trimToNull(text);
        if (expression == null) {
            return null;
        }
        if (expression.toUpperCase(Locale.ENGLISH).startsWith("AS")) {
            expression = expression.substring(2).trim();
        }
        if (expression.startsWith("(") && expression.endsWith(")")) {
            expression = expression.substring(1, expression.length() - 1).trim();
        }
        return expression;
    }

    /**
     * Types of the catalog: GRAPHIC sizes in characters, strings FOR BIT DATA (code page 0) are binary, DECFLOAT
     * keeps its digits in the type name and is a number without size for other databases, XML is SQLXML
     */
    static void normalizeType(Map<String, Object> columnInfo, String typeName, int length, int codePage) {
        String type = StringUtils.defaultString(typeName).toUpperCase(Locale.ENGLISH);
        switch (type) {
            case "GRAPHIC":
            case "VARGRAPHIC":
            case "DBCLOB":
                columnInfo.put("COLUMN_SIZE", length);
                break;
            case "CHARACTER":
            case "VARCHAR":
                if (codePage == 0) {
                    columnInfo.put("TYPE_NAME", ("CHARACTER".equals(type) ? "CHAR" : "VARCHAR") + " () FOR BIT DATA");
                    columnInfo.put("DATA_TYPE", "CHARACTER".equals(type) ? Types.BINARY : Types.VARBINARY);
                    columnInfo.put("COLUMN_SIZE", length);
                }
                break;
            case "DECFLOAT":
                columnInfo.put("TYPE_NAME", "DECFLOAT(" + (length == 8 ? 16 : 34) + ")");
                columnInfo.put("DATA_TYPE", Types.NUMERIC);
                columnInfo.put("COLUMN_SIZE", 0);
                columnInfo.put("DECIMAL_DIGITS", null);
                break;
            case "XML":
                columnInfo.put("DATA_TYPE", Types.SQLXML);
                columnInfo.put("COLUMN_SIZE", 0);
                break;
            default:
                break;
        }
    }

    /**
     * Types of a result set: DECFLOAT and XML have no jdbc types there
     */
    @Override
    public void normalizeQueryColumnInfo(Map<String, Object> columnInfo) {
        String type = StringUtils.defaultString((String) columnInfo.get("TYPE_NAME")).toUpperCase(Locale.ENGLISH);
        if ("DECFLOAT".equals(type)) {
            Object size = columnInfo.get("COLUMN_SIZE");
            normalizeType(columnInfo, type, size instanceof Integer && (Integer) size == 16 ? 8 : 16, 1208);
        } else if ("XML".equals(type)) {
            normalizeType(columnInfo, type, 0, 1208);
        }
    }

    @Override
    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName, String tableName) throws SQLException {
        return super.getPrimaryKeyInfos(databaseMetaData, null, schemaName, tableName);
    }

    @Override
    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName, String tableName) throws SQLException {
        return super.getIndexInfos(databaseMetaData, null, schemaName, tableName);
    }

    @Override
    public List<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                         String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> keyInfos = super.getImportedKeyInfos(databaseMetaData, null, schemaName,
                tableName);
        keyInfos.forEach(info -> {
            info.put("PKTABLE_SCHEM", StringUtils.trim((String) info.get("PKTABLE_SCHEM")));
            info.put("FKTABLE_SCHEM", StringUtils.trim((String) info.get("FKTABLE_SCHEM")));
        });
        return keyInfos;
    }

    /**
     * Sequences of the schema with the tables of their triggers, sequences of identity columns are left out. The
     * next value is the first value of the next cache, values of the current cache are not used again.
     */
    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        String sql = "SELECT SEQNAME AS SEQUENCE_NAME, COALESCE(NEXTCACHEFIRSTVALUE, START) AS START_VALUE,"
                + " INCREMENT, MINVALUE AS MIN_VALUE, MAXVALUE AS MAX_VALUE, CYCLE, CACHE AS CACHE_SIZE"
                + " FROM SYSCAT.SEQUENCES WHERE SEQSCHEMA = ? AND SEQTYPE = 'S' ORDER BY SEQNAME";
        List<Map<String, Object>> sequenceInfos;
        try {
            sequenceInfos = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql, new Object[]{schemaName});
        } catch (SQLException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to read sequences of schema {}: {}", schemaName, e.getMessage());
            }
            return new ArrayList<>();
        }
        // Tables using sequences by triggers, Db2 has no sequences in defaults of columns
        Map<String, List<String>> referencedTableNames = new HashMap<>();
        try {
            for (Map<String, Object> dependency : JdbcUtils.fetchAll(databaseMetaData.getConnection(),
                    "SELECT d.BNAME AS SEQUENCE_NAME, t.TABNAME FROM SYSCAT.TRIGDEP d JOIN SYSCAT.TRIGGERS t"
                            + " ON t.TRIGSCHEMA = d.TRIGSCHEMA AND t.TRIGNAME = d.TRIGNAME"
                            + " WHERE d.BTYPE = 'Q' AND d.BSCHEMA = ?", new Object[]{schemaName})) {
                referencedTableNames.computeIfAbsent(StringUtils.trim((String) dependency.get("SEQUENCE_NAME")),
                        k -> new ArrayList<>()).add(StringUtils.trim((String) dependency.get("TABNAME")));
            }
        } catch (SQLException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to read triggers using sequences of schema {}: {}", schemaName, e.getMessage());
            }
        }
        for (Map<String, Object> sequenceInfo : sequenceInfos) {
            sequenceInfo.put("SEQUENCE_NAME", StringUtils.trim((String) sequenceInfo.get("SEQUENCE_NAME")));
            List<String> tableNames = referencedTableNames.get((String) sequenceInfo.get("SEQUENCE_NAME"));
            if (tableNames != null) {
                sequenceInfo.put("REFERENCED_TABLE_NAMES", tableNames.toArray(new String[0]));
            }
            sequenceInfo.put("CYCLE", "Y".equals(StringUtils.trim(String.valueOf(sequenceInfo.get("CYCLE")))));
            sequenceInfo.put("DATA_TYPE", "BIGINT");
        }
        return sequenceInfos;
    }
}
