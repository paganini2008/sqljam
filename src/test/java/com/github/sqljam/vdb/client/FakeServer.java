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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

/**
 * @Description: FakeServer answers requests of a vector database client with JSON of its routes: the route of the
 *               longest prefix of "METHOD /path?query" answers, by a fixed text or by a function of the request body.
 *               Requests are recorded with their headers.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
final class FakeServer implements AutoCloseable {

    /**
     * A request: method, path with the query, body and the authorization header
     */
    static final class Request {
        final String target;
        final String body;
        final String authorization;

        Request(String target, String body, String authorization) {
            this.target = target;
            this.body = body;
            this.authorization = authorization;
        }
    }

    private final HttpServer server;
    private final Map<String, Function<String, String>> routes = new LinkedHashMap<>();
    private final Map<String, Integer> statuses = new LinkedHashMap<>();
    final List<Request> requests = new ArrayList<>();

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String target = exchange.getRequestMethod() + " " + exchange.getRequestURI().toString();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(new Request(target, body, exchange.getRequestHeaders().getFirst("Authorization")));
            }
            String route = null;
            for (String prefix : routes.keySet()) {
                if (target.startsWith(prefix) && (route == null || prefix.length() > route.length())) {
                    route = prefix;
                }
            }
            byte[] response = (route != null ? routes.get(route).apply(body) : "{\"error\": \"no route\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(route != null ? statuses.getOrDefault(route, 200) : 404,
                    response.length == 0 ? -1 : response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    FakeServer route(String prefix, String response) {
        return route(prefix, body -> response);
    }

    FakeServer route(String prefix, int status, String response) {
        statuses.put(prefix, status);
        return route(prefix, body -> response);
    }

    FakeServer route(String prefix, Function<String, String> response) {
        routes.put(prefix, response);
        return this;
    }

    String getUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Requests whose method and path start with the prefix
     */
    List<Request> requests(String prefix) {
        List<Request> matched = new ArrayList<>();
        synchronized (requests) {
            requests.stream().filter(request -> request.target.startsWith(prefix)).forEach(matched::add);
        }
        return matched;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
