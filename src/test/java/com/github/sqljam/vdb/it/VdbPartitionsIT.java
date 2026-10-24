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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbExportResult;
import com.github.sqljam.vdb.VdbExporter;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbPartitionsIT reads the levels and the parts of the vector databases which have them: databases of
 *               Milvus and Chroma, partitions of Milvus, tenants of Weaviate and namespaces of Pinecone. Parts are
 *               listed, counted, read and imported apart, all parts when a query names none.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbPartitionsIT {

    static final VdbServer[] SERVERS = {VdbServer.MILVUS, VdbServer.WEAVIATE, VdbServer.CHROMA, VdbServer.PINECONE};

    @BeforeAll
    static void createFixtures() throws Exception {
        for (VdbServer server : SERVERS) {
            if (server.isAvailable()) {
                server.createFixture();
                server.createPartitionedFixture();
            }
        }
    }

    @AfterAll
    static void dropFixtures() throws Exception {
        for (VdbServer server : SERVERS) {
            if (server.isAvailable()) {
                server.dropPartitionedFixture();
                server.dropFixture();
            }
        }
        if (ItDatabase.DUCKDB.isAvailable()) {
            try (Connection connection = ItDatabase.DUCKDB.getTargetConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS main.sjvt_parts");
            }
        }
    }

    static List<Object> numbers(VdbClient client, VdbCollection collection, VdbQuery query) {
        List<Object> numbers = new ArrayList<>();
        client.read(collection, query, 2, (records, last) -> {
            records.forEach(row -> numbers.add(((Number) row.get("n")).intValue()));
            return true;
        });
        numbers.sort(null);
        return numbers;
    }

    static VdbQuery partitions(String... partitions) {
        VdbQuery query = new VdbQuery();
        query.getPartitions().addAll(List.of(partitions));
        return query;
    }

    @Test
    void readsTenantsOfWeaviate() {
        VdbServer server = VdbServer.WEAVIATE;
        assumeTrue(server.isAvailable());
        try (VdbClient client = server.createClient()) {
            assertEquals(List.of("t1", "t2"), sorted(client.listPartitions("SjvtTenant")));
            assertEquals(List.of(), client.listPartitions(server.getCollection()));
            VdbCollection collection = client.describe("SjvtTenant");
            assertEquals(List.of("t1", "t2"), sorted(client.getPartitions(collection)));
            assertEquals(5, collection.getCount());
            assertEquals(List.of(1, 2, 3), numbers(client, collection, partitions("t1")));
            assertEquals(List.of(4, 5), numbers(client, collection, partitions("t2")));
            assertEquals(List.of(1, 2, 3, 4, 5), numbers(client, collection, VdbQuery.all()));
            assertEquals(List.of(), numbers(client, collection, partitions("missing")));
            assertEquals(2, client.count(collection, partitions("t2")));
            // Filters and searches of a tenant
            VdbQuery filtered = partitions("t1").where("n >= 2");
            assertEquals(List.of(2, 3), numbers(client, collection, filtered));
            assertEquals(2, client.count(collection, filtered));
            VdbQuery nearest = partitions("t2");
            nearest.setNearestField("embedding");
            nearest.setNearestVector(VdbServer.getDocuments().get(0).embedding);
            nearest.setLimit(5);
            assertEquals(List.of(4, 5), numbers(client, collection, nearest));
        }
    }

    @Test
    void readsPartitionsAndDatabasesOfMilvus() {
        VdbServer server = VdbServer.MILVUS;
        assumeTrue(server.isAvailable());
        try (VdbClient client = server.createClient()) {
            assertTrue(client.getDatabases().contains("default"), client.getDatabases().toString());
            assertEquals(List.of("_default", "p1", "p2"), sorted(client.listPartitions("sjvt_part")));
            VdbCollection collection = client.describe("sjvt_part");
            assertEquals(8, collection.getCount());
            assertEquals(List.of(1, 2, 3, 4), numbers(client, collection, partitions("p1")));
            assertEquals(4, client.count(collection, partitions("p2")));
            assertEquals(List.of(5, 6, 7, 8), numbers(client, collection, partitions("p2")));
            assertEquals(List.of(7, 8), numbers(client, collection, partitions("p2").where("n >= 7")));
            VdbQuery nearest = partitions("p1");
            nearest.setNearestField("embedding");
            nearest.setNearestVector(VdbServer.getDocuments().get(7).embedding);
            nearest.setLimit(8);
            assertEquals(List.of(1, 2, 3, 4), numbers(client, collection, nearest));
        }
        // A client of the database default reads the same collections
        VdbSource source = server.getSource().withDatabase("default");
        try (VdbClient client = VdbType.MILVUS.createClient(source)) {
            assertTrue(client.getCollections().contains("sjvt_part"));
        }
    }

    @Test
    void readsDatabasesOfChroma() {
        VdbServer server = VdbServer.CHROMA;
        assumeTrue(server.isAvailable());
        try (VdbClient client = server.createClient()) {
            List<String> databases = client.getDatabases();
            assertTrue(databases.contains("default_database") && databases.contains("sjvt_db2"),
                    databases.toString());
            assertTrue(client.getCollections().contains(server.getCollection()));
            assertTrue(!client.getCollections().contains("sjvt_doc2"));
        }
        try (VdbClient client = VdbType.CHROMA.createClient(server.getSource().withDatabase("sjvt_db2"))) {
            assertEquals(List.of("sjvt_doc2"), client.getCollections());
            VdbCollection collection = client.describe("sjvt_doc2");
            assertEquals(List.of(1, 2), numbers(client, collection, VdbQuery.all()));
            assertEquals(List.of(), client.getPartitions(collection));
        }
    }

    @Test
    void readsNamespacesOfPinecone() {
        VdbServer server = VdbServer.PINECONE;
        assumeTrue(server.isAvailable());
        try (VdbClient client = server.createClient()) {
            assertEquals(List.of("__default__", "ns1"), sorted(client.listPartitions(server.getCollection())));
            VdbCollection collection = client.describe(server.getCollection());
            assertEquals(List.of(1, 2), numbers(client, collection, partitions("__default__")));
            assertEquals(2, client.count(collection, partitions("__default__")));
            assertEquals(8, numbers(client, collection, partitions("ns1")).size());
            assertEquals(10, client.count(collection, VdbQuery.all()));
        }
    }

    /**
     * Rows of a tenant are imported with their tenant
     */
    @Test
    void importsTenant() throws Exception {
        VdbServer server = VdbServer.WEAVIATE;
        ItDatabase target = ItDatabase.DUCKDB;
        assumeTrue(server.isAvailable() && target.isAvailable());
        VdbExportConfiguration configuration = new VdbExportConfiguration();
        configuration.setTargetSchema(VdbImportIT.getSchema(target));
        configuration.addCollection("SjvtTenant", "sjvt_parts");
        configuration.setTableRecreated(true);
        configuration.setQuery(partitions("t2"));
        VdbExportResult result;
        try (VdbClient client = server.createClient()) {
            result = new VdbExporter(client, VdbImportIT.dataSource(target), configuration).export();
        }
        assertEquals(2, result.getWritten());
        assertEquals(2, result.getTables().get(0).getTotal());
        try (Connection connection = target.getTargetConnection(); Statement statement = connection
                .createStatement(); ResultSet rs = statement.executeQuery("SELECT tenant, COUNT(*) FROM "
                + VdbDialect.of(connection).getQualifiedTableName("main", "sjvt_parts") + " GROUP BY tenant")) {
            assertTrue(rs.next());
            assertEquals("t2", rs.getString(1));
            assertEquals(2, rs.getInt(2));
        }
    }

    private static List<String> sorted(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        copy.sort(null);
        return copy;
    }
}
