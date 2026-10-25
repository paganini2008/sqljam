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

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.impexp.ExportListener;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SyncScheduler runs the sync tasks in SqlJam while it is open, like the schedulers of DBeaver and
 *               DataGrip: every N minutes or every day at a time, and Run Now. A task never runs twice at once, a run
 *               which was missed while SqlJam was closed runs once when it starts. Errors of the connection are run
 *               again after a delay which doubles, errors of the data are not. Every run leaves a report in the
 *               history, the watermarks move on after a successful run only.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class SyncScheduler implements AutoCloseable {

    /**
     * Waits between the attempts of a run
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final SyncTaskStore store;
    private final ReportStore reportStore;
    private final Map<SyncTask.Kind, SyncEngine> engines = new EnumMap<>(SyncTask.Kind.class);
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    private final Set<Consumer<SyncTask>> listeners = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "sqljam-sync");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledExecutorService ticker;
    private LongSupplier clock = System::currentTimeMillis;
    private Sleeper sleeper = Thread::sleep;
    private ZoneId zone = ZoneId.systemDefault();
    private long retryDelayMillis = 30_000;
    private volatile boolean closed;
    /**
     * What starts the runs of this scheduler, kept in the reports and the tasks
     */
    private String origin = "SqlJam";

    public SyncScheduler(SyncTaskStore store, ReportStore reportStore) {
        this.store = store;
        this.reportStore = reportStore;
    }

    public void register(SyncTask.Kind kind, SyncEngine engine) {
        engines.put(kind, engine);
    }

    public SyncTaskStore getStore() {
        return store;
    }

    /**
     * Tells a task which changed: it started, ended, or was saved
     */
    public void addListener(Consumer<SyncTask> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<SyncTask> listener) {
        listeners.remove(listener);
    }

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    void setZone(ZoneId zone) {
        this.zone = zone;
    }

    void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    /**
     * File of the lock of a task: SqlJam and the command line never run a task at once
     */
    File lockFile(String taskId) {
        return new File(new File(store.getFile().getParentFile(), "sync-locks"), taskId + ".lock");
    }

    /**
     * Whether another process runs the task now, e.g. the command line started by the system
     */
    public boolean isRunningElsewhere(String taskId) {
        if (isRunning(taskId)) {
            return false;
        }
        File file = lockFile(taskId);
        if (!file.exists()) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                lock.release();
                return false;
            }
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public void setRetryDelayMillis(long retryDelayMillis) {
        this.retryDelayMillis = Math.max(0, retryDelayMillis);
    }

    /**
     * Looks for tasks to run every given seconds
     */
    public synchronized void start(long tickSeconds) {
        if (ticker != null || closed) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqljam-sync-ticker");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.error("Sync tasks are not checked: {}", e.getMessage(), e);
            }
        }, 0, Math.max(1, tickSeconds), TimeUnit.SECONDS);
    }

    public boolean isRunning(String taskId) {
        AtomicBoolean flag = running.get(taskId);
        return flag != null && flag.get();
    }

    /**
     * Time of the next run, -1 for tasks which run by Run Now only
     */
    public long nextRunAt(SyncTask task) {
        return nextRunAt(task, clock.getAsLong());
    }

    long nextRunAt(SyncTask task, long now) {
        if (!task.isEnabled() || task.getScheduleType() == SyncTask.ScheduleType.MANUAL) {
            return -1;
        }
        // A new task waits for its first time, the creation stands for its last run
        long last = Math.max(task.getLastRunAt(), task.getCreatedAt());
        if (task.getScheduleType() == SyncTask.ScheduleType.INTERVAL) {
            // Overdue after SqlJam was closed: due at once
            return last + Math.max(1, task.getIntervalMinutes()) * 60_000L;
        }
        LocalTime time = dailyTime(task);
        ZonedDateTime today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).atTime(time).atZone(zone);
        ZonedDateTime previous = today.toInstant().toEpochMilli() <= now ? today : today.minusDays(1);
        long previousMillis = previous.toInstant().toEpochMilli();
        // The time of today passed while SqlJam was closed: it runs now
        return last < previousMillis ? previousMillis : previous.plusDays(1).toInstant().toEpochMilli();
    }

    static LocalTime dailyTime(SyncTask task) {
        try {
            return LocalTime.parse(StringUtils.defaultIfBlank(task.getDailyTime(), "02:00"));
        } catch (DateTimeParseException e) {
            return LocalTime.of(2, 0);
        }
    }

    /**
     * Runs the tasks which are due and not running
     */
    public void tick() {
        long now = clock.getAsLong();
        for (SyncTask task : store.getTasks()) {
            // Tasks of the system scheduler run by the command line, not twice
            if (task.getRunner() == SyncTask.Runner.SYSTEM) {
                continue;
            }
            long next = nextRunAt(task, now);
            if (next >= 0 && next <= now && !isRunning(task.getId())) {
                runNow(task.getId());
            }
        }
    }

    /**
     * Runs the task in the background, the future has its report, or is null when it runs already
     */
    public CompletableFuture<TransferReport> runNow(String taskId) {
        AtomicBoolean flag = running.computeIfAbsent(taskId, id -> new AtomicBoolean());
        if (closed || !flag.compareAndSet(false, true)) {
            return null;
        }
        CompletableFuture<TransferReport> future = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                TransferReport report = null;
                Throwable failure = null;
                try {
                    report = runLocked(taskId);
                } catch (Throwable e) {
                    failure = e;
                } finally {
                    // The task is told as ended before the future completes
                    flag.set(false);
                    store.findTask(taskId).ifPresent(this::changed);
                }
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else {
                    future.complete(report);
                }
            });
        } catch (RuntimeException e) {
            flag.set(false);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Stops a running task: it is cancelled before its next page, its transaction rolled back
     */
    public void cancel(String taskId) {
        AtomicBoolean flag = cancelled.computeIfAbsent(taskId, id -> new AtomicBoolean());
        flag.set(true);
    }

    /**
     * Runs the task holding its lock, null when another process runs it
     */
    private TransferReport runLocked(String taskId) throws IOException {
        File file = lockFile(taskId);
        Files.createDirectories(file.getParentFile().toPath());
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption
                .WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                log.info("Sync task {} runs in another process, it is not run here", taskId);
                return null;
            }
            try {
                return store.findTask(taskId).map(this::run).orElse(null);
            } finally {
                lock.release();
            }
        }
    }

    private void changed(SyncTask task) {
        for (Consumer<SyncTask> listener : listeners) {
            try {
                listener.accept(task);
            } catch (RuntimeException e) {
                log.warn("A listener of sync tasks failed: {}", e.getMessage());
            }
        }
    }

    /**
     * One run with its attempts, its report is saved whatever its end
     */
    TransferReport run(SyncTask task) {
        AtomicBoolean cancel = cancelled.computeIfAbsent(task.getId(), id -> new AtomicBoolean());
        cancel.set(false);
        changed(task);
        SyncEngine engine = engines.get(task.getKind());
        TransferReport report = new TransferReport();
        ReportRecorder recorder = new ReportRecorder(report);
        recorder.start();
        TransferReport.Status status;
        Throwable failure = null;
        Map<String, SyncTask.Watermark> watermarks = null;
        int attempts = 0;
        while (true) {
            attempts++;
            int errorsBefore = report.getErrors().size();
            try {
                if (engine == null) {
                    throw new SyncErrors.PermanentException("Tasks of " + task.getKind() + " can not run here.");
                }
                recorder.restart();
                engine.describe(task, report);
                watermarks = engine.run(task, report, listener(recorder, cancel));
                // Errors of the attempts before, which were run again, do not count
                status = report.getErrors().size() == errorsBefore && report.getSkippedRowCount() == 0
                        ? TransferReport.Status.COMPLETED : TransferReport.Status.COMPLETED_WITH_ERRORS;
                break;
            } catch (Throwable e) {
                failure = e;
                if (isCancellation(e, cancel)) {
                    status = TransferReport.Status.CANCELLED;
                    break;
                }
                if (attempts > task.getRetries() || !SyncErrors.isRetryable(e)) {
                    status = TransferReport.Status.FAILED;
                    break;
                }
                long delay = retryDelayMillis << Math.min(attempts - 1, 10);
                recorder.error(String.format("Attempt %d failed: %s. It runs again in %d s.", attempts, message(e),
                        delay / 1000));
                log.warn("Sync task {} failed, attempt {} of {}: {}", task.getName(), attempts, task.getRetries()
                        + 1, message(e));
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status = TransferReport.Status.CANCELLED;
                    break;
                }
                if (cancel.get() || closed) {
                    status = TransferReport.Status.CANCELLED;
                    break;
                }
            }
        }
        report.getOptions().put("Attempts", String.valueOf(attempts));
        report.getOptions().put("Started by", origin);
        recorder.finish(status, status == TransferReport.Status.FAILED ? failure : null, null);
        // The target is counted unless it can not be reached
        boolean reachable = status != TransferReport.Status.FAILED || !SyncErrors.isRetryable(failure);
        if (engine != null && reachable && !(failure instanceof SyncErrors.PermanentException)) {
            try {
                engine.verify(task, report);
            } catch (Exception e) {
                log.warn("Rows of the target of {} are not counted: {}", task.getName(), e.getMessage());
            }
        }
        try {
            reportStore.save(report);
        } catch (Exception e) {
            log.error("The report of sync task {} is not saved: {}", task.getName(), e.getMessage());
        }
        Map<String, SyncTask.Watermark> committed = status.isKept() ? watermarks : null;
        TransferReport.Status finalStatus = status;
        try {
            store.updateTask(task.getId(), saved -> {
                saved.setLastRunAt(report.getStartedAt());
                saved.setLastStatus(finalStatus);
                saved.setLastReportId(report.getId());
                saved.setLastRowCount(report.getWrittenRows());
                saved.setLastRunBy(origin);
                if (committed != null) {
                    committed.forEach((name, watermark) -> {
                        SyncTask.Table table = saved.findTable(name);
                        if (table != null && watermark != null) {
                            table.setWatermark(watermark);
                        }
                    });
                }
            });
        } catch (Exception e) {
            log.error("Sync task {} is not saved after its run: {}", task.getName(), e.getMessage());
        }
        return report;
    }

    private static String message(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && StringUtils.isBlank(cause.getMessage())) {
            cause = cause.getCause();
        }
        return StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private boolean isCancellation(Throwable e, AtomicBoolean cancel) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ExportCancelledException || cause instanceof InterruptedException) {
                return true;
            }
        }
        return cancel.get() || closed;
    }

    private ExportListener listener(ReportRecorder recorder, AtomicBoolean cancel) {
        return new ExportListener() {
            @Override
            public void onTableStart(String catalogName, String schemaName, String tableName, long totalRows) {
                recorder.tableStarted(catalogName, schemaName, tableName, totalRows);
            }

            @Override
            public void onTableProgress(String catalogName, String schemaName, String tableName, long processedRows,
                                        long totalRows) {
                recorder.tableProgress(catalogName, schemaName, tableName, processedRows);
            }

            @Override
            public void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
                recorder.tableEnded(catalogName, schemaName, tableName, processedRows);
            }

            @Override
            public void onError(String message, Throwable e) {
                recorder.error(message);
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
                return cancel.get() || closed || Thread.currentThread().isInterrupted();
            }
        };
    }

    /**
     * Stops the scheduler: running tasks are cancelled and their reports saved, waiting a few seconds for them
     */
    @Override
    public void close() {
        closed = true;
        synchronized (this) {
            if (ticker != null) {
                ticker.shutdownNow();
            }
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
