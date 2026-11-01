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
package com.github.sqljam.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @Description: ExcelUtilsTest verifies workbooks written from a result set and read back as CSV: the header row,
 *               values of every common type, NULL and empty texts, inf and nan, long numbers and microseconds as
 *               text, parts of a number of rows, an empty result, sheet names and number formats of dates, and
 *               workbooks of Excel 97-2003 which are refused
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class ExcelUtilsTest {

    @TempDir
    File dir;

    @Test
    void writesAndReadsBackRows() throws Exception {
        File file = new File(dir, "out/notes.xlsx");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE T (ID INT, BIG DECIMAL(20, 2), R DOUBLE PRECISION, F BOOLEAN, D DATE,"
                    + " TS TIMESTAMP(6), MS TIMESTAMP(3), NOTE VARCHAR(50))");
            statement.execute("INSERT INTO T VALUES (1, 123456789012345678.91, 1.5, TRUE, DATE '2024-02-29',"
                    + " TIMESTAMP '2024-02-29 01:02:03.000004', TIMESTAMP '2024-02-29 01:02:03.250', 'a,\"b\"'),"
                    + " (2, 12.5, 'Infinity', FALSE, DATE '0001-01-01', TIMESTAMP '1899-12-31 23:00:00', NULL, ''),"
                    + " (3, NULL, 'NaN', NULL, NULL, NULL, NULL, NULL)");
            try (ResultSet rs = statement.executeQuery("SELECT * FROM T ORDER BY ID")) {
                List<File> files = ExcelUtils.write(rs, List.of("ID", "BIG", "R", "F", "D", "TS", "MS", "NOTE"),
                        "T:1", file, 2);
                assertEquals(List.of(file, new File(dir, "out/notes_2.xlsx")), files);
            }
            try (ResultSet rs = statement.executeQuery("SELECT * FROM T WHERE ID < 0")) {
                File empty = new File(dir, "empty.xlsx");
                assertEquals(List.of(empty), ExcelUtils.write(rs, List.of("ID"), "T", empty, 0));
                File csv = new File(dir, "empty.csv");
                assertEquals(0, ExcelUtils.toCsv(empty, null, csv));
                assertEquals("ID\n", Files.readString(csv.toPath(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(List.of("T_1"), ExcelUtils.sheetNames(file));
        File csv = new File(dir, "notes.csv");
        assertEquals(2, ExcelUtils.toCsv(file, "T_1", csv));
        assertEquals("ID,BIG,R,F,D,TS,MS,NOTE\n"
                + "1,123456789012345678.91,1.5,true,2024-02-29,2024-02-29 01:02:03.000004,2024-02-29 01:02:03.25,"
                + "\"a,\"\"b\"\"\"\n"
                + "2,12.50,inf,false,0001-01-01,1899-12-31 23:00:00.000000,,\"\"\n", Files.readString(csv.toPath(),
                StandardCharsets.UTF_8));
        File second = new File(dir, "second.csv");
        assertEquals(1, ExcelUtils.toCsv(new File(dir, "out/notes_2.xlsx"), null, second));
        assertEquals("ID,BIG,R,F,D,TS,MS,NOTE\n3,,nan,,,,,\n", Files.readString(second.toPath(),
                StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> ExcelUtils.toCsv(file, "missing", csv));
    }

    @Test
    void refusesWorkbooksOfExcel97() throws Exception {
        File file = new File(dir, "old.xlsx");
        Files.write(file.toPath(), new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1,
                0x1A, (byte) 0xE1, 0, 0});
        IOException e = assertThrows(IOException.class, () -> ExcelUtils.sheetNames(file));
        assertTrue(e.getMessage().contains("Excel 97-2003"), e.getMessage());
    }

    @Test
    void namesAndFormats() {
        assertTrue(ExcelUtils.isExcel(new File("A.XLSX")));
        assertFalse(ExcelUtils.isExcel(new File("a.xls")));
        assertEquals("a_b_c_d_", ExcelUtils.sheetName("a:b/c[d]"));
        assertEquals("Sheet1", ExcelUtils.sheetName("''"));
        assertEquals(31, ExcelUtils.sheetName("x".repeat(40)).length());
        assertTrue(ExcelUtils.isDateFormat(14, null));
        assertTrue(ExcelUtils.isDateFormat(200, "yyyy\\-mm\\-dd hh:mm"));
        assertTrue(ExcelUtils.isDateFormat(200, "[$-409]d-mmm-yy"));
        assertTrue(ExcelUtils.isDateFormat(200, "yyyy-mm-dd hh:mm:ss.000"));
        assertFalse(ExcelUtils.isDateFormat(0, "General"));
        assertFalse(ExcelUtils.isDateFormat(200, "0.00\"days\""));
        assertFalse(ExcelUtils.isDateFormat(200, "#,##0"));
        assertFalse(ExcelUtils.isDateFormat(null, null));
        assertEquals("\"\"", ExcelUtils.quote(""));
        assertEquals("\" a\"", ExcelUtils.quote(" a"));
        assertEquals("\"x\ny\"", ExcelUtils.quote("x\ny"));
        assertEquals("plain", ExcelUtils.quote("plain"));
    }
}
