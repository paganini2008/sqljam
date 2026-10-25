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
package com.github.sqljam.face.vdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ReportStore;
import com.github.sqljam.face.service.SyncScheduler;
import com.github.sqljam.face.service.SyncTaskStore;
import com.github.sqljam.face.view.DataSourceExtension;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.vdb.it.VdbServer;

/**
 * @Description: VdbSyncEngineTest syncs the Qdrant fixture into DuckDB: the first run copies every record, its
 *               watermark is read from the target, the next run reads the records of the watermark and later only.
 *               The sync support of the extension lists the collections and the fields which can be watermarks.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbSyncEngineTest {

    static final VdbServer SERVER = VdbServer.QDRANT;

    @TempDir
    File dir;

    private SyncScheduler scheduler;

    @BeforeAll
    static void createFixture() throws Exception {
        if (SERVER.isAvailable()) {
            SERVER.createFixture();
        }
    }

    @AfterAll
    static void dropFixture() {
        if (SERVER.isAvailable()) {
            SERVER.dropFixture();
        }
    }

    @AfterEach
    void close() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private static long count(File duckdb, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:" + duckdb.getAbsolutePath());
             Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void syncsCollections() throws Exception {
        assumeTrue(SERVER.isAvailable());
        VdbProfileStore store = new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME));
        VdbProfile profile = VdbImportDialogTest.profile();
        store.saveProfile(profile, true);
        File duckdb = new File(dir, "target.duckdb");
        ConnectionProfile target = new ConnectionProfile();
        target.setName("Target DuckDB");
        target.setDbType(DbType.DUCKDB);
        target.setDatabase(duckdb.getAbsolutePath());
        Map<String, ConnectionProfile> profiles = Map.of(target.getId(), target);

        DataSourceExtension.SyncSupport support = new VdbDataSourceExtension(store).getSyncSupport();
        assertEquals(SyncTask.Kind.VECTOR, support.getKind());
        assertTrue(support.getDatabases(profile.getId()).isEmpty());
        assertTrue(support.getCollections(profile.getId(), null).contains(SERVER.getCollection()));
        List<String> fields = support.getWatermarkFields(profile.getId(), null, SERVER.getCollection());
        assertTrue(fields.contains("n") && fields.contains("score"), fields.toString());
        assertFalse(fields.contains("title"));

        scheduler = new SyncScheduler(new SyncTaskStore(new File(dir, "sync-tasks.json")), new ReportStore(new File(
                dir, "reports"), 20));
        scheduler.register(SyncTask.Kind.VECTOR, support.createEngine(id -> Optional.ofNullable(profiles.get(id))));
        SyncTask task = new SyncTask();
        task.setName("vectors");
        task.setKind(SyncTask.Kind.VECTOR);
        task.setSourceId(profile.getId());
        task.setTargetId(target.getId());
        SyncTask.Table table = new SyncTask.Table(SERVER.getCollection(), "n", SyncTask.WatermarkType.UPDATED_AT);
        table.setTargetName("sjvt_sync");
        task.getTables().add(table);
        task.setScheduleType(SyncTask.ScheduleType.MANUAL);
        scheduler.getStore().saveTask(task);

        TransferReport first = scheduler.runNow(task.getId()).get(120, TimeUnit.SECONDS);
        assertEquals(TransferReport.Status.COMPLETED, first.getStatus(), first.getFailure());
        assertEquals(TransferReport.Kind.VECTOR_SYNC, first.getKind());
        assertEquals("All records, the first run", first.getOptions().get("Copy"));
        assertEquals(1, first.getObjects().get("Tables"));
        assertEquals(VdbServer.RECORDS, first.getWrittenRows());
        assertEquals(Long.valueOf(VdbServer.RECORDS), first.getTables().get(0).getTargetRows());
        assertEquals(VdbServer.RECORDS, count(duckdb, "SELECT COUNT(*) FROM sjvt_sync"));
        SyncTask.Watermark watermark = scheduler.getStore().findTask(task.getId()).orElseThrow().getTables().get(0)
                .getWatermark();
        assertEquals(String.valueOf(VdbServer.RECORDS), watermark.getValue());

        // The records of the watermark are read again and updated, the table is kept
        TransferReport second = scheduler.runNow(task.getId()).get(120, TimeUnit.SECONDS);
        assertEquals(TransferReport.Status.COMPLETED, second.getStatus(), second.getFailure());
        assertEquals("Records changed since the last run", second.getOptions().get("Copy"));
        assertEquals(1, second.getWrittenRows());
        assertTrue(second.getObjects().isEmpty());
        assertEquals(VdbServer.RECORDS, count(duckdb, "SELECT COUNT(*) FROM sjvt_sync"));
        assertEquals("n from " + VdbServer.RECORDS + " to " + VdbServer.RECORDS, second.getOptions().get(
                "Watermark of " + SERVER.getCollection()));
    }

    @Test
    void failsWithoutTheSourceOrAField() throws Exception {
        VdbProfileStore store = new VdbProfileStore(new File(dir, VdbProfileStore.FILE_NAME));
        VdbSyncEngine engine = new VdbSyncEngine(store, id -> Optional.empty());
        SyncTask task = new SyncTask();
        task.setName("gone");
        task.setKind(SyncTask.Kind.VECTOR);
        task.setSourceId("missing");
        TransferReport report = new TransferReport();
        engine.describe(task, report);
        assertEquals(TransferReport.Kind.VECTOR_SYNC, report.getKind());
        assertEquals("missing", report.getSource());
        try {
            engine.run(task, report, com.github.sqljam.impexp.ExportListener.NONE);
            throw new AssertionError("A removed source runs");
        } catch (com.github.sqljam.face.service.SyncErrors.PermanentException e) {
            assertTrue(e.getMessage().contains("vector database of the task was removed"));
        }
        assertEquals(8L, VdbSyncEngine.queryValue(new SyncTask.Watermark("NUMBER", "8.0")));
        assertEquals(2.5, VdbSyncEngine.queryValue(new SyncTask.Watermark("NUMBER", "2.5")));
        assertEquals("2026-10-25T10:00+08:00", VdbSyncEngine.queryValue(new SyncTask.Watermark("TIMESTAMPTZ",
                "2026-10-25T10:00+08:00")));
        File h2 = new File(dir, "h2");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:" + h2.getAbsolutePath(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"docs\" (\"id\" INT, \"n\" INT)");
            statement.execute("INSERT INTO \"docs\" VALUES (1, 4), (2, 9)");
            assertEquals("9", VdbSyncEngine.readMax(connection, "PUBLIC", "DOCS", "N").getValue());
            try {
                VdbSyncEngine.readMax(connection, "PUBLIC", "docs", "missing");
                throw new AssertionError("A missing column is read");
            } catch (com.github.sqljam.face.service.SyncErrors.PermanentException e) {
                assertTrue(e.getMessage().contains("missing is not a column"));
            }
        }
    }
}
