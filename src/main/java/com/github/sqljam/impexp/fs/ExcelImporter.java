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

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.sqljam.utils.ExcelUtils;
import com.github.sqljam.impexp.DataFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.DuckDBWorkspace;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.ImpExpException;

/**
 * @Description: ExcelImporter imports Excel workbooks (.xlsx) into a database by a connection: packages exported by
 *               {@link ExcelExporter}, whose values are read with the column types of manifest.json, or workbooks of
 *               other tools without manifest.json, whose first row names the columns and whose column types are
 *               detected, into a new table or appended to an existing table. A sheet is read as a CSV file in the
 *               workspace, then it is loaded like the CSV files of other tools.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class ExcelImporter extends DataFileImporter {

    /**
     * Sheet of workbooks of other tools, null for the first sheet
     */
    private String sheet;
    /**
     * CSV files of the sheets in the workspace, read once for counting and loading
     */
    private final Map<String, File> csvFiles = new HashMap<>();

    public ExcelImporter(Connection connection, DbType dbType) {
        super(connection, dbType);
    }

    /**
     * Sheet of the workbooks, the first sheet if null
     */
    public void setSheet(String sheet) {
        this.sheet = sheet;
    }

    public String getSheet() {
        return sheet;
    }

    @Override
    protected DataFormat getDataFormat() {
        return DataFormat.EXCEL;
    }

    @Override
    protected ExportManifest.FileType getFileType() {
        return ExportManifest.FileType.EXCEL;
    }

    @Override
    protected long countRows(DuckDBWorkspace workspace, List<File> files) throws SQLException, IOException {
        return workspace.countFileRows(toCsv(workspace, files), new CsvOptions());
    }

    @Override
    protected void prepare(ExportManifest manifest) {
        // A package has one sheet in each workbook
        sheet = null;
    }

    @Override
    protected void load(DuckDBWorkspace workspace, String schema, String table, List<File> files,
                        List<String> identityColumns, ExportManifest.TableEntry tableEntry) throws SQLException,
            IOException {
        workspace.loadCsv(schema, table, toCsv(workspace, files), tableEntry != null ? tableEntry.getColumns() : null,
                identityColumns, new CsvOptions());
    }

    /**
     * CSV files of the sheets of the workbooks in the directory of the workspace, deleted with it. They have the
     * default options of CSV files: comma, double quotes, a header row, NULL is an empty field
     */
    private List<File> toCsv(DuckDBWorkspace workspace, List<File> files) throws IOException {
        File directory = workspace.getFile().getParentFile();
        List<File> converted = new ArrayList<>();
        for (File file : files) {
            String key = directory.getPath() + File.pathSeparator + file.getAbsolutePath();
            File csv = csvFiles.get(key);
            if (csv == null) {
                csv = new File(directory, "excel-" + (csvFiles.size() + 1) + ".csv");
                try {
                    ExcelUtils.toCsv(file, sheet, csv);
                } catch (IllegalArgumentException e) {
                    throw new ImpExpException(e.getMessage(), e);
                }
                csvFiles.put(key, csv);
            }
            converted.add(csv);
        }
        return converted;
    }
}
