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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.sqljam.face.service.MetricSource;
import com.github.sqljam.vdb.VdbClient;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: VdbMetrics reads the dashboard of a vector database by its REST API: the collections and their
 *               records of every server, and what a server tells beyond: CPU, heap of the JVM, indexing, searches and
 *               the store of Elasticsearch and OpenSearch, requests and memory of Qdrant. Metrics which a server does
 *               not tell are listed after the first sample, a server which can not be reached is tried again.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class VdbMetrics implements MetricSource {

    static final String SAMPLE_TIME = "sample.millis";

    private static final List<Chart> CHARTS = List.of(
            new Chart("Collections", Unit.COUNT, Series.gauge("Collections", "collections")),
            new Chart("Records", Unit.COUNT, Series.gauge("Records", "records")),
            new Chart("Records written", Unit.PER_SECOND, Series.rate("Records", "records")),
            new Chart("CPU", Unit.PERCENT, Series.gauge("Nodes", "cpu.percent")),
            new Chart("CPU cores used", Unit.COUNT, Series.gauge("Cores", "cpu.cores")),
            new Chart("JVM heap", Unit.BYTES, Series.gauge("Used", "heap.used"), Series.gauge("Maximum", "heap.max")),
            new Chart("Indexing and searches", Unit.PER_SECOND, Series.rate("Indexed", "indexing.total"), Series.rate(
                    "Searches", "search.total")),
            new Chart("Store", Unit.BYTES, Series.gauge("Size", "store.bytes")),
            new Chart("Requests", Unit.PER_SECOND, Series.rate("REST and gRPC", "requests")),
            new Chart("Memory", Unit.BYTES, Series.gauge("Resident", "memory.resident"), Series.gauge("Allocated",
                    "memory.allocated")),
            new Chart("Sample time", Unit.MILLIS, Series.gauge("All metrics", SAMPLE_TIME)));

    private final VdbProfile profile;
    private final Map<String, String> unavailable = new LinkedHashMap<>();
    private VdbClient client;
    private boolean sampled;

    public VdbMetrics(VdbProfile profile) {
        this.profile = profile;
    }

    @Override
    public List<Chart> getCharts() {
        return CHARTS;
    }

    @Override
    public synchronized Map<String, String> getUnavailable() {
        return new LinkedHashMap<>(unavailable);
    }

    @Override
    public synchronized Map<String, Double> read() throws Exception {
        long start = System.nanoTime();
        if (client == null) {
            client = profile.getType().createClient(profile.toSource());
        }
        Map<String, Double> values;
        try {
            values = new LinkedHashMap<>(client.getMetrics());
        } catch (RuntimeException e) {
            // The server is not reached, a new client tries again with the next sample
            close();
            throw e;
        }
        values.put(SAMPLE_TIME, (System.nanoTime() - start) / 1_000_000.0);
        if (!sampled) {
            sampled = true;
            for (Chart chart : CHARTS) {
                for (Series series : chart.getSeries()) {
                    series.getKeys().stream().filter(key -> !values.containsKey(key)).forEach(key -> unavailable
                            .putIfAbsent(key, "Not told by the REST API of " + profile.getType().getDisplayName()));
                }
            }
        }
        return values;
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("The client of the dashboard is closed already: {}", e.getMessage());
            }
            client = null;
        }
    }
}
