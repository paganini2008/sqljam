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

import java.util.Map;

import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.impexp.ExportListener;

/**
 * @Description: SyncEngine runs the sync tasks of a kind of source: tables of data sources, or collections of
 *               vector databases which the vdb line registers, so that the scheduler knows neither
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public interface SyncEngine {

    /**
     * Header of the report of a run: its kind, source, target and options
     */
    void describe(SyncTask task, TransferReport report);

    /**
     * Copies the rows which changed since the watermarks of the task, returns the watermarks of the copied rows by
     * table, which are saved after a successful run only
     */
    Map<String, SyncTask.Watermark> run(SyncTask task, TransferReport report, ExportListener listener)
            throws Exception;

    /**
     * Counts the rows of the target tables after a run
     */
    default void verify(SyncTask task, TransferReport report) throws Exception {
    }
}
