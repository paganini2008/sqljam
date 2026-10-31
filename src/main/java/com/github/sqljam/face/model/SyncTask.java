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
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: SyncTask copies the rows of tables which changed since its last run into a target database, on a
 *               schedule. A watermark column of each table (a column of the time of the last update by default, or
 *               an increasing key) tells the changed rows, they are updated or inserted by key. The first run copies
 *               all rows, its watermark is read before. The watermark moves on only when the rows are committed, so
 *               that a failed run is run again. Rows which were deleted in the source stay in the target.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
public class SyncTask {

    public enum Kind {
        /**
         * Tables of a data source into tables of another
         */
        DATABASE,
        /**
         * Collections of a vector database into tables of a database with vectors
         */
        VECTOR
    }

    public enum WatermarkType {
        /**
         * A column of the time of the last change: rows of the same time are read again, they are updated
         */
        UPDATED_AT,
        /**
         * A key which only grows: new rows are read, changed rows are not
         */
        INCREASING_KEY
    }

    public enum ScheduleType {
        /**
         * Run Now only
         */
        MANUAL,
        /**
         * Every N minutes
         */
        INTERVAL,
        /**
         * Every day at a time
         */
        DAILY
    }

    /**
     * A table or a collection of the task with its watermark
     */
    @Getter
    @Setter
    public static class Table {

        private String name;
        /**
         * Name of the target table, the same name if it is blank
         */
        private String targetName;
        private String watermarkColumn;
        private WatermarkType watermarkType = WatermarkType.UPDATED_AT;
        /**
         * Key columns of the updates, the primary key if it is empty
         */
        private List<String> keyColumns = new ArrayList<>();
        /**
         * Watermark of the last committed run, null before the first run
         */
        private Watermark watermark;

        public Table() {
        }

        public Table(String name, String watermarkColumn, WatermarkType watermarkType) {
            this.name = name;
            this.watermarkColumn = watermarkColumn;
            this.watermarkType = watermarkType;
        }
    }

    /**
     * The greatest value of a watermark column which was copied, with its type to make a literal of it again
     */
    @Getter
    @Setter
    public static class Watermark {

        /**
         * Kind of the value: NUMBER, TIMESTAMP, DATE or TEXT
         */
        private String type;
        private String value;

        public Watermark() {
        }

        public Watermark(String type, String value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private String id = UUID.randomUUID().toString();
    private String name;
    private Kind kind = Kind.DATABASE;
    private boolean enabled = true;
    /**
     * Data source or vector database of the source
     */
    private String sourceId;
    private String sourceCatalog;
    private String sourceSchema;
    private String targetId;
    private String targetCatalog;
    private String targetSchema;
    private List<Table> tables = new ArrayList<>();
    private ScheduleType scheduleType = ScheduleType.INTERVAL;
    private int intervalMinutes = 60;
    /**
     * Time of a daily run, HH:mm
     */
    private String dailyTime = "02:00";
    /**
     * Runs again after errors of the connection, errors of the data are not run again
     */
    private int retries = 3;
    private int pageSize = 5000;
    private long createdAt = System.currentTimeMillis();
    private long lastRunAt;
    private TransferReport.Status lastStatus;
    private String lastReportId;
    private long lastRowCount;
    /**
     * What started the last run, e.g. SqlJam or Command line
     */
    private String lastRunBy;

    @JsonIgnore
    public Table findTable(String name) {
        return tables.stream().filter(table -> table.getName().equals(name)).findFirst().orElse(null);
    }

    /**
     * Whether the task has run once and has a watermark for every table, a full copy comes first otherwise
     */
    @JsonIgnore
    public boolean isInitialized() {
        return !tables.isEmpty() && tables.stream().allMatch(table -> table.getWatermark() != null);
    }
}
