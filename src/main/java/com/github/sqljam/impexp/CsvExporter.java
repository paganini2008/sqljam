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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: CsvExporter exports tables as an export package with CSV files: schema.sql and constraints.sql for
 *               the target database (the same as SQL packages), data/&lt;table&gt;.csv with a header row for rows and
 *               manifest.json. CSV files have no types, manifest.json keeps the type of each column, so that
 *               {@link CsvImporter} reads the values back exactly. NULL is an empty field, an empty string is "".
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class CsvExporter extends DataFileExporter {

    public static final String CSV_EXTENSION = ".csv";
    /**
     * Delimiters offered by the user interface, tab separated files are opened by spreadsheets as well
     */
    public static final String[] DELIMITERS = {",", ";", "\t", "|"};

    private CsvOptions options = new CsvOptions();
    private long maxFileSize;

    public CsvExporter(File root) {
        super(root);
    }

    /**
     * Maximum size of a CSV file in bytes, 0 for no limit. Larger files are split like data files of sql scripts:
     * orders.csv, orders_2.csv, orders_3.csv ... each with the header row.
     */
    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = Math.max(0, maxFileSize);
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    /**
     * Delimiter of the fields, a comma by default
     */
    public void setDelimiter(String delimiter) {
        options.setDelimiter(StringUtils.defaultIfEmpty(delimiter, DuckDBWorkspace.DEFAULT_DELIMITER));
    }

    public String getDelimiter() {
        return options.getDelimiter();
    }

    /**
     * Delimiter, quote, text of NULL, header, encoding and date format of the files
     */
    public void setOptions(CsvOptions options) {
        this.options = options != null ? options : new CsvOptions();
    }

    public CsvOptions getOptions() {
        return options;
    }

    @Override
    protected DataFormat getDataFormat() {
        return DataFormat.CSV;
    }

    @Override
    protected String getExtension() {
        return CSV_EXTENSION;
    }

    @Override
    protected ExportManifest.FileType getFileType() {
        return ExportManifest.FileType.CSV;
    }

    @Override
    protected List<File> writeFile(DuckDBWorkspace workspace, String schema, String table, File file,
                                   Map<String, String> metadata) throws Exception {
        workspace.writeCsv(schema, table, file, options);
        // Parts are split as UTF-8 and converted to the encoding afterwards
        List<File> parts = CsvSplitter.split(file, maxFileSize, options.getQuote().charAt(0), options.isHeader());
        if (!options.isUtf8()) {
            for (File part : parts) {
                CsvSplitter.transcode(part, StandardCharsets.UTF_8, part, options.getCharset(), options.isBom());
            }
        }
        return parts;
    }

    @Override
    protected void describeTable(ExportManifest.TableEntry tableEntry, DuckDBWorkspace workspace, String schema,
                                 String table) throws Exception {
        tableEntry.getColumns().clear();
        for (String[] column : workspace.describeTable(schema, table)) {
            tableEntry.getColumns().add(new ExportManifest.ColumnEntry(column[0], column[1]));
        }
    }

    @Override
    protected void putOptions(Map<String, Object> options) {
        options.putAll(this.options.toMap());
        options.put("maxFileSize", maxFileSize);
    }
}
