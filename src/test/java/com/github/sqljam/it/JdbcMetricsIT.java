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
package com.github.sqljam.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.DashboardSampler;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.face.service.JdbcMetrics;
import com.github.sqljam.face.service.MetricSource;

/**
 * @Description: JdbcMetricsIT samples the dashboard of each database twice: its metrics are read, counters become
 *               rates after the second sample, and most metrics are told by the server. The values and the metrics
 *               which a server does not tell are printed.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class JdbcMetricsIT {

    @ParameterizedTest
    @EnumSource(ItDatabase.class)
    void samplesDashboards(ItDatabase database) throws Exception {
        assumeTrue(database.isAvailable(), database + " is not available");
        ConnectionProfile profile = database.getSourceProfile();
        DatabaseSession session = new DatabaseSession(profile);
        try (JdbcMetrics metrics = new JdbcMetrics(profile, session)) {
            DashboardSampler sampler = new DashboardSampler(metrics);
            sampler.sample();
            Thread.sleep(1500);
            DashboardSampler.Sample sample = sampler.sample();
            int series = 0;
            int shown = 0;
            StringBuilder text = new StringBuilder(database.name()).append('\n');
            for (MetricSource.Chart chart : metrics.getCharts()) {
                for (MetricSource.Series line : chart.getSeries()) {
                    series++;
                    Double value = sample.get(chart, line);
                    boolean missing = line.getKeys().stream().anyMatch(metrics.getUnavailable()::containsKey);
                    if (!missing) {
                        shown++;
                    }
                    text.append(String.format("  %-28s %-22s %s%n", chart.getTitle(), line.getName(), missing
                            ? "UNAVAILABLE " + line.getKeys().stream().map(metrics.getUnavailable()::get).filter(
                                    reason -> reason != null).findFirst().orElse("") : value));
                }
            }
            System.out.println(text);
            Map<String, Double> values = sample.getValues();
            assertTrue(values.containsKey("Sample time/All metrics"));
            // As many as the server tells: most of them for the user of the tests
            assertTrue(shown * 2 >= series, database + " tells " + shown + " of " + series + "\n" + text);
        } finally {
            session.close();
        }
    }
}
