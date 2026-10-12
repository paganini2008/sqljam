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
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * @Description: CsvImporter imports CSV files with a header row into a database by a connection: packages exported
 *               by {@link CsvExporter}, whose values are read with the column types of manifest.json, or CSV files
 *               of other tools, whose delimiter and column types are detected, into a new table or appended to an
 *               existing table
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class CsvImporter extends DataFileImporter {

    /**
     * Options of the files, null to detect them (files of other tools)
     */
    private CsvOptions options;

    public CsvImporter(Connection connection, DbType dbType) {
        super(connection, dbType);
    }

    /**
     * Delimiter of the fields, detected from the files by default
     */
    public void setDelimiter(String delimiter) {
        if (options == null) {
            options = new CsvOptions();
        }
        options.setDelimiter(delimiter);
    }

    /**
     * Options of files of other tools, an empty delimiter is detected
     */
    public void setOptions(CsvOptions options) {
        this.options = options;
    }

    @Override
    protected DataFormat getDataFormat() {
        return DataFormat.CSV;
    }

    @Override
    protected ExportManifest.FileType getFileType() {
        return ExportManifest.FileType.CSV;
    }

    /**
     * Rows of the files read with their options, e.g. an encoding or no header
     */
    @Override
    protected long countRows(DuckDBWorkspace workspace, List<File> files) throws SQLException, IOException {
        return workspace.countFileRows(files, options);
    }

    @Override
    protected void prepare(ExportManifest manifest) {
        options = CsvOptions.fromMap(manifest.getOptions());
    }

    @Override
    protected void load(DuckDBWorkspace workspace, String schema, String table, List<File> files,
                        List<String> identityColumns, ExportManifest.TableEntry tableEntry) throws SQLException,
            IOException {
        workspace.loadCsv(schema, table, files, tableEntry != null ? tableEntry.getColumns() : null, identityColumns,
                options);
    }
}
