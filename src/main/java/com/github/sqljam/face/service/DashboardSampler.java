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
package com.github.sqljam.face.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * @Description: DashboardSampler reads the metrics of a source and turns them into the values of the series of the
 *               charts: gauges as they are, counters a second since the last sample, ratios of the growth of
 *               counters in percent. A counter which went back (a restart of the server) starts again.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class DashboardSampler {

    /**
     * Values of the series of one moment by chart and series, e.g. "Sessions/Active"
     */
    public static final class Sample {

        private final long time;
        private final Map<String, Double> values;

        Sample(long time, Map<String, Double> values) {
            this.time = time;
            this.values = values;
        }

        public long getTime() {
            return time;
        }

        public Map<String, Double> getValues() {
            return values;
        }

        public Double get(MetricSource.Chart chart, MetricSource.Series series) {
            return values.get(key(chart, series));
        }
    }

    private final MetricSource source;
    private final LongSupplier clock;
    private Map<String, Double> previous;
    private long previousTime;

    public DashboardSampler(MetricSource source) {
        this(source, System::currentTimeMillis);
    }

    DashboardSampler(MetricSource source, LongSupplier clock) {
        this.source = source;
        this.clock = clock;
    }

    public MetricSource getSource() {
        return source;
    }

    static String key(MetricSource.Chart chart, MetricSource.Series series) {
        return chart.getTitle() + "/" + series.getName();
    }

    /**
     * Reads the source once: rates and ratios have values from the second sample on
     */
    public Sample sample() throws Exception {
        Map<String, Double> raw;
        try {
            raw = source.read();
        } catch (Exception e) {
            // The counters of a new connection start again
            previous = null;
            throw e;
        }
        long time = clock.getAsLong();
        double seconds = previous != null ? (time - previousTime) / 1000.0 : 0;
        Map<String, Double> values = new LinkedHashMap<>();
        for (MetricSource.Chart chart : source.getCharts()) {
            for (MetricSource.Series series : chart.getSeries()) {
                Double value = value(series, raw, seconds);
                if (value != null) {
                    values.put(key(chart, series), value);
                }
            }
        }
        previous = new HashMap<>(raw);
        previousTime = time;
        return new Sample(time, values);
    }

    private Double value(MetricSource.Series series, Map<String, Double> raw, double seconds) {
        Double now = raw.get(series.getKey());
        switch (series.getKind()) {
            case GAUGE:
                return now;
            case RATE:
                Double grown = growth(series.getKey(), raw);
                return grown != null && seconds > 0 ? grown / seconds : null;
            default:
                Double part = growth(series.getKey(), raw);
                double total = 0;
                for (String key : series.getTotalKeys()) {
                    Double growth = growth(key, raw);
                    if (growth == null) {
                        return null;
                    }
                    total += growth;
                }
                // Nothing happened: no share to tell
                return part != null && total > 0 ? Math.min(100, Math.max(0, part * 100 / total)) : null;
        }
    }

    private Double growth(String key, Map<String, Double> raw) {
        Double now = raw.get(key);
        Double before = previous != null ? previous.get(key) : null;
        if (now == null || before == null || now < before) {
            return null;
        }
        return now - before;
    }
}
