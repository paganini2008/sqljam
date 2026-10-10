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

import java.nio.charset.StandardCharsets;
import java.sql.Types;

import org.apache.commons.lang3.StringUtils;

import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: H2Dialect for H2 2.x
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class H2Dialect extends Dialect {

    public H2Dialect() {
        super();
        registerColumnType(Types.BIT, "boolean");
        registerColumnType(Types.BOOLEAN, "boolean");
        registerColumnType(Types.TINYINT, "tinyint");
        registerColumnType(Types.INTEGER, "integer");
        registerColumnType(Types.FLOAT, "real");
        registerColumnType(Types.REAL, "real");
        registerColumnType(Types.NUMERIC, "numeric($p,$s)");
        registerColumnType(Types.DECIMAL, "numeric($p,$s)");

        registerColumnType(Types.BINARY, "blob");
        registerColumnType(Types.BINARY, 1000000, "binary($l)");
        registerColumnType(Types.VARBINARY, "blob");
        registerColumnType(Types.VARBINARY, 1000000, "varbinary($l)");
        registerColumnType(Types.LONGVARBINARY, "blob");

        registerColumnType(Types.CHAR, "clob");
        registerColumnType(Types.CHAR, 1000000, "char($l)");
        registerColumnType(Types.NCHAR, "clob");
        registerColumnType(Types.NCHAR, 1000000, "char($l)");
        registerColumnType(Types.VARCHAR, "clob");
        registerColumnType(Types.VARCHAR, 1000000, "varchar($l)");
        registerColumnType(Types.NVARCHAR, "clob");
        registerColumnType(Types.NVARCHAR, 1000000, "varchar($l)");
        registerColumnType(Types.LONGVARCHAR, "clob");
        registerColumnType(Types.LONGNVARCHAR, "clob");
        registerColumnType(Types.NCLOB, "clob");
        registerColumnType(Types.SQLXML, "clob");
        registerColumnType(Types.OTHER, "clob");
        registerColumnType(Types.JAVA_OBJECT, "java_object");

        registerReservedWords("if", "minus", "qualify", "regexp", "rownum", "sysdate", "systime", "systimestamp",
                "today", "top", "value", "year", "month", "day", "hour", "minute", "second");
    }

    @Override
    public DbType getDbType() {
        return DbType.H2;
    }

    @Override
    public IdentifierCase getStoredIdentifierCase() {
        return IdentifierCase.UPPER;
    }

    @Override
    public int getMaxIdentifierLength() {
        return 256;
    }

    @Override
    public int getMaxNumericPrecision() {
        return 100000;
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        switch (baseTypeName) {
            case "json":
            case "jsonb":
                return "json";
            case "uuid":
            case "uniqueidentifier":
                return "uuid";
            case "bool":
            case "boolean":
                return "boolean";
            case "year":
                return "smallint";
            case "enum":
            case "set":
                return "varchar(255)";
            case "text":
            case "tinytext":
            case "mediumtext":
            case "longtext":
            case "character large object":
            case "xml":
            case "xmltype":
                return "clob";
            case "bytea":
            case "tinyblob":
            case "mediumblob":
            case "longblob":
            case "binary large object":
                return "blob";
            case "money":
            case "smallmoney":
                return "numeric(19,4)";
            default:
                break;
        }
        if (baseTypeName.startsWith("_")) {
            return "clob";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "bigint";
        }
        if (dataType == Types.TIMESTAMP) {
            int fraction = Math.min(Math.max(columnScale, 0), 9);
            return "timestamp(" + fraction + ")";
        }
        if (dataType == Types.TIME && columnScale > 0) {
            return "time(" + Math.min(columnScale, 9) + ")";
        }
        if ((dataType == Types.VARCHAR || dataType == Types.NVARCHAR) && columnSize <= 0) {
            return "clob";
        }
        return null;
    }

    /**
     * Native type names of H2 which can not be mapped by jdbc type code
     */
    @Override
    protected String getNativeTypeName(int dataType, String typeName, int columnSize, int columnScale) {
        String sourceTypeName = typeName.toLowerCase();
        if (sourceTypeName.startsWith("enum(") || sourceTypeName.endsWith(" array")
                || sourceTypeName.startsWith("interval") || sourceTypeName.startsWith("geometry")) {
            return typeName;
        }
        switch (sourceTypeName) {
            case "decfloat":
            case "uuid":
            case "json":
            case "java_object":
            case "time with time zone":
            case "boolean":
                return sourceTypeName.toUpperCase();
            case "timestamp with time zone":
                return "TIMESTAMP(" + Math.max(columnScale, 0) + ") WITH TIME ZONE";
            case "varchar_ignorecase":
                return "VARCHAR_IGNORECASE(" + columnSize + ")";
            case "character large object":
                return "CLOB";
            case "binary large object":
                return "BLOB";
            default:
                return null;
        }
    }

    @Override
    public String getCreateUserStatement(String username, String password) {
        return String.format("CREATE USER IF NOT EXISTS %s PASSWORD '%s'", getIdentifier(username), password);
    }

    @Override
    public String getCreateDatabaseStatement(String catalog, String username) {
        return null;
    }

    @Override
    public String getDefaultSchemaName(String catalog) {
        return "PUBLIC";
    }

    @Override
    public String getCreateSchemaStatement(String catalog, String schema, String username) {
        return String.format("CREATE SCHEMA IF NOT EXISTS %s", getIdentifier(schema));
    }

    @Override
    public String getSequenceNameStatement(String catalog, String schema, String tableName, String columnName) {
        return null;
    }

    @Override
    public String getDefaultSequenceName(String catalog, String schema, String tableName, String columnName) {
        return null;
    }

    @Override
    public String getAlterSequenceStartValueStatement(String catalog, String schema, String tableName,
                                                      String sequenceName, long startValue) {
        return null;
    }

    @Override
    public String getResetIdentityStatement(String catalog, String schema, String tableName, String columnName,
                                            long startValue) {
        return String.format("ALTER TABLE %s ALTER COLUMN %s RESTART WITH %d",
                getQualifiedTableName(catalog, schema, tableName), getIdentifier(columnName), startValue);
    }

    @Override
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        return String.format("COMMENT ON COLUMN %s.%s IS %s", getQualifiedTableName(catalog, schema, tableName),
                getIdentifier(columnName), getStringLiteral(comment));
    }

    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        if (!columnTypeName.matches("(?i)(tinyint|smallint|integer|int|bigint)")) {
            columnTypeName = "bigint";
        }
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        columnDef.append(" GENERATED BY DEFAULT AS IDENTITY NOT NULL");
        return columnDef.toString().trim();
    }

    @Override
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return null;
    }

    @Override
    public boolean isSequenceSupported() {
        return true;
    }

    @Override
    public String getCreateSequenceStatement(String catalog, String schema, String sequenceName, long startValue,
                                             long increment, Long minValue, Long maxValue, boolean cycle,
                                             Long cacheSize, String dataType) {
        String sql = super.getCreateSequenceStatement(catalog, schema, sequenceName, startValue, increment, minValue,
                maxValue, cycle, cacheSize, dataType);
        return "CREATE SEQUENCE IF NOT EXISTS " + sql.substring("CREATE SEQUENCE ".length());
    }

    @Override
    public String[] getStatementAfterSequenceCreated(String catalog, String schema, String sequenceName,
                                                     long startValue) {
        return new String[]{String.format("ALTER SEQUENCE %s RESTART WITH %d",
                getQualifiedSequenceName(catalog, schema, sequenceName), startValue)};
    }

    /**
     * String bound to JSON column is treated as a JSON string literal by H2, JSON text must be bound as bytes
     */
    @Override
    public Object getJdbcValue(Object value, int sourceDataType, String sourceTypeName, int columnSize) {
        if (value instanceof String && StringUtils.startsWithIgnoreCase(sourceTypeName, "json")) {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
        }
        return super.getJdbcValue(value, sourceDataType, sourceTypeName, columnSize);
    }

    @Override
    public boolean isSingleIncrementalColumn() {
        return true;
    }

    @Override
    public String getCreateSchemaIfNotExistsStatement(String schema) {
        return String.format("CREATE SCHEMA IF NOT EXISTS %s", quoteIdentifier(schema));
    }

    /**
     * Values of ROW types are read as text
     */
    @Override
    public String getSelectColumnExpression(String columnName, String typeName) {
        if (H2MetaDataOperations.isRowType(typeName)) {
            return String.format("CAST(%1$s AS VARCHAR) AS %1$s", quoteIdentifier(columnName));
        }
        return super.getSelectColumnExpression(columnName, typeName);
    }
}
