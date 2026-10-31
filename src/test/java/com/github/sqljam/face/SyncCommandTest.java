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
package com.github.sqljam.face;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ConnectionStore;
import com.github.sqljam.face.service.SettingsStore;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.ProfileRegistry;
import com.github.sqljam.impexp.DbType;

/**
 * @Description: SyncCommandTest runs sync tasks by the command line: the list, a task by its name or id, a task
 *               when it is due, every task which is due, the exit codes, and a task which runs elsewhere is not run
 *               twice. The runs leave their reports in the history and tell the command line as their starter.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class SyncCommandTest {

    @TempDir
    File dir;

    private AppContext context;
    private SyncTask task;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private ConnectionProfile h2(String name) throws Exception {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setDbType(DbType.H2);
        profile.setDatabase(new File(dir, name).getAbsolutePath());
        profile.setUsername("sa");
        profile.setPassword("");
        context.getProfileRegistry().saveProfile(profile, true);
        return profile;
    }

    @BeforeEach
    void setUp() throws Exception {
        Config config = new Config(new File(dir, Config.FILE_NAME));
        config.set("sqljam.reports.dir", new File(dir, "reports").getAbsolutePath());
        config.set("sqljam.sync.file", new File(dir, "sync-tasks.json").getAbsolutePath());
        config.set("sqljam.vdb.connections.file", new File(dir, "vdb-connections.json").getAbsolutePath());
        Config.setInstance(config);
        context = new AppContext(new SettingsStore(config), new ProfileRegistry(new ConnectionStore(new File(dir,
                "connections.json"))), null);
        ConnectionProfile source = h2("source");
        ConnectionProfile target = h2("target");
        try (Connection connection = DriverManager.getConnection(source.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            statement.execute("INSERT INTO ITEMS VALUES (1, 'a'), (2, 'b')");
        }
        task = new SyncTask();
        task.setName("Items");
        task.setSourceId(source.getId());
        task.setSourceSchema("PUBLIC");
        task.setTargetId(target.getId());
        task.setTargetSchema("PUBLIC");
        task.getTables().add(new SyncTask.Table("ITEMS", "ID", SyncTask.WatermarkType.INCREASING_KEY));
        task.setScheduleType(SyncTask.ScheduleType.INTERVAL);
        task.setIntervalMinutes(30);
        context.getSyncScheduler().getStore().saveTask(task);
    }

    @AfterEach
    void tearDown() {
        context.closeSyncScheduler();
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        return SyncCommand.run(args, context, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(
                err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void runsTasks() throws Exception {
        assertTrue(SyncCommand.isCommand(new String[]{"sync", "--list"}));
        assertFalse(SyncCommand.isCommand(new String[]{}));
        assertEquals(0, run("sync", "--list"));
        assertTrue(out().contains("Items\tevery 30 min"), out());

        // Created just now: not due yet
        assertEquals(0, run("sync", "--task", "items", "--if-due"));
        assertEquals("Items: not due", out().trim());
        assertEquals(0, run("sync", "--due"));
        assertEquals("", out());

        assertEquals(0, run("sync", "--task", "Items"));
        assertTrue(out().startsWith("Items: Completed, 2 rows in "), out());
        SyncTask saved = context.getSyncScheduler().getStore().getTasks().get(0);
        assertEquals("Command line", saved.getLastRunBy());
        assertEquals("2", saved.getTables().get(0).getWatermark().getValue());
        TransferReport report = context.getReportStore().list().get(0);
        assertEquals("Command line", report.getOptions().get("Started by"));

        // Overdue: run by --due
        context.getSyncScheduler().getStore().updateTask(task.getId(), changed -> {
            changed.setCreatedAt(System.currentTimeMillis() - 24 * 60 * 60_000L);
            changed.setLastRunAt(System.currentTimeMillis() - 31 * 60_000L);
        });
        assertEquals(0, run("sync", "--due"));
        assertTrue(out().startsWith("Items: Completed, 0 rows"), out());
        assertEquals(2, context.getReportStore().list().size());
    }

    @Test
    void tellsWrongCommandsAndFailures() throws Exception {
        assertEquals(2, run("sync"));
        assertTrue(err().startsWith("Usage: sqljam sync --list"));
        assertEquals(2, run("sync", "--task"));
        assertEquals(2, run("sync", "--task", "x", "--now"));
        assertEquals(2, run("sync", "--task", "missing"));
        assertTrue(err().contains("No sync task missing"));
        // The target is gone: failed, exit code 1
        context.getProfileRegistry().removeProfile(task.getTargetId());
        assertEquals(1, run("sync", "--task", task.getId()));
        assertTrue(err().contains("Items: Failed"), err());
        assertTrue(err().contains("target data source of the task was removed"), err());
    }

    @Test
    void runsNotATaskWhichRunsElsewhere() throws Exception {
        File lock = new File(new File(dir, "sync-locks"), task.getId() + ".lock");
        Files.createDirectories(lock.getParentFile().toPath());
        // Another process holds the lock: a child JVM, the lock of this JVM would overlap
        Process holder = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp",
                System.getProperty("java.class.path"), LockHolder.class.getName(), lock.getAbsolutePath())
                .redirectErrorStream(true).start();
        try {
            assertEquals("locked", new String(holder.getInputStream().readNBytes(6), StandardCharsets.UTF_8));
            assertTrue(context.getSyncScheduler().isRunningElsewhere(task.getId()));
            assertEquals(0, run("sync", "--task", "Items"));
            assertEquals("Items: runs already elsewhere, not run again", out().trim());
            assertTrue(context.getReportStore().list().isEmpty());
        } finally {
            holder.destroy();
            holder.waitFor();
        }
        assertFalse(context.getSyncScheduler().isRunningElsewhere(task.getId()));
    }

    /**
     * Holds the lock of a task until it is killed
     */
    public static final class LockHolder {

        public static void main(String[] args) throws Exception {
            try (FileChannel channel = FileChannel.open(new File(args[0]).toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
                System.out.print("locked");
                System.out.flush();
                Thread.sleep(60_000);
            }
        }
    }
}
