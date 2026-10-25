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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.impexp.ExportListener;

/**
 * @Description: SyncSchedulerTest runs sync tasks between two H2 databases: the first run copies all rows, later
 *               runs the rows after the watermark, deleted rows stay, increasing keys copy new rows, errors of the
 *               connection are run again and errors of the data are not, the watermark moves on after a commit
 *               only, and every run leaves a report. A stub engine shows cancels, closes, edits during a run and
 *               the times of the schedules.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class SyncSchedulerTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 8, 0);

    @TempDir
    File dir;

    private final Map<String, ConnectionProfile> profiles = new HashMap<>();
    private final List<Long> sleeps = new ArrayList<>();
    private ConnectionProfile source;
    private ConnectionProfile target;
    private SyncTaskStore store;
    private ReportStore reports;
    private SyncScheduler scheduler;

    private ConnectionProfile h2(String name) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setDbType(DbType.H2);
        profile.setDatabase(new File(dir, name).getAbsolutePath());
        profile.setUsername("sa");
        profile.setPassword("");
        profiles.put(profile.getId(), profile);
        return profile;
    }

    private static void execute(ConnectionProfile profile, String... sqls) throws SQLException {
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> rows(ConnectionProfile profile, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static String at(int minutes) {
        return "TIMESTAMP '" + BASE.plusMinutes(minutes).toString().replace('T', ' ') + ":00'";
    }

    @BeforeEach
    void setUp() throws Exception {
        source = h2("source");
        target = h2("target");
        execute(source, "CREATE TABLE ORDERS (ID INT PRIMARY KEY, NAME VARCHAR(20), UPDATED_AT TIMESTAMP)");
        for (int i = 1; i <= 5; i++) {
            execute(source, "INSERT INTO ORDERS VALUES (" + i + ", 'order " + i + "', " + at(i) + ")");
        }
        store = new SyncTaskStore(new File(dir, "sync-tasks.json"));
        reports = new ReportStore(new File(dir, "reports"), 50);
        scheduler = new SyncScheduler(store, reports);
        scheduler.register(SyncTask.Kind.DATABASE, new SyncRunner(new TransferService(), id -> Optional
                .ofNullable(profiles.get(id)).map(ConnectionProfile::copy)));
        scheduler.setSleeper(sleeps::add);
        scheduler.setRetryDelayMillis(1000);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    private SyncTask task(SyncTask.WatermarkType type, String column) throws Exception {
        SyncTask task = new SyncTask();
        task.setName("orders");
        task.setSourceId(source.getId());
        task.setSourceSchema("PUBLIC");
        task.setTargetId(target.getId());
        task.setTargetSchema("PUBLIC");
        task.getTables().add(new SyncTask.Table("ORDERS", column, type));
        task.setScheduleType(SyncTask.ScheduleType.MANUAL);
        task.setRetries(2);
        store.saveTask(task);
        return task;
    }

    private TransferReport run(SyncTask task) throws Exception {
        CompletableFuture<TransferReport> future = scheduler.runNow(task.getId());
        assertNotNull(future);
        return future.get(60, TimeUnit.SECONDS);
    }

    private SyncTask saved(SyncTask task) {
        return store.findTask(task.getId()).orElseThrow();
    }

    @Test
    void copiesAllRowsFirstThenTheChangedOnes() throws Exception {
        SyncTask task = task(SyncTask.WatermarkType.UPDATED_AT, "UPDATED_AT");
        TransferReport first = run(task);
        assertEquals(TransferReport.Status.COMPLETED, first.getStatus(), first.getFailure());
        assertEquals(TransferReport.Kind.SYNC, first.getKind());
        assertEquals("All rows, the first run", first.getOptions().get("Copy"));
        assertEquals(TransferReport.Transaction.ONE, first.getTransaction());
        assertEquals("1", first.getOptions().get("Attempts"));
        assertEquals(5, rows(target, "SELECT COUNT(*) FROM ORDERS").get(0).length() > 0 ? Integer.parseInt(rows(
                target, "SELECT COUNT(*) FROM ORDERS").get(0)) : -1);
        // The target table was created with its rows, counted afterwards
        assertEquals(5L, first.getTables().get(0).getTargetRows());
        SyncTask afterFirst = saved(task);
        assertEquals(SyncRunner.TIMESTAMP, afterFirst.getTables().get(0).getWatermark().getType());
        assertEquals(BASE.plusMinutes(5), LocalDateTime.parse(afterFirst.getTables().get(0).getWatermark()
                .getValue()));
        assertEquals(TransferReport.Status.COMPLETED, afterFirst.getLastStatus());
        assertEquals(first.getId(), afterFirst.getLastReportId());
        assertTrue(afterFirst.isInitialized());

        // Two rows change, one is new, one is deleted
        execute(source, "UPDATE ORDERS SET NAME = 'changed 2', UPDATED_AT = " + at(10) + " WHERE ID = 2",
                "UPDATE ORDERS SET NAME = 'changed 3', UPDATED_AT = " + at(11) + " WHERE ID = 3",
                "INSERT INTO ORDERS VALUES (6, 'order 6', " + at(12) + ")", "DELETE FROM ORDERS WHERE ID = 1");
        TransferReport second = run(task);
        assertEquals(TransferReport.Status.COMPLETED, second.getStatus(), second.getFailure());
        assertEquals("Rows changed since the last run", second.getOptions().get("Copy"));
        // The rows of the last watermark are read again: row 5, and the changed and new rows
        assertEquals(4, second.getWrittenRows());
        assertEquals(List.of("order 1", "changed 2", "changed 3", "order 4", "order 5", "order 6"), rows(target,
                "SELECT NAME FROM ORDERS ORDER BY ID"));
        assertEquals(BASE.plusMinutes(12), LocalDateTime.parse(saved(task).getTables().get(0).getWatermark()
                .getValue()));

        // Nothing changed: only the rows of the watermark again
        TransferReport third = run(task);
        assertEquals(1, third.getWrittenRows());
        assertEquals(3, reports.list().size());
    }

    @Test
    void copiesNewRowsOfIncreasingKeys() throws Exception {
        SyncTask task = task(SyncTask.WatermarkType.INCREASING_KEY, "ID");
        run(task);
        assertEquals("5", saved(task).getTables().get(0).getWatermark().getValue());
        execute(source, "UPDATE ORDERS SET NAME = 'not copied' WHERE ID = 2", "INSERT INTO ORDERS VALUES (7, "
                + "'order 7', " + at(20) + ")");
        TransferReport report = run(task);
        assertEquals(1, report.getWrittenRows());
        assertEquals(List.of("order 2", "order 7"), rows(target, "SELECT NAME FROM ORDERS WHERE ID IN (2, 7) "
                + "ORDER BY ID"));
        assertEquals("7", saved(task).getTables().get(0).getWatermark().getValue());
    }

    @Test
    void copiesNothingOfEmptyTables() throws Exception {
        execute(source, "DELETE FROM ORDERS");
        SyncTask task = task(SyncTask.WatermarkType.UPDATED_AT, "UPDATED_AT");
        TransferReport report = run(task);
        assertEquals(TransferReport.Status.COMPLETED, report.getStatus(), report.getFailure());
        assertNull(saved(task).getTables().get(0).getWatermark());
        assertFalse(saved(task).isInitialized());
        assertEquals("UPDATED_AT from the first row to no rows", report.getOptions().get("Watermark of ORDERS"));
    }

    @Test
    void runsAgainAfterErrorsOfTheConnection() throws Exception {
        SyncTask task = task(SyncTask.WatermarkType.UPDATED_AT, "UPDATED_AT");
        // A server which is not there
        target.setDatabase(null);
        target.setUrl("jdbc:h2:tcp://127.0.0.1:1/nowhere");
        TransferReport report = run(task);
        assertEquals(TransferReport.Status.FAILED, report.getStatus());
        assertEquals("3", report.getOptions().get("Attempts"));
        // The delay doubles
        assertEquals(List.of(1000L, 2000L), sleeps);
        assertTrue(report.getErrors().get(0).startsWith("Attempt 1 failed: "), report.getErrors().get(0));
        assertNull(saved(task).getTables().get(0).getWatermark());
        assertEquals(TransferReport.Status.FAILED, saved(task).getLastStatus());
    }

    @Test
    void runsNotAgainAfterErrorsOfTheData() throws Exception {
        execute(target, "CREATE TABLE ORDERS (ID INT PRIMARY KEY, NAME VARCHAR(20), UPDATED_AT TIMESTAMP, "
                + "CONSTRAINT NO_FOUR CHECK (ID <> 4))");
        SyncTask task = task(SyncTask.WatermarkType.UPDATED_AT, "UPDATED_AT");
        TransferReport report = run(task);
        assertEquals(TransferReport.Status.FAILED, report.getStatus());
        assertEquals("1", report.getOptions().get("Attempts"));
        assertTrue(sleeps.isEmpty());
        assertTrue(report.isRolledBack());
        assertTrue(report.getFailure().contains("NO_FOUR"), report.getFailure());
        // Rolled back: nothing is kept, the watermark stays, the rows are counted
        assertEquals(List.of("0"), rows(target, "SELECT COUNT(*) FROM ORDERS"));
        assertEquals(0L, report.getTables().get(0).getTargetRows());
        assertNull(saved(task).getTables().get(0).getWatermark());

        // Fixed: the next run copies all rows
        execute(target, "ALTER TABLE ORDERS DROP CONSTRAINT NO_FOUR");
        assertEquals(TransferReport.Status.COMPLETED, run(task).getStatus());
        assertEquals(List.of("5"), rows(target, "SELECT COUNT(*) FROM ORDERS"));
    }

    @Test
    void failsWithoutPasswordsOrDataSources() throws Exception {
        SyncTask task = task(SyncTask.WatermarkType.UPDATED_AT, "UPDATED_AT");
        target.setPassword(null);
        TransferReport report = run(task);
        assertEquals(TransferReport.Status.FAILED, report.getStatus());
        assertEquals("1", report.getOptions().get("Attempts"));
        assertTrue(report.getFailure().contains("password of target is not remembered"), report.getFailure());
        profiles.remove(target.getId());
        assertTrue(run(task).getFailure().contains("target data source of the task was removed"));
        task.setKind(SyncTask.Kind.VECTOR);
        store.saveTask(task);
        assertTrue(run(task).getFailure().contains("can not run here"));
    }

    /**
     * An engine whose runs are told by the test
     */
    private static final class StubEngine implements SyncEngine {

        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile Exception firstFailure;
        volatile boolean waitForCancel;

        @Override
        public void describe(SyncTask task, TransferReport report) {
            report.setKind(TransferReport.Kind.SYNC);
            report.setTitle("Sync " + task.getName());
        }

        @Override
        public Map<String, SyncTask.Watermark> run(SyncTask task, TransferReport report, ExportListener listener)
                throws Exception {
            int run = runs.incrementAndGet();
            started.countDown();
            if (run == 1 && firstFailure != null) {
                listener.onError("Row of the failed attempt", null);
                throw firstFailure;
            }
            if (waitForCancel) {
                while (!listener.isCancelled()) {
                    Thread.sleep(10);
                }
                throw new ExportCancelledException();
            }
            release.await(30, TimeUnit.SECONDS);
            listener.onTableStart(null, null, "T", 2);
            listener.onTableEnd(null, null, "T", 2);
            return Map.of("T", new SyncTask.Watermark(SyncRunner.NUMBER, String.valueOf(run)));
        }
    }

    private SyncTask stubTask(StubEngine engine) throws Exception {
        scheduler.register(SyncTask.Kind.DATABASE, engine);
        SyncTask task = new SyncTask();
        task.setName("stub");
        task.getTables().add(new SyncTask.Table("T", "ID", SyncTask.WatermarkType.INCREASING_KEY));
        task.setScheduleType(SyncTask.ScheduleType.MANUAL);
        store.saveTask(task);
        return task;
    }

    @Test
    void completesAfterAnAttemptWhichFailed() throws Exception {
        StubEngine engine = new StubEngine();
        engine.firstFailure = new SQLTransientConnectionException("connection reset", "08006");
        SyncTask task = stubTask(engine);
        TransferReport report = run(task);
        assertEquals(TransferReport.Status.COMPLETED, report.getStatus());
        assertEquals("2", report.getOptions().get("Attempts"));
        assertEquals(2, report.getTables().get(0).getWritten());
        assertEquals(1, report.getTables().size());
        assertTrue(report.getErrors().stream().anyMatch(error -> error.startsWith("Attempt 1 failed: connection "
                + "reset")));
        assertEquals("2", saved(task).getTables().get(0).getWatermark().getValue());
    }

    @Test
    void runsATaskOnceAtATime() throws Exception {
        StubEngine engine = new StubEngine();
        engine.release = new CountDownLatch(1);
        SyncTask task = stubTask(engine);
        CompletableFuture<TransferReport> first = scheduler.runNow(task.getId());
        assertTrue(engine.started.await(30, TimeUnit.SECONDS));
        assertTrue(scheduler.isRunning(task.getId()));
        assertNull(scheduler.runNow(task.getId()));
        // The user saves the task meanwhile: the run keeps the change and adds its watermark
        SyncTask edited = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(task), SyncTask.class);
        edited.setName("renamed");
        store.saveTask(edited);
        engine.release.countDown();
        first.get(30, TimeUnit.SECONDS);
        assertFalse(scheduler.isRunning(task.getId()));
        assertEquals("renamed", saved(task).getName());
        assertEquals("1", saved(task).getTables().get(0).getWatermark().getValue());
        assertEquals(1, engine.runs.get());
    }

    @Test
    void cancelsARun() throws Exception {
        StubEngine engine = new StubEngine();
        engine.waitForCancel = true;
        SyncTask task = stubTask(engine);
        List<String> changes = new ArrayList<>();
        scheduler.addListener(changed -> changes.add(changed.getName()));
        CompletableFuture<TransferReport> future = scheduler.runNow(task.getId());
        assertTrue(engine.started.await(30, TimeUnit.SECONDS));
        scheduler.cancel(task.getId());
        TransferReport report = future.get(30, TimeUnit.SECONDS);
        assertEquals(TransferReport.Status.CANCELLED, report.getStatus());
        assertNull(saved(task).getTables().get(0).getWatermark());
        assertEquals(TransferReport.Status.CANCELLED, saved(task).getLastStatus());
        // Started and ended
        assertEquals(List.of("stub", "stub"), changes);
    }

    @Test
    void cancelsRunsWhenItCloses() throws Exception {
        StubEngine engine = new StubEngine();
        engine.waitForCancel = true;
        SyncTask task = stubTask(engine);
        CompletableFuture<TransferReport> future = scheduler.runNow(task.getId());
        assertTrue(engine.started.await(30, TimeUnit.SECONDS));
        scheduler.close();
        assertEquals(TransferReport.Status.CANCELLED, future.get(30, TimeUnit.SECONDS).getStatus());
        // The report is saved, a closed scheduler runs nothing
        assertEquals(1, reports.list().size());
        assertNull(scheduler.runNow(task.getId()));
    }

    @Test
    void ticksTasksWhichAreDue() throws Exception {
        StubEngine engine = new StubEngine();
        SyncTask task = stubTask(engine);
        task.setScheduleType(SyncTask.ScheduleType.INTERVAL);
        task.setIntervalMinutes(10);
        task.setCreatedAt(1_000_000L);
        store.saveTask(task);
        scheduler.setClock(() -> 1_000_000L + 9 * 60_000L);
        scheduler.tick();
        assertEquals(0, engine.runs.get());
        scheduler.setClock(() -> 1_000_000L + 10 * 60_000L);
        scheduler.tick();
        assertTrue(engine.started.await(30, TimeUnit.SECONDS));
    }

    @Test
    void tellsTheNextRuns() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        scheduler.setZone(zone);
        long ten = ZonedDateTime.of(2026, 10, 25, 10, 0, 0, 0, zone).toInstant().toEpochMilli();
        long minute = 60_000L;
        SyncTask task = new SyncTask();
        task.setCreatedAt(ten - 30 * minute);
        task.setScheduleType(SyncTask.ScheduleType.INTERVAL);
        task.setIntervalMinutes(60);
        assertEquals(ten + 30 * minute, scheduler.nextRunAt(task, ten));
        task.setCreatedAt(ten - 10 * 60 * minute);
        task.setLastRunAt(ten - 5 * 60 * minute);
        // Overdue: due at once
        assertTrue(scheduler.nextRunAt(task, ten) <= ten);
        task.setIntervalMinutes(0);
        assertEquals(task.getLastRunAt() + minute, scheduler.nextRunAt(task, ten));

        task.setScheduleType(SyncTask.ScheduleType.DAILY);
        task.setDailyTime("02:00");
        task.setCreatedAt(ten - 30 * minute);
        task.setLastRunAt(0);
        // Created today after 02:00: tomorrow at 02:00
        assertEquals(ZonedDateTime.of(2026, 10, 26, 2, 0, 0, 0, zone).toInstant().toEpochMilli(),
                scheduler.nextRunAt(task, ten));
        // Last run the day before yesterday: the run of today at 02:00 was missed, it is due
        task.setCreatedAt(ten - 30 * 24 * 60 * minute);
        task.setLastRunAt(ten - 2 * 24 * 60 * minute);
        assertEquals(ZonedDateTime.of(2026, 10, 25, 2, 0, 0, 0, zone).toInstant().toEpochMilli(),
                scheduler.nextRunAt(task, ten));
        task.setDailyTime("23:30");
        task.setLastRunAt(ZonedDateTime.of(2026, 10, 24, 23, 30, 5, 0, zone).toInstant().toEpochMilli());
        assertEquals(ZonedDateTime.of(2026, 10, 25, 23, 30, 0, 0, zone).toInstant().toEpochMilli(),
                scheduler.nextRunAt(task, ten));
        task.setDailyTime("not a time");
        assertEquals(java.time.LocalTime.of(2, 0), SyncScheduler.dailyTime(task));

        task.setScheduleType(SyncTask.ScheduleType.MANUAL);
        assertEquals(-1, scheduler.nextRunAt(task, ten));
        task.setScheduleType(SyncTask.ScheduleType.INTERVAL);
        task.setEnabled(false);
        assertEquals(-1, scheduler.nextRunAt(task, ten));
    }

    @Test
    void tellsErrorsWhichPass() {
        assertTrue(SyncErrors.isRetryable(new SQLException("lost", "08S01")));
        assertTrue(SyncErrors.isRetryable(new SQLException("deadlock", "40001")));
        assertTrue(SyncErrors.isRetryable(new SQLException("broken", "90067")));
        assertTrue(SyncErrors.isRetryable(new SQLTransientConnectionException("reset")));
        assertTrue(SyncErrors.isRetryable(new RuntimeException(new java.net.SocketTimeoutException("timed out"))));
        assertFalse(SyncErrors.isRetryable(new SQLException("duplicate key", "23505")));
        assertFalse(SyncErrors.isRetryable(new SQLException("too long", "22001")));
        assertFalse(SyncErrors.isRetryable(new SQLException("no table", "42S02")));
        assertFalse(SyncErrors.isRetryable(new SQLException("login failed", "28000")));
        assertFalse(SyncErrors.isRetryable(new SyncErrors.PermanentException("no password")));
        assertFalse(SyncErrors.isRetryable(new IllegalArgumentException("bad")));
    }

    @Test
    void keepsWatermarksOfEveryType() {
        for (Object value : new Object[]{42, 42L, new BigDecimal("12.50"), 1.5d, new java.math.BigInteger("7"),
                java.sql.Timestamp.valueOf("2026-10-25 10:00:00.123456"), LocalDateTime.of(2026, 1, 2, 3, 4),
                OffsetDateTime.parse("2026-10-25T10:00+08:00"), ZonedDateTime.parse("2026-10-25T10:00+08:00[Asia/"
                + "Shanghai]"), java.time.Instant.parse("2026-10-25T02:00:00Z"), java.sql.Date.valueOf(
                "2026-10-25"), java.time.LocalDate.of(2026, 10, 25), "b-17", java.util.UUID.fromString(
                "123e4567-e89b-12d3-a456-426614174000")}) {
            SyncTask.Watermark watermark = SyncRunner.watermarkOf(value);
            Object again = SyncRunner.valueOf(watermark);
            assertEquals(watermark.getValue(), SyncRunner.watermarkOf(again).getValue(), String.valueOf(value));
        }
        assertNull(SyncRunner.watermarkOf(null));
        assertEquals("2026-10-25T10:00:00.123456", SyncRunner.watermarkOf(java.sql.Timestamp.valueOf(
                "2026-10-25 10:00:00.123456")).getValue());
    }

    @Test
    void keepsTasksOfAFileWhichCanNotBeRead() throws Exception {
        File file = new File(dir, "broken/sync-tasks.json");
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "[{not json");
        SyncTaskStore broken = new SyncTaskStore(file);
        assertTrue(broken.getTasks().isEmpty());
        assertEquals(1, file.getParentFile().listFiles((parent, name) -> name.startsWith("sync-tasks.json.broken-"))
                .length);
        SyncTask task = new SyncTask();
        task.setName("new");
        broken.saveTask(task);
        assertEquals("new", new SyncTaskStore(file).getTasks().get(0).getName());
        assertFalse(broken.updateTask("missing", changed -> changed.setName("x")).isPresent());
        broken.removeTask(task.getId());
        assertTrue(new SyncTaskStore(file).getTasks().isEmpty());
    }
}
