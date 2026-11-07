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
package com.github.sqljam.face.vdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.service.TransferService;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.utils.RestException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbSqlResult;
import com.github.sqljam.vdb.it.VdbServer;

/**
 * @Description: VdbSqlIT runs SQL on an index of a real Elasticsearch and OpenSearch: pages of a cursor, the rows
 *               read into a DuckDB file with the types of their columns, vectors of OpenSearch told as vectors, and
 *               the rows exported like a table into an H2 database by the export of the application
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
class VdbSqlIT {

    private static final String INDEX = "sjvt_sql";
    private static final int DOCUMENTS = 25;

    @TempDir
    File dir;

    private static void createIndex(VdbServer server) {
        RestClient rest = server.rest();
        try {
            rest.delete("/" + INDEX);
        } catch (RestException e) {
            // Not there yet
        }
        boolean open = server == VdbServer.OPENSEARCH;
        Map<String, Object> vector = open ? Map.of("type", "knn_vector", "dimension", 3)
                : Map.of("type", "dense_vector", "dims", 3);
        Map<String, Object> properties = Map.of("name", Map.of("type", "keyword"), "n", Map.of("type", "integer"),
                "price", Map.of("type", "double"), "ok", Map.of("type", "boolean"), "at", Map.of("type", "date"),
                "note", Map.of("type", "text"), "embedding", vector);
        rest.put("/" + INDEX, open ? Map.of("settings", Map.of("index", Map.of("knn", true)), "mappings",
                Map.of("properties", properties)) : Map.of("mappings", Map.of("properties", properties)));
        StringBuilder bulk = new StringBuilder();
        for (int i = 1; i <= DOCUMENTS; i++) {
            bulk.append("{\"index\": {\"_index\": \"").append(INDEX).append("\", \"_id\": \"").append(i)
                    .append("\"}}\n").append(String.format("{\"name\": \"doc %d\", \"n\": %d, \"price\": %d.5,"
                    + " \"ok\": %b, \"at\": \"2024-01-%02dT10:00:00Z\", \"note\": \"文本 %d\", \"embedding\":"
                    + " [%d, 0.5, -1]}%n", i, i, i, i % 2 == 0, i, i, i));
        }
        rest.post("/_bulk?refresh=true", bulk.toString());
    }

    @ParameterizedTest(name = "sql of {0}")
    @EnumSource(value = VdbServer.class, names = {"ELASTICSEARCH", "OPENSEARCH"})
    void exportsRowsOfIndexLikeTable(VdbServer server) throws Exception {
        assumeTrue(server.isAvailable(), server + " is not available");
        createIndex(server);
        try (VdbClient client = server.createClient()) {
            // Pages of the cursor
            VdbSqlResult page = client.querySql("SELECT name, n, price, ok, at, note FROM " + INDEX
                    + " ORDER BY n", 10);
            List<Object> names = new ArrayList<>();
            int pages = 1;
            while (true) {
                page.getRows().forEach(row -> names.add(row.get(0)));
                if (!page.hasMore()) {
                    break;
                }
                page = client.nextSqlPage(page.getCursor());
                pages++;
            }
            assertEquals(DOCUMENTS, names.size());
            assertEquals("doc 1", names.get(0));
            assertTrue(pages >= 3, "pages: " + pages);

            // All rows in a DuckDB file with their types
            VdbSqlStaging.Staged staged = VdbSqlStaging.stage(client, "SELECT name, n, price, ok, at, note FROM "
                    + INDEX, INDEX, new AtomicBoolean(), rows -> { });
            assertEquals(DOCUMENTS, staged.rows);
            try (Connection connection = DriverManager.getConnection("jdbc:duckdb:" + staged.file
                    .getAbsolutePath()); Statement statement = connection.createStatement()) {
                List<String> types = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery("DESCRIBE \"" + INDEX + "\"")) {
                    while (rs.next()) {
                        types.add(rs.getString("column_name") + " " + rs.getString("column_type"));
                    }
                }
                assertTrue(types.containsAll(List.of("name VARCHAR", "n INTEGER", "price DOUBLE", "ok BOOLEAN",
                        "at TIMESTAMP")), types.toString());
                try (ResultSet rs = statement.executeQuery("SELECT count(*), sum(\"n\"), min(\"at\") FROM \"" + INDEX
                        + "\"")) {
                    rs.next();
                    assertEquals(DOCUMENTS, rs.getInt(1));
                    assertEquals(DOCUMENTS * (DOCUMENTS + 1) / 2, rs.getInt(2));
                    assertEquals("2024-01-01 10:00:00.0", rs.getTimestamp(3).toString());
                }
            }

            // OpenSearch returns vectors as arrays, they are told before the export
            if (server == VdbServer.OPENSEARCH) {
                VdbSqlResult withVectors = client.querySql("SELECT name, embedding FROM " + INDEX, 5);
                assertEquals(List.of("embedding"), VdbSqlStaging.vectorColumns(withVectors.getColumns(),
                        withVectors.getRows()));
                client.closeSqlCursor(withVectors.getCursor());
            }

            // Exported like a table into H2 by the export of the application
            ConnectionProfile results = new ConnectionProfile();
            results.setName(server + " query results");
            results.setDbType(DbType.DUCKDB);
            results.setDatabase(staged.file.getAbsolutePath());
            ConnectionProfile h2 = new ConnectionProfile();
            h2.setName("Target H2");
            h2.setDbType(DbType.H2);
            h2.setDatabase(new File(dir, "target").getAbsolutePath());
            h2.setUsername("sa");
            h2.setPassword("");
            TransferRequest request = new TransferRequest();
            request.setSource(results);
            request.getQueries().put("docs_of_index", "SELECT * FROM \"" + INDEX + "\"");
            request.setTarget(TransferRequest.Target.DATABASE);
            request.setTargetProfile(h2);
            request.setTargetSchema("PUBLIC");
            request.setExportMode(ExportMode.DDL_DATA);
            com.github.sqljam.it.CollectingListener listener = new com.github.sqljam.it.CollectingListener();
            new TransferService().transfer(request, listener);
            assertEquals(List.of(), listener.getErrors());
            try (Connection connection = DriverManager.getConnection(h2.getJdbcUrl(), "sa", "");
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*), SUM(N) FROM DOCS_OF_INDEX")) {
                rs.next();
                assertEquals(DOCUMENTS, rs.getInt(1));
                assertEquals(DOCUMENTS * (DOCUMENTS + 1) / 2, rs.getInt(2));
            }
        } finally {
            try {
                server.rest().delete("/" + INDEX);
            } catch (RestException e) {
                // Dropped already
            }
        }
    }
}
