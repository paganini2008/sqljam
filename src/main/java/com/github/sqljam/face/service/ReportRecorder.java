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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.model.TransferReport.TableReport;
import com.github.sqljam.face.model.TransferReport.TableStatus;

/**
 * @Description: ReportRecorder fills the report of a transfer from its events: the tables with their rows, the
 *               rows which were skipped, the statements of the structure by the kinds of objects, the errors, and at
 *               the end its status and where it stopped. The events come from any thread, the report is read after
 *               the end.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ReportRecorder {

    /**
     * Errors which a report keeps at most, the first ones tell most
     */
    static final int MAX_ERRORS = 200;
    static final Pattern VECTOR_INDEX = Pattern.compile("(?i)\\b(hnsw|ivfflat|ivf|diskann|vector_similarity|"
            + "vector\\s+index|vector_distance|annoy|usearch)\\b");

    private final TransferReport report;
    private TableReport current;

    public ReportRecorder(TransferReport report) {
        this.report = report;
    }

    public TransferReport getReport() {
        return report;
    }

    public synchronized void start() {
        report.setStartedAt(System.currentTimeMillis());
        report.setStatus(TransferReport.Status.RUNNING);
    }

    /**
     * A new attempt of a run whose last attempt was rolled back: its tables, objects and skipped rows are gone, its
     * errors stay
     */
    public synchronized void restart() {
        report.getTables().clear();
        report.getObjects().clear();
        report.setFailedStatements(0);
        report.getSkippedRows().clear();
        report.setSkippedRowCount(0);
        report.setRolledBack(false);
        current = null;
    }

    public synchronized void tableStarted(String catalog, String schema, String table, long totalRows) {
        TableReport tableReport = report.table(catalog, schema, table);
        tableReport.setStatus(TableStatus.INTERRUPTED);
        tableReport.setStartedAt(System.currentTimeMillis());
        if (totalRows >= 0) {
            tableReport.setSourceRows(totalRows);
        }
        current = tableReport;
    }

    /**
     * Rows of a table read so far, the rows which were skipped are not written
     */
    public synchronized void tableProgress(String catalog, String schema, String table, long processedRows) {
        TableReport tableReport = report.table(catalog, schema, table);
        tableReport.setWritten(Math.max(0, processedRows - tableReport.getSkipped()));
    }

    /**
     * The end of a table with its written rows: the importers tell them without the rows which were skipped
     */
    public synchronized void tableEnded(String catalog, String schema, String table, long writtenRows) {
        TableReport tableReport = report.table(catalog, schema, table);
        tableReport.setWritten(writtenRows);
        tableReport.setEndedAt(System.currentTimeMillis());
        tableReport.setStatus(tableReport.getSkipped() > 0 ? TableStatus.COMPLETED_WITH_ERRORS
                : TableStatus.COMPLETED);
        if (current == tableReport) {
            current = null;
        }
    }

    /**
     * A row which was skipped, of the table which is written when the event names no schema
     */
    public synchronized void rowSkipped(String catalog, String schema, String table, String key, Throwable e) {
        TableReport tableReport = findTable(catalog, schema, table);
        tableReport.setSkipped(tableReport.getSkipped() + 1);
        report.setSkippedRowCount(report.getSkippedRowCount() + 1);
        if (report.getSkippedRows().size() < TransferReport.MAX_SKIPPED_ROWS) {
            report.getSkippedRows().add(new TransferReport.SkippedRow(table, key, e != null ? StringUtils
                    .defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()) : null));
        }
    }

    private TableReport findTable(String catalog, String schema, String table) {
        if (catalog == null && schema == null) {
            for (TableReport candidate : report.getTables()) {
                if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(table)) {
                    return candidate;
                }
            }
        }
        return report.table(catalog, schema, table);
    }

    public synchronized void error(String message) {
        if (report.getErrors().size() < MAX_ERRORS && StringUtils.isNotBlank(message)) {
            report.getErrors().add(message);
        }
    }

    /**
     * A statement of the structure counted by the kind of its object
     */
    public synchronized void ddlExecuted(String statement, boolean successful) {
        if (!successful) {
            report.setFailedStatements(report.getFailedStatements() + 1);
            return;
        }
        String kind = kindOf(statement);
        if (kind != null) {
            report.getObjects().merge(kind, 1, Integer::sum);
        }
    }

    /**
     * Kind of the object which a statement creates, null for statements which create none (drops)
     */
    static String kindOf(String statement) {
        String text = StringUtils.normalizeSpace(StringUtils.defaultString(statement)).toUpperCase(Locale.ENGLISH);
        if (text.startsWith("DROP") || text.startsWith("TRUNCATE") || text.isEmpty()) {
            return null;
        }
        if (text.contains("FOREIGN KEY")) {
            return "Foreign keys";
        }
        if (text.contains("PRIMARY KEY") && !text.startsWith("CREATE TABLE")) {
            return "Primary keys";
        }
        if (text.matches("^CREATE\\s+TABLE\\s+.*\\bPARTITION\\s+OF\\b.*")) {
            return "Partitions";
        }
        if (text.startsWith("CREATE TABLE") || text.startsWith("CREATE COLUMN TABLE")) {
            return "Tables";
        }
        if (text.contains("INDEX") && VECTOR_INDEX.matcher(text).find()) {
            return "Vector indexes";
        }
        if (text.contains(" INDEX ") || text.startsWith("CREATE INDEX") || text.startsWith("CREATE UNIQUE")) {
            return "Indexes";
        }
        if (text.startsWith("CREATE SEQUENCE") || text.startsWith("ALTER SEQUENCE")) {
            return "Sequences";
        }
        if (text.startsWith("COMMENT ON") || text.contains("SP_ADDEXTENDEDPROPERTY") || text.contains("COMMENT =")) {
            return "Comments";
        }
        if (text.startsWith("CREATE SCHEMA") || text.startsWith("CREATE DATABASE")) {
            return "Schemas";
        }
        if (text.startsWith("CREATE TYPE") || text.startsWith("CREATE DOMAIN")) {
            return "Types";
        }
        return "Other statements";
    }

    /**
     * The end of the transfer: its status, the error which ended it and where it stopped
     */
    public synchronized void finish(TransferReport.Status status, Throwable failure, String summary) {
        report.setEndedAt(System.currentTimeMillis());
        report.setStatus(status);
        report.setSummary(summary);
        if (failure != null) {
            Throwable cause = failure;
            while (cause.getCause() != null && StringUtils.isBlank(cause.getMessage())) {
                cause = cause.getCause();
            }
            report.setFailure(StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getName()));
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            report.setFailureTrace(StringUtils.abbreviate(trace.toString(), 20000));
        }
        for (TableReport table : report.getTables()) {
            if (table.getStatus() == TableStatus.INTERRUPTED) {
                table.setEndedAt(report.getEndedAt());
                if (failure != null && table.getError() == null) {
                    table.setError(report.getFailure());
                }
                if (status == TransferReport.Status.FAILED) {
                    table.setStatus(TableStatus.FAILED);
                }
            }
        }
        if (current != null && !status.isKept()) {
            report.setStoppedAt(String.format("%s after %,d rows", current.getQualifiedName(), current
                    .getWritten()));
        } else if (current != null) {
            report.setStoppedAt(null);
        }
    }
}
