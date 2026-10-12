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
 * @Description: ParquetImporter imports Parquet files into a database by a connection: packages exported by
 *               {@link ParquetExporter}, or Parquet files of other tools into a new table created from the Parquet
 *               schema or appended to an existing table
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ParquetImporter extends DataFileImporter {

    public ParquetImporter(Connection connection, DbType dbType) {
        super(connection, dbType);
    }

    @Override
    protected DataFormat getDataFormat() {
        return DataFormat.PARQUET;
    }

    @Override
    protected ExportManifest.FileType getFileType() {
        return ExportManifest.FileType.PARQUET;
    }

    @Override
    protected void load(DuckDBWorkspace workspace, String schema, String table, List<File> files,
                        List<String> identityColumns, ExportManifest.TableEntry tableEntry) throws SQLException,
            IOException {
        workspace.loadParquet(schema, table, files, identityColumns);
    }
}
