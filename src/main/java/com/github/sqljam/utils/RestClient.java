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
package com.github.sqljam.utils;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: RestClient sends JSON requests to the REST API of a vector database by the HTTP client of the JDK,
 *               with the headers of its credentials. An error status of the server is a RestException with the
 *               status and the message of the server, a server which can not be reached is a RestException of
 *               status 0.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class RestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Characters of a response kept in the message of an error
     */
    private static final int MAX_ERROR_LENGTH = 500;

    private final String baseUrl;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String accessDeniedHint;
    private final HttpClient httpClient;
    private final Duration timeout;

    public RestClient(String baseUrl, boolean certificateTrusted, int timeoutSeconds) {
        this.baseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(baseUrl), "/");
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(this.timeout)
                .version(HttpClient.Version.HTTP_1_1);
        if (certificateTrusted) {
            builder.sslContext(trustingContext());
        }
        this.httpClient = builder.build();
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Hint of an answer 401 or 403 in the message of its RestException, e.g. whether credentials are given
     */
    public RestClient accessDeniedHint(String hint) {
        this.accessDeniedHint = hint;
        return this;
    }

    public String getAccessDeniedHint() {
        return accessDeniedHint;
    }

    public RestClient header(String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            headers.put(name, value);
        }
        return this;
    }

    public JsonNode get(String path) {
        return send("GET", path, null);
    }

    public JsonNode post(String path, Object body) {
        return send("POST", path, body);
    }

    public JsonNode put(String path, Object body) {
        return send("PUT", path, body);
    }

    public JsonNode delete(String path) {
        return send("DELETE", path, null);
    }

    /**
     * Sends a request, a path is relative to the base url, an absolute url is sent as it is (hosts of Pinecone
     * indexes). The body is a JSON node, a map or a list, or text which is sent as it is (NDJSON).
     *
     * @return the JSON of the response, NullNode for an empty response
     */
    public JsonNode send(String method, String path, Object body) {
        String url = path.startsWith("http://") || path.startsWith("https://") ? path : baseUrl + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Accept", "application/json");
        headers.forEach(builder::header);
        if (body != null) {
            String text;
            try {
                text = body instanceof String ? (String) body : MAPPER.writeValueAsString(body);
            } catch (IOException e) {
                throw new RestException("Unable to write the request: " + e.getMessage(), 0, e);
            }
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RestException(String.format("Unable to reach %s: %s", baseUrl, StringUtils.defaultIfBlank(
                    e.getMessage(), e.getClass().getSimpleName())), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestException("Interrupted: " + method + " " + path, 0, e);
        }
        String text = response.body();
        if (response.statusCode() >= 400) {
            boolean denied = response.statusCode() == 401 || response.statusCode() == 403;
            throw new RestException(String.format("%s %s: HTTP %d %s%s", method, path, response.statusCode(),
                    StringUtils.abbreviate(StringUtils.defaultString(text).trim(), MAX_ERROR_LENGTH),
                    denied && accessDeniedHint != null ? " (" + accessDeniedHint + ")" : ""),
                    response.statusCode(), null);
        }
        if (StringUtils.isBlank(text)) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            // A response which is not JSON, e.g. the text of a health check
            if (log.isDebugEnabled()) {
                log.debug("Response of {} {} is not JSON: {}", method, path, StringUtils.abbreviate(text, 200));
            }
            return MAPPER.getNodeFactory().textNode(text);
        }
    }

    /**
     * SSL context which trusts every certificate, for servers of self-signed certificates the user trusts
     */
    private static SSLContext trustingContext() {
        // An extended trust manager: the JDK verifies host names for other trust managers
        TrustManager[] trustManagers = {new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // Trusted by the user
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // Trusted by the user
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // Trusted by the user
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // Trusted by the user
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // Trusted by the user
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // Trusted by the user
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new java.security.SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new RestException("Unable to trust the certificate: " + e.getMessage(), 0, e);
        }
    }
}
