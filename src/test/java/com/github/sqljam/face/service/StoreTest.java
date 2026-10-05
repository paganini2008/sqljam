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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.AppSettings;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.DataPage;
import com.github.sqljam.impexp.DbType;

/**
 * @Description: StoreTest verifies settings and connection profiles persistence
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class StoreTest {

    @TempDir
    Path tempDir;

    @Test
    void settings() throws Exception {
        Config config = new Config(tempDir.resolve("sqljam.properties").toFile());
        SettingsStore store = new SettingsStore(config);
        AppSettings settings = store.getSettings();
        assertEquals(AppSettings.DEFAULT_THEME, settings.getTheme());
        assertEquals(10L * 1024 * 1024, settings.getMaxDataFileSize());
        assertNull(settings.getLastExportDirectory());
        settings.setTheme("Dracula");
        settings.setLastExportDirectory("/tmp/export");
        settings.setWindowWidth(1000);
        store.save();
        SettingsStore reloaded = new SettingsStore(new Config(tempDir.resolve("sqljam.properties").toFile()));
        assertEquals("Dracula", reloaded.getSettings().getTheme());
        assertEquals("/tmp/export", reloaded.getSettings().getLastExportDirectory());
        assertEquals(1000, reloaded.getSettings().getWindowWidth());
        assertEquals(config.getUserFile(), store.getConfig().getUserFile());
    }

    @Test
    void connections() throws Exception {
        File file = tempDir.resolve("conf/connections.json").toFile();
        ConnectionStore store = new ConnectionStore(file);
        assertTrue(store.getProfiles().isEmpty());
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName("local");
        profile.setDbType(DbType.MYSQL);
        profile.setHostname("localhost");
        profile.setPort(3306);
        profile.setDatabase("test");
        profile.setUsername("u");
        profile.setPassword("p");
        store.saveProfile(profile);
        ConnectionProfile copy = profile.copy();
        copy.setName("renamed");
        store.saveProfile(copy);

        ConnectionStore reloaded = new ConnectionStore(file);
        assertEquals(1, reloaded.getProfiles().size());
        assertEquals("renamed", reloaded.findProfile(profile.getId()).orElseThrow().getName());
        assertTrue(reloaded.findProfile(profile.getId()).orElseThrow().getJdbcUrl().startsWith(
                "jdbc:mysql://localhost:3306/test"));
        assertFalse(profile.toString().contains("p'"));
        reloaded.removeProfile(profile.getId());
        assertTrue(new ConnectionStore(file).getProfiles().isEmpty());

        // Corrupted file is ignored
        Files.writeString(file.toPath(), "{");
        assertTrue(new ConnectionStore(file).getProfiles().isEmpty());
    }

    @Test
    void profileUrls() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setDbType(DbType.POSTGRESQL);
        profile.setHostname("h");
        profile.setPort(5432);
        profile.setDatabase("demo");
        assertTrue(profile.getJdbcUrl("other").startsWith("jdbc:postgresql://h:5432/other"));
        profile.setUrl("jdbc:postgresql://custom:1/demo");
        assertEquals("jdbc:postgresql://custom:1/demo", profile.getJdbcUrl());
        assertEquals("jdbc:postgresql://custom:1/demo", profile.getJdbcUrl("demo"));
        assertTrue(profile.getJdbcUrl("other").startsWith("jdbc:postgresql://h:5432/other"));
    }

    @Test
    void formatting() {
        assertNull(DatabaseSession.formatValue(null));
        assertEquals("(2 bytes) 0x0AFF", DatabaseSession.formatValue(new byte[]{10, -1}));
        assertTrue(DatabaseSession.formatValue(new byte[20]).endsWith("..."));
        assertEquals("[1, 2]", DatabaseSession.formatValue(new Object[]{1, 2}));
        assertEquals("1.50", DatabaseSession.formatValue(new java.math.BigDecimal("1.50")));
        assertEquals(1003, DatabaseSession.formatValue("x".repeat(2000)).length());
        assertEquals("CASCADE", DatabaseSession.getRuleName(0));
        assertEquals("SET NULL", DatabaseSession.getRuleName(2));
        assertEquals("SET DEFAULT", DatabaseSession.getRuleName(4));
        assertEquals("RESTRICT", DatabaseSession.getRuleName(1));
        assertEquals("NO ACTION", DatabaseSession.getRuleName(3));
        assertEquals("", DatabaseSession.getRuleName(null));
        assertEquals(3, new DataPage(null, null, 1, 100, 250).getTotalPages());
        assertEquals(0, new DataPage(null, null, 1, 0, 250).getTotalPages());
    }

    @Test
    void versions() {
        assertNull(TransferService.parseVersion(" "));
        assertEquals(10, TransferService.parseVersion("2008")[0]);
        assertEquals(11, TransferService.parseVersion("2012")[0]);
        assertEquals(12, TransferService.parseVersion("2014")[0]);
        assertEquals(13, TransferService.parseVersion("2016")[0]);
        assertEquals(14, TransferService.parseVersion("2017")[0]);
        assertEquals(15, TransferService.parseVersion("2019")[0]);
        assertEquals(16, TransferService.parseVersion("2022")[0]);
        int[] version = TransferService.parseVersion("11.2");
        assertEquals(11, version[0]);
        assertEquals(2, version[1]);
        assertEquals(0, TransferService.parseVersion("8")[1]);
        assertEquals(12, TransferService.parseVersion("12c")[0]);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> TransferService.parseVersion("abc"));
    }
}
