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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.ImportExporter;

/**
 * @Description: AbstractImportIT imports fixture tables of a source database into every database directly
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public abstract class AbstractImportIT {

    protected final ItDatabase source;

    protected AbstractImportIT(ItDatabase source) {
        this.source = source;
    }

    static boolean isTargetSelected(ItDatabase target) {
        String filter = System.getProperty("it.target");
        return filter == null || target.name().equalsIgnoreCase(filter);
    }

    @ParameterizedTest(name = "import into {0}")
    @EnumSource(ItDatabase.class)
    void importInto(ItDatabase target) throws Exception {
        // Oracle test user has only one schema
        assumeTrue(!(source == ItDatabase.ORACLE && target == ItDatabase.ORACLE));
        assumeTrue(isTargetSelected(target));
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();

        ImportExporter importExporter = new ImportExporter();
        source.configureSource(importExporter.getExportConfiguration());
        importExporter.getExportConfiguration().setIdReused(true);
        importExporter.getExportConfiguration().setPageSize(100);
        target.configureTarget(importExporter.getImportConfiguration());
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        try {
            importExporter.exportDdlAndData();
        } catch (Exception e) {
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors(), "Errors of " + source + " -> " + target);
        assertEquals(Boolean.TRUE, listener.getSuccessful());
        assertTrue(listener.isCompleted(), "Progress 100%");

        try (Connection connection = target.getTargetConnection()) {
            new ItVerifier(source, target, connection).verifyAll();
        }
    }

    /**
     * Rows are optional: structure only import creates tables, keys, indexes, comments and sequences
     */
    @Test
    void importStructureOnly() throws Exception {
        ItDatabase target = ItDatabase.H2;
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();

        ImportExporter importExporter = new ImportExporter();
        source.configureSource(importExporter.getExportConfiguration());
        target.configureTarget(importExporter.getImportConfiguration());
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        importExporter.exportDdl();
        assertEquals(List.of(), listener.getErrors());
        assertEquals(Boolean.TRUE, listener.getSuccessful());

        try (Connection connection = target.getTargetConnection()) {
            new ItVerifier(source, target, connection).verifyStructureOnly();
        }
    }
}
