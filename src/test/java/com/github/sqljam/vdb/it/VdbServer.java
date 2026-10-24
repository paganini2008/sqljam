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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.utils.RestException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VdbType;

/**
 * @Description: VdbServer is a vector database of the integration tests of the vdb line (it-vdb-test.properties)
 *               with its fixture: a collection of the 8 documents of vector/embeddings.tsv, their bge-m3 embeddings
 *               (1024 dimensions, an HNSW index of the cosine distance), their qwen3-embedding embeddings (2560
 *               dimensions), a title, a number, a score, tags and a flag. Milvus and Qdrant have sparse vectors too.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public enum VdbServer {

    MILVUS(VdbType.MILVUS, "sjvt_doc"),
    QDRANT(VdbType.QDRANT, "sjvt_doc"),
    WEAVIATE(VdbType.WEAVIATE, "SjvtDoc"),
    PINECONE(VdbType.PINECONE, "sjvt-doc"),
    ELASTICSEARCH(VdbType.ELASTICSEARCH, "sjvt_doc"),
    OPENSEARCH(VdbType.OPENSEARCH, "sjvt_doc"),
    CHROMA(VdbType.CHROMA, "sjvt_doc");

    /**
     * Records of the fixture, Pinecone has 2 more in its default namespace
     */
    public static final int RECORDS = 8;
    static final ObjectMapper MAPPER = RestClient.getMapper();

    private final VdbType type;
    private final String collection;

    VdbServer(VdbType type, String collection) {
        this.type = type;
        this.collection = collection;
    }

    /**
     * A holder class, because static fields of an enum are not set yet when its constants are created
     */
    static final class Settings {

        private static final Properties PROPERTIES = new Properties();
        private static final Map<VdbServer, Boolean> AVAILABLE = new EnumMap<>(VdbServer.class);

        static {
            try (InputStream in = VdbServer.class.getResourceAsStream("/it-vdb-test.properties")) {
                PROPERTIES.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static String get(VdbServer server, String key) {
            String name = server.name().toLowerCase(Locale.ENGLISH) + "." + key;
            return StringUtils.trimToNull(System.getProperty(name, PROPERTIES.getProperty(name)));
        }
    }

    /**
     * A document of the fixture
     */
    public static final class Document {
        public final int id;
        public final String title;
        public final float[] embedding;
        public final float[] embeddingLarge;

        Document(int id, String title, float[] embedding, float[] embeddingLarge) {
            this.id = id;
            this.title = title;
            this.embedding = embedding;
            this.embeddingLarge = embeddingLarge;
        }
    }

    private static List<Document> documents;

    public static synchronized List<Document> getDocuments() {
        if (documents == null) {
            List<Document> list = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(VdbServer.class.getResourceAsStream(
                    "/vector/embeddings.tsv"), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.startsWith("#") || line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    list.add(new Document(Integer.parseInt(parts[0]), parts[1], MAPPER.readValue(parts[2],
                            float[].class), MAPPER.readValue(parts[3], float[].class)));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            documents = list;
        }
        return documents;
    }

    public VdbType getType() {
        return type;
    }

    public String getCollection() {
        return collection;
    }

    /**
     * Name of the target table of the fixture, e.g. sjvt_qdrant
     */
    public String getTableName() {
        return "sjvt_" + name().toLowerCase(Locale.ENGLISH);
    }

    public boolean isConfigured() {
        return Settings.get(this, "url") != null;
    }

    public VdbSource getSource() {
        return getSource(Settings.get(this, "apiKey"));
    }

    public VdbSource getSource(String apiKey) {
        VdbSource source = new VdbSource(type, Settings.get(this, "url"));
        source.setApiKey(apiKey);
        source.setUsername(Settings.get(this, "username"));
        source.setPassword(Settings.get(this, "password"));
        source.setCertificateTrusted(true);
        return source;
    }

    public String getSetting(String key) {
        return Settings.get(this, key);
    }

    public VdbClient createClient() {
        return type.createClient(getSource());
    }

    public synchronized boolean isAvailable() {
        return Settings.AVAILABLE.computeIfAbsent(this, server -> {
            if (!isConfigured()) {
                return false;
            }
            try (VdbClient client = createClient()) {
                client.getCollections();
                return true;
            } catch (RuntimeException e) {
                System.err.println("Vector database is not available: " + this + ", " + e.getMessage());
                return false;
            }
        });
    }

    RestClient rest() {
        RestClient rest = new RestClient(Settings.get(this, "url"), true, 120);
        String apiKey = Settings.get(this, "apiKey");
        switch (type) {
            case MILVUS:
                rest.header("Authorization", "Bearer " + Settings.get(this, "username") + ":"
                        + Settings.get(this, "password"));
                break;
            case QDRANT:
                rest.header("api-key", apiKey);
                break;
            case PINECONE:
                rest.header("Api-Key", apiKey);
                rest.header("X-Pinecone-API-Version", "2025-01");
                break;
            case ELASTICSEARCH:
            case OPENSEARCH:
                rest.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                        (Settings.get(this, "username") + ":" + Settings.get(this, "password"))
                                .getBytes(StandardCharsets.UTF_8)));
                break;
            default:
                rest.header("Authorization", "Bearer " + apiKey);
                break;
        }
        return rest;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> payload(Document document) {
        return map("title", document.title, "n", document.id, "score", document.id / 2.0, "tags", List.of("t"
                + document.id, "x"), "active", document.id % 2 == 0);
    }

    /**
     * Creates the fixture collection again
     */
    public void createFixture() throws Exception {
        dropFixture();
        RestClient rest = rest();
        List<Document> docs = getDocuments();
        switch (type) {
            case MILVUS:
                milvus(rest, "/v2/vectordb/collections/create", map("collectionName", collection, "schema", map(
                        "autoId", false, "enableDynamicField", true, "fields", List.of(
                                map("fieldName", "id", "dataType", "Int64", "isPrimary", true),
                                map("fieldName", "title", "dataType", "VarChar", "elementTypeParams",
                                        map("max_length", 512)),
                                map("fieldName", "embedding", "dataType", "FloatVector", "elementTypeParams",
                                        map("dim", 1024)),
                                map("fieldName", "embedding_large", "dataType", "Float16Vector",
                                        "elementTypeParams", map("dim", 2560)),
                                map("fieldName", "sparse", "dataType", "SparseFloatVector"),
                                map("fieldName", "n", "dataType", "Int32"),
                                map("fieldName", "score", "dataType", "Double", "nullable", true),
                                map("fieldName", "tags", "dataType", "Array", "elementDataType", "VarChar",
                                        "elementTypeParams", map("max_capacity", 8, "max_length", 32)),
                                map("fieldName", "active", "dataType", "Bool"))),
                        "indexParams", List.of(
                                map("fieldName", "embedding", "indexName", "embedding_hnsw", "metricType", "COSINE",
                                        "indexType", "HNSW", "params", map("M", 16, "efConstruction", 64)),
                                map("fieldName", "embedding_large", "indexName", "large_ivf", "metricType", "L2",
                                        "indexType", "IVF_FLAT", "params", map("nlist", 4)),
                                map("fieldName", "sparse", "indexName", "sparse_idx", "metricType", "IP",
                                        "indexType", "SPARSE_INVERTED_INDEX"))));
                List<Object> rows = new ArrayList<>();
                for (Document document : docs) {
                    Map<String, Object> row = map("id", document.id, "embedding", document.embedding,
                            "embedding_large", document.embeddingLarge, "sparse", map(String.valueOf(document.id),
                                    1.0, String.valueOf(document.id + 10), 0.5), "extra_dynamic", "dyn" + document.id);
                    row.putAll(payload(document));
                    row.put("score", document.id % 3 == 0 ? null : document.id / 2.0);
                    rows.add(row);
                }
                milvus(rest, "/v2/vectordb/entities/insert", map("collectionName", collection, "data", rows));
                break;
            case QDRANT:
                rest.put("/collections/" + collection, map("vectors", map(
                        "embedding", map("size", 1024, "distance", "Cosine", "hnsw_config", map("m", 16,
                                "ef_construct", 64)),
                        "embedding_large", map("size", 2560, "distance", "Euclid", "datatype", "float16")),
                        "sparse_vectors", map("keywords", map())));
                List<Object> points = new ArrayList<>();
                for (Document document : docs) {
                    Map<String, Object> vectors = map("embedding", document.embedding, "keywords", map("indices",
                            List.of(document.id, document.id + 10), "values", List.of(1.0, 0.5)));
                    // A point without a vector of the collection
                    if (document.id != RECORDS) {
                        vectors.put("embedding_large", document.embeddingLarge);
                    }
                    points.add(map("id", document.id == 3 ? new UUID(0, 3).toString() : document.id, "vector",
                            vectors, "payload", payload(document)));
                }
                rest.put("/collections/" + collection + "/points?wait=true", map("points", points));
                break;
            case WEAVIATE:
                rest.post("/v1/schema", map("class", collection, "description", "Documents of SqlJam",
                        "vectorConfig", map(
                                "embedding", map("vectorizer", map("none", map()), "vectorIndexType", "hnsw",
                                        "vectorIndexConfig", map("distance", "cosine", "maxConnections", 16,
                                                "efConstruction", 64)),
                                "embedding_large", map("vectorizer", map("none", map()), "vectorIndexType",
                                        "flat", "vectorIndexConfig", map("distance", "l2-squared"))),
                        "properties", List.of(map("name", "title", "dataType", List.of("text")),
                                map("name", "n", "dataType", List.of("int")),
                                map("name", "score", "dataType", List.of("number")),
                                map("name", "tags", "dataType", List.of("text[]")),
                                map("name", "active", "dataType", List.of("boolean")),
                                map("name", "published", "dataType", List.of("date")))));
                List<Object> objects = new ArrayList<>();
                for (Document document : docs) {
                    Map<String, Object> properties = payload(document);
                    properties.put("published", String.format("2024-%02d-01T08:30:00Z", document.id));
                    objects.add(map("class", collection, "id", new UUID(0, document.id).toString(), "properties",
                            properties, "vectors", map("embedding", document.embedding, "embedding_large",
                                    document.embeddingLarge)));
                }
                rest.post("/v1/batch/objects", map("objects", objects));
                break;
            case PINECONE:
                rest.post("/indexes", map("name", collection, "dimension", 1024, "metric", "cosine", "spec",
                        map("serverless", map("cloud", "aws", "region", "us-east-1"))));
                String host = pineconeHost(rest);
                List<Object> vectors = new ArrayList<>();
                for (Document document : docs) {
                    vectors.add(map("id", "doc-" + document.id, "values", document.embedding, "metadata",
                            payload(document)));
                }
                rest.post(host + "/vectors/upsert", map("vectors", vectors, "namespace", "ns1"));
                rest.post(host + "/vectors/upsert", map("vectors", vectors.subList(0, 2)));
                break;
            case ELASTICSEARCH:
            case OPENSEARCH:
                boolean opensearch = type == VdbType.OPENSEARCH;
                Map<String, Object> embedding = opensearch ? map("type", "knn_vector", "dimension", 1024, "method",
                        map("name", "hnsw", "engine", "faiss", "space_type", "cosinesimil", "parameters", map("m", 16,
                                "ef_construction", 64))) : map("type", "dense_vector", "dims", 1024, "index", true,
                        "similarity", "cosine", "index_options", map("type", "hnsw", "m", 16, "ef_construction", 64));
                Map<String, Object> large = opensearch ? map("type", "knn_vector", "dimension", 2560, "method",
                        map("name", "hnsw", "engine", "lucene", "space_type", "l2")) : map("type", "dense_vector",
                        "dims", 2560, "index", true, "similarity", "l2_norm");
                Map<String, Object> index = map("mappings", map("properties", map("title", map("type", "text"),
                        "n", map("type", "integer"), "score", map("type", "double"), "tags", map("type", "keyword"),
                        "active", map("type", "boolean"), "embedding", embedding, "embedding_large", large)));
                if (opensearch) {
                    index.put("settings", map("index.knn", true));
                }
                rest.put("/" + collection, index);
                StringBuilder bulk = new StringBuilder();
                for (Document document : docs) {
                    Map<String, Object> source = payload(document);
                    source.put("embedding", document.embedding);
                    if (document.id != RECORDS) {
                        source.put("embedding_large", document.embeddingLarge);
                    }
                    bulk.append(MAPPER.writeValueAsString(map("index", map("_id", String.valueOf(document.id)))))
                            .append('\n').append(MAPPER.writeValueAsString(source)).append('\n');
                }
                rest.post("/" + collection + "/_bulk?refresh=true", bulk.toString());
                break;
            default:
                String base = "/api/v2/tenants/default_tenant/databases/default_database/collections";
                JsonNode created = rest.post(base, map("name", collection, "metadata", map("description",
                        "Documents of SqlJam"), "configuration", map("hnsw", map("space", "cosine",
                        "ef_construction", 100, "max_neighbors", 16))));
                List<Object> ids = new ArrayList<>();
                List<Object> embeddings = new ArrayList<>();
                List<Object> metadatas = new ArrayList<>();
                List<Object> texts = new ArrayList<>();
                for (Document document : docs) {
                    ids.add(String.valueOf(document.id));
                    embeddings.add(document.embedding);
                    Map<String, Object> metadata = payload(document);
                    metadata.remove("tags");
                    metadatas.add(metadata);
                    texts.add(document.title);
                }
                rest.post(base + "/" + created.path("id").asText() + "/add", map("ids", ids, "embeddings", embeddings,
                        "metadatas", metadatas, "documents", texts));
                break;
        }
    }

    private void milvus(RestClient rest, String path, Object body) {
        JsonNode response = rest.post(path, body);
        if (response.path("code").asInt(0) != 0) {
            throw new IllegalStateException(path + ": " + response);
        }
    }

    /**
     * Host of the Pinecone index, localhost of Pinecone Local is the host of the url
     */
    String pineconeHost(RestClient rest) {
        String host = rest.get("/indexes/" + collection).path("host").asText();
        String url = Settings.get(this, "url");
        String server = StringUtils.substringBefore(StringUtils.substringAfter(url, "://"), ":");
        return StringUtils.substringBefore(url, "://") + "://" + host.replace("localhost", server);
    }

    /**
     * Name of the collection of the parts of a collection which are read apart: a Weaviate class of the tenants t1
     * (records 1 to 3) and t2 (records 4 and 5), a Milvus collection of the partitions p1 (records 1 to 4) and p2
     * (records 5 to 8), and a Chroma collection in the database sjvt_db2 (records 1 and 2). Pinecone has the
     * namespaces ns1 and __default__ of its fixture, the others have none.
     */
    public String getPartitionedCollection() {
        switch (type) {
            case WEAVIATE:
                return "SjvtTenant";
            case MILVUS:
                return "sjvt_part";
            case CHROMA:
                return "sjvt_doc2";
            case PINECONE:
                return collection;
            default:
                return null;
        }
    }

    /**
     * Creates the collection of parts again
     */
    public void createPartitionedFixture() throws Exception {
        dropPartitionedFixture();
        RestClient rest = rest();
        List<Document> docs = getDocuments();
        switch (type) {
            case WEAVIATE:
                rest.post("/v1/schema", map("class", "SjvtTenant", "multiTenancyConfig", map("enabled", true),
                        "vectorConfig", map("embedding", map("vectorizer", map("none", map()), "vectorIndexType",
                                "hnsw", "vectorIndexConfig", map("distance", "cosine"))),
                        "properties", List.of(map("name", "n", "dataType", List.of("int")))));
                rest.post("/v1/schema/SjvtTenant/tenants", List.of(map("name", "t1"), map("name", "t2")));
                List<Object> objects = new ArrayList<>();
                for (Document document : docs.subList(0, 5)) {
                    objects.add(map("class", "SjvtTenant", "tenant", document.id <= 3 ? "t1" : "t2", "id",
                            new UUID(0, document.id).toString(), "properties", map("n", document.id), "vectors",
                            map("embedding", document.embedding)));
                }
                rest.post("/v1/batch/objects", map("objects", objects));
                break;
            case MILVUS:
                milvus(rest, "/v2/vectordb/collections/create", map("collectionName", "sjvt_part", "schema", map(
                        "autoId", false, "fields", List.of(
                                map("fieldName", "id", "dataType", "Int64", "isPrimary", true),
                                map("fieldName", "n", "dataType", "Int32"),
                                map("fieldName", "embedding", "dataType", "FloatVector", "elementTypeParams",
                                        map("dim", 1024)))),
                        "indexParams", List.of(map("fieldName", "embedding", "indexName", "embedding_hnsw",
                                "metricType", "COSINE", "indexType", "HNSW"))));
                for (String partition : List.of("p1", "p2")) {
                    milvus(rest, "/v2/vectordb/partitions/create", map("collectionName", "sjvt_part",
                            "partitionName", partition));
                    List<Object> rows = new ArrayList<>();
                    for (Document document : docs) {
                        if ((document.id <= 4) == partition.equals("p1")) {
                            rows.add(map("id", document.id, "n", document.id, "embedding", document.embedding));
                        }
                    }
                    milvus(rest, "/v2/vectordb/entities/insert", map("collectionName", "sjvt_part",
                            "partitionName", partition, "data", rows));
                }
                break;
            case CHROMA:
                String databases = "/api/v2/tenants/default_tenant/databases";
                rest.post(databases, map("name", "sjvt_db2"));
                JsonNode created = rest.post(databases + "/sjvt_db2/collections", map("name", "sjvt_doc2"));
                rest.post(databases + "/sjvt_db2/collections/" + created.path("id").asText() + "/add", map("ids",
                        List.of("1", "2"), "embeddings", List.of(docs.get(0).embedding, docs.get(1).embedding),
                        "metadatas", List.of(map("n", 1), map("n", 2))));
                break;
            default:
                break;
        }
    }

    /**
     * Drops the collection of parts, a missing one is fine
     */
    public void dropPartitionedFixture() {
        RestClient rest = rest();
        try {
            switch (type) {
                case WEAVIATE:
                    rest.delete("/v1/schema/SjvtTenant");
                    break;
                case MILVUS:
                    rest.post("/v2/vectordb/collections/drop", map("collectionName", "sjvt_part"));
                    break;
                case CHROMA:
                    rest.delete("/api/v2/tenants/default_tenant/databases/sjvt_db2");
                    break;
                default:
                    break;
            }
        } catch (RestException e) {
            if (e.getStatus() != 404 && e.getStatus() != 400 && e.getStatus() != 422) {
                throw e;
            }
        }
    }

    /**
     * Drops the fixture collection, a missing collection is fine
     */
    public void dropFixture() {
        RestClient rest = rest();
        try {
            switch (type) {
                case MILVUS:
                    rest.post("/v2/vectordb/collections/drop", map("collectionName", collection));
                    break;
                case QDRANT:
                    rest.delete("/collections/" + collection);
                    break;
                case WEAVIATE:
                    rest.delete("/v1/schema/" + collection);
                    break;
                case PINECONE:
                    rest.delete("/indexes/" + collection);
                    break;
                case ELASTICSEARCH:
                case OPENSEARCH:
                    rest.delete("/" + collection);
                    break;
                default:
                    rest.delete("/api/v2/tenants/default_tenant/databases/default_database/collections/"
                            + collection);
                    break;
            }
        } catch (RestException e) {
            if (e.getStatus() != 404 && e.getStatus() != 400) {
                throw e;
            }
        }
    }
}
