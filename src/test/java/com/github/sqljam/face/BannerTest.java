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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.config.Config;

/**
 * @Description: BannerTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class BannerTest {

    @TempDir
    Path tempDir;

    private Config newConfig() {
        return new Config(tempDir.resolve("user.properties").toFile());
    }

    private String print(Config config) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Banner.print(config, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void version() {
        // Filtered by Maven from ${project.version}
        String version = Banner.getVersion();
        assertNotEquals(Banner.UNKNOWN_VERSION, version);
        assertFalse(version.contains("${"));
    }

    @Test
    void defaultBanner() {
        String text = print(newConfig());
        assertTrue(text.contains(":: SqlJam ::  (v" + Banner.getVersion() + ")"), text);
        assertTrue(text.contains("Java " + System.getProperty("java.version")), text);
        assertFalse(text.contains("${"), text);
    }

    @Test
    void modes() {
        Config config = newConfig();
        config.set(Banner.MODE_KEY, "off");
        assertEquals("", print(config));
        config.set(Banner.MODE_KEY, "LOG");
        assertEquals("", print(config));
        // Unknown modes fall back to console
        config.set(Banner.MODE_KEY, "nowhere");
        assertTrue(print(config).contains(":: SqlJam ::"));
        assertEquals(Banner.Mode.CONSOLE, Banner.Mode.of(null));
        assertEquals(Banner.Mode.LOG, Banner.Mode.of(" log "));
    }

    @Test
    void customBanner() throws Exception {
        File file = tempDir.resolve("my-banner.txt").toFile();
        Files.writeString(file.toPath(), "My Jam ${sqljam.version} ${sqljam.ui.theme} ${no.such.key}\n\n");
        Config config = newConfig();
        config.set(Banner.LOCATION_KEY, file.getAbsolutePath());
        assertEquals("My Jam " + Banner.getVersion() + " Primer Dark ${no.such.key}", Banner.getBanner(config));
        // A missing file falls back to the default banner
        config.set(Banner.LOCATION_KEY, tempDir.resolve("missing.txt").toString());
        assertTrue(Banner.getBanner(config).contains(":: SqlJam ::"));
    }

    @Test
    void blankBanner() throws Exception {
        File file = tempDir.resolve("blank.txt").toFile();
        Files.writeString(file.toPath(), "  \n");
        Config config = newConfig();
        config.set(Banner.LOCATION_KEY, file.getAbsolutePath());
        assertEquals("", print(config));
    }
}
