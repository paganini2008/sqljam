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

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: JvmOptions reads sqljam.vmoptions next to the runnable jar, one JVM option per line. The launch
 *               scripts pass them to java. When the jar is started by double click, the JVM is already running, so
 *               SqlJam is started again with the options; -Dsqljam.vmoptions.applied=true marks a JVM which has
 *               them.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public final class JvmOptions {

    public static final String FILE_NAME = "sqljam.vmoptions";
    public static final String APPLIED_PROPERTY = "sqljam.vmoptions.applied";
    public static final String DOCK_ICON_FILE_NAME = "sqljam.png";

    private JvmOptions() {
    }

    /**
     * Options of the file, blank lines and lines starting with # are skipped
     */
    public static List<String> read(File file) {
        if (file == null || !file.isFile()) {
            return Collections.emptyList();
        }
        try {
            List<String> options = new ArrayList<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String option = line.trim();
                if (!option.isEmpty() && !option.startsWith("#")) {
                    options.add(option);
                }
            }
            return options;
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to read JVM options: {}", file, e);
            }
            return Collections.emptyList();
        }
    }

    /**
     * Command to start the jar again with the options, followed by the arguments of the current JVM so that options
     * given on the command line (e.g. -Dsqljam.config) are kept and win. The Dock of macOS shows the name and the
     * icon of SqlJam.
     */
    public static List<String> getCommand(String javaCommand, File jarFile, List<String> options,
                                          List<String> jvmArguments, String osName, String[] args) {
        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        if (StringUtils.defaultString(osName).toLowerCase(Locale.ENGLISH).contains("mac")) {
            command.add("-Xdock:name=SqlJam");
            File icon = new File(jarFile.getParentFile(), DOCK_ICON_FILE_NAME);
            if (icon.isFile()) {
                command.add("-Xdock:icon=" + icon.getAbsolutePath());
            }
        }
        command.addAll(options);
        command.addAll(jvmArguments);
        command.add("-D" + APPLIED_PROPERTY + "=true");
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());
        command.addAll(Arrays.asList(args));
        return command;
    }

    /**
     * Starts SqlJam again with the options when the jar was started without them (double click). Returns true if a
     * new process is started and this one should exit.
     */
    public static boolean relaunchIfNeeded(String[] args) {
        if (Boolean.getBoolean(APPLIED_PROPERTY)) {
            return false;
        }
        File jarDirectory = Config.getJarDirectory();
        if (jarDirectory == null) {
            return false;
        }
        List<String> options = read(new File(jarDirectory, FILE_NAME));
        String javaCommand = ProcessHandle.current().info().command().orElse(null);
        if (options.isEmpty() || javaCommand == null) {
            return false;
        }
        try {
            File jarFile = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> command = getCommand(javaCommand, jarFile, options,
                    ManagementFactory.getRuntimeMXBean().getInputArguments(), System.getProperty("os.name"), args);
            new ProcessBuilder(command).inheritIO().start();
            return true;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to start with JVM options, they are ignored: {}", e.getMessage());
            }
            return false;
        }
    }
}
