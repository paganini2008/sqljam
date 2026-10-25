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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DatabaseFeature;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.IndexMetaData;
import com.github.sqljam.impexp.VectorIndex;
import com.github.sqljam.impexp.VectorType;

/**
 * @Description: VectorDialectTest checks vector types, values and indexes of the dialects: by detected features, by
 *               versions if the features are not known (scripts), and without features (an older server, an
 *               extension which is not installed, a setting or a view which is not granted)
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VectorDialectTest {

    private static final VectorType FLOAT32 = VectorType.dense(VectorType.Element.FLOAT32, 3);
    private static final VectorType FLOAT16 = VectorType.dense(VectorType.Element.FLOAT16, 3);
    private static final VectorType FLOAT64 = VectorType.dense(VectorType.Element.FLOAT64, 3);
    private static final VectorType INT8 = VectorType.dense(VectorType.Element.INT8, 3);
    private static final VectorType SPARSE = new VectorType(VectorType.Element.FLOAT32, 5, true);
    private static final VectorType ANY_DIMENSIONS = VectorType.dense(VectorType.Element.FLOAT32, 0);
    private static final Set<DatabaseFeature> ALL = EnumSet.allOf(DatabaseFeature.class);
    private static final Set<DatabaseFeature> NONE = EnumSet.noneOf(DatabaseFeature.class);

    private static VectorIndex index(String text) {
        return VectorIndex.parse(text);
    }

    private static <T extends Dialect> T withFeatures(T dialect, Set<DatabaseFeature> features) {
        dialect.setDatabaseFeatures(features);
        return dialect;
    }

    @Test
    void postgreSqlTypes() {
        PostgreSQLDialect unknown = new PostgreSQLDialect();
        // A script: pgvector is taken as installed
        assertEquals("vector(3)", unknown.getVectorTypeName(FLOAT32));
        assertEquals("vector(3)", unknown.getVectorTypeName(INT8));
        assertEquals("halfvec(3)", unknown.getVectorTypeName(FLOAT16));
        assertEquals("sparsevec(5)", unknown.getVectorTypeName(SPARSE));
        assertEquals("vector", unknown.getVectorTypeName(ANY_DIMENSIONS));
        assertEquals("vector(3)", unknown.getVectorTypeName(FLOAT32, true));
        // Doubles and bits would change, they are text
        assertNull(unknown.getVectorTypeName(FLOAT64));
        assertNull(unknown.getVectorTypeName(VectorType.dense(VectorType.Element.BINARY, 8)));
        assertEquals("vector(16000)", unknown.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 16000)));
        assertNull(unknown.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 16001)));

        // pgvector is not installed
        PostgreSQLDialect none = withFeatures(new PostgreSQLDialect(), NONE);
        assertNull(none.getVectorTypeName(FLOAT32));
        assertNull(none.getVectorTypeName(FLOAT16));
        assertNull(none.getVectorTypeName(SPARSE));
        // pgvector before 0.7.0: no halfvec and sparsevec
        PostgreSQLDialect old = withFeatures(new PostgreSQLDialect(), EnumSet.of(DatabaseFeature.VECTOR,
                DatabaseFeature.IVF_INDEX));
        assertEquals("vector(3)", old.getVectorTypeName(FLOAT16));
        assertNull(old.getVectorTypeName(SPARSE));
        // The features are kept by the dialect of a version
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR, DatabaseFeature.IVF_INDEX), old.forVersion(16, 0)
                .getDatabaseFeatures());
        assertNull(unknown.forVersion(16, 0).getDatabaseFeatures());

        assertEquals("[1,2.5,-3]", unknown.getVectorValue(VectorType.parseValues("[1,2.5,-3]"), FLOAT32));
        assertEquals("{1:1.5,3:2}/5", unknown.getVectorValue(VectorType.parseValues("[5,[0,2],[1.5,2]]"), SPARSE));
    }

    @Test
    void postgreSqlIndexes() {
        PostgreSQLDialect all = withFeatures(new PostgreSQLDialect(), ALL);
        assertEquals("CREATE INDEX IF NOT EXISTS idx_t_v ON t USING hnsw (v vector_cosine_ops) WITH (m = 16,"
                        + " ef_construction = 64)", all.getCreateVectorIndexStatement(null, "s", "t", "idx_t_v", "v",
                "vector(3)", FLOAT32, false, index("HNSW,COSINE,m=16;ef_construction=64")));
        assertEquals("CREATE INDEX IF NOT EXISTS i ON t USING ivfflat (v halfvec_l2_ops) WITH (lists = 100)",
                all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "halfvec(3)", FLOAT16, true,
                        index("IVF,EUCLIDEAN,lists=100;m=8")));
        // Options which are not numbers are left out
        assertEquals("CREATE INDEX IF NOT EXISTS i ON t USING hnsw (v vector_ip_ops)",
                all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector(3)", FLOAT32, true,
                        index("HNSW,DOT,m=many")));
        // IVFFlat has no Manhattan distance and no sparse vectors, HNSW is taken
        assertTrue(all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector(3)", FLOAT32, true,
                index("IVF,MANHATTAN,lists=10")).contains("USING hnsw (v vector_l1_ops)"));
        assertTrue(all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "sparsevec(5)", SPARSE, true,
                index("IVF,COSINE,lists=10")).contains("USING hnsw (v sparsevec_cosine_ops)"));
        // Dimensions which an index does not take, and vectors without dimensions
        assertNull(all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector(2001)", null, true,
                index("HNSW,COSINE,")));
        assertTrue(all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "halfvec(4000)", null, true,
                index("HNSW,COSINE,")).contains("halfvec_cosine_ops"));
        assertNull(all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "halfvec(4001)", null, true,
                index("HNSW,COSINE,")));
        assertNull(all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector", null, true,
                index("HNSW,COSINE,")));
        // Distances of bit vectors
        assertNull(all.getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector(3)", FLOAT32, true,
                index("HNSW,HAMMING,")));

        // pgvector before 0.5.0 has no HNSW: IVFFlat is taken
        PostgreSQLDialect ivfOnly = withFeatures(new PostgreSQLDialect(), EnumSet.of(DatabaseFeature.VECTOR,
                DatabaseFeature.IVF_INDEX));
        assertTrue(ivfOnly.getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector(3)", FLOAT32, true,
                index("HNSW,COSINE,m=16")).contains("USING ivfflat (v vector_cosine_ops)"));
        assertNull(ivfOnly.getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector(3)", FLOAT32, true,
                index("HNSW,MANHATTAN,")));
        assertNull(withFeatures(new PostgreSQLDialect(), EnumSet.of(DatabaseFeature.VECTOR))
                .getCreateVectorIndexStatement(null, null, "t", "i", "v", "vector(3)", FLOAT32, true,
                        index("HNSW,COSINE,")));

        // Indexes of other access methods of the same database type
        assertEquals("CREATE INDEX IF NOT EXISTS i ON t USING gin (doc jsonb_path_ops, tags) WITH (fastupdate=off)",
                all.getCreateIndexStatement(null, null, "t", false, new String[]{"doc", "tags"}, "i", false, "gin",
                        new String[]{"jsonb_path_ops"}, "fastupdate=off"));
        assertEquals(all.getCreateIndexStatement(null, null, "t", false, new String[]{"a"}, "i", true, null),
                all.getCreateIndexStatement(null, null, "t", false, new String[]{"a"}, "i", true, null, null, null));

        assertEquals("l2", PostgreSQLDialect.getVectorOperator(VectorIndex.Distance.EUCLIDEAN));
        assertNull(PostgreSQLDialect.getVectorOperator(VectorIndex.Distance.JACCARD));
        assertEquals(VectorIndex.Distance.HAMMING, PostgreSQLDialect.getVectorDistance("bit_hamming_ops"));
        assertEquals(VectorIndex.Distance.JACCARD, PostgreSQLDialect.getVectorDistance("bit_jaccard_ops"));
        assertEquals(VectorIndex.Distance.MANHATTAN, PostgreSQLDialect.getVectorDistance("VECTOR_L1_OPS"));
        assertNull(PostgreSQLDialect.getVectorDistance("jsonb_path_ops"));
        assertNull(PostgreSQLDialect.getVectorDistance(null));
    }

    @Test
    void postgreSqlFeatures() {
        // vector and hnsw, an older pgvector without halfvec, sparsevec and ivfflat
        Connection connection = connection(sql -> new Object[]{true, false, false, true, false});
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR, DatabaseFeature.HNSW_INDEX),
                new PostgreSQLDialect().readDatabaseFeatures(connection));
        // A catalog which is not readable: no features, the vectors are text
        PostgreSQLDialect failed = new PostgreSQLDialect();
        assertEquals(NONE, failed.readDatabaseFeatures(connection(sql -> {
            throw new SQLException("permission denied");
        })));
        assertNull(failed.getVectorTypeName(FLOAT32));
        assertEquals(NONE, new PostgreSQLDialect().readDatabaseFeatures(connection(sql -> null)));
    }

    @Test
    void mySqlAndMariaDb() {
        // VECTOR of MySQL 9.0 and MariaDB 11.7, by the version for a script
        assertEquals("VECTOR(3)", new MySQLDialect().getVectorTypeName(FLOAT32));
        assertEquals("VECTOR(3)", new MySQLDialect().getVectorTypeName(FLOAT16));
        assertNull(DbType.MYSQL.createDialect(8, 0).getVectorTypeName(FLOAT32));
        assertNull(DbType.MARIADB.createDialect(11, 4).getVectorTypeName(FLOAT32));
        assertEquals("VECTOR(3)", DbType.MARIADB.createDialect(11, 8).getVectorTypeName(FLOAT32));
        MySQLDialect mysql = (MySQLDialect) DbType.MYSQL.createDialect(9, 7);
        assertEquals("VECTOR(16383)", mysql.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 16383)));
        assertNull(mysql.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 16384)));
        assertNull(mysql.getVectorTypeName(ANY_DIMENSIONS));
        assertNull(mysql.getVectorTypeName(SPARSE));
        assertNull(mysql.getVectorTypeName(FLOAT64));
        // The function is not found: a server of an older version than it says, or an edition without vectors
        assertNull(withFeatures((MySQLDialect) DbType.MYSQL.createDialect(9, 7), NONE).getVectorTypeName(FLOAT32));
        assertNull(mysql.getCreateVectorIndexStatement(null, "d", "t", "i", "v", "VECTOR(3)", FLOAT32, false,
                index("HNSW,COSINE,")));

        // Text of a script for a VECTOR column
        mysql.registerColumnTypeName("t", "v", "VECTOR(3)");
        assertEquals("STRING_TO_VECTOR('[1,2]')", mysql.getStringValue(null, "d", "t", "v", "[1,2]"));
        assertEquals("'x'", mysql.getStringValue(null, "d", "t", "w", "x"));
        MariaDBDialect mariadb = new MariaDBDialect();
        mariadb.registerColumnTypeName("t", "v", "VECTOR(3)");
        assertEquals("VEC_FromText('[1,2]')", mariadb.getStringValue(null, "d", "t", "v", "[1,2]"));

        assertEquals("CREATE VECTOR INDEX i ON t (v) M=8 DISTANCE=cosine", mariadb.getCreateVectorIndexStatement(
                null, "d", "t", "i", "v", "VECTOR(3)", FLOAT32, false, index("HNSW,COSINE,m=8")));
        // M of 3 to 200, other values are left out
        assertEquals("CREATE VECTOR INDEX i ON t (v) DISTANCE=euclidean", mariadb.getCreateVectorIndexStatement(
                null, null, "t", "i", "v", "VECTOR(3)", FLOAT32, false, index("IVF,EUCLIDEAN,m=500")));
        assertTrue(mariadb.getCreateVectorIndexStatement(null, null, "t", "i", "v", "VECTOR(3)", FLOAT32, false,
                index("HNSW,COSINE,m=3")).contains(" M=3 "));
        // A vector index needs NOT NULL, it has no dot product
        assertNull(mariadb.getCreateVectorIndexStatement(null, null, "t", "i", "v", "VECTOR(3)", FLOAT32, true,
                index("HNSW,COSINE,")));
        assertNull(mariadb.getCreateVectorIndexStatement(null, null, "t", "i", "v", "VECTOR(3)", FLOAT32, false,
                index("HNSW,DOT,")));
        assertNull(withFeatures(new MariaDBDialect(), EnumSet.of(DatabaseFeature.VECTOR))
                .getCreateVectorIndexStatement(null, null, "t", "i", "v", "VECTOR(3)", FLOAT32, false,
                        index("HNSW,COSINE,")));

        assertEquals(EnumSet.of(DatabaseFeature.VECTOR), new MySQLDialect().readDatabaseFeatures(connection(
                sql -> new Object[]{"[1.00000e+00]"})));
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR, DatabaseFeature.HNSW_INDEX), new MariaDBDialect()
                .readDatabaseFeatures(connection(sql -> new Object[]{"[1]"})));
        assertEquals(NONE, new MariaDBDialect().readDatabaseFeatures(connection(sql -> {
            throw new SQLException("FUNCTION VEC_FromText does not exist");
        })));
    }

    @Test
    void oracle() {
        assertNull(DbType.ORACLE.createDialect(19, 0).getVectorTypeName(FLOAT32));
        OracleDialect oracle = (OracleDialect) DbType.ORACLE.createDialect(23, 26);
        assertEquals("VECTOR(3, FLOAT32)", oracle.getVectorTypeName(FLOAT32));
        assertEquals("VECTOR(3, FLOAT32)", oracle.getVectorTypeName(FLOAT16));
        assertEquals("VECTOR(3, FLOAT64)", oracle.getVectorTypeName(FLOAT64));
        assertEquals("VECTOR(3, INT8)", oracle.getVectorTypeName(INT8));
        assertEquals("VECTOR(*, FLOAT32)", oracle.getVectorTypeName(ANY_DIMENSIONS));
        assertEquals("VECTOR(5, FLOAT32, SPARSE)", oracle.getVectorTypeName(SPARSE));
        assertEquals("VECTOR(16, BINARY)", oracle.getVectorTypeName(VectorType.dense(VectorType.Element.BINARY, 16)));
        // Bits of whole bytes, and no sparse bits
        assertNull(oracle.getVectorTypeName(VectorType.dense(VectorType.Element.BINARY, 12)));
        assertNull(oracle.getVectorTypeName(new VectorType(VectorType.Element.BINARY, 16, true)));
        assertNull(oracle.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 65536)));
        assertEquals("[5,[0,2],[1.5,2]]", oracle.getVectorValue(VectorType.parseValues("{1:1.5,3:2}/5"), SPARSE));
        assertEquals("[1,2]", oracle.getVectorValue(VectorType.parseValues("[1,2]"), FLOAT32));

        // HNSW is kept in vector memory, IVF otherwise and for scripts
        OracleDialect memory = withFeatures((OracleDialect) DbType.ORACLE.createDialect(23, 26), ALL);
        assertEquals("CREATE VECTOR INDEX I ON T (V) ORGANIZATION INMEMORY NEIGHBOR GRAPH DISTANCE COSINE"
                + " PARAMETERS (TYPE HNSW, NEIGHBORS 16, EFCONSTRUCTION 64)", memory.getCreateVectorIndexStatement(
                null, null, "T", "I", "V", "VECTOR(3, FLOAT32)", FLOAT32, true,
                index("HNSW,COSINE,m=16;ef_construction=64")));
        assertEquals("CREATE VECTOR INDEX I ON T (V) ORGANIZATION NEIGHBOR PARTITIONS DISTANCE COSINE",
                oracle.getCreateVectorIndexStatement(null, null, "T", "I", "V", "VECTOR(3, FLOAT32)", FLOAT32, true,
                        index("HNSW,COSINE,m=16")));
        OracleDialect noMemory = withFeatures((OracleDialect) DbType.ORACLE.createDialect(23, 26), EnumSet.of(
                DatabaseFeature.VECTOR, DatabaseFeature.IVF_INDEX));
        assertEquals("CREATE VECTOR INDEX I ON T (V) ORGANIZATION NEIGHBOR PARTITIONS DISTANCE DOT PARAMETERS"
                + " (TYPE IVF, NEIGHBOR PARTITIONS 8)", noMemory.getCreateVectorIndexStatement(null, null, "T", "I",
                "V", "VECTOR(3, FLOAT32)", FLOAT32, true, index("HNSW,DOT,lists=8")));
        assertNull(oracle.getCreateVectorIndexStatement(null, null, "T", "I", "V", "VECTOR(5, FLOAT32, SPARSE)", SPARSE,
                true, index("HNSW,COSINE,")));
        // Vectors were not found: no type and no index
        OracleDialect none = withFeatures((OracleDialect) DbType.ORACLE.createDialect(23, 26), NONE);
        assertNull(none.getVectorTypeName(FLOAT32));
        assertNull(none.getCreateVectorIndexStatement(null, null, "T", "I", "V", "VECTOR(3, FLOAT32)", FLOAT32, true,
                index("IVF,COSINE,")));
        // Sparse vectors of 23.7 and later
        assertNull(noMemory.getVectorTypeName(SPARSE));

        // Vector memory of 256 MB
        assertEquals(ALL.size() - 1, new OracleDialect().readDatabaseFeatures(connection(sql -> sql.contains(
                "v$parameter") ? new Object[]{"268435456"} : new Object[]{"[1]"})).size());
        // v$parameter is not granted: no HNSW
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR, DatabaseFeature.IVF_INDEX, DatabaseFeature.SPARSE_VECTOR),
                new OracleDialect().readDatabaseFeatures(connection(sql -> {
                    if (sql.contains("v$parameter")) {
                        throw new SQLException("ORA-00942: table or view does not exist");
                    }
                    return new Object[]{"[1]"};
                })));
        // No vector memory, an older release without sparse vectors
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR, DatabaseFeature.IVF_INDEX), new OracleDialect()
                .readDatabaseFeatures(connection(sql -> {
                    if (sql.contains("SPARSE")) {
                        throw new SQLException("ORA-00907: missing right parenthesis");
                    }
                    return new Object[]{sql.contains("v$parameter") ? "0" : "[1]"};
                })));
        // Oracle 19c: no vectors at all
        assertEquals(NONE, new OracleDialect().readDatabaseFeatures(connection(sql -> {
            throw new SQLException("ORA-00904: TO_VECTOR: invalid identifier");
        })));
    }

    @Test
    void sqlServer() throws SQLException {
        assertNull(DbType.SQLSERVER.createDialect(16, 0).getVectorTypeName(FLOAT32));
        SQLServerDialect sqlServer = (SQLServerDialect) DbType.SQLSERVER.createDialect(17, 0);
        assertEquals("VECTOR(3)", sqlServer.getVectorTypeName(FLOAT32));
        assertEquals("VECTOR(3)", sqlServer.getVectorTypeName(INT8));
        assertEquals("VECTOR(3, float16)", sqlServer.getVectorTypeName(FLOAT16));
        assertEquals("VECTOR(1998)", sqlServer.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 1998)));
        assertNull(sqlServer.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 1999)));
        assertEquals("VECTOR(3996, float16)", sqlServer.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT16,
                3996)));
        // 16-bit floats of more dimensions than float16 takes, and without float16: 32-bit floats if they fit
        assertNull(sqlServer.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT16, 3997)));
        assertEquals("VECTOR(3)", withFeatures((SQLServerDialect) DbType.SQLSERVER.createDialect(17, 0),
                EnumSet.of(DatabaseFeature.VECTOR)).getVectorTypeName(FLOAT16));
        assertNull(sqlServer.getVectorTypeName(ANY_DIMENSIONS));
        assertNull(sqlServer.getVectorTypeName(SPARSE));
        assertNull(sqlServer.getVectorTypeName(FLOAT64));
        // Vector indexes are a preview feature, a table with one is read only
        assertNull(sqlServer.getCreateVectorIndexStatement(null, "s", "t", "i", "v", "VECTOR(3)", FLOAT32, false,
                index("HNSW,COSINE,")));

        assertEquals("CAST(v AS NVARCHAR(MAX)) AS v", sqlServer.getSelectColumnExpression("v", "vector"));
        assertEquals("v", sqlServer.getSelectColumnExpression("v", "nvarchar"));
        // The binary of a vector: header 0xA9 0x01, dimensions, base type 0 and little endian floats
        byte[] binary = vectorBinary(1, 2.5f, -3);
        assertEquals("[1.0,2.5,-3.0]", SQLServerDialect.toVectorText(binary));
        byte[] shortBinary = {1, 2};
        assertSame(shortBinary, SQLServerDialect.toVectorText(shortBinary));
        byte[] otherHeader = vectorBinary(1);
        otherHeader[0] = 0;
        assertSame(otherHeader, SQLServerDialect.toVectorText(otherHeader));
        byte[] otherType = vectorBinary(1);
        otherType[4] = 1;
        assertSame(otherType, SQLServerDialect.toVectorText(otherType));
        byte[] truncated = new byte[binary.length - 1];
        System.arraycopy(binary, 0, truncated, 0, truncated.length);
        assertSame(truncated, SQLServerDialect.toVectorText(truncated));
        assertEquals("[1.0,2.5,-3.0]", sqlServer.getColumnValue(resultSet(binary), 1, -160, "vector"));
        assertArrayEquals(shortBinary, (byte[]) sqlServer.getColumnValue(resultSet(shortBinary), 1, Types.VARBINARY,
                "varbinary"));

        assertEquals(EnumSet.of(DatabaseFeature.VECTOR, DatabaseFeature.HALF_VECTOR), new SQLServerDialect()
                .readDatabaseFeatures(connection(sql -> new Object[]{"[1.0000000e+000]"})));
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR), new SQLServerDialect().readDatabaseFeatures(connection(sql -> {
            if (sql.contains("float16")) {
                throw new SQLException("The type 'float16' is not supported");
            }
            return new Object[]{"[1.0000000e+000]"};
        })));
        // SQL Server 2022: no VECTOR
        assertEquals(NONE, new SQLServerDialect().readDatabaseFeatures(connection(sql -> {
            throw new SQLException("Column, parameter, or variable #1: Cannot find data type VECTOR.");
        })));
    }

    @Test
    void fileAndOlapDatabases() {
        DuckDBDialect duckdb = new DuckDBDialect();
        assertEquals("FLOAT[3]", duckdb.getVectorTypeName(FLOAT32));
        assertEquals("FLOAT[3]", duckdb.getVectorTypeName(FLOAT16));
        assertEquals("DOUBLE[3]", duckdb.getVectorTypeName(FLOAT64));
        assertNull(duckdb.getVectorTypeName(ANY_DIMENSIONS));
        assertNull(duckdb.getVectorTypeName(SPARSE));
        assertNull(duckdb.getVectorTypeName(VectorType.dense(VectorType.Element.BINARY, 8)));
        assertNull(duckdb.getCreateVectorIndexStatement(null, "main", "t", "i", "v", "FLOAT[3]", FLOAT32, false,
                index("HNSW,COSINE,")));
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR), new DuckDBDialect().readDatabaseFeatures(connection(
                sql -> new Object[]{"[1.0]"})));

        ClickHouseDialect clickHouse = new ClickHouseDialect();
        assertEquals("Array(Float32)", clickHouse.getVectorTypeName(FLOAT32, false));
        assertEquals("Array(Float32)", clickHouse.getVectorTypeName(ANY_DIMENSIONS, false));
        assertEquals("Array(Float64)", clickHouse.getVectorTypeName(FLOAT64, false));
        // Arrays take no NULL: nullable vectors are text
        assertNull(clickHouse.getVectorTypeName(FLOAT32, true));
        assertNull(clickHouse.getVectorTypeName(SPARSE, false));
        // Vector similarity indexes are allowed by settings, they are not known for a script
        assertNull(clickHouse.getCreateVectorIndexStatement(null, "d", "t", "i", "v", "Array(Float32)", FLOAT32,
                false, index("HNSW,COSINE,")));
        ClickHouseDialect allowed = withFeatures(new ClickHouseDialect(), ALL);
        assertEquals("ALTER TABLE t ADD INDEX i v TYPE vector_similarity('hnsw', 'cosineDistance', 3, 'bf16', 16,"
                + " 64) GRANULARITY 100000000", allowed.getCreateVectorIndexStatement(null, "d", "t", "i", "v",
                "Array(Float32)", FLOAT32, false, index("HNSW,COSINE,m=16;ef_construction=64")));
        assertEquals("ALTER TABLE t ADD INDEX i v TYPE vector_similarity('hnsw', 'L2Distance', 3) GRANULARITY"
                + " 100000000", allowed.getCreateVectorIndexStatement(null, null, "t", "i", "v", "Array(Float32)",
                FLOAT32, false, index("IVF,EUCLIDEAN,m=16")));
        assertNull(allowed.getCreateVectorIndexStatement(null, null, "t", "i", "v", "Array(Float32)", FLOAT32, false,
                index("HNSW,DOT,")));
        assertNull(allowed.getCreateVectorIndexStatement(null, null, "t", "i", "v", "Array(Float32)", ANY_DIMENSIONS,
                false, index("HNSW,COSINE,")));
        assertNull(allowed.getCreateVectorIndexStatement(null, null, "t", "i", "v", "Array(Float32)", null, false,
                index("HNSW,COSINE,")));
        assertEquals(EnumSet.of(DatabaseFeature.VECTOR), new ClickHouseDialect().readDatabaseFeatures(connection(
                sql -> sql.contains("system.settings") ? null : new Object[]{"[1]"})));

        // Databases without vectors keep them as text and have no vector indexes
        for (DbType dbType : new DbType[]{DbType.H2, DbType.SQLITE, DbType.ACCESS}) {
            Dialect dialect = dbType.createDialect();
            assertNull(dialect.getVectorTypeName(FLOAT32), dbType.name());
            assertNull(dialect.getVectorTypeName(FLOAT32, false), dbType.name());
            assertNull(dialect.getCreateVectorIndexStatement(null, null, "t", "i", "v", "text", FLOAT32, false,
                    index("HNSW,COSINE,")), dbType.name());
            assertEquals(NONE, dialect.readDatabaseFeatures(connection(sql -> new Object[]{1})), dbType.name());
        }
    }

    @Test
    void db2() {
        // Db2 12.1.2 and later, the version tells no mod pack: by the features of the connection
        Db2Dialect db2 = new Db2Dialect();
        assertEquals("VECTOR(3, FLOAT32)", db2.getVectorTypeName(FLOAT32));
        assertEquals("VECTOR(3, FLOAT32)", db2.getVectorTypeName(FLOAT16));
        assertEquals("VECTOR(3, INT8)", db2.getVectorTypeName(INT8));
        assertEquals("VECTOR(8168, FLOAT32)", db2.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 8168)));
        assertNull(db2.getVectorTypeName(VectorType.dense(VectorType.Element.FLOAT32, 8169)));
        assertNull(db2.getVectorTypeName(ANY_DIMENSIONS));
        assertNull(db2.getVectorTypeName(FLOAT64));
        assertNull(db2.getVectorTypeName(SPARSE));
        assertNull(DbType.DB2.createDialect(11, 5).getVectorTypeName(FLOAT32));
        assertNull(withFeatures(new Db2Dialect(), NONE).getVectorTypeName(FLOAT32));

        assertEquals("CREATE VECTOR INDEX I ON T (V) WITH DISTANCE COSINE", db2.getCreateVectorIndexStatement(null,
                null, "T", "I", "V", "VECTOR(3, FLOAT32)", FLOAT32, true, index("HNSW,COSINE,m=16")));
        assertEquals("CREATE VECTOR INDEX I ON T (V) WITH DISTANCE EUCLIDEAN", db2.getCreateVectorIndexStatement(null,
                null, "T", "I", "V", "VECTOR(3, FLOAT32)", FLOAT32, false, index("IVF,EUCLIDEAN,")));
        // Keys of 8000 bytes: 2000 dimensions of 32-bit floats, 8000 of bytes
        assertTrue(db2.getCreateVectorIndexStatement(null, null, "T", "I", "V", "VECTOR(2000, FLOAT32)",
                VectorType.dense(VectorType.Element.FLOAT32, 2000), true, index("HNSW,COSINE,")) != null);
        assertNull(db2.getCreateVectorIndexStatement(null, null, "T", "I", "V", "VECTOR(2001, FLOAT32)",
                VectorType.dense(VectorType.Element.FLOAT32, 2001), true, index("HNSW,COSINE,")));
        assertTrue(db2.getCreateVectorIndexStatement(null, null, "T", "I", "V", "VECTOR(8000, INT8)",
                VectorType.dense(VectorType.Element.INT8, 8000), true, index("HNSW,COSINE,")) != null);
        // Dot product and Manhattan distance have no vector index of Db2
        assertNull(db2.getCreateVectorIndexStatement(null, null, "t", "i", "v", "VECTOR(3, FLOAT32)", FLOAT32, true,
                index("HNSW,DOT,")));
        assertNull(withFeatures(new Db2Dialect(), NONE).getCreateVectorIndexStatement(null, null, "t", "i", "v",
                "VECTOR(3, FLOAT32)", FLOAT32, true, index("HNSW,COSINE,")));

        assertEquals(EnumSet.of(DatabaseFeature.VECTOR, DatabaseFeature.HNSW_INDEX), new Db2Dialect()
                .readDatabaseFeatures(connection(sql -> new Object[]{"[1]"})));
        // Db2 12.1.1 and older: no VECTOR function
        assertEquals(NONE, new Db2Dialect().readDatabaseFeatures(connection(sql -> {
            throw new SQLException("SQLCODE=-440, SQLSTATE=42884, SQLERRMC=VECTOR;FUNCTION");
        })));
    }

    @Test
    void metadataOfSources() {
        assertEquals(VectorType.dense(VectorType.Element.FLOAT32, 1024), PostgreSQLMetaDataOperations.getVectorType(
                "vector", "vector(1024)"));
        assertEquals(VectorType.dense(VectorType.Element.FLOAT16, 0), PostgreSQLMetaDataOperations.getVectorType(
                "halfvec", null));
        assertEquals(new VectorType(VectorType.Element.FLOAT32, 30000, true), PostgreSQLMetaDataOperations
                .getVectorType("sparsevec", "sparsevec(30000)"));
        assertNull(PostgreSQLMetaDataOperations.getVectorType("int4", "integer"));

        Map<String, Object> hnsw = new HashMap<>();
        PostgreSQLMetaDataOperations.applyIndexMethod(hnsw, method("hnsw", "vector_cosine_ops", false,
                "m=16, ef_construction=64"));
        assertEquals("hnsw", hnsw.get(IndexMetaData.INDEX_METHOD));
        assertEquals("vector_cosine_ops", hnsw.get(IndexMetaData.OPERATOR_CLASS));
        assertEquals("HNSW,COSINE,m=16;ef_construction=64", hnsw.get(VectorIndex.DETAIL_KEY));
        Map<String, Object> ivf = new HashMap<>();
        PostgreSQLMetaDataOperations.applyIndexMethod(ivf, method("ivfflat", "halfvec_ip_ops", false, null));
        assertEquals("IVF,DOT,", ivf.get(VectorIndex.DETAIL_KEY));
        // Default operator classes are not written, ordinary indexes are not vector indexes
        Map<String, Object> btree = new HashMap<>();
        PostgreSQLMetaDataOperations.applyIndexMethod(btree, method("btree", "int4_ops", true, null));
        assertNull(btree.get(IndexMetaData.OPERATOR_CLASS));
        assertNull(btree.get(VectorIndex.DETAIL_KEY));
        Map<String, Object> gin = new HashMap<>();
        PostgreSQLMetaDataOperations.applyIndexMethod(gin, method("gin", "gin_trgm_ops", false, "fastupdate=off"));
        assertEquals("fastupdate=off", gin.get(IndexMetaData.INDEX_OPTIONS));
        assertNull(gin.get(VectorIndex.DETAIL_KEY));

        Map<String, String> mariadb = MariaDBMetaDataOperations.getVectorIndexes("CREATE TABLE `t` (\n"
                + "  `v` vector(3) NOT NULL,\n  `w` vector(3) NOT NULL,\n  `x` vector(3) NOT NULL,\n"
                + "  VECTOR KEY `ix_v` (`v`) `M`='8' `DISTANCE`='cosine',\n  VECTOR KEY `ix``w` (`w`),\n"
                + "  VECTOR KEY `ix_x` (`x`) `DISTANCE`='euclidean'\n) ENGINE=InnoDB");
        assertEquals("HNSW,COSINE,m=8", mariadb.get("ix_v"));
        assertEquals("HNSW,EUCLIDEAN,", mariadb.get("ix`w"));
        assertEquals("HNSW,EUCLIDEAN,", mariadb.get("ix_x"));
        assertTrue(MariaDBMetaDataOperations.getVectorIndexes(null).isEmpty());

        assertEquals(VectorType.dense(VectorType.Element.FLOAT32, 3), OracleMetaDataOperations.getVectorType(
                "VECTOR(3,FLOAT32,DENSE)"));
        assertEquals(new VectorType(VectorType.Element.FLOAT64, 8, true), OracleMetaDataOperations.getVectorType(
                "VECTOR(8,FLOAT64,SPARSE)"));
        assertEquals(VectorType.dense(VectorType.Element.INT8, 0), OracleMetaDataOperations.getVectorType(
                "VECTOR(*,INT8,DENSE)"));
        // A column of any format keeps the vectors of Oracle, it has no vector type
        assertNull(OracleMetaDataOperations.getVectorType("VECTOR(*,*,DENSE)"));
        assertNull(OracleMetaDataOperations.getVectorType(null));
        assertNull(OracleMetaDataOperations.getVectorType("VECTOR(3,FLOAT8,DENSE)"));

        assertArrayEquals(new String[]{"HNSW,COSINE,", "1024"}, ClickHouseMetaDataOperations.parseVectorIndex(
                "vector_similarity('hnsw', 'cosineDistance', 1024)"));
        assertArrayEquals(new String[]{"HNSW,EUCLIDEAN,m=16;ef_construction=64", "3"}, ClickHouseMetaDataOperations
                .parseVectorIndex("vector_similarity('hnsw', 'L2Distance', 3, 'bf16', 16, 64)"));
        assertArrayEquals(new String[]{"HNSW,COSINE,m=32", "8"}, ClickHouseMetaDataOperations.parseVectorIndex(
                "vector_similarity('hnsw', 'cosineDistance', 8, 'f32', 32)"));
        assertNull(ClickHouseMetaDataOperations.parseVectorIndex("vector_similarity('hnsw', 'dotProduct', 3)"));
        assertNull(ClickHouseMetaDataOperations.parseVectorIndex("minmax"));
        assertEquals(VectorType.dense(VectorType.Element.FLOAT32, 3), ClickHouseMetaDataOperations.getVectorType(
                "Array(Float32)", "3"));
        assertEquals(VectorType.dense(VectorType.Element.FLOAT16, 0), ClickHouseMetaDataOperations.getVectorType(
                "Array(BFloat16)", null));
        assertEquals(VectorType.dense(VectorType.Element.FLOAT64, 0), ClickHouseMetaDataOperations.getVectorType(
                "Array( Float64 )", null));
        assertNull(ClickHouseMetaDataOperations.getVectorType("Array(Int32)", null));

        assertEquals(VectorType.dense(VectorType.Element.FLOAT32, 3), DuckDBMetaDataOperations.getVectorType(
                "FLOAT[3]"));
        assertEquals(VectorType.dense(VectorType.Element.FLOAT64, 1024), DuckDBMetaDataOperations.getVectorType(
                "double[1024]"));
        // Lists of floats and arrays of integers are not vectors
        assertNull(DuckDBMetaDataOperations.getVectorType("FLOAT[]"));
        assertNull(DuckDBMetaDataOperations.getVectorType("INTEGER[3]"));
        assertNull(DuckDBMetaDataOperations.getVectorType(null));
    }

    @Test
    void probes() throws SQLException {
        List<String> rolledBack = new ArrayList<>();
        Connection transactional = connection(sql -> {
            throw new SQLException("no such function");
        }, false, rolledBack);
        // A failed probe rolls back the transaction which it aborted
        assertEquals(NONE, new MySQLDialect().readDatabaseFeatures(transactional));
        assertEquals(List.of("rollback"), rolledBack);
        // A rollback which fails, e.g. of a closed connection
        Connection closed = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    throw new SQLException("closed");
                });
        assertEquals(NONE, new MySQLDialect().readDatabaseFeatures(closed));
        assertEquals(NONE, new DuckDBDialect().readDatabaseFeatures(connection(sql -> {
            throw new IllegalStateException("broken driver");
        })));
    }

    private static Map<String, Object> method(String amName, String operatorClass, boolean defaultClass,
                                              String options) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("amname", amName);
        method.put("opcname", operatorClass);
        method.put("opcdefault", defaultClass);
        method.put("options", options);
        return method;
    }

    private static byte[] vectorBinary(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0xA9).put((byte) 1).putShort((short) values.length).put((byte) 0).put(new byte[3]);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    @FunctionalInterface
    interface Query {
        /**
         * Columns of the only row of a query, null for no row
         */
        Object[] execute(String sql) throws SQLException;
    }

    private static Connection connection(Query query) {
        return connection(query, true, new ArrayList<>());
    }

    /**
     * Connection whose statements answer queries by the function
     */
    private static Connection connection(Query query, boolean autoCommit, List<String> calls) {
        return (Connection) Proxy.newProxyInstance(VectorDialectTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "createStatement":
                            return statement(query);
                        case "getAutoCommit":
                            return autoCommit;
                        case "rollback":
                            calls.add("rollback");
                            return null;
                        default:
                            return null;
                    }
                });
    }

    private static Statement statement(Query query) {
        return (Statement) Proxy.newProxyInstance(VectorDialectTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        Object[] row = query.execute((String) args[0]);
                        return resultSet(row == null ? null : new Object[][]{row}, i -> null);
                    }
                    return null;
                });
    }

    private static ResultSet resultSet(byte[] value) {
        return resultSet(new Object[][]{{value}}, i -> value);
    }

    private static ResultSet resultSet(Object[][] rows, Function<Integer, Object> objects) {
        int[] position = {-1};
        ResultSetMetaData metaData = (ResultSetMetaData) Proxy.newProxyInstance(
                VectorDialectTest.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> "getColumnCount".equals(method.getName()) ? 1 : null);
        return (ResultSet) Proxy.newProxyInstance(VectorDialectTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            return rows != null && ++position[0] < rows.length;
                        case "getBoolean":
                            return Boolean.TRUE.equals(rows[position[0]][(Integer) args[0] - 1]);
                        case "getString":
                            Object value = rows[Math.max(position[0], 0)][(Integer) args[0] - 1];
                            return value != null ? value.toString() : null;
                        case "getBytes":
                        case "getObject":
                            return objects.apply((Integer) args[0]) != null ? objects.apply((Integer) args[0])
                                    : rows[Math.max(position[0], 0)][(Integer) args[0] - 1];
                        case "getMetaData":
                            return metaData;
                        case "wasNull":
                            return false;
                        default:
                            return null;
                    }
                });
    }
}
