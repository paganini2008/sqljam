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
package com.github.sqljam.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;

import com.github.sqljam.impexp.ImportExporter;

/**
 * @Description: ItRunner runs an import from a fixture database into a target database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class ItRunner {

    private ItRunner() {
    }

    /**
     * @param customizer customizes the importer before running, e.g. specifying versions
     */
    public static CollectingListener importTables(ItDatabase source, ItDatabase target,
                                                  Consumer<ImportExporter> customizer) throws Exception {
        source.loadFixture();
        ImportExporter importExporter = new ImportExporter();
        source.configureSource(importExporter.getExportConfiguration());
        importExporter.getExportConfiguration().setIdReused(true);
        importExporter.getExportConfiguration().setPageSize(100);
        target.configureTarget(importExporter.getImportConfiguration());
        customizer.accept(importExporter);
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        try {
            importExporter.exportDdlAndData();
        } catch (Exception e) {
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors(), "Errors of " + source + " -> " + target);
        assertTrue(listener.isCompleted());
        return listener;
    }
}
