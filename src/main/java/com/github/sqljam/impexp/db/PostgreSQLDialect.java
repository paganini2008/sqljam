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


import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.postgresql.util.PGobject;
import com.github.sqljam.impexp.DatabaseFeature;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.ImpExpException;
import com.github.sqljam.impexp.StringHelper;
import com.github.sqljam.impexp.TypeNames;
import com.github.sqljam.impexp.VectorIndex;
import com.github.sqljam.impexp.VectorType;

/**
 * @Description: PostgreSQLDialect for PostgreSQL 12 and later versions.
 *               PostgreSQL 9.x and later are supported, older versions by PostgreSQL9Dialect and PostgreSQL10Dialect.
 *               Vectors of pgvector, detected on the connection: vector and IVFFlat of pgvector 0.4, HNSW of 0.5.0,
 *               halfvec, sparsevec and the Manhattan distance of HNSW of 0.7.0.
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

    /**
     * Enum and domain types are created in the target schema if they do not exist
     */
    @Override
    public String getCreateUserTypeStatement(String catalog, String schema, Map<String, Object> userType) {
        String typeName = getUserTypeName(catalog, schema, (String) userType.get("NAME"));
        String create = String.format("CREATE %s %s AS %s", "DOMAIN".equals(userType.get("KIND")) ? "DOMAIN" : "TYPE",
                typeName, userType.get("DEFINITION"));
        return String.format("DO $$ BEGIN %s; EXCEPTION WHEN duplicate_object THEN NULL; END $$", create);
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

    /**
     * Index of another access method (gin, gist, hnsw) with the operator classes of its columns and its options
     */
    @Override
    public String getCreateIndexStatement(String catalog, String schema, String tableName, boolean partition,
                                          String[] columnNames, String indexName, boolean unique, String indexType,
                                          String[] operatorClasses, String options) {
        if (StringUtils.isBlank(indexType)) {
            return getCreateIndexStatement(catalog, schema, tableName, partition, columnNames, indexName, unique,
                    indexType);
        }
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < columnNames.length; i++) {
            String operatorClass = operatorClasses != null && i < operatorClasses.length ? operatorClasses[i] : null;
            columns.append(i > 0 ? ", " : "").append(getIdentifier(columnNames[i]))
                    .append(StringUtils.isNotBlank(operatorClass) ? " " + operatorClass : "");
        }
        return String.format("CREATE %sINDEX %s%s ON %s USING %s (%s)%s", unique ? "UNIQUE " : "", getIfNotExists(),
                quoteIdentifier(indexName), getQualifiedTableName(catalog, schema, tableName), indexType, columns,
                StringUtils.isNotBlank(options) ? " WITH (" + options + ")" : "");
    }

    // ------------------------------------------------------------------
    // Vectors of pgvector
    // ------------------------------------------------------------------

    /**
     * Dimensions of vector and halfvec columns
     */
    static final int MAX_VECTOR_DIMENSIONS = 16000;
    /**
     * Dimensions of an index of vector and of halfvec columns
     */
    static final int MAX_VECTOR_INDEX_DIMENSIONS = 2000;
    static final int MAX_HALFVEC_INDEX_DIMENSIONS = 4000;

    /**
     * Types (vector, halfvec, sparsevec) and index methods (hnsw, ivfflat) of pgvector which the database has,
     * the types are found by the search path. The queries do not fail for a missing type: a failed query aborts
     * the transaction of PostgreSQL.
     */
    @Override
    protected void detectDatabaseFeatures(Connection connection, Set<DatabaseFeature> features) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT to_regtype('vector') IS NOT NULL, to_regtype('halfvec') IS NOT NULL,"
                        + " to_regtype('sparsevec') IS NOT NULL,"
                        + " EXISTS (SELECT 1 FROM pg_am WHERE amname = 'hnsw'),"
                        + " EXISTS (SELECT 1 FROM pg_am WHERE amname = 'ivfflat')")) {
            if (rs.next()) {
                DatabaseFeature[] columns = {DatabaseFeature.VECTOR, DatabaseFeature.HALF_VECTOR,
                        DatabaseFeature.SPARSE_VECTOR, DatabaseFeature.HNSW_INDEX, DatabaseFeature.IVF_INDEX};
                for (int i = 0; i < columns.length; i++) {
                    if (rs.getBoolean(i + 1)) {
                        features.add(columns[i]);
                    }
                }
            }
        }
    }

    /**
     * The schema of pgvector is added to the search path if it is not in it, e.g. of a url with currentSchema: the
     * types (vector) and operator classes (vector_cosine_ops) of pgvector are found by the search path
     */
    @Override
    public void prepareSession(Connection connection) {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT n.nspname, current_setting('search_path') FROM pg_extension e"
                        + " JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'vector'"
                        + " AND NOT n.nspname = ANY (current_schemas(false))")) {
            if (rs.next()) {
                String path = StringUtils.trimToEmpty(rs.getString(2));
                String schema = "\"" + rs.getString(1).replace("\"", "\"\"") + "\"";
                try (Statement set = connection.createStatement()) {
                    set.execute("SET search_path TO " + (path.isEmpty() ? schema : path + ", " + schema));
                }
            }
        } catch (SQLException e) {
            // The search path is kept, vectors are found if pgvector is in it
        }
    }

    /**
     * vector (32-bit floats, also of bytes), halfvec (16-bit floats) and sparsevec of pgvector. Doubles and bits
     * are kept as text.
     */
    @Override
    public String getVectorTypeName(VectorType vectorType) {
        if (vectorType.getDimensions() > MAX_VECTOR_DIMENSIONS || !vectorType.isFloat32Compatible()) {
            return null;
        }
        String dimensions = vectorType.getDimensions() > 0 ? "(" + vectorType.getDimensions() + ")" : "";
        if (vectorType.isSparse()) {
            // sparsevec of pgvector 0.7.0 and later
            return hasDatabaseFeature(DatabaseFeature.SPARSE_VECTOR, true) ? "sparsevec" + dimensions : null;
        }
        if (vectorType.getElement() == VectorType.Element.FLOAT16 && hasDatabaseFeature(DatabaseFeature.HALF_VECTOR,
                true)) {
            return "halfvec" + dimensions;
        }
        // 16-bit floats are kept by vectors of 32-bit floats of older versions
        return hasDatabaseFeature(DatabaseFeature.VECTOR, true) ? "vector" + dimensions : null;
    }

    @Override
    public Object getVectorValue(VectorType.Values values, VectorType vectorType) {
        return vectorType.isSparse() ? values.toPostgreSqlSparseText() : values.toDenseText();
    }

    /**
     * HNSW or IVFFlat index with the operator class of the distance, e.g. vector_cosine_ops. Columns of more
     * dimensions than an index takes, and columns without dimensions, have no index.
     */
    @Override
    public String getCreateVectorIndexStatement(String catalog, String schema, String tableName, String indexName,
                                                String columnName, String columnTypeName, VectorType vectorType,
                                                boolean nullable, VectorIndex vectorIndex) {
        String type = StringUtils.substringBefore(StringUtils.defaultString(columnTypeName), "(").trim()
                .toLowerCase(Locale.ENGLISH);
        int dimensions = NumberUtils.toInt(StringUtils.substringBetween(columnTypeName, "(", ")"), 0);
        String operator = getVectorOperator(vectorIndex.getDistance());
        if (operator == null || dimensions <= 0 || ("vector".equals(type) && dimensions > MAX_VECTOR_INDEX_DIMENSIONS)
                || ("halfvec".equals(type) && dimensions > MAX_HALFVEC_INDEX_DIMENSIONS)) {
            return null;
        }
        // HNSW of pgvector 0.5.0 and later, IVFFlat has no sparse vectors: the other kind is taken if the database
        // has not the kind of the index or not for the vectors
        // IVFFlat has no Manhattan distance (l1)
        boolean hnswAvailable = hasDatabaseFeature(DatabaseFeature.HNSW_INDEX, true);
        boolean ivfAvailable = hasDatabaseFeature(DatabaseFeature.IVF_INDEX, true) && !"sparsevec".equals(type)
                && vectorIndex.getDistance() != VectorIndex.Distance.MANHATTAN;
        if (!hnswAvailable && !ivfAvailable) {
            return null;
        }
        boolean ivf = ivfAvailable && (vectorIndex.getKind() == VectorIndex.Kind.IVF || !hnswAvailable);
        List<String> options = new ArrayList<>();
        for (String option : ivf ? new String[]{"lists"} : new String[]{"m", "ef_construction"}) {
            String value = vectorIndex.getOption(option);
            if (NumberUtils.isDigits(value)) {
                options.add(option + " = " + value);
            }
        }
        return String.format("CREATE INDEX %s%s ON %s USING %s (%s %s_%s_ops)%s", getIfNotExists(),
                quoteIdentifier(indexName), getQualifiedTableName(catalog, schema, tableName), ivf ? "ivfflat" : "hnsw",
                getIdentifier(columnName), type, operator, options.isEmpty() ? "" : " WITH (" + String.join(", ",
                        options) + ")");
    }

    /**
     * Operator of a distance in the names of the operator classes, e.g. cosine of vector_cosine_ops
     */
    static String getVectorOperator(VectorIndex.Distance distance) {
        switch (distance) {
            case EUCLIDEAN:
                return "l2";
            case COSINE:
                return "cosine";
            case DOT:
                return "ip";
            case MANHATTAN:
                return "l1";
            default:
                // Hamming and Jaccard distances are of bit vectors
                return null;
        }
    }

    /**
     * Distance of an operator class of pgvector, e.g. vector_cosine_ops, null for other operator classes
     */
    static VectorIndex.Distance getVectorDistance(String operatorClass) {
        String name = StringUtils.defaultString(operatorClass).toLowerCase(Locale.ENGLISH);
        if (name.endsWith("_l2_ops")) {
            return VectorIndex.Distance.EUCLIDEAN;
        } else if (name.endsWith("_cosine_ops")) {
            return VectorIndex.Distance.COSINE;
        } else if (name.endsWith("_ip_ops")) {
            return VectorIndex.Distance.DOT;
        } else if (name.endsWith("_l1_ops")) {
            return VectorIndex.Distance.MANHATTAN;
        } else if (name.endsWith("_hamming_ops")) {
            return VectorIndex.Distance.HAMMING;
        } else if (name.endsWith("_jaccard_ops")) {
            return VectorIndex.Distance.JACCARD;
        }
        return null;
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
        return Types.OTHER;
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

    /**
     * PostgreSQL reads the rows by a cursor only in a transaction, without it all rows are read into memory
     */
    @Override
    public boolean prepareStreaming(Connection connection, Statement statement, int fetchSize) throws SQLException {
        statement.setFetchSize(fetchSize);
        if (connection.getAutoCommit()) {
            connection.setAutoCommit(false);
            return true;
        }
        return false;
    }
}
