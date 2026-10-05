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

import java.util.Map;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.page.EachPage;

/**
 * @Description: ExportHandler receives ddl and rows from Exporter, e.g. writing sql scripts or importing into another database
 * @Author: Fred Feng
 * @Date: 30/03/2023
 * @Version 1.0.0
 */
public interface ExportHandler {

    /**
     * Called before {@link #start()} with the configuration of the export
     */
    default void prepare(Exporter.ExportConfiguration configuration, ExportMode exportMode) {
    }

    default void start() {
    }

    /**
     * Called after the export finished (successfully or not) and before releasing resources
     */
    default void finish(boolean successful) throws Exception {
    }

    default void releaseExternalResource() {
    }

    default void setExportListener(ExportListener exportListener) {
    }

    /**
     * Adjusts the dialect generating ddl/dml after the handler started, e.g. the dialect of the version of target
     * database. Returns the dialect to use.
     */
    default Dialect configureDialect(Dialect dialect) {
        return dialect;
    }

    /**
     * Called after metadata of source database loaded
     */
    default void prepare(ServerMetaData serverMetaData) throws Exception {
    }

    void exportDdl(DdlScripter ddlScripter) throws Exception;

    void exportData(String catalogName, 
                    String schemaName, 
                    String tableName, 
                    TableMetaData tableMetaData, 
                    EachPage<Map<String, Object>> eachPage,
                    boolean idReused, 
                    ConnectionFactory connectionFactory) throws Exception;

    /**
     * Exports constraints (foreign keys) after all data exported
     */
    default void exportConstraints(DdlScripter ddlScripter) throws Exception {
    }
}
