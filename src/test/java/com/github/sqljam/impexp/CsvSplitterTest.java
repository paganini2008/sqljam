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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @Description: CsvSplitterTest verifies parts of large CSV files: named like data files of sql scripts, each with
 *               the header row, records are never cut (also with line breaks and quotes inside fields), and the
 *               order of importing by part numbers
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class CsvSplitterTest {

    private static final String HEADER = "id,name,note\n";

    @TempDir
    File dir;

    private File write(String name, int rows) throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= rows; i++) {
            // Line breaks, delimiters and escaped quotes inside quoted fields
            csv.append(i).append(",\"name ").append(i).append("\",\"line one\nline \"\"two\"\", ")
                    .append("中文 ").append(i).append("\"\n");
        }
        File file = new File(dir, name);
        Files.writeString(file.toPath(), csv.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Records of the parts without their headers
     */
    private static List<String> records(List<File> parts) throws Exception {
        List<String> records = new ArrayList<>();
        for (File part : parts) {
            String text = Files.readString(part.toPath(), StandardCharsets.UTF_8);
            assertTrue(text.startsWith(HEADER), "Header of " + part.getName());
            String body = text.substring(HEADER.length());
            // Records end with a quote and a line break
            for (String record : body.split("(?<=\")\n")) {
                if (!record.isEmpty()) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    @Test
    void splitsAtRecords() throws Exception {
        File file = write("orders.csv", 200);
        String original = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        List<File> parts = CsvSplitter.split(file, 1024);
        assertTrue(parts.size() > 5, "Parts: " + parts.size());
        assertEquals("orders.csv", parts.get(0).getName());
        assertEquals("orders_2.csv", parts.get(1).getName());
        assertEquals("orders_" + parts.size() + ".csv", parts.get(parts.size() - 1).getName());
        for (File part : parts) {
            assertTrue(part.length() <= 1024, part.getName() + " " + part.length());
        }
        // Nothing is lost or reordered, the temporary file is removed
        List<String> records = records(parts);
        assertEquals(200, records.size());
        assertEquals(original.substring(HEADER.length()), String.join("\n", records) + "\n");
        assertEquals(parts.size(), dir.listFiles().length);
    }

    @Test
    void keepsSmallFiles() throws Exception {
        File file = write("small.csv", 3);
        long size = file.length();
        assertEquals(List.of(file), CsvSplitter.split(file, size));
        assertEquals(List.of(file), CsvSplitter.split(file, 0));
        assertEquals(size, file.length());
    }

    @Test
    void keepsLargeRecordsWhole() throws Exception {
        // Each record is larger than the maximum size: one record per part
        File file = write("large.csv", 5);
        List<File> parts = CsvSplitter.split(file, 10);
        assertEquals(5, parts.size());
        assertEquals(5, records(parts).size());
    }

    @Test
    void splitsFileWithoutTrailingLineBreak() throws Exception {
        File file = new File(dir, "plain.csv");
        Files.writeString(file.toPath(), HEADER + "1,\"a\",\"x\"\n2,\"b\",\"y\"", StandardCharsets.UTF_8);
        List<File> parts = CsvSplitter.split(file, HEADER.length() + 12);
        assertEquals(2, parts.size());
        assertEquals(HEADER + "2,\"b\",\"y\"", Files.readString(parts.get(1).toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void sortsPartsByNumber() {
        List<File> files = new ArrayList<>();
        for (String name : new String[]{"orders_10.csv", "orders_2.csv", "items.csv", "orders.csv", "orders_9.csv",
                "items_2.csv", "orders_x.csv"}) {
            files.add(new File(dir, name));
        }
        List<String> names = new ArrayList<>();
        CsvSplitter.sortParts(files).forEach(file -> names.add(file.getName()));
        assertEquals(List.of("items.csv", "items_2.csv", "orders.csv", "orders_2.csv", "orders_9.csv",
                "orders_10.csv", "orders_x.csv"), names);
    }
}
