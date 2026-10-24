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

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: AccessDialect generates ddl/dml of Microsoft Access files (.accdb, .mdb) through UCanAccess. Access
 *               has no schemas, sequences, partitions or comments. Type names are the ones of Access (INTEGER is
 *               16-bit, LONG 32-bit, BYTE unsigned). Text columns hold 255 characters (MEMO beyond),
 *               decimals 28 digits, date and time columns are DATETIME. 64-bit integers (BIGINT) are kept by files of
 *               Access 2016 and later, older files get DECIMAL(28,1): UCanAccess stores BIGINT of them as 32-bit and
 *               limits DECIMAL of scale 0 to 18 digits. Names are quoted by backticks, explicit autonumbers are
 *               inserted with DISABLE AUTOINCREMENT.
 *               Files of Access 2000 and later are supported, .accdb and .mdb. Access has no vectors.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class AccessDialect extends Dialect {

    /**
     * Characters of TEXT, longer text is MEMO
     */
    static final int MAX_TEXT_LENGTH = 255;
    /**
     * Bytes of BINARY, longer binaries are OLE objects
     */
    static final int MAX_BINARY_LENGTH = 255;
    static final int MAX_NUMERIC_PRECISION = 28;
    /**
     * Precision of DECIMAL of scale 0 which UCanAccess keeps, larger precisions are stored as 18
     */
    static final int MAX_INTEGRAL_DECIMAL_PRECISION = 18;
    /**
     * File format of Access 2016 (VERSION_16), the first one with 64-bit integers
     */
    static final int BIGINT_VERSION = 16;
    private static final Pattern FILE_VERSION = Pattern.compile("VERSION_(\\d+)");
    /**
     * UCanAccess writes a warning for every row it passes while looking for an updated or deleted row, kept here
     * because loggers of java.util.logging are weakly referenced
     */
    private static final Logger CURSOR_LOGGER = Logger.getLogger("net.ucanaccess.commands.AbstractCursorCommand");

    static {
        CURSOR_LOGGER.setLevel(Level.SEVERE);
    }

    public AccessDialect() {
        super();
        registerColumnType(Types.BIT, "YESNO");
        registerColumnType(Types.BOOLEAN, "YESNO");
        // Type names of UCanAccess are the ones of Access: INTEGER is 16-bit, LONG 32-bit, BYTE (and TINYINT) is
        // unsigned 0-255, REAL is DOUBLE
        registerColumnType(Types.TINYINT, "SMALLINT");
        registerColumnType(Types.SMALLINT, "SMALLINT");
        registerColumnType(Types.INTEGER, "LONG");
        registerColumnType(Types.BIGINT, "BIGINT");
        registerColumnType(Types.REAL, "SINGLE");
        registerColumnType(Types.FLOAT, "DOUBLE");
        registerColumnType(Types.DOUBLE, "DOUBLE");
        registerColumnType(Types.NUMERIC, "DECIMAL($p,$s)");
        registerColumnType(Types.DECIMAL, "DECIMAL($p,$s)");

        registerColumnType(Types.DATE, "DATETIME");
        registerColumnType(Types.TIME, "DATETIME");
        registerColumnType(Types.TIMESTAMP, "DATETIME");
        // Access has no time zones, values are migrated in the local time zone
        registerColumnType(Types.TIME_WITH_TIMEZONE, "DATETIME");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "DATETIME");

        registerColumnType(Types.BINARY, "OLE");
        registerColumnType(Types.BINARY, MAX_BINARY_LENGTH, "BINARY($l)");
        registerColumnType(Types.VARBINARY, "OLE");
        // BINARY of UCanAccess pads values to its length
        registerColumnType(Types.VARBINARY, MAX_BINARY_LENGTH, "VARBINARY($l)");
        registerColumnType(Types.LONGVARBINARY, "OLE");
        registerColumnType(Types.BLOB, "OLE");

        for (int dataType : new int[]{Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR}) {
            registerColumnType(dataType, "MEMO");
            registerColumnType(dataType, MAX_TEXT_LENGTH, "TEXT($l)");
        }
        registerColumnType(Types.LONGVARCHAR, "MEMO");
        registerColumnType(Types.LONGNVARCHAR, "MEMO");
        registerColumnType(Types.CLOB, "MEMO");
        registerColumnType(Types.NCLOB, "MEMO");
        registerColumnType(Types.SQLXML, "MEMO");
        registerColumnType(Types.OTHER, "MEMO");
        registerColumnType(Types.JAVA_OBJECT, "MEMO");

        registerReservedWords("add", "all", "alter", "and", "any", "as", "asc", "autoincrement", "between",
                "binary", "bit", "boolean", "by", "byte", "char", "character", "column", "constraint", "counter",
                "create", "currency", "database", "date", "datetime", "decimal", "delete", "desc", "disallow",
                "distinct", "distinctrow", "double", "drop", "exists", "float", "foreign", "from", "general", "group",
                "guid", "having", "ignore", "imp", "in", "index", "inner", "insert", "integer", "into", "is", "join",
                "key", "left", "level", "like", "logical", "long", "longbinary", "longtext", "memo", "money", "mod",
                "name", "not", "null", "number", "numeric", "ole", "on", "option", "or", "order", "outer", "owneraccess",
                "parameters", "password", "percent", "pivot", "primary", "procedure", "real", "references", "right",
                "select", "set", "short", "single", "smallint", "some", "table", "text", "time", "timestamp", "top",
                "transform", "union", "unique", "update", "user", "value", "values", "varbinary", "varchar", "where",
                "with", "yesno");
    }

    @Override
    public DbType getDbType() {
        return DbType.ACCESS;
    }

    /**
     * Version of the Access file format, e.g. 14 of "V2010 [VERSION_14]" or 16 of Access 2016. The version of
     * UCanAccess metadata is the one of its HSQLDB.
     */
    @Override
    public int[] readDatabaseVersion(DatabaseMetaData databaseMetaData) throws SQLException {
        Matcher matcher = FILE_VERSION.matcher(StringUtils.defaultString(databaseMetaData
                .getDatabaseProductVersion()));
        return matcher.find() ? new int[]{Integer.parseInt(matcher.group(1)), 0} : new int[]{-1, -1};
    }

    @Override
    public String getOpenQuote() {
        return "`";
    }

    @Override
    public String getCloseQuote() {
        return "`";
    }

    @Override
    public int getMaxIdentifierLength() {
        return 64;
    }

    @Override
    public int getMaxNumericPrecision() {
        return MAX_NUMERIC_PRECISION;
    }

    /**
     * Numbers without precision, e.g. numeric of PostgreSQL, the 28 digits of Access with 10 decimals
     */
    @Override
    public String getUnboundedNumericTypeName() {
        return "DECIMAL(28,10)";
    }

    /**
     * Dates are stored as days and fractions of a day, milliseconds are kept
     */
    @Override
    public int getMaxFractionalSecondsPrecision() {
        return 3;
    }

    /**
     * Whether the file keeps 64-bit integers: Access 2016 and later. A script for an unknown file is written for
     * the latest format.
     */
    boolean isBigIntSupported() {
        return isVersionAtLeast(BIGINT_VERSION, 0);
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        switch (baseTypeName) {
            case "uuid":
            case "uniqueidentifier":
                return "TEXT(38)";
            case "json":
            case "jsonb":
            case "xml":
            case "xmltype":
            case "text":
            case "mediumtext":
            case "longtext":
            case "tinytext":
                return "MEMO";
            case "inet":
            case "inet4":
            case "inet6":
            case "cidr":
            case "macaddr":
                return "TEXT(50)";
            case "enum":
            case "set":
                return "TEXT(255)";
            default:
                break;
        }
        if (baseTypeName.startsWith("_") || dataType == Types.ARRAY || dataType == Types.STRUCT) {
            return "MEMO";
        }
        if (dataType == Types.BIGINT && !isBigIntSupported()) {
            return "DECIMAL(28,1)";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            // Bit strings are integers, LONG is signed 32-bit
            return columnSize < 32 ? "LONG" : isBigIntSupported() ? "BIGINT" : "DECIMAL(28,1)";
        }
        if ((dataType == Types.NUMERIC || dataType == Types.DECIMAL) && columnSize > 0
                && columnSize <= MAX_NUMERIC_PRECISION) {
            int scale = Math.max(columnScale, 0);
            // UCanAccess stores DECIMAL of scale 0 with 18 digits, one decimal keeps the larger integers
            if (scale == 0 && columnSize > MAX_INTEGRAL_DECIMAL_PRECISION) {
                return String.format("DECIMAL(%d,1)", Math.min(columnSize + 1, MAX_NUMERIC_PRECISION));
            }
        }
        if ((dataType == Types.NUMERIC || dataType == Types.DECIMAL) && columnSize > MAX_NUMERIC_PRECISION) {
            // Numbers wider than 28 digits are kept as text
            return "TEXT(100)";
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

    /**
     * Access has no schemas, tables are named without qualifier
     */
    @Override
    public String getQualifiedTableName(String catalog, String schema, String tableName) {
        return getIdentifier(tableName);
    }

    @Override
    public String getSourceTableName(String catalog, String schema, String tableName) {
        return quoteIdentifier(tableName);
    }

    /**
     * Statements of UCanAccess without IF NOT EXISTS: with it the table is created in its HSQLDB mirror only
     */
    @Override
    public String getCreateTableStatement(String catalog, String schema, String tableName) {
        return String.format("CREATE TABLE %s", getQualifiedTableName(catalog, schema, tableName));
    }

    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType) {
        return String.format("CREATE %sINDEX %s ON %s (%s)", unique ? "UNIQUE " : "", quoteIdentifier(indexName),
                getQualifiedTableName(catalog, schema, tableName), getIdentifiers(columnNames));
    }

    /**
     * UCanAccess drops a table of the Access file by DROP TABLE only, with IF EXISTS or CASCADE it drops the table
     * of its HSQLDB mirror and keeps the one of the file. A missing table is skipped by its error.
     */
    @Override
    public String getDropTableStatement(String catalog, String schema, String tableName) {
        return String.format("DROP TABLE %s", getQualifiedTableName(catalog, schema, tableName));
    }

    /**
     * Rows are deleted before the tables are dropped: UCanAccess deletes the rows of a dropped table and fails on
     * the relationship index of a related table dropped before (Reference to missing table)
     */
    @Override
    public String getEmptyTableStatement(String catalog, String schema, String tableName) {
        return String.format("DELETE FROM %s", getQualifiedTableName(catalog, schema, tableName));
    }

    /**
     * Related tables are created referenced tables first and dropped in the reverse order
     */
    @Override
    public boolean isForeignKeyOrderRequired() {
        return true;
    }

    /**
     * The table of DROP TABLE (or of the DELETE before it) does not exist: "object not found" of HSQLDB (42501)
     */
    @Override
    public boolean isMissingDropError(String sql, SQLException e) {
        String statement = StringUtils.trimToEmpty(sql);
        if (!StringUtils.startsWithIgnoreCase(statement, "DROP TABLE")
                && !StringUtils.startsWithIgnoreCase(statement, "DELETE FROM")) {
            return false;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = StringUtils.defaultString(cause.getMessage());
            if (message.contains("object not found") || message.contains("does not exist")) {
                return true;
            }
        }
        return false;
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
     * UCanAccess looks for an updated row by passing the rows of the table, LOB values are written in INSERT
     * statements
     */
    @Override
    public boolean isLobSeparationSupported() {
        return false;
    }

    @Override
    public boolean isSequenceSupported() {
        return false;
    }

    /**
     * An autonumber continues after the largest value when it is enabled again
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
     * A table of Access has one autonumber
     */
    @Override
    public boolean isSingleIncrementalColumn() {
        return true;
    }

    /**
     * Autonumber (COUNTER) is a 32-bit integer (LONG), other identities keep their type and their values: a column
     * related to an autonumber must be a LONG
     */
    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        String columnType = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        columnDef.append(StringHelper.textLeft("LONG".equals(columnType) ? "COUNTER" : columnType, 30));
        columnDef.append(" NOT NULL");
        return columnDef.toString().trim();
    }

    /**
     * Calculated fields are not created by UCanAccess, generated columns become plain columns
     */
    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return StringHelper.textLeft(getIdentifier(columnName), 30) + getColumnTypeName(dataType, typeName,
                columnSize, columnScale);
    }

    @Override
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return null;
    }

    /**
     * Explicit autonumbers are inserted while the autonumber of the table is disabled
     */
    @Override
    public String[] getStatementBeforeInsert(String catalog, String schema, String tableName,
                                             boolean identityIncluded) {
        if (!identityIncluded) {
            return null;
        }
        return new String[]{"DISABLE AUTOINCREMENT ON " + getQualifiedTableName(catalog, schema, tableName)};
    }

    @Override
    public String[] getStatementAfterInsert(String catalog, String schema, String tableName,
                                            boolean identityIncluded) {
        if (!identityIncluded) {
            return null;
        }
        return new String[]{"ENABLE AUTOINCREMENT ON " + getQualifiedTableName(catalog, schema, tableName)};
    }

    @Override
    public String getUpsertStatement(String catalog, String schema, String tableName, String[] columnNames,
                                     String[] keyColumnNames, String[] updateColumnNames, Object[] values) {
        StringBuilder sql = new StringBuilder("MERGE INTO ").append(getQualifiedTableName(catalog, schema, tableName))
                .append(" tgt USING (VALUES (").append(StringUtils.join(values, ",")).append(")) src (")
                .append(getIdentifiers(columnNames)).append(") ON ").append(Arrays.stream(keyColumnNames)
                        .map(name -> "tgt." + getIdentifier(name) + " = src." + getIdentifier(name))
                        .collect(Collectors.joining(" AND ")));
        if (updateColumnNames.length > 0) {
            sql.append(" WHEN MATCHED THEN UPDATE SET ").append(Arrays.stream(updateColumnNames).map(name -> "tgt."
                    + getIdentifier(name) + " = src." + getIdentifier(name)).collect(Collectors.joining(", ")));
        }
        return sql.append(" WHEN NOT MATCHED THEN INSERT (").append(getIdentifiers(columnNames)).append(") VALUES (")
                .append(Arrays.stream(columnNames).map(name -> "src." + getIdentifier(name))
                        .collect(Collectors.joining(", "))).append(")").toString();
    }

    /**
     * Types of the target columns type the parameters of upserts
     */
    @Override
    public boolean isTargetTypeConversionRequired() {
        return true;
    }

    /**
     * Date times are bound as timestamps: a LocalDateTime in VALUES of MERGE fails in UCanAccess (cardinality
     * violation)
     */
    @Override
    public Object getTargetJdbcValue(Object value, String targetTypeName) {
        if (value instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) value);
        } else if (value instanceof LocalDate) {
            return Timestamp.valueOf(((LocalDate) value).atStartOfDay());
        } else if (value instanceof LocalTime) {
            return Timestamp.valueOf(LocalDateTime.of(LocalDate.of(1899, 12, 30), (LocalTime) value));
        }
        return value;
    }

    /**
     * A parameter of VALUES has no type for UCanAccess, bytes are bound to binary columns and OLE objects (BLOB) by
     * a cast
     */
    @Override
    public String getParameterMarker(String targetTypeName) {
        String type = StringUtils.defaultString(targetTypeName).toUpperCase(Locale.ENGLISH);
        if ("BLOB".equals(type)) {
            return "CAST(? AS BLOB)";
        }
        return "VARBINARY".equals(type) || "BINARY".equals(type) ? "CAST(? AS VARBINARY(" + MAX_BINARY_LENGTH + "))"
                : "?";
    }

    @Override
    public String getCurrentTimestampExpression() {
        return "NOW()";
    }

    @Override
    public String getCurrentDateExpression() {
        return "DATE()";
    }

    @Override
    public String getBooleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    @Override
    public String getTimestampLiteral(LocalDateTime value) {
        return "'" + formatTimestamp(value) + "'";
    }

    @Override
    public String getDateLiteral(LocalDate value) {
        return "'" + value + "'";
    }

    /**
     * A time of Access is a date time of 1899-12-30, the day zero of Access
     */
    @Override
    public String getTimeLiteral(LocalTime value) {
        return "'1899-12-30 " + formatTime(value) + "'";
    }

    /**
     * Times are bound as date times of the day zero of Access
     */
    @Override
    public Object getJdbcValue(Object value, int sourceDataType, String sourceTypeName, int columnSize) {
        Object result = super.getJdbcValue(value, sourceDataType, sourceTypeName, columnSize);
        if (result instanceof LocalTime) {
            return LocalDateTime.of(LocalDate.of(1899, 12, 30), (LocalTime) result);
        }
        return result;
    }

    /**
     * OLE objects are read as blobs: bytes of UCanAccess are the key of the object in its mirror, not the content
     */
    @Override
    public Object getColumnValue(ResultSet rs, int columnIndex, int columnType, String columnTypeName)
            throws SQLException {
        if (columnType == Types.BLOB || columnType == Types.LONGVARBINARY) {
            Blob blob = rs.getBlob(columnIndex);
            return blob != null ? blob.getBytes(1, (int) blob.length()) : null;
        }
        Object value = super.getColumnValue(rs, columnIndex, columnType, columnTypeName);
        if (value instanceof BigDecimal && columnType == Types.NUMERIC
                && rs.getMetaData().getPrecision(columnIndex) > MAX_NUMERIC_PRECISION) {
            // SINGLE of a file is NUMERIC(100,7) for UCanAccess, decimals of Access have 28 digits at most
            return ((BigDecimal) value).doubleValue();
        }
        return value;
    }

    /**
     * Floating point literals are plain numbers, UCanAccess takes the exponent of 1.0E100 as a name
     */
    @Override
    public String getStringValue(String catalog, String schema, String tableName, String columnName,
                                 Object columnValue) {
        if ((columnValue instanceof Double || columnValue instanceof Float)
                && Double.isFinite(((Number) columnValue).doubleValue())) {
            return new BigDecimal(columnValue.toString()).toPlainString();
        }
        return super.getStringValue(catalog, schema, tableName, columnName, columnValue);
    }
}
