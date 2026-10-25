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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.utils.RestException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbExportListener;
import com.github.sqljam.vdb.VdbExportResult;
import com.github.sqljam.vdb.VdbExporter;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbBoundaryIT tests the boundaries of the vdb line: wrong and read-only keys of vector databases,
 *               unreachable servers and missing collections, native filters of each database, a target without
 *               vectors (PostgreSQL without pgvector), a target user who may not create tables, rows which the target
 *               refuses (skipped or failing the import) and a cancelled import
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbBoundaryIT {

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
        if (ItDatabase.POSTGRESQL.isAvailable()) {
            VdbImportIT.dropTables(ItDatabase.POSTGRESQL);
        }
    }

    @ParameterizedTest
    @EnumSource(value = VdbServer.class, names = {"QDRANT", "WEAVIATE", "ELASTICSEARCH", "OPENSEARCH", "CHROMA",
            "MILVUS"})
    void refusesWrongCredentials(VdbServer server) {
        assumeTrue(server.isAvailable());
        VdbSource source = server.getSource(server.getSetting("apiKey") != null ? "wrong-key" : null);
        if (source.getUsername() != null) {
            source.setPassword("wrong-password");
        }
        try (VdbClient client = server.getType().createClient(source)) {
            // Milvus answers HTTP 200 with an error code, which is 401 too
            RestException e = assertThrows(RestException.class, client::getCollections);
            assertTrue(e.isAccessDenied(), e.toString());
            assertTrue(e.getMessage().contains("are refused"), e.getMessage());
        }
    }

    /**
     * Without an API key and a user (or with blank ones) no credentials are sent: a server which requires them
     * refuses with a hint, a server without authentication (Pinecone Local) is read
     */
    @ParameterizedTest
    @EnumSource(VdbServer.class)
    void reportsMissingCredentials(VdbServer server) {
        assumeTrue(server.isAvailable());
        for (String blank : new String[]{null, "", "  "}) {
            VdbSource source = server.getSource(blank);
            source.setUsername(blank);
            source.setPassword(blank);
            assertFalse(source.hasCredentials());
            try (VdbClient client = server.getType().createClient(source)) {
                if (server == VdbServer.PINECONE) {
                    assertTrue(client.getCollections().contains(server.getCollection()));
                    continue;
                }
                RestException e = assertThrows(RestException.class, client::getCollections);
                assertTrue(e.isAccessDenied(), e.toString());
                assertTrue(e.getMessage().contains("no API key or user is given"), e.getMessage());
            }
        }
    }

    @Test
    void readsWithReadOnlyKey() {
        VdbServer server = VdbServer.QDRANT;
        assumeTrue(server.isAvailable() && server.getSetting("readonlyKey") != null);
        try (VdbClient client = server.getType().createClient(server.getSource(server.getSetting("readonlyKey")))) {
            VdbCollection collection = client.describe(server.getCollection());
            assertEquals(VdbServer.RECORDS, collection.getCount());
            List<Map<String, Object>> rows = new ArrayList<>();
            client.read(collection, VdbQuery.all(), 100, (records, last) -> rows.addAll(records));
            assertEquals(VdbServer.RECORDS, rows.size());
        }
    }

    @ParameterizedTest
    @EnumSource(VdbType.class)
    void reportsUnreachableServer(VdbType type) {
        VdbSource source = new VdbSource(type, "http://127.0.0.1:1");
        source.setApiKey("key");
        source.setTimeoutSeconds(5);
        try (VdbClient client = type.createClient(source)) {
            RestException e = assertThrows(RestException.class, client::getCollections);
            assertEquals(0, e.getStatus());
        }
    }

    @ParameterizedTest
    @EnumSource(VdbServer.class)
    void reportsMissingCollection(VdbServer server) {
        assumeTrue(server.isAvailable());
        try (VdbClient client = server.createClient()) {
            assertThrows(RuntimeException.class, () -> client.describe("sjvt_missing"));
        }
    }

    /**
     * Native filters of n >= 6 in the language of each database
     */
    @ParameterizedTest
    @EnumSource(VdbServer.class)
    void filtersNatively(VdbServer server) {
        assumeTrue(server.isAvailable());
        String filter;
        switch (server) {
            case MILVUS:
                filter = "n >= 6";
                break;
            case QDRANT:
                filter = "{\"must\": [{\"key\": \"n\", \"range\": {\"gte\": 6}}]}";
                break;
            case WEAVIATE:
                filter = "{ path: [\"n\"], operator: GreaterThanEqual, valueInt: 6 }";
                break;
            case ELASTICSEARCH:
            case OPENSEARCH:
                filter = "{\"range\": {\"n\": {\"gte\": 6}}}";
                break;
            default:
                filter = "{\"n\": {\"$gte\": 6}}";
                break;
        }
        try (VdbClient client = server.createClient()) {
            VdbCollection collection = client.describe(server.getCollection());
            VdbQuery query = new VdbQuery();
            query.setNativeFilter(filter);
            List<Object> numbers = new ArrayList<>();
            if (server == VdbServer.PINECONE) {
                // Pinecone filters the metadata of searches only
                assertThrows(VdbException.class, () -> client.read(collection, query, 10, (records, last) -> true));
                query.setNearestField("values");
                query.setNearestVector(VdbServer.getDocuments().get(0).embedding);
                query.setLimit(10);
            }
            client.read(collection, query, 2, (records, last) -> {
                records.forEach(row -> numbers.add(((Number) row.get("n")).intValue()));
                return true;
            });
            numbers.sort(null);
            assertEquals(List.of(6, 7, 8), numbers, server.toString());
        }
    }

    @Test
    void stopsReadingWhenConsumerStops() {
        VdbServer server = VdbServer.QDRANT;
        assumeTrue(server.isAvailable());
        try (VdbClient client = server.createClient()) {
            VdbCollection collection = client.describe(server.getCollection());
            int[] pages = {0};
            client.read(collection, VdbQuery.all(), 2, (records, last) -> ++pages[0] < 2);
            assertEquals(2, pages[0]);
        }
    }

    @Test
    void refusesTargetWithoutVectors() throws Exception {
        assumeTrue(ItDatabase.POSTGRESQL.isAvailable() && VdbServer.QDRANT.isAvailable());
        String url = ItDatabase.POSTGRESQL.getUrl("sjit_novector");
        try (Connection connection = DriverManager.getConnection(url, ItDatabase.POSTGRESQL.getUsername(),
                ItDatabase.POSTGRESQL.getPassword())) {
            VdbDialect dialect = VdbDialect.of(connection);
            assertNotNull(dialect);
            assertFalse(dialect.isVectorSupported());
            VdbException e = assertThrows(VdbException.class, () -> VdbExporter.getDialect(connection));
            assertTrue(e.getMessage().contains("has no vectors"), e.getMessage());
        } catch (SQLException e) {
            assumeTrue(false, "No database sjit_novector: " + e.getMessage());
        }
    }

    @Test
    void reportsTargetUserWithoutPrivileges() throws Exception {
        VdbServer server = VdbServer.QDRANT;
        assumeTrue(ItDatabase.POSTGRESQL.isAvailable() && server.isAvailable());
        VdbExportConfiguration configuration = VdbImportIT.configuration(server, ItDatabase.POSTGRESQL);
        String url = ItDatabase.POSTGRESQL.getTargetUrl();
        try (VdbClient client = server.createClient()) {
            // The reader may read the schema of the targets but not create tables there
            VdbExporter reader = new VdbExporter(client, new SimpleDataSource(url, "sjit_reader", "sjit_reader123"),
                    configuration);
            SQLException e = assertThrows(SQLException.class, reader::export);
            String message = e.getMessage().toLowerCase(Locale.ENGLISH);
            assertTrue(message.contains("denied") || message.contains("permission") || message.contains("owner"),
                    e.getMessage());
        }
    }

    @Test
    void skipsRowsWhichTargetRefuses() throws Exception {
        VdbServer server = VdbServer.QDRANT;
        ItDatabase target = ItDatabase.POSTGRESQL;
        assumeTrue(target.isAvailable() && server.isAvailable());
        VdbExportConfiguration configuration = VdbImportIT.configuration(server, target);
        configuration.getMappings().set(0, new VdbExportConfiguration.Mapping(server.getCollection(),
                server.getTableName() + "_e"));
        VdbImportIT.export(server, target, configuration);
        String table = "\"" + VdbImportIT.getSchema(target) + "\".\"" + server.getTableName() + "_e\"";
        try (Connection connection = target.getTargetConnection(); Statement statement = connection
                .createStatement()) {
            statement.execute("DELETE FROM " + table);
            statement.execute("ALTER TABLE " + table + " ADD CONSTRAINT sjvt_n CHECK (n < 8)");
        }
        configuration.setTableRecreated(false);
        List<String> skipped = new ArrayList<>();
        VdbExportResult result;
        try (VdbClient client = server.createClient()) {
            result = new VdbExporter(client, VdbImportIT.dataSource(target), configuration, new VdbExportListener() {
                @Override
                public void onRowSkipped(VdbExportResult.TableResult table, String key, Exception e) {
                    skipped.add(key);
                }
            }).export();
        }
        VdbExportResult.TableResult tableResult = result.getTables().get(0);
        assertEquals(VdbServer.RECORDS - 1, tableResult.getWritten());
        assertEquals(1, tableResult.getSkipped());
        assertEquals(List.of("id=8"), skipped);

        // Without skipping the import fails, rows of the failed page are rolled back
        try (Connection connection = target.getTargetConnection(); Statement statement = connection
                .createStatement()) {
            statement.execute("DELETE FROM " + table);
        }
        configuration.setErrorSkipped(false);
        assertThrows(SQLException.class, () -> VdbImportIT.export(server, target, configuration));
        try (Connection connection = target.getTargetConnection()) {
            // Pages of 3 rows before the page of the refused row are committed, that page is rolled back
            long written = countTable(connection, table);
            assertTrue(written < VdbServer.RECORDS && written % 3 == 0, String.valueOf(written));
        }

        // Rows written one by one, each committed by itself
        try (Connection connection = target.getTargetConnection(); Statement statement = connection
                .createStatement()) {
            statement.execute("DELETE FROM " + table);
        }
        configuration.setErrorSkipped(true);
        configuration.setTransactional(false);
        assertEquals(VdbServer.RECORDS - 1, VdbImportIT.export(server, target, configuration).getWritten());
    }

    private static long countTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void cancelsImport() throws Exception {
        VdbServer server = VdbServer.ELASTICSEARCH;
        ItDatabase target = ItDatabase.DUCKDB;
        assumeTrue(target.isAvailable() && server.isAvailable());
        VdbExportConfiguration configuration = VdbImportIT.configuration(server, target);
        configuration.setPageSize(2);
        try (VdbClient client = server.createClient()) {
            VdbExporter[] exporter = new VdbExporter[1];
            exporter[0] = new VdbExporter(client, VdbImportIT.dataSource(target), configuration,
                    new VdbExportListener() {
                        @Override
                        public void onRowsWritten(VdbExportResult.TableResult table) {
                            exporter[0].cancel();
                        }
                    });
            VdbExportResult result = exporter[0].export();
            assertTrue(result.isCancelled());
            assertEquals(2, result.getWritten());
            List<String> scripts = exporter[0].getScripts();
            assertTrue(scripts.get(0).startsWith("DROP TABLE"), scripts.get(0));
        } finally {
            VdbImportIT.dropTables(target);
        }
    }

    /**
     * Connections of a user of the url
     */
    static final class SimpleDataSource extends AbstractDataSource {

        private final String url;
        private final String username;
        private final String password;

        SimpleDataSource(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
    }
}
