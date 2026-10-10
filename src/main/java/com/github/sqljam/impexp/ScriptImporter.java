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
import java.sql.Types;
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
import com.github.sqljam.jdbc.JdbcUtils;
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
public class ScriptImporter extends AbstractFileImporter {

    private static final Pattern PART_FILE = Pattern.compile("^(.*?)(?:_(\\d+))?\\.sql$");

    private int lobCount;

    public ScriptImporter(Connection connection, DbType dbType) {
        super(connection, dbType);
    }

    @Override
    public int getLobCount() {
        return lobCount;
    }

    /**
     * Directories without manifest.json are discovered by the layout: schema.sql, data files, lob/ and
     * constraints.sql of the directory or its sub directories (catalogs)
     */
    @Override
    protected void importDirectoryWithoutManifest(File dir) throws IOException, SQLException {
        File[] parquetFiles = new File(dir, "data").listFiles((parent, name) -> name.endsWith(
                ParquetExporter.PARQUET_EXTENSION));
        if (parquetFiles != null && parquetFiles.length > 0) {
            throw new ImpExpException("Parquet files without manifest are imported by ParquetImporter: " + dir);
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
     * LOB files are counted by the size of their directory
     */
    @Override
    protected long getManifestBytes(File dir, ExportManifest manifest) {
        long bytes = 0;
        for (ExportManifest.FileEntry fileEntry : manifest.getFiles()) {
            File file = new File(dir, fileEntry.getPath());
            bytes += fileEntry.getType() == ExportManifest.FileType.LOB_MANIFEST
                    ? sizeOf(new File(file.getParentFile(), LobManifestWriter.LOB_DIR_NAME)) : file.length();
        }
        return bytes;
    }

    @Override
    protected void importManifest(File dir, ExportManifest manifest) throws IOException, SQLException {
        if (manifest.getDataFormat() == DataFormat.PARQUET) {
            throw new ImpExpException("Packages of Parquet files are imported by ParquetImporter");
        }
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

    /**
     * Restores LOB values by primary keys: UPDATE table SET column = ? WHERE key = ?
     */
    private void restoreLobs(File dir, File manifest) throws IOException, SQLException {
        ObjectMapper objectMapper = new ObjectMapper();
        boolean autoCommit = connection.getAutoCommit();
        boolean transactional = JdbcUtils.beginTransaction(connection);
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
            if (transactional) {
                connection.commit();
            }
        } catch (SQLException | IOException | RuntimeException e) {
            if (transactional) {
                connection.rollback();
            }
            throw e;
        } finally {
            if (transactional) {
                connection.setAutoCommit(autoCommit);
            }
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
                ps.setNull(index++, Types.VARCHAR);
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
}
