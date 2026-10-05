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

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @Description: JvmOptionsTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class JvmOptionsTest {

    @TempDir
    File dir;

    @Test
    void read() throws Exception {
        File file = new File(dir, JvmOptions.FILE_NAME);
        Files.writeString(file.toPath(), "# comment\n\n  -Xmx2g  \n-Dfile.encoding=UTF-8\n   # indented comment\n");
        assertEquals(List.of("-Xmx2g", "-Dfile.encoding=UTF-8"), JvmOptions.read(file));
        assertEquals(List.of(), JvmOptions.read(new File(dir, "missing.vmoptions")));
        assertEquals(List.of(), JvmOptions.read(null));
        assertEquals(List.of(), JvmOptions.read(dir));
    }

    @Test
    void command() throws Exception {
        File jar = new File(dir, "sqljam-win.jar");
        // Arguments of the current JVM follow the options and win
        List<String> command = JvmOptions.getCommand("C:\\\\Java\\\\bin\\\\javaw.exe", jar, List.of("-Xmx2g"),
                List.of("-Xmx4g", "-Dsqljam.config=C:\\\\x.properties"), "Windows 11", new String[]{"--x"});
        assertEquals(List.of("C:\\\\Java\\\\bin\\\\javaw.exe", "-Xmx2g", "-Xmx4g", "-Dsqljam.config=C:\\\\x.properties",
                "-D" + JvmOptions.APPLIED_PROPERTY + "=true", "-jar", jar.getAbsolutePath(), "--x"), command);

        // The Dock of macOS shows the name and, if it exists, the icon of SqlJam
        command = JvmOptions.getCommand("/usr/bin/java", jar, List.of(), List.of(), "Mac OS X", new String[0]);
        assertEquals("-Xdock:name=SqlJam", command.get(1));
        assertFalse(command.stream().anyMatch(option -> option.startsWith("-Xdock:icon")));
        Files.write(new File(dir, JvmOptions.DOCK_ICON_FILE_NAME).toPath(), new byte[]{1});
        command = JvmOptions.getCommand("/usr/bin/java", jar, List.of(), List.of(), "Mac OS X", new String[0]);
        assertTrue(command.contains("-Xdock:icon=" + new File(dir, JvmOptions.DOCK_ICON_FILE_NAME).getAbsolutePath()));
    }

    @Test
    void notRelaunched() {
        // Tests do not run from a jar
        assertFalse(JvmOptions.relaunchIfNeeded(new String[0]));
        System.setProperty(JvmOptions.APPLIED_PROPERTY, "true");
        try {
            assertFalse(JvmOptions.relaunchIfNeeded(new String[0]));
        } finally {
            System.clearProperty(JvmOptions.APPLIED_PROPERTY);
        }
    }
}
