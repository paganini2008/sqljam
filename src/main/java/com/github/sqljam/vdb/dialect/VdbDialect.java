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
package com.github.sqljam.vdb.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: VdbDialect writes the tables of vector database collections into a target database which has
 *               vectors: types of the columns, vector types and indexes, CREATE TABLE, INSERT and upsert. Its rules
 *               are copies of the dialects of impexp for the vdb line, which do not depend on each other. The
 *               features of the target (vectors, half vectors, sparse vectors, HNSW and IVF indexes) are detected on
 *               its connection: a column whose vectors the target has not is text, an index the target can not
 *               create is left out.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public abstract class VdbDialect {

    /**
     * Column of a table: its name in the target, its field and its type
     */
    public static final class Column {

        private final String name;
        private final VdbField field;
        private final String typeName;

        public Column(String name, VdbField field, String typeName) {
            this.name = name;
            this.field = field;
            this.typeName = typeName;
        }

        public String getName() {
            return name;
        }

        public VdbField getField() {
            return field;
        }

        public String getTypeName() {
            return typeName;
        }

        public boolean isNullable() {
            return field.isNullable() && !field.isPrimaryKey();
        }
    }

    /**
     * Text of a time with its offset, which targets cast to their times
     */
    protected static final DateTimeFormatter OFFSET_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss.SSSSSSxxx");

    private final Set<VdbFeature> features = EnumSet.noneOf(VdbFeature.class);
    private int majorVersion;
    private int minorVersion;
    private String productVersion;

    /**
     * Name of the target database, e.g. PostgreSQL
     */
    public abstract String getName();

    /**
     * Dialect of the database of a connection, null if the vdb line does not write into it
     */
    public static VdbDialect of(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String product = StringUtils.defaultString(metaData.getDatabaseProductName()).toLowerCase(Locale.ENGLISH);
        String version = StringUtils.defaultString(metaData.getDatabaseProductVersion()).toLowerCase(Locale.ENGLISH);
        VdbDialect dialect;
        if (product.contains("postgresql")) {
            dialect = new PostgreSQLVdbDialect();
        } else if (product.contains("mariadb") || version.contains("mariadb")) {
            dialect = new MariaDBVdbDialect();
        } else if (product.contains("mysql")) {
            dialect = new MySQLVdbDialect();
        } else if (product.contains("oracle")) {
            dialect = new OracleVdbDialect();
        } else if (product.contains("microsoft sql server")) {
            dialect = new SQLServerVdbDialect();
        } else if (product.startsWith("db2")) {
            dialect = new Db2VdbDialect();
        } else if (product.contains("duckdb")) {
            dialect = new DuckDBVdbDialect();
        } else if (product.contains("clickhouse")) {
            dialect = new ClickHouseVdbDialect();
        } else {
            return null;
        }
        dialect.initialize(connection);
        return dialect;
    }

    /**
     * Reads the version, detects the features and prepares the session of a connection
     */
    public void initialize(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        majorVersion = metaData.getDatabaseMajorVersion();
        minorVersion = metaData.getDatabaseMinorVersion();
        productVersion = metaData.getDatabaseProductVersion();
        features.clear();
        detectFeatures(connection, features);
        prepareSession(connection);
    }

    /**
     * Detects the features of the target, each by a probe which fails without it
     */
    protected abstract void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException;

    /**
     * Prepares a connection before tables are created and rows are written, e.g. the search path of pgvector
     */
    public void prepareSession(Connection connection) {
    }

    /**
     * Features of a target which is not connected, e.g. to show the statements of a version
     */
    public void useFeatures(Set<VdbFeature> targetFeatures) {
        features.clear();
        features.addAll(targetFeatures);
    }

    public Set<VdbFeature> getFeatures() {
        return Collections.unmodifiableSet(features);
    }

    public boolean hasFeature(VdbFeature feature) {
        return features.contains(feature);
    }

    /**
     * Whether the target keeps vectors, the vdb line writes into targets with vectors only
     */
    public boolean isVectorSupported() {
        return hasFeature(VdbFeature.VECTOR);
    }

    public String getProductVersion() {
        return productVersion;
    }

    protected boolean isVersionAtLeast(int major, int minor) {
        return majorVersion > major || (majorVersion == major && minorVersion >= minor);
    }

    protected static boolean probe(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next();
        } catch (SQLException e) {
            try {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            } catch (SQLException ignored) {
                // The connection is closed
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Identifiers
    // ------------------------------------------------------------------

    protected String getOpenQuote() {
        return "\"";
    }

    protected String getCloseQuote() {
        return "\"";
    }

    /**
     * Name of a table or a column in the target: unquoted names of PostgreSQL are lower case, of Oracle and Db2
     * upper case. Names of other cases are kept.
     */
    public String foldIdentifier(String name) {
        return name;
    }

    public int getMaxIdentifierLength() {
        return 128;
    }

    public String quoteIdentifier(String name) {
        return getOpenQuote() + name.replace(getCloseQuote(), getCloseQuote() + getCloseQuote()) + getCloseQuote();
    }

    /**
     * Name of a table, a column or an index of the target, folded, cut to the maximum length and quoted
     */
    public String getIdentifier(String name) {
        return quoteIdentifier(getLimitedIdentifier(foldIdentifier(name)));
    }

    public String getLimitedIdentifier(String name) {
        return name.length() > getMaxIdentifierLength() ? name.substring(0, getMaxIdentifierLength()) : name;
    }

    public String getIdentifiers(List<String> names) {
        return names.stream().map(this::getIdentifier).collect(Collectors.joining(", "));
    }

    public String getQualifiedTableName(String schema, String tableName) {
        return StringUtils.isNotBlank(schema) ? getIdentifier(schema) + "." + getIdentifier(tableName)
                : getIdentifier(tableName);
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    /**
     * Type of a scalar column of a kind
     */
    protected abstract String getScalarTypeName(VdbField field);

    /**
     * Type of a vector column of this database, null if the vectors are kept as text: the database has no vectors,
     * not of these elements or not of so many dimensions
     */
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        return null;
    }

    /**
     * Type of a column of the target: vectors which the target has not are text
     */
    public String getTypeName(VdbField field) {
        if (field.isVector()) {
            String typeName = getVectorTypeName(field.getVectorType(), field.isNullable() && !field.isPrimaryKey());
            if (typeName != null) {
                return typeName;
            }
            return getScalarTypeName(new VdbField(field.getName(), VdbField.Kind.TEXT));
        }
        return getScalarTypeName(field);
    }

    /**
     * Whether a column of the type keeps vectors of the target, see {@link #getVectorTypeName}
     */
    public boolean isVectorTypeName(VdbField field, String typeName) {
        return field.isVector() && typeName.equals(getVectorTypeName(field.getVectorType(), field.isNullable()
                && !field.isPrimaryKey()));
    }

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    public String getColumnDefinition(Column column) {
        return getIdentifier(column.getName()) + " " + column.getTypeName() + (column.isNullable() ? "" : " NOT NULL");
    }

    public String getPrimaryKeyDefinition(String tableName, List<String> keyColumnNames) {
        return String.format("CONSTRAINT %s PRIMARY KEY (%s)", getIdentifier("pk_" + tableName),
                getIdentifiers(keyColumnNames));
    }

    /**
     * Options of CREATE TABLE after the columns, e.g. the engine of ClickHouse
     */
    public String getTableOptions(List<String> keyColumnNames) {
        return "";
    }

    public String getDropTableStatement(String schema, String tableName) {
        return "DROP TABLE IF EXISTS " + getQualifiedTableName(schema, tableName);
    }

    /**
     * Comment of a table, null if the database has no comments
     */
    public String getTableCommentStatement(String schema, String tableName, String comment) {
        return String.format("COMMENT ON TABLE %s IS %s", getQualifiedTableName(schema, tableName),
                quoteLiteral(comment));
    }

    /**
     * Comment of a column, null if the database has no comments
     */
    public String getColumnCommentStatement(String schema, String tableName, String columnName, String comment) {
        return String.format("COMMENT ON COLUMN %s.%s IS %s", getQualifiedTableName(schema, tableName),
                getIdentifier(columnName), quoteLiteral(comment));
    }

    protected String quoteLiteral(String text) {
        return "'" + quoteLiteralText(text).replace("'", "''") + "'";
    }

    /**
     * Text of a literal before its quotes are doubled, e.g. backslashes of MySQL
     */
    protected String quoteLiteralText(String text) {
        return text;
    }

    /**
     * Index for the nearest neighbors of a vector column, null if this database has none for it: then no index is
     * created, an ordinary index can not hold vectors
     *
     * @param columnTypeName type of the column of this database, see {@link #getVectorTypeName}
     * @param nullable       whether the column takes NULL
     */
    public String getCreateVectorIndexStatement(String schema, String tableName, String indexName, String columnName,
                                                String columnTypeName, VectorType vectorType, boolean nullable,
                                                VectorIndex vectorIndex) {
        return null;
    }

    /**
     * Parameter of a value of a column, e.g. a cast of the text of a vector
     */
    public String getParameterMarker(Column column) {
        return "?";
    }

    public String getInsertStatement(String schema, String tableName, List<Column> columns) {
        return String.format("INSERT INTO %s (%s) VALUES (%s)", getQualifiedTableName(schema, tableName),
                getIdentifiers(names(columns)), columns.stream().map(this::getParameterMarker)
                        .collect(Collectors.joining(", ")));
    }

    public boolean isUpsertSupported() {
        return true;
    }

    /**
     * Whether vector indexes may be created on a table of the key columns, e.g. keys of MariaDB up to 256 bytes
     */
    public boolean isVectorIndexAllowed(List<Column> keyColumns) {
        return true;
    }

    /**
     * Vector indexes of a table at most, MariaDB has one
     */
    public int getMaxVectorIndexes() {
        return Integer.MAX_VALUE;
    }

    /**
     * Whether rows of a page are written in a transaction, ClickHouse writes each batch as a block of its own
     */
    public boolean isTransactionSupported() {
        return true;
    }

    /**
     * Statement which inserts a row, or updates the row of its key
     */
    public String getUpsertStatement(String schema, String tableName, List<Column> columns,
                                     List<String> keyColumnNames) {
        String insert = getInsertStatement(schema, tableName, columns);
        List<String> updates = updated(columns, keyColumnNames);
        if (updates.isEmpty()) {
            return String.format("%s ON CONFLICT (%s) DO NOTHING", insert, getIdentifiers(keyColumnNames));
        }
        return String.format("%s ON CONFLICT (%s) DO UPDATE SET %s", insert, getIdentifiers(keyColumnNames),
                updates.stream().map(name -> getIdentifier(name) + " = EXCLUDED." + getIdentifier(name))
                        .collect(Collectors.joining(", ")));
    }

    /**
     * MERGE of a row of parameters (Oracle, SQL Server, Db2)
     *
     * @param source rows of the parameters as src, e.g. (VALUES (?, ?)) AS src (a, b)
     */
    protected String getMergeStatement(String schema, String tableName, List<Column> columns,
                                       List<String> keyColumnNames, String source, String end) {
        StringBuilder sql = new StringBuilder("MERGE INTO ").append(getQualifiedTableName(schema, tableName))
                .append(" tgt USING ").append(source).append(" ON (").append(keyColumnNames.stream()
                        .map(name -> "tgt." + getIdentifier(name) + " = src." + getIdentifier(name))
                        .collect(Collectors.joining(" AND "))).append(")");
        List<String> updates = updated(columns, keyColumnNames);
        if (!updates.isEmpty()) {
            sql.append(" WHEN MATCHED THEN UPDATE SET ").append(updates.stream().map(name -> "tgt."
                    + getIdentifier(name) + " = src." + getIdentifier(name)).collect(Collectors.joining(", ")));
        }
        return sql.append(" WHEN NOT MATCHED THEN INSERT (").append(getIdentifiers(names(columns)))
                .append(") VALUES (").append(columns.stream().map(column -> "src." + getIdentifier(column.getName()))
                        .collect(Collectors.joining(", "))).append(")").append(end).toString();
    }

    protected static List<String> names(List<Column> columns) {
        return columns.stream().map(Column::getName).collect(Collectors.toList());
    }

    protected static List<String> updated(List<Column> columns, List<String> keyColumnNames) {
        return names(columns).stream().filter(name -> !keyColumnNames.contains(name)).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /**
     * Value of a row for the parameter of its column: vectors are text of the target, times are of UTC for targets
     * without time zones
     */
    public Object getJdbcValue(Object value, Column column) {
        if (value == null) {
            return null;
        }
        VdbField field = column.getField();
        if (field.isVector()) {
            if (!isVectorTypeName(field, column.getTypeName())) {
                return value.toString();
            }
            VectorType.Values values = VectorType.parseValues(value.toString());
            if (values == null) {
                throw new VdbException("Invalid vector of column " + column.getName() + ": " + StringUtils.abbreviate(
                        value.toString(), 80));
            }
            return getVectorValue(values, field.getVectorType());
        }
        VdbField.Kind kind = field.getKind();
        if ((kind == VdbField.Kind.VARCHAR || kind == VdbField.Kind.TEXT || kind == VdbField.Kind.JSON)
                && !(value instanceof String)) {
            // Ids of numbers and UUIDs (Qdrant) are text, a batch binds every row as its first
            return value.toString();
        }
        if (value instanceof OffsetDateTime && !isTimeZoneSupported()) {
            return Timestamp.valueOf(((OffsetDateTime) value).withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime());
        }
        return value;
    }

    /**
     * Value of a vector for a vector column of this database, e.g. a sparse vector as the sparse text of this
     * database
     */
    protected Object getVectorValue(VectorType.Values values, VectorType vectorType) {
        return values.toDenseText();
    }

    /**
     * Whether TIMESTAMP columns keep time zones, times of other targets are of UTC
     */
    protected boolean isTimeZoneSupported() {
        return true;
    }

    /**
     * Sql type used to bind NULL of a column
     */
    public int getNullSqlType(Column column) {
        VdbField field = column.getField();
        switch (field.getKind()) {
            case BOOLEAN:
                return Types.BOOLEAN;
            case SMALLINT:
                return Types.SMALLINT;
            case INTEGER:
                return Types.INTEGER;
            case BIGINT:
                return Types.BIGINT;
            case REAL:
                return Types.REAL;
            case DOUBLE:
                return Types.DOUBLE;
            case TIMESTAMP:
                return isTimeZoneSupported() ? Types.TIMESTAMP_WITH_TIMEZONE : Types.TIMESTAMP;
            case BINARY:
                return Types.VARBINARY;
            default:
                return Types.VARCHAR;
        }
    }

    /**
     * Whether a table exists in the target
     */
    public boolean isTableExisting(Connection connection, String schema, String tableName) throws SQLException {
        String name = getLimitedIdentifier(foldIdentifier(tableName));
        String schemaName = StringUtils.isNotBlank(schema) ? foldIdentifier(schema) : null;
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = isCatalogQualifier() ? metaData.getTables(schemaName, null, name, null)
                : metaData.getTables(null, schemaName, name, null)) {
            while (rs.next()) {
                if (name.equals(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the qualifier of a table is a catalog of JDBC (databases of MySQL and ClickHouse)
     */
    protected boolean isCatalogQualifier() {
        return false;
    }
}
