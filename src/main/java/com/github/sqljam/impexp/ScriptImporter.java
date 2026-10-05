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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ScriptImporter imports a directory exported by {@link ScriptExporter} into a database: schema.sql,
 *               data files (data.sql or data/*.sql and their parts), LOB files of lob-manifest.json and at last
 *               constraints.sql.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class ScriptImporter {

    private static final Pattern PART_FILE = Pattern.compile("^(.*?)(?:_(\\d+))?\\.sql$");

    private final Connection connection;
    private final DbType dbType;
    private boolean stopOnError = true;
    private int batchSize = SqlScriptRunner.DEFAULT_BATCH_SIZE;
    private ExportListener exportListener = ExportListener.NONE;
    private int executedCount;
    private int failedCount;
    private int lobCount;
    /**
     * Overall progress in bytes of sql files and LOB files
     */
    private long totalBytes;
    private long completedBytes;

    public ScriptImporter(Connection connection, DbType dbType) {
        this.connection = connection;
        this.dbType = dbType;
    }

    public void setStopOnError(boolean stopOnError) {
        this.stopOnError = stopOnError;
    }

    /**
     * INSERT statements per batch, 1 or less executes statement by statement
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setExportListener(ExportListener exportListener) {
        this.exportListener = exportListener != null ? exportListener : ExportListener.NONE;
    }

    public int getExecutedCount() {
        return executedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public int getLobCount() {
        return lobCount;
    }

    /**
     * Imports an export directory. Files are executed in the order of manifest.json if it exists, otherwise they are
     * discovered by the directory layout.
     */
    public void importDirectory(File dir) throws IOException, SQLException {
        ExportManifest manifest = ExportManifest.read(dir);
        if (manifest != null) {
            boolean successful = false;
            try {
                importManifest(dir, manifest);
                successful = true;
            } finally {
                exportListener.onEnd(successful);
            }
            return;
        }
        List<File> units = new ArrayList<>();
        if (isScriptDirectory(dir)) {
            units.add(dir);
        } else {
            File[] subDirs = dir.listFiles(File::isDirectory);
            if (subDirs != null) {
                Arrays.stream(subDirs).filter(ScriptImporter::isScriptDirectory).sorted().forEach(units::add);
            }
        }
        if (units.isEmpty()) {
            throw new ImpExpException("No sql scripts found in directory: " + dir);
        }
        long bytes = 0;
        for (File unit : units) {
            bytes += sizeOf(new File(unit, ScriptExportHandler.SCHEMA_FILE_NAME))
                    + getDataFiles(unit).stream().mapToLong(File::length).sum()
                    + sizeOf(new File(unit, LobManifestWriter.LOB_DIR_NAME))
                    + sizeOf(new File(unit, ScriptExportHandler.CONSTRAINT_FILE_NAME));
        }
        startProgress(bytes);
        boolean successful = false;
        try {
            for (File unit : units) {
                importUnit(unit);
            }
            successful = true;
        } finally {
            exportListener.onEnd(successful);
        }
    }

    /**
     * Validates the manifest and executes its files in order
     */
    private static long sizeOf(File file) {
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

    private void startProgress(long totalBytes) {
        this.totalBytes = Math.max(totalBytes, 1);
        this.completedBytes = 0;
        exportListener.onProgress(0, this.totalBytes);
    }

    private void completeBytes(long bytes) {
        completedBytes += bytes;
        exportListener.onProgress(Math.min(completedBytes, totalBytes), totalBytes);
    }

    private void importManifest(File dir, ExportManifest manifest) throws IOException, SQLException {
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
        long bytes = 0;
        for (ExportManifest.FileEntry fileEntry : manifest.getFiles()) {
            File file = new File(dir, fileEntry.getPath());
            bytes += fileEntry.getType() == ExportManifest.FileType.LOB_MANIFEST
                    ? sizeOf(new File(file.getParentFile(), LobManifestWriter.LOB_DIR_NAME)) : file.length();
        }
        startProgress(bytes);
        for (ExportManifest.FileEntry fileEntry : manifest.getFiles()) {
            File file = new File(dir, fileEntry.getPath());
            if (fileEntry.getType() == ExportManifest.FileType.LOB_MANIFEST) {
                restoreLobs(file.getParentFile(), file);
            } else {
                runScript(file);
            }
        }
    }

    static boolean isScriptDirectory(File dir) {
        return new File(dir, ScriptExportHandler.SCHEMA_FILE_NAME).exists()
                || new File(dir, ScriptExportHandler.DATA_FILE_NAME).exists()
                || new File(dir, ScriptExportHandler.DATA_DIR_NAME).isDirectory();
    }

    private void importUnit(File dir) throws IOException, SQLException {
        runScript(new File(dir, ScriptExportHandler.SCHEMA_FILE_NAME));
        for (File file : getDataFiles(dir)) {
            runScript(file);
        }
        File manifest = new File(dir, LobManifestWriter.MANIFEST_FILE_NAME);
        if (manifest.exists()) {
            restoreLobs(dir, manifest);
        }
        runScript(new File(dir, ScriptExportHandler.CONSTRAINT_FILE_NAME));
    }

    /**
     * Data files in order: data.sql, data_2.sql, ... and data/table.sql, data/table_2.sql, ...
     */
    public static List<File> getDataFiles(File dir) {
        List<File> files = new ArrayList<>();
        File[] singleFiles = dir.listFiles(file -> file.isFile() && file.getName().matches("data(_\\d+)?\\.sql"));
        if (singleFiles != null) {
            files.addAll(sortParts(Arrays.asList(singleFiles)));
        }
        File dataDir = new File(dir, ScriptExportHandler.DATA_DIR_NAME);
        File[] tableFiles = dataDir.listFiles(file -> file.isFile() && file.getName().endsWith(".sql"));
        if (tableFiles != null) {
            files.addAll(sortParts(Arrays.asList(tableFiles)));
        }
        return files;
    }

    private static List<File> sortParts(List<File> files) {
        return files.stream().sorted(Comparator.comparing((File file) -> partOf(file)[0])
                .thenComparing(file -> partOf(file)[1].isEmpty() ? 0 : Integer.parseInt(partOf(file)[1])))
                .collect(Collectors.toList());
    }

    private static String[] partOf(File file) {
        Matcher matcher = PART_FILE.matcher(file.getName());
        if (matcher.matches()) {
            return new String[]{matcher.group(1), StringUtils.defaultString(matcher.group(2))};
        }
        return new String[]{file.getName(), ""};
    }

    private void runScript(File file) throws IOException, SQLException {
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
                // Progress of statements in the file is converted to bytes
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
     * Restores LOB values by primary keys: UPDATE table SET column = ? WHERE key = ?
     */
    private void restoreLobs(File dir, File manifest) throws IOException, SQLException {
        ObjectMapper objectMapper = new ObjectMapper();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (JsonParser parser = objectMapper.getFactory().createParser(manifest)) {
            Dialect dialect = createDialect(objectMapper, manifest);
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && "tables".equals(parser.currentName())) {
                    parser.nextToken();
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        restoreTable(dir, parser, objectMapper, dialect);
                    }
                }
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        exportListener.onMessage(String.format("%d LOB values restored", lobCount));
    }

    private Dialect createDialect(ObjectMapper objectMapper, File manifest) throws IOException {
        DbType type = dbType;
        if (type == null) {
            JsonNode root = objectMapper.readTree(manifest);
            type = DbType.forName(root.path("dbType").asText());
        }
        if (type == null) {
            throw new ImpExpException("Unknown database type of LOB manifest: " + manifest);
        }
        return type.createDialect();
    }

    private void restoreTable(File dir, JsonParser parser, ObjectMapper objectMapper, Dialect dialect)
            throws IOException, SQLException {
        String schemaName = null;
        String tableName = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String fieldName = parser.currentName();
            parser.nextToken();
            if ("schema".equals(fieldName)) {
                schemaName = parser.getValueAsString();
            } else if ("table".equals(fieldName)) {
                tableName = parser.getValueAsString();
            } else if ("rows".equals(fieldName)) {
                String tableRef = (StringUtils.isNotBlank(schemaName) ? dialect.quoteIdentifier(schemaName) + "."
                        : "") + dialect.quoteIdentifier(tableName);
                while (parser.nextToken() == JsonToken.START_OBJECT) {
                    JsonNode row = objectMapper.readTree(parser);
                    restoreRow(dir, dialect, tableRef, row);
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private void restoreRow(File dir, Dialect dialect, String tableRef, JsonNode row) throws IOException,
            SQLException {
        if (exportListener.isCancelled()) {
            throw new ExportCancelledException();
        }
        JsonNode key = row.path("key");
        List<String> keyNames = new ArrayList<>();
        key.fieldNames().forEachRemaining(keyNames::add);
        String where = keyNames.stream().map(name -> dialect.quoteIdentifier(name) + " = ?")
                .collect(Collectors.joining(" AND "));
        Iterator<Map.Entry<String, JsonNode>> columns = row.path("columns").fields();
        while (columns.hasNext()) {
            Map.Entry<String, JsonNode> column = columns.next();
            File file = new File(dir, column.getValue().path("file").asText());
            boolean binary = LobManifestWriter.BLOB.equals(column.getValue().path("type").asText());
            String sql = String.format("UPDATE %s SET %s = ? WHERE %s", tableRef,
                    dialect.quoteIdentifier(column.getKey()), where);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                completeBytes(bytes.length);
                if (binary) {
                    ps.setBytes(1, bytes);
                } else {
                    ps.setString(1, new String(bytes, StandardCharsets.UTF_8));
                }
                bindKey(ps, key, keyNames);
                ps.executeUpdate();
            } catch (SQLException e) {
                failedCount++;
                String message = String.format("Unable to restore LOB %s of %s: %s", file.getName(), tableRef,
                        e.getMessage());
                exportListener.onError(message, e);
                if (stopOnError) {
                    throw e;
                }
            }
            lobCount++;
        }
    }

    private static void bindKey(PreparedStatement ps, JsonNode key, List<String> keyNames) throws SQLException {
        int index = 2;
        for (String keyName : keyNames) {
            JsonNode value = key.get(keyName);
            if (value == null || value.isNull()) {
                ps.setNull(index++, java.sql.Types.VARCHAR);
            } else if (value.isIntegralNumber()) {
                ps.setLong(index++, value.asLong());
            } else if (value.isNumber()) {
                ps.setBigDecimal(index++, new BigDecimal(value.asText()));
            } else if (value.isBoolean()) {
                ps.setBoolean(index++, value.asBoolean());
            } else {
                ps.setString(index++, value.asText());
            }
        }
    }

    /**
     * Forwards errors of scripts, the end of each script is not the end of the import
     */
    private static class ForwardingListener implements ExportListener {


        private final ExportListener delegate;

        ForwardingListener(ExportListener delegate) {
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
