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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * @Description: ParquetExporter exports tables as an export package with Parquet files: schema.sql and
 *               constraints.sql for the target database (the same as SQL packages), data/&lt;table&gt;.parquet for
 *               rows and manifest.json. Values which Parquet can not hold exactly are kept as text.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class ParquetExporter extends DataFileExporter {

    public static final String PARQUET_EXTENSION = ".parquet";
    public static final String DEFAULT_COMPRESSION = "ZSTD";
    public static final String[] COMPRESSIONS = {"ZSTD", "SNAPPY", "GZIP", "UNCOMPRESSED"};

    /**
     * Parts are written a little smaller than the maximum size, the size of compressed rows varies
     */
    private static final double PART_FILL = 0.9;

    private String compression = DEFAULT_COMPRESSION;
    private long maxFileSize;

    public ParquetExporter(File root) {
        super(root);
    }

    /**
     * Maximum size of a Parquet file in bytes, 0 for no limit. Larger tables are split into parts of about this
     * size like data files of sql scripts: orders.parquet, orders_2.parquet ... each part holds the next rows in
     * the order of the table.
     */
    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = Math.max(0, maxFileSize);
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    /**
     * Compression of Parquet files: ZSTD (default), SNAPPY, GZIP or UNCOMPRESSED
     */
    public void setCompression(String compression) {
        this.compression = StringUtils.defaultIfBlank(compression, DEFAULT_COMPRESSION).toUpperCase();
    }

    public String getCompression() {
        return compression;
    }

    @Override
    protected DataFormat getDataFormat() {
        return DataFormat.PARQUET;
    }

    @Override
    protected String getExtension() {
        return PARQUET_EXTENSION;
    }

    @Override
    protected ExportManifest.FileType getFileType() {
        return ExportManifest.FileType.PARQUET;
    }

    @Override
    protected List<File> writeFile(DuckDBWorkspace workspace, String schema, String table, File file,
                                   Map<String, String> metadata) throws Exception {
        workspace.writeParquet(schema, table, file, compression, metadata);
        if (maxFileSize <= 0 || file.length() <= maxFileSize) {
            return Collections.singletonList(file);
        }
        // The size of the whole table decides the number of parts, rows are divided evenly
        long rows = workspace.countRows(schema, table);
        int parts = (int) Math.min(Math.max(rows, 1), (long) Math.ceil(file.length() / (maxFileSize * PART_FILL)));
        long rowsPerPart = (rows + parts - 1) / parts;
        String baseName = FilenameUtils.getBaseName(file.getName());
        List<File> files = new ArrayList<>();
        for (int i = 0; i < parts && (long) i * rowsPerPart < rows; i++) {
            File part = i == 0 ? file : new File(file.getParentFile(), baseName + "_" + (i + 1) + PARQUET_EXTENSION);
            workspace.writeParquet(schema, table, part, compression, metadata, i * rowsPerPart, rowsPerPart);
            files.add(part);
        }
        return files;
    }

    @Override
    protected void putOptions(Map<String, Object> options) {
        options.put("compression", compression);
        options.put("maxFileSize", maxFileSize);
    }
}
