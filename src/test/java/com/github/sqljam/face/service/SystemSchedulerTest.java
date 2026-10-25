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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.github.sqljam.face.model.SyncTask;

/**
 * @Description: SystemSchedulerTest registers sync tasks with a crontab and a Task Scheduler which are recorded:
 *               the lines of SqlJam are marked, the other lines are kept, a task is replaced and removed, schedules
 *               which cron can not tell wake every 5 minutes and run when due, and arguments are quoted
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class SystemSchedulerTest {

    /**
     * A crontab in memory, and the commands which were run
     */
    private final List<List<String>> commands = new ArrayList<>();
    private String crontab;

    private SystemScheduler scheduler(String os, List<String> launcher) {
        return new SystemScheduler(os, launcher, new File("/home/u/.sqljam/sync.log"), (command, input) -> {
            commands.add(command);
            if (command.equals(List.of("crontab", "-l"))) {
                return crontab == null ? new SystemScheduler.Result(1, "no crontab for u")
                        : new SystemScheduler.Result(0, crontab);
            }
            if (command.equals(List.of("crontab", "-"))) {
                crontab = input;
            }
            return new SystemScheduler.Result(0, "");
        });
    }

    private static SyncTask task(String id, SyncTask.ScheduleType type, int minutes) {
        SyncTask task = new SyncTask();
        task.setId(id);
        task.setName(id);
        task.setScheduleType(type);
        task.setIntervalMinutes(minutes);
        task.setRunner(SyncTask.Runner.SYSTEM);
        return task;
    }

    @Test
    void registersWithTheCrontab() throws IOException {
        crontab = "0 3 * * * /usr/bin/backup\n";
        SystemScheduler scheduler = scheduler("Mac OS X", List.of("/usr/bin/java", "-jar", "/opt/Sql Jam/sqljam.jar"));
        assertTrue(scheduler.isAvailable());
        SyncTask task = task("a1", SyncTask.ScheduleType.INTERVAL, 15);
        scheduler.apply(task);
        assertEquals("0 3 * * * /usr/bin/backup\n*/15 * * * * /usr/bin/java -jar '/opt/Sql Jam/sqljam.jar' sync "
                + "--task a1 >> /home/u/.sqljam/sync.log 2>&1 # sqljam-sync a1\n", crontab);
        assertTrue(scheduler.isRegistered("a1"));
        // Registered again: replaced, not added
        task.setScheduleType(SyncTask.ScheduleType.DAILY);
        task.setDailyTime("02:30");
        scheduler.apply(task);
        assertEquals(2, crontab.split("\n").length);
        assertTrue(crontab.contains("30 2 * * * /usr/bin/java"));
        // Switched off: removed, the other line stays
        task.setEnabled(false);
        scheduler.apply(task);
        assertEquals("0 3 * * * /usr/bin/backup\n", crontab);
        assertFalse(scheduler.isRegistered("a1"));
        // Nothing to remove: the crontab is not written
        int written = commands.size();
        scheduler.unregister("a1");
        assertEquals(written + 1, commands.size());
    }

    @Test
    void createsTheFirstCrontab() throws IOException {
        SystemScheduler scheduler = scheduler("Linux", List.of("/opt/sqljam/bin/SqlJam"));
        scheduler.register(task("b2", SyncTask.ScheduleType.INTERVAL, 90));
        // Every 90 minutes is no cron time: it wakes every 5 minutes and runs when due
        assertEquals("*/5 * * * * /opt/sqljam/bin/SqlJam sync --task b2 --if-due >> /home/u/.sqljam/sync.log 2>&1 "
                + "# sqljam-sync b2\n", crontab);
        scheduler.unregister("b2");
        assertEquals("", crontab);
    }

    @Test
    void tellsCronTimes() {
        SystemScheduler scheduler = scheduler("Linux", List.of("sqljam"));
        assertEquals("* * * * *", scheduler.cron(task("t", SyncTask.ScheduleType.INTERVAL, 1)));
        assertEquals("*/20 * * * *", scheduler.cron(task("t", SyncTask.ScheduleType.INTERVAL, 20)));
        assertEquals("0 * * * *", scheduler.cron(task("t", SyncTask.ScheduleType.INTERVAL, 60)));
        assertEquals("0 */6 * * *", scheduler.cron(task("t", SyncTask.ScheduleType.INTERVAL, 360)));
        assertEquals("0 0 * * *", scheduler.cron(task("t", SyncTask.ScheduleType.INTERVAL, 1440)));
        assertEquals("*/5 * * * *", scheduler.cron(task("t", SyncTask.ScheduleType.INTERVAL, 7)));
        assertFalse(scheduler.isExact(task("t", SyncTask.ScheduleType.INTERVAL, 7)));
        assertFalse(scheduler.isExact(task("t", SyncTask.ScheduleType.INTERVAL, 300)));
        SyncTask daily = task("t", SyncTask.ScheduleType.DAILY, 0);
        daily.setDailyTime("23:05");
        assertEquals("5 23 * * *", scheduler.cron(daily));
    }

    @Test
    void registersWithTheTaskSchedulerOfWindows() throws IOException {
        SystemScheduler scheduler = scheduler("Windows 11", List.of("C:\\Program Files\\SqlJam\\SqlJam.exe"));
        scheduler.apply(task("c3", SyncTask.ScheduleType.INTERVAL, 90));
        assertEquals(List.of("schtasks", "/Create", "/F", "/TN", "SqlJam\\sync-c3", "/TR",
                "\"C:\\Program Files\\SqlJam\\SqlJam.exe\" sync --task c3", "/SC", "MINUTE", "/MO", "90"), commands
                .get(0));
        SyncTask daily = task("c3", SyncTask.ScheduleType.DAILY, 0);
        daily.setDailyTime("02:00");
        scheduler.apply(daily);
        assertEquals(List.of("/SC", "DAILY", "/ST", "02:00"), commands.get(1).subList(7, 11));
        scheduler.apply(task("c3", SyncTask.ScheduleType.INTERVAL, 1440));
        assertEquals(List.of("/SC", "DAILY", "/ST", "00:00"), commands.get(2).subList(7, 11));
        scheduler.apply(task("c3", SyncTask.ScheduleType.INTERVAL, 3000));
        assertTrue(commands.get(3).get(6).endsWith("--if-due"));
        assertEquals(List.of("/SC", "MINUTE", "/MO", "5"), commands.get(3).subList(7, 11));
        SyncTask manual = task("c3", SyncTask.ScheduleType.MANUAL, 0);
        scheduler.apply(manual);
        assertEquals(List.of("schtasks", "/Delete", "/F", "/TN", "SqlJam\\sync-c3"), commands.get(4));
        assertTrue(scheduler.isRegistered("c3"));
    }

    @Test
    void failsWithoutALauncherOrWhenTheSystemRefuses() {
        SystemScheduler unavailable = scheduler("Linux", List.of());
        assertFalse(unavailable.isAvailable());
        IOException e = assertThrows(IOException.class, () -> unavailable.register(task("d4", SyncTask.ScheduleType
                .INTERVAL, 5)));
        assertTrue(e.getMessage().contains("sqljam sync --task d4"));
        SystemScheduler refused = new SystemScheduler("Linux", List.of("sqljam"), new File("sync.log"),
                (command, input) -> new SystemScheduler.Result(command.contains("-l") ? 0 : 1, "permission denied"));
        assertTrue(assertThrows(IOException.class, () -> refused.register(task("d4", SyncTask.ScheduleType.INTERVAL,
                5))).getMessage().contains("permission denied"));
    }

    @Test
    void quotesArguments() {
        assertEquals("/usr/bin/java", SystemScheduler.shellQuote("/usr/bin/java"));
        assertEquals("'a b'", SystemScheduler.shellQuote("a b"));
        assertEquals("'it'\\''s'", SystemScheduler.shellQuote("it's"));
        assertEquals("C:\\SqlJam\\sqljam.exe", SystemScheduler.windowsQuote("C:\\SqlJam\\sqljam.exe"));
        assertEquals("\"a \\\"b\\\"\"", SystemScheduler.windowsQuote("a \"b\""));
        assertEquals("SqlJam\\sync-x", SystemScheduler.taskName("x"));
    }

    @Test
    void runsCommandsOfTheSystem() throws IOException {
        SystemScheduler.Result result = SystemScheduler.execute(List.of("sh", "-c", "cat; echo done"), "input ");
        assertEquals(0, result.getExitCode());
        assertEquals("input done", result.getOutput().trim());
        assertTrue(SystemScheduler.launcher() != null);
    }
}
