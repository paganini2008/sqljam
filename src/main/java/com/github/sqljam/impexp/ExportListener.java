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
package com.github.sqljam.impexp;

/**
 * @Description: ExportListener receives progress, messages and errors of an export or import, and cancels it
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public interface ExportListener {

    ExportListener NONE = new ExportListener() {
    };

    default void onStart(ExportMode exportMode) {
    }

    default void onTableStart(String catalogName, String schemaName, String tableName, long totalRows) {
    }

    default void onTableProgress(String catalogName, String schemaName, String tableName, long processedRows,
                                 long totalRows) {
    }

    default void onTableEnd(String catalogName, String schemaName, String tableName, long processedRows) {
    }

    /**
     * Overall progress: rows of all tables when exporting/importing data, bytes when importing an export package
     */
    default void onProgress(long processed, long total) {
    }

    default void onMessage(String message) {
    }

    default void onError(String message, Throwable e) {
    }

    /**
     * A row with an error which was skipped, after its error was told by {@link #onError}, e.g. for a report
     *
     * @param key values which name the row, e.g. its primary key
     */
    default void onRowSkipped(String catalogName, String schemaName, String tableName, String key, Throwable e) {
    }

    /**
     * A statement of the structure which was run on the target: a table, a key, an index, a constraint, a sequence
     * or a comment, e.g. for a report of the objects which were created
     */
    default void onDdlExecuted(String statement, boolean successful) {
    }

    default void onEnd(boolean successful) {
    }

    default boolean isCancelled() {
        return false;
    }
}
