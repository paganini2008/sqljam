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

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: AbstractFileImporter imports files of an export package into a database by a connection:
 *               manifest.json is validated (format, status, target database type and checksums of files), files are
 *               imported in its order with the overall progress in bytes. {@link ScriptImporter} imports packages
 *               of sql scripts, {@link ParquetImporter} packages of Parquet files and Parquet files of other tools.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public abstract class AbstractFileImporter {

    protected final Connection connection;
    protected final DbType dbType;
    protected boolean stopOnError = true;
    protected int batchSize = SqlScriptRunner.DEFAULT_BATCH_SIZE;
    protected ExportListener exportListener = ExportListener.NONE;
    protected File workspaceDirectory;
    protected int executedCount;
    protected int failedCount;
    /**
     * Overall progress in bytes of imported files
     */
    protected long totalBytes;
    protected long completedBytes;

    protected AbstractFileImporter(Connection connection, DbType dbType) {
        this.connection = connection;
        this.dbType = dbType;
    }

    /**
     * Importer of an export directory by the data format of its manifest: Parquet packages are imported by
     * {@link ParquetImporter}, sql scripts (with or without manifest) by {@link ScriptImporter}
     */
    public static AbstractFileImporter forDirectory(File dir, Connection connection, DbType dbType)
            throws IOException {
        ExportManifest manifest = ExportManifest.read(dir);
        if (manifest != null && manifest.getDataFormat() == DataFormat.PARQUET) {
            return new ParquetImporter(connection, dbType);
        }
        return new ScriptImporter(connection, dbType);
    }

    public void setStopOnError(boolean stopOnError) {
        this.stopOnError = stopOnError;
    }

    /**
     * INSERT statements per batch of sql scripts, 1 or less executes statement by statement
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setExportListener(ExportListener exportListener) {
        this.exportListener = exportListener != null ? exportListener : ExportListener.NONE;
    }

    /**
     * Directory of temporary files (the DuckDB workspace of Parquet files), the system temporary directory by default
     */
    public void setWorkspaceDirectory(File workspaceDirectory) {
        this.workspaceDirectory = workspaceDirectory;
    }

    /**
     * Executed sql statements and imported rows of Parquet files
     */
    public int getExecutedCount() {
        return executedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    /**
     * Restored LOB values, LOBs of Parquet packages are inside Parquet files
     */
    public int getLobCount() {
        return 0;
    }

    /**
     * Imports an export directory. Files are imported in the order of manifest.json if it exists.
     */
    public void importDirectory(File dir) throws IOException, SQLException {
        ExportManifest manifest = ExportManifest.read(dir);
        if (manifest == null) {
            importDirectoryWithoutManifest(dir);
            return;
        }
        boolean successful = false;
        try {
            validateManifest(dir, manifest);
            startProgress(getManifestBytes(dir, manifest));
            importManifest(dir, manifest);
            successful = true;
        } finally {
            exportListener.onEnd(successful);
        }
    }

    /**
     * Imports files listed by the manifest in order, the manifest is validated already
     */
    protected abstract void importManifest(File dir, ExportManifest manifest) throws IOException, SQLException;

    /**
     * Imports a directory without manifest.json, discovered by the directory layout
     */
    protected void importDirectoryWithoutManifest(File dir) throws IOException, SQLException {
        throw new ImpExpException("Manifest not found: " + new File(dir, ExportManifest.FILE_NAME));
    }

    /**
     * Format, status, target database type and files of the manifest
     */
    protected void validateManifest(File dir, ExportManifest manifest) throws IOException {
        if (!ExportManifest.FORMAT.equals(manifest.getFormat())) {
            throw new ImpExpException("Unknown export format: " + manifest.getFormat());
        }
        if (manifest.getStatus() != ExportManifest.Status.COMPLETED) {
            throw new ImpExpException("The export is not completed: " + manifest.getStatus());
        }
        DbType targetDbType = manifest.getTarget().getDbType();
        if (dbType != null && targetDbType != null && dbType != targetDbType) {
            throw new ImpExpException(String.format("The scripts are generated for %s, but the target database is %s",
                    targetDbType.getDisplayName(), dbType.getDisplayName()));
        }
        for (ExportManifest.FileEntry fileEntry : manifest.getFiles()) {
            File file = new File(dir, fileEntry.getPath());
            if (!file.exists()) {
                throw new ImpExpException("File not found: " + fileEntry.getPath());
            }
            if (StringUtils.isNotBlank(fileEntry.getSha256()) && !fileEntry.getSha256().equals(
                    ExportManifest.sha256(file))) {
                throw new ImpExpException("File is modified or corrupted (checksum mismatch): " + fileEntry.getPath());
            }
        }
    }

    /**
     * Bytes of the files of the manifest, the total of the progress
     */
    protected long getManifestBytes(File dir, ExportManifest manifest) {
        long bytes = 0;
        for (ExportManifest.FileEntry fileEntry : manifest.getFiles()) {
            bytes += new File(dir, fileEntry.getPath()).length();
        }
        return bytes;
    }

    protected void startProgress(long totalBytes) {
        this.totalBytes = Math.max(totalBytes, 1);
        this.completedBytes = 0;
        exportListener.onProgress(0, this.totalBytes);
    }

    protected void completeBytes(long bytes) {
        completedBytes += bytes;
        exportListener.onProgress(Math.min(completedBytes, totalBytes), totalBytes);
    }

    protected static long sizeOf(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            long size = 0;
            if (files != null) {
                for (File child : files) {
                    size += sizeOf(child);
                }
            }
            return size;
        }
        return file.exists() ? file.length() : 0;
    }

    /**
     * Executes a sql script (schema.sql, data files, constraints.sql), progress of statements is converted to bytes
     */
    protected void runScript(File file) throws IOException, SQLException {
        if (!file.exists()) {
            return;
        }
        exportListener.onMessage("Executing script: " + file.getName());
        SqlScriptRunner runner = new SqlScriptRunner(connection);
        runner.setStopOnError(stopOnError);
        runner.setBatchSize(batchSize);
        long fileBytes = file.length();
        runner.setExportListener(new ForwardingListener(exportListener) {

            @Override
            public void onProgress(long processed, long total) {
                long bytes = total > 0 ? fileBytes * processed / total : 0;
                exportListener.onProgress(Math.min(completedBytes + bytes, totalBytes), totalBytes);
            }
        });
        try {
            runner.runScript(file);
        } finally {
            executedCount += runner.getExecutedCount();
            failedCount += runner.getFailedCount();
            completeBytes(fileBytes);
        }
    }

    /**
     * Forwards messages and errors of a part of the import, the end of each part is not the end of the import
     */
    protected static class ForwardingListener implements ExportListener {

        private final ExportListener delegate;

        protected ForwardingListener(ExportListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onMessage(String message) {
            delegate.onMessage(message);
        }

        @Override
        public void onError(String message, Throwable e) {
            delegate.onError(message, e);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }
}
