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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: JsonStore persists objects as json files in the application directory (~/.sqljam)
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class JsonStore {

    public static final File DEFAULT_DIRECTORY = new File(System.getProperty("user.home"), ".sqljam");

    private final File directory;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public JsonStore() {
        this(DEFAULT_DIRECTORY);
    }

    public JsonStore(File directory) {
        this.directory = directory;
    }

    public File getDirectory() {
        return directory;
    }

    public <T> T read(String fileName, Class<T> type, T defaultValue) {
        File file = new File(directory, fileName);
        if (!file.exists()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(file, type);
        } catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("Unable to read file: {}", file, e);
            }
            return defaultValue;
        }
    }

    public void write(String fileName, Object value) throws IOException {
        Files.createDirectories(directory.toPath());
        File file = new File(directory, fileName);
        objectMapper.writeValue(file, value);
        try {
            // Connection profiles contain passwords
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Unable to set file permissions: {}", file);
            }
        }
    }
}
