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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @Description: DataFileBoundaryTest verifies CSV and Parquet packages at their boundaries with H2 databases: empty
 *               tables (a header only), names with delimiters, quotes and spaces, NULL and empty strings, a table of
 *               one row which is not split, and parts of a large table in the order of its rows
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class DataFileBoundaryTest {

    @TempDir
    File dir;

    private static String newUrl() {
        return "jdbc:h2:mem:df" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
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
                "CREATE TABLE \"ODD, \"\"NAMES\"\"\" (ID INT PRIMARY KEY, \"A;B|C\" VARCHAR(50), \"QUOTE\"\"D\" "
                        + "VARCHAR(50), \"TAB\tCOL\" VARCHAR(50))",
                "INSERT INTO \"ODD, \"\"NAMES\"\"\" VALUES (1, '', NULL, 'tab\there'), (2, 'a,b;c|d', '\"q\"', "
                        + "'line\nbreak'), (3, NULL, '', '   ')",
                "CREATE TABLE ONE_ROW (ID INT PRIMARY KEY, NOTE VARCHAR(20))",
                "INSERT INTO ONE_ROW VALUES (1, 'only')",
                "CREATE TABLE MANY (ID INT PRIMARY KEY, NOTE VARCHAR(100))",
                "INSERT INTO MANY SELECT X, REPEAT('x', 80) || X FROM SYSTEM_RANGE(1, 3000)");
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

    private static void importPackage(File packageDir, String targetUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(targetUrl, "sa", "")) {
            AbstractFileImporter importer = AbstractFileImporter.forDirectory(packageDir, connection, DbType.H2);
            importer.importDirectory(packageDir);
            assertEquals(0, importer.getFailedCount());
        }
    }

    private static void verify(String source, String target) throws Exception {
        assertEquals(List.of(), rows(target, "SELECT * FROM \"EMPTY\""));
        String odd = "SELECT * FROM \"ODD, \"\"NAMES\"\"\" ORDER BY ID";
        assertEquals(rows(source, odd), rows(target, odd));
        // NULL and empty strings stay apart
        assertNull(rows(target, odd).get(0).get(2));
        assertEquals("", rows(target, odd).get(0).get(1));
        assertEquals("", rows(target, odd).get(2).get(2));
        assertEquals(List.of(List.of("1", "only")), rows(target, "SELECT * FROM ONE_ROW"));
        String many = "SELECT * FROM MANY ORDER BY ID";
        assertEquals(rows(source, many), rows(target, many));
    }

    @Test
    void csvPackageAtBoundaries() throws Exception {
        String source = sourceUrl();
        File packageDir = new File(dir, "csv");
        CsvExporter exporter = new CsvExporter(packageDir);
        configure(exporter.getConfiguration(), source);
        exporter.setDelimiter(";");
        exporter.setMaxFileSize(32 * 1024);
        exporter.exportDdlAndData();
        ExportManifest manifest = ExportManifest.read(packageDir);
        assertEquals(";", manifest.getOptions().get("delimiter"));
        ExportManifest.TableEntry empty = table(manifest, "EMPTY");
        assertEquals(0, empty.getRows());
        assertEquals(1, empty.getDataFiles().size());
        // An empty table is a header only
        assertEquals("ID;NAME\n", Files.readString(new File(packageDir, empty.getDataFiles().iterator().next())
                .toPath(), StandardCharsets.UTF_8));
        assertEquals(List.of("ID", "NAME"), table(manifest, "EMPTY").getColumns().stream()
                .map(ExportManifest.ColumnEntry::getName).toList());
        assertEquals(1, table(manifest, "ONE_ROW").getDataFiles().size());
        List<String> manyFiles = List.copyOf(table(manifest, "MANY").getDataFiles());
        assertTrue(manyFiles.size() > 3, manyFiles.toString());
        assertTrue(manyFiles.get(1).endsWith("MANY_2.csv"), manyFiles.toString());
        String target = newUrl();
        importPackage(packageDir, target);
        verify(source, target);
    }

    @Test
    void parquetPackageAtBoundaries() throws Exception {
        String source = sourceUrl();
        File packageDir = new File(dir, "parquet");
        ParquetExporter exporter = new ParquetExporter(packageDir);
        configure(exporter.getConfiguration(), source);
        exporter.setMaxFileSize(8 * 1024);
        exporter.exportDdlAndData();
        ExportManifest manifest = ExportManifest.read(packageDir);
        assertEquals(8 * 1024, ((Number) manifest.getOptions().get("maxFileSize")).longValue());
        assertEquals(1, table(manifest, "EMPTY").getDataFiles().size());
        // One row is never split
        assertEquals(1, table(manifest, "ONE_ROW").getDataFiles().size());
        List<String> manyFiles = List.copyOf(table(manifest, "MANY").getDataFiles());
        assertTrue(manyFiles.size() > 1, manyFiles.toString());
        assertTrue(manyFiles.get(1).endsWith("MANY_2.parquet"), manyFiles.toString());
        // Each part holds the next rows of the table
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            long previousMax = 0;
            for (String path : manyFiles) {
                try (ResultSet rs = statement.executeQuery("SELECT MIN(ID), MAX(ID) FROM read_parquet('"
                        + new File(packageDir, path).getAbsolutePath() + "')")) {
                    rs.next();
                    assertEquals(previousMax + 1, rs.getLong(1), path);
                    previousMax = rs.getLong(2);
                }
            }
            assertEquals(3000, previousMax);
        }
        String target = newUrl();
        importPackage(packageDir, target);
        verify(source, target);
    }

    @Test
    void emptyCsvFileOfOtherTools() throws Exception {
        File file = new File(dir, "empty.csv");
        Files.writeString(file.toPath(), "id,name\n", StandardCharsets.UTF_8);
        String target = newUrl();
        try (Connection connection = DriverManager.getConnection(target, "sa", "")) {
            CsvImporter importer = new CsvImporter(connection, DbType.H2);
            importer.setDelimiter(",");
            assertEquals(0, importer.importFiles(List.of(file), "EMPTY_CSV", DataFileImporter.Mode.CREATE));
        }
        assertEquals(List.of(List.of("0")), rows(target, "SELECT COUNT(*) FROM EMPTY_CSV"));
    }

    private static ExportManifest.TableEntry table(ExportManifest manifest, String name) {
        return manifest.getTables().stream().filter(table -> name.equals(table.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("No table " + name));
    }

    private static String optionsSource() throws Exception {
        String url = newUrl();
        execute(url, "CREATE TABLE NOTES (ID INT PRIMARY KEY, TXT VARCHAR(50), D DATE, TS TIMESTAMP)",
                "INSERT INTO NOTES VALUES (1, 'NULL', DATE '2024-01-31', TIMESTAMP '2024-01-31 08:09:10'),"
                        + " (2, NULL, NULL, NULL), (3, '', DATE '1999-12-31', TIMESTAMP '1999-12-31 23:59:59'),"
                        + " (4, 'it''s 中文; \"q\"', DATE '2000-02-29', TIMESTAMP '2000-02-29 00:00:00')");
        return url;
    }

    private File exportNotes(String source, CsvOptions options, String name) throws Exception {
        File packageDir = new File(dir, name);
        CsvExporter exporter = new CsvExporter(packageDir);
        configure(exporter.getConfiguration(), source);
        exporter.setOptions(options);
        exporter.exportDdlAndData();
        return packageDir;
    }

    /**
     * Options other than the defaults keep every value: the text of NULL apart from the same string, single quotes,
     * a semicolon, no header, GBK and a date format without fractions of seconds
     */
    @Test
    void csvOptionsKeepValues() throws Exception {
        String source = optionsSource();
        CsvOptions options = new CsvOptions();
        options.setDelimiter(";");
        options.setQuote("'");
        options.setNullString("NULL");
        options.setHeader(false);
        options.setEncoding("GBK");
        options.setDateFormat("%d.%m.%Y");
        File packageDir = exportNotes(source, options, "options");
        ExportManifest manifest = ExportManifest.read(packageDir);
        assertEquals(options, CsvOptions.fromMap(manifest.getOptions()));
        File data = new File(packageDir, table(manifest, "NOTES").getDataFiles().iterator().next());
        String text = new String(Files.readAllBytes(data.toPath()), Charset.forName("GBK"));
        // No header, dates in the format, NULL as text and the string NULL quoted
        assertTrue(text.startsWith("1;'NULL';31.01.2024;31.01.2024 08:09:10"), text);
        assertTrue(text.contains("2;NULL;NULL;NULL"), text);
        assertTrue(text.contains("中文"), text);
        String target = newUrl();
        importPackage(packageDir, target);
        String query = "SELECT * FROM NOTES ORDER BY ID";
        assertEquals(rows(source, query), rows(target, query));
        assertEquals("NULL", rows(target, query).get(0).get(1));
        assertNull(rows(target, query).get(1).get(1));
        assertEquals("", rows(target, query).get(2).get(1));
    }

    @Test
    void csvWithByteOrderMark() throws Exception {
        String source = optionsSource();
        CsvOptions options = new CsvOptions();
        options.setEncoding(CsvOptions.UTF_8_BOM);
        File packageDir = exportNotes(source, options, "bom");
        ExportManifest manifest = ExportManifest.read(packageDir);
        byte[] bytes = Files.readAllBytes(new File(packageDir, table(manifest, "NOTES").getDataFiles().iterator()
                .next()).toPath());
        // Excel recognizes UTF-8 by the byte order mark
        assertEquals(List.of((byte) 0xEF, (byte) 0xBB, (byte) 0xBF), List.of(bytes[0], bytes[1], bytes[2]));
        String target = newUrl();
        importPackage(packageDir, target);
        assertEquals(rows(source, "SELECT * FROM NOTES ORDER BY ID"), rows(target, "SELECT * FROM NOTES ORDER BY ID"));
    }

    /**
     * Characters which the encoding can not hold stop the export instead of being replaced
     */
    @Test
    void refusesCharactersOutsideTheEncoding() throws Exception {
        String source = optionsSource();
        CsvOptions options = new CsvOptions();
        options.setEncoding("ISO-8859-1");
        ImpExpException e = assertThrows(ImpExpException.class, () -> exportNotes(source, options, "latin"));
        assertTrue(e.getMessage().contains("can not be converted from UTF-8 to ISO-8859-1"), e.getMessage());
    }

    @Test
    void importsCsvFileOfOtherToolWithOptions() throws Exception {
        File file = new File(dir, "sales.txt");
        Files.write(file.toPath(), "1;北京;10.5\n2;上海;\n3;'a;b';7\n".getBytes(Charset.forName("GBK")));
        CsvOptions options = new CsvOptions();
        options.setDelimiter(";");
        options.setQuote("'");
        options.setHeader(false);
        options.setEncoding("GBK");
        String target = newUrl();
        try (Connection connection = DriverManager.getConnection(target, "sa", "")) {
            CsvImporter importer = new CsvImporter(connection, DbType.H2);
            importer.setOptions(options);
            assertEquals(3, importer.importFiles(List.of(file), "SALES", DataFileImporter.Mode.CREATE));
        }
        List<List<String>> rows = rows(target, "SELECT * FROM SALES ORDER BY 1");
        assertEquals("北京", rows.get(0).get(1));
        assertNull(rows.get(1).get(2));
        assertEquals("a;b", rows.get(2).get(1));
    }

    /**
     * CSV files of other tools into an existing table: rows of existing keys are kept, a row with an error is
     * skipped and reported when errors are skipped, otherwise it stops the import
     */
    @Test
    void skipsExistingRowsAndErrorsOfFiles() throws Exception {
        File file = new File(dir, "items.csv");
        Files.write(file.toPath(), "id,note\n1,new\n2,b\n3,toolongvalue\n4,d\n".getBytes(StandardCharsets.UTF_8));
        String target = newUrl();
        try (Connection connection = DriverManager.getConnection(target, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY, NOTE VARCHAR(5))");
            statement.execute("INSERT INTO ITEMS VALUES (1, 'old')");
        }
        try (Connection connection = DriverManager.getConnection(target, "sa", "")) {
            CsvImporter importer = new CsvImporter(connection, DbType.H2);
            importer.setPageSize(2);
            assertThrows(Exception.class, () -> importer.importFiles(List.of(file), "ITEMS",
                    DataFileImporter.Mode.SKIP_EXISTING));
        }
        // The first page is committed before the error stops the import
        assertEquals(List.of(List.of("1", "old"), List.of("2", "b")), rows(target, "SELECT * FROM ITEMS ORDER BY 1"));
        try (Connection connection = DriverManager.getConnection(target, "sa", "")) {
            CsvImporter importer = new CsvImporter(connection, DbType.H2);
            importer.setPageSize(2);
            importer.setStopOnError(false);
            com.github.sqljam.it.CollectingListener listener = new com.github.sqljam.it.CollectingListener();
            importer.setExportListener(listener);
            // Rows without errors are counted, the skipped row is not
            assertEquals(3, importer.importFiles(List.of(file), "ITEMS", DataFileImporter.Mode.SKIP_EXISTING));
            assertEquals(1, listener.getErrors().size(), listener.getErrors().toString());
            // The row is named by the columns of the target table
            assertTrue(listener.getErrors().get(0).startsWith("Skipped row of table 'ITEMS' (ID=3): "),
                    listener.getErrors().get(0));
        }
        assertEquals(List.of(List.of("1", "old"), List.of("2", "b"), List.of("4", "d")), rows(target,
                "SELECT * FROM ITEMS ORDER BY 1"));
    }
}
