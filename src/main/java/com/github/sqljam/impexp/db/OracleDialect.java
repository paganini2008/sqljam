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
import java.sql.Types;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.StringHelper;

/**
 * @Description: OracleDialect for Oracle 12.2 and later versions
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class OracleDialect extends Dialect {

    /**
     * Max characters of a string literal in one TO_CLOB call, it's safe for multi-byte characters
     */
    private static final int MAX_LITERAL_LENGTH = 1000;

    public OracleDialect() {
        super();
        registerColumnType(Types.BIT, "NUMBER(1)");
        registerColumnType(Types.BOOLEAN, "NUMBER(1)");
        registerColumnType(Types.TINYINT, "NUMBER(3)");
        registerColumnType(Types.SMALLINT, "NUMBER(5)");
        registerColumnType(Types.INTEGER, "NUMBER(10)");
        registerColumnType(Types.BIGINT, "NUMBER(19)");
        registerColumnType(Types.FLOAT, "BINARY_FLOAT");
        registerColumnType(Types.REAL, "BINARY_FLOAT");
        registerColumnType(Types.DOUBLE, "BINARY_DOUBLE");
        registerColumnType(Types.NUMERIC, "NUMBER($p,$s)");
        registerColumnType(Types.DECIMAL, "NUMBER($p,$s)");

        registerColumnType(Types.DATE, "DATE");
        registerColumnType(Types.TIME, "VARCHAR2(20)");
        registerColumnType(Types.TIME_WITH_TIMEZONE, "VARCHAR2(32)");
        registerColumnType(Types.TIMESTAMP, "TIMESTAMP");
        registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE");

        registerColumnType(Types.BINARY, "BLOB");
        registerColumnType(Types.BINARY, 2000, "RAW($l)");
        registerColumnType(Types.VARBINARY, "BLOB");
        registerColumnType(Types.VARBINARY, 2000, "RAW($l)");
        registerColumnType(Types.LONGVARBINARY, "BLOB");
        registerColumnType(Types.BLOB, "BLOB");

        registerColumnType(Types.CHAR, "CLOB");
        registerColumnType(Types.CHAR, 2000, "CHAR($l CHAR)");
        registerColumnType(Types.NCHAR, "NCLOB");
        registerColumnType(Types.NCHAR, 1000, "NCHAR($l)");
        registerColumnType(Types.VARCHAR, "CLOB");
        registerColumnType(Types.VARCHAR, 4000, "VARCHAR2($l CHAR)");
        registerColumnType(Types.NVARCHAR, "NCLOB");
        registerColumnType(Types.NVARCHAR, 2000, "NVARCHAR2($l)");
        registerColumnType(Types.LONGVARCHAR, "CLOB");
        registerColumnType(Types.LONGNVARCHAR, "NCLOB");
        registerColumnType(Types.CLOB, "CLOB");
        registerColumnType(Types.NCLOB, "NCLOB");
        registerColumnType(Types.SQLXML, "CLOB");
        registerColumnType(Types.OTHER, "CLOB");
        registerColumnType(Types.JAVA_OBJECT, "CLOB");

        registerReservedWords("access", "audit", "cluster", "compress", "connect", "exclusive", "file", "identified",
                "immediate", "increment", "initial", "integer", "lock", "long", "maxextents", "minus", "mode",
                "modify", "noaudit", "nocompress", "nowait", "offline", "online", "pctfree", "prior", "privileges",
                "public", "raw", "rename", "resource", "rowid", "rownum", "share", "start", "successful", "synonym",
                "sysdate", "trigger", "uid", "validate", "varchar", "varchar2", "whenever");
    }

    @Override
    public DbType getDbType() {
        return DbType.ORACLE;
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
    public String getUnboundedNumericTypeName() {
        return "NUMBER";
    }

    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        if (!isCrossDatabase()) {
            if (sourceTypeName.indexOf('(') > 0) {
                // TIMESTAMP(6), TIMESTAMP(6) WITH TIME ZONE, INTERVAL DAY(2) TO SECOND(6)
                return sourceTypeName.toUpperCase();
            }
            switch (sourceTypeName) {
                case "date":
                case "clob":
                case "nclob":
                case "blob":
                case "binary_float":
                case "binary_double":
                case "vector":
                case "xmltype":
                case "json":
                case "long":
                case "rowid":
                case "boolean":
                    return sourceTypeName.toUpperCase();
                case "varchar2":
                    return "VARCHAR2(" + columnSize + ")";
                case "nvarchar2":
                    return "NVARCHAR2(" + columnSize + ")";
                case "char":
                    return "CHAR(" + columnSize + ")";
                case "nchar":
                    return "NCHAR(" + columnSize + ")";
                case "raw":
                    return "RAW(" + columnSize + ")";
                case "float":
                    return "FLOAT(" + columnSize + ")";
                case "number":
                    if (columnSize <= 0) {
                        return "NUMBER";
                    }
                    return columnScale > 0 ? "NUMBER(" + columnSize + "," + columnScale + ")"
                            : "NUMBER(" + columnSize + ")";
                default:
                    return null;
            }
        }
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").replace(" unsigned", "").trim();
        switch (baseTypeName) {
            case "json":
            case "jsonb":
            case "xml":
            case "text":
            case "tinytext":
            case "mediumtext":
            case "longtext":
            case "tsvector":
                return "CLOB";
            case "bytea":
            case "tinyblob":
            case "blob":
            case "mediumblob":
            case "longblob":
                return "BLOB";
            case "uuid":
            case "uniqueidentifier":
                return "VARCHAR2(36)";
            case "bool":
            case "boolean":
                return "NUMBER(1)";
            case "year":
                return "NUMBER(4)";
            case "enum":
            case "set":
                return "VARCHAR2(255 CHAR)";
            case "inet":
            case "cidr":
            case "macaddr":
            case "interval":
                return "VARCHAR2(64)";
            case "money":
            case "smallmoney":
                return "NUMBER(19,4)";
            case "datetimeoffset":
                return "TIMESTAMP WITH TIME ZONE";
            default:
                break;
        }
        if (baseTypeName.startsWith("_")) {
            return "CLOB";
        }
        if (dataType == Types.BIT && columnSize > 1) {
            return "NUMBER(20)";
        }
        if (dataType == Types.TIMESTAMP) {
            int fraction = Math.min(Math.max(columnScale, 0), 9);
            return fraction > 0 ? "TIMESTAMP(" + fraction + ")" : "TIMESTAMP";
        }
        if ((dataType == Types.VARCHAR || dataType == Types.NVARCHAR) && columnSize <= 0) {
            return "CLOB";
        }
        return null;
    }

    @Override
    public String getCreateUserStatement(String username, String password) {
        return String.format("CREATE USER %s IDENTIFIED BY \"%s\"", getIdentifier(username), password);
    }

    @Override
    public String[] getStatementAfterUserCreated(String username) {
        return new String[]{String.format("GRANT CONNECT, RESOURCE, UNLIMITED TABLESPACE TO %s",
                getIdentifier(username))};
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
        String table = getQualifiedTableName(catalog, schema, tableName).replace("'", "''");
        return String.format("BEGIN EXECUTE IMMEDIATE 'DROP TABLE %s CASCADE CONSTRAINTS PURGE'; "
                + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;", table);
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
        return String.format("ALTER TABLE %s MODIFY %s GENERATED BY DEFAULT ON NULL AS IDENTITY (START WITH %d)",
                getQualifiedTableName(catalog, schema, tableName), getIdentifier(columnName), startValue);
    }

    @Override
    public String getCreateCommentStatement(String catalog, String schema, String tableName, String columnName,
                                            String comment) {
        return String.format("COMMENT ON COLUMN %s.%s IS %s", getQualifiedTableName(catalog, schema, tableName),
                getIdentifier(columnName), getStringLiteral(comment));
    }

    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType) {
        // LOB columns can not be indexed
        boolean lobIncluded = Arrays.stream(columnNames).map(name -> getRegisteredColumnTypeName(tableName, name))
                .anyMatch(typeName -> typeName.endsWith("lob") || typeName.equals("long"));
        if (lobIncluded) {
            return null;
        }
        return String.format("CREATE %sINDEX %s ON %s (%s)", unique ? "UNIQUE " : "", quoteIdentifier(indexName),
                getQualifiedTableName(catalog, schema, tableName), getIdentifiers(columnNames));
    }

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
        if (!columnTypeName.startsWith("NUMBER")) {
            columnTypeName = "NUMBER(19)";
        }
        columnDef.append(StringHelper.textLeft(columnTypeName, 30));
        columnDef.append(getIdentityClause());
        return columnDef.toString().trim();
    }

    /**
     * Identity clause of an identity column
     */
    protected String getIdentityClause() {
        return " GENERATED BY DEFAULT ON NULL AS IDENTITY NOT NULL";
    }

    @Override
    public String getCurrentTimestampExpression() {
        return "SYSTIMESTAMP";
    }

    @Override
    public String getCurrentDateExpression() {
        return "SYSDATE";
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

    @Override
    public String getStringLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        if (value.length() <= MAX_LITERAL_LENGTH) {
            return super.getStringLiteral(value);
        }
        // String literal is limited to 4000 bytes
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < value.length(); i += MAX_LITERAL_LENGTH) {
            if (literal.length() > 0) {
                literal.append(" || ");
            }
            String chunk = value.substring(i, Math.min(value.length(), i + MAX_LITERAL_LENGTH));
            literal.append("TO_CLOB(").append(super.getStringLiteral(chunk)).append(")");
        }
        return literal.toString();
    }

    @Override
    public String getBinaryLiteral(byte[] bytes) {
        return "HEXTORAW('" + toHexString(bytes) + "')";
    }

    @Override
    public String getTimeLiteral(LocalTime value) {
        return "'" + formatTime(value) + "'";
    }

    @Override
    public Object getJdbcValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
        } else if (value instanceof LocalTime) {
            // Oracle has no TIME type, time values are stored as text
            return formatTime((LocalTime) value);
        } else if ((value instanceof Double || value instanceof Float)
                && Double.isFinite(((Number) value).doubleValue())) {
            // The driver binds doubles as NUMBER of 15 digits, the shortest decimal keeps the exact value
            return new BigDecimal(value.toString());
        }
        return value;
    }

    @Override
    public boolean isEmptyValueNull() {
        return true;
    }

    @Override
    public boolean isSequenceSupported() {
        return true;
    }

    @Override
    public String[] getStatementBeforeSequenceCreated(String catalog, String schema, String sequenceName) {
        String name = getQualifiedSequenceName(catalog, schema, sequenceName).replace("'", "''");
        return new String[]{String.format("BEGIN EXECUTE IMMEDIATE 'DROP SEQUENCE %s'; "
                + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2289 THEN RAISE; END IF; END;", name)};
    }

    @Override
    public String getCreateSequenceStatement(String catalog, String schema, String sequenceName, long startValue,
                                             long increment, Long minValue, Long maxValue, boolean cycle,
                                             Long cacheSize, String dataType) {
        String sql = super.getCreateSequenceStatement(catalog, schema, sequenceName, startValue, increment, minValue,
                maxValue, cycle, cacheSize, dataType);
        return cacheSize != null && cacheSize <= 1 ? sql + " NOCACHE" : sql;
    }

    @Override
    public String getGeneratedColumnStatement(String catalog, String schema, String tableName, String columnName,
                                              int dataType, String typeName, int columnSize, int columnScale,
                                              String expression, boolean stored) {
        return String.format("%s %s GENERATED ALWAYS AS (%s) VIRTUAL", getIdentifier(columnName),
                getColumnTypeName(dataType, typeName, columnSize, columnScale), expression);
    }

    @Override
    public String getEmptyLobLiteral(boolean binary) {
        return binary ? "EMPTY_BLOB()" : "EMPTY_CLOB()";
    }

    @Override
    public String getTimestampWithTimeZoneLiteral(OffsetDateTime value) {
        return "TIMESTAMP '" + formatTimestamp(value.toLocalDateTime()) + " " + value.getOffset() + "'";
    }

    @Override
    public boolean isSingleIncrementalColumn() {
        return true;
    }

    /**
     * XMLTYPE is read as CLOB, so that no XML libraries of Oracle are required by the driver
     */
    @Override
    public String getSelectColumnExpression(String columnName, String typeName) {
        String column = quoteIdentifier(columnName);
        if (StringUtils.endsWithIgnoreCase(typeName, "XMLTYPE")) {
            return String.format("XMLSERIALIZE(CONTENT %s AS CLOB) AS %s", column, column);
        }
        if ("VECTOR".equalsIgnoreCase(typeName)) {
            // Text of vectors is accepted by every database, Oracle reads it back into vectors
            return String.format("FROM_VECTOR(%s RETURNING CLOB) AS %s", column, column);
        }
        return column;
    }
}
