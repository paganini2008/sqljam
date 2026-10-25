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
package com.github.sqljam.vdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbExportResult;
import com.github.sqljam.vdb.VdbExporter;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbImportIT imports the fixture collection of each vector database into each target database with
 *               vectors: the rows, the nearest neighbors of the imported vectors (the same as of the vector database),
 *               upserts of existing rows, and the rows of conditions and of a search
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbImportIT {

    static final ItDatabase[] TARGETS = {ItDatabase.POSTGRESQL, ItDatabase.MYSQL, ItDatabase.MARIADB,
            ItDatabase.ORACLE, ItDatabase.SQLSERVER, ItDatabase.DB2, ItDatabase.DUCKDB, ItDatabase.CLICKHOUSE};
    /**
     * Documents nearest to the first one by the cosine distance of bge-m3, as each vector database finds them
     */
    static final List<Integer> NEAREST = List.of(1, 5, 2);

    @BeforeAll
    static void createFixtures() throws Exception {
        for (VdbServer server : VdbServer.values()) {
            if (server.isAvailable()) {
                server.createFixture();
            }
        }
    }

    @AfterAll
    static void dropFixtures() throws Exception {
        for (VdbServer server : VdbServer.values()) {
            if (server.isAvailable()) {
                server.dropFixture();
            }
        }
        for (ItDatabase target : TARGETS) {
            if (target.isAvailable()) {
                dropTables(target);
            }
        }
    }

    static Stream<Arguments> pairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (VdbServer server : VdbServer.values()) {
            for (ItDatabase target : TARGETS) {
                pairs.add(Arguments.of(server, target));
            }
        }
        return pairs.stream();
    }

    /**
     * Schema of the target tables, the database of MySQL and MariaDB
     */
    public static String getSchema(ItDatabase target) {
        return target.getTargetSchema() != null ? target.getTargetSchema() : target.getTargetCatalog();
    }

    public static DataSource dataSource(ItDatabase target) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return target.getTargetConnection();
            }
        };
    }

    static VdbExportConfiguration configuration(VdbServer server, ItDatabase target) {
        VdbExportConfiguration configuration = new VdbExportConfiguration();
        configuration.setTargetSchema(getSchema(target));
        configuration.addCollection(server.getCollection(), server.getTableName());
        configuration.setTableRecreated(true);
        configuration.setPageSize(3);
        configuration.setErrorSkipped(true);
        return configuration;
    }

    static VdbExportResult.TableResult export(VdbServer server, ItDatabase target,
                                              VdbExportConfiguration configuration) throws SQLException {
        try (VdbClient client = server.createClient()) {
            return new VdbExporter(client, dataSource(target), configuration).export().getTables().get(0);
        }
    }

    static void assume(VdbServer server, ItDatabase target) {
        assumeTrue(server.isAvailable(), server + " is not available");
        assumeTrue(target.isAvailable(), target + " is not available");
    }

    /**
     * Records of the fixture, Pinecone has 2 more in its default namespace
     */
    static int getRecords(VdbServer server) {
        return server == VdbServer.PINECONE ? VdbServer.RECORDS + 2 : VdbServer.RECORDS;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("pairs")
    void importsCollection(VdbServer server, ItDatabase target) throws Exception {
        assume(server, target);
        VdbExportResult.TableResult result = export(server, target, configuration(server, target));
        int records = getRecords(server);
        assertEquals(records, result.getRead());
        assertEquals(records, result.getWritten());
        assertEquals(0, result.getSkipped(), String.valueOf(result.getWarnings()));
        assertTrue(result.isCreated());
        try (Connection connection = target.getTargetConnection()) {
            VdbDialect dialect = VdbDialect.of(connection);
            assertEquals(records, count(connection, dialect, target, server, null));
            List<Integer> nearest = nearest(connection, dialect, target, server);
            if (nearest != null) {
                assertEquals(NEAREST, nearest, server + " into " + target);
            }
        }
        // Rows of the same keys are updated, ClickHouse has no upserts
        VdbExportConfiguration upsert = configuration(server, target);
        upsert.setTableRecreated(false);
        upsert.setWriteMode(VdbExportConfiguration.WriteMode.UPSERT);
        if (target == ItDatabase.CLICKHOUSE) {
            assertThrows(VdbException.class, () -> export(server, target, upsert));
        } else {
            VdbExportResult.TableResult updated = export(server, target, upsert);
            assertEquals(records, updated.getWritten());
            assertEquals(0, updated.getSkipped(), String.valueOf(updated.getWarnings()));
            try (Connection connection = target.getTargetConnection()) {
                assertEquals(records, count(connection, VdbDialect.of(connection), target, server, null));
            }
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("pairs")
    void importsQuery(VdbServer server, ItDatabase target) throws Exception {
        assume(server, target);
        try (VdbClient client = server.createClient()) {
            VdbCollection collection = client.describe(server.getCollection());
            VdbQuery query = VdbQuery.all().where("n >= 6");
            long expected = 3;
            // Counts of filters which the database can not count are -1
            long count = client.count(collection, query);
            assertTrue(count == expected || count == -1, String.valueOf(count));
            VdbExportConfiguration configuration = configuration(server, target);
            configuration.setQuery(query);
            configuration.getMappings().set(0, new VdbExportConfiguration.Mapping(server.getCollection(),
                    server.getTableName() + "_q"));
            VdbExportResult.TableResult result = export(server, target, configuration);
            assertEquals(expected, result.getWritten());

            VdbQuery search = new VdbQuery();
            search.setNearestField(collection.getVectorFields().get(0).getName());
            search.setNearestVector(VdbServer.getDocuments().get(0).embedding);
            search.setLimit(3);
            configuration.setQuery(search);
            result = export(server, target, configuration);
            assertEquals(3, result.getWritten());
        }
    }

    static long count(Connection connection, VdbDialect dialect, ItDatabase target, VdbServer server, String where)
            throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM " + dialect.getQualifiedTableName(getSchema(target), server.getTableName())
                        + (where != null ? " WHERE " + where : ""))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Numbers of the documents nearest to the first one by the SQL of the target, null if the target has no
     * distance of vectors (MySQL Community)
     */
    static List<Integer> nearest(Connection connection, VdbDialect dialect, ItDatabase target, VdbServer server)
            throws SQLException {
        String table = dialect.getQualifiedTableName(getSchema(target), server.getTableName());
        String vector = dialect.getIdentifier(server == VdbServer.PINECONE ? "values" : "embedding");
        String n = dialect.getIdentifier("n");
        String filter = server == VdbServer.PINECONE ? " AND " + dialect.getIdentifier("namespace") + " = 'ns1'"
                : "";
        String first = String.format("(SELECT %s FROM %s WHERE %s = 1%s)", vector, table, n, filter);
        String where = filter.isEmpty() ? "" : " WHERE " + filter.substring(5);
        String sql;
        DbType type = target.getDbType();
        switch (type) {
            case POSTGRESQL:
                sql = String.format("SELECT %s FROM %s%s ORDER BY %s <=> %s LIMIT 3", n, table, where, vector, first);
                break;
            case MARIADB:
                sql = String.format("SELECT %s FROM %s%s ORDER BY VEC_DISTANCE_COSINE(%s, %s) LIMIT 3", n, table,
                        where, vector, first);
                break;
            case ORACLE:
            case DB2:
                sql = String.format("SELECT %s FROM %s%s ORDER BY VECTOR_DISTANCE(%s, %s, COSINE) FETCH FIRST 3 ROWS"
                        + " ONLY", n, table, where, vector, first);
                break;
            case SQLSERVER:
                sql = String.format("SELECT TOP 3 %s FROM %s%s ORDER BY VECTOR_DISTANCE('cosine', %s, %s)", n, table,
                        where, vector, first);
                break;
            case DUCKDB:
                sql = String.format("SELECT %s FROM %s%s ORDER BY array_cosine_distance(%s, %s) LIMIT 3", n, table,
                        where, vector, first);
                break;
            case CLICKHOUSE:
                sql = String.format("SELECT %s FROM %s%s ORDER BY cosineDistance(%s, %s) LIMIT 3", n, table, where,
                        vector, first);
                break;
            default:
                return null;
        }
        List<Integer> numbers = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                numbers.add(rs.getInt(1));
            }
        }
        return numbers;
    }

    /**
     * Drops the tables of the tests (sjvt_*) of a target
     */
    static void dropTables(ItDatabase target) throws SQLException {
        try (Connection connection = target.getTargetConnection()) {
            VdbDialect dialect = VdbDialect.of(connection);
            for (VdbServer server : VdbServer.values()) {
                for (String table : new String[]{server.getTableName(), server.getTableName() + "_q",
                        server.getTableName() + "_e"}) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(dialect.getDropTableStatement(getSchema(target), table));
                    }
                }
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
    }
}
