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

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.model.TransferReport.TableReport;
import com.github.sqljam.face.model.TransferReport.TableStatus;

/**
 * @Description: ReportRecorderTest verifies that the events of a transfer fill its report: rows of the tables,
 *               skipped rows, objects by kind, errors, and where a failed or cancelled transfer stopped
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class ReportRecorderTest {

    @Test
    void recordsCompletedTables() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        recorder.start();
        assertEquals(TransferReport.Status.RUNNING, recorder.getReport().getStatus());
        recorder.tableStarted(null, "app", "users", 3);
        recorder.tableProgress(null, "app", "users", 2);
        assertEquals(2, recorder.getReport().table(null, "app", "users").getWritten());
        recorder.tableEnded(null, "app", "users", 3);
        recorder.finish(TransferReport.Status.COMPLETED, null, "done");

        TransferReport report = recorder.getReport();
        TableReport users = report.getTables().get(0);
        assertEquals(TableStatus.COMPLETED, users.getStatus());
        assertEquals(3, users.getSourceRows());
        assertEquals(3, users.getWritten());
        assertEquals(3, report.getWrittenRows());
        assertEquals("done", report.getSummary());
        assertNull(report.getStoppedAt());
        assertNull(report.getFailure());
        assertTrue(report.getEndedAt() >= report.getStartedAt());
    }

    @Test
    void recordsSkippedRowsOfTheTableBeingWritten() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        recorder.start();
        recorder.tableStarted("db", "app", "Orders", -1);
        // The handler names the table only, in another case
        recorder.rowSkipped(null, null, "orders", "id=7", new SQLException("value too long"));
        recorder.rowSkipped(null, null, "orders", "id=9", new IllegalStateException());
        // Written rows at the end, without the skipped ones
        recorder.tableEnded("db", "app", "Orders", 8);
        recorder.finish(TransferReport.Status.COMPLETED_WITH_ERRORS, null, null);

        TransferReport report = recorder.getReport();
        assertEquals(1, report.getTables().size());
        TableReport orders = report.getTables().get(0);
        assertEquals(-1, orders.getSourceRows());
        assertEquals(2, orders.getSkipped());
        assertEquals(8, orders.getWritten());
        assertEquals(TableStatus.COMPLETED_WITH_ERRORS, orders.getStatus());
        assertEquals(2, report.getSkippedRowCount());
        assertEquals("value too long", report.getSkippedRows().get(0).getError());
        assertEquals("IllegalStateException", report.getSkippedRows().get(1).getError());
    }

    @Test
    void countsSkippedRowsBeyondTheListedOnes() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        for (int i = 0; i < TransferReport.MAX_SKIPPED_ROWS + 5; i++) {
            recorder.rowSkipped(null, null, "t", String.valueOf(i), null);
        }
        for (int i = 0; i < ReportRecorder.MAX_ERRORS + 5; i++) {
            recorder.error("error " + i);
        }
        recorder.error(" ");
        assertEquals(TransferReport.MAX_SKIPPED_ROWS, recorder.getReport().getSkippedRows().size());
        assertEquals(TransferReport.MAX_SKIPPED_ROWS + 5, recorder.getReport().getSkippedRowCount());
        assertNull(recorder.getReport().getSkippedRows().get(0).getError());
        assertEquals(ReportRecorder.MAX_ERRORS, recorder.getReport().getErrors().size());
    }

    @Test
    void recordsWhereAFailedTransferStopped() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        recorder.start();
        recorder.tableStarted(null, null, "a", 2);
        recorder.tableEnded(null, null, "a", 2);
        recorder.tableStarted(null, null, "b", 100);
        recorder.tableProgress(null, null, "b", 40);
        recorder.getReport().table(null, null, "c");
        // The message is in the cause
        recorder.finish(TransferReport.Status.FAILED, new RuntimeException(new SQLException("connection lost")),
                null);

        TransferReport report = recorder.getReport();
        assertEquals("b after 40 rows", report.getStoppedAt());
        assertTrue(report.getFailure().contains("connection lost"));
        assertTrue(report.getFailureTrace().contains("SQLException"));
        assertEquals(TableStatus.COMPLETED, report.table(null, null, "a").getStatus());
        TableReport b = report.table(null, null, "b");
        assertEquals(TableStatus.FAILED, b.getStatus());
        assertEquals(report.getFailure(), b.getError());
        assertEquals(report.getEndedAt(), b.getEndedAt());
        assertEquals(TableStatus.NOT_STARTED, report.table(null, null, "c").getStatus());
    }

    @Test
    void recordsWhereACancelledTransferStopped() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        recorder.start();
        recorder.tableStarted(null, null, "b", 100);
        recorder.tableProgress(null, null, "b", 10);
        recorder.finish(TransferReport.Status.CANCELLED, null, null);

        TransferReport report = recorder.getReport();
        assertEquals("b after 10 rows", report.getStoppedAt());
        assertNull(report.getFailure());
        // A cancelled table is interrupted, it did not fail
        assertEquals(TableStatus.INTERRUPTED, report.getTables().get(0).getStatus());
        assertNull(report.getTables().get(0).getError());
    }

    @Test
    void stopsNowhereWhenTheRowsAreKept() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        recorder.tableStarted(null, null, "script", -1);
        recorder.finish(TransferReport.Status.COMMITTED, null, null);
        assertNull(recorder.getReport().getStoppedAt());
    }

    @Test
    void countsStatementsByTheirObjects() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        recorder.ddlExecuted("CREATE TABLE a (id INT)", true);
        recorder.ddlExecuted("create   table b (id int)", true);
        recorder.ddlExecuted("CREATE INDEX i ON a (id)", true);
        recorder.ddlExecuted("DROP TABLE a", true);
        recorder.ddlExecuted("CREATE TABLE c (id INT)", false);
        assertEquals(2, recorder.getReport().getObjects().get("Tables"));
        assertEquals(1, recorder.getReport().getObjects().get("Indexes"));
        assertFalse(recorder.getReport().getObjects().containsKey("Other statements"));
        assertEquals(1, recorder.getReport().getFailedStatements());
    }

    @Test
    void tellsKindsOfObjects() {
        assertNull(ReportRecorder.kindOf(null));
        assertNull(ReportRecorder.kindOf("  "));
        assertNull(ReportRecorder.kindOf("TRUNCATE TABLE a"));
        assertEquals("Foreign keys", ReportRecorder.kindOf("ALTER TABLE a ADD CONSTRAINT f FOREIGN KEY (b) "
                + "REFERENCES b (id)"));
        assertEquals("Primary keys", ReportRecorder.kindOf("ALTER TABLE a ADD PRIMARY KEY (id)"));
        assertEquals("Tables", ReportRecorder.kindOf("CREATE TABLE a (id INT PRIMARY KEY)"));
        assertEquals("Tables", ReportRecorder.kindOf("CREATE COLUMN TABLE a (id INT)"));
        assertEquals("Partitions", ReportRecorder.kindOf("CREATE TABLE a_2026 PARTITION OF a FOR VALUES IN (1)"));
        assertEquals("Vector indexes", ReportRecorder.kindOf("CREATE INDEX v ON a USING hnsw (embedding "
                + "vector_cosine_ops)"));
        assertEquals("Indexes", ReportRecorder.kindOf("CREATE UNIQUE INDEX u ON a (code)"));
        assertEquals("Indexes", ReportRecorder.kindOf("ALTER TABLE a ADD INDEX i (code)"));
        assertEquals("Sequences", ReportRecorder.kindOf("CREATE SEQUENCE s START WITH 1"));
        assertEquals("Sequences", ReportRecorder.kindOf("ALTER SEQUENCE s RESTART WITH 5"));
        assertEquals("Comments", ReportRecorder.kindOf("COMMENT ON TABLE a IS 'x'"));
        assertEquals("Comments", ReportRecorder.kindOf("EXEC sp_addextendedproperty 'MS_Description', 'x'"));
        assertEquals("Comments", ReportRecorder.kindOf("ALTER TABLE a COMMENT = 'x'"));
        assertEquals("Schemas", ReportRecorder.kindOf("CREATE SCHEMA app"));
        assertEquals("Schemas", ReportRecorder.kindOf("CREATE DATABASE app"));
        assertEquals("Types", ReportRecorder.kindOf("CREATE TYPE mood AS ENUM ('a')"));
        assertEquals("Types", ReportRecorder.kindOf("CREATE DOMAIN d AS INT"));
        assertEquals("Other statements", ReportRecorder.kindOf("SET search_path TO app"));
    }
}
