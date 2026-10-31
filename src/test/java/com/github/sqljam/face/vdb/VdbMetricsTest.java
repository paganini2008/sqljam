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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import com.github.sqljam.face.service.DashboardSampler;
import com.github.sqljam.face.service.MetricSource;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbInfo;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.it.VdbServer;

/**
 * @Description: VdbMetricsTest verifies the dashboard of vector databases: the Prometheus text of a server, the
 *               collections and records which every server tells, and the metrics of the servers of the tests,
 *               sampled twice so that counters become rates
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbMetricsTest {

    @Test
    void readsPrometheusText() {
        Map<String, Double> values = VdbInfo.prometheus("# HELP rest_responses_total requests\n"
                + "# TYPE rest_responses_total counter\n"
                + "rest_responses_total{endpoint=\"/collections\",status=\"200\"} 5\n"
                + "rest_responses_total{endpoint=\"/points\",status=\"200\"} 7 1730000000000\n"
                + "memory_resident_bytes 1048576\n"
                + "rest_responses_duration_seconds_count{endpoint=\"/a\"} 3\n"
                + "app_info{name=\"qdrant\",version=\"1.12.0\"} 1\n"
                + "broken\n"
                + "nan_metric NaN\n"
                + "words_metric{a=\"b\"} not-a-number\n");
        assertEquals(12.0, values.get("rest_responses_total"));
        assertEquals(1048576.0, values.get("memory_resident_bytes"));
        assertEquals(1.0, values.get("app_info"));
        assertEquals(3.0, values.get("rest_responses_duration_seconds_count"));
        assertFalse(values.containsKey("broken"));
        assertFalse(values.containsKey("words_metric"));
        assertTrue(Double.isNaN(values.get("nan_metric")));
    }

    /**
     * Collections and records of any server: a collection which can not be counted is left out of the records
     */
    @Test
    void countsCollectionsAndRecords() {
        VdbClient client = new VdbClient() {
            @Override
            public VdbType getType() {
                return VdbType.QDRANT;
            }

            @Override
            public String getVersion() {
                return "1";
            }

            @Override
            public List<String> getCollections() {
                return List.of("a", "b", "denied", "uncounted");
            }

            @Override
            public VdbCollection describe(String collection) {
                if (collection.equals("denied")) {
                    throw new IllegalStateException("HTTP 403");
                }
                return new VdbCollection(collection);
            }

            @Override
            public long count(VdbCollection collection, VdbQuery query) {
                return collection.getName().equals("uncounted") ? -1 : collection.getName().equals("a") ? 3 : 4;
            }

            @Override
            public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
            }

            @Override
            public void close() {
            }
        };
        assertEquals(Map.of("collections", 4.0, "records", 7.0), client.getMetrics());
    }

    @ParameterizedTest
    @EnumSource(value = VdbServer.class, names = {"QDRANT", "ELASTICSEARCH", "OPENSEARCH", "MILVUS", "WEAVIATE",
            "CHROMA"})
    void samplesDashboards(VdbServer server) throws Exception {
        assumeTrue(server.isAvailable(), server + " is not available");
        VdbProfile profile = new VdbProfile();
        profile.setName(server.name());
        profile.setType(server.getType());
        profile.setUrl(server.getSource().getUrl());
        profile.setApiKey(server.getSource().getApiKey());
        profile.setUsername(server.getSource().getUsername());
        profile.setPassword(server.getSource().getPassword());
        profile.setCertificateTrusted(server.getSource().isCertificateTrusted());
        try (VdbMetrics metrics = new VdbMetrics(profile)) {
            DashboardSampler sampler = new DashboardSampler(metrics);
            sampler.sample();
            Thread.sleep(1200);
            DashboardSampler.Sample sample = sampler.sample();
            StringBuilder text = new StringBuilder(server.name()).append('\n');
            for (MetricSource.Chart chart : metrics.getCharts()) {
                for (MetricSource.Series series : chart.getSeries()) {
                    boolean missing = series.getKeys().stream().anyMatch(metrics.getUnavailable()::containsKey);
                    text.append(String.format("  %-24s %-14s %s%n", chart.getTitle(), series.getName(), missing
                            ? "not told" : sample.get(chart, series)));
                }
            }
            System.out.println(text);
            assertTrue(sample.getValues().containsKey("Collections/Collections"), text.toString());
            assertTrue(sample.getValues().containsKey("Sample time/All metrics"));
            if (server == VdbServer.ELASTICSEARCH || server == VdbServer.OPENSEARCH) {
                assertTrue(sample.getValues().containsKey("CPU/Nodes"), text.toString());
                assertTrue(sample.getValues().containsKey("JVM heap/Used"), text.toString());
            }
            if (server == VdbServer.QDRANT) {
                assertTrue(sample.getValues().containsKey("Requests/REST and gRPC"), text.toString());
                assertTrue(sample.getValues().containsKey("Memory/Resident"), text.toString());
            }
        }
    }
}
