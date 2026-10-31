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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.SyncTask;

/**
 * @Description: SyncCommandLine tells the command which runs a sync task without the window, e.g. to put it into a
 *               scheduler of the system (cron, launchd, the Task Scheduler of Windows) by hand. SqlJam does not
 *               register it there itself.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class SyncCommandLine {

    private SyncCommandLine() {
    }

    /**
     * The command of the task for the shell of this system: Java with the jar of SqlJam or the launcher of its
     * installation, sqljam.sh or sqljam.bat when SqlJam does not run from either
     */
    public static String of(SyncTask task, String osName) {
        boolean windows = osName != null && osName.toLowerCase(Locale.ENGLISH).startsWith("windows");
        List<String> arguments = new ArrayList<>(launcher());
        if (arguments.isEmpty()) {
            arguments.add(windows ? "sqljam.bat" : "sqljam.sh");
        }
        arguments.addAll(List.of("sync", "--task", task.getId()));
        return arguments.stream().map(argument -> windows ? windowsQuote(argument) : shellQuote(argument))
                .collect(Collectors.joining(" "));
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

    static String shellQuote(String argument) {
        return argument.matches("[A-Za-z0-9_./:=@%+-]+") ? argument : "'" + argument.replace("'", "'\\''") + "'";
    }

    static String windowsQuote(String argument) {
        return argument.matches("[A-Za-z0-9_./:=\\\\-]+") ? argument : "\"" + argument.replace("\"", "\\\"")
                + "\"";
    }
}
