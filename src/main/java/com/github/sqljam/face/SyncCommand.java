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

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ConnectionStore;
import com.github.sqljam.face.service.ReportExporter;
import com.github.sqljam.face.service.SettingsStore;
import com.github.sqljam.face.service.SyncScheduler;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.ProfileRegistry;

/**
 * @Description: SyncCommand runs sync tasks from the command line, without the window, e.g. from a cron of the
 *               user when SqlJam is closed (SqlJam does not register them with the system itself):
 *
 *               <pre>
 *               sqljam sync --list                    the tasks with their schedules and last runs
 *               sqljam sync --task NAME_OR_ID         runs a task now
 *               sqljam sync --task NAME_OR_ID --if-due   runs it when it is due
 *               sqljam sync --due                     runs every task which is due
 *               </pre>
 *
 *               Each run leaves its report in the history of SqlJam. The exit code is 0 when the runs completed,
 *               1 when one failed or was cancelled, 2 for a wrong command or a task which is not there. A task which
 *               runs in SqlJam or another command meanwhile is not run twice.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class SyncCommand {

    public static final String NAME = "sync";
    public static final String ORIGIN = "Command line";
    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;
    /**
     * A system which wakes at the time of a task may wake a little before its next run
     */
    static final long SLACK_MILLIS = 60_000;

    private SyncCommand() {
    }

    public static boolean isCommand(String[] args) {
        return args.length > 0 && NAME.equals(args[0]);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Config config = Config.getInstance();
        AppContext context = new AppContext(new SettingsStore(config), new ProfileRegistry(new ConnectionStore()),
                null);
        try {
            return run(args, context, out, err);
        } finally {
            context.closeSyncScheduler();
            context.getSessionManager().closeAll();
        }
    }

    static int run(String[] args, AppContext context, PrintStream out, PrintStream err) {
        List<String> options = List.of(args).subList(Math.min(1, args.length), args.length);
        SyncScheduler scheduler = context.getSyncScheduler();
        scheduler.setOrigin(ORIGIN);
        if (options.equals(List.of("--list"))) {
            list(scheduler, out);
            return OK;
        }
        if (options.equals(List.of("--due"))) {
            int code = OK;
            for (SyncTask task : scheduler.getStore().getTasks()) {
                if (isDue(scheduler, task)) {
                    code = Math.max(code, runTask(scheduler, task, out, err));
                }
            }
            return code;
        }
        if (options.size() >= 2 && options.get(0).equals("--task") && (options.size() == 2 || options.size() == 3
                && options.get(2).equals("--if-due"))) {
            Optional<SyncTask> task = find(scheduler, options.get(1));
            if (task.isEmpty()) {
                err.println("No sync task " + options.get(1) + ", sqljam sync --list shows them.");
                return USAGE;
            }
            if (options.size() == 3 && !isDue(scheduler, task.get())) {
                out.println(task.get().getName() + ": not due");
                return OK;
            }
            return runTask(scheduler, task.get(), out, err);
        }
        usage(err);
        return USAGE;
    }

    static void usage(PrintStream err) {
        err.println("Usage: sqljam sync --list");
        err.println("       sqljam sync --task NAME_OR_ID [--if-due]");
        err.println("       sqljam sync --due");
    }

    /**
     * A task by its id, or by its name without case
     */
    static Optional<SyncTask> find(SyncScheduler scheduler, String nameOrId) {
        List<SyncTask> tasks = scheduler.getStore().getTasks();
        Optional<SyncTask> byId = tasks.stream().filter(task -> task.getId().equals(nameOrId)).findFirst();
        return byId.isPresent() ? byId : tasks.stream().filter(task -> StringUtils.equalsIgnoreCase(task.getName(),
                nameOrId)).findFirst();
    }

    static boolean isDue(SyncScheduler scheduler, SyncTask task) {
        long now = System.currentTimeMillis();
        long next = scheduler.nextRunAt(task);
        return next >= 0 && next <= now + SLACK_MILLIS;
    }

    private static int runTask(SyncScheduler scheduler, SyncTask task, PrintStream out, PrintStream err) {
        CompletableFuture<TransferReport> future = scheduler.runNow(task.getId());
        TransferReport report;
        try {
            report = future != null ? future.get() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println(task.getName() + ": interrupted");
            return FAILED;
        } catch (Exception e) {
            err.println(task.getName() + ": " + e.getMessage());
            return FAILED;
        }
        if (report == null) {
            out.println(task.getName() + ": runs already elsewhere, not run again");
            return OK;
        }
        String line = String.format("%s: %s, %,d rows in %s, report %s", task.getName(), ReportExporter.words(report
                .getStatus()), report.getWrittenRows(), ReportExporter.duration(report.getElapsedMillis()), report
                .getId());
        if (report.getStatus().isKept()) {
            out.println(line);
            return OK;
        }
        err.println(line + (report.getFailure() != null ? ": " + report.getFailure() : ""));
        return FAILED;
    }

    private static void list(SyncScheduler scheduler, PrintStream out) {
        List<SyncTask> tasks = scheduler.getStore().getTasks();
        if (tasks.isEmpty()) {
            out.println("No sync tasks, create them in SqlJam: Transfer > Sync Tasks");
            return;
        }
        out.println(String.join("\t", "ID", "Name", "Schedule", "Next run", "Last run", "Last run by",
                "Last status"));
        for (SyncTask task : tasks) {
            long next = scheduler.nextRunAt(task);
            out.println(List.of(task.getId(), task.getName(), schedule(task), next >= 0
                    ? ReportExporter.time(next) : "", ReportExporter.time(task.getLastRunAt()), StringUtils.defaultString(task.getLastRunBy()), task.getLastStatus()
                    != null ? ReportExporter.words(task.getLastStatus()) : "").stream().collect(Collectors.joining(
                    "\t")));
        }
    }

    static String schedule(SyncTask task) {
        if (!task.isEnabled()) {
            return "disabled";
        }
        switch (task.getScheduleType()) {
            case INTERVAL:
                return "every " + task.getIntervalMinutes() + " min";
            case DAILY:
                return "daily " + task.getDailyTime();
            default:
                return "manual";
        }
    }
}
