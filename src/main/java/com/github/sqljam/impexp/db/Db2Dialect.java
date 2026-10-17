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
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: Db2Dialect for IBM Db2 for Linux, UNIX and Windows 11.1 and later versions. Strings of other
 *               databases are sized by their characters, LOBs are created with their largest size, a table
 *               has one identity column, upserts are MERGE statements. Db2 has no time zones in TIMESTAMP, values
 *               with time zones are kept as text.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
public class Db2Dialect extends Dialect {

    /**
     * Max characters of a string literal in one CLOB call, a string constant has at most 32672 bytes
     */
    private static final int MAX_LITERAL_LENGTH = 8000;
    /**
     * Bytes of a character of UTF-8 in VARCHAR of other databases: keys of 255 characters stay below the 1024 bytes
     * of an index key of 4K pages, which VARCHAR(n CODEUNITS32) of 4 bytes each does not
     */
    private static final int BYTES_PER_CHAR = 3;
    private static final int MAX_VARCHAR_BYTES = 32672;
    /**
     * Max characters of CHAR(n CODEUNITS32)
     */
    private static final int MAX_CHAR_LENGTH = 63;
    private static final int MAX_NUMERIC_PRECISION = 31;

    public Db2Dialect() {
        super();
        registerColumnType(Types.BIT, "BOOLEAN");
        registerColumnType(Types.BOOLEAN, "BOOLEAN");
        registerColumnType(Types.TINYINT, "SMALLINT");
        registerColumnType(Types.SMALLINT, "SMALLINT");
        registerColumnType(Types.INTEGER, "INTEGER");
        registerColumnType(Types.BIGINT, "BIGINT");
        registerColumnType(Types.FLOAT, "DOUBLE");
        registerColumnType(Types.REAL, "REAL");
        registerColumnType(Types.DOUBLE, "DOUBLE");
        registerColumnType(Types.NUMERIC, "DECIMAL($p,$s)");
        registerColumnType(Types.DECIMAL, "DECIMAL($p,$s)");

        registerColumnType(Types.DATE, "DATE");
        registerColumnType(Types.TIME, "TIME");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "VARCHAR(32)");
        registerColumnType(Types.TIMESTAMP, "TIMESTAMP");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "VARCHAR(40)");

        registerColumnType(Types.BINARY, "BLOB(2G)");
        registerColumnType(Types.BINARY, 255, "BINARY($l)");
        registerColumnType(Types.VARBINARY, "BLOB(2G)");
        registerColumnType(Types.VARBINARY, 32672, "VARBINARY($l)");
        registerColumnType(Types.LONGVARBINARY, "BLOB(2G)");
        registerColumnType(Types.BLOB, "BLOB(2G)");

        registerColumnType(Types.CHAR, "CLOB(2G)");
        registerColumnType(Types.CHAR, MAX_CHAR_LENGTH, "CHAR($l CODEUNITS32)");
        registerColumnType(Types.NCHAR, "CLOB(2G)");
        registerColumnType(Types.NCHAR, MAX_CHAR_LENGTH, "CHAR($l CODEUNITS32)");
        registerColumnType(Types.VARCHAR, "CLOB(2G)");
        registerColumnType(Types.NVARCHAR, "CLOB(2G)");
        registerColumnType(Types.LONGVARCHAR, "CLOB(2G)");
        registerColumnType(Types.LONGNVARCHAR, "CLOB(2G)");
        registerColumnType(Types.CLOB, "CLOB(2G)");
        registerColumnType(Types.NCLOB, "CLOB(2G)");
        registerColumnType(Types.SQLXML, "XML");
        registerColumnType(Types.OTHER, "CLOB(2G)");
        registerColumnType(Types.JAVA_OBJECT, "CLOB(2G)");

        registerReservedWords("activate", "alias", "allocate", "allow", "audit", "aux", "auxiliary", "binary",
                "bufferpool", "capture", "ccsid", "collection", "collid", "concat", "contains", "count_big",
                "current_lc_ctype", "data", "database", "days", "db2general", "db2genrl", "db2sql", "dbinfo",
                "defaults", "definition", "dssize", "editproc", "encoding", "encryption", "ending", "erase",
                "excluding", "exclusive", "fenced", "fieldproc", "file", "final", "generated", "graphic", "hours",
                "identity", "include", "including", "inf", "infinity", "integrity", "isobid", "jar", "java", "label",
                "lc_ctype", "linktype", "locale", "locators", "lock", "lockmax", "locksize", "long", "maxvalue",
                "microsecond", "microseconds", "minutes", "minvalue", "mode", "months", "nan", "new_table",
                "nocache", "nocycle", "nodename", "nodenumber", "nomaxvalue", "nominvalue", "noorder", "normalized",
                "nulls", "numparts", "obid", "old_table", "optimization", "optimize", "packed", "padded", "page",
                "pages", "part", "partition", "partitioned", "partitioning", "password", "path", "piecesize",
                "plan", "prevval", "priqty", "program", "psid", "query", "queryno", "rank", "rename", "reset",
                "restart", "resource", "routine", "row_number", "rrn", "run", "scratchpad", "seconds", "secqty",
                "security", "simple", "snan", "ssid", "stacked", "standard", "stay", "stogroup", "stores", "style",
                "summary", "synonym", "sysfun", "sysibm", "sysproc", "system", "tablespace", "type", "validproc",
                "variable", "variant", "vcat", "version", "volatile", "volumes", "wlm", "years");
    }

    @Override
    public DbType getDbType() {
        return DbType.DB2;
    }

    @Override
    public IdentifierCase getStoredIdentifierCase() {
        return IdentifierCase.UPPER;
    }

    @Override
    public int getMaxIdentifierLength() {
        return 128;
    }

    @Override
    public int getMaxNumericPrecision() {
        return MAX_NUMERIC_PRECISION;
    }

    /**
     * Numbers wider than DECIMAL(31) keep their digits in DECFLOAT(34)
     */
    @Override
    public String getUnboundedNumericTypeName() {
        return "DECFLOAT(34)";
    }

    /**
     * Values are read up to nanoseconds, TIMESTAMP(12) columns of Db2 keep their precision as types
     */
    @Override
    public int getMaxFractionalSecondsPrecision() {
        return 9;
    }

    /**
     * Columns of Db2 keep their types, e.g. GRAPHIC, DECFLOAT and FOR BIT DATA. Types of other databases without
     * a jdbc type of the same meaning are mapped by name.
     */
    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        if (!isCrossDatabase()) {
            return getNativeDb2TypeName(sourceTypeName, columnSize, columnScale);
        }
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        switch (baseTypeName) {
            case "json":
            case "jsonb":
            case "text":
            case "tinytext":
            case "mediumtext":
            case "longtext":
            case "ntext":
            case "clob":
            case "nclob":
            case "long":
            case "tsvector":
            case "vector":
            case "sql_variant":
                return "CLOB(2G)";
            case "xml":
            case "xmltype":
                return "XML";
            case "bytea":
            case "tinyblob":
            case "blob":
            case "mediumblob":
            case "longblob":
            case "image":
                return "BLOB(2G)";
            case "uuid":
            case "uniqueidentifier":
                return "CHAR(36)";
            case "bool":
            case "boolean":
                return "BOOLEAN";
            case "year":
                return "SMALLINT";
            case "enum":
            case "set":
                return "VARCHAR(" + 255 * BYTES_PER_CHAR + ")";
            case "inet":
            case "cidr":
            case "macaddr":
            case "interval":
            case "rowid":
            case "urowid":
                return "VARCHAR(64)";
            case "money":
            case "smallmoney":
                return "DECIMAL(19,4)";
            case "datetimeoffset":
                return "VARCHAR(40)";
            default:
                break;
        }
        if (baseTypeName.startsWith("_") || baseTypeName.startsWith("interval")) {
            return baseTypeName.startsWith("_") ? "CLOB(2G)" : "VARCHAR(64)";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "BIGINT";
        }
        if (dataType == Types.TIMESTAMP) {
            int fraction = Math.min(Math.max(columnScale, 0), 12);
            return fraction == 6 ? "TIMESTAMP" : "TIMESTAMP(" + fraction + ")";
        }
        if (dataType == Types.VARCHAR || dataType == Types.NVARCHAR || dataType == Types.CHAR
                || dataType == Types.NCHAR) {
            return getStringTypeName(dataType, columnSize);
        }
        return null;
    }

    /**
     * Strings of other databases sized by characters: CHAR(n CODEUNITS32) of fixed length, VARCHAR of 3 bytes a
     * character, CLOB when they do not fit
     */
    static String getStringTypeName(int dataType, int columnSize) {
        if (columnSize <= 0 || (long) columnSize * BYTES_PER_CHAR > MAX_VARCHAR_BYTES) {
            return "CLOB(2G)";
        }
        if ((dataType == Types.CHAR || dataType == Types.NCHAR) && columnSize <= MAX_CHAR_LENGTH) {
            return "CHAR(" + columnSize + " CODEUNITS32)";
        }
        return "VARCHAR(" + columnSize * BYTES_PER_CHAR + ")";
    }

    /**
     * Type of a column of Db2 as it is created again, sizes of GRAPHIC types are characters
     */
    private static String getNativeDb2TypeName(String sourceTypeName, int columnSize, int columnScale) {
        String typeName = sourceTypeName.toUpperCase(Locale.ENGLISH).trim();
        if (typeName.startsWith("DECFLOAT(")) {
            // DECFLOAT(16) or DECFLOAT(34) of Db2MetaDataOperations
            return typeName;
        }
        if (typeName.contains("FOR BIT DATA")) {
            // CHAR () FOR BIT DATA and VARCHAR () FOR BIT DATA of the driver
            return (typeName.startsWith("CHAR") ? "CHAR(" : "VARCHAR(") + columnSize + ") FOR BIT DATA";
        }
        switch (typeName) {
            case "CHAR":
            case "CHARACTER":
            case "VARCHAR":
            case "GRAPHIC":
            case "VARGRAPHIC":
            case "BINARY":
            case "VARBINARY":
                return ("CHARACTER".equals(typeName) ? "CHAR" : typeName) + "(" + columnSize + ")";
            case "CLOB":
            case "BLOB":
            case "DBCLOB":
                return typeName + "(" + columnSize + ")";
            case "DECFLOAT":
                return "DECFLOAT(" + (columnSize == 16 ? 16 : 34) + ")";
            case "TIMESTAMP":
                return "TIMESTAMP(" + Math.min(Math.max(columnScale, 0), 12) + ")";
            case "DECIMAL":
            case "NUMERIC":
                return "DECIMAL(" + columnSize + "," + Math.max(columnScale, 0) + ")";
            case "XML":
            case "BOOLEAN":
            case "DATE":
            case "TIME":
            case "REAL":
            case "DOUBLE":
            case "SMALLINT":
            case "INTEGER":
            case "BIGINT":
                return typeName;
            default:
                return null;
        }
    }

    /**
     * Db2 users are users of the operating system or of the directory
     */
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
        return String.format("CREATE SCHEMA %s", getIdentifier(schema));
    }

    @Override
    public String getCreateSchemaIfNotExistsStatement(String schema) {
        String name = foldIdentifier(schema);
        return String.format("BEGIN IF NOT EXISTS (SELECT 1 FROM SYSCAT.SCHEMATA WHERE SCHEMANAME = '%s') THEN "
                + "EXECUTE IMMEDIATE 'CREATE SCHEMA %s'; END IF; END", name.replace("'", "''"),
                quoteIdentifier(name).replace("'", "''"));
    }

    /**
     * Foreign keys of other tables are dropped with the table
     */
    @Override
    public String getDropTableStatement(String catalog, String schema, String tableName) {
        return String.format("DROP TABLE IF EXISTS %s", getQualifiedTableName(catalog, schema, tableName));
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

    /**
     * Indexes are objects of the schema of their table, LOB and XML columns can not be indexed by normal indexes
     */
    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType) {
        boolean lobIncluded = Arrays.stream(columnNames).map(name -> getRegisteredColumnTypeName(tableName, name))
                .anyMatch(typeName -> typeName.startsWith("blob") || typeName.startsWith("clob")
                        || typeName.startsWith("dbclob") || typeName.equals("xml"));
        if (lobIncluded) {
            return null;
        }
        String qualifier = getQualifier(null, getTargetSchemaName(schema));
        String index = StringUtils.isNotBlank(qualifier) ? qualifier + "." + quoteIdentifier(indexName)
                : quoteIdentifier(indexName);
        return String.format("CREATE %sINDEX %s ON %s (%s)", unique ? "UNIQUE " : "", index,
                getQualifiedTableName(catalog, schema, tableName), getIdentifiers(columnNames));
    }

    /**
     * ON DELETE SET DEFAULT and update rules other than NO ACTION and RESTRICT do not exist
     */
    @Override
    public String getCreateForeignKeyStatement(String catalog, String schema, String tableName, String fkName,
                                               String[] columnNames, String refTableName, String[] refColumnNames,
                                               String updateRule, String deleteRule) {
        if ("SET DEFAULT".equals(deleteRule)) {
            deleteRule = null;
        }
        return super.getCreateForeignKeyStatement(catalog, schema, tableName, fkName, columnNames, refTableName,
                refColumnNames, updateRule, deleteRule);
    }

    @Override
    protected boolean isOnUpdateRuleSupported() {
        return false;
    }

    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        // An identity is an integer or a DECIMAL without scale
        if (!columnTypeName.equals("SMALLINT") && !columnTypeName.equals("INTEGER")
                && !columnTypeName.equals("BIGINT") && !columnTypeName.matches("DECIMAL\\(\\d+,0\\)")) {
            columnTypeName = "BIGINT";
        }
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        columnDef.append(" NOT NULL GENERATED BY DEFAULT AS IDENTITY");
        return columnDef.toString().trim();
    }

    @Override
    public boolean isSingleIncrementalColumn() {
        return true;
    }

    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return String.format("%s %s GENERATED ALWAYS AS (%s)", getIdentifier(columnName),
                getColumnTypeName(dataType, typeName, columnSize, columnScale), expression);
    }

    @Override
    public boolean isSequenceSupported() {
        return true;
    }

    /**
     * A sequence which does not exist (42704) is not dropped, Db2 has no DROP SEQUENCE IF EXISTS
     */
    @Override
    public String[] getStatementBeforeSequenceCreated(String catalog, String schema, String sequenceName) {
        String name = getQualifiedSequenceName(catalog, schema, sequenceName).replace("'", "''");
        return new String[]{String.format("BEGIN DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END; "
                + "EXECUTE IMMEDIATE 'DROP SEQUENCE %s'; END", name)};
    }

    /**
     * Sequences are BIGINT, a sequence of Db2 is INTEGER by default
     */
    @Override
    public String getCreateSequenceStatement(String catalog, String schema, String sequenceName, long startValue,
                                             long increment, Long minValue, Long maxValue, boolean cycle,
                                             Long cacheSize, String dataType) {
        String sql = super.getCreateSequenceStatement(catalog, schema, sequenceName, startValue, increment, minValue,
                maxValue, cycle, cacheSize, dataType);
        String name = getQualifiedSequenceName(catalog, schema, sequenceName);
        sql = sql.replaceFirst(java.util.regex.Pattern.quote("CREATE SEQUENCE " + name),
                java.util.regex.Matcher.quoteReplacement("CREATE SEQUENCE " + name + " AS BIGINT"));
        return cacheSize != null && cacheSize <= 1 ? sql + " NO CACHE" : sql;
    }

    @Override
    public boolean isPartitionSupported() {
        return true;
    }

    /**
     * Range partitions of Db2 are created again from the catalog, see Db2MetaDataOperations
     */
    @Override
    public String getDefinePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String partitionType, String columnNames,
                                                   Map<String, Object> detail) {
        if (isCrossDatabase()) {
            return null;
        }
        return (String) detail.get("PARTITION_CLAUSE");
    }

    @Override
    public String getCreatePartitionTableStatement(String catalog, String schema, String tableName,
                                                   String inheritedTableName) {
        return null;
    }

    @Override
    public String getPageStatement(String sql, String orderBy, int limit, int offset) {
        StringBuilder page = new StringBuilder(sql);
        if (StringUtils.isNotBlank(orderBy)) {
            page.append(" ORDER BY ").append(orderBy);
        }
        return page.append(String.format(" OFFSET %d ROWS FETCH NEXT %d ROWS ONLY", offset, limit)).toString();
    }

    /**
     * MERGE with the row of a VALUES table, the keys join the existing row
     */
    @Override
    public String getUpsertStatement(String catalog, String schema, String tableName, String[] columnNames,
                                     String[] keyColumnNames, String[] updateColumnNames, Object[] values) {
        StringBuilder sql = new StringBuilder("MERGE INTO ").append(getQualifiedTableName(catalog, schema, tableName))
                .append(" AS tgt USING (VALUES (").append(StringUtils.join(values, ",")).append(")) AS src (")
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

    @Override
    public String getCurrentTimestampExpression() {
        return "CURRENT TIMESTAMP";
    }

    @Override
    public String getCurrentDateExpression() {
        return "CURRENT DATE";
    }

    @Override
    public String getBooleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    /**
     * Literals of more than 8000 characters are CLOB values joined together
     */
    @Override
    public String getStringLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        if (value.length() <= MAX_LITERAL_LENGTH) {
            return super.getStringLiteral(value);
        }
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < value.length(); i += MAX_LITERAL_LENGTH) {
            if (literal.length() > 0) {
                literal.append(" || ");
            }
            String chunk = value.substring(i, Math.min(value.length(), i + MAX_LITERAL_LENGTH));
            literal.append("CLOB(").append(super.getStringLiteral(chunk)).append(")");
        }
        return literal.toString();
    }

    /**
     * Binary strings are BX literals, X literals are character strings FOR BIT DATA
     */
    @Override
    public String getBinaryLiteral(byte[] bytes) {
        return "BX'" + toHexString(bytes) + "'";
    }

    @Override
    public String getEmptyLobLiteral(boolean binary) {
        return binary ? "BX''" : "''";
    }

    /**
     * TIME of Db2 has no fractions of seconds, a literal with them is refused (-180)
     */
    @Override
    public String getTimeLiteral(LocalTime value) {
        return "TIME '" + value.withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME) + "'";
    }

    /**
     * Values with time zones are text, Db2 has no time zones in TIMESTAMP
     */
    @Override
    public String getTimestampWithTimeZoneLiteral(OffsetDateTime value) {
        return getStringLiteral(value.toString());
    }

    @Override
    public String getStringValue(String catalog, String schema, String tableName, String columnName,
                                 Object columnValue) {
        if (columnValue instanceof BigDecimal && ((BigDecimal) columnValue).precision() > MAX_NUMERIC_PRECISION) {
            // A decimal constant has at most 31 digits
            return "DECFLOAT('" + ((BigDecimal) columnValue).toPlainString() + "')";
        }
        String targetType = getRegisteredColumnTypeName(tableName, columnName);
        if (columnValue instanceof byte[] && targetType.contains("for bit data")) {
            return "X'" + toHexString((byte[]) columnValue) + "'";
        }
        if ((columnValue instanceof Number || columnValue instanceof Boolean)
                && getTargetJdbcValue(columnValue, targetType) instanceof String) {
            // Numbers into text columns are text literals, e.g. sql_variant of SQL Server into CLOB
            return getStringLiteral((String) getTargetJdbcValue(columnValue, targetType));
        }
        return super.getStringValue(catalog, schema, tableName, columnName, columnValue);
    }

    @Override
    public Object getJdbcValue(Object value) {
        if (value instanceof OffsetDateTime || value instanceof OffsetTime) {
            // Kept as text, see TIMESTAMP_WITH_TIMEZONE
            return value.toString();
        }
        return value;
    }

    /**
     * Values of other types are bound as text to text columns, e.g. numbers of sql_variant of SQL Server into CLOB,
     * the driver does not convert them
     */
    @Override
    public boolean isTargetTypeConversionRequired() {
        return true;
    }

    @Override
    public Object getTargetJdbcValue(Object value, String targetTypeName) {
        String type = StringUtils.defaultString(targetTypeName).toUpperCase(Locale.ENGLISH);
        boolean text = type.startsWith("CLOB") || type.startsWith("DBCLOB") || type.startsWith("VARCHAR")
                || type.startsWith("CHAR") || type.startsWith("GRAPHIC") || type.startsWith("VARGRAPHIC")
                || type.startsWith("LONG VARCHAR");
        if (text && !type.contains("FOR BIT DATA") && (value instanceof Number || value instanceof Boolean)) {
            return value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString();
        }
        return value;
    }

    /**
     * Object exists (-601, 42710), primary key exists (-624, 42889)
     */
    @Override
    public boolean isAlreadyExistsError(SQLException e) {
        int code = e.getErrorCode();
        return code == -601 || code == -624 || "42889".equals(e.getSQLState()) || super.isAlreadyExistsError(e);
    }
}
