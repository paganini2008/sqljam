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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbSqlResult;
import com.github.sqljam.vdb.VdbType;

/**
 * @Description: VdbSqlClientTest verifies the SQL of Elasticsearch (columns, rows) and OpenSearch (schema,
 *               datarows) on fake servers: the first page and the next pages of a cursor, the close of a cursor
 *               which the server refuses, the reasons of errors, values of JSON, and servers without SQL
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class VdbSqlClientTest {

    @Test
    void readsPagesOfElasticsearch() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("POST /_sql?format=json", body -> body.contains("\"cursor\"")
                    ? "{\"rows\": [[\"b\", 2, false]]}"
                    : "{\"columns\": [{\"name\": \"name\", \"type\": \"keyword\"}, {\"name\": \"n\", \"type\":"
                    + " \"long\"}, {\"name\": \"ok\", \"type\": \"boolean\"}], \"rows\": [[\"a\", 1, true]],"
                    + " \"cursor\": \"c1\"}")
                    .route("POST /_sql/close", 404, "{\"error\": \"gone\"}");
            try (VdbClient client = VdbType.ELASTICSEARCH.createClient(VdbClientsTest.source(VdbType.ELASTICSEARCH,
                    server))) {
                assertTrue(client.isSqlSupported());
                VdbSqlResult first = client.querySql("SELECT * FROM docs", 1);
                assertEquals("name", first.getColumns().get(0)[0]);
                assertEquals("long", first.getColumns().get(1)[1]);
                assertEquals(List.of(Arrays.asList("a", 1L, true)), first.getRows());
                assertTrue(first.hasMore());
                assertTrue(server.requests("POST /_sql").get(0).body.contains("\"fetch_size\":1"));
                VdbSqlResult next = client.nextSqlPage(first.getCursor());
                assertFalse(next.hasMore());
                assertTrue(next.getColumns().isEmpty());
                assertEquals(List.of(Arrays.asList("b", 2L, false)), next.getRows());
                // A refused close is fine, an empty cursor is not sent
                client.closeSqlCursor("c1");
                client.closeSqlCursor("");
                assertEquals(1, server.requests("POST /_sql/close").size());
            }
        }
    }

    @Test
    void readsPagesOfOpenSearch() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("POST /_plugins/_sql", "{\"schema\": [{\"name\": \"id\", \"type\": \"integer\"},"
                    + " {\"name\": \"embedding\", \"type\": \"nested\"}], \"datarows\": [[1, [0.5, -1]]],"
                    + " \"total\": 1, \"size\": 1, \"status\": 200}")
                    .route("POST /_plugins/_sql/close", "{\"succeeded\": true}");
            try (VdbClient client = VdbType.OPENSEARCH.createClient(VdbClientsTest.source(VdbType.OPENSEARCH,
                    server))) {
                VdbSqlResult result = client.querySql("SELECT * FROM docs", 0);
                assertEquals("embedding", result.getColumns().get(1)[0]);
                assertEquals(List.of(Arrays.asList(1L, "[0.5,-1]")), result.getRows());
                assertFalse(result.hasMore());
                assertFalse(server.requests("POST /_plugins/_sql").get(0).body.contains("fetch_size"));
                client.closeSqlCursor("c2");
                assertEquals(1, server.requests("POST /_plugins/_sql/close").size());
            }
        }
    }

    @Test
    void tellsReasonsOfErrors() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.route("POST /_sql", 400, "{\"error\": {\"root_cause\": [{\"type\": \"parsing_exception\","
                    + " \"reason\": \"line 1:1: mismatched input \\\"SELEC\\\"\"}], \"type\": \"parsing_exception\"}}");
            try (VdbClient client = VdbType.ELASTICSEARCH.createClient(VdbClientsTest.source(VdbType.ELASTICSEARCH,
                    server))) {
                VdbException e = assertThrows(VdbException.class, () -> client.querySql("SELEC x", 10));
                assertEquals("line 1:1: mismatched input \"SELEC\"", e.getMessage());
            }
        }
        assertEquals("plain message", ElasticsearchClient.sqlError("plain message"));
        assertNull(ElasticsearchClient.sqlError(null));
    }

    @Test
    void readsValuesOfJson() throws Exception {
        JsonNode values = RestClient.getMapper().readTree("[\"t\", 9223372036854775807, 18446744073709551615,"
                + " 1.25, false, null, {\"a\": 1}]");
        assertEquals("t", ElasticsearchClient.sqlValue(values.get(0)));
        assertEquals(Long.MAX_VALUE, ElasticsearchClient.sqlValue(values.get(1)));
        assertEquals(new BigInteger("18446744073709551615"), ElasticsearchClient.sqlValue(values.get(2)));
        assertEquals(new BigDecimal("1.25"), ElasticsearchClient.sqlValue(values.get(3)));
        assertEquals(Boolean.FALSE, ElasticsearchClient.sqlValue(values.get(4)));
        assertNull(ElasticsearchClient.sqlValue(values.get(5)));
        assertEquals("{\"a\":1}", ElasticsearchClient.sqlValue(values.get(6)));
        assertNull(ElasticsearchClient.sqlValue(null));
    }

    @Test
    void otherServersRunNoSql() throws Exception {
        try (FakeServer server = new FakeServer();
             VdbClient client = VdbType.QDRANT.createClient(VdbClientsTest.source(VdbType.QDRANT, server))) {
            assertFalse(client.isSqlSupported());
            assertThrows(VdbException.class, () -> client.querySql("SELECT 1", 10));
            assertThrows(VdbException.class, () -> client.nextSqlPage("c"));
            client.closeSqlCursor("c");
        }
    }
}
