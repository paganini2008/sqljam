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


import java.sql.Types;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.postgresql.util.PGobject;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.ImpExpException;
import com.github.sqljam.impexp.StringHelper;
import com.github.sqljam.impexp.TypeNames;

/**
 * @Description: PostgreSQLDialect for PostgreSQL 12 and later versions
 * @Author: Fred Feng
 * @Date: 24/03/2023
 * @Version 1.0.0
 */
public class PostgreSQLDialect extends Dialect {

    public PostgreSQLDialect() {
        super();
        registerColumnType(Types.BIT, "boolean");
        registerColumnType(Types.BOOLEAN, "boolean");
        registerColumnType(Types.BIGINT, "int8");
        registerColumnType(Types.SMALLINT, "int2");
        registerColumnType(Types.TINYINT, "int2");
        registerColumnType(Types.INTEGER, "int4");
        registerColumnType(Types.FLOAT, "float4");
        registerColumnType(Types.REAL, "float4");
        registerColumnType(Types.DOUBLE, "float8");
        registerColumnType(Types.DATE, "date");
        registerColumnType(Types.TIME, "time");
        registerColumnType(Types.TIMESTAMP, "timestamp");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "timetz");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz");
        registerColumnType(Types.VARBINARY, "bytea");
        registerColumnType(Types.BINARY, "bytea");
        registerColumnType(Types.LONGVARCHAR, "text");
        registerColumnType(Types.LONGNVARCHAR, "text");
        registerColumnType(Types.LONGVARBINARY, "bytea");
        registerColumnType(Types.CLOB, "text");
        registerColumnType(Types.NCLOB, "text");
        registerColumnType(Types.BLOB, "bytea");
        registerColumnType(Types.NUMERIC, "numeric($p, $s)");
        registerColumnType(Types.DECIMAL, "numeric($p, $s)");
        registerColumnType(Types.OTHER, "text");
        registerColumnType(Types.JAVA_OBJECT, "bytea");
        registerColumnType(Types.SQLXML, "xml");

        registerColumnType(Types.VARCHAR, "text");
        registerColumnType(Types.VARCHAR, 10485760, "varchar($l)");
        registerColumnType(Types.NVARCHAR, "text");
        registerColumnType(Types.NVARCHAR, 10485760, "varchar($l)");
        registerColumnType(Types.CHAR, "char($l)");
        registerColumnType(Types.NCHAR, "char($l)");

        registerSerialColumnType(Types.SMALLINT, "smallserial");
        registerSerialColumnType(Types.TINYINT, "smallserial");
        registerSerialColumnType(Types.INTEGER, "serial");
        registerSerialColumnType(Types.BIGINT, "bigserial");
        registerSerialColumnType(Types.NUMERIC, "bigserial");
        registerSerialColumnType(Types.DECIMAL, "bigserial");

        registerReservedWords("analyse", "analyze", "array", "asymmetric", "both", "cast", "collate", "do", "freeze",
                "ilike", "initially", "lateral", "leading", "localtime", "localtimestamp", "notnull", "only",
                "overlaps", "placing", "returning", "similar", "symmetric", "trailing", "variadic", "verbose",
                "window");
    }

    private final TypeNames serialTypeNames = new TypeNames();

    protected void registerSerialColumnType(int code, String name) {
        serialTypeNames.put(code, name);
    }

    public String getSerialTypeName(int code) {
        final String result = serialTypeNames.get(code);
        if (result == null) {
            throw new ImpExpException("No default serial type mapping for (java.sql.Types) " + code);
        }
        return result;
    }

    @Override
    public DbType getDbType() {
        return DbType.POSTGRESQL;
    }

    @Override
    public IdentifierCase getStoredIdentifierCase() {
        return IdentifierCase.LOWER;
    }

    @Override
    public int getMaxNumericPrecision() {
        return 1000;
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        if (!isCrossDatabase()) {
            switch (sourceTypeName) {
                case "json":
                case "jsonb":
                case "uuid":
                case "inet":
                case "cidr":
                case "macaddr":
                case "xml":
                case "money":
                case "interval":
                case "tsvector":
                case "point":
                case "int4range":
                case "int8range":
                case "tsrange":
                case "tstzrange":
                case "daterange":
                case "text":
                case "bytea":
                    return sourceTypeName;
                case "varchar":
                    return columnSize > 0 && columnSize < Integer.MAX_VALUE ? "varchar(" + columnSize + ")" : "varchar";
                case "bpchar":
                    return "char(" + columnSize + ")";
                case "bit":
                    return "bit(" + Math.max(columnSize, 1) + ")";
                case "varbit":
                    return columnSize > 0 && columnSize < Integer.MAX_VALUE ? "varbit(" + columnSize + ")" : "varbit";
                case "float4":
                case "float8":
                case "bool":
                case "date":
                case "time":
                case "timetz":
                case "timestamptz":
                    return sourceTypeName;
                case "timestamp":
                    return "timestamp";
                default:
                    if (sourceTypeName.startsWith("_")) {
                        // Array types: _int4, _text ...
                        return sourceTypeName.substring(1) + "[]";
                    }
                    return null;
            }
        }
        switch (sourceTypeName) {
            case "json":
                return "json";
            case "uniqueidentifier":
            case "uuid":
                return "uuid";
            case "year":
                return "int2";
            case "xmltype":
                return "xml";
            case "datetimeoffset":
                return "timestamptz";
            case "money":
            case "smallmoney":
                return "numeric(19, 4)";
            default:
                if (dataType == Types.BIT && columnSize > 1) {
                    return "int8";
                }
                return null;
        }
    }

    @Override
    public String getCreateUserStatement(String username, String password) {
        return String.format("CREATE USER %s WITH PASSWORD '%s'", username, password);
    }

    @Override
    public String getCreateDatabaseStatement(String catalog, String username) {
        return String.format("CREATE DATABASE %s OWNER %s", getIdentifier(catalog), username);
    }

    @Override
    public String[] getStatementAfterDatabaseCreated(String catalog, String username) {
        String statement = String.format("GRANT ALL PRIVILEGES ON DATABASE %s TO %s", getIdentifier(catalog),
                username);
        return new String[]{statement};
    }

    @Override
    public String getDefaultSchemaName(String catalog) {
        return "public";
    }

    @Override
    public String getCreateSchemaStatement(String catalog, String schema, String username) {
        return String.format("CREATE SCHEMA IF NOT EXISTS %s AUTHORIZATION %s", getIdentifier(schema), username);
    }

    @Override
    public String getAlterSequenceStartValueStatement(String catalog, String schema, String tableName, String sequenceName,
                                                      long startValue) {
        return String.format("ALTER SEQUENCE %s restart with %s", sequenceName, startValue);
    }

    @Override
    public String getSequenceNameStatement(String catalog, String schema, String tableName, String columnName) {
        return String.format("SELECT pg_get_serial_sequence('%s.%s','%s') as sequenceName", schema, tableName, columnName);
    }

    @Override
    public String getDefaultSequenceName(String catalog, String schema, String tableName, String columnName) {
        return String.format("%s.%s_%s_seq", schema, tableName, columnName);
    }

    @Override
    public String getResetIdentityStatement(String catalog, String schema, String tableName, String columnName,
                                            long startValue) {
        String tableRef = getQualifiedTableName(catalog, schema, tableName).replace("'", "''");
        return String.format("SELECT setval(pg_get_serial_sequence('%s', '%s'), %d, false)", tableRef,
                foldIdentifier(columnName), startValue);
    }

    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType) {
        if (StringUtils.isNotBlank(indexType)) {
            return String.format("CREATE INDEX %s%s ON %s USING %s (%s)", getIfNotExists(), quoteIdentifier(indexName),
                    getQualifiedTableName(catalog, schema, tableName), indexType, getIdentifiers(columnNames));
        }
        if (partition) {
            unique = false;
        }
        String sql = super.getCreateIndexStatement(catalog, schema, tableName, partition, columnNames, indexName,
                unique, indexType);
        return sql.replace("IF NOT EXISTS ", getIfNotExists());
    }

    @Override
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        return String.format("COMMENT ON COLUMN %s.%s IS %s", getQualifiedTableName(catalog, schema, tableName),
                getIdentifier(columnName), getStringLiteral(comment));
    }

    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType,
                                                String typeName, int columnSize, int columnScale, String defaultValue,
                                                boolean nullable) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName;
        try {
            columnTypeName = getSerialTypeName(dataType);
        } catch (RuntimeException e) {
            columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        }
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        if (!nullable) {
            columnDef.append(" NOT NULL");
        }
        return columnDef.toString().trim();
    }

    @Override
    public String getBinaryLiteral(byte[] bytes) {
        return "'\\x" + toHexString(bytes) + "'::bytea";
    }

    @Override
    public String getStringValue(String catalog, String schema, String tableName, String columnName, Object columnValue) {
        if (columnValue instanceof PGobject) {
            String str = ((PGobject) columnValue).getValue();
            if (StringUtils.isNotBlank(str)) {
                str = str.replaceAll("\r\n", "");
            }
            return getStringLiteral(str);
        }
        return super.getStringValue(catalog, schema, tableName, columnName, columnValue);
    }

    @Override
    public boolean isPartitionSupported() {
        return true;
    }

    /**
     * IF NOT EXISTS clause of CREATE INDEX/SEQUENCE
     */
    protected String getIfNotExists() {
        return "IF NOT EXISTS ";
    }

    @Override
    public String getDefinePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String partitionType, String columnNames) {
        return String.format("PARTITION BY %s (%s)", partitionType, getIdentifiers(columnNames));
    }

    @Override
    public String getDefinePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String partitionType, String columnNames,
                                                   Map<String, Object> detail) {
        if (isCrossDatabase() || !isPartitionSupported()) {
            return null;
        }
        String clause = (String) detail.get("PARTITION_CLAUSE");
        return StringUtils.isNotBlank(clause) ? clause
                : getDefinePartitionTableStatement(catalog, schema, tableName, partitionType, columnNames);
    }

    @Override
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return String.format("CREATE TABLE IF NOT EXISTS %s PARTITION OF %s",
                getQualifiedTableName(catalog, schema, tableName),
                getQualifiedTableName(catalog, schema, inheritedTableName));
    }

    @Override
    public String getSelectMaxColumnStatement(String catalog, String schema, String tableName, String columnName) {
        return String.format("SELECT max(%s) FROM %s", quoteIdentifier(columnName),
                getSourceTableName(catalog, schema, tableName));
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
        sql = "CREATE SEQUENCE " + getIfNotExists() + sql.substring("CREATE SEQUENCE ".length());
        if (StringUtils.isNotBlank(dataType) && !isCrossDatabase() && isSequenceDataTypeSupported()) {
            int index = sql.indexOf(" START WITH");
            sql = sql.substring(0, index) + " AS " + dataType + sql.substring(index);
        }
        return sql;
    }

    /**
     * Data type of sequences (CREATE SEQUENCE ... AS bigint)
     */
    protected boolean isSequenceDataTypeSupported() {
        return true;
    }

    @Override
    public String[] getStatementAfterSequenceCreated(String catalog, String schema, String sequenceName,
                                                     long startValue) {
        return new String[]{String.format("SELECT setval('%s', %d, false)",
                getQualifiedSequenceName(catalog, schema, sequenceName).replace("'", "''"), startValue)};
    }

    /**
     * Generated columns are supported since PostgreSQL 12 and must be stored
     */
    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return String.format("%s %s GENERATED ALWAYS AS (%s) STORED", getIdentifier(columnName),
                getColumnTypeName(dataType, typeName, columnSize, columnScale), expression);
    }

    /**
     * NULL of unspecified type is accepted by any column
     */
    @Override
    public int getNullSqlType(int sqlType) {
        return java.sql.Types.OTHER;
    }

    @Override
    public int getMaxFractionalSecondsPrecision() {
        return 6;
    }

    @Override
    public String getCreateSchemaIfNotExistsStatement(String schema) {
        return String.format("CREATE SCHEMA IF NOT EXISTS %s", quoteIdentifier(schema));
    }

    @Override
    public boolean isGeneratedColumnSupported() {
        return true;
    }
}
