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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.MetricSource.Chart;
import com.github.sqljam.face.service.MetricSource.Series;
import com.github.sqljam.impexp.DbType;

/**
 * @Description: DashboardTest verifies the values of the dashboard: gauges as they are, counters a second, ratios of
 *               counters in percent, counters which went back, and the metrics of an embedded H2 database read in
 *               SqlJam, whose lost connection is opened again
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class DashboardTest {

    @TempDir
    File dir;

    /**
     * Raw values given one sample after the other
     */
    private static final class QueuedSource implements MetricSource {

        final Deque<Object> samples = new ArrayDeque<>();
        boolean closed;

        @Override
        public List<Chart> getCharts() {
            return List.of(new Chart("Sessions", Unit.COUNT, Series.gauge("Active", "active")), new Chart(
                    "Commits", Unit.PER_SECOND, Series.rate("Commits", "commits")), new Chart("CPU", Unit.PERCENT,
                    Series.ratio("Busy", "busy", "busy", "idle")));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Double> read() throws Exception {
            Object next = samples.removeFirst();
            if (next instanceof Exception) {
                throw (Exception) next;
            }
            return (Map<String, Double>) next;
        }

        @Override
        public Map<String, String> getUnavailable() {
            return Map.of();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void turnsCountersIntoRates() throws Exception {
        QueuedSource source = new QueuedSource();
        AtomicLong clock = new AtomicLong(10_000);
        DashboardSampler sampler = new DashboardSampler(source, clock::get);
        Chart sessions = source.getCharts().get(0);
        Chart commits = source.getCharts().get(1);
        Chart cpu = source.getCharts().get(2);
        source.samples.add(Map.of("active", 3.0, "commits", 100.0, "busy", 10.0, "idle", 90.0));
        DashboardSampler.Sample first = sampler.sample();
        assertEquals(3.0, first.get(sessions, sessions.getSeries().get(0)));
        // Rates need two samples
        assertNull(first.get(commits, commits.getSeries().get(0)));
        assertNull(first.get(cpu, cpu.getSeries().get(0)));

        clock.addAndGet(2000);
        source.samples.add(Map.of("active", 4.0, "commits", 160.0, "busy", 40.0, "idle", 100.0));
        DashboardSampler.Sample second = sampler.sample();
        assertEquals(12_000, second.getTime());
        assertEquals(30.0, second.get(commits, commits.getSeries().get(0)), 0.001);
        // 30 busy of 40
        assertEquals(75.0, second.get(cpu, cpu.getSeries().get(0)), 0.001);

        // A restart of the server: counters start again, no negative rate
        clock.addAndGet(2000);
        source.samples.add(Map.of("active", 1.0, "commits", 5.0, "busy", 40.0, "idle", 100.0));
        DashboardSampler.Sample restarted = sampler.sample();
        assertNull(restarted.get(commits, commits.getSeries().get(0)));
        // Nothing happened: no share
        assertNull(restarted.get(cpu, cpu.getSeries().get(0)));

        // A lost connection: the counters of the next connection start again
        source.samples.add(new SQLException("connection reset"));
        assertThrows(SQLException.class, sampler::sample);
        clock.addAndGet(2000);
        source.samples.add(Map.of("active", 1.0, "commits", 50.0));
        DashboardSampler.Sample again = sampler.sample();
        assertNull(again.get(commits, commits.getSeries().get(0)));
        assertEquals(1.0, again.get(sessions, sessions.getSeries().get(0)));
        assertEquals(List.of("busy", "idle"), cpu.getSeries().get(0).getKeys());
        assertEquals(List.of("active"), sessions.getSeries().get(0).getKeys());
    }

    @Test
    void readsMetricsOfEmbeddedDatabases() throws Exception {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName("h2");
        profile.setDbType(DbType.H2);
        profile.setDatabase(new File(dir, "db").getAbsolutePath());
        profile.setUsername("sa");
        profile.setPassword("");
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE T (ID INT)");
        }
        DatabaseSession session = new DatabaseSession(profile);
        try (JdbcMetrics metrics = new JdbcMetrics(profile, session)) {
            assertEquals(List.of("CPU of SqlJam", "Memory of SqlJam", "Sessions", "File", "Sample time"), metrics
                    .getCharts().stream().map(Chart::getTitle).toList());
            Map<String, Double> values = metrics.read();
            assertTrue(metrics.getUnavailable().isEmpty(), metrics.getUnavailable().toString());
            assertTrue(values.get("h2.sessions") >= 1);
            assertTrue(values.get("file.size") > 0);
            assertTrue(values.get("jvm.heap") > 0);
            assertTrue(values.get("jvm.cpu") >= 0);
            assertNotNull(values.get(JdbcMetrics.SAMPLE_TIME));
            assertEquals(new File(dir, "db.mv.db"), metrics.databaseFile());

            // The connection is lost: the sample fails, the next one opens a new connection
            metrics.getConnection().close();
            assertThrows(SQLException.class, metrics::read);
            assertNull(metrics.getConnection());
            assertTrue(metrics.read().get("h2.sessions") >= 1);
            assertTrue(metrics.getUnavailable().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    void readsEachQueryOnceASample() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:pairs")) {
            JdbcMetrics.Context context = new JdbcMetrics.Context(connection);
            String sql = "SELECT 'a', '1' UNION ALL SELECT 'a', '2' UNION ALL SELECT 'b', 'x' UNION ALL SELECT NULL, "
                    + "'3'";
            Map<String, Double> pairs = context.pairs(sql);
            // Rows of the same name are added, values which are no numbers are left out
            assertEquals(Map.of("a", 3.0), pairs);
            assertTrue(pairs == context.pairs(sql));
            assertEquals(Map.of("X", 1.5, "Y", 2.0), context.row("SELECT 1.5 AS x, '2' AS y"));
            SQLException failure = assertThrows(SQLException.class, () -> context.row("SELECT * FROM missing"));
            // The failure is kept for the other metrics of the query
            assertTrue(failure == assertThrows(SQLException.class, () -> context.row("SELECT * FROM missing")));
            assertEquals(Map.of(), context.row("SELECT 1 FROM DUAL WHERE 1 = 0"));
        }
    }
}
