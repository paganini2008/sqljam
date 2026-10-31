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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.github.sqljam.face.model.SyncTask;

/**
 * @Description: SyncCommandLineTest verifies the command line of a sync task for the shells of the systems, with
 *               the arguments quoted where they need it
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class SyncCommandLineTest {

    @Test
    void tellsTheCommandOfATask() {
        SyncTask task = new SyncTask();
        String unix = SyncCommandLine.of(task, "Mac OS X");
        assertTrue(unix.endsWith(" sync --task " + task.getId()), unix);
        String windows = SyncCommandLine.of(task, "Windows 11");
        assertTrue(windows.endsWith(" sync --task " + task.getId()), windows);
        // Tests run from the classes, not from a jar: the launcher script of the system
        assertTrue(SyncCommandLine.launcher().isEmpty() || !unix.startsWith("sqljam"));
    }

    @Test
    void quotesArguments() {
        assertEquals("/usr/bin/java", SyncCommandLine.shellQuote("/usr/bin/java"));
        assertEquals("'a b'", SyncCommandLine.shellQuote("a b"));
        assertEquals("'it'\\''s'", SyncCommandLine.shellQuote("it's"));
        assertEquals("C:\\SqlJam\\sqljam.exe", SyncCommandLine.windowsQuote("C:\\SqlJam\\sqljam.exe"));
        assertEquals("\"a \\\"b\\\"\"", SyncCommandLine.windowsQuote("a \"b\""));
    }
}
