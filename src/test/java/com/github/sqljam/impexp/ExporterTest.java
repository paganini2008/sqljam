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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * @Description: ExporterTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ExporterTest {

    @Test
    void pageSize() {
        Exporter.ExportConfiguration configuration = new Exporter.ExportConfiguration();
        assertEquals(Exporter.DEFAULT_PAGE_SIZE, configuration.getPageSize());
        assertEquals(Exporter.DEFAULT_LOB_PAGE_SIZE, configuration.getLobPageSize());
        // Tables with LOB columns are read by smaller pages
        assertEquals(5000, Exporter.getPageSize(5000, 100, false));
        assertEquals(100, Exporter.getPageSize(5000, 100, true));
        assertEquals(50, Exporter.getPageSize(50, 100, true));
        assertEquals(5000, Exporter.getPageSize(5000, 0, true));
        assertEquals(Exporter.DEFAULT_PAGE_SIZE, Exporter.getPageSize(0, 100, false));
    }
}
