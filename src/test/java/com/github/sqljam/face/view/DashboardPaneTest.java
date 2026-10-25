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
package com.github.sqljam.face.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.face.service.JdbcMetrics;
import com.github.sqljam.face.service.MetricSource;
import com.github.sqljam.face.service.MetricSource.Chart;
import com.github.sqljam.face.service.MetricSource.Series;
import com.github.sqljam.face.service.MetricSource.Unit;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: DashboardPaneTest shows the live charts of a dashboard: a chart a group of metrics with a line a
 *               metric, the values in their units, charts whose metrics the server does not tell are left out and
 *               listed, a lost connection is told, samples pause, resume and stop with the dashboard, whose
 *               metrics are closed with it. The info tab of a data source samples while its dashboard is shown.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class DashboardPaneTest {

    @TempDir
    File dir;

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        FxTestSupport.createContext(dir);
        stage.setScene(new Scene(new StackPane(), 1200, 800));
        stage.show();
    }

    /**
     * Sessions grow by one a sample, commits by 10, the cache is not told
     */
    private static final class CountingSource implements MetricSource {

        final AtomicInteger reads = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean failing;

        @Override
        public List<Chart> getCharts() {
            return List.of(new Chart("Sessions", Unit.COUNT, Series.gauge("Active", "active"), Series.gauge("Idle",
                    "idle")), new Chart("Commits", Unit.PER_SECOND, Series.rate("Commits", "commits")), new Chart(
                    "Cache", Unit.PERCENT, Series.ratio("Hits", "hits", "hits", "reads")), new Chart("Size",
                    Unit.BYTES, Series.gauge("Size", "size")));
        }

        @Override
        public Map<String, Double> read() throws Exception {
            if (failing) {
                throw new SQLException("Connection reset");
            }
            int read = reads.incrementAndGet();
            return Map.of("active", (double) read, "idle", 2.0, "commits", read * 10.0, "size", 3.5 * 1024 * 1024);
        }

        @Override
        public Map<String, String> getUnavailable() {
            return Map.of("hits", "permission denied for pg_statio");
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    void showsLiveCharts() {
        CountingSource source = new CountingSource();
        DashboardPane pane = FxTestSupport.call(() -> {
            DashboardPane created = new DashboardPane(() -> source);
            ((StackPane) stage.getScene().getRoot()).getChildren().setAll(created);
            return created;
        });
        FxTestSupport.run(() -> ((ComboBox<Integer>) pane.lookup("#dashboardIntervalCombo")).setValue(1));
        FxTestSupport.run(pane::start);
        FxTestSupport.waitUntil(() -> source.reads.get() >= 3);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> pane.getCharts().size() == 4 && pane.getCharts()
                .get(0).getData().get(0).getData().size() >= 3));
        List<LineChart<Number, Number>> charts = FxTestSupport.call(pane::getCharts);
        assertEquals("Sessions", charts.get(0).getTitle());
        assertEquals(2, charts.get(0).getData().size());
        assertTrue(charts.get(0).isLegendVisible());
        assertEquals("Commits (/s)", charts.get(1).getTitle());
        // Rates from the second sample on: 10 commits a second
        double rate = FxTestSupport.call(() -> charts.get(1).getData().get(0).getData().get(0).getYValue()
                .doubleValue());
        assertTrue(rate > 5 && rate < 20, String.valueOf(rate));
        // The cache is not told: its chart is left out and listed with the reason
        assertFalse(FxTestSupport.call(() -> charts.get(2).isVisible()));
        assertTrue(FxTestSupport.call(() -> pane.getUnavailableLabel().getText()).contains(
                "Cache / Hits: permission denied for pg_statio"));
        assertEquals("3.5 MB", FxTestSupport.call(() -> ((NumberAxis) charts.get(3).getYAxis())
                .getTickLabelFormatter().toString(3.5 * 1024 * 1024)));
        assertTrue(FxTestSupport.call(() -> pane.getStatusLabel().getText()).startsWith("Sampled at "));

        // Paused: no samples, resumed: samples again
        Button pause = (Button) pane.lookup("#dashboardPauseButton");
        FxTestSupport.run(pause::fire);
        assertEquals("Resume", FxTestSupport.call(pause::getText));
        int paused = source.reads.get();
        org.testfx.util.WaitForAsyncUtils.sleep(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(source.reads.get() <= paused + 1);
        FxTestSupport.run(pause::fire);
        FxTestSupport.waitUntil(() -> source.reads.get() > paused + 1);

        // Lost: told, the charts stay
        source.failing = true;
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> pane.getStatusLabel().getText()).startsWith(
                "Not connected, tried again with the next sample: Connection reset"));
        source.failing = false;
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> pane.getStatusLabel().getText()).startsWith(
                "Sampled at "));
        FxTestSupport.run(pane::close);
        FxTestSupport.waitUntil(source.closed::get);
    }

    @Test
    void formatsValues() {
        assertEquals("512 B", DashboardPane.format(Unit.BYTES, 512));
        assertEquals("2.0 KB", DashboardPane.format(Unit.BYTES_PER_SECOND, 2048));
        assertEquals("1.5 GB", DashboardPane.format(Unit.BYTES, 1.5 * 1024 * 1024 * 1024));
        assertEquals("42", DashboardPane.format(Unit.COUNT, 42));
        assertEquals("2.5", DashboardPane.format(Unit.PER_SECOND, 2.5));
        assertEquals("12k", DashboardPane.format(Unit.PER_SECOND, 12_345));
        assertEquals("3.2M", DashboardPane.format(Unit.COUNT, 3_210_000));
        assertEquals(" (%)", DashboardPane.unitSuffix(Unit.PERCENT));
        assertEquals(" (ms)", DashboardPane.unitSuffix(Unit.MILLIS));
        assertEquals(" (bytes/s)", DashboardPane.unitSuffix(Unit.BYTES_PER_SECOND));
        assertEquals("", DashboardPane.unitSuffix(Unit.BYTES));
    }

    /**
     * The info tab of a data source: its dashboard samples while it is shown
     */
    @Test
    void samplesTheDashboardOfADataSource() throws Exception {
        ConnectionProfile profile = UiDatabase.create(dir, "Embedded H2");
        DatabaseSession session = new DatabaseSession(profile);
        AtomicInteger created = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<JdbcMetrics> metrics = new java.util.concurrent.atomic
                .AtomicReference<>();
        InfoTab tab = FxTestSupport.call(() -> {
            InfoTab info = new InfoTab(profile.getName(), null, () -> Map.of(), () -> Map.of(), () -> {
                created.incrementAndGet();
                metrics.set(new JdbcMetrics(profile, session));
                return metrics.get();
            });
            TabPane tabs = new TabPane(info);
            ((StackPane) stage.getScene().getRoot()).getChildren().setAll(tabs);
            return info;
        });
        DashboardPane pane = tab.getDashboard();
        // Not shown: not sampled
        org.testfx.util.WaitForAsyncUtils.sleep(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0, created.get());
        FxTestSupport.run(tab::showDashboard);
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> pane.getCharts().size() == 5 && !pane.getCharts()
                .get(2).getData().get(0).getData().isEmpty()));
        assertEquals("Sessions", FxTestSupport.call(() -> pane.getCharts().get(2).getTitle()));
        assertEquals("", FxTestSupport.call(() -> pane.getUnavailableLabel().getText()));
        assertEquals(1, created.get());
        // Another tab is shown: no samples
        FxTestSupport.run(() -> {
            tab.getTabPane().getTabs().add(new javafx.scene.control.Tab("other"));
            tab.getTabPane().getSelectionModel().select(1);
        });
        int points = FxTestSupport.call(() -> pane.getCharts().get(2).getData().get(0).getData().size());
        org.testfx.util.WaitForAsyncUtils.sleep(4500, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(FxTestSupport.call(() -> pane.getCharts().get(2).getData().get(0).getData().size()) <= points + 1);
        // Removed with its data source: the dashboard is closed, its connection too
        FxTestSupport.run(() -> tab.getTabPane().getTabs().remove(tab));
        FxTestSupport.waitUntil(() -> metrics.get().getConnection() == null);
        session.close();
    }
}
