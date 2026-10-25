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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.TransferReport;

/**
 * @Description: ReportStoreTest verifies the history of the reports: newest first, the number to keep, a report
 *               saved again keeps its file, files which can not be read and directories which can not be written
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class ReportStoreTest {

    @TempDir
    File dir;

    private static TransferReport report(long startedAt) {
        TransferReport report = new TransferReport();
        report.setStartedAt(startedAt);
        report.setEndedAt(startedAt + 1500);
        report.setKind(TransferReport.Kind.DATABASE);
        report.setStatus(TransferReport.Status.COMPLETED);
        report.table(null, "app", "users").setWritten(3);
        return report;
    }

    private static List<String> ids(List<TransferReport> reports) {
        return reports.stream().map(TransferReport::getId).collect(Collectors.toList());
    }

    @Test
    void listsNewestFirst() throws IOException {
        ReportStore store = new ReportStore(new File(dir, "reports"), 10);
        assertTrue(store.list().isEmpty());
        TransferReport first = report(1_700_000_000_000L);
        TransferReport second = report(1_700_000_100_000L);
        store.save(second);
        store.save(first);

        List<TransferReport> reports = store.list();
        assertEquals(List.of(second.getId(), first.getId()), ids(reports));
        assertEquals(3, reports.get(0).getWrittenRows());
        assertEquals("users", reports.get(0).getTables().get(0).getName());
        assertEquals(first.getId(), store.find(first.getId()).orElseThrow().getId());
        assertFalse(store.find("missing").isPresent());
    }

    @Test
    void keepsItsFileWhenSavedAgain() throws IOException {
        ReportStore store = new ReportStore(new File(dir, "reports"), 10);
        TransferReport report = report(1_700_000_000_000L);
        File file = store.save(report);
        report.getTables().get(0).setTargetRows(3L);
        assertEquals(file, store.save(report));
        assertEquals(1, store.getDirectory().listFiles().length);
        assertEquals(3L, store.list().get(0).getTables().get(0).getTargetRows());
    }

    @Test
    void keepsTheNewestReports() throws IOException {
        ReportStore store = new ReportStore(new File(dir, "reports"), 3);
        TransferReport newest = null;
        for (int i = 0; i < 5; i++) {
            newest = report(1_700_000_000_000L + i * 1000L);
            store.save(newest);
        }
        List<TransferReport> reports = store.list();
        assertEquals(3, reports.size());
        assertEquals(newest.getId(), reports.get(0).getId());
        assertEquals(1_700_000_002_000L, reports.get(2).getStartedAt());
    }

    @Test
    void leavesOutFilesWhichCanNotBeRead() throws IOException {
        ReportStore store = new ReportStore(new File(dir, "reports"), 10);
        TransferReport report = report(1_700_000_000_000L);
        store.save(report);
        Files.writeString(new File(store.getDirectory(), "29991231-000000-broken.json").toPath(), "{not json",
                StandardCharsets.UTF_8);
        // Unknown fields of a newer version are read all the same
        Files.writeString(new File(store.getDirectory(), "29991230-000000-newer.json").toPath(),
                "{\"id\":\"newer\",\"status\":\"COMPLETED\",\"futureField\":1}", StandardCharsets.UTF_8);
        assertEquals(List.of("newer", report.getId()), ids(store.list()));
    }

    @Test
    void deletesReports() throws IOException {
        ReportStore store = new ReportStore(new File(dir, "reports"), 10);
        TransferReport report = report(1_700_000_000_000L);
        store.save(report);
        assertTrue(store.delete(report.getId()));
        assertFalse(store.delete(report.getId()));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void failsWhenTheDirectoryCanNotBeWritten() throws IOException {
        File file = new File(dir, "a-file");
        Files.writeString(file.toPath(), "x");
        ReportStore store = new ReportStore(file, 10);
        assertThrows(IOException.class, () -> store.save(report(1L)));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void readsItsDirectoryFromTheConfiguration() {
        Config config = new Config(new File(dir, Config.FILE_NAME));
        config.set("sqljam.home", dir.getAbsolutePath());
        assertEquals(new File(dir, "reports"), new ReportStore(config).getDirectory());
        config.set("sqljam.reports.dir", new File(dir, "elsewhere").getAbsolutePath());
        assertEquals(new File(dir, "elsewhere"), new ReportStore(config).getDirectory());
    }
}
