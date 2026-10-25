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

import java.util.List;
import java.util.Map;

/**
 * @Description: MetricSource is where the dashboard of a data source reads its metrics: the charts with their
 *               series, and the raw values of a moment. A value which can not be read (no permission, another
 *               version) is left out and told with its reason, the others are read all the same.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public interface MetricSource extends AutoCloseable {

    enum Unit {
        COUNT, PER_SECOND, BYTES, BYTES_PER_SECOND, PERCENT, MILLIS
    }

    enum Kind {
        /**
         * The value as it is read, e.g. sessions
         */
        GAUGE,
        /**
         * A counter which only grows, shown a second, e.g. commits
         */
        RATE,
        /**
         * The growth of a counter of the growth of others in percent, e.g. busy time of busy and idle time
         */
        RATIO
    }

    /**
     * A line of a chart: its name and the keys of the raw values it is made of
     */
    final class Series {

        private final String name;
        private final Kind kind;
        private final String key;
        private final List<String> totalKeys;

        private Series(String name, Kind kind, String key, List<String> totalKeys) {
            this.name = name;
            this.kind = kind;
            this.key = key;
            this.totalKeys = totalKeys;
        }

        public static Series gauge(String name, String key) {
            return new Series(name, Kind.GAUGE, key, List.of());
        }

        public static Series rate(String name, String key) {
            return new Series(name, Kind.RATE, key, List.of());
        }

        /**
         * Growth of the key of the growth of the sum of the total keys, in percent
         */
        public static Series ratio(String name, String key, String... totalKeys) {
            return new Series(name, Kind.RATIO, key, List.of(totalKeys));
        }

        public String getName() {
            return name;
        }

        public Kind getKind() {
            return kind;
        }

        public String getKey() {
            return key;
        }

        public List<String> getTotalKeys() {
            return totalKeys;
        }

        /**
         * Keys of the raw values of the series
         */
        public List<String> getKeys() {
            if (totalKeys.isEmpty()) {
                return List.of(key);
            }
            List<String> keys = new java.util.ArrayList<>(List.of(key));
            totalKeys.stream().filter(total -> !total.equals(key)).forEach(keys::add);
            return keys;
        }
    }

    final class Chart {

        private final String title;
        private final Unit unit;
        private final List<Series> series;

        public Chart(String title, Unit unit, Series... series) {
            this.title = title;
            this.unit = unit;
            this.series = List.of(series);
        }

        public String getTitle() {
            return title;
        }

        public Unit getUnit() {
            return unit;
        }

        public List<Series> getSeries() {
            return series;
        }
    }

    List<Chart> getCharts();

    /**
     * Raw values of this moment by key, keys which can not be read are left out
     *
     * @throws Exception when the source can not be reached at all
     */
    Map<String, Double> read() throws Exception;

    /**
     * Keys which can not be read with the reason, they are not read again
     */
    Map<String, String> getUnavailable();

    @Override
    void close();
}
