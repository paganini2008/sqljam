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

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: ClickHouseDialect generates ddl/dml of ClickHouse: tables of the MergeTree engine sorted by the
 *               primary key, Nullable types for nullable columns, comments in column definitions. ClickHouse has no
 *               transactions, identity columns, sequences, foreign keys and unique indexes, they are not created.
 *               Temporal values are bound as text, so that their wall clock time is kept whatever the time zones of
 *               the client and the server are.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ClickHouseDialect extends Dialect {

    /**
     * Max precision of Decimal
     */
    public static final int MAX_DECIMAL_PRECISION = 76;

    /**
     * Engine of created tables
     */
    public static final String DEFAULT_ENGINE = "MergeTree";

    private static final Pattern HEX = Pattern.compile("^(?:[0-9A-F]{2})*$");

    /**
     * Labels of string columns selected by hex
     */
    private final Set<String> hexColumns = ConcurrentHashMap.newKeySet();

    public ClickHouseDialect() {
        super();
        registerColumnType(Types.BIT, "Bool");
        registerColumnType(Types.BOOLEAN, "Bool");
        registerColumnType(Types.TINYINT, "Int8");
        registerColumnType(Types.SMALLINT, "Int16");
        registerColumnType(Types.INTEGER, "Int32");
        registerColumnType(Types.BIGINT, "Int64");
        // FLOAT of jdbc is a double precision number
        registerColumnType(Types.FLOAT, "Float64");
        registerColumnType(Types.REAL, "Float32");
        registerColumnType(Types.DOUBLE, "Float64");
        registerColumnType(Types.NUMERIC, "Decimal($p,$s)");
        registerColumnType(Types.DECIMAL, "Decimal($p,$s)");

        registerColumnType(Types.DATE, "Date32");
        // Time types of ClickHouse are experimental, times are kept as text
        registerColumnType(Types.TIME, "String");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "String");
        registerColumnType(Types.TIMESTAMP, "DateTime64(6)");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "DateTime64(6)");

        // String of ClickHouse is a sequence of bytes, lengths are not declared
        registerColumnType(Types.BINARY, "String");
        registerColumnType(Types.VARBINARY, "String");
        registerColumnType(Types.LONGVARBINARY, "String");
        registerColumnType(Types.BLOB, "String");
        registerColumnType(Types.JAVA_OBJECT, "String");
        registerColumnType(Types.CHAR, "String");
        registerColumnType(Types.NCHAR, "String");
        registerColumnType(Types.VARCHAR, "String");
        registerColumnType(Types.NVARCHAR, "String");
        registerColumnType(Types.LONGVARCHAR, "String");
        registerColumnType(Types.LONGNVARCHAR, "String");
        registerColumnType(Types.CLOB, "String");
        registerColumnType(Types.NCLOB, "String");
        registerColumnType(Types.SQLXML, "String");
        registerColumnType(Types.OTHER, "String");
        registerColumnType(Types.ARRAY, "String");
    }

    @Override
    public DbType getDbType() {
        return DbType.CLICKHOUSE;
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
     * Numerics without precision (or wider than Decimal) are kept as text
     */
    @Override
    public String getUnboundedNumericTypeName() {
        return "String";
    }

    /**
     * Arrays are accepted as text
     */
    @Override
    public boolean isArrayParameterSupported() {
        return false;
    }

    /**
     * Type name of ClickHouse keeps parameters and nested types, e.g. Decimal(12, 2), Array(Int32), Enum8('a' = 1)
     */
    @Override
    protected String getNativeTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        return sourceTypeName;
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        switch (baseTypeName) {
            case "uuid":
            case "uniqueidentifier":
                return "UUID";
            case "bool":
            case "boolean":
                return "Bool";
            case "year":
                return "Int16";
            case "money":
            case "smallmoney":
                return "Decimal(19,4)";
            case "json":
            case "jsonb":
            case "xml":
            case "xmltype":
            case "enum":
            case "set":
            case "interval":
                return "String";
            default:
                break;
        }
        if (baseTypeName.startsWith("_") || baseTypeName.startsWith("interval")) {
            // Arrays of PostgreSQL and intervals are migrated as text
            return "String";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "Int64";
        }
        if (dataType == Types.TIMESTAMP || dataType == Types.TIMESTAMP_WITH_TIMEZONE) {
            return "DateTime64(" + Math.min(Math.max(columnScale, 0), 6) + ")";
        }
        return null;
    }

    /**
     * Nullable columns are Nullable types, columns are not null by default. Nested types and LowCardinality types
     * can not be inside Nullable.
     */
    @Override
    public String getColumnStatement(String catalog, String schema, String tableName, String columnName, int dataType,
                                     String typeName, int columnSize, int columnScale, String defaultValue,
                                     boolean nullable, String comment) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        columnDef.append(StringHelper.textLeft(nullable ? toNullable(columnTypeName) : columnTypeName, 30));
        String value = getDefaultValue(dataType, typeName, defaultValue);
        if (StringUtils.isNotBlank(value)) {
            columnDef.append(" DEFAULT ").append(value);
        }
        if (StringUtils.isNotBlank(comment)) {
            columnDef.append(" COMMENT ").append(getStringLiteral(comment));
        }
        return columnDef.toString().trim();
    }

    @Override
    public String getColumnStatement(String catalog, String schema, String tableName, String columnName, int dataType,
                                     String typeName, int columnSize, int columnScale, String defaultValue,
                                     boolean nullable) {
        return getColumnStatement(catalog, schema, tableName, columnName, dataType, typeName, columnSize,
                columnScale, defaultValue, nullable, null);
    }

    /**
     * Nullable type of a column type, LowCardinality(String) becomes LowCardinality(Nullable(String))
     */
    static String toNullable(String columnTypeName) {
        String baseType = ClickHouseMetaDataOperations.getBaseType(columnTypeName);
        if (columnTypeName.startsWith("Nullable(")) {
            return columnTypeName;
        }
        if (columnTypeName.startsWith("LowCardinality(")) {
            return "LowCardinality(Nullable(" + columnTypeName.substring("LowCardinality(".length(),
                    columnTypeName.length() - 1) + "))";
        }
        switch (baseType) {
            case "array":
            case "map":
            case "tuple":
            case "nested":
            case "json":
            case "object":
            case "variant":
            case "dynamic":
                return columnTypeName;
            default:
                return "Nullable(" + columnTypeName + ")";
        }
    }

    /**
     * Identity columns are normal columns, imported values are kept
     */
    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        return getColumnStatement(catalog, schema, tableName, columnName, dataType, typeName, columnSize,
                columnScale, null, false, null);
    }

    /**
     * MATERIALIZED columns are stored, ALIAS columns are computed when read
     */
    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return String.format("%s %s %s %s", getIdentifier(columnName),
                getColumnTypeName(dataType, typeName, columnSize, columnScale), stored ? "MATERIALIZED" : "ALIAS",
                expression);
    }

    /**
     * The primary key of other databases is the sorting key, tables of ClickHouse keep their own primary key by
     * the table engine
     */
    @Override
    public String getCreatePrimaryKeyStatement(String catalog, String schema, String tableName, String columnName,
                                               String pkeyName) {
        if (!isCrossDatabase()) {
            return null;
        }
        return String.format("PRIMARY KEY (%s)", getIdentifiers(columnName));
    }

    /**
     * Tables of the same database type keep their engine, sorting key, partition and settings. Other tables are
     * MergeTree tables sorted by the primary key.
     */
    @Override
    public String getTableOptions(String catalog, String schema, String tableName, Map<String, Object> detail,
                                  List<String> primaryKeyColumnNames) {
        String engine = detail != null ? (String) detail.get("ENGINE_FULL") : null;
        if (!isCrossDatabase() && StringUtils.isNotBlank(engine)) {
            return "ENGINE = " + engine;
        }
        String orderBy = primaryKeyColumnNames == null || primaryKeyColumnNames.isEmpty() ? "tuple()"
                : "(" + primaryKeyColumnNames.stream().map(this::getIdentifier).collect(Collectors.joining(", "))
                + ")";
        // Primary key columns of some databases (SQLite) are nullable
        return String.format("ENGINE = %s ORDER BY %s SETTINGS allow_nullable_key = 1", DEFAULT_ENGINE, orderBy);
    }

    @Override
    public String getDropTableStatement(String catalog, String schema, String tableName) {
        return String.format("DROP TABLE IF EXISTS %s", getQualifiedTableName(catalog, schema, tableName));
    }

    @Override
    public String getCreateUserStatement(String username, String password) {
        return null;
    }

    @Override
    public String getCreateDatabaseStatement(String catalog, String username) {
        return null;
    }

    /**
     * Schemas are databases of ClickHouse
     */
    @Override
    public String getCreateSchemaStatement(String catalog, String schema, String username) {
        return getCreateSchemaIfNotExistsStatement(schema);
    }

    @Override
    public String getCreateSchemaIfNotExistsStatement(String schema) {
        return String.format("CREATE DATABASE IF NOT EXISTS %s", quoteIdentifier(schema));
    }

    @Override
    public String getDefaultSchemaName(String catalog) {
        return "default";
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
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        return String.format("ALTER TABLE %s COMMENT COLUMN %s %s", getQualifiedTableName(catalog, schema, tableName),
                getIdentifier(columnName), getStringLiteral(comment));
    }

    @Override
    public String getCreateTableCommentStatement(String catalog, String schema, String tableName, String comment) {
        return String.format("ALTER TABLE %s MODIFY COMMENT %s", getQualifiedTableName(catalog, schema, tableName),
                getStringLiteral(comment));
    }

    /**
     * Indexes of other databases are not data skipping indexes
     */
    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType) {
        return null;
    }

    @Override
    public String getCreateForeignKeyStatement(String catalog, String schema, String tableName, String fkName,
                                               String[] columnNames, String refTableName, String[] refColumnNames,
                                               String updateRule, String deleteRule) {
        return null;
    }

    /**
     * Lightweight updates of ClickHouse need settings of the table, LOB values are written in INSERT statements
     */
    @Override
    public boolean isLobSeparationSupported() {
        return false;
    }

    @Override
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return null;
    }

    /**
     * Nested types, enums, addresses, uuid and wide integers are read as text, which every database accepts
     */
    @Override
    public String getSelectColumnExpression(String columnName, String typeName) {
        if (isBytesRead(typeName)) {
            // Bytes of strings are kept between ClickHouse databases
            hexColumns.add(columnName.toLowerCase(Locale.ENGLISH));
            return String.format("hex(%1$s) AS %1$s", quoteIdentifier(columnName));
        }
        if (ClickHouseMetaDataOperations.isTextReadType(typeName)) {
            return String.format("toString(%1$s) AS %1$s", quoteIdentifier(columnName));
        }
        return super.getSelectColumnExpression(columnName, typeName);
    }

    /**
     * Strings are read as bytes when they are written to ClickHouse, the driver decodes them as UTF-8 otherwise
     */
    private boolean isBytesRead(String typeName) {
        String baseType = ClickHouseMetaDataOperations.getBaseType(typeName);
        return getReadTargetDbType() == DbType.CLICKHOUSE && ("string".equals(baseType)
                || "fixedstring".equals(baseType));
    }

    /**
     * Strings selected by hex are decoded to their bytes
     */
    @Override
    public Object getColumnValue(ResultSet rs, int columnIndex, int columnType, String columnTypeName)
            throws SQLException {
        Object value = super.getColumnValue(rs, columnIndex, columnType, columnTypeName);
        if (value instanceof String && !hexColumns.isEmpty() && hexColumns.contains(rs.getMetaData()
                .getColumnLabel(columnIndex).toLowerCase(Locale.ENGLISH)) && HEX.matcher((String) value).matches()) {
            return HexFormat.of().parseHex((String) value);
        }
        if (value instanceof String && StringUtils.containsIgnoreCase(columnTypeName, "FixedString")) {
            // Values of FixedString are padded with zero bytes, which text of other databases does not accept
            return StringUtils.stripEnd((String) value, "\u0000");
        }
        return value;
    }

    /**
     * Rows without primary key are ordered by all columns, LIMIT/OFFSET of parallel reading is not deterministic
     * otherwise
     */
    @Override
    public String getPageStatement(String sql, String orderBy, int limit, int offset) {
        return super.getPageStatement(sql, StringUtils.isNotBlank(orderBy) ? orderBy : "tuple(*)", limit, offset);
    }

    @Override
    public String getCurrentTimestampExpression() {
        return "now64()";
    }

    @Override
    public String getCurrentDateExpression() {
        return "today()";
    }

    @Override
    public String getStringLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        // Quotes are doubled as standard sql, so that scripts are parsed like scripts of other databases
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    @Override
    public String getBinaryLiteral(byte[] bytes) {
        return "unhex('" + toHexString(bytes) + "')";
    }

    @Override
    public String getTimestampLiteral(LocalDateTime value) {
        return "toDateTime64('" + formatTimestamp(value) + "', 6)";
    }

    @Override
    public String getTimestampWithTimeZoneLiteral(OffsetDateTime value) {
        return getTimestampLiteral(value.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
    }

    @Override
    public String getDateLiteral(LocalDate value) {
        return "toDate32('" + value + "')";
    }

    @Override
    public String getTimeLiteral(LocalTime value) {
        return getStringLiteral(formatTime(value));
    }

    @Override
    public int getMaxFractionalSecondsPrecision() {
        return 6;
    }

    /**
     * Temporal values are bound as text, the driver converts dates and times by the time zone of the client
     * otherwise. UUIDs and times are text too.
     */
    @Override
    public Object getJdbcValue(Object value, int sourceDataType, String sourceTypeName, int columnSize) {
        Object result = super.getJdbcValue(value, sourceDataType, sourceTypeName, columnSize);
        if (result instanceof Timestamp) {
            result = ((Timestamp) result).toLocalDateTime();
        } else if (result instanceof Date) {
            result = ((Date) result).toLocalDate();
        } else if (result instanceof Time) {
            result = ((Time) result).toLocalTime();
        }
        if (result instanceof OffsetDateTime) {
            result = ((OffsetDateTime) result).atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        }
        if (result instanceof LocalDateTime) {
            return formatTimestamp((LocalDateTime) result);
        } else if (result instanceof LocalDate) {
            return result.toString();
        } else if (result instanceof LocalTime) {
            return formatTime((LocalTime) result);
        } else if (result instanceof OffsetTime) {
            OffsetTime time = (OffsetTime) result;
            return formatTime(time.toLocalTime()) + time.getOffset();
        } else if (result instanceof UUID) {
            return result.toString();
        }
        return result;
    }
}
