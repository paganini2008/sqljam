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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.TransferReport;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ReportStore keeps the reports of the transfers as the history of the imports: a JSON file a report
 *               in sqljam.reports.dir, newest first, the number of sqljam.reports.keep at most. A file which can not
 *               be read is left out of the history, it does not hide the others.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class ReportStore {

    public static final int DEFAULT_KEEP = 200;
    static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId
            .systemDefault());
    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).disable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final File directory;
    private final int keep;

    public ReportStore(Config config) {
        this(config.getFile("sqljam.reports.dir", new File(config.getString("sqljam.home", System.getProperty(
                "user.home") + "/.sqljam"), "reports")), config.getInt("sqljam.reports.keep", DEFAULT_KEEP));
    }

    public ReportStore(File directory, int keep) {
        this.directory = directory;
        this.keep = Math.max(1, keep);
    }

    public File getDirectory() {
        return directory;
    }

    /**
     * Saves the report, the oldest reports beyond the number to keep are deleted
     */
    public synchronized File save(TransferReport report) throws IOException {
        Files.createDirectories(directory.toPath());
        File file = fileOf(report);
        File temporary = new File(directory, file.getName() + ".tmp");
        MAPPER.writeValue(temporary, report);
        Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        List<File> files = files();
        for (int i = keep; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i).toPath());
        }
        return file;
    }

    private File fileOf(TransferReport report) {
        // A report saved again (e.g. after its target rows were counted) keeps its file
        for (File file : files()) {
            if (file.getName().endsWith(report.getId() + ".json")) {
                return file;
            }
        }
        return new File(directory, FILE_TIME.format(Instant.ofEpochMilli(Math.max(report.getStartedAt(), 1)))
                + "-" + report.getId() + ".json");
    }

    /**
     * Files of the reports, newest first
     */
    private List<File> files() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return new ArrayList<>();
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName).reversed());
        return list;
    }

    /**
     * Reports of the history, newest first, files which can not be read are left out
     */
    public synchronized List<TransferReport> list() {
        List<TransferReport> reports = new ArrayList<>();
        for (File file : files()) {
            try {
                reports.add(MAPPER.readValue(file, TransferReport.class));
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Report {} can not be read: {}", file, e.getMessage());
                }
            }
        }
        return reports;
    }

    public synchronized Optional<TransferReport> find(String id) {
        return list().stream().filter(report -> report.getId().equals(id)).findFirst();
    }

    public synchronized boolean delete(String id) throws IOException {
        for (File file : files()) {
            if (file.getName().endsWith(id + ".json")) {
                Files.delete(file.toPath());
                return true;
            }
        }
        return false;
    }
}
