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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbSource;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: OpenSearchClient reads OpenSearch 2.4 and later (k-NN plugin, point in time) by its REST API, like
 *               Elasticsearch: knn_vector fields are vectors of their data type (float, byte, binary), space type
 *               (cosinesimil, l2, innerproduct, l1, hamming) and method (HNSW or IVF of the engines faiss, lucene
 *               and nmslib). Documents are read in a point in time sorted by _id.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class OpenSearchClient extends ElasticsearchClient {

    public OpenSearchClient(VdbSource source) {
        super(source);
    }

    /**
     * Indexes of the security audit log and of the query insights are of the server too
     */
    @Override
    protected boolean isSystemIndex(String name) {
        return super.isSystemIndex(name) || name.startsWith("security-auditlog-") || name.startsWith("top_queries-");
    }

    @Override
    protected boolean isVectorType(String type) {
        return "knn_vector".equals(type);
    }

    /**
     * knn_vector of its data type, space type and method, the space type of 2.17 and later may be of the field
     */
    @Override
    protected VdbField toVectorField(String name, JsonNode mapping) {
        String dataType = StringUtils.defaultIfBlank(text(mapping, "data_type"), "float");
        VectorType.Element element = "byte".equals(dataType) ? VectorType.Element.INT8
                : "binary".equals(dataType) ? VectorType.Element.BINARY : VectorType.Element.FLOAT32;
        VdbField field = VdbField.vector(name, VectorType.dense(element, mapping.path("dimension").asInt(0)));
        JsonNode method = mapping.path("method");
        String spaceType = StringUtils.defaultIfBlank(text(method, "space_type"), text(mapping, "space_type"));
        VectorIndex.Distance distance = toSpaceDistance(StringUtils.defaultIfBlank(spaceType, "l2"));
        if (distance == null) {
            return field;
        }
        Map<String, String> options = new LinkedHashMap<>();
        JsonNode parameters = method.path("parameters");
        if (parameters.has("m")) {
            options.put("m", parameters.path("m").asText());
        }
        if (parameters.has("ef_construction")) {
            options.put("ef_construction", parameters.path("ef_construction").asText());
        }
        if (parameters.has("nlist")) {
            options.put("lists", parameters.path("nlist").asText());
        }
        boolean ivf = "ivf".equalsIgnoreCase(text(method, "name"));
        field.setVectorIndex(new VectorIndex(ivf ? VectorIndex.Kind.IVF : VectorIndex.Kind.HNSW, distance, options));
        return field;
    }

    static VectorIndex.Distance toSpaceDistance(String spaceType) {
        switch (StringUtils.defaultString(spaceType).toLowerCase(Locale.ENGLISH)) {
            case "cosinesimil":
                return VectorIndex.Distance.COSINE;
            case "l2":
                return VectorIndex.Distance.EUCLIDEAN;
            case "innerproduct":
                return VectorIndex.Distance.DOT;
            case "l1":
                return VectorIndex.Distance.MANHATTAN;
            case "hamming":
            case "hammingbit":
                return VectorIndex.Distance.HAMMING;
            default:
                // linf has no index of other databases
                return null;
        }
    }

    @Override
    protected String openPointInTime(String index) {
        return text(rest.post("/" + encode(index) + "/_search/point_in_time?keep_alive=" + KEEP_ALIVE, null),
                "pit_id");
    }

    @Override
    protected void closePointInTime(String pit) {
        try {
            ObjectNode body = RestClient.getMapper().createObjectNode();
            body.putArray("pit_id").add(pit);
            rest.send("DELETE", "/_search/point_in_time", body);
        } catch (RuntimeException e) {
            // A point in time which is not closed expires
        }
    }

    @Override
    protected String getPointInTimeSort() {
        return "_id";
    }

    @Override
    protected ObjectNode toNearestSearch(VdbCollection collection, VdbQuery query, int pageSize) {
        int k = query.getLimit() > 0 ? (int) query.getLimit() : pageSize;
        ObjectNode body = RestClient.getMapper().createObjectNode().put("size", k);
        ObjectNode knn = body.putObject("query").putObject("knn").putObject(sourceNameOf(collection,
                query.getNearestField())).put("k", k);
        knn.set("vector", toVectorNode(nearestVector(collection, query)));
        JsonNode filter = toQuery(collection, query);
        if (filter != null) {
            knn.set("filter", filter);
        }
        return body;
    }
}
