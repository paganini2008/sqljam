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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.view.DataSourceExtension;
import com.github.sqljam.face.view.SourceItem;
import com.github.sqljam.utils.RestException;
import com.github.sqljam.vdb.VdbType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * @Description: VdbSourceItemsTest browses vector databases of servers which differ from those of the tests: a
 *               Milvus user who may not list the databases sees the database of the source, or the collections of
 *               the default database, and a server without the route of partitions shows its collections without
 *               them
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbSourceItemsTest {

    @TempDir
    File dir;

    private HttpServer server;

    /**
     * Milvus which refuses databases/list (code 1800) and has no route of partitions
     */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/databases/list")) {
                reply(exchange, 200, "{\"code\": 1800, \"message\": \"permission denied\"}");
            } else if (path.endsWith("/collections/list")) {
                reply(exchange, 200, "{\"code\": 0, \"data\": [\"docs\", \"faq\"]}");
            } else {
                reply(exchange, 404, "{\"error\": \"no route\"}");
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private DataSourceExtension.Source source(String database) throws IOException {
        VdbProfileStore store = new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME));
        VdbProfile profile = new VdbProfile();
        profile.setName("Milvus of another team");
        profile.setType(VdbType.MILVUS);
        profile.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        profile.setUsername("reader");
        profile.setPassword("secret");
        profile.setDatabase(database);
        store.saveProfile(profile, true);
        return new VdbDataSourceExtension(store).getSources().get(0);
    }

    private static List<String> names(List<SourceItem> items) {
        return items.stream().map(item -> item.getKind() + " " + item.getName()).collect(Collectors.toList());
    }

    @Test
    void showsCollectionsWhenDatabasesAreNotListed() throws Exception {
        DataSourceExtension.Source source = source(null);
        List<SourceItem> items = source.loadItems(null);
        assertEquals(List.of("COLLECTION docs", "COLLECTION faq"), names(items));
        // Partitions are not listed by this server, the collection is read whole
        assertEquals(List.of(), source.loadItems(items.get(0)));
    }

    @Test
    void showsDatabaseOfSourceWhenDatabasesAreNotListed() throws Exception {
        DataSourceExtension.Source source = source("team_db");
        List<SourceItem> databases = source.loadItems(null);
        assertEquals(List.of("DATABASE team_db"), names(databases));
        List<SourceItem> collections = source.loadItems(databases.get(0));
        assertEquals(List.of("COLLECTION docs", "COLLECTION faq"), names(collections));
        assertEquals(List.of(), source.loadItems(collections.get(1)));
    }

    @Test
    void tellsRefusedKeys() {
        assertTrue(VdbDataSourceExtension.VdbSourceItem.isAccessDenied(new RestException("denied", 401, null)));
        assertTrue(VdbDataSourceExtension.VdbSourceItem.isAccessDenied(new IllegalStateException(new RestException(
                "Milvus: denied (code 1800)", 403, null))));
        assertFalse(VdbDataSourceExtension.VdbSourceItem.isAccessDenied(new RestException("missing", 404, null)));
        assertFalse(VdbDataSourceExtension.VdbSourceItem.isAccessDenied(new IOException("refused")));
    }
}
