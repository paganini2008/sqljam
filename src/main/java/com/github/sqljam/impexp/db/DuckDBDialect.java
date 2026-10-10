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

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: DuckDBDialect for DuckDB, an embedded analytical database. Identity columns are implemented by
 *               sequences and nextval defaults. Sequences can not be altered, so they are recreated to reset the
 *               next value. Foreign keys and unique constraints can not be added by ALTER TABLE, foreign keys are
 *               defined in CREATE TABLE and unique constraints by unique indexes.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class DuckDBDialect extends Dialect {

    /**
     * Max precision of DECIMAL
     */
    public static final int MAX_DECIMAL_PRECISION = 38;

    /**
     * Whether values which DuckDB can not hold exactly (unbounded numerics, time with time zone) are stored as
     * text, used when DuckDB is the intermediate database of Parquet files
     */
    private boolean exactValues;

    /**
     * Database type which the rows are exported for (exact mode): values of the same database type are kept as
     * text if DuckDB has no exact type for them (offsets of times, bit strings), they are converted otherwise
     */
    private DbType exactTargetDbType;

    public DuckDBDialect() {
        super();
        registerColumnType(Types.BIT, "BOOLEAN");
        registerColumnType(Types.BOOLEAN, "BOOLEAN");
        registerColumnType(Types.TINYINT, "TINYINT");
        registerColumnType(Types.SMALLINT, "SMALLINT");
        registerColumnType(Types.INTEGER, "INTEGER");
        registerColumnType(Types.BIGINT, "BIGINT");
        // FLOAT of jdbc is a double precision number
        registerColumnType(Types.FLOAT, "DOUBLE");
        registerColumnType(Types.REAL, "REAL");
        registerColumnType(Types.DOUBLE, "DOUBLE");
        registerColumnType(Types.NUMERIC, "DECIMAL($p,$s)");
        registerColumnType(Types.DECIMAL, "DECIMAL($p,$s)");

        registerColumnType(Types.DATE, "DATE");
        registerColumnType(Types.TIME, "TIME");
        registerColumnType(Types.TIMESTAMP, "TIMESTAMP");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "TIMETZ");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMPTZ");

        registerColumnType(Types.BINARY, "BLOB");
        registerColumnType(Types.VARBINARY, "BLOB");
        registerColumnType(Types.LONGVARBINARY, "BLOB");
        registerColumnType(Types.BLOB, "BLOB");
        registerColumnType(Types.JAVA_OBJECT, "BLOB");

        // Lengths of character types are not enforced by DuckDB
        registerColumnType(Types.CHAR, "VARCHAR");
        registerColumnType(Types.NCHAR, "VARCHAR");
        registerColumnType(Types.VARCHAR, "VARCHAR");
        registerColumnType(Types.NVARCHAR, "VARCHAR");
        registerColumnType(Types.LONGVARCHAR, "VARCHAR");
        registerColumnType(Types.LONGNVARCHAR, "VARCHAR");
        registerColumnType(Types.CLOB, "VARCHAR");
        registerColumnType(Types.NCLOB, "VARCHAR");
        registerColumnType(Types.SQLXML, "VARCHAR");
        registerColumnType(Types.OTHER, "VARCHAR");

        registerReservedWords("analyze", "array", "asymmetric", "both", "cast", "collate", "columns", "do",
                "lambda", "lateral", "leading", "offset", "pivot", "pivot_longer", "pivot_wider", "placing",
                "qualify", "returning", "show", "summarize", "symmetric", "trailing", "unpivot", "using",
                "variadic", "window");
    }

    @Override
    public DbType getDbType() {
        return DbType.DUCKDB;
    }

    public boolean isExactValues() {
        return exactValues;
    }

    public void setExactValues(boolean exactValues) {
        this.exactValues = exactValues;
    }

    public DbType getExactTargetDbType() {
        return exactTargetDbType;
    }

    public void setExactTargetDbType(DbType exactTargetDbType) {
        this.exactTargetDbType = exactTargetDbType;
    }

    /**
     * Timestamps with time zone are kept as text when they go back to the same database type, except Oracle which
     * parses text by session formats
     */
    private boolean isOffsetTextKept() {
        return isTextKept() && getSourceDbType() != DbType.ORACLE;
    }

    /**
     * Values are kept as text when they go back to the same database type
     */
    private boolean isTextKept() {
        return exactValues && exactTargetDbType != null && exactTargetDbType == getSourceDbType();
    }

    /**
     * Settings of DuckDB are kept by the dialect of the detected version
     */
    @Override
    public Dialect forVersion(int majorVersion, int minorVersion) {
        Dialect dialect = super.forVersion(majorVersion, minorVersion);
        if (dialect instanceof DuckDBDialect) {
            ((DuckDBDialect) dialect).setExactValues(exactValues);
            ((DuckDBDialect) dialect).setExactTargetDbType(exactTargetDbType);
        }
        return dialect;
    }

    /**
     * Lists are accepted as text
     */
    @Override
    public boolean isArrayParameterSupported() {
        return false;
    }

    @Override
    public IdentifierCase getStoredIdentifierCase() {
        return IdentifierCase.LOWER;
    }

    @Override
    public int getMaxIdentifierLength() {
        return 255;
    }

    @Override
    public int getMaxNumericPrecision() {
        return MAX_DECIMAL_PRECISION;
    }

    /**
     * Numerics without precision (or wider than DECIMAL) are doubles, or text when values must be exact
     */
    @Override
    public String getUnboundedNumericTypeName() {
        return exactValues ? "VARCHAR" : "DOUBLE";
    }

    /**
     * Type name of DuckDB keeps parameters and nested types, e.g. DECIMAL(12,2), INTEGER[], STRUCT(a INTEGER)
     */
    @Override
    protected String getNativeTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        if (exactValues && StringUtils.startsWithIgnoreCase(sourceTypeName, "UNION(")) {
            // Unions are written as unnamed structs to Parquet, which can not be read back into tables
            return "VARCHAR";
        }
        if (isTextKept() && "TIME WITH TIME ZONE".equalsIgnoreCase(sourceTypeName)) {
            // Parquet keeps times in UTC, the offset is kept by text
            return "VARCHAR";
        }
        return sourceTypeName;
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        switch (baseTypeName) {
            case "json":
            case "jsonb":
                return "JSON";
            case "uuid":
            case "uniqueidentifier":
                return "UUID";
            case "bool":
            case "boolean":
                return "BOOLEAN";
            case "bytea":
                return "BLOB";
            case "year":
                return "SMALLINT";
            case "money":
            case "smallmoney":
                return "DECIMAL(19,4)";
            case "datetimeoffset":
                return isOffsetTextKept() ? "VARCHAR" : "TIMESTAMPTZ";
            case "timetz":
            case "time with time zone":
                return isTextKept() ? "VARCHAR" : "TIMETZ";
            case "xml":
            case "xmltype":
            case "enum":
            case "set":
                return "VARCHAR";
            default:
                break;
        }
        if (baseTypeName.startsWith("_")) {
            // Arrays of PostgreSQL are migrated as text
            return "VARCHAR";
        }
        if (isTextKept() && getSourceDbType() == DbType.POSTGRESQL && ("bit".equals(baseTypeName)
                || "varbit".equals(baseTypeName))) {
            // Bit strings of PostgreSQL are kept as text
            return "VARCHAR";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "BIGINT";
        }
        if (dataType == Types.TIME_WITH_TIMEZONE && isTextKept()) {
            return "VARCHAR";
        }
        if (dataType == Types.TIMESTAMP_WITH_TIMEZONE && isOffsetTextKept()) {
            // Offsets and fractional seconds of timestamps with time zone are kept by text
            return "VARCHAR";
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
        return getCreateSchemaIfNotExistsStatement(schema);
    }

    @Override
    public String getCreateSchemaIfNotExistsStatement(String schema) {
        return String.format("CREATE SCHEMA IF NOT EXISTS %s", quoteIdentifier(schema));
    }

    @Override
    public String getDefaultSchemaName(String catalog) {
        return "main";
    }

    /**
     * Sequences of identity columns: table_column_seq in the schema of the table
     */
    @Override
    public String getDefaultSequenceName(String catalog, String schema, String tableName, String columnName) {
        String name = getLimitedIdentifier(foldIdentifier(tableName + "_" + columnName + "_seq"));
        String qualifier = getQualifier(getTargetCatalogName(), getTargetSchemaName(schema));
        return StringUtils.isNotBlank(qualifier) ? qualifier + "." + quoteIdentifier(name) : quoteIdentifier(name);
    }

    @Override
    public String getSequenceNameStatement(String catalog, String schema, String tableName, String columnName) {
        return null;
    }

    /**
     * ALTER SEQUENCE is not supported, sequences are recreated
     */
    @Override
    public String getAlterSequenceStartValueStatement(String catalog, String schema, String tableName,
                                                      String sequenceName, long startValue) {
        return null;
    }

    @Override
    public boolean isSequenceSupported() {
        return true;
    }

    /**
     * CACHE is not supported, an existing sequence is kept since columns may depend on it
     */
    @Override
    public String getCreateSequenceStatement(String catalog, String schema, String sequenceName, long startValue,
                                             long increment, Long minValue, Long maxValue, boolean cycle,
                                             Long cacheSize, String dataType) {
        String sql = super.getCreateSequenceStatement(catalog, schema, sequenceName, startValue, increment, minValue,
                maxValue, cycle, null, dataType);
        return "CREATE SEQUENCE IF NOT EXISTS " + sql.substring("CREATE SEQUENCE ".length());
    }

    /**
     * The identity column gets a sequence and a nextval default after the table is created
     */
    @Override
    public String[] getStatementAfterIncrementalColumnCreated(String catalog, String schema, String tableName,
                                                              String columnName) {
        String sequenceName = getDefaultSequenceName(catalog, schema, tableName, columnName);
        return new String[]{String.format("DROP SEQUENCE IF EXISTS %s", sequenceName),
                String.format("CREATE SEQUENCE %s START 1", sequenceName),
                String.format("ALTER TABLE %s ALTER COLUMN %s SET DEFAULT nextval('%s')",
                        getQualifiedTableName(catalog, schema, tableName), getIdentifier(columnName),
                        sequenceName.replace("'", "''"))};
    }

    /**
     * The sequence of the identity column is recreated with the next value, statements are executed together
     */
    @Override
    public String getResetIdentityStatement(String catalog, String schema, String tableName, String columnName,
                                            long startValue) {
        String sequenceName = getDefaultSequenceName(catalog, schema, tableName, columnName);
        String table = getQualifiedTableName(catalog, schema, tableName);
        String column = getIdentifier(columnName);
        return String.format("ALTER TABLE %1$s ALTER COLUMN %2$s DROP DEFAULT; DROP SEQUENCE IF EXISTS %3$s; "
                        + "CREATE SEQUENCE %3$s START %4$d; "
                        + "ALTER TABLE %1$s ALTER COLUMN %2$s SET DEFAULT nextval('%5$s')", table, column,
                sequenceName, startValue, sequenceName.replace("'", "''"));
    }

    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        if (columnTypeName.startsWith("DECIMAL")) {
            // Sequences generate integers
            columnTypeName = "BIGINT";
        }
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        columnDef.append(" NOT NULL");
        return columnDef.toString().trim();
    }

    @Override
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        return String.format("COMMENT ON COLUMN %s.%s IS %s", getQualifiedTableName(catalog, schema, tableName),
                getIdentifier(columnName), getStringLiteral(comment));
    }

    /**
     * DuckDB can not add foreign keys by ALTER TABLE, they are defined in CREATE TABLE
     */
    @Override
    public boolean isForeignKeyInline() {
        return true;
    }

    /**
     * Foreign keys are always checked: referenced tables are created and loaded first, referencing tables are
     * dropped first
     */
    @Override
    public boolean isForeignKeyOrderRequired() {
        return true;
    }

    /**
     * ON DELETE and ON UPDATE actions are not supported
     */
    @Override
    public String getCreateForeignKeyStatement(String catalog, String schema, String tableName, String fkName,
                                               String[] columnNames, String refTableName, String[] refColumnNames,
                                               String updateRule, String deleteRule) {
        return String.format("CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s (%s)",
                quoteIdentifier(getLimitedIdentifier(foldIdentifier(fkName))), getIdentifiers(columnNames),
                getQualifiedTableName(catalog, schema, refTableName), getIdentifiers(refColumnNames));
    }

    @Override
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return null;
    }

    /**
     * Generated columns of DuckDB are always virtual
     */
    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return String.format("%s %s GENERATED ALWAYS AS (%s) VIRTUAL", getIdentifier(columnName),
                getColumnTypeName(dataType, typeName, columnSize, columnScale), expression);
    }

    /**
     * Nested types, enums, intervals, json and uuid are read as text, which every database accepts
     */
    @Override
    public String getSelectColumnExpression(String columnName, String typeName) {
        if (DuckDBMetaDataOperations.isTextReadType(typeName)) {
            return String.format("CAST(%1$s AS VARCHAR) AS %1$s", quoteIdentifier(columnName));
        }
        return super.getSelectColumnExpression(columnName, typeName);
    }

    /**
     * Rows without primary key are ordered by rowid, LIMIT/OFFSET of parallel scans is not deterministic otherwise
     */
    @Override
    public String getPageStatement(String sql, String orderBy, int limit, int offset) {
        return super.getPageStatement(sql, StringUtils.isNotBlank(orderBy) ? orderBy : "rowid", limit, offset);
    }

    @Override
    public String getCurrentDateExpression() {
        return "CURRENT_DATE";
    }

    @Override
    public String getBooleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    @Override
    public String getBinaryLiteral(byte[] bytes) {
        StringBuilder literal = new StringBuilder("'");
        String hex = toHexString(bytes);
        for (int i = 0; i < hex.length(); i += 2) {
            literal.append("\\x").append(hex, i, i + 2);
        }
        return literal.append("'::BLOB").toString();
    }

    @Override
    public String getTimestampWithTimeZoneLiteral(OffsetDateTime value) {
        return "TIMESTAMPTZ '" + formatTimestamp(value.toLocalDateTime()) + value.getOffset() + "'";
    }

    /**
     * Microseconds, the precision of Parquet timestamps written by DuckDB
     */
    /**
     * Parses text of a timestamp with offset, returns the text itself if it can not be parsed
     */
    static Object parseOffsetDateTime(String text) {
        String value = text.trim().replaceFirst("\\s+([+-]\\d{2}:?\\d{2})$", "$1").replace(' ', 'T');
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            return text;
        }
    }

    @Override
    public int getMaxFractionalSecondsPrecision() {
        return 6;
    }

    /**
     * Values which DuckDB can not bind directly are converted: offset times as text in exact mode, other
     * temporal values as timestamps
     */
    @Override
    public Object getJdbcValue(Object value, int sourceDataType, String sourceTypeName, int columnSize) {
        if (isTextKept()) {
            if (value instanceof CharSequence && getSourceDbType() == DbType.POSTGRESQL
                    && ("bit".equalsIgnoreCase(sourceTypeName) || "varbit".equalsIgnoreCase(sourceTypeName)
                    || "timetz".equalsIgnoreCase(sourceTypeName))) {
                return value.toString();
            }
            if (value instanceof OffsetTime) {
                // Offsets of times are kept by text, e.g. 12:00:00+08:00
                OffsetTime time = (OffsetTime) value;
                return formatTime(time.toLocalTime()) + time.getOffset();
            }
        }
        if (isOffsetTextKept() && Dialect.normalizeDataType(sourceDataType) == Types.TIMESTAMP_WITH_TIMEZONE) {
            if (value instanceof OffsetDateTime) {
                OffsetDateTime dateTime = (OffsetDateTime) value;
                return formatTimestamp(dateTime.toLocalDateTime()) + dateTime.getOffset();
            }
            if (value instanceof CharSequence) {
                return value.toString();
            }
        }
        if (value instanceof CharSequence && Dialect.normalizeDataType(sourceDataType)
                == Types.TIMESTAMP_WITH_TIMEZONE) {
            // Text of offset timestamps, e.g. 2024-02-29 12:00:00.1234567 +08:00 (DateTimeOffset of SQL Server)
            value = parseOffsetDateTime(value.toString());
        }
        Object result = super.getJdbcValue(value, sourceDataType, sourceTypeName, columnSize);
        if (result instanceof OffsetTime) {
            return result.toString();
        }
        if (result instanceof UUID) {
            return result.toString();
        }
        if (result instanceof LocalDateTime) {
            LocalDateTime dateTime = (LocalDateTime) result;
            // Nanoseconds (TIMESTAMP_NS) are kept by text
            return dateTime.getNano() % 1000 != 0 ? formatTimestamp(dateTime) : Timestamp.valueOf(dateTime);
        }
        return result;
    }
}
