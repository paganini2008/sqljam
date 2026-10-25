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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.SyncTask;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SystemScheduler registers sync tasks with the scheduler of the system, like DBeaver does, so that
 *               they run when SqlJam is closed: a line of the crontab on macOS and Linux, a task of the Task
 *               Scheduler on Windows. Each runs sqljam sync --task with the id of the task. Lines of the crontab
 *               of SqlJam are marked with the id, the other lines are kept as they are. Schedules which cron can
 *               not tell exactly (e.g. every 90 minutes) wake every 5 minutes and run when the task is due.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class SystemScheduler {

    static final String MARK = "# sqljam-sync ";
    static final String WINDOWS_FOLDER = "SqlJam\\";

    /**
     * Runs a command of the system with its input, returns its exit code and output
     */
    @FunctionalInterface
    public interface CommandRunner {
        Result run(List<String> command, String input) throws IOException;
    }

    public static final class Result {

        private final int exitCode;
        private final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }
    }

    private final boolean windows;
    private final List<String> launcher;
    private final File logFile;
    private final CommandRunner runner;

    /**
     * @param launcher command which starts SqlJam, empty when SqlJam does not run from its jar or installation
     */
    public SystemScheduler(String osName, List<String> launcher, File logFile, CommandRunner runner) {
        this.windows = StringUtils.defaultString(osName).toLowerCase(Locale.ENGLISH).startsWith("windows");
        this.launcher = launcher;
        this.logFile = logFile;
        this.runner = runner;
    }

    /**
     * The scheduler of this system with the launcher of this SqlJam
     */
    public static SystemScheduler create(Config config) {
        File home = new File(config.getString("sqljam.home", JsonStore.DEFAULT_DIRECTORY.getAbsolutePath()));
        return new SystemScheduler(System.getProperty("os.name"), launcher(), new File(home, "sync.log"),
                SystemScheduler::execute);
    }

    /**
     * Java with the jar of SqlJam, or the launcher of an installation of SqlJam
     */
    static List<String> launcher() {
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null) {
            return List.of();
        }
        String name = new File(command).getName().toLowerCase(Locale.ENGLISH);
        if (!name.equals("java") && !name.equals("java.exe") && !name.equals("javaw.exe")) {
            // The launcher of the installation passes its arguments to SqlJam
            return List.of(command);
        }
        File jarDirectory = Config.getJarDirectory();
        if (jarDirectory == null) {
            return List.of();
        }
        try {
            File jar = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return List.of(command, "-Dsqljam.vmoptions.applied=true", "-jar", jar.getAbsolutePath());
        } catch (Exception e) {
            return List.of();
        }
    }

    static Result execute(List<String> command, String input) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stdout = process.getInputStream()) {
            stdout.transferTo(output);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("No answer of " + command.get(0));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    public boolean isAvailable() {
        return !launcher.isEmpty();
    }

    /**
     * Arguments of the command line of the task, the check of the time when the system wakes more often
     */
    List<String> arguments(SyncTask task) {
        List<String> arguments = new ArrayList<>(launcher);
        arguments.addAll(List.of("sync", "--task", task.getId()));
        if (!isExact(task)) {
            arguments.add("--if-due");
        }
        return arguments;
    }

    /**
     * The command line which runs the task, e.g. to copy it into a scheduler of your own
     */
    public String getCommand(SyncTask task) {
        return arguments(task).stream().map(argument -> windows ? windowsQuote(argument) : shellQuote(argument))
                .collect(Collectors.joining(" "));
    }

    /**
     * Whether the system runs the task at its times exactly, it wakes every 5 minutes otherwise
     */
    boolean isExact(SyncTask task) {
        if (task.getScheduleType() == SyncTask.ScheduleType.DAILY) {
            return true;
        }
        int minutes = Math.max(1, task.getIntervalMinutes());
        if (windows) {
            return minutes <= 1439 || minutes == 1440;
        }
        return minutes < 60 && 60 % minutes == 0 || minutes % 60 == 0 && 24 % (minutes / 60) == 0;
    }

    /**
     * When cron runs the task
     */
    String cron(SyncTask task) {
        if (task.getScheduleType() == SyncTask.ScheduleType.DAILY) {
            LocalTime time = SyncScheduler.dailyTime(task);
            return time.getMinute() + " " + time.getHour() + " * * *";
        }
        int minutes = Math.max(1, task.getIntervalMinutes());
        if (minutes < 60 && 60 % minutes == 0) {
            return minutes == 1 ? "* * * * *" : "*/" + minutes + " * * * *";
        }
        if (minutes % 60 == 0 && 24 % (minutes / 60) == 0) {
            int hours = minutes / 60;
            return hours == 24 ? "0 0 * * *" : hours == 1 ? "0 * * * *" : "0 */" + hours + " * * *";
        }
        return "*/5 * * * *";
    }

    /**
     * Registers the task, or removes it when it is not run by the system on a schedule
     */
    public void apply(SyncTask task) throws IOException {
        if (task.getRunner() == SyncTask.Runner.SYSTEM && task.isEnabled() && task.getScheduleType()
                != SyncTask.ScheduleType.MANUAL) {
            register(task);
        } else {
            unregister(task.getId());
        }
    }

    public void register(SyncTask task) throws IOException {
        if (!isAvailable()) {
            throw new IOException("SqlJam does not run from its jar or its installation, the system can not start "
                    + "it. Run this command on a schedule yourself: sqljam sync --task " + task.getId());
        }
        if (windows) {
            List<String> command = new ArrayList<>(List.of("schtasks", "/Create", "/F", "/TN", taskName(task
                    .getId()), "/TR", getCommand(task)));
            if (task.getScheduleType() == SyncTask.ScheduleType.DAILY) {
                LocalTime time = SyncScheduler.dailyTime(task);
                command.addAll(List.of("/SC", "DAILY", "/ST", String.format("%02d:%02d", time.getHour(), time
                        .getMinute())));
            } else if (isExact(task) && task.getIntervalMinutes() < 1440) {
                command.addAll(List.of("/SC", "MINUTE", "/MO", String.valueOf(Math.max(1, task
                        .getIntervalMinutes()))));
            } else if (task.getIntervalMinutes() == 1440) {
                command.addAll(List.of("/SC", "DAILY", "/ST", "00:00"));
            } else {
                command.addAll(List.of("/SC", "MINUTE", "/MO", "5"));
            }
            check(runner.run(command, null), command);
            return;
        }
        List<String> lines = crontab();
        lines.removeIf(line -> line.endsWith(MARK + task.getId()));
        lines.add(cron(task) + " " + getCommand(task) + " >> " + shellQuote(logFile.getAbsolutePath()) + " 2>&1 "
                + MARK + task.getId());
        writeCrontab(lines);
    }

    public void unregister(String taskId) throws IOException {
        if (windows) {
            // A task which was never registered is not there
            runner.run(List.of("schtasks", "/Delete", "/F", "/TN", taskName(taskId)), null);
            return;
        }
        List<String> lines = crontab();
        if (lines.removeIf(line -> line.endsWith(MARK + taskId))) {
            writeCrontab(lines);
        }
    }

    public boolean isRegistered(String taskId) throws IOException {
        if (windows) {
            return runner.run(List.of("schtasks", "/Query", "/TN", taskName(taskId)), null).getExitCode() == 0;
        }
        return crontab().stream().anyMatch(line -> line.endsWith(MARK + taskId));
    }

    static String taskName(String taskId) {
        return WINDOWS_FOLDER + "sync-" + taskId;
    }

    private List<String> crontab() throws IOException {
        Result result = runner.run(List.of("crontab", "-l"), null);
        // No crontab yet
        if (result.getExitCode() != 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(result.getOutput().split("\\R"))).stream().filter(StringUtils
                ::isNotEmpty).collect(Collectors.toCollection(ArrayList::new));
    }

    private void writeCrontab(List<String> lines) throws IOException {
        List<String> command = List.of("crontab", "-");
        check(runner.run(command, lines.isEmpty() ? "" : String.join("\n", lines) + "\n"), command);
    }

    private static void check(Result result, List<String> command) throws IOException {
        if (result.getExitCode() != 0) {
            throw new IOException(command.get(0) + " failed: " + StringUtils.defaultIfBlank(result.getOutput()
                    .trim(), "exit code " + result.getExitCode()));
        }
    }

    static String shellQuote(String argument) {
        return argument.matches("[A-Za-z0-9_./:=@%+-]+") ? argument : "'" + argument.replace("'", "'\\''") + "'";
    }

    static String windowsQuote(String argument) {
        return argument.matches("[A-Za-z0-9_./:=\\\\-]+") ? argument : "\"" + argument.replace("\"", "\\\"")
                + "\"";
    }
}
