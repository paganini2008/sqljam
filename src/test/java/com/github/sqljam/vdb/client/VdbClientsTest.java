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
package com.github.sqljam.vdb.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.utils.RestException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: VdbClientsTest tests the clients of the vector databases with a fake server and their mappings
 *               without one: types of fields, vectors of other elements (16-bit floats, int8, bits, multivectors),
 *               indexes and distances, filters of conditions, tenants and namespaces, errors of the servers
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbClientsTest {

    static JsonNode json(String text) throws Exception {
        return RestClient.getMapper().readTree(text);
    }

    static VdbSource source(VdbType type, FakeServer server) {
        VdbSource source = new VdbSource(type, server.getUrl());
        source.setApiKey("key");
        source.setTimeoutSeconds(5);
        return source;
    }

    static List<Map<String, Object>> readAll(VdbClient client, VdbCollection collection, VdbQuery query,
                                             int pageSize) {
        List<Map<String, Object>> rows = new ArrayList<>();
        client.read(collection, query, pageSize, (records, last) -> {
            rows.addAll(records);
            return true;
        });
        return rows;
    }

    static VdbQuery allOperators() {
        return VdbQuery.all().where("a = 1 and b != 'x' and c > 1.5 and d >= 2 and e < 3 and f <= 4 and g IN (1, 2)");
    }

    // ------------------------------------------------------------------
    // Milvus
    // ------------------------------------------------------------------

    @Test
    void mapsMilvusFields() throws Exception {
        String[] types = {"Bool", "Int8", "Int16", "Int32", "Int64", "Float", "Double", "String", "Text",
                "Timestamptz", "JSON", "Geometry", "Struct", "Int8Vector", "BinaryVector", "BFloat16Vector",
                "Function"};
        VdbField.Kind[] kinds = {VdbField.Kind.BOOLEAN, VdbField.Kind.SMALLINT, VdbField.Kind.SMALLINT,
                VdbField.Kind.INTEGER, VdbField.Kind.BIGINT, VdbField.Kind.REAL, VdbField.Kind.DOUBLE,
                VdbField.Kind.TEXT, VdbField.Kind.TEXT, VdbField.Kind.TIMESTAMP, VdbField.Kind.JSON,
                VdbField.Kind.JSON, VdbField.Kind.JSON, VdbField.Kind.VECTOR, VdbField.Kind.VECTOR,
                VdbField.Kind.VECTOR, VdbField.Kind.TEXT};
        for (int i = 0; i < types.length; i++) {
            VdbField field = MilvusClient.toField(json("{\"name\": \"f\", \"type\": \"" + types[i]
                    + "\", \"params\": [{\"key\": \"dim\", \"value\": \"16\"}], \"description\": \"d\","
                    + " \"nullable\": true}"));
            assertEquals(kinds[i], field.getKind(), types[i]);
            assertTrue(field.isNullable());
            assertEquals("d", field.getRemarks());
        }
        VdbField text = MilvusClient.toField(json("{\"name\": \"t\", \"type\": \"VarChar\", \"params\": [{\"key\":"
                + " \"max_length\", \"value\": \"65535\"}], \"primaryKey\": true}"));
        assertEquals(VdbField.Kind.TEXT, text.getKind());
        assertTrue(text.isPrimaryKey());
        assertFalse(text.isNullable());

        VectorIndex hnsw = MilvusClient.toVectorIndex(json("{\"indexType\": \"DISKANN\", \"metricType\": \"IP\","
                + " \"indexParams\": [{\"key\": \"params\", \"value\": \"{\\\"M\\\": 8, \\\"efConstruction\\\": 32}\"},"
                + " {\"key\": \"params\", \"value\": \"not json\"}]}"));
        assertEquals(VectorIndex.Kind.HNSW, hnsw.getKind());
        assertEquals(VectorIndex.Distance.DOT, hnsw.getDistance());
        assertEquals("8", hnsw.getOption("m"));
        VectorIndex ivf = MilvusClient.toVectorIndex(json("{\"indexType\": \"SCANN\", \"metricType\": \"HAMMING\"}"));
        assertEquals(VectorIndex.Kind.IVF, ivf.getKind());
        assertEquals(VectorIndex.Distance.HAMMING, ivf.getDistance());
        assertEquals(VectorIndex.Distance.JACCARD, MilvusClient.toDistance("jaccard"));
        assertNull(MilvusClient.toVectorIndex(json("{\"indexType\": \"SPARSE_INVERTED_INDEX\", \"metricType\":"
                + " \"IP\"}")));
        assertNull(MilvusClient.toVectorIndex(json("{\"indexType\": \"HNSW\", \"metricType\": \"BM25\"}")));
    }

    static VdbField milvusVector(String type) throws Exception {
        return MilvusClient.toField(json("{\"name\": \"v\", \"type\": \"" + type + "\", \"params\": [{\"key\":"
                + " \"dim\", \"value\": \"2\"}]}"));
    }

    @Test
    void readsMilvusValues() throws Exception {
        // 1.0 and -2.0 as 16-bit floats, bfloat16, int8 and the bits of a byte
        assertEquals("[1.0,-2.0]", MilvusClient.toVectorText(json("\"ADwAwA==\""), milvusVector("Float16Vector")));
        assertEquals("[1.0,-2.0]", MilvusClient.toVectorText(json("\"gD8AwA==\""), milvusVector("BFloat16Vector")));
        assertEquals("[1.0,-2.0]", MilvusClient.toVectorText(json("[1, -2]"), milvusVector("Float16Vector")));
        assertEquals("[1.0,-2.0]", MilvusClient.toVectorText(json("[1, -2]"), milvusVector("BFloat16Vector")));
        assertEquals("[1.0,-2.0]", MilvusClient.toVectorText(json("\"Af4=\""), milvusVector("Int8Vector")));
        assertEquals("[1.0,-2.0]", MilvusClient.toVectorText(json("[1, -2]"), milvusVector("Int8Vector")));
        assertEquals("[170]", MilvusClient.toVectorText(json("\"qg==\""), milvusVector("BinaryVector")));
        assertEquals("[1]", MilvusClient.toVectorText(json("[1]"), milvusVector("BinaryVector")));
        assertEquals("{1:0.5,5:1.0}/5", MilvusClient.toVectorText(json("{\"0\": 0.5, \"4\": 1}"),
                milvusVector("SparseFloatVector")));
        assertNull(MilvusClient.toVectorText(json("null"), milvusVector("FloatVector")));
        assertNull(MilvusClient.toVectorText(null, milvusVector("FloatVector")));

        VdbField json = MilvusClient.toField(json("{\"name\": \"j\", \"type\": \"JSON\"}"));
        assertEquals(json("{\"a\": 1}"), MilvusClient.normalize(json("\"{\\\"a\\\": 1}\""), json));
        assertEquals(json("\"{bad\""), MilvusClient.normalize(json("\"{bad\""), json));
        VdbField array = MilvusClient.toField(json("{\"name\": \"a\", \"type\": \"Array\"}"));
        assertEquals(json("[1, 2]"), MilvusClient.normalize(json("{\"Data\": {\"LongData\": {\"data\": [1, 2]}}}"),
                array));
        assertEquals(json("[]"), MilvusClient.normalize(json("{\"Data\": {\"LongData\": {}}}"), array));
        assertNull(MilvusClient.normalize(null, array));

        VdbCollection collection = new VdbCollection("c");
        collection.addField(new VdbField("a", VdbField.Kind.BIGINT));
        assertEquals("a == 1 and b != \"x\" and c > 1.5 and d >= 2 and e < 3 and f <= 4 and g in [1, 2]",
                MilvusClient.toFilter(collection, allOperators()));
        assertEquals("\"a\\\\\\\"b\"", MilvusClient.literal("a\\\"b"));
        VdbQuery query = new VdbQuery();
        query.setNativeFilter(" id > 3 ");
        assertEquals("id > 3", MilvusClient.toFilter(collection, query));
    }

    @Test
    void readsMilvusOverRest() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("GET /v2/vectordb/server/version", 404, "{}")
                    .route("POST /v2/vectordb/collections/list", "{\"code\": 0, \"data\": [\"docs\", \"bad\"]}")
                    .route("POST /v2/vectordb/collections/describe", body -> body.contains("\"bad\"")
                            ? "{\"code\": 0, \"data\": {\"fields\": [{\"name\": \"x\", \"type\": \"Int64\"}]}}"
                            : "{\"code\": 0, \"data\": {\"description\": \"Docs\", \"enableDynamicField\": true,"
                            + " \"fields\": [{\"name\": \"pk\", \"type\": \"VarChar\", \"primaryKey\": true,"
                            + " \"params\": [{\"key\": \"max_length\", \"value\": \"64\"}]},"
                            + " {\"name\": \"v\", \"type\": \"FloatVector\", \"params\": [{\"key\": \"dim\","
                            + " \"value\": \"2\"}]}]}}")
                    .route("POST /v2/vectordb/indexes/list", "{\"code\": 0, \"data\": [\"v_idx\"]}")
                    .route("POST /v2/vectordb/indexes/describe", "{\"code\": 0, \"data\": [{\"fieldName\": \"v\","
                            + " \"indexType\": \"HNSW\", \"metricType\": \"L2\"}]}")
                    .route("POST /v2/vectordb/entities/query", body -> body.contains("count(*)")
                            ? "{\"code\": 0, \"data\": [{\"count(*)\": 3}]}"
                            : body.contains("pk > \\\"b\\\"") ? "{\"code\": 0, \"data\": [{\"pk\": \"c\", \"v\": [5, 6]}]}"
                            : "{\"code\": 0, \"data\": [{\"pk\": \"b\", \"v\": [3, 4], \"dyn\": 1, \"distance\": 0},"
                            + " {\"pk\": \"a\", \"v\": [1, 2]}]}")
                    .route("POST /v2/vectordb/entities/get", body -> body.contains("missing")
                            ? "{\"code\": 0, \"data\": []}" : "{\"code\": 0, \"data\": [{\"pk\": \"a\", \"v\": [1, 2]}]}")
                    .route("POST /v2/vectordb/entities/search", "{\"code\": 0, \"data\": [{\"pk\": \"a\","
                            + " \"v\": [1, 2], \"distance\": 0}]}")
                    .route("POST /v2/vectordb/collections/fail", "{\"code\": 1800, \"message\": \"denied\"}");
            VdbSource source = source(VdbType.MILVUS, server);
            source.setApiKey(null);
            source.setUsername("u");
            source.setPassword("p");
            source.setDatabase("db1");
            try (VdbClient client = VdbType.MILVUS.createClient(source)) {
                assertEquals("", client.getVersion());
                assertEquals(List.of("docs", "bad"), client.getCollections());
                assertEquals("Bearer u:p", server.requests("POST /v2/vectordb/collections/list").get(0)
                        .authorization);
                assertTrue(server.requests("POST /v2/vectordb/collections/list").get(0).body.contains("db1"));
                VdbException e = assertThrows(VdbException.class, () -> client.describe("bad"));
                assertTrue(e.getMessage().contains("no primary key"));
                VdbCollection collection = client.describe("docs");
                // A server without the route of partitions reads the collection whole
                assertEquals(List.of(), client.getPartitions(collection));
                assertEquals(3, collection.getCount());
                assertEquals("Docs", collection.getRemarks());
                assertEquals(VectorIndex.Distance.EUCLIDEAN, collection.findField("v").get().getVectorIndex()
                        .getDistance());
                // Pages of text keys follow the last key in quotes, rows are sorted by the key
                List<Map<String, Object>> rows = readAll(client, collection, VdbQuery.all(), 2);
                assertEquals(List.of("a", "b", "c"), List.of(rows.get(0).get("pk"), rows.get(1).get("pk"),
                        rows.get(2).get("pk")));
                assertEquals("{\"dyn\":1}", rows.get(1).get("payload"));
                VdbQuery nearest = new VdbQuery();
                nearest.setNearestField("v");
                nearest.setNearestId("a");
                nearest.setLimit(1);
                assertEquals(1, client.count(collection, nearest));
                assertEquals(1, readAll(client, collection, nearest, 10).size());
                nearest.setNearestId("missing");
                assertThrows(VdbException.class, () -> readAll(client, collection, nearest, 10));
                nearest.setNearestId(null);
                nearest.setNearestVector(new float[]{1, 2});
                nearest.where("pk = 'a'");
                assertEquals(1, readAll(client, collection, nearest, 10).size());
            }
            source.setUsername(null);
            source.setApiKey("token");
            try (MilvusClient client = new MilvusClient(source)) {
                VdbException e = assertThrows(VdbException.class, () -> client.describe("fail".replace("fail",
                        "bad")));
                assertTrue(e.getMessage().contains("Milvus"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Weaviate
    // ------------------------------------------------------------------

    @Test
    void mapsWeaviateFields() throws Exception {
        String[] types = {"text", "string", "int", "number", "boolean", "date", "uuid", "blob", "text[]",
                "object", "geoCoordinates", "phoneNumber", "Other"};
        VdbField.Kind[] kinds = {VdbField.Kind.TEXT, VdbField.Kind.TEXT, VdbField.Kind.BIGINT, VdbField.Kind.DOUBLE,
                VdbField.Kind.BOOLEAN, VdbField.Kind.TIMESTAMP, VdbField.Kind.VARCHAR, VdbField.Kind.BINARY,
                VdbField.Kind.JSON, VdbField.Kind.JSON, VdbField.Kind.JSON, VdbField.Kind.JSON, VdbField.Kind.JSON};
        for (int i = 0; i < types.length; i++) {
            assertEquals(kinds[i], WeaviateClient.toField("f", json("{\"dataType\": [\"" + types[i] + "\"]}"))
                    .getKind(), types[i]);
        }
        assertEquals("o { a b { c } }", WeaviateClient.selectionOf(json("{\"name\": \"o\", \"dataType\":"
                + " [\"object\"], \"nestedProperties\": [{\"name\": \"a\", \"dataType\": [\"text\"]}, {\"name\": \"b\","
                + " \"dataType\": [\"object\"], \"nestedProperties\": [{\"name\": \"c\", \"dataType\": [\"int\"]}]}]}")));
        assertEquals("g { latitude longitude }", WeaviateClient.selectionOf(json("{\"name\": \"g\", \"dataType\":"
                + " [\"geoCoordinates\"]}")));
        assertTrue(WeaviateClient.selectionOf(json("{\"name\": \"p\", \"dataType\": [\"phoneNumber\"]}"))
                .startsWith("p { input"));
        assertEquals("r { ... on Other { _additional { id } } }", WeaviateClient.selectionOf(json("{\"name\": \"r\","
                + " \"dataType\": [\"Other\"]}")));
        assertEquals("e", WeaviateClient.selectionOf(json("{\"name\": \"e\", \"dataType\": []}")));

        VdbField multivector = WeaviateClient.toVectorField("m", "hnsw", json("{\"multivector\": {\"enabled\":"
                + " true}}"), 4);
        assertEquals(VdbField.Kind.JSON, multivector.getKind());
        assertNull(WeaviateClient.toVectorField("f", "flat", json("{\"distance\": \"cosine\"}"), 4).getVectorIndex());
        VectorIndex dynamic = WeaviateClient.toVectorField("d", "dynamic", json("{\"distance\": \"dot\", \"hnsw\":"
                + " {\"maxConnections\": 8, \"efConstruction\": 32}}"), 4).getVectorIndex();
        assertEquals(VectorIndex.Distance.DOT, dynamic.getDistance());
        assertEquals("8", dynamic.getOption("m"));
        assertEquals(VectorIndex.Distance.MANHATTAN, WeaviateClient.toDistance("manhattan"));
        assertEquals(VectorIndex.Distance.HAMMING, WeaviateClient.toDistance("hamming"));
        assertEquals(VectorIndex.Distance.EUCLIDEAN, WeaviateClient.toDistance("l2-squared"));
        assertEquals(VectorIndex.Distance.COSINE, WeaviateClient.toDistance(null));
        assertNull(WeaviateClient.toDistance("other"));

        VdbCollection collection = new VdbCollection("C");
        assertEquals("{ operator: And, operands: [{ path: [\"a\"], operator: Equal, valueInt: 1 }, { path: [\"b\"],"
                + " operator: NotEqual, valueText: \"x\" }, { path: [\"c\"], operator: GreaterThan, valueNumber: 1.5 },"
                + " { path: [\"d\"], operator: GreaterThanEqual, valueInt: 2 }, { path: [\"e\"], operator: LessThan,"
                + " valueInt: 3 }, { path: [\"f\"], operator: LessThanEqual, valueInt: 4 }, { path: [\"g\"], operator:"
                + " ContainsAny, valueIntArray: [1, 2] }] }", WeaviateClient.toWhere(collection, allOperators()));
        assertEquals("{ path: [\"t\"], operator: ContainsAny, valueTextArray: [\"a\", \"b\\\"\"] }",
                WeaviateClient.toWhere(collection, VdbQuery.all().where("t IN ('a', 'b\"')")));
        assertEquals("{ path: [\"t\"], operator: Equal, valueBoolean: true }", WeaviateClient.toWhere(collection,
                VdbQuery.all().where("t = true")));
        assertTrue(WeaviateClient.toWhere(collection, VdbQuery.all().where("t IN (1.5)")).contains(
                "valueNumberArray: [1.5]"));
        assertTrue(WeaviateClient.toWhere(collection, VdbQuery.all().where("t IN (true)")).contains(
                "valueBooleanArray: [true]"));
        assertNull(WeaviateClient.toWhere(collection, VdbQuery.all()));
    }

    @Test
    void readsWeaviateTenants() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("GET /v1/meta", "{\"version\": \"1.24.0\"}")
                    .route("GET /v1/schema", "{\"classes\": [{\"class\": \"Doc\"}]}")
                    .route("GET /v1/schema/Doc", "{\"class\": \"Doc\", \"description\": \"D\",\"multiTenancyConfig\":"
                            + " {\"enabled\": true}, \"vectorIndexType\": \"hnsw\", \"vectorIndexConfig\": {\"distance\":"
                            + " \"cosine\"}, \"properties\": [{\"name\": \"n\", \"dataType\": [\"int\"]},"
                            + " {\"name\": \"id\", \"dataType\": [\"text\"]}]}")
                    .route("GET /v1/schema/Doc/tenants", "[{\"name\": \"t1\"}, {\"name\": \"t2\"}]")
                    .route("GET /v1/objects?class=Doc&limit=1&include=vector&tenant=t1", "{\"objects\": []}")
                    .route("GET /v1/objects?class=Doc&limit=1&include=vector&tenant=t2", "{\"objects\": [{\"id\":"
                            + " \"u1\", \"vector\": [1, 2]}]}")
                    .route("GET /v1/objects?class=Doc&limit=2&include=vector&tenant=t1", "{\"objects\": [{\"id\":"
                            + " \"u1\", \"vector\": [1, 2], \"properties\": {\"n\": 1, \"id\": \"x\"}}, {\"id\": \"u2\","
                            + " \"vector\": [3, 4], \"properties\": {\"n\": 2}}]}")
                    .route("GET /v1/objects?class=Doc&limit=2&include=vector&tenant=t1&after=u2", "{\"objects\": []}")
                    .route("GET /v1/objects?class=Doc&limit=2&include=vector&tenant=t2", "{\"objects\": [{\"id\":"
                            + " \"u3\", \"vector\": [5, 6], \"properties\": {\"n\": 3}}]}")
                    .route("POST /v1/graphql", body -> body.contains("Aggregate") ? "{\"data\": {\"Aggregate\":"
                            + " {\"Doc\": [{\"meta\": {\"count\": 2}}]}}}" : body.contains("bad")
                            ? "{\"errors\": [{\"message\": \"bad where\"}]}" : body.contains("offset: 1")
                            ? "{\"data\": {\"Get\": {\"Doc\": []}}}" : "{\"data\": {\"Get\": {\"Doc\": [{\"n\": 1, \"_additional\": {\"id\": \"u1\","
                            + " \"vector\": [1, 2]}}]}}}");
            try (VdbClient client = VdbType.WEAVIATE.createClient(source(VdbType.WEAVIATE, server))) {
                assertEquals("1.24.0", client.getVersion());
                assertEquals(List.of("Doc"), client.getCollections());
                VdbCollection collection = client.describe("Doc");
                assertEquals("Bearer key", server.requests("GET /v1/meta").get(0).authorization);
                assertEquals(4, collection.getCount());
                assertEquals(List.of("tenant", "id"), List.of(collection.getPrimaryKeyFields().get(0).getName(),
                        collection.getPrimaryKeyFields().get(1).getName()));
                assertEquals(2, collection.findField("vector").get().getVectorType().getDimensions());
                // The property id is a column of its own
                assertEquals("id", collection.findField("id_2").get().getSourceName());
                List<Map<String, Object>> rows = readAll(client, collection, VdbQuery.all(), 2);
                assertEquals(3, rows.size());
                assertEquals("t2", rows.get(2).get("tenant"));
                assertEquals("x", rows.get(0).get("id_2"));
                VdbQuery limited = VdbQuery.all();
                limited.setLimit(1);
                assertEquals(1, readAll(client, collection, limited, 2).size());
                VdbQuery filtered = VdbQuery.all().where("n = 1");
                assertEquals(2, readAll(client, collection, filtered, 1).size());
                VdbQuery nearest = new VdbQuery();
                nearest.setNearestField("vector");
                nearest.setNearestId("u1");
                nearest.setLimit(5);
                assertEquals(5, client.count(collection, nearest));
                assertEquals(2, readAll(client, collection, nearest, 10).size());
                assertTrue(server.requests("POST /v1/graphql").stream().anyMatch(request -> request.body.contains(
                        "nearObject")));
                VdbQuery bad = new VdbQuery();
                bad.setNativeFilter("bad");
                VdbException e = assertThrows(VdbException.class, () -> readAll(client, collection, bad, 10));
                assertTrue(e.getMessage().contains("bad where"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Pinecone
    // ------------------------------------------------------------------

    @Test
    void matchesPineconeMetadata() throws Exception {
        VdbCollection collection = new VdbCollection("i");
        JsonNode metadata = json("{\"a\": 1, \"b\": \"y\", \"c\": 2, \"d\": 2, \"e\": 2, \"f\": 4, \"g\": 2,"
                + " \"t\": [\"x\", \"z\"], \"flag\": true}");
        assertTrue(PineconeClient.matches(collection, metadata, allOperators().getConditions()));
        assertTrue(PineconeClient.matches(collection, metadata, VdbQuery.all().where("t = 'x' and t != 'q' and"
                + " flag = true and missing != 1").getConditions()));
        assertFalse(PineconeClient.matches(collection, metadata, VdbQuery.all().where("missing = 1")
                .getConditions()));
        assertFalse(PineconeClient.matches(collection, metadata, VdbQuery.all().where("a = 'text'")
                .getConditions()));
        assertTrue(PineconeClient.matches(collection, metadata, VdbQuery.all().where("a != 'text'")
                .getConditions()));
        assertFalse(PineconeClient.matches(collection, metadata, VdbQuery.all().where("g IN (5, 6)")
                .getConditions()));
        assertTrue(PineconeClient.matches(json("1"), VdbQuery.Operator.IN, 1L));
        assertEquals(VectorIndex.Distance.EUCLIDEAN, PineconeClient.toDistance("euclidean"));
        assertEquals(VectorIndex.Distance.DOT, PineconeClient.toDistance("dotproduct"));
        assertNull(PineconeClient.toDistance("other"));
        assertTrue(PineconeClient.isDefaultNamespace("__default__"));
        assertEquals(json("{\"$and\": [{\"a\": {\"$eq\": 1}}, {\"g\": {\"$in\": [1, 2]}}]}").toString(),
                AbstractVdbClient.toOperatorFilter(collection, VdbQuery.all().where("a = 1 and g IN (1, 2)"), "P")
                        .toString());
        VdbQuery invalid = new VdbQuery();
        invalid.setNativeFilter("{bad");
        assertThrows(VdbException.class, () -> AbstractVdbClient.toOperatorFilter(collection, invalid, "P"));

        PineconeClient client = new PineconeClient(new VdbSource(VdbType.PINECONE, "https://api.pinecone.io"));
        assertEquals("https://idx.svc.pinecone.io", client.toHost("idx.svc.pinecone.io"));
        assertEquals("http://h.example", client.toHost("http://h.example"));
        client = new PineconeClient(new VdbSource(VdbType.PINECONE, "http://10.0.0.1:5080"));
        assertEquals("http://10.0.0.1:5081", client.toHost("localhost:5081"));
        assertEquals("http://10.0.0.1", client.toHost("127.0.0.1"));
    }

    @Test
    void readsPineconeSparseIndex() throws Exception {
        try (FakeServer server = new FakeServer()) {
            String host = server.getUrl().replace("127.0.0.1", "localhost").substring("http://".length());
            server.route("GET /indexes/sparse", "{\"name\": \"sparse\", \"metric\": \"euclidean\", \"vector_type\":"
                            + " \"sparse\", \"host\": \"" + host + "\"}")
                    .route("GET /indexes", "{\"indexes\": [{\"name\": \"sparse\"}]}")
                    .route("POST /describe_index_stats", "{\"namespaces\": {}}")
                    .route("GET /vectors/list", "{\"vectors\": [{\"id\": \"a\"}, {\"id\": \"b\"}]}")
                    .route("GET /vectors/fetch", "{\"vectors\": {\"a\": {\"id\": \"a\", \"sparseValues\": {\"indices\":"
                            + " [3], \"values\": [0.5]}, \"metadata\": {\"n\": 1}}}}")
                    .route("POST /query", "{\"matches\": [{\"id\": \"b\", \"score\": 2}, {\"id\": \"a\", \"score\":"
                            + " 1, \"sparseValues\": {\"indices\": [1], \"values\": [1]}}]}");
            try (VdbClient client = VdbType.PINECONE.createClient(source(VdbType.PINECONE, server))) {
                assertEquals("2025-01", client.getVersion());
                assertEquals(List.of("sparse"), client.getCollections());
                VdbCollection collection = client.describe("sparse");
                assertFalse(collection.findField("values").isPresent());
                assertFalse(collection.findField("namespace").isPresent());
                VdbField sparse = collection.findField("sparse_values").get();
                assertEquals(4, sparse.getVectorType().getDimensions());
                // A vector deleted between the list and the fetch is not read
                assertEquals(1, collection.getCount());
                List<Map<String, Object>> rows = readAll(client, collection, VdbQuery.all(), 10);
                assertEquals("{4:0.5}/4", rows.get(0).get("sparse_values"));
                        assertEquals(-1, client.count(collection, VdbQuery.all().where("n = 1")));
                VdbQuery nearest = new VdbQuery();
                nearest.setNearestField("sparse_values");
                nearest.setNearestId("a");
                nearest.where("n = 1");
                assertEquals(10000, client.count(collection, nearest));
                rows = readAll(client, collection, nearest, 10);
                // Smaller distances of euclidean are nearer
                assertEquals(List.of("a", "b"), List.of(rows.get(0).get("id"), rows.get(1).get("id")));
                assertTrue(server.requests("POST /query").get(0).body.contains("\"filter\""));
            }
        }
    }

    // ------------------------------------------------------------------
    // Elasticsearch and OpenSearch
    // ------------------------------------------------------------------

    @Test
    void mapsElasticsearchFields() throws Exception {
        ElasticsearchClient client = new ElasticsearchClient(new VdbSource(VdbType.ELASTICSEARCH, "http://h"));
        String[] types = {"boolean", "byte", "short", "integer", "long", "unsigned_long", "float", "half_float",
                "double", "scaled_float", "keyword", "wildcard", "ip", "date", "binary", "geo_shape"};
        VdbField.Kind[] kinds = {VdbField.Kind.BOOLEAN, VdbField.Kind.SMALLINT, VdbField.Kind.SMALLINT,
                VdbField.Kind.INTEGER, VdbField.Kind.BIGINT, VdbField.Kind.BIGINT, VdbField.Kind.REAL,
                VdbField.Kind.REAL, VdbField.Kind.DOUBLE, VdbField.Kind.DOUBLE, VdbField.Kind.TEXT, VdbField.Kind.TEXT,
                VdbField.Kind.TEXT, VdbField.Kind.TEXT, VdbField.Kind.BINARY, VdbField.Kind.JSON};
        for (int i = 0; i < types.length; i++) {
            assertEquals(kinds[i], client.toField("f", json("{\"type\": \"" + types[i] + "\"}")).getKind(),
                    types[i]);
        }
        assertEquals(VdbField.Kind.JSON, client.toField("o", json("{\"properties\": {}}")).getKind());
        VdbField bits = client.toField("b", json("{\"type\": \"dense_vector\", \"dims\": 16, \"element_type\":"
                + " \"bit\", \"similarity\": \"max_inner_product\", \"index_options\": {\"type\": \"flat\"}}"));
        assertEquals(VectorType.Element.BINARY, bits.getVectorType().getElement());
        assertNull(bits.getVectorIndex());
        VdbField bytes = client.toField("b", json("{\"type\": \"dense_vector\", \"dims\": 4, \"element_type\":"
                + " \"byte\", \"similarity\": \"dot_product\", \"index_options\": {\"type\": \"int8_hnsw\"}}"));
        assertEquals(VectorType.Element.INT8, bytes.getVectorType().getElement());
        assertEquals(VectorIndex.Distance.DOT, bytes.getVectorIndex().getDistance());
        assertNull(client.toField("n", json("{\"type\": \"dense_vector\", \"dims\": 4, \"index\": false,"
                + " \"similarity\": \"cosine\"}")).getVectorIndex());
        assertNull(ElasticsearchClient.toDistance("other"));
        assertArrayEquals(new byte[]{(byte) 0xAB, 1}, ElasticsearchClient.hexToBytes("ab01"));
        VdbCollection collection = new VdbCollection("i");
        assertEquals(json("{\"bool\": {\"filter\": [{\"term\": {\"a\": 1}}, {\"range\": {\"c\": {\"gt\": 1.5}}},"
                        + " {\"range\": {\"d\": {\"gte\": 2}}}, {\"range\": {\"e\": {\"lt\": 3}}}, {\"range\": {\"f\":"
                        + " {\"lte\": 4}}}, {\"terms\": {\"g\": [1, 2]}}], \"must_not\": [{\"term\": {\"b\": \"x\"}}]}}").toString(),
                client.toQuery(collection, allOperators()).toString());
        VdbQuery invalid = new VdbQuery();
        invalid.setNativeFilter("{bad");
        assertThrows(VdbException.class, () -> client.toQuery(collection, invalid));

        OpenSearchClient openSearch = new OpenSearchClient(new VdbSource(VdbType.OPENSEARCH, "https://h"));
        VdbField ivf = openSearch.toField("v", json("{\"type\": \"knn_vector\", \"dimension\": 8, \"data_type\":"
                + " \"binary\", \"method\": {\"name\": \"ivf\", \"space_type\": \"hamming\", \"parameters\":"
                + " {\"nlist\": 4}}}"));
        assertEquals(VectorType.Element.BINARY, ivf.getVectorType().getElement());
        assertEquals(VectorIndex.Kind.IVF, ivf.getVectorIndex().getKind());
        assertEquals("4", ivf.getVectorIndex().getOption("lists"));
        VdbField linf = openSearch.toField("v", json("{\"type\": \"knn_vector\", \"dimension\": 8, \"data_type\":"
                + " \"byte\", \"space_type\": \"linf\"}"));
        assertEquals(VectorType.Element.INT8, linf.getVectorType().getElement());
        assertNull(linf.getVectorIndex());
        assertEquals(VectorIndex.Distance.MANHATTAN, OpenSearchClient.toSpaceDistance("l1"));
        assertEquals(VectorIndex.Distance.DOT, OpenSearchClient.toSpaceDistance("innerproduct"));
        assertTrue(openSearch.isSystemIndex("top_queries-2026"));
        assertFalse(openSearch.isSystemIndex("docs"));
    }

    @Test
    void readsElasticsearchAlias() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("GET /_cat/indices", "[{\"index\": \"docs\"}, {\"index\": \".hidden\"}]")
                    .route("GET /alias/_mapping", "{\"docs\": {\"mappings\": {\"properties\": {\"b\": {\"type\":"
                            + " \"dense_vector\", \"dims\": 16, \"element_type\": \"bit\"}, \"n\": {\"type\": \"long\"}}}}}")
                    .route("POST /alias/_count", "{\"count\": 1}")
                    .route("POST /alias/_pit", "{\"id\": \"p1\"}")
                    .route("POST /_search", "{\"pit_id\": \"p2\", \"hits\": {\"hits\": [{\"_id\": \"1\", \"_source\":"
                            + " {\"b\": \"ab01\", \"n\": 5}, \"sort\": [1]}]}}")
                    .route("DELETE /_pit", 500, "{}")
                    .route("GET /alias/_doc/1", "{\"_source\": {\"n\": 1}}")
                    .route("POST /alias/_search", "{\"hits\": {\"hits\": []}}");
            try (VdbClient client = VdbType.ELASTICSEARCH.createClient(source(VdbType.ELASTICSEARCH, server))) {
                assertEquals(List.of("docs"), client.getCollections());
                assertEquals("ApiKey key", server.requests("GET /_cat").get(0).authorization);
                VdbCollection collection = client.describe("alias");
                List<Map<String, Object>> rows = readAll(client, collection, VdbQuery.all(), 10);
                assertEquals("[171,1]", rows.get(0).get("b"));
                assertEquals(5L, rows.get(0).get("n"));
                VdbQuery nearest = new VdbQuery();
                nearest.setNearestField("b");
                nearest.setNearestId("1");
                VdbException e = assertThrows(VdbException.class, () -> readAll(client, collection, nearest, 10));
                assertTrue(e.getMessage().contains("has no vector"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Qdrant, Chroma and the payloads
    // ------------------------------------------------------------------

    @Test
    void mapsQdrantAndChroma() throws Exception {
        VdbField multivector = QdrantClient.toVectorField("m", json("{\"size\": 4, \"multivector_config\": {}}"),
                json("{}"));
        assertEquals(VdbField.Kind.JSON, multivector.getKind());
        VdbField uint8 = QdrantClient.toVectorField("u", json("{\"size\": 4, \"datatype\": \"uint8\", \"distance\":"
                + " \"Manhattan\", \"hnsw_config\": {\"m\": 0}}"), json("{\"m\": 16, \"ef_construct\": 100}"));
        assertEquals(VectorType.Element.FLOAT32, uint8.getVectorType().getElement());
        assertEquals(VectorIndex.Distance.MANHATTAN, uint8.getVectorIndex().getDistance());
        assertNull(uint8.getVectorIndex().getOption("m"));
        assertNull(QdrantClient.toVectorField("x", json("{\"size\": 4, \"distance\": \"Other\"}"), json("{}"))
                .getVectorIndex());
        VdbCollection collection = new VdbCollection("c");
        VdbField renamed = new VdbField("a_2", VdbField.Kind.BIGINT);
        renamed.setSourceName("a");
        collection.addField(renamed);
        assertEquals(json("{\"must\": [{\"key\": \"a\", \"match\": {\"value\": 1}}, {\"key\": \"c\", \"range\":"
                        + " {\"gt\": 1.5}}, {\"key\": \"d\", \"range\": {\"gte\": 2}}, {\"key\": \"e\", \"range\": {\"lt\":"
                        + " 3}}, {\"key\": \"f\", \"range\": {\"lte\": 4}}, {\"key\": \"g\", \"match\": {\"any\": [1, 2]}}],"
                        + " \"must_not\": [{\"key\": \"b\", \"match\": {\"value\": \"x\"}}]}").toString(),
                QdrantClient.toFilter(collection, VdbQuery.all().where("a_2 = 1 and b != 'x' and c > 1.5 and d >= 2"
                        + " and e < 3 and f <= 4 and g IN (1, 2)")).toString());
        VdbQuery invalid = new VdbQuery();
        invalid.setNativeFilter("{bad");
        assertThrows(VdbException.class, () -> QdrantClient.toFilter(collection, invalid));

        assertEquals(VectorIndex.Distance.DOT, ChromaClient.toVectorIndex(json("{\"spann\": {\"space\": \"ip\"}}"))
                .getDistance());
        assertEquals(VectorIndex.Distance.EUCLIDEAN, ChromaClient.toVectorIndex(json("{}")).getDistance());
        assertNull(ChromaClient.toVectorIndex(json("{\"hnsw\": {\"space\": \"other\"}}")));
    }

    @Test
    void readsChromaWithoutEmbeddings() throws Exception {
        try (FakeServer server = new FakeServer()) {
            String base = "/api/v2/tenants/t/databases/d";
            server.route("GET /api/v2/version", "\"1.0.0\"")
                    .route("GET " + base + "/collections?", "[{\"name\": \"c\"}]")
                    .route("GET " + base + "/collections/c", "{\"id\": \"id1\", \"metadata\": null,"
                            + " \"configuration_json\": {\"hnsw\": {\"space\": \"l2\"}}}")
                    .route("POST " + base + "/collections/id1/get", body -> body.contains("\"where\"")
                            ? "{\"ids\": [\"1\", \"2\"]}" : "{\"ids\": [\"1\"], \"metadatas\": [null], \"embeddings\":"
                            + " [null], \"documents\": [null]}")
                    .route("GET " + base + "/collections/id1/count", "1");
            VdbSource source = source(VdbType.CHROMA, server);
            source.setTenant("t");
            source.setDatabase("d");
            try (VdbClient client = VdbType.CHROMA.createClient(source)) {
                assertEquals("1.0.0", client.getVersion());
                assertEquals(List.of("c"), client.getCollections());
                VdbCollection collection = client.describe("c");
                assertTrue(collection.findField("embedding").get().isNullable());
                assertNull(collection.getRemarks());
                List<Map<String, Object>> rows = readAll(client, collection, VdbQuery.all(), 10);
                assertNull(rows.get(0).get("embedding"));
                assertNull(rows.get(0).get("document"));
                assertEquals(2, client.count(collection, VdbQuery.all().where("n = 1")));
                VdbQuery limited = VdbQuery.all();
                limited.setLimit(1);
                assertEquals(1, client.count(collection, limited));
            }
        }
    }

    @Test
    void convertsPayloadValues() throws Exception {
        assertNull(AbstractVdbClient.toValue(json("true"), new VdbField("f", VdbField.Kind.INTEGER)));
        assertEquals(2, AbstractVdbClient.toValue(json("2"), new VdbField("f", VdbField.Kind.SMALLINT)));
        assertNull(AbstractVdbClient.toValue(json("1"), new VdbField("f", VdbField.Kind.BOOLEAN)));
        assertEquals(1.5f, AbstractVdbClient.toValue(json("1.5"), new VdbField("f", VdbField.Kind.REAL)));
        assertNull(AbstractVdbClient.toValue(json("\"x\""), new VdbField("f", VdbField.Kind.DOUBLE)));
        assertEquals("[1]", AbstractVdbClient.toValue(json("[1]"), new VdbField("f", VdbField.Kind.TEXT)));
        assertEquals(OffsetDateTime.parse("2024-01-01T00:00Z"), AbstractVdbClient.toValue(json(
                "\"2024-01-01T00:00:00Z\""), new VdbField("f", VdbField.Kind.TIMESTAMP)));
        assertNull(AbstractVdbClient.toValue(json("\"2024\""), new VdbField("f", VdbField.Kind.TIMESTAMP)));
        assertNull(AbstractVdbClient.toValue(json("1"), new VdbField("f", VdbField.Kind.TIMESTAMP)));
        assertArrayEquals(new byte[]{1}, (byte[]) AbstractVdbClient.toValue(json("\"AQ==\""), new VdbField("f",
                VdbField.Kind.BINARY)));
        assertNull(AbstractVdbClient.toValue(json("\"!!\""), new VdbField("f", VdbField.Kind.BINARY)));
        assertNull(AbstractVdbClient.toValue(json("1"), new VdbField("f", VdbField.Kind.BINARY)));
        assertEquals("{\"a\":1}", AbstractVdbClient.toValue(json("{\"a\": 1}"), new VdbField("f",
                VdbField.Kind.VECTOR)));
        assertEquals("x", AbstractVdbClient.toValue(json("\"x\""), new VdbField("f", VdbField.Kind.VECTOR)));
        assertNull(AbstractVdbClient.toValue(json("null"), new VdbField("f", VdbField.Kind.TEXT)));
        assertThrows(IllegalArgumentException.class, () -> AbstractVdbClient.required(" ", "Host"));
        assertEquals("a%20b", AbstractVdbClient.encode("a b"));
    }

    @Test
    void reportsHttpErrors() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("GET /collections", 403, "{\"status\": {\"error\": \"forbidden\"}}");
            try (VdbClient client = VdbType.QDRANT.createClient(source(VdbType.QDRANT, server))) {
                RestException e = assertThrows(RestException.class, client::getCollections);
                assertTrue(e.isAccessDenied());
                assertEquals(VdbType.QDRANT, client.getType());
            }
        }
    }

    /**
     * Blank API keys and users are no credentials: no authorization header is sent, a refusal tells so
     */
    @Test
    void sendsNoBlankCredentials() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("GET ", 401, "{\"error\": \"Unauthorized\"}")
                    .route("POST ", 401, "{\"error\": \"Unauthorized\"}");
            for (VdbType type : VdbType.values()) {
                if (type == VdbType.MILVUS) {
                    continue;
                }
                VdbSource source = source(type, server);
                source.setApiKey("  ");
                source.setUsername(" ");
                assertFalse(source.hasCredentials());
                try (VdbClient client = type.createClient(source)) {
                    RestException e = assertThrows(RestException.class, client::getCollections);
                    assertTrue(e.isAccessDenied());
                    assertTrue(e.getMessage().contains("no API key or user is given"), e.getMessage());
                }
            }
            assertTrue(server.requests.stream().allMatch(request -> request.authorization == null));
            int requests = server.requests.size();
            VdbSource source = source(VdbType.QDRANT, server);
            try (VdbClient client = VdbType.QDRANT.createClient(source)) {
                RestException e = assertThrows(RestException.class, client::getCollections);
                assertTrue(e.getMessage().contains("are refused"), e.getMessage());
            }
            assertEquals(requests + 1, server.requests.size());
        }
        // Milvus refuses with HTTP 200 and its codes
        try (FakeServer server = new FakeServer()) {
            server.route("POST /v2/vectordb/collections/list", "{\"code\": 1800, \"message\": \"user hasn't"
                    + " authenticated\"}")
                    .route("POST /v2/vectordb/collections/describe", "{\"code\": 65535, \"message\":"
                            + " \"PrivilegeLoad: permission denied\"}");
            VdbSource source = source(VdbType.MILVUS, server);
            source.setApiKey(null);
            try (VdbClient client = VdbType.MILVUS.createClient(source)) {
                RestException e = assertThrows(RestException.class, client::getCollections);
                assertEquals(401, e.getStatus());
                assertTrue(e.getMessage().contains("no API key or user is given"), e.getMessage());
                RestException denied = assertThrows(RestException.class, () -> client.describe("docs"));
                assertEquals(403, denied.getStatus());
            }
            assertTrue(server.requests.stream().allMatch(request -> request.authorization == null));
        }
    }
}
