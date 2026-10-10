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
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: ExportManifest describes an export directory (manifest.json), so that the directory can be imported
 *               as a whole: which files to execute in which order, source and target databases, options and tables.
 *               Export and import are paired operations.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Getter
@Setter
public class ExportManifest {

    public static final String FILE_NAME = "manifest.json";
    public static final String FORMAT = "sqljam-export";

    public enum Status {

        COMPLETED,

        FAILED,

        CANCELLED
    }

    public enum FileType {

        SCHEMA,

        DATA,

        LOB_MANIFEST,

        CONSTRAINTS,

        /**
         * Rows of a table in a Parquet file
         */
        PARQUET
    }

    private String format = FORMAT;
    private int version = 1;
    private Status status;
    private String createdAt = LocalDateTime.now().toString();
    private ExportMode exportMode;
    /**
     * Format of rows, SQL for packages written before Parquet was supported
     */
    private DataFormat dataFormat = DataFormat.SQL;
    private Database source = new Database();
    private Database target = new Database();
    private Map<String, Object> options = new LinkedHashMap<>();
    /**
     * Files in the order of importing
     */
    private List<FileEntry> files = new ArrayList<>();
    private List<TableEntry> tables = new ArrayList<>();

    @Getter
    @Setter
    public static class Database {

        private DbType dbType;
        private String product;
        private String version;
        private String catalog;
        private String schema;
        private List<String> catalogs = new ArrayList<>();
        private List<String> schemas = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class FileEntry {

        private String path;
        private FileType type;
        private long size;
        private String sha256;

        public FileEntry() {
        }

        public FileEntry(String path, FileType type) {
            this.path = path;
            this.type = type;
        }
    }

    @Getter
    @Setter
    public static class TableEntry {

        private String catalog;
        private String schema;
        private String name;
        private long rows;
        private Set<String> dataFiles = new LinkedHashSet<>();
        private long lobFiles;
        /**
         * Identity columns of the table (Parquet packages), their values are kept and sequences are reset
         */
        private List<String> identityColumns = new ArrayList<>();
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void write(File root) throws IOException {
        for (FileEntry fileEntry : files) {
            File file = new File(root, fileEntry.getPath());
            if (file.exists()) {
                fileEntry.setSize(file.length());
                fileEntry.setSha256(sha256(file));
            }
        }
        createObjectMapper().writeValue(new File(root, FILE_NAME), this);
    }

    public static ExportManifest read(File root) throws IOException {
        File file = new File(root, FILE_NAME);
        return file.exists() ? createObjectMapper().readValue(file, ExportManifest.class) : null;
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, length);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
