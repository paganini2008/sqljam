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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: MySQLDialect for MySQL 8.0 and later versions
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class MySQLDialect extends Dialect {

    /**
     * Max bytes of an index key prefix of InnoDB
     */
    private static final int MAX_INDEX_PREFIX_LENGTH = 191;

    public MySQLDialect() {
        super();
        registerColumnType(Types.BIT, "bit(1)");
        registerColumnType(Types.BOOLEAN, "tinyint(1)");
        registerColumnType(Types.TINYINT, "tinyint");
        registerColumnType(Types.SMALLINT, "smallint");
        registerColumnType(Types.INTEGER, "int");
        registerColumnType(Types.BIGINT, "bigint");
        registerColumnType(Types.FLOAT, "float");
        registerColumnType(Types.REAL, "float");
        registerColumnType(Types.DOUBLE, "double");
        registerColumnType(Types.NUMERIC, "decimal($p,$s)");
        registerColumnType(Types.DECIMAL, "decimal($p,$s)");

        registerColumnType(Types.DATE, "date");
        registerColumnType(Types.TIME, "time");
        registerColumnType(Types.TIMESTAMP, "datetime");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "time");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "datetime(6)");

        registerColumnType(Types.BINARY, "longblob");
        registerColumnType(Types.BINARY, 255, "binary($l)");
        registerColumnType(Types.VARBINARY, "longblob");
        registerColumnType(Types.VARBINARY, 16383, "varbinary($l)");
        registerColumnType(Types.LONGVARBINARY, "longblob");
        registerColumnType(Types.BLOB, "longblob");

        registerColumnType(Types.CHAR, "longtext");
        registerColumnType(Types.CHAR, 255, "char($l)");
        registerColumnType(Types.NCHAR, "longtext");
        registerColumnType(Types.NCHAR, 255, "char($l)");
        registerColumnType(Types.VARCHAR, "longtext");
        registerColumnType(Types.VARCHAR, 4000, "varchar($l)");
        registerColumnType(Types.VARCHAR, 65535, "text");
        registerColumnType(Types.VARCHAR, 16777215, "mediumtext");
        registerColumnType(Types.NVARCHAR, "longtext");
        registerColumnType(Types.NVARCHAR, 4000, "varchar($l)");
        registerColumnType(Types.NVARCHAR, 65535, "text");
        registerColumnType(Types.NVARCHAR, 16777215, "mediumtext");
        registerColumnType(Types.LONGVARCHAR, "longtext");
        registerColumnType(Types.LONGNVARCHAR, "longtext");
        registerColumnType(Types.CLOB, "longtext");
        registerColumnType(Types.NCLOB, "longtext");
        registerColumnType(Types.SQLXML, "longtext");
        registerColumnType(Types.OTHER, "longtext");
        registerColumnType(Types.JAVA_OBJECT, "longtext");

        registerReservedWords("accessible", "analyze", "change", "condition", "database", "databases", "dual",
                "div", "enclosed", "escaped", "explain", "fulltext", "groups", "interval", "keys", "kill", "lines",
                "load", "lock", "long", "match", "mod", "option", "range", "rank", "read", "regexp", "rename",
                "replace", "require", "rlike", "schema", "schemas", "separator", "show", "signal", "spatial",
                "sql", "status", "trigger", "usage", "use", "write", "xor", "zerofill");
    }

    @Override
    public DbType getDbType() {
        return DbType.MYSQL;
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
        return 65;
    }

    @Override
    public String getUnboundedNumericTypeName() {
        return "decimal(65,30)";
    }

    @Override
    protected String getQualifier(String catalog, String schema) {
        return StringUtils.isNotBlank(catalog) ? getIdentifier(catalog) : null;
    }

    @Override
    public String getSourceTableName(String catalog, String schema, String tableName) {
        StringBuilder ref = new StringBuilder();
        if (StringUtils.isNotBlank(catalog)) {
            ref.append(quoteIdentifier(catalog)).append(".");
        }
        return ref.append(quoteIdentifier(tableName)).toString();
    }

    /**
     * Full column type of MySQL, e.g. varchar(255), int unsigned, enum('a','b'), decimal(10,2)
     */
    @Override
    protected String getNativeTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        return sourceTypeName;
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        if (!isCrossDatabase()) {
            switch (baseTypeName) {
                case "json":
                case "year":
                case "tinytext":
                case "text":
                case "mediumtext":
                case "longtext":
                case "tinyblob":
                case "blob":
                case "mediumblob":
                case "longblob":
                case "geometry":
                case "point":
                case "date":
                    return baseTypeName;
                default:
                    break;
            }
            if (sourceTypeName.endsWith("unsigned")) {
                return sourceTypeName;
            }
        }
        switch (baseTypeName) {
            case "json":
            case "jsonb":
                return getJsonTypeName();
            case "uuid":
            case "uniqueidentifier":
                return "char(36)";
            case "bool":
            case "boolean":
                return "tinyint(1)";
            case "inet":
            case "cidr":
            case "macaddr":
            case "interval":
                return "varchar(64)";
            case "xml":
            case "xmltype":
            case "tsvector":
                return "longtext";
            case "money":
            case "smallmoney":
                return "decimal(19,4)";
            case "text":
                return "longtext";
            case "bytea":
                return "longblob";
            case "float4":
            case "binary_float":
                return "float";
            case "float8":
            case "binary_double":
                return "double";
            default:
                break;
        }
        if (baseTypeName.startsWith("_")) {
            // PostgreSQL array
            return "longtext";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "bit(" + Math.min(columnSize, 64) + ")";
        }
        if (dataType == Types.TIMESTAMP || dataType == Types.TIMESTAMP_WITH_TIMEZONE) {
            int fraction = Math.min(Math.max(columnScale, 0), 6);
            return fraction > 0 ? "datetime(" + fraction + ")" : "datetime";
        }
        if ((dataType == Types.VARCHAR || dataType == Types.NVARCHAR) && columnSize <= 0) {
            return "longtext";
        }
        return null;
    }

    @Override
    public String getCreateUserStatement(String username, String password) {
        return String.format("CREATE USER IF NOT EXISTS '%s'@'%%' IDENTIFIED BY '%s'", username, password);
    }

    @Override
    public String getCreateDatabaseStatement(String catalog, String username) {
        return String.format("CREATE DATABASE IF NOT EXISTS %s DEFAULT CHARACTER SET utf8mb4", getIdentifier(catalog));
    }

    @Override
    public String[] getStatementAfterDatabaseCreated(String catalog, String username) {
        return new String[]{String.format("GRANT ALL PRIVILEGES ON %s.* TO '%s'@'%%'", getIdentifier(catalog),
                username)};
    }

    @Override
    public String getCreateSchemaStatement(String catalog, String schema, String username) {
        return null;
    }

    @Override
    public String[] getSessionStatements() {
        return new String[]{"SET FOREIGN_KEY_CHECKS=0"};
    }

    @Override
    public String getDropTableStatement(String catalog, String schema, String tableName) {
        return String.format("DROP TABLE IF EXISTS %s", getQualifiedTableName(catalog, schema, tableName));
    }

    @Override
    public String getTableOptions(String catalog, String schema, String tableName, Map<String, Object> detail) {
        return "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
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
        return String.format("ALTER TABLE %s AUTO_INCREMENT = %d", getQualifiedTableName(catalog, schema, tableName),
                startValue);
    }

    @Override
    public String getCreatePrimaryKeyStatement(String catalog, String schema, String tableName, String columnName,
                                               String pkeyName) {
        return String.format("PRIMARY KEY (%s)", getIndexColumns(tableName,
                Arrays.stream(columnName.split(",")).map(String::trim).toArray(String[]::new)));
    }

    @Override
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        // Column comments are defined inline
        return null;
    }

    @Override
    public String getCreateTableCommentStatement(String catalog, String schema, String tableName, String comment) {
        return String.format("ALTER TABLE %s COMMENT = %s", getQualifiedTableName(catalog, schema, tableName),
                getStringLiteral(comment));
    }

    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType) {
        return String.format("CREATE %sINDEX %s ON %s (%s)", unique ? "UNIQUE " : "", quoteIdentifier(indexName),
                getQualifiedTableName(catalog, schema, tableName), getIndexColumns(tableName, columnNames));
    }

    /**
     * Text/blob columns must be indexed with a prefix length
     */
    private String getIndexColumns(String tableName, String[] columnNames) {
        return Arrays.stream(columnNames).map(columnName -> {
            String typeName = getRegisteredColumnTypeName(tableName, columnName);
            if (typeName.endsWith("text") || typeName.endsWith("blob") || typeName.equals("json")) {
                return getIdentifier(columnName) + "(" + MAX_INDEX_PREFIX_LENGTH + ")";
            }
            if (typeName.startsWith("varchar(") || typeName.startsWith("varbinary(")) {
                int length = Integer.parseInt(typeName.replaceAll("\\D", ""));
                if (length > 768) {
                    return getIdentifier(columnName) + "(" + MAX_INDEX_PREFIX_LENGTH + ")";
                }
            }
            return getIdentifier(columnName);
        }).collect(Collectors.joining(","));
    }

    @Override
    public String getIncrementalColumnStatement(String catalog, String schema, String tableName, String columnName,
                                                int dataType, String typeName, int columnSize, int columnScale,
                                                String defaultValue, boolean nullable) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        if (columnTypeName.startsWith("decimal") || columnTypeName.startsWith("numeric")) {
            columnTypeName = "bigint";
        }
        registerColumnTypeName(tableName, columnName, columnTypeName);
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        columnDef.append(" NOT NULL AUTO_INCREMENT");
        return columnDef.toString().trim();
    }

    @Override
    public String getColumnStatement(String catalog, String schema, String tableName, String columnName, int dataType,
                                     String typeName, int columnSize, int columnScale, String defaultValue,
                                     boolean nullable, String comment) {
        StringBuilder columnDef = new StringBuilder(StringHelper.textLeft(getIdentifier(columnName), 30));
        String columnTypeName = getColumnTypeName(dataType, typeName, columnSize, columnScale);
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        defaultValue = getDefaultValue(dataType, typeName, defaultValue);
        if (StringUtils.isNotBlank(defaultValue)) {
            String lowerTypeName = columnTypeName.toLowerCase(Locale.ENGLISH);
            boolean expressionRequired = lowerTypeName.endsWith("text") || lowerTypeName.endsWith("blob")
                    || lowerTypeName.equals("json");
            if (expressionRequired && !defaultValue.startsWith("(")) {
                defaultValue = "(" + defaultValue + ")";
            }
            if (defaultValue.startsWith("(") && !isExpressionDefaultSupported()) {
                defaultValue = null;
            }
            if (defaultValue != null && lowerTypeName.startsWith("datetime")
                    && defaultValue.toUpperCase(Locale.ENGLISH).startsWith("CURRENT_TIMESTAMP")
                    && !isDatetimeCurrentTimestampSupported()) {
                defaultValue = null;
            }
            // Fractional seconds precision of CURRENT_TIMESTAMP must match the column
            java.util.regex.Matcher precision = java.util.regex.Pattern.compile("^(datetime|timestamp)\\((\\d)\\)$")
                    .matcher(lowerTypeName);
            if (defaultValue != null && precision.matches()
                    && defaultValue.equalsIgnoreCase(getCurrentTimestampExpression())) {
                defaultValue = getCurrentTimestampExpression() + "(" + precision.group(2) + ")";
            }
            if (defaultValue != null) {
                columnDef.append(" DEFAULT ").append(defaultValue);
            }
        }
        if (!nullable) {
            columnDef.append(" NOT NULL");
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
        return getColumnStatement(catalog, schema, tableName, columnName, dataType, typeName, columnSize, columnScale,
                defaultValue, nullable, null);
    }

    @Override
    public String getCurrentDateExpression() {
        return "(CURRENT_DATE)";
    }

    @Override
    public boolean isPartitionSupported() {
        return true;
    }

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
    public String getStringLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    @Override
    public String getBooleanLiteral(boolean value) {
        return value ? "1" : "0";
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
    public boolean isSingleIncrementalColumn() {
        return true;
    }

    @Override
    public boolean isIncrementalColumnKeyRequired() {
        return true;
    }

    @Override
    public int getMaxFractionalSecondsPrecision() {
        return 6;
    }

    /**
     * Schema of MySQL is database
     */
    @Override
    public String getCreateSchemaIfNotExistsStatement(String schema) {
        return String.format("CREATE DATABASE IF NOT EXISTS %s DEFAULT CHARACTER SET utf8mb4",
                quoteIdentifier(schema));
    }

    @Override
    public boolean isGeneratedColumnSupported() {
        return true;
    }

    protected String getJsonTypeName() {
        return "json";
    }

    /**
     * Expression default values, e.g. DEFAULT ('abc') of text columns
     */
    protected boolean isExpressionDefaultSupported() {
        return true;
    }

    /**
     * DEFAULT CURRENT_TIMESTAMP of datetime columns
     */
    protected boolean isDatetimeCurrentTimestampSupported() {
        return true;
    }
}
