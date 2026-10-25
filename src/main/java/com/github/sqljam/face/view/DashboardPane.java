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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.service.DashboardSampler;
import com.github.sqljam.face.service.MetricSource;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: DashboardPane shows the metrics of a data source in live line charts like the dashboards of DBeaver:
 *               a sample every few seconds while it is shown, the last 5 minutes of each chart. Charts of metrics
 *               which the server does not tell are left out and listed with their reasons. A lost connection is told
 *               and opened again by the next sample.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class DashboardPane extends BorderPane {

    static final int[] INTERVALS = {1, 2, 5, 10, 30};
    static final int WINDOW_SECONDS = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Supplier<MetricSource> sourceFactory;
    private final ComboBox<Integer> intervalCombo = new ComboBox<>();
    private final Button pauseButton = new Button(Messages.get("dashboard.pause"));
    private final Label statusLabel = new Label();
    private final Label unavailableLabel = new Label();
    private final TilePane charts = new TilePane();
    private final Map<MetricSource.Chart, LineChart<Number, Number>> chartViews = new LinkedHashMap<>();
    private final Map<String, XYChart.Series<Number, Number>> lines = new LinkedHashMap<>();
    private DashboardSampler sampler;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private boolean paused;
    private long startTime;

    public DashboardPane(Supplier<MetricSource> sourceFactory) {
        this.sourceFactory = sourceFactory;
        setId("dashboardPane");
        intervalCombo.setId("dashboardIntervalCombo");
        pauseButton.setId("dashboardPauseButton");
        statusLabel.setId("dashboardStatusLabel");
        unavailableLabel.setId("dashboardUnavailableLabel");
        statusLabel.getStyleClass().add("muted");
        unavailableLabel.getStyleClass().add("muted");
        unavailableLabel.setWrapText(true);
        for (int interval : INTERVALS) {
            intervalCombo.getItems().add(interval);
        }
        intervalCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer seconds) {
                return seconds == null ? "" : Messages.format("dashboard.every", seconds);
            }

            @Override
            public Integer fromString(String string) {
                return null;
            }
        });
        intervalCombo.setValue(2);
        intervalCombo.valueProperty().addListener((observable, old, interval) -> {
            if (future != null) {
                schedule();
            }
        });
        pauseButton.setOnAction(event -> setPaused(!paused));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, new Label(Messages.get("dashboard.sample")), intervalCombo, pauseButton, spacer,
                statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        charts.setId("dashboardCharts");
        charts.setHgap(12);
        charts.setVgap(12);
        charts.setPadding(new Insets(8));
        charts.setPrefTileWidth(460);
        charts.setPrefTileHeight(260);
        ScrollPane scroll = new ScrollPane(new VBox(8, charts, unavailableLabel));
        scroll.setFitToWidth(true);
        setTop(bar);
        setCenter(scroll);
    }

    /**
     * Samples while the dashboard is shown
     */
    public void start() {
        if (future != null || paused) {
            return;
        }
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sqljam-dashboard");
                thread.setDaemon(true);
                return thread;
            });
        }
        schedule();
    }

    private void schedule() {
        if (future != null) {
            future.cancel(false);
        }
        future = executor.scheduleWithFixedDelay(this::sampleOnce, 0, intervalCombo.getValue(), TimeUnit.SECONDS);
    }

    /**
     * Stops sampling, e.g. when another page is shown, the charts stay
     */
    public void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    /**
     * Stops sampling and closes the connection of the metrics
     */
    public void close() {
        stop();
        if (executor != null) {
            ScheduledExecutorService closing = executor;
            executor = null;
            closing.execute(() -> {
                if (sampler != null) {
                    sampler.getSource().close();
                }
            });
            closing.shutdown();
        }
    }

    void setPaused(boolean paused) {
        this.paused = paused;
        pauseButton.setText(Messages.get(paused ? "dashboard.resume" : "dashboard.pause"));
        if (paused) {
            stop();
        } else {
            start();
        }
    }

    /**
     * One sample in the background, the charts are updated on the FX thread
     */
    void sampleOnce() {
        try {
            if (sampler == null) {
                sampler = new DashboardSampler(sourceFactory.get());
                MetricSource source = sampler.getSource();
                Platform.runLater(() -> createCharts(source.getCharts()));
            }
            DashboardSampler.Sample sample = sampler.sample();
            Map<String, String> unavailable = sampler.getSource().getUnavailable();
            Platform.runLater(() -> show(sample, unavailable));
        } catch (Exception e) {
            String message = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            log.debug("A sample of the dashboard failed: {}", message);
            Platform.runLater(() -> {
                statusLabel.getStyleClass().setAll("label", "status-error");
                statusLabel.setText(Messages.format("dashboard.disconnected", message));
            });
        }
    }

    private void createCharts(List<MetricSource.Chart> definitions) {
        charts.getChildren().clear();
        chartViews.clear();
        lines.clear();
        startTime = System.currentTimeMillis();
        for (MetricSource.Chart definition : definitions) {
            NumberAxis time = new NumberAxis();
            time.setAutoRanging(false);
            time.setForceZeroInRange(false);
            time.setTickLabelFormatter(new StringConverter<>() {
                @Override
                public String toString(Number seconds) {
                    return LocalTime.ofInstant(Instant.ofEpochMilli(startTime + seconds.longValue() * 1000), ZoneId
                            .systemDefault()).format(TIME);
                }

                @Override
                public Number fromString(String string) {
                    return 0;
                }
            });
            NumberAxis values = new NumberAxis();
            values.setTickLabelFormatter(new StringConverter<>() {
                @Override
                public String toString(Number value) {
                    return format(definition.getUnit(), value.doubleValue());
                }

                @Override
                public Number fromString(String string) {
                    return 0;
                }
            });
            if (definition.getUnit() == MetricSource.Unit.PERCENT) {
                values.setAutoRanging(false);
                values.setLowerBound(0);
                values.setUpperBound(100);
                values.setTickUnit(25);
            }
            LineChart<Number, Number> chart = new LineChart<>(time, values);
            chart.setTitle(definition.getTitle() + unitSuffix(definition.getUnit()));
            chart.setCreateSymbols(false);
            chart.setAnimated(false);
            chart.setLegendVisible(definition.getSeries().size() > 1);
            chart.getStyleClass().add("dashboard-chart");
            for (MetricSource.Series series : definition.getSeries()) {
                XYChart.Series<Number, Number> line = new XYChart.Series<>();
                line.setName(series.getName());
                chart.getData().add(line);
                lines.put(definition.getTitle() + "/" + series.getName(), line);
            }
            chartViews.put(definition, chart);
            charts.getChildren().add(chart);
        }
    }

    private void show(DashboardSampler.Sample sample, Map<String, String> unavailable) {
        double seconds = (sample.getTime() - startTime) / 1000.0;
        for (Map.Entry<MetricSource.Chart, LineChart<Number, Number>> entry : chartViews.entrySet()) {
            MetricSource.Chart definition = entry.getKey();
            for (MetricSource.Series series : definition.getSeries()) {
                Double value = sample.get(definition, series);
                if (value == null) {
                    continue;
                }
                XYChart.Series<Number, Number> line = lines.get(definition.getTitle() + "/" + series.getName());
                line.getData().add(new XYChart.Data<>(seconds, value));
                // The last 5 minutes
                while (!line.getData().isEmpty() && line.getData().get(0).getXValue().doubleValue() < seconds
                        - WINDOW_SECONDS) {
                    line.getData().remove(0);
                }
            }
            NumberAxis time = (NumberAxis) entry.getValue().getXAxis();
            time.setLowerBound(Math.max(0, seconds - WINDOW_SECONDS));
            time.setUpperBound(Math.max(seconds, 10));
            time.setTickUnit(Math.max(10, Math.round((time.getUpperBound() - time.getLowerBound()) / 5)));
            // A chart whose metrics the server does not tell at all is left out
            boolean shown = definition.getSeries().stream().anyMatch(series -> series.getKeys().stream().noneMatch(
                    unavailable::containsKey));
            entry.getValue().setVisible(shown);
            entry.getValue().setManaged(shown);
        }
        statusLabel.getStyleClass().setAll("label", "muted");
        statusLabel.setText(Messages.format("dashboard.sampled", LocalTime.ofInstant(Instant.ofEpochMilli(sample
                .getTime()), ZoneId.systemDefault()).format(TIME)));
        List<String> missing = new ArrayList<>();
        for (MetricSource.Chart definition : chartViews.keySet()) {
            for (MetricSource.Series series : definition.getSeries()) {
                series.getKeys().stream().filter(unavailable::containsKey).findFirst().ifPresent(key -> missing.add(
                        definition.getTitle() + " / " + series.getName() + ": " + StringUtils.abbreviate(unavailable
                                .get(key), 160)));
            }
        }
        unavailableLabel.setText(missing.isEmpty() ? "" : Messages.get("dashboard.unavailable") + "\n" + missing
                .stream().collect(Collectors.joining("\n")));
    }

    static String unitSuffix(MetricSource.Unit unit) {
        switch (unit) {
            case PER_SECOND:
                return " (/s)";
            case BYTES_PER_SECOND:
                return " (bytes/s)";
            case PERCENT:
                return " (%)";
            case MILLIS:
                return " (ms)";
            default:
                return "";
        }
    }

    /**
     * A value of the axis in its unit, e.g. 1.5 MB or 12.3
     */
    static String format(MetricSource.Unit unit, double value) {
        if (unit == MetricSource.Unit.BYTES || unit == MetricSource.Unit.BYTES_PER_SECOND) {
            String[] units = {"B", "KB", "MB", "GB", "TB"};
            int index = 0;
            double size = value;
            while (Math.abs(size) >= 1024 && index < units.length - 1) {
                size /= 1024;
                index++;
            }
            return index == 0 ? String.format(Locale.ENGLISH, "%.0f B", size) : String.format(Locale.ENGLISH,
                    "%.1f %s", size, units[index]);
        }
        if (Math.abs(value) >= 1_000_000) {
            return String.format(Locale.ENGLISH, "%.1fM", value / 1_000_000);
        }
        if (Math.abs(value) >= 10_000) {
            return String.format(Locale.ENGLISH, "%.0fk", value / 1000);
        }
        return value == Math.rint(value) ? String.format(Locale.ENGLISH, "%.0f", value) : String.format(Locale
                .ENGLISH, "%.1f", value);
    }

    List<LineChart<Number, Number>> getCharts() {
        return new ArrayList<>(chartViews.values());
    }

    Label getUnavailableLabel() {
        return unavailableLabel;
    }

    Label getStatusLabel() {
        return statusLabel;
    }
}
