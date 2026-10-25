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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.model.TransferReport.TableReport;

/**
 * @Description: ReportExporterTest verifies the report as Markdown, HTML and JSON, the verdicts of the tables and
 *               the words of durations and statuses
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class ReportExporterTest {

    /**
     * A failed import with a skipped row, objects and values which need escaping
     */
    private static TransferReport report() {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        TransferReport report = recorder.getReport();
        report.setTitle("Import into <prod>");
        report.setKind(TransferReport.Kind.PACKAGE_IMPORT);
        report.setSource("/tmp/package");
        report.setTarget("pg / app");
        report.setTransaction(TransferReport.Transaction.ONE);
        report.getOptions().put("Skip rows with errors", "true");
        recorder.start();
        recorder.ddlExecuted("CREATE TABLE a (id INT)", true);
        recorder.ddlExecuted("CREATE INDEX i ON a (id)", false);
        recorder.tableStarted(null, "app", "a", 2);
        recorder.rowSkipped(null, null, "a", "id=1", new SQLException("bad | value\nhere"));
        recorder.tableEnded(null, "app", "a", 1);
        recorder.tableStarted(null, "app", "b", 10);
        recorder.tableProgress(null, "app", "b", 4);
        report.setRolledBack(true);
        recorder.finish(TransferReport.Status.FAILED, new SQLException("disk full"), null);
        return report;
    }

    @Test
    void writesMarkdown() throws IOException {
        String text = ReportExporter.export(report(), ReportExporter.Format.MARKDOWN);
        assertTrue(text.startsWith("# Import into <prod>\n"));
        assertTrue(text.contains("| Status | Failed |"));
        assertTrue(text.contains("| Kind | Package import |"));
        assertTrue(text.contains("| Stopped at | app.b after 4 rows |"));
        assertTrue(text.contains("| Error | disk full |"));
        assertTrue(text.contains("| Kept in target | Nothing, the transaction was rolled back |"));
        assertTrue(text.contains("## Options"));
        assertTrue(text.contains("| app.a | Completed with errors | 2 | 1 | 1 |"));
        assertTrue(text.contains("| Tables | 1 |"));
        assertTrue(text.contains("| Failed statements | 1 |"));
        // A cell is one line and its bars are escaped
        assertTrue(text.contains("| a | id=1 | bad \\| value here |"));
        assertTrue(text.contains("## Error\n\n```\njava.sql.SQLException: disk full"));
    }

    @Test
    void writesHtmlWhichOpensAnywhere() throws IOException {
        String html = ReportExporter.export(report(), ReportExporter.Format.HTML);
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("<title>Import into &lt;prod&gt;</title>"));
        assertFalse(html.contains("<prod>"));
        assertTrue(html.contains("<p class=\"status bad\">Failed</p>"));
        assertTrue(html.contains("prefers-color-scheme: dark"));
        assertTrue(html.contains("<h2>Skipped rows</h2>"));
        assertTrue(html.contains("<h2>Error</h2><pre>java.sql.SQLException: disk full"));
        assertEquals("&amp;&lt;&gt;&quot;&#39;", ReportExporter.escape("&<>\"'"));
    }

    @Test
    void writesJsonWhichIsReadBack() throws IOException {
        TransferReport report = report();
        String json = ReportExporter.export(report, ReportExporter.Format.JSON);
        TransferReport read = ReportStore.MAPPER.readValue(json, TransferReport.class);
        assertEquals(report.getId(), read.getId());
        assertEquals(TransferReport.Status.FAILED, read.getStatus());
        assertTrue(read.isRolledBack());
        assertEquals(2, read.getTables().size());
        assertEquals(1, read.getSkippedRows().size());
        assertFalse(json.contains("qualifiedName"));
    }

    @Test
    void writesAReportWithoutTablesOrTitle() throws IOException {
        TransferReport report = new TransferReport();
        report.setKind(TransferReport.Kind.PACKAGE);
        report.setStatus(TransferReport.Status.COMPLETED);
        String text = ReportExporter.export(report, ReportExporter.Format.MARKDOWN);
        assertTrue(text.startsWith("# Transfer report\n"));
        assertFalse(text.contains("## Options"));
        assertFalse(text.contains("## Objects"));
        assertFalse(text.contains("Stopped at"));
        assertFalse(text.contains("Kept in target"));
        assertTrue(ReportExporter.export(report, ReportExporter.Format.HTML).contains("<p class=\"status ok\">"));
    }

    @Test
    void tellsVerdictsOfTables() {
        TableReport table = new TableReport();
        table.setName("t");
        table.setWritten(5);
        assertEquals("", ReportExporter.verdict(table));
        table.setTargetRows(1234L);
        // Rows of tables which were there before are shown, not compared
        assertEquals("1,234", ReportExporter.verdict(table));
        table.setComparable(true);
        assertEquals("1,234 ✗", ReportExporter.verdict(table));
        table.setTargetRows(5L);
        assertEquals("5 ✓", ReportExporter.verdict(table));
        table.setStartedAt(1000);
        table.setEndedAt(3000);
        assertEquals(Arrays.asList("t", "Not started", "", "5", "0", "5 ✓", "2.0 s", "3"),
                Arrays.asList(ReportExporter.tableRow(table)));
    }

    @Test
    void tellsWordsAndTimes() {
        assertEquals("Completed with errors", ReportExporter.words(TransferReport.Status.COMPLETED_WITH_ERRORS));
        assertEquals("0.3 s", ReportExporter.duration(250));
        assertEquals("2 min 5 s", ReportExporter.duration(125_000));
        assertEquals("2 h 3 min", ReportExporter.duration(7_380_000));
        assertEquals("", ReportExporter.time(0));
        assertEquals(19, ReportExporter.time(1_700_000_000_000L).length());
    }
}
