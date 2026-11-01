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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.sqljam.utils.ExcelUtils;
import com.github.sqljam.impexp.DataFileExporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DuckDBWorkspace;
import com.github.sqljam.impexp.ExportManifest;

/**
 * @Description: ExcelExporter exports tables as an export package with Excel workbooks: schema.sql and
 *               constraints.sql for the target database (the same as SQL packages), data/&lt;table&gt;.xlsx with one
 *               sheet and a header row for rows, and manifest.json with the type of each column, so that
 *               {@link ExcelImporter} reads the values back exactly. A table of more rows than a sheet holds is
 *               split into orders.xlsx, orders_2.xlsx ...
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class ExcelExporter extends DataFileExporter {

    /**
     * Types which are cells of their own types, others are written as their text of DuckDB, e.g. binary values,
     * times, intervals and timestamps with time zones
     */
    private static final Set<String> CELL_TYPES = Set.of("BOOLEAN", "TINYINT", "SMALLINT", "INTEGER", "BIGINT",
            "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "HUGEINT", "UHUGEINT", "FLOAT", "DOUBLE", "DATE",
            "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS");

    private int maxRows = ExcelUtils.MAX_ROWS;

    public ExcelExporter(File root) {
        super(root);
    }

    /**
     * Rows of a workbook, the rows of a sheet (1,048,575 without the header) at most
     */
    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows > 0 ? Math.min(maxRows, ExcelUtils.MAX_ROWS) : ExcelUtils.MAX_ROWS;
    }

    public int getMaxRows() {
        return maxRows;
    }

    @Override
    protected DataFormat getDataFormat() {
        return DataFormat.EXCEL;
    }

    @Override
    protected String getExtension() {
        return ExcelUtils.EXCEL_EXTENSION;
    }

    @Override
    protected ExportManifest.FileType getFileType() {
        return ExportManifest.FileType.EXCEL;
    }

    @Override
    protected List<File> writeFile(DuckDBWorkspace workspace, String schema, String table, File file,
                                   Map<String, String> metadata) throws Exception {
        List<String[]> columns = workspace.describeTable(schema, table);
        String select = columns.stream().map(column -> isCellType(column[1]) ? DuckDBWorkspace.quote(column[0])
                : String.format("CAST(%s AS VARCHAR)", DuckDBWorkspace.quote(column[0])))
                .collect(Collectors.joining(", "));
        try (Statement statement = workspace.getConnection().createStatement();
             ResultSet rs = statement.executeQuery(String.format("SELECT %s FROM %s", select,
                     DuckDBWorkspace.qualify(schema, table)))) {
            return ExcelUtils.write(rs, columns.stream().map(column -> column[0]).collect(Collectors.toList()),
                    table, file, maxRows);
        }
    }

    static boolean isCellType(String duckdbType) {
        String type = duckdbType.toUpperCase(Locale.ENGLISH);
        return CELL_TYPES.contains(type) || type.startsWith("DECIMAL");
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
        options.put("maxRows", maxRows);
    }
}
