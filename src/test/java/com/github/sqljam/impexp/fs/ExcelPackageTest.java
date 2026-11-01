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
package com.github.sqljam.impexp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.sqljam.utils.ExcelUtils;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.DataFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.ImpExpException;

/**
 * @Description: ExcelPackageTest verifies Excel packages and workbooks of other tools with H2 databases: the values
 *               of every common type read back exactly (NULL and empty strings apart, 15 digits and more, microseconds),
 *               names with delimiters and quotes, an empty table, a table split into workbooks of a number of rows,
 *               and workbooks without manifest.json whose sheets, header, dates, formulas and empty rows are read
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class ExcelPackageTest {

    @TempDir
    File dir;

    private static String newUrl() {
        return "jdbc:h2:mem:xl" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    private static void execute(String url, String... sqls) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }

    private static List<List<String>> rows(String url, String sql) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String sourceUrl() throws Exception {
        String url = newUrl();
        execute(url, "CREATE TABLE \"EMPTY\" (ID INT PRIMARY KEY, NAME VARCHAR(20))",
                "CREATE TABLE TYPES (ID INT PRIMARY KEY, BIG BIGINT, AMOUNT DECIMAL(20, 4), RATE DOUBLE PRECISION,"
                        + " FLAG BOOLEAN, SOLD_DAY DATE, SOLD_AT TIMESTAMP(6), CLOCK TIME, NOTE VARCHAR(100), BODY CLOB,"
                        + " BYTES VARBINARY(10), CODE UUID)",
                "INSERT INTO TYPES VALUES (1, 9223372036854775807, 1234567890123456.7891, 0.1, TRUE,"
                        + " DATE '2024-02-29', TIMESTAMP '2024-02-29 13:14:15.123456', TIME '23:59:58', '', 'long',"
                        + " X'00FF10', '123e4567-e89b-12d3-a456-426614174000')",
                "INSERT INTO TYPES VALUES (2, 42, 10.5, -1.5E300, FALSE, DATE '1900-01-01',"
                        + " TIMESTAMP '2024-01-02 03:04:05', TIME '00:00:00', 'a,b \"q\"\nline', NULL, NULL, NULL)",
                "INSERT INTO TYPES VALUES (3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
                "CREATE TABLE \"ODD, \"\"NAMES\"\"\" (ID INT PRIMARY KEY, \"A;B|C\" VARCHAR(50))",
                "INSERT INTO \"ODD, \"\"NAMES\"\"\" VALUES (1, '  spaced  '), (2, NULL)",
                "CREATE TABLE MANY (ID INT PRIMARY KEY, NOTE VARCHAR(100))",
                "INSERT INTO MANY SELECT X, 'row ' || X FROM SYSTEM_RANGE(1, 2500)");
        return url;
    }

    private static void configure(Exporter.ExportConfiguration configuration, String url) {
        configuration.setDbType(DbType.H2);
        configuration.setUrl(url);
        configuration.setUsername("sa");
        configuration.setPassword("");
        configuration.setIncludedSchemaNames(new String[]{"PUBLIC"});
        configuration.setIdReused(true);
        configuration.setConnectionPoolEnabled(false);
    }

    private static ExportManifest.TableEntry table(ExportManifest manifest, String name) {
        return manifest.getTables().stream().filter(entry -> name.equals(entry.getName())).findFirst()
                .orElseThrow();
    }

    @Test
    void excelPackageReadsBackExactly() throws Exception {
        String source = sourceUrl();
        File packageDir = new File(dir, "excel");
        ExcelExporter exporter = new ExcelExporter(packageDir);
        configure(exporter.getConfiguration(), source);
        exporter.setMaxRows(1000);
        exporter.exportDdlAndData();
        ExportManifest manifest = ExportManifest.read(packageDir);
        assertEquals(DataFormat.EXCEL, manifest.getDataFormat());
        assertEquals(1000, ((Number) manifest.getOptions().get("maxRows")).intValue());
        assertEquals(1, table(manifest, "EMPTY").getDataFiles().size());
        List<String> manyFiles = List.copyOf(table(manifest, "MANY").getDataFiles());
        assertEquals(3, manyFiles.size(), manyFiles.toString());
        assertTrue(manyFiles.get(2).endsWith("MANY_3.xlsx"), manyFiles.toString());

        // Cells of their types: numbers, booleans and dates are cells for spreadsheets, 19 digits are text
        File types = new File(packageDir, table(manifest, "TYPES").getDataFiles().iterator().next());
        try (ReadableWorkbook workbook = new ReadableWorkbook(types)) {
            assertEquals("TYPES", workbook.getFirstSheet().getName());
            List<Row> sheet = workbook.getFirstSheet().read();
            assertEquals("ID", sheet.get(0).getCellText(0));
            assertEquals("9223372036854775807", sheet.get(1).getCellText(1));
            assertEquals(new BigDecimal("42"), sheet.get(2).getCellAsNumber(1).orElseThrow());
            assertEquals(Boolean.TRUE, sheet.get(1).getCellAsBoolean(4).orElseThrow());
            assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0), sheet.get(1).getCellAsDate(5).orElseThrow());
        }

        String target = newUrl();
        try (Connection connection = DriverManager.getConnection(target, "sa", "")) {
            AbstractFileImporter importer = AbstractFileImporter.forDirectory(packageDir, connection, DbType.H2);
            assertTrue(importer instanceof ExcelImporter);
            importer.importDirectory(packageDir);
            assertEquals(0, importer.getFailedCount());
        }
        for (String sql : new String[]{"SELECT * FROM TYPES ORDER BY ID", "SELECT * FROM MANY ORDER BY ID",
                "SELECT * FROM \"ODD, \"\"NAMES\"\"\" ORDER BY ID", "SELECT * FROM \"EMPTY\""}) {
            assertEquals(rows(source, sql), rows(target, sql), sql);
        }
        // NULL and empty strings stay apart
        assertEquals("", rows(target, "SELECT NOTE FROM TYPES WHERE ID = 1").get(0).get(0));
        assertNull(rows(target, "SELECT NOTE FROM TYPES WHERE ID = 3").get(0).get(0));
    }

    /**
     * A workbook of a spreadsheet without manifest.json: the first row names the columns, the types are detected
     */
    @Test
    void importsWorkbookOfOtherTools() throws Exception {
        File file = new File(dir, "sales.xlsx");
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            Workbook workbook = new Workbook(out, "Spreadsheet", "1.0");
            Worksheet notes = workbook.newWorksheet("Notes");
            notes.value(0, 0, "not the first table");
            Worksheet sales = workbook.newWorksheet("Sales 2024");
            String[] header = {"id", "city", "amount", "sold on", "paid", "total", "", "comment"};
            for (int c = 0; c < header.length; c++) {
                sales.value(0, c, header[c]);
            }
            sales.value(1, 0, 1);
            sales.value(1, 1, "北京");
            sales.value(1, 2, 10.5);
            sales.value(1, 3, LocalDate.of(2024, 3, 1));
            sales.style(1, 3).format("yyyy-mm-dd").set();
            sales.value(1, 4, true);
            sales.formula(1, 5, "C2*2");
            sales.value(1, 7, "");
            sales.value(2, 0, 2);
            sales.value(2, 1, "Paris, \"FR\"");
            sales.value(2, 3, LocalDate.of(2024, 3, 2));
            sales.style(2, 3).format("d/m/yy").set();
            sales.value(2, 4, false);
            // An empty row in the middle and empty rows at the end are skipped
            sales.value(4, 0, 3);
            sales.value(4, 1, "Rome");
            sales.value(4, 2, 7);
            workbook.finish();
        }
        assertEquals(List.of("Notes", "Sales 2024"), ExcelUtils.sheetNames(file));
        String target = newUrl();
        try (Connection connection = DriverManager.getConnection(target, "sa", "")) {
            ExcelImporter importer = new ExcelImporter(connection, DbType.H2);
            importer.setSheet("Sales 2024");
            assertEquals("Sales 2024", importer.getSheet());
            assertEquals(3, importer.importFiles(List.of(file), "SALES", DataFileImporter.Mode.CREATE));
        }
        List<List<String>> rows = rows(target, "SELECT * FROM SALES ORDER BY 1");
        assertEquals(Arrays.asList("1", "北京", "10.5", "2024-03-01", "TRUE", null, null, ""), rows.get(0));
        assertEquals(Arrays.asList("2", "Paris, \"FR\"", null, "2024-03-02", "FALSE", null, null, null), rows.get(1));
        assertEquals("Rome", rows.get(2).get(1));
        // Detected types, the names of the columns in the case of the target, a column without a name is numbered
        assertEquals(List.of(List.of("ID", "BIGINT"), List.of("CITY", "CHARACTER LARGE OBJECT"),
                List.of("AMOUNT", "DOUBLE PRECISION"), List.of("SOLD ON", "DATE"), List.of("PAID", "BOOLEAN"),
                List.of("TOTAL", "CHARACTER LARGE OBJECT"), List.of("COLUMN7", "CHARACTER LARGE OBJECT"),
                List.of("COMMENT", "CHARACTER LARGE OBJECT")), rows(target, "SELECT COLUMN_NAME, DATA_TYPE FROM"
                + " INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SALES' ORDER BY ORDINAL_POSITION"));

        // The first sheet by default, a missing sheet is told
        String other = newUrl();
        try (Connection connection = DriverManager.getConnection(other, "sa", "")) {
            ExcelImporter importer = new ExcelImporter(connection, DbType.H2);
            assertEquals(0, importer.importFiles(List.of(file), "NOTES", DataFileImporter.Mode.CREATE));
            importer.setSheet("Missing");
            assertThrows(ImpExpException.class, () -> importer.importFiles(List.of(file), "MISSING",
                    DataFileImporter.Mode.CREATE));
        }
    }

    @Test
    void typesOfCells() {
        assertTrue(ExcelExporter.isCellType("DECIMAL(20,4)"));
        assertTrue(ExcelExporter.isCellType("timestamp"));
        assertFalse(ExcelExporter.isCellType("TIMESTAMP WITH TIME ZONE"));
        assertFalse(ExcelExporter.isCellType("BLOB"));
    }
}
