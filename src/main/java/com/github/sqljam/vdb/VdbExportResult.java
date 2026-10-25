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
package com.github.sqljam.vdb;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * @Description: VdbExportResult is what an import of collections did: rows read, written and skipped of each
 *               table, the vector indexes created and the warnings
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
public class VdbExportResult {

    @Getter
    @Setter
    public static final class TableResult {

        private final String collection;
        private final String tableName;
        /**
         * Records of the collection which are read, -1 if they are not known before
         */
        private long total = -1;
        private long read;
        private long written;
        private long skipped;
        private int indexes;
        private boolean created;
        private final List<String> warnings = new ArrayList<>();
        private long elapsed;

        public TableResult(String collection, String tableName) {
            this.collection = collection;
            this.tableName = tableName;
        }
    }

    private final List<TableResult> tables = new ArrayList<>();
    @Setter
    private boolean cancelled;

    public long getWritten() {
        return tables.stream().mapToLong(TableResult::getWritten).sum();
    }

    public long getSkipped() {
        return tables.stream().mapToLong(TableResult::getSkipped).sum();
    }
}
