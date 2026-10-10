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

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.impexp.ExportListener;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;
import atlantafx.base.theme.Styles;

/**
 * @Description: ProgressDialog shows the progress of an export or import, it can be cancelled
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class ProgressDialog {

    private static final int MAX_LOG_ENTRIES = 5000;

    private final AppContext context;
    private final Stage stage = new Stage();
    private final Label statusLabel = new Label();
    private final Label overallLabel = new Label();
    private final Label percentLabel = new Label();
    private final ProgressBar overallBar = new ProgressBar(0);
    private final Label tableLabel = new Label();
    private final ProgressBar tableBar = new ProgressBar(0);
    private final Label elapsedLabel = new Label();
    private final ListView<LogEntry> logList = new ListView<>();
    private final Button cancelButton = new Button(Messages.get("button.cancel"), Icons.of(Icons.CANCEL));
    private final Button openFolderButton = new Button(Messages.get("progress.openFolder"), Icons.of(Icons.OPEN));
    private final Button viewPackageButton = new Button(Messages.get("package.view"), Icons.of(Icons.TABLE));
    private final Label manifestLabel = new Label();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final int totalTables;
    private int completedTables;
    private int errorCount;
    private long startTime;
    private Timeline timer;
    private File outputDirectory;
    private boolean finished;

    private static class LogEntry {

        private final String message;
        private final boolean error;

        LogEntry(String message, boolean error) {
            this.message = message;
            this.error = error;
        }
    }

    public ProgressDialog(Window owner, AppContext context, String title, int totalTables) {
        this.context = context;
        this.totalTables = totalTables;
        stage.initOwner(owner);
        Branding.applyIcons(stage);
        stage.initModality(Modality.NONE);
        stage.setTitle(title);
        statusLabel.setId("progressStatus");
        overallLabel.setId("overallLabel");
        percentLabel.setId("percentLabel");
        overallBar.setId("overallBar");
        tableLabel.setId("tableLabel");
        tableBar.setId("tableBar");
        elapsedLabel.setId("elapsedLabel");
        logList.setId("logList");
        cancelButton.setId("cancelButton");
        openFolderButton.setId("openFolderButton");
        viewPackageButton.setId("viewPackageButton");
        manifestLabel.setId("manifestLabel");

        statusLabel.getStyleClass().add("progress-status");
        statusLabel.setText(Messages.get("progress.running"));
        for (ProgressBar bar : new ProgressBar[]{overallBar, tableBar}) {
            bar.setMaxWidth(Double.MAX_VALUE);
            // Keeps the bars visible when the log list takes the remaining height
            bar.setMinHeight(Region.USE_PREF_SIZE);
            bar.getStyleClass().add(Styles.MEDIUM);
        }
        // Overall progress is reported by onProgress: rows of exports, bytes of package imports
        overallBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        percentLabel.getStyleClass().add("progress-percent");
        updateOverall();
        logList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(LogEntry item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("log-error");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.message);
                    if (item.error) {
                        getStyleClass().add("log-error");
                    }
                }
            }
        });
        logList.getStyleClass().addAll("log-list", Styles.DENSE);
        VBox.setVgrow(logList, Priority.ALWAYS);

        cancelButton.setOnAction(event -> {
            if (finished) {
                stage.close();
            } else {
                cancelled.set(true);
                cancelButton.setDisable(true);
                statusLabel.setText(Messages.get("progress.cancelling"));
            }
        });
        openFolderButton.setVisible(false);
        openFolderButton.setManaged(false);
        viewPackageButton.setVisible(false);
        viewPackageButton.setManaged(false);
        viewPackageButton.setOnAction(event -> {
            if (outputDirectory != null) {
                new PackageViewer(stage, context, outputDirectory).show();
            }
        });
        openFolderButton.setOnAction(event -> {
            if (outputDirectory != null) {
                context.getHostServices().showDocument(outputDirectory.toURI().toString());
            }
        });
        manifestLabel.getStyleClass().add("muted");
        // Keeps the file name visible for long paths
        manifestLabel.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        manifestLabel.setVisible(false);
        manifestLabel.setManaged(false);
        openFolderButton.setMinWidth(Region.USE_PREF_SIZE);
        viewPackageButton.setMinWidth(Region.USE_PREF_SIZE);
        cancelButton.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, viewPackageButton, openFolderButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, statusLabel, headerSpacer, elapsedLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        Region overallSpacer = new Region();
        HBox.setHgrow(overallSpacer, Priority.ALWAYS);
        HBox overallBox = new HBox(8, percentLabel, overallSpacer, overallLabel);
        overallBox.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(10, header, overallBox, overallBar, tableLabel, tableBar, logList, manifestLabel,
                buttons);
        root.setPadding(new Insets(16));
        root.getStyleClass().add("progress-dialog");
        Scene scene = new Scene(root, 760, 560);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            if (!finished) {
                cancelled.set(true);
            }
        });
    }

    Stage getStage() {
        return stage;
    }

    /**
     * Error entries of the log
     */
    long getErrorLogCount() {
        return logList.getItems().stream().filter(entry -> entry.error).count();
    }

    boolean isFinished() {
        return finished;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Listener of export/import events, events are delivered on the FX thread
     */
    public ExportListener getListener() {
        return new ExportListener() {
            @Override
            public void onTableStart(String catalogName, String schemaName, String tableName, long totalRows) {
                Platform.runLater(() -> {
                    tableLabel.setText(Messages.format("progress.table", qualify(catalogName, schemaName,
                            tableName), totalRows));
                    tableBar.setProgress(totalRows > 0 ? 0 : ProgressBar.INDETERMINATE_PROGRESS);
                });
            }

            @Override
            public void onTableProgress(String catalogName, String schemaName, String tableName, long processedRows,
                                        long totalRows) {
                Platform.runLater(() -> {
                    if (totalRows > 0) {
                        tableBar.setProgress(Math.min(1.0, (double) processedRows / totalRows));
                    }
                });
            }

            @Override
            public void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
                Platform.runLater(() -> {
                    completedTables++;
                    tableBar.setProgress(1);
                    updateOverall();
                    addLog(Messages.format("progress.tableDone", qualify(catalogName, schemaName, tableName),
                            processedRows), false);
                });
            }

            @Override
            public void onProgress(long processed, long total) {
                Platform.runLater(() -> updatePercent(processed, total));
            }

            @Override
            public void onMessage(String message) {
                Platform.runLater(() -> addLog(message, false));
            }

            @Override
            public void onError(String message, Throwable e) {
                Platform.runLater(() -> {
                    errorCount++;
                    addLog(message, true);
                });
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    private static String qualify(String catalogName, String schemaName, String tableName) {
        StringBuilder name = new StringBuilder();
        if (StringUtils.isNotBlank(schemaName)) {
            name.append(schemaName).append('.');
        } else if (StringUtils.isNotBlank(catalogName)) {
            name.append(catalogName).append('.');
        }
        return name.append(tableName).toString();
    }

    private void updateOverall() {
        if (totalTables > 0) {
            overallLabel.setText(Messages.format("progress.tables", completedTables, totalTables));
        } else {
            overallLabel.setText(Messages.format("progress.tablesUnknown", completedTables));
        }
    }

    private void updatePercent(long processed, long total) {
        if (total <= 0) {
            return;
        }
        double progress = Math.min(1.0, (double) processed / total);
        overallBar.setProgress(progress);
        percentLabel.setText(Messages.format("progress.percent", (int) Math.floor(progress * 100)));
    }

    private void addLog(String message, boolean error) {
        if (StringUtils.isBlank(message)) {
            return;
        }
        logList.getItems().add(new LogEntry(message, error));
        if (logList.getItems().size() > MAX_LOG_ENTRIES) {
            logList.getItems().remove(0, logList.getItems().size() - MAX_LOG_ENTRIES);
        }
        logList.scrollTo(logList.getItems().size() - 1);
    }

    /**
     * Shows the dialog and runs the job in background
     *
     * @param summary summary text of the job result when completed, may be null
     */
    public <T> void run(Callable<T> job, Function<T, String> summary, Runnable onCompleted) {
        startTime = System.currentTimeMillis();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateElapsed()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        updateElapsed();
        stage.show();
        addLog(Messages.get("progress.started"), false);
        TaskRunner.execute(() -> {
            try {
                T result = job.call();
                Platform.runLater(() -> finish(errorCount == 0 ? Messages.get("progress.completed")
                        : Messages.format("progress.completedWithErrors", errorCount), false, false,
                        summary != null ? summary.apply(result) : null, onCompleted));
            } catch (Throwable e) {
                boolean cancel = isCancellation(e);
                if (!cancel && log.isErrorEnabled()) {
                    log.error(e.getMessage(), e);
                }
                Platform.runLater(() -> {
                    if (!cancel) {
                        errorCount++;
                        addLog(StringUtils.defaultIfBlank(rootMessage(e), e.getClass().getName()), true);
                    }
                    finish(cancel ? Messages.get("progress.cancelled") : Messages.get("progress.failed"), true, cancel,
                            null, null);
                });
            }
        });
    }

    private boolean isCancellation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ExportCancelledException) {
                return true;
            }
        }
        return cancelled.get();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && StringUtils.isBlank(cause.getMessage())) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private void finish(String status, boolean failed, boolean cancelled, String summary, Runnable onCompleted) {
        finished = true;
        timer.stop();
        updateElapsed();
        statusLabel.setText(status);
        statusLabel.getStyleClass().add(failed ? "status-error" : "status-success");
        if (!failed) {
            updatePercent(1, 1);
        } else if (overallBar.getProgress() < 0) {
            overallBar.setProgress(0);
        }
        if (tableBar.getProgress() < 0) {
            tableBar.setProgress(failed ? 0 : 1);
        }
        if (StringUtils.isNotBlank(summary)) {
            addLog(summary, false);
        }
        // Cancelling is not an error
        addLog(status, failed && !cancelled);
        cancelButton.setText(Messages.get("button.close"));
        cancelButton.setGraphic(null);
        cancelButton.setDisable(false);
        if (!failed && outputDirectory != null) {
            openFolderButton.setVisible(true);
            openFolderButton.setManaged(true);
            viewPackageButton.setVisible(true);
            viewPackageButton.setManaged(true);
            File manifest = new File(outputDirectory, "manifest.json");
            if (manifest.exists()) {
                manifestLabel.setText(Messages.format("progress.manifest", manifest.getAbsolutePath()));
                manifestLabel.setTooltip(new Tooltip(manifest.getAbsolutePath()));
                manifestLabel.setVisible(true);
                manifestLabel.setManaged(true);
            }
        }
        if (onCompleted != null) {
            onCompleted.run();
        }
    }

    private void updateElapsed() {
        Duration elapsed = Duration.millis(System.currentTimeMillis() - startTime);
        long seconds = (long) elapsed.toSeconds();
        elapsedLabel.setText(Messages.format("progress.elapsed", String.format("%02d:%02d:%02d", seconds / 3600,
                (seconds % 3600) / 60, seconds % 60)));
    }
}
