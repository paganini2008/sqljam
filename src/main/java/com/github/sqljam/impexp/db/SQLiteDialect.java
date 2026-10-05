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

import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: SQLiteDialect for SQLite 3
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SQLiteDialect extends Dialect {

    public SQLiteDialect() {
        super();
        registerColumnType(Types.BIT, "BOOLEAN");
        registerColumnType(Types.BOOLEAN, "BOOLEAN");
        // INTEGER PRIMARY KEY is an alias of rowid
        registerColumnType(Types.TINYINT, "INTEGER");
        registerColumnType(Types.SMALLINT, "INTEGER");
        registerColumnType(Types.INTEGER, "INTEGER");
        registerColumnType(Types.BIGINT, "INTEGER");
        registerColumnType(Types.FLOAT, "REAL");
        registerColumnType(Types.REAL, "REAL");
        registerColumnType(Types.DOUBLE, "REAL");
        registerColumnType(Types.NUMERIC, "NUMERIC($p,$s)");
        registerColumnType(Types.DECIMAL, "NUMERIC($p,$s)");

        registerColumnType(Types.DATE, "DATE");
        registerColumnType(Types.TIME, "TIME");
        registerColumnType(Types.TIMESTAMP, "DATETIME");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "TIME");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "DATETIME");

        registerColumnType(Types.BINARY, "BLOB");
        registerColumnType(Types.VARBINARY, "BLOB");
        registerColumnType(Types.LONGVARBINARY, "BLOB");
        registerColumnType(Types.BLOB, "BLOB");

        registerColumnType(Types.CHAR, "TEXT");
        registerColumnType(Types.CHAR, 65535, "CHAR($l)");
        registerColumnType(Types.NCHAR, "TEXT");
        registerColumnType(Types.NCHAR, 65535, "CHAR($l)");
        registerColumnType(Types.VARCHAR, "TEXT");
        registerColumnType(Types.VARCHAR, 65535, "VARCHAR($l)");
        registerColumnType(Types.NVARCHAR, "TEXT");
        registerColumnType(Types.NVARCHAR, 65535, "VARCHAR($l)");
        registerColumnType(Types.LONGVARCHAR, "TEXT");
        registerColumnType(Types.LONGNVARCHAR, "TEXT");
        registerColumnType(Types.CLOB, "TEXT");
        registerColumnType(Types.NCLOB, "TEXT");
        registerColumnType(Types.SQLXML, "TEXT");
        registerColumnType(Types.OTHER, "TEXT");
        registerColumnType(Types.JAVA_OBJECT, "TEXT");

        registerReservedWords("abort", "action", "after", "analyze", "attach", "autoincrement", "before", "begin",
                "cascade", "conflict", "deferrable", "deferred", "detach", "each", "exclusive", "explain", "fail",
                "glob", "if", "ignore", "immediate", "indexed", "initially", "instead", "isnull", "notnull",
                "plan", "pragma", "query", "raise", "recursive", "regexp", "reindex", "release", "rename",
                "replace", "restrict", "rollback", "savepoint", "temp", "temporary", "transaction", "trigger",
                "vacuum", "virtual", "without");
    }

    @Override
    public DbType getDbType() {
        return DbType.SQLITE;
    }

    @Override
    public int getMaxIdentifierLength() {
        return 128;
    }

    @Override
    public int getMaxNumericPrecision() {
        return 1000;
    }

    /**
     * Declared type of SQLite column
     */
    @Override
    protected String getNativeTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        if (sourceTypeName.indexOf('(') < 0 && columnSize > 0 && (dataType == Types.CHAR
                || dataType == Types.VARCHAR || dataType == Types.NUMERIC)) {
            return dataType == Types.NUMERIC
                    ? String.format("%s(%d,%d)", sourceTypeName, columnSize, Math.max(columnScale, 0))
                    : String.format("%s(%d)", sourceTypeName, columnSize);
        }
        return sourceTypeName;
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        switch (baseTypeName) {
            case "json":
            case "jsonb":
            case "uuid":
            case "uniqueidentifier":
            case "enum":
            case "set":
            case "text":
            case "tinytext":
            case "mediumtext":
            case "longtext":
            case "xml":
            case "xmltype":
                return "TEXT";
            case "bool":
            case "boolean":
                return "BOOLEAN";
            case "bytea":
                return "BLOB";
            default:
                break;
        }
        if (baseTypeName.startsWith("_")) {
            return "TEXT";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "INTEGER";
        }
        if ((dataType == Types.VARCHAR || dataType == Types.NVARCHAR) && columnSize <= 0) {
            return "TEXT";
        }
        return null;
    }

    @Override
    public String getCreateUserStatement(String username, String password) {
        return null;
    }

    @Override
    public String getCreateDatabaseStatement(String catalog, String username) {
        return null;
    }

    @Override
    public String getCreateSchemaStatement(String catalog, String schema, String username) {
        return null;
    }

    @Override
    public String getDropTableStatement(String catalog, String schema, String tableName) {
        return String.format("DROP TABLE IF EXISTS %s", getQualifiedTableName(catalog, schema, tableName));
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

    /**
     * Next rowid is always max(rowid) + 1, no need to reset
     */
    @Override
    public String getResetIdentityStatement(String catalog, String schema, String tableName, String columnName,
                                            long startValue) {
        return null;
    }

    @Override
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        return null;
    }

    @Override
    public String getCreateTableCommentStatement(String catalog, String schema, String tableName, String comment) {
        return null;
    }

    /**
     * SQLite can not add foreign key by ALTER TABLE, foreign keys are defined in CREATE TABLE
     */
    @Override
    public boolean isForeignKeyInline() {
        return true;
    }

    @Override
    public String getCreateForeignKeyStatement(String catalog, String schema, String tableName, String fkName,
                                               String[] columnNames, String refTableName, String[] refColumnNames,
                                               String updateRule, String deleteRule) {
        StringBuilder sql = new StringBuilder(String.format("CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s (%s)",
                quoteIdentifier(getLimitedIdentifier(foldIdentifier(fkName))), getIdentifiers(columnNames),
                getIdentifier(refTableName), getIdentifiers(refColumnNames)));
        if (deleteRule != null) {
            sql.append(" ON DELETE ").append(deleteRule);
        }
        if (updateRule != null) {
            sql.append(" ON UPDATE ").append(updateRule);
        }
        return sql.toString();
    }

    /**
     * Identity column is declared as INTEGER and becomes an alias of rowid by the primary key constraint
     */
    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        columnDef.append(StringHelper.textLeft("INTEGER", 30));
        columnDef.append(" NOT NULL");
        return columnDef.toString().trim();
    }

    @Override
    public String getCurrentDateExpression() {
        return "CURRENT_DATE";
    }

    @Override
    public String getBooleanLiteral(boolean value) {
        return value ? "1" : "0";
    }

    @Override
    public String getTimestampLiteral(LocalDateTime value) {
        return "'" + formatTimestamp(value) + "'";
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
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return null;
    }

    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return String.format("%s %s GENERATED ALWAYS AS (%s) %s", getIdentifier(columnName),
                getColumnTypeName(dataType, typeName, columnSize, columnScale), expression,
                stored ? "STORED" : "VIRTUAL");
    }

    @Override
    public boolean isIncrementalColumnKeyRequired() {
        return true;
    }

    /**
     * Date/time values are stored as ISO text
     */
    @Override
    public Object getJdbcValue(Object value, int sourceDataType, String sourceTypeName, int columnSize) {
        Object result = super.getJdbcValue(value, sourceDataType, sourceTypeName, columnSize);
        if (result instanceof LocalDateTime) {
            return formatTimestamp((LocalDateTime) result);
        } else if (result instanceof LocalDate || result instanceof java.time.OffsetDateTime) {
            return result.toString();
        } else if (result instanceof LocalTime) {
            return formatTime((LocalTime) result);
        }
        return result;
    }
}
