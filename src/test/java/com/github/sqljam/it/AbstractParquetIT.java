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

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.CsvImporter;
import com.github.sqljam.impexp.CsvOptions;
import com.github.sqljam.impexp.DataFileExporter;
import com.github.sqljam.impexp.DataFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.ParquetExporter;
import com.github.sqljam.impexp.ParquetImporter;
import com.github.sqljam.impexp.ScriptExportHandler;

/**
 * @Description: AbstractParquetIT exports fixture tables of a source database as Parquet and CSV packages (split
 *               into parts) and imports them into every database, and loads Parquet and CSV files into new and
 *               existing tables
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public abstract class AbstractParquetIT {

    /**
     * Small parts, so that tables of the fixture are split: emp.csv, emp_2.csv ... imported in order
     */
    private static final long CSV_PART_SIZE = 8 * 1024;
    private static final long PARQUET_PART_SIZE = 16 * 1024;

    protected final ItDatabase source;

    protected AbstractParquetIT(ItDatabase source) {
        this.source = source;
    }

    protected File getExportDirectory(String name) {
        File dir = new File(ItDatabase.DATA_DIR, "parquet/" + source + "-" + name);
        FileUtils.deleteQuietly(dir);
        return dir;
    }

    private DataFileExporter newExporter(File dir, ItDatabase target, DataFormat format) {
        int rotation = source.ordinal() + target.ordinal();
        DataFileExporter exporter;
        if (format == DataFormat.CSV) {
            CsvExporter csvExporter = new CsvExporter(dir);
            // Options are rotated over the matrix: delimiter, encoding, quote, text of NULL and header
            CsvOptions options = new CsvOptions();
            options.setDelimiter(CsvExporter.DELIMITERS[rotation % CsvExporter.DELIMITERS.length]);
            // Encodings which hold the Chinese text of the fixture, others stop the export
            String[] encodings = {"UTF-8", CsvOptions.UTF_8_BOM, "GBK", "GB18030", "UTF-16"};
            options.setEncoding(encodings[rotation % encodings.length]);
            options.setQuote(CsvOptions.QUOTES[rotation % CsvOptions.QUOTES.length]);
            options.setNullString(rotation % 3 == 0 ? "NULL" : "");
            options.setHeader(rotation % 4 != 1);
            csvExporter.setOptions(options);
            csvExporter.setMaxFileSize(CSV_PART_SIZE);
            exporter = csvExporter;
        } else {
            ParquetExporter parquetExporter = new ParquetExporter(dir);
            // Compressions are rotated over the matrix
            parquetExporter.setCompression(ParquetExporter.COMPRESSIONS[rotation % ParquetExporter.COMPRESSIONS
                    .length]);
            parquetExporter.setMaxFileSize(PARQUET_PART_SIZE);
            exporter = parquetExporter;
        }
        source.configureSource(exporter.getConfiguration());
        exporter.getConfiguration().setIdReused(true);
        exporter.setTargetDbType(target.getDbType());
        exporter.setTargetCatalogName(target.getTargetCatalog());
        exporter.setTargetSchemaName(target.getTargetSchema());
        return exporter;
    }

    @ParameterizedTest(name = "parquet for {0}")
    @EnumSource(ItDatabase.class)
    void exportAndImport(ItDatabase target) throws Exception {
        exportAndImport(target, DataFormat.PARQUET);
    }

    @ParameterizedTest(name = "csv for {0}")
    @EnumSource(ItDatabase.class)
    void exportAndImportCsv(ItDatabase target) throws Exception {
        exportAndImport(target, DataFormat.CSV);
    }

    private void exportAndImport(ItDatabase target, DataFormat format) throws Exception {
        // Test users of Oracle and MariaDB have one schema, the same database type is verified by copyIntoSameSchema
        assumeTrue(!(source == target && source.isSingleSchema()));
        assumeTrue(AbstractImportIT.isTargetSelected(target));
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();

        File dir = getExportDirectory(format.name().toLowerCase() + "-" + target.name());
        DataFileExporter exporter = newExporter(dir, target, format);
        CollectingListener listener = new CollectingListener();
        exporter.setExportListener(listener);
        try {
            exporter.exportDdlAndData();
        } catch (Exception e) {
            throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
        }
        assertEquals(List.of(), listener.getErrors());
        assertEquals(Boolean.TRUE, listener.getSuccessful());

        ExportManifest manifest = ExportManifest.read(dir);
        assertEquals(format, manifest.getDataFormat());
        assertEquals(ExportManifest.Status.COMPLETED, manifest.getStatus());
        assertEquals(target.getDbType(), manifest.getTarget().getDbType());
        assertEquals(5, manifest.getTables().size(), "Tables in manifest: " + manifest.getTables().size());
        ExportManifest.TableEntry emp = manifest.getTables().stream()
                .filter(table -> table.getName().equalsIgnoreCase(source.getPrefix() + "emp")).findFirst()
                .orElseThrow();
        assertEquals(ItDatabase.EMP_COUNT, emp.getRows());
        // ClickHouse has no identity columns
        assertEquals(source == ItDatabase.CLICKHOUSE ? 0 : 1, emp.getIdentityColumns().size(), "Identity column of emp");
        ExportManifest.FileType fileType = format == DataFormat.CSV ? ExportManifest.FileType.CSV
                : ExportManifest.FileType.PARQUET;
        assertTrue(manifest.getFiles().stream().anyMatch(f -> f.getType() == fileType));
        assertEquals(ExportManifest.FileType.SCHEMA, manifest.getFiles().get(0).getType());
        List<String> empFiles = List.copyOf(emp.getDataFiles());
        for (String path : empFiles) {
            assertTrue(new File(dir, path).isFile(), "Data file of emp: " + path);
        }
        if (format == DataFormat.CSV) {
            // Every column with its type, parts named like data files of sql scripts, each with the header
            CsvOptions options = CsvOptions.fromMap(manifest.getOptions());
            if (options.isHeader() && options.isUtf8()) {
                assertEquals(emp.getColumns().size(), countColumns(new File(dir, empFiles.get(0))));
            }
            assertTrue(empFiles.size() > 1, "emp is split: " + empFiles);
            assertTrue(empFiles.get(1).endsWith(source.getPrefix().toLowerCase() + "emp_2.csv")
                    || empFiles.get(1).toLowerCase().endsWith("emp_2.csv"), empFiles.toString());
            // Parts are split as UTF-8, UTF-16 doubles their size
            for (String path : empFiles) {
                assertTrue(new File(dir, path).length() <= (CSV_PART_SIZE + 4096) * 2, "Size of " + path);
            }
        }

        try (Connection connection = target.getTargetConnection()) {
            AbstractFileImporter importer = AbstractFileImporter.forDirectory(dir, connection, target.getDbType());
            assertEquals(format == DataFormat.CSV ? CsvImporter.class : ParquetImporter.class, importer.getClass());
            listener = new CollectingListener();
            importer.setExportListener(listener);
            try {
                importer.importDirectory(dir);
            } catch (Exception e) {
                throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
            }
            assertEquals(List.of(), listener.getErrors());
            assertTrue(listener.isCompleted(), "Import progress 100%");
            new ItVerifier(source, target, connection).withInstantEquality().verifyAll();
        }
    }

    /**
     * Fields of the header row of a CSV file
     */
    private static int countColumns(File csvFile) throws Exception {
        String header = Files.readAllLines(csvFile.toPath()).get(0);
        for (String delimiter : CsvExporter.DELIMITERS) {
            if (header.contains(delimiter)) {
                return header.split(Pattern.quote(delimiter), -1).length;
            }
        }
        return 1;
    }

    /**
     * Parquet files are loaded into a new table (created from the Parquet schema) and appended to it
     */
    @Test
    void loadParquetFiles() throws Exception {
        loadFiles(DataFormat.PARQUET);
    }

    /**
     * CSV files are loaded into a new table (delimiter and types detected) and appended to it, parts in order
     */
    @Test
    void loadCsvFiles() throws Exception {
        loadFiles(DataFormat.CSV);
    }

    private void loadFiles(DataFormat format) throws Exception {
        ItDatabase target = ItDatabase.H2;
        assumeTrue(source.isAvailable() && target.isAvailable());
        source.loadFixture();
        File dir = getExportDirectory(format.name().toLowerCase() + "-files");
        DataFileExporter exporter = newExporter(dir, target, format);
        exporter.export(ExportMode.DATA);
        ExportManifest manifest = ExportManifest.read(dir);
        assertEquals(ExportMode.DATA, manifest.getExportMode());
        ExportManifest.TableEntry emp = manifest.getTables().stream()
                .filter(table -> table.getName().equalsIgnoreCase(source.getPrefix() + "emp")).findFirst()
                .orElseThrow();
        List<File> files = new ArrayList<>();
        for (String path : emp.getDataFiles()) {
            files.add(new File(dir, path));
        }
        assertTrue(files.get(0).getParentFile().getName().equals(ScriptExportHandler.DATA_DIR_NAME));
        // The parts are given in any order, they are imported in the order of their numbers
        Collections.reverse(files);

        String tableName = source.getPrefix() + (format == DataFormat.CSV ? "csv_emp" : "pq_emp");
        try (Connection connection = target.getTargetConnection()) {
            DataFileImporter importer;
            if (format == DataFormat.CSV) {
                // The options of the files are chosen by the user, the delimiter is detected
                CsvImporter csvImporter = new CsvImporter(connection, target.getDbType());
                CsvOptions options = CsvOptions.fromMap(manifest.getOptions());
                options.setDelimiter("");
                csvImporter.setOptions(options);
                importer = csvImporter;
            } else {
                importer = new ParquetImporter(connection, target.getDbType());
            }
            importer.setTargetSchemaName(target.getTargetSchema());
            CollectingListener listener = new CollectingListener();
            importer.setExportListener(listener);
            assertEquals(ItDatabase.EMP_COUNT, importer.importFiles(files, tableName,
                    DataFileImporter.Mode.CREATE));
            assertEquals(List.of(), listener.getErrors());
            assertTrue(listener.isCompleted(), "Import progress 100%");
            // Rows are appended to the existing table
            listener = new CollectingListener();
            importer.setExportListener(listener);
            assertEquals(ItDatabase.EMP_COUNT, importer.importFiles(files, tableName,
                    DataFileImporter.Mode.APPEND));
            assertEquals(List.of(), listener.getErrors());
            assertTrue(listener.isCompleted(), "Append progress 100%");
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM \"" + target.getTargetSchema()
                         + "\".\"" + tableName.toUpperCase() + "\"")) {
                rs.next();
                assertEquals(ItDatabase.EMP_COUNT * 2, rs.getLong(1));
            }
            if (format == DataFormat.CSV && files.size() > 1) {
                // Rows of the parts keep the order of the source: the first part holds the first rows
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT * FROM \"" + target.getTargetSchema() + "\".\""
                             + tableName.toUpperCase() + "\" LIMIT 1")) {
                    assertTrue(rs.next());
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE \"" + target.getTargetSchema() + "\".\"" + tableName.toUpperCase()
                        + "\"");
            }
        }
    }
}
