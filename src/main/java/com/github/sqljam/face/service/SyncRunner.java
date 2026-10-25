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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.SyncTask.Watermark;
import com.github.sqljam.face.model.SyncTask.WatermarkType;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.TableQuery;
import com.github.sqljam.impexp.WriteMode;
import com.github.sqljam.jdbc.ManualTransaction;

/**
 * @Description: SyncRunner runs a sync task of tables once: it reads the watermarks of the source first, copies the
 *               rows between the last watermarks and them (all rows the first time) by update or insert by key into
 *               the kept tables of the target, in one transaction where the target has transactions, and returns
 *               the new watermarks, which are saved only after the commit. Tables which the target misses are
 *               created.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class SyncRunner implements SyncEngine {

    static final String NUMBER = "NUMBER";
    static final String TIMESTAMP = "TIMESTAMP";
    static final String TIMESTAMP_TZ = "TIMESTAMPTZ";
    static final String DATE = "DATE";
    static final String TEXT = "TEXT";

    private final TransferService transferService;
    private final Function<String, Optional<ConnectionProfile>> profiles;

    public SyncRunner(TransferService transferService, Function<String, Optional<ConnectionProfile>> profiles) {
        this.transferService = transferService;
        this.profiles = profiles;
    }

    ConnectionProfile profile(String id, String role) {
        return requireProfile(profiles, id, role);
    }

    /**
     * A data source which a task runs with alone: it exists and its password is known
     */
    public static ConnectionProfile requireProfile(Function<String, Optional<ConnectionProfile>> profiles, String id,
                                                   String role) {
        ConnectionProfile profile = profiles.apply(id).orElseThrow(() -> new SyncErrors.PermanentException(
                "The " + role + " data source of the task was removed."));
        // A password which is not remembered is null, an empty one is remembered
        if (profile.getPassword() == null && StringUtils.isNotBlank(profile.getUsername())) {
            throw new SyncErrors.PermanentException("The password of " + profile.getName() + " is not remembered, "
                    + "a task which runs by itself needs it. Remember it in the data source.");
        }
        return profile;
    }

    @Override
    public void describe(SyncTask task, TransferReport report) {
        report.setKind(TransferReport.Kind.SYNC);
        report.setTitle("Sync " + task.getName());
        report.setSource(TransferService.describe(profiles.apply(task.getSourceId()).map(ConnectionProfile::getName)
                .orElse(task.getSourceId()), task.getSourceCatalog(), task.getSourceSchema()));
        report.setTarget(TransferService.describe(profiles.apply(task.getTargetId()).map(ConnectionProfile::getName)
                .orElse(task.getTargetId()), task.getTargetCatalog(), task.getTargetSchema()));
        report.getOptions().put("Task", task.getName());
        report.getOptions().put("Copy", task.isInitialized() ? "Rows changed since the last run"
                : "All rows, the first run");
        report.getOptions().put("Write mode", "Update or insert by key");
        report.getOptions().put("Rows deleted in the source", "Kept in the target");
    }

    @Override
    public Map<String, Watermark> run(SyncTask task, TransferReport report, ExportListener listener)
            throws Exception {
        ConnectionProfile source = profile(task.getSourceId(), "source");
        ConnectionProfile target = profile(task.getTargetId(), "target");
        Dialect dialect = source.getDbType().createDialect();
        Map<String, Watermark> watermarks = new LinkedHashMap<>();
        Map<String, TableQuery> queries = new LinkedHashMap<>();
        // The watermarks are read before the rows: rows which change meanwhile are copied by the next run
        try (Connection connection = TransferService.connect(source, task.getSourceCatalog(), task
                .getSourceSchema())) {
            for (SyncTask.Table table : task.getTables()) {
                String column = dialect.quoteIdentifier(table.getWatermarkColumn());
                Watermark last = table.getWatermark();
                Watermark next = readMax(connection, dialect.getSourceTableName(task.getSourceCatalog(), task
                        .getSourceSchema(), table.getName()), column);
                watermarks.put(table.getName(), next != null ? next : last);
                List<String> conditions = new ArrayList<>();
                if (last != null) {
                    // Rows of the same time are read again and updated, keys which only grow are new rows
                    conditions.add(column + (table.getWatermarkType() == WatermarkType.UPDATED_AT ? " >= " : " > ")
                            + literal(dialect, task, table, last));
                }
                if (next != null) {
                    conditions.add(column + " <= " + literal(dialect, task, table, next));
                }
                if (!conditions.isEmpty()) {
                    queries.put(table.getName(), new TableQuery(null, String.join(" AND ", conditions), null,
                            null));
                }
                report.getOptions().put("Watermark of " + table.getName(), table.getWatermarkColumn() + " from "
                        + (last != null ? last : "the first row") + " to " + (next != null ? next : "no rows"));
            }
        }
        TransferRequest request = request(task, source, target, queries);
        ManualTransaction transaction = null;
        if (target.getDbType().isTransactionSupported()) {
            transaction = transferService.beginTransaction(target, task.getTargetCatalog(), task.getTargetSchema());
            report.setTransaction(TransferReport.Transaction.ONE);
        } else {
            report.setTransaction(TransferReport.Transaction.EVERY_ROWS);
        }
        try {
            transferService.transfer(request, transaction, listener);
            if (transaction != null) {
                // Statements after the commit, e.g. identity values, which failed
                transaction.commit().forEach(failure -> listener.onError(failure, null));
            }
        } catch (Exception e) {
            // Closed without a commit: rolled back, nothing of the run is kept
            if (transaction != null && !transaction.isFinished()) {
                report.setRolledBack(true);
            }
            throw e;
        } finally {
            if (transaction != null) {
                transaction.close();
            }
        }
        return watermarks;
    }

    /**
     * Rows of the target tables, kept tables hold rows of earlier runs and of others: they are not compared
     */
    @Override
    public void verify(SyncTask task, TransferReport report) throws Exception {
        transferService.verifyReport(request(task, profile(task.getSourceId(), "source"), profile(task
                .getTargetId(), "target"), Map.of()), report);
    }

    TransferRequest request(SyncTask task, ConnectionProfile source, ConnectionProfile target,
                            Map<String, TableQuery> queries) {
        TransferRequest request = new TransferRequest();
        request.setSource(source);
        request.setSourceCatalog(task.getSourceCatalog());
        request.setSourceSchema(task.getSourceSchema());
        request.setTables(task.getTables().stream().map(SyncTask.Table::getName).collect(Collectors.toList()));
        request.setTarget(TransferRequest.Target.DATABASE);
        request.setTargetProfile(target);
        request.setTargetCatalog(task.getTargetCatalog());
        request.setTargetSchema(task.getTargetSchema());
        request.setExportMode(ExportMode.DDL_DATA);
        // Kept tables, missing ones are created with their keys and indexes
        request.setTableRecreated(false);
        request.setWriteMode(WriteMode.UPSERT);
        request.setForeignKeyIncluded(false);
        request.setSequenceIncluded(false);
        request.setIdReused(true);
        request.setFailFast(true);
        request.setPageSize(task.getPageSize());
        for (SyncTask.Table table : task.getTables()) {
            if (!table.getKeyColumns().isEmpty()) {
                request.getKeyColumnNames().put(table.getName(), table.getKeyColumns());
            }
        }
        request.getTableQueries().putAll(queries);
        return request;
    }

    /**
     * The greatest value of the column, null for a table without rows
     */
    static Watermark readMax(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(" + column + ") FROM " + table)) {
            return rs.next() ? watermarkOf(rs.getObject(1)) : null;
        }
    }

    public static Watermark watermarkOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return new Watermark(NUMBER, ((BigDecimal) value).toPlainString());
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return new Watermark(NUMBER, value.toString());
        }
        if (value instanceof Number) {
            return new Watermark(NUMBER, new BigDecimal(value.toString()).toPlainString());
        }
        if (value instanceof Timestamp) {
            return new Watermark(TIMESTAMP, ((Timestamp) value).toLocalDateTime().toString());
        }
        if (value instanceof LocalDateTime) {
            return new Watermark(TIMESTAMP, value.toString());
        }
        if (value instanceof OffsetDateTime) {
            return new Watermark(TIMESTAMP_TZ, value.toString());
        }
        if (value instanceof ZonedDateTime) {
            return new Watermark(TIMESTAMP_TZ, ((ZonedDateTime) value).toOffsetDateTime().toString());
        }
        if (value instanceof Instant) {
            return new Watermark(TIMESTAMP_TZ, ((Instant) value).atOffset(ZoneOffset.UTC).toString());
        }
        if (value instanceof java.sql.Date) {
            return new Watermark(DATE, ((java.sql.Date) value).toLocalDate().toString());
        }
        if (value instanceof LocalDate) {
            return new Watermark(DATE, value.toString());
        }
        return new Watermark(TEXT, value.toString());
    }

    /**
     * The value of a watermark again, as the source returned it
     */
    public static Object valueOf(Watermark watermark) {
        switch (watermark.getType()) {
            case NUMBER:
                return new BigDecimal(watermark.getValue());
            case TIMESTAMP:
                return LocalDateTime.parse(watermark.getValue());
            case TIMESTAMP_TZ:
                return OffsetDateTime.parse(watermark.getValue());
            case DATE:
                return java.sql.Date.valueOf(LocalDate.parse(watermark.getValue()));
            default:
                return watermark.getValue();
        }
    }

    private static String literal(Dialect dialect, SyncTask task, SyncTask.Table table, Watermark watermark) {
        return dialect.getStringValue(task.getSourceCatalog(), task.getSourceSchema(), table.getName(), table
                .getWatermarkColumn(), valueOf(watermark));
    }
}
