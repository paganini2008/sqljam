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
package com.github.sqljam.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @Description: ConfigTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsAndOverrides() throws Exception {
        File file = tempDir.resolve("sqljam.properties").toFile();
        Config config = new Config(file);
        assertEquals("Primer Dark", config.getString("sqljam.ui.theme", null));
        assertEquals(10485760L, config.getLong("sqljam.export.max-file-size", 0));
        assertEquals(10, config.getInt("sqljam.pool.maximum-size", 0));
        assertEquals(1280.0, config.getDouble("sqljam.ui.window.width", 0));
        assertTrue(config.getBoolean("sqljam.export.lob-separated", false));
        // Placeholders are resolved by system properties and other keys
        assertEquals(new File(System.getProperty("user.home"), ".sqljam/connections.json"),
                config.getFile("sqljam.connections.file", null));
        assertEquals("x", config.getString("missing", "x"));
        assertEquals(7, config.getInt("missing", 7));
        assertEquals(7L, config.getLong("missing", 7L));
        assertEquals(1.5, config.getDouble("missing", 1.5));
        assertFalse(config.getBoolean("missing", false));
        assertNull(config.getFile("sqljam.export.last-directory", null));

        config.set("sqljam.ui.theme", "Nord Light");
        config.set("sqljam.pool.maximum-size", "abc");
        config.set("custom.dir", "${sqljam.home}/x");
        config.save();
        Config reloaded = new Config(file);
        assertEquals("Nord Light", reloaded.getString("sqljam.ui.theme", null));
        assertEquals(10, reloaded.getInt("sqljam.pool.maximum-size", 10));
        assertEquals(10L, reloaded.getLong("sqljam.pool.maximum-size", 10L));
        assertEquals(2.0, reloaded.getDouble("sqljam.pool.maximum-size", 2.0));
        assertTrue(reloaded.getString("custom.dir", null).endsWith(".sqljam/x"));
        assertEquals(file, reloaded.getUserFile());
        reloaded.set("sqljam.ui.theme", null);
        assertEquals("Primer Dark", reloaded.getString("sqljam.ui.theme", null));
        new Config(null).save();
    }

    @Test
    void instance() {
        Config config = new Config(tempDir.resolve("a.properties").toFile());
        Config.setInstance(config);
        assertEquals(config, Config.getInstance());
        Config.setInstance(null);
        System.setProperty(Config.CONFIG_PROPERTY, tempDir.resolve("b.properties").toString());
        try {
            assertEquals(tempDir.resolve("b.properties").toFile(), Config.getInstance().getUserFile());
        } finally {
            System.clearProperty(Config.CONFIG_PROPERTY);
            Config.setInstance(null);
        }
    }

    @Test
    void applicationFile() throws Exception {
        // The external application file overrides classpath defaults, the user file overrides both
        File appFile = tempDir.resolve("app.properties").toFile();
        java.nio.file.Files.writeString(appFile.toPath(),
                "sqljam.ui.theme=Dracula\nsqljam.pool.maximum-size=20\n");
        File userFile = tempDir.resolve("user.properties").toFile();
        java.nio.file.Files.writeString(userFile.toPath(), "sqljam.ui.theme=Nord Dark\n");
        Config config = new Config(appFile, userFile);
        assertEquals("Nord Dark", config.getString("sqljam.ui.theme", null));
        assertEquals(20, config.getInt("sqljam.pool.maximum-size", 0));
        assertEquals(5000, config.getInt("sqljam.export.page-size", 0));
        assertEquals(appFile, config.getApplicationFile());
        // Saving writes the user file only
        config.set("sqljam.ui.theme", "Primer Light");
        config.save();
        assertTrue(java.nio.file.Files.readString(appFile.toPath()).contains("Dracula"));
    }

    @Test
    void applicationFileLookup() throws Exception {
        File dir = tempDir.toFile();
        assertNull(Config.getApplicationFile(null));
        assertNull(Config.getApplicationFile(dir));
        File flat = new File(dir, Config.FILE_NAME);
        java.nio.file.Files.writeString(flat.toPath(), "a=1\n");
        assertEquals(flat, Config.getApplicationFile(dir));
        File conf = new File(new File(dir, Config.CONF_DIR_NAME), Config.FILE_NAME);
        conf.getParentFile().mkdirs();
        java.nio.file.Files.writeString(conf.toPath(), "a=2\n");
        assertEquals(conf, Config.getApplicationFile(dir));
        // Tests run from target/classes, not from a jar
        assertNull(Config.getJarDirectory());
        System.setProperty(Config.APP_CONFIG_PROPERTY, flat.getPath());
        try {
            assertEquals(flat, Config.getDefaultApplicationFile());
        } finally {
            System.clearProperty(Config.APP_CONFIG_PROPERTY);
        }
    }
}
