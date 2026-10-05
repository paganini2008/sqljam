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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: SQLServerDialect for SQL Server 2012 and later versions
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SQLServerDialect extends Dialect {

    public SQLServerDialect() {
        super();
        registerColumnType(Types.BIT, "bit");
        registerColumnType(Types.BOOLEAN, "bit");
        // tinyint of SQL Server is unsigned
        registerColumnType(Types.TINYINT, "smallint");
        registerColumnType(Types.SMALLINT, "smallint");
        registerColumnType(Types.INTEGER, "int");
        registerColumnType(Types.BIGINT, "bigint");
        registerColumnType(Types.FLOAT, "real");
        registerColumnType(Types.REAL, "real");
        registerColumnType(Types.DOUBLE, "float");
        registerColumnType(Types.NUMERIC, "decimal($p,$s)");
        registerColumnType(Types.DECIMAL, "decimal($p,$s)");

        registerColumnType(Types.DATE, "date");
        registerColumnType(Types.TIME, "time");
        registerColumnType(Types.TIMESTAMP, "datetime2");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "time");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "datetimeoffset");

        registerColumnType(Types.BINARY, "varbinary(max)");
        registerColumnType(Types.BINARY, 8000, "binary($l)");
        registerColumnType(Types.VARBINARY, "varbinary(max)");
        registerColumnType(Types.VARBINARY, 8000, "varbinary($l)");
        registerColumnType(Types.LONGVARBINARY, "varbinary(max)");
        registerColumnType(Types.BLOB, "varbinary(max)");

        registerColumnType(Types.CHAR, "nvarchar(max)");
        registerColumnType(Types.CHAR, 4000, "nchar($l)");
        registerColumnType(Types.NCHAR, "nvarchar(max)");
        registerColumnType(Types.NCHAR, 4000, "nchar($l)");
        registerColumnType(Types.VARCHAR, "nvarchar(max)");
        registerColumnType(Types.VARCHAR, 4000, "nvarchar($l)");
        registerColumnType(Types.NVARCHAR, "nvarchar(max)");
        registerColumnType(Types.NVARCHAR, 4000, "nvarchar($l)");
        registerColumnType(Types.LONGVARCHAR, "nvarchar(max)");
        registerColumnType(Types.LONGNVARCHAR, "nvarchar(max)");
        registerColumnType(Types.CLOB, "nvarchar(max)");
        registerColumnType(Types.NCLOB, "nvarchar(max)");
        registerColumnType(Types.SQLXML, "xml");
        registerColumnType(Types.OTHER, "nvarchar(max)");
        registerColumnType(Types.JAVA_OBJECT, "nvarchar(max)");

        registerReservedWords("backup", "break", "browse", "bulk", "cascade", "checkpoint", "close", "clustered",
                "compute", "contains", "continue", "database", "dbcc", "deallocate", "declare", "deny", "disk",
                "distributed", "dump", "errlvl", "escape", "exec", "execute", "exit", "external", "file",
                "fillfactor", "freetext", "function", "goto", "holdlock", "identity", "identitycol", "if", "kill",
                "lineno", "load", "merge", "national", "nocheck", "nonclustered", "off", "offsets", "open",
                "openquery", "option", "over", "percent", "pivot", "plan", "precision", "print", "proc",
                "procedure", "public", "raiserror", "read", "readtext", "reconfigure", "replication", "restore",
                "restrict", "return", "revert", "revoke", "rollback", "rowcount", "rowguidcol", "rule", "save",
                "schema", "securityaudit", "semantickeyphrasetable", "shutdown", "statistics", "system_user",
                "tablesample", "textsize", "top", "tran", "transaction", "trigger", "truncate", "tsequal", "unpivot",
                "updatetext", "use", "varying", "waitfor", "while", "within", "writetext");
    }

    @Override
    public DbType getDbType() {
        return DbType.SQLSERVER;
    }

    @Override
    public String getOpenQuote() {
        return "[";
    }

    @Override
    public String getCloseQuote() {
        return "]";
    }

    @Override
    public int getMaxIdentifierLength() {
        return 128;
    }

    @Override
    public String getUnboundedNumericTypeName() {
        return "decimal(38,10)";
    }

    @Override
    public String getSourceTableName(String catalog, String schema, String tableName) {
        StringBuilder ref = new StringBuilder();
        if (StringUtils.isNotBlank(catalog)) {
            ref.append(quoteIdentifier(catalog)).append(".");
            ref.append(StringUtils.isNotBlank(schema) ? quoteIdentifier(schema) : "").append(".");
        } else if (StringUtils.isNotBlank(schema)) {
            ref.append(quoteIdentifier(schema)).append(".");
        }
        return ref.append(quoteIdentifier(tableName)).toString();
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replace(" identity", "").replaceAll("\\(.*\\)", "")
                .replace(" unsigned", "").trim();
        if (!isCrossDatabase()) {
            switch (baseTypeName) {
                case "varchar":
                case "nvarchar":
                case "varbinary":
                    return columnSize <= 0 || columnSize > 8000 ? baseTypeName + "(max)"
                            : baseTypeName + "(" + columnSize + ")";
                case "char":
                case "nchar":
                case "binary":
                    return baseTypeName + "(" + columnSize + ")";
                case "datetime2":
                case "datetimeoffset":
                case "time":
                    return baseTypeName + "(" + Math.max(columnScale, 0) + ")";
                case "timestamp":
                case "rowversion":
                    return "rowversion";
                case "tinyint":
                case "datetime":
                case "smalldatetime":
                case "money":
                case "smallmoney":
                case "uniqueidentifier":
                case "xml":
                case "text":
                case "ntext":
                case "image":
                case "sql_variant":
                case "hierarchyid":
                case "geography":
                case "geometry":
                case "date":
                case "float":
                case "real":
                case "bit":
                    return baseTypeName;
                default:
                    return null;
            }
        }
        switch (baseTypeName) {
            case "timestamp":
            case "rowversion":
                // Row version of SQL Server, timestamp of other databases is a date time type
                if (dataType == Types.BINARY || dataType == Types.VARBINARY) {
                    return "binary(8)";
                }
                break;
            case "json":
            case "jsonb":
            case "text":
            case "tinytext":
            case "mediumtext":
            case "longtext":
            case "clob":
            case "nclob":
            case "tsvector":
                return "nvarchar(max)";
            case "bytea":
            case "tinyblob":
            case "blob":
            case "mediumblob":
            case "longblob":
                return "varbinary(max)";
            case "uuid":
                return "uniqueidentifier";
            case "bool":
            case "boolean":
                return "bit";
            case "year":
                return "smallint";
            case "enum":
            case "set":
                return "nvarchar(255)";
            case "inet":
            case "cidr":
            case "macaddr":
            case "interval":
                return "nvarchar(64)";
            case "xml":
            case "xmltype":
                return "xml";
            case "money":
                return "money";
            case "binary_float":
            case "float4":
                return "real";
            case "binary_double":
            case "float8":
                return "float";
            default:
                break;
        }
        if (baseTypeName.startsWith("_")) {
            return "nvarchar(max)";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "bigint";
        }
        if (dataType == Types.TIMESTAMP) {
            return "datetime2(" + Math.min(Math.max(columnScale, 0), 7) + ")";
        }
        if ((dataType == Types.VARCHAR || dataType == Types.NVARCHAR) && columnSize <= 0) {
            return "nvarchar(max)";
        }
        return null;
    }

    @Override
    public String getCreateUserStatement(String username, String password) {
        return String.format("CREATE LOGIN %s WITH PASSWORD = '%s'", getIdentifier(username), password);
    }

    @Override
    public String getCreateDatabaseStatement(String catalog, String username) {
        return String.format("CREATE DATABASE %s", getIdentifier(catalog));
    }

    @Override
    public String getDefaultSchemaName(String catalog) {
        return "dbo";
    }

    @Override
    public String getCreateSchemaStatement(String catalog, String schema, String username) {
        return String.format("CREATE SCHEMA %s", getIdentifier(schema));
    }

    /**
     * Foreign keys referencing the table are dropped before dropping the table
     */
    @Override
    public String getDropTableStatement(String catalog, String schema, String tableName) {
        String table = getQualifiedTableName(catalog, schema, tableName).replace("'", "''");
        return String.format("IF OBJECT_ID(N'%1$s', N'U') IS NOT NULL BEGIN DECLARE @sql NVARCHAR(MAX) = N''; "
                + "SELECT @sql += N'ALTER TABLE ' + QUOTENAME(OBJECT_SCHEMA_NAME(parent_object_id)) + N'.' "
                + "+ QUOTENAME(OBJECT_NAME(parent_object_id)) + N' DROP CONSTRAINT ' + QUOTENAME(name) + N';' "
                + "FROM sys.foreign_keys WHERE referenced_object_id = OBJECT_ID(N'%1$s'); "
                + "EXEC sp_executesql @sql; DROP TABLE %2$s; END", table, getQualifiedTableName(catalog, schema,
                tableName));
    }

    @Override
    public String getCreateTableStatement(String catalog, String schema, String tableName) {
        return String.format("CREATE TABLE %s", getQualifiedTableName(catalog, schema, tableName));
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
        return String.format("DBCC CHECKIDENT ('%s', RESEED, %d)",
                getQualifiedTableName(catalog, schema, tableName).replace("'", "''"), startValue - 1);
    }

    @Override
    public String[] getStatementBeforeInsert(String catalog, String schema, String tableName,
                                             boolean identityIncluded) {
        if (!identityIncluded) {
            return null;
        }
        return new String[]{String.format("SET IDENTITY_INSERT %s ON",
                getQualifiedTableName(catalog, schema, tableName))};
    }

    @Override
    public String[] getStatementAfterInsert(String catalog, String schema, String tableName,
                                            boolean identityIncluded) {
        if (!identityIncluded) {
            return null;
        }
        return new String[]{String.format("SET IDENTITY_INSERT %s OFF",
                getQualifiedTableName(catalog, schema, tableName))};
    }

    private String getCommentSchemaName(String schema) {
        if (StringUtils.isNotBlank(getTargetSchemaName(schema))) {
            return getTargetSchemaName(schema);
        }
        return isCrossDatabase() || StringUtils.isBlank(schema) ? null : schema;
    }

    @Override
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        String schemaName = getCommentSchemaName(schema);
        return String.format("DECLARE @schema SYSNAME = %s; EXEC sp_addextendedproperty @name = N'MS_Description', "
                        + "@value = %s, @level0type = N'SCHEMA', @level0name = @schema, @level1type = N'TABLE', "
                        + "@level1name = %s, @level2type = N'COLUMN', @level2name = %s",
                schemaName != null ? getStringLiteral(schemaName) : "SCHEMA_NAME()", getStringLiteral(comment),
                getStringLiteral(foldIdentifier(tableName)), getStringLiteral(foldIdentifier(columnName)));
    }

    @Override
    public String getCreateTableCommentStatement(String catalog, String schema, String tableName, String comment) {
        String schemaName = getCommentSchemaName(schema);
        return String.format("DECLARE @schema SYSNAME = %s; EXEC sp_addextendedproperty @name = N'MS_Description', "
                        + "@value = %s, @level0type = N'SCHEMA', @level0name = @schema, @level1type = N'TABLE', "
                        + "@level1name = %s",
                schemaName != null ? getStringLiteral(schemaName) : "SCHEMA_NAME()", getStringLiteral(comment),
                getStringLiteral(foldIdentifier(tableName)));
    }

    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType) {
        boolean maxIncluded = Arrays.stream(columnNames).map(name -> getRegisteredColumnTypeName(tableName, name))
                .anyMatch(typeName -> typeName.endsWith("(max)") || typeName.equals("xml")
                        || typeName.equals("text") || typeName.equals("ntext") || typeName.equals("image"));
        if (maxIncluded) {
            return null;
        }
        String sql = String.format("CREATE %sINDEX %s ON %s (%s)", unique ? "UNIQUE " : "",
                quoteIdentifier(indexName), getQualifiedTableName(catalog, schema, tableName),
                getIdentifiers(columnNames));
        if (unique && isCrossDatabase()) {
            // Unique index of SQL Server allows only one NULL
            sql += " WHERE " + Arrays.stream(columnNames).map(name -> getIdentifier(name) + " IS NOT NULL")
                    .collect(Collectors.joining(" AND "));
        }
        return sql;
    }

    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        if (!columnTypeName.matches("(?i)(tinyint|smallint|int|bigint|decimal\\(\\d+,0\\)|numeric\\(\\d+,0\\))")) {
            columnTypeName = "bigint";
        }
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        columnDef.append(" IDENTITY(1,1) NOT NULL");
        return columnDef.toString().trim();
    }

    @Override
    public String getCurrentTimestampExpression() {
        return "CURRENT_TIMESTAMP";
    }

    @Override
    public String getCurrentDateExpression() {
        return "CAST(GETDATE() AS DATE)";
    }

    @Override
    public String getBooleanLiteral(boolean value) {
        return value ? "1" : "0";
    }

    @Override
    public boolean isPartitionSupported() {
        return true;
    }

    @Override
    public String getDefinePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String partitionType, String columnNames,
                                                   Map<String, Object> detail) {
        if (isCrossDatabase() || detail.get("PARTITION_SCHEME") == null) {
            return null;
        }
        return String.format("ON %s(%s)", quoteIdentifier((String) detail.get("PARTITION_SCHEME")),
                getIdentifiers(columnNames));
    }

    @Override
    public String[] getStatementBeforePartitionTableCreated(String catalog, String schema, String tableName,
                                                           Map<String, Object> detail) {
        if (isCrossDatabase() || detail.get("PARTITION_SCHEME") == null) {
            return null;
        }
        String functionName = (String) detail.get("PARTITION_FUNCTION");
        String schemeName = (String) detail.get("PARTITION_SCHEME");
        String createFunction = String.format(
                "IF NOT EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = N'%s') EXEC(N'%s')",
                functionName.replace("'", "''"), ((String) detail.get("PARTITION_FUNCTION_DDL")).replace("'", "''"));
        String createScheme = String.format(
                "IF NOT EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = N'%s') "
                        + "EXEC(N'CREATE PARTITION SCHEME %s AS PARTITION %s ALL TO ([PRIMARY])')",
                schemeName.replace("'", "''"), quoteIdentifier(schemeName).replace("'", "''"),
                quoteIdentifier(functionName).replace("'", "''"));
        return new String[]{createFunction, createScheme};
    }

    @Override
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return null;
    }

    @Override
    public String getPageStatement(String sql, String orderBy, int limit, int offset) {
        return String.format("%s ORDER BY %s OFFSET %d ROWS FETCH NEXT %d ROWS ONLY", sql,
                StringUtils.isNotBlank(orderBy) ? orderBy : "(SELECT NULL)", offset, limit);
    }

    @Override
    public String getStringLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        return "N" + super.getStringLiteral(value);
    }

    @Override
    public String getBinaryLiteral(byte[] bytes) {
        return bytes.length == 0 ? "0x" : "0x" + toHexString(bytes);
    }

    @Override
    public String getTimestampLiteral(LocalDateTime value) {
        return "'" + formatTimestamp(value).replace(' ', 'T') + "'";
    }

    @Override
    public String getDateLiteral(LocalDate value) {
        return "'" + value + "'";
    }

    @Override
    public String getTimeLiteral(LocalTime value) {
        return "'" + formatTime(value) + "'";
    }

    @Override
    public boolean isSequenceSupported() {
        return true;
    }

    @Override
    public String getCreateSequenceStatement(String catalog, String schema, String sequenceName, long startValue,
                                             long increment, Long minValue, Long maxValue, boolean cycle,
                                             Long cacheSize, String dataType) {
        if (!isSequenceSupported()) {
            return null;
        }
        String name = getQualifiedSequenceName(catalog, schema, sequenceName);
        String sql = super.getCreateSequenceStatement(catalog, schema, sequenceName, startValue, increment, minValue,
                maxValue, cycle, cacheSize, dataType);
        String typeName = StringUtils.isNotBlank(dataType) && !isCrossDatabase() ? dataType : "bigint";
        int index = sql.indexOf(" START WITH");
        sql = sql.substring(0, index) + " AS " + typeName + sql.substring(index);
        return String.format("IF OBJECT_ID(N'%1$s', N'SO') IS NULL EXEC(N'%2$s') ELSE ALTER SEQUENCE %3$s RESTART WITH %4$d",
                name.replace("'", "''"), sql.replace("'", "''"), name, startValue);
    }

    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return String.format("%s AS %s%s", getIdentifier(columnName), expression, stored ? " PERSISTED" : "");
    }

    @Override
    public boolean isSingleIncrementalColumn() {
        return true;
    }

    @Override
    public int getMaxFractionalSecondsPrecision() {
        return 7;
    }

    @Override
    public String getCreateSchemaIfNotExistsStatement(String schema) {
        return String.format("IF SCHEMA_ID(N'%s') IS NULL EXEC(N'CREATE SCHEMA %s')", schema.replace("'", "''"),
                quoteIdentifier(schema).replace("'", "''"));
    }
}
