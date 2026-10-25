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

import com.github.sqljam.face.view.Messages;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.vdb.VdbExportListener;
import com.github.sqljam.vdb.VdbExportResult;

/**
 * @Description: VdbListeners turns the events of the vdb line into the events of the progress and the reports, each
 *               collection is a table. It needs no JavaFX, so that sync tasks run by the command line use it too.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
final class VdbListeners {

    private VdbListeners() {
    }

    /**
     * Events of the vdb line as events of the progress and the report: each collection is a table
     */
    static VdbExportListener adapt(ExportListener listener, String schema) {
        return new VdbExportListener() {
            @Override
            public void onTableStarted(VdbExportResult.TableResult table) {
                listener.onTableStart(null, schema, table.getTableName(), table.getTotal());
            }

            @Override
            public void onTableCreated(VdbExportResult.TableResult table, String statement) {
                listener.onDdlExecuted(statement, true);
            }

            @Override
            public void onRowsWritten(VdbExportResult.TableResult table) {
                listener.onTableProgress(null, schema, table.getTableName(), table.getRead(), table.getTotal());
            }

            @Override
            public void onRowSkipped(VdbExportResult.TableResult table, String key, Exception e) {
                listener.onError(Messages.format("vdb.progress.skipped", table.getTableName(), key, e.getMessage()),
                        e);
                listener.onRowSkipped(null, schema, table.getTableName(), key, e);
            }

            @Override
            public void onStatementSkipped(VdbExportResult.TableResult table, String statement, Exception e) {
                listener.onMessage(Messages.format("vdb.progress.statementSkipped", e.getMessage(), statement));
                listener.onDdlExecuted(statement, false);
            }

            @Override
            public void onTableFinished(VdbExportResult.TableResult table) {
                listener.onTableEnd(null, schema, table.getTableName(), table.getWritten());
            }

            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }
        };
    }
}
