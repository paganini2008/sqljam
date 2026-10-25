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
package com.github.sqljam.face.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: TransferReport is the report of an import or an export, whatever its end: what was moved from where
 *               to where with which options, each table with its rows (read, written, skipped, failed, found in the
 *               target afterwards), the objects which were created, where it stopped when it failed, was cancelled or
 *               was rolled back, and the rows which were skipped. Reports are kept as the history of the imports.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
public class TransferReport {

    /**
     * Rows which a report lists at most, the others are counted
     */
    public static final int MAX_SKIPPED_ROWS = 1000;

    public enum Kind {
        /**
         * Tables or the rows of a query straight into another database
         */
        DATABASE,
        /**
         * Tables into an export package of SQL, Parquet or CSV files
         */
        PACKAGE,
        /**
         * An export package into a database
         */
        PACKAGE_IMPORT,
        /**
         * Parquet or CSV files of other tools into a database
         */
        FILE_IMPORT,
        /**
         * Collections of a vector database into a database with vectors
         */
        VECTOR_IMPORT,
        /**
         * A run of a sync task: the rows of tables which changed since its last run
         */
        SYNC,
        /**
         * A run of a sync task of collections of a vector database
         */
        VECTOR_SYNC
    }

    public enum Status {
        RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, COMMITTED, ROLLED_BACK, FAILED, CANCELLED;

        /**
         * Whether the rows which were written are kept in the target
         */
        public boolean isKept() {
            return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == COMMITTED;
        }
    }

    /**
     * How the rows are committed: in one transaction, every N rows, or not at all for files
     */
    public enum Transaction {
        ONE, EVERY_ROWS, NONE
    }

    /**
     * The verdict of a table after the transfer
     */
    public enum TableStatus {
        /**
         * Not reached before the end
         */
        NOT_STARTED,
        /**
         * Started and not ended: the transfer stopped in this table
         */
        INTERRUPTED,
        COMPLETED,
        COMPLETED_WITH_ERRORS,
        FAILED
    }

    @Getter
    @Setter
    public static class TableReport {

        private String catalog;
        private String schema;
        private String name;
        /**
         * Rows of the source counted before the table was read, -1 when they were not counted
         */
        private long sourceRows = -1;
        private long written;
        private long skipped;
        private long failed;
        /**
         * Rows of the target table found after the transfer, null when they were not counted
         */
        private Long targetRows;
        /**
         * Whether the target rows are the rows of the transfer only (tables which were created), so that they are
         * compared with the written rows
         */
        private boolean comparable;
        private TableStatus status = TableStatus.NOT_STARTED;
        private long startedAt;
        private long endedAt;
        private String error;

        @JsonIgnore
        public String getQualifiedName() {
            StringBuilder name = new StringBuilder();
            if (catalog != null) {
                name.append(catalog).append('.');
            }
            if (schema != null) {
                name.append(schema).append('.');
            }
            return name.append(this.name).toString();
        }

        @JsonIgnore
        public long getElapsedMillis() {
            return startedAt > 0 && endedAt >= startedAt ? endedAt - startedAt : 0;
        }

        /**
         * Rows written a second, 0 for a table which took no time
         */
        @JsonIgnore
        public double getRowsPerSecond() {
            long millis = getElapsedMillis();
            return millis > 0 ? written * 1000.0 / millis : 0;
        }

        /**
         * Whether the rows found in the target are the rows which were written, null when they can not be compared
         */
        @JsonIgnore
        public Boolean getVerified() {
            return targetRows != null && comparable ? targetRows == written : null;
        }
    }

    @Getter
    @Setter
    public static class SkippedRow {

        private String table;
        private String key;
        private String error;

        public SkippedRow() {
        }

        public SkippedRow(String table, String key, String error) {
            this.table = table;
            this.key = key;
            this.error = error;
        }
    }

    private String id = UUID.randomUUID().toString();
    private Kind kind;
    private String title;
    /**
     * The source, e.g. a data source and its schema, a package directory or a vector database
     */
    private String source;
    /**
     * The target, e.g. a data source and its schema or a package directory and its format
     */
    private String target;
    /**
     * Options of the transfer by name, in the order in which they are shown
     */
    private Map<String, String> options = new LinkedHashMap<>();
    private Transaction transaction = Transaction.NONE;
    private Status status = Status.RUNNING;
    private long startedAt;
    private long endedAt;
    private List<TableReport> tables = new ArrayList<>();
    /**
     * Objects which were created by kind, e.g. Tables 5, Indexes 7, and the statements which failed
     */
    private Map<String, Integer> objects = new LinkedHashMap<>();
    private int failedStatements;
    private List<SkippedRow> skippedRows = new ArrayList<>();
    private long skippedRowCount;
    private List<String> errors = new ArrayList<>();
    /**
     * The error which ended the transfer, with the trace of its causes
     */
    private String failure;
    private String failureTrace;
    /**
     * Where the transfer stopped when it did not complete: the table and the rows written in it
     */
    private String stoppedAt;
    /**
     * The transaction was rolled back, nothing which was written is kept in the target
     */
    private boolean rolledBack;
    /**
     * Summary of the end, e.g. statements and files of a package
     */
    private String summary;

    @JsonIgnore
    public long getElapsedMillis() {
        return endedAt >= startedAt && startedAt > 0 ? endedAt - startedAt : 0;
    }

    @JsonIgnore
    public long getWrittenRows() {
        return tables.stream().mapToLong(TableReport::getWritten).sum();
    }

    @JsonIgnore
    public long getSourceRows() {
        return tables.stream().mapToLong(table -> Math.max(0, table.getSourceRows())).sum();
    }

    /**
     * The report of the table, created when it is first told
     */
    public TableReport table(String catalog, String schema, String name) {
        for (TableReport table : tables) {
            if (name != null && name.equals(table.getName()) && (catalog == null || table.getCatalog() == null
                    || catalog.equals(table.getCatalog())) && (schema == null || table.getSchema() == null
                    || schema.equals(table.getSchema()))) {
                return table;
            }
        }
        TableReport table = new TableReport();
        table.setCatalog(catalog);
        table.setSchema(schema);
        table.setName(name);
        tables.add(table);
        return table;
    }
}
