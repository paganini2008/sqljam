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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

/**
 * @Description: LobManifestWriter writes LOB values of exported rows into separated files and records them in
 *               lob-manifest.json, so that data.sql keeps small and LOBs are restored by primary keys after rows
 *               inserted.
 *
 *               <pre>
 *               export/
 *               ├── schema.sql
 *               ├── data.sql
 *               ├── lob/
 *               │   └── document/
 *               │       ├── 000001_content.clob
 *               │       └── 000001_attachment.blob
 *               └── lob-manifest.json
 *
 *               {
 *                 "version": 1,
 *                 "dbType": "ORACLE",
 *                 "tables": [
 *                   {
 *                     "table": "document",
 *                     "rows": [
 *                       {
 *                         "key": { "id": 1001 },
 *                         "columns": {
 *                           "content": { "type": "CLOB", "file": "lob/document/000001_content.clob" },
 *                           "attachment": { "type": "BLOB", "file": "lob/document/000001_attachment.blob" }
 *                         }
 *                       }
 *                     ]
 *                   }
 *                 ]
 *               }
 *               </pre>
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class LobManifestWriter implements Closeable {

    public static final String MANIFEST_FILE_NAME = "lob-manifest.json";
    public static final String LOB_DIR_NAME = "lob";
    public static final String CLOB = "CLOB";
    public static final String BLOB = "BLOB";

    private final File root;
    private final JsonGenerator generator;
    private String currentSchema;
    private String currentTable;
    private int fileCount;

    public LobManifestWriter(File root, DbType targetDbType) throws IOException {
        this.root = root;
        FileUtils.forceMkdir(root);
        this.generator = new JsonFactory().createGenerator(new File(root, MANIFEST_FILE_NAME), JsonEncoding.UTF8);
        generator.useDefaultPrettyPrinter();
        generator.writeStartObject();
        generator.writeNumberField("version", 1);
        generator.writeStringField("dbType", targetDbType != null ? targetDbType.name() : null);
        generator.writeStringField("createdAt", LocalDateTime.now().toString());
        generator.writeArrayFieldStart("tables");
    }

    /**
     * Writes LOB values of a row into files and records them
     *
     * @param schemaName target schema name, may be null
     * @param tableName  target table name
     * @param key        primary key values of the row (target column name -> value)
     * @param lobValues  LOB values of the row (target column name -> String or byte[])
     * @param rowNumber  row number of the table (1-based), used in file names
     */
    public synchronized void writeRow(String schemaName, String tableName, Map<String, Object> key,
                                      Map<String, Object> lobValues, long rowNumber) throws IOException {
        if (lobValues.isEmpty()) {
            return;
        }
        if (!Objects.equals(currentTable, tableName) || !Objects.equals(currentSchema, schemaName)) {
            endTable();
            generator.writeStartObject();
            if (StringUtils.isNotBlank(schemaName)) {
                generator.writeStringField("schema", schemaName);
            }
            generator.writeStringField("table", tableName);
            generator.writeArrayFieldStart("rows");
            currentSchema = schemaName;
            currentTable = tableName;
        }
        generator.writeStartObject();
        generator.writeObjectFieldStart("key");
        for (Map.Entry<String, Object> entry : key.entrySet()) {
            writeValue(entry.getKey(), entry.getValue());
        }
        generator.writeEndObject();
        generator.writeObjectFieldStart("columns");
        for (Map.Entry<String, Object> entry : lobValues.entrySet()) {
            Object value = entry.getValue();
            boolean binary = value instanceof byte[];
            String type = binary ? BLOB : CLOB;
            String path = String.format("%s/%s/%06d_%s.%s", LOB_DIR_NAME, toFileName(tableName), rowNumber,
                    toFileName(entry.getKey()), type.toLowerCase());
            File file = new File(root, path);
            if (binary) {
                FileUtils.writeByteArrayToFile(file, (byte[]) value);
            } else {
                FileUtils.writeStringToFile(file, value.toString(), StandardCharsets.UTF_8);
            }
            fileCount++;
            generator.writeObjectFieldStart(entry.getKey());
            generator.writeStringField("type", type);
            generator.writeStringField("file", path);
            generator.writeEndObject();
        }
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private void endTable() throws IOException {
        if (currentTable != null) {
            generator.writeEndArray();
            generator.writeEndObject();
            currentTable = null;
            currentSchema = null;
        }
    }

    private void writeValue(String fieldName, Object value) throws IOException {
        if (value == null) {
            generator.writeNullField(fieldName);
        } else if (value instanceof BigDecimal) {
            generator.writeNumberField(fieldName, (BigDecimal) value);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            generator.writeNumberField(fieldName, ((Number) value).longValue());
        } else if (value instanceof Number) {
            generator.writeNumberField(fieldName, new BigDecimal(value.toString()));
        } else if (value instanceof Boolean) {
            generator.writeBooleanField(fieldName, (Boolean) value);
        } else {
            generator.writeStringField(fieldName, value.toString());
        }
    }

    static String toFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    public int getFileCount() {
        return fileCount;
    }

    @Override
    public synchronized void close() throws IOException {
        endTable();
        generator.writeEndArray();
        generator.writeNumberField("fileCount", fileCount);
        generator.writeEndObject();
        generator.close();
    }
}
