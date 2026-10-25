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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ReportRecorder;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.jdbc.ManualTransaction;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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
import javafx.stage.FileChooser;
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
    /**
     * Entries kept for Save Log, e.g. every skipped row of a large import
     */
    private static final int MAX_SAVED_LOG_ENTRIES = 1_000_000;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

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
    private final ObservableList<LogEntry> logEntries = FXCollections.observableArrayList();
    private final FilteredList<LogEntry> shownLogEntries = new FilteredList<>(logEntries);
    private final List<LogEntry> savedLogEntries = new ArrayList<>();
    private long droppedLogEntries;
    private final CheckBox errorsOnlyCheck = new CheckBox(Messages.get("progress.errorsOnly"));
    private final Button saveLogButton = new Button(Messages.get("progress.saveLog"), Icons.of(Icons.SAVE));
    private final Button cancelButton = new Button(Messages.get("button.cancel"), Icons.of(Icons.CANCEL));
    private final Button openFolderButton = new Button(Messages.get("progress.openFolder"), Icons.of(Icons.OPEN));
    private final Button viewPackageButton = new Button(Messages.get("package.view"), Icons.of(Icons.TABLE));
    private final Button commitButton = new Button(Messages.get("progress.commit"), Icons.of(Icons.START));
    private final Button rollbackButton = new Button(Messages.get("progress.rollback"), Icons.of(Icons.CANCEL));
    private final Label manifestLabel = new Label();
    /**
     * Opens the report of the transfer on its own page, shown when the report is saved
     */
    private final Button viewReportButton = new Button(Messages.get("report.view"), Icons.of(Icons.INFO));
    /**
     * Report of the transfer, its header is filled by the caller, its rows by the events
     */
    private final TransferReport report = new TransferReport();
    private final ReportRecorder recorder = new ReportRecorder(report);
    private ReportVerifier reportVerifier;
    private Throwable failure;
    /**
     * Commit or roll back of the user, null without a decision
     */
    private Boolean decision;
    /**
     * Summary of the result of a job whose transaction is committed or rolled back afterwards, for its report
     */
    private String resultSummary;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final int totalTables;
    private int completedTables;
    private int errorCount;
    private long startTime;
    private Timeline timer;
    private File outputDirectory;
    private boolean finished;
    /**
     * Transaction of an import waiting for the decision of the user: commit or roll back
     */
    private ManualTransaction pendingTransaction;
    private Runnable pendingCompleted;
    /**
     * Reminds the user of an import waiting for commit, its transaction holds locks, undo and log space
     */
    private Timeline reminder;
    private Duration reminderInterval;
    private long pendingSince;
    private boolean reminded;
    /**
     * The transaction is committed or rolled back without asking the user
     */
    private boolean automatic;
    /**
     * Errors of rows which were skipped do not roll back the transaction
     */
    private boolean skipErrors;

    private static class LogEntry {

        private final LocalTime time = LocalTime.now();
        private final String message;
        private final boolean error;

        LogEntry(String message, boolean error) {
            this.message = message;
            this.error = error;
        }

        @Override
        public String toString() {
            return LOG_TIME.format(time) + (error ? " ERROR " : " INFO  ") + message;
        }
    }

    /**
     * Counts the rows of the target tables after the transfer, in the background
     */
    @FunctionalInterface
    public interface ReportVerifier {
        void verify(TransferReport report) throws Exception;
    }

    public ProgressDialog(Window owner, AppContext context, String title, int totalTables) {
        this.context = context;
        this.totalTables = totalTables;
        report.setTitle(title);
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
        errorsOnlyCheck.setId("errorsOnlyCheck");
        saveLogButton.setId("saveLogButton");
        cancelButton.setId("cancelButton");
        openFolderButton.setId("openFolderButton");
        viewPackageButton.setId("viewPackageButton");
        manifestLabel.setId("manifestLabel");
        commitButton.setId("commitButton");
        rollbackButton.setId("rollbackButton");

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
        logList.setItems(shownLogEntries);
        // Errors and skipped rows only
        errorsOnlyCheck.selectedProperty().addListener((obs, oldValue, errorsOnly) -> shownLogEntries.setPredicate(
                errorsOnly ? entry -> entry.error : null));
        saveLogButton.setMinWidth(Region.USE_PREF_SIZE);
        saveLogButton.setOnAction(event -> saveLog());
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
        commitButton.getStyleClass().add(Styles.ACCENT);
        for (Button button : new Button[]{commitButton, rollbackButton}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setVisible(false);
            button.setManaged(false);
        }
        commitButton.setOnAction(event -> decide(true));
        rollbackButton.setOnAction(event -> decide(false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        viewReportButton.setId("viewReportButton");
        viewReportButton.setVisible(false);
        viewReportButton.setManaged(false);
        viewReportButton.setMinWidth(Region.USE_PREF_SIZE);
        viewReportButton.setOnAction(event -> {
            if (context.getReportViewer() != null) {
                context.getReportViewer().accept(report);
            }
        });
        HBox buttons = new HBox(8, errorsOnlyCheck, saveLogButton, spacer, viewReportButton, viewPackageButton,
                openFolderButton, rollbackButton, commitButton, cancelButton);
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
            if (pendingTransaction != null) {
                // The window stays until the import is rolled back
                event.consume();
                if (Dialogs.confirm(stage, Messages.get("progress.decide.title"),
                        Messages.get("progress.decide.close"))) {
                    decide(false, true);
                }
            } else if (!finished) {
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
        return logEntries.stream().filter(entry -> entry.error).count();
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
                recorder.tableStarted(catalogName, schemaName, tableName, totalRows);
                Platform.runLater(() -> {
                    tableLabel.setText(Messages.format("progress.table", qualify(catalogName, schemaName,
                            tableName), totalRows));
                    tableBar.setProgress(totalRows > 0 ? 0 : ProgressBar.INDETERMINATE_PROGRESS);
                });
            }

            @Override
            public void onTableProgress(String catalogName, String schemaName, String tableName, long processedRows,
                                        long totalRows) {
                recorder.tableProgress(catalogName, schemaName, tableName, processedRows);
                Platform.runLater(() -> {
                    if (totalRows > 0) {
                        tableBar.setProgress(Math.min(1.0, (double) processedRows / totalRows));
                    }
                });
            }

            @Override
            public void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
                recorder.tableEnded(catalogName, schemaName, tableName, processedRows);
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
                recorder.error(message);
                Platform.runLater(() -> {
                    errorCount++;
                    addLog(message, true);
                });
            }

            @Override
            public void onRowSkipped(String catalogName, String schemaName, String tableName, String key,
                                     Throwable e) {
                recorder.rowSkipped(catalogName, schemaName, tableName, key, e);
            }

            @Override
            public void onDdlExecuted(String statement, boolean successful) {
                recorder.ddlExecuted(statement, successful);
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
        LogEntry entry = new LogEntry(message, error);
        logEntries.add(entry);
        if (logEntries.size() > MAX_LOG_ENTRIES) {
            logEntries.remove(0, logEntries.size() - MAX_LOG_ENTRIES);
        }
        if (savedLogEntries.size() < MAX_SAVED_LOG_ENTRIES) {
            savedLogEntries.add(entry);
        } else {
            droppedLogEntries++;
        }
        if (!shownLogEntries.isEmpty()) {
            logList.scrollTo(shownLogEntries.size() - 1);
        }
    }

    /**
     * Saves the whole log, the window shows its last entries only
     */
    private void saveLog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("progress.saveLog"));
        chooser.setInitialFileName("sqljam-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(
                LocalDateTime.now()) + ".log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("progress.logFiles"),
                "*.log", "*.txt"));
        File file = chooser.showSaveDialog(stage);
        if (file != null) {
            try {
                writeLog(file);
                addLog(Messages.format("progress.logSaved", file.getAbsolutePath()), false);
            } catch (IOException e) {
                Dialogs.showError(stage, Messages.get("progress.saveLog"), e);
            }
        }
    }

    /**
     * Writes the log as text, one entry a line with its time and level
     */
    void writeLog(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(stage.getTitle());
        lines.add(Messages.format("progress.statusLine", statusLabel.getText(), errorCount));
        savedLogEntries.forEach(entry -> lines.add(entry.toString()));
        if (droppedLogEntries > 0) {
            lines.add(Messages.format("progress.logDropped", droppedLogEntries));
        }
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }

    /**
     * Shows the dialog and runs the job in background
     *
     * @param summary summary text of the job result when completed, may be null
     */
    public <T> void run(Callable<T> job, Function<T, String> summary, Runnable onCompleted) {
        run(job, summary, null, onCompleted);
    }

    /**
     * Shows the dialog and runs an import in one transaction which waits for the user to commit or roll back
     */
    public <T> void run(Callable<T> job, Function<T, String> summary, Supplier<ManualTransaction> transaction,
                        Runnable onCompleted) {
        run(job, summary, transaction, true, onCompleted);
    }

    /**
     * Shows the dialog and runs an import in background. An import in one transaction is committed when it
     * succeeds and rolled back when it fails or has errors, with confirm the user commits or rolls back.
     *
     * @param transaction the transaction opened by the job, null or a supplier of null for commits every N rows
     * @param confirm     whether a successful import waits for the user to commit or roll back
     */
    public <T> void run(Callable<T> job, Function<T, String> summary, Supplier<ManualTransaction> transaction,
                        boolean confirm, Runnable onCompleted) {
        startTime = System.currentTimeMillis();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateElapsed()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        updateElapsed();
        stage.show();
        recorder.start();
        addLog(Messages.get("progress.started"), false);
        TaskRunner.execute(() -> {
            try {
                T result = job.call();
                ManualTransaction pending = transaction != null ? transaction.get() : null;
                Platform.runLater(() -> {
                    String text = summary != null ? summary.apply(result) : null;
                    if (pending != null && confirm) {
                        awaitDecision(pending, text, onCompleted);
                    } else if (pending != null) {
                        completeTransaction(pending, text, onCompleted);
                    } else {
                        finish(errorCount == 0 ? Messages.get("progress.completed")
                                : Messages.format("progress.completedWithErrors", errorCount), false, false, text,
                                onCompleted);
                    }
                });
            } catch (Throwable e) {
                failure = e;
                boolean cancel = isCancellation(e);
                if (!cancel && log.isErrorEnabled()) {
                    log.error(e.getMessage(), e);
                }
                // Nothing of a failed or cancelled import is kept
                ManualTransaction pending = transaction != null ? transaction.get() : null;
                if (pending != null) {
                    pending.close();
                    Platform.runLater(() -> addLog(Messages.get("progress.rolledBack"), false));
                }
                Platform.runLater(() -> {
                    if (pending != null) {
                        report.setRolledBack(true);
                    }
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

    /**
     * The import is written in a transaction: the user is asked to commit or roll back. An import with errors is
     * rolled back, nothing of it is kept, unless the rows with errors were skipped.
     */
    private void awaitDecision(ManualTransaction transaction, String summary, Runnable onCompleted) {
        if (StringUtils.isNotBlank(summary)) {
            addLog(summary, false);
        }
        resultSummary = summary;
        pendingTransaction = transaction;
        pendingCompleted = onCompleted;
        if (transaction.isRolledBack() || (errorCount > 0 && !skipErrors)) {
            decide(false, false);
            return;
        }
        timer.stop();
        updateElapsed();
        completedTables = Math.max(completedTables, totalTables);
        updateOverall();
        updatePercent(1, 1);
        if (tableBar.getProgress() < 0) {
            tableBar.setProgress(1);
        }
        statusLabel.setText(Messages.get("progress.pending"));
        statusLabel.getStyleClass().add("status-pending");
        addLog(Messages.get("progress.pending.message"), false);
        if (!transaction.isTransactionalDdl()) {
            addLog(Messages.get("progress.pending.ddl"), false);
        }
        if (!transaction.getDeferredStatements().isEmpty()) {
            addLog(Messages.format("progress.pending.deferred", transaction.getDeferredStatements().size()), false);
        }
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        for (Button button : new Button[]{commitButton, rollbackButton}) {
            button.setVisible(true);
            button.setManaged(true);
        }
        startReminder();
        Platform.runLater(this::askDecision);
    }

    /**
     * Whether the import skips rows with errors: its transaction is committed (or confirmed) with the errors
     */
    public void setSkipErrors(boolean skipErrors) {
        this.skipErrors = skipErrors;
    }

    /**
     * Interval of the reminders, sqljam.import.confirm-commit.reminder-minutes by default
     */
    void setReminderInterval(Duration reminderInterval) {
        this.reminderInterval = reminderInterval;
    }

    private void startReminder() {
        pendingSince = System.currentTimeMillis();
        Duration interval = reminderInterval != null ? reminderInterval
                : Duration.minutes(context.getSettings().getConfirmCommitReminderMinutes());
        if (interval.lessThanOrEqualTo(Duration.ZERO)) {
            return;
        }
        reminder = new Timeline(new KeyFrame(interval, event -> remind()));
        reminder.setCycleCount(Timeline.INDEFINITE);
        reminder.play();
    }

    /**
     * Each reminder is logged, the first one is also shown without blocking the buttons of the window
     */
    private void remind() {
        if (pendingTransaction == null) {
            return;
        }
        long minutes = Math.round((System.currentTimeMillis() - pendingSince) / 60000.0);
        addLog(Messages.format("progress.reminder.log", minutes), false);
        statusLabel.setText(Messages.get("progress.pending") + " (" + minutes + " min)");
        if (!reminded) {
            reminded = true;
            Alert alert = new Alert(Alert.AlertType.WARNING, Messages.format("progress.reminder.message", minutes));
            Dialogs.initOwner(alert, stage);
            Branding.applyIcons(alert);
            alert.setTitle(Messages.get("progress.reminder.title"));
            alert.setHeaderText(Messages.get("progress.reminder.title"));
            alert.getDialogPane().setId("reminderPane");
            alert.show();
        }
    }

    /**
     * One transaction without confirm: committed when the import succeeds, rolled back when it has errors which
     * were not skipped
     */
    private void completeTransaction(ManualTransaction transaction, String summary, Runnable onCompleted) {
        if (StringUtils.isNotBlank(summary)) {
            addLog(summary, false);
        }
        resultSummary = summary;
        pendingTransaction = transaction;
        pendingCompleted = onCompleted;
        automatic = true;
        decide(!transaction.isRolledBack() && (errorCount == 0 || skipErrors), false);
    }

    /**
     * Asks the user to commit or roll back, closing the question keeps both buttons in the window
     */
    private void askDecision() {
        if (pendingTransaction == null || !stage.isShowing()) {
            return;
        }
        ButtonType commit = new ButtonType(Messages.get("progress.commit"), ButtonBar.ButtonData.OK_DONE);
        ButtonType rollback = new ButtonType(Messages.get("progress.rollback"), ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, Messages.format("progress.decide.message",
                stage.getTitle()), commit, rollback, ButtonType.CANCEL);
        Dialogs.initOwner(alert, stage);
        Branding.applyIcons(alert);
        alert.setTitle(Messages.get("progress.decide.title"));
        alert.setHeaderText(Messages.get("progress.decide.title"));
        alert.getDialogPane().setId("decisionPane");
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setText(Messages.get("progress.decide.later"));
        alert.showAndWait().ifPresent(type -> {
            if (type == commit) {
                decide(true);
            } else if (type == rollback) {
                decide(false);
            }
        });
    }

    private void decide(boolean commit) {
        decide(commit, false);
    }

    /**
     * Commits or rolls back the pending transaction in background, the window is closed afterwards if asked
     */
    private void decide(boolean commit, boolean close) {
        ManualTransaction transaction = pendingTransaction;
        if (transaction == null || transaction.isFinished()) {
            return;
        }
        decision = commit;
        commitButton.setDisable(true);
        rollbackButton.setDisable(true);
        statusLabel.setText(Messages.get(commit ? "progress.committing" : "progress.rollingBack"));
        // Skipped rows do not roll back the import, the user may still do it
        boolean errors = transaction.isRolledBack() || (errorCount > 0 && !skipErrors);
        int skippedErrors = errorCount;
        TaskRunner.run(() -> {
            try {
                if (commit) {
                    return transaction.commit();
                }
                transaction.rollback();
                return Collections.<String>emptyList();
            } finally {
                transaction.close();
            }
        }, failures -> {
            failures.forEach(failure -> addLog(failure, true));
            errorCount += failures.size();
            String status;
            if (!commit) {
                status = Messages.get(errors ? "progress.rolledBackErrors" : "progress.rolledBack");
            } else if (!failures.isEmpty()) {
                status = Messages.format("progress.committedWithErrors", failures.size());
            } else if (skippedErrors > 0) {
                // Rows with errors were skipped, the others are committed
                status = Messages.format(automatic ? "progress.completedWithErrors" : "progress.committedSkipped",
                        skippedErrors);
            } else {
                status = Messages.get(automatic ? "progress.completed" : "progress.committed");
            }
            completeDecision(status, !commit, close);
        }, e -> {
            errorCount++;
            // The commit or the roll back failed, the report tells why
            failure = e;
            addLog(StringUtils.defaultIfBlank(rootMessage(e), e.getClass().getName()), true);
            completeDecision(Messages.get("progress.failed"), true, close);
        });
    }

    private void completeDecision(String status, boolean failed, boolean close) {
        if (reminder != null) {
            reminder.stop();
            reminder = null;
        }
        Runnable onCompleted = pendingCompleted;
        pendingTransaction = null;
        pendingCompleted = null;
        statusLabel.getStyleClass().remove("status-pending");
        for (Button button : new Button[]{commitButton, rollbackButton}) {
            button.setVisible(false);
            button.setManaged(false);
        }
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        // A roll back by the user is not an error of the import
        finish(status, failed, failed && errorCount == 0, null, onCompleted);
        if (close) {
            stage.close();
        }
    }

    /**
     * Whether the import waits for the user to commit or roll back
     */
    boolean isPending() {
        return pendingTransaction != null;
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
            // Scripts of a package are imported file by file, every table is complete at the end
            completedTables = Math.max(completedTables, totalTables);
            updateOverall();
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
        finishReport(failed, cancelled, summary);
        if (onCompleted != null) {
            onCompleted.run();
        }
    }

    /**
     * Status of the report by the end: cancelled, rolled back by the user, failed, committed or completed
     */
    TransferReport.Status reportStatus(boolean failed, boolean cancelled) {
        if (cancelled) {
            return Boolean.FALSE.equals(decision) ? TransferReport.Status.ROLLED_BACK : TransferReport.Status.CANCELLED;
        }
        if (failed) {
            return Boolean.FALSE.equals(decision) ? TransferReport.Status.ROLLED_BACK : TransferReport.Status.FAILED;
        }
        if (Boolean.TRUE.equals(decision) && !automatic) {
            return TransferReport.Status.COMMITTED;
        }
        return errorCount > 0 || report.getSkippedRowCount() > 0 ? TransferReport.Status.COMPLETED_WITH_ERRORS
                : TransferReport.Status.COMPLETED;
    }

    /**
     * The report is completed: its status, the rows of the target tables, and it is saved in the history.
     * A report which can not be counted or saved is shown all the same.
     */
    private void finishReport(boolean failed, boolean cancelled, String summary) {
        TransferReport.Status status = reportStatus(failed, cancelled);
        if (status == TransferReport.Status.ROLLED_BACK) {
            report.setRolledBack(true);
        }
        recorder.finish(status, failed && !cancelled ? failure : null, summary != null ? summary : resultSummary);
        ReportVerifier verifier = reportVerifier;
        TaskRunner.run(() -> {
            if (verifier != null) {
                try {
                    verifier.verify(report);
                } catch (Exception e) {
                    log.warn("Rows of the target are not counted: {}", e.getMessage());
                }
            }
            context.getReportStore().save(report);
            return report;
        }, saved -> showReportButton(), e -> {
            addLog(Messages.format("report.notSaved", StringUtils.defaultIfBlank(e.getMessage(), e.getClass()
                    .getSimpleName())), true);
            showReportButton();
        });
    }

    private void showReportButton() {
        viewReportButton.setVisible(true);
        viewReportButton.setManaged(true);
    }

    /**
     * Report of the transfer, its header is filled by the caller before the transfer runs
     */
    public TransferReport getReport() {
        return report;
    }

    public void setReportVerifier(ReportVerifier reportVerifier) {
        this.reportVerifier = reportVerifier;
    }

    private void updateElapsed() {
        Duration elapsed = Duration.millis(System.currentTimeMillis() - startTime);
        long seconds = (long) elapsed.toSeconds();
        elapsedLabel.setText(Messages.format("progress.elapsed", String.format("%02d:%02d:%02d", seconds / 3600,
                (seconds % 3600) / 60, seconds % 60)));
    }
}
