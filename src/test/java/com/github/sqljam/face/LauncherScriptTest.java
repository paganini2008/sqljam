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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * @Description: LauncherScriptTest verifies the launcher of Windows: line endings of cmd, the check of Java 17 and
 *               paths which are not expanded in parenthesized blocks, where a path like C:\Program Files (x86)
 *               would end the block
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class LauncherScriptTest {

    private static final File SCRIPT = new File("src/bin/sqljam.bat");

    @Test
    void usesWindowsLineEndings() throws Exception {
        String text = Files.readString(SCRIPT.toPath(), StandardCharsets.UTF_8);
        assertFalse(text.replace("\r\n", "").contains("\n"), "Lines of cmd end with CRLF");
        assertTrue(text.contains("sqljam-@project.version@-win.jar"));
    }

    @Test
    void checksJavaVersion() throws Exception {
        String text = Files.readString(SCRIPT.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("-version"));
        assertTrue(text.contains("LSS 17 goto oldJava"));
        assertTrue(text.contains("start \"SqlJam\" \"%JAVAW%\""));
    }

    @Test
    void expandsNoPathInBlocks() throws Exception {
        List<String> lines = Files.readAllLines(SCRIPT.toPath(), StandardCharsets.UTF_8);
        int depth = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("rem") || trimmed.startsWith("for /f")) {
                continue;
            }
            if (depth > 0) {
                assertFalse(line.matches(".*%(JAVA_HOME|JAVA|JAVAW|JAR|DIR)%.*"), line);
            }
            if (trimmed.endsWith("(")) {
                depth++;
            } else if (trimmed.startsWith(")")) {
                depth--;
            }
        }
    }
}
