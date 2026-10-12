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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;

/**
 * @Description: CsvSplitter splits a large CSV file into parts of a maximum size, named like data files of sql
 *               scripts: orders.csv, orders_2.csv, orders_3.csv ... Every part starts with the header row and ends
 *               with a complete record, a line break inside a quoted field does not end a record. Quotes and line
 *               breaks are single bytes in UTF-8, so the file is split as bytes.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class CsvSplitter {

    /**
     * Order of importing: the file itself, then its parts by number (orders.csv, orders_2.csv ... orders_10.csv),
     * files of different names by name
     */
    public static final Comparator<File> PART_ORDER = Comparator.comparing((File file) -> baseOf(file))
            .thenComparingInt(CsvSplitter::partOf).thenComparing(File::getName);
    private static final Pattern PART = Pattern.compile("^(.*)_(\\d+)$");

    private static final int LINE_FEED = '\n';

    private CsvSplitter() {
    }

    /**
     * Splits the file if it is larger than the maximum size, the file keeps the first part
     *
     * @param maxFileSize maximum size of a part in bytes, 0 or less keeps the file as it is
     * @return the file and its further parts
     */
    public static List<File> split(File file, long maxFileSize) throws IOException {
        return split(file, maxFileSize, '"', true);
    }

    /**
     * Splits the file if it is larger than the maximum size, the file keeps the first part
     *
     * @param quote  quote character of the fields, a line break inside quotes does not end a record
     * @param header whether every part starts with the header row of the file
     */
    public static List<File> split(File file, long maxFileSize, char quote, boolean header) throws IOException {
        List<File> parts = new ArrayList<>();
        parts.add(file);
        if (maxFileSize <= 0 || file.length() <= maxFileSize) {
            return parts;
        }
        File source = new File(file.getParentFile(), file.getName() + ".split");
        Files.move(file.toPath(), source.toPath());
        String baseName = FilenameUtils.getBaseName(file.getName());
        String extension = "." + FilenameUtils.getExtension(file.getName());
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source.toPath()), 1 << 16)) {
            byte[] headerRow = header ? readRecord(in, quote) : new byte[0];
            if (headerRow == null) {
                headerRow = new byte[0];
            }
            OutputStream out = open(file, headerRow);
            long size = headerRow.length;
            boolean empty = true;
            try {
                byte[] record;
                while ((record = readRecord(in, quote)) != null) {
                    // A part holds at least one record, also when the record is larger than the maximum size
                    if (!empty && size + record.length > maxFileSize) {
                        out.close();
                        File part = new File(file.getParentFile(), baseName + "_" + (parts.size() + 1) + extension);
                        parts.add(part);
                        out = open(part, headerRow);
                        size = headerRow.length;
                    }
                    out.write(record);
                    size += record.length;
                    empty = false;
                }
            } finally {
                out.close();
            }
        } finally {
            Files.deleteIfExists(source.toPath());
        }
        return parts;
    }

    /**
     * Files in the order of importing
     */
    public static List<File> sortParts(List<File> files) {
        List<File> sorted = new ArrayList<>(files);
        sorted.sort(PART_ORDER);
        return sorted;
    }

    private static String baseOf(File file) {
        String baseName = FilenameUtils.getBaseName(file.getName());
        Matcher matcher = PART.matcher(baseName);
        return matcher.matches() ? matcher.group(1) : baseName;
    }

    /**
     * Number of the part, 1 for the file without a number
     */
    private static int partOf(File file) {
        Matcher matcher = PART.matcher(FilenameUtils.getBaseName(file.getName()));
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return 1;
    }

    /**
     * Converts a text file between encodings, a byte order mark of UTF-8 is removed from the input and written to
     * the output if asked (Excel recognizes UTF-8 by it). Characters which the encoding can not hold, or bytes which
     * are not of the source encoding, stop the conversion instead of being replaced.
     */
    public static void transcode(File source, Charset from, File target, Charset to, boolean bom) throws IOException {
        File output = source.equals(target) ? new File(target.getPath() + ".encoding") : target;
        try (Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(source.toPath()),
                from.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(
                        CodingErrorAction.REPORT)), 1 << 16);
             Writer writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(output.toPath()),
                     to.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(
                             CodingErrorAction.REPORT)), 1 << 16)) {
            if (bom) {
                writer.write('\uFEFF');
            }
            copy(reader, writer);
        } catch (CharacterCodingException e) {
            Files.deleteIfExists(output.toPath());
            throw new ImpExpException(String.format("%s can not be converted from %s to %s, some characters are "
                    + "not of these encodings, please choose UTF-8", source.getName(), from.name(), to.name()), e);
        }
        if (output != target) {
            Files.move(output.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Copies the text, a byte order mark at the start of the input is dropped
     */
    private static void copy(Reader reader, Writer writer) throws IOException {
        char[] buffer = new char[1 << 15];
        boolean first = true;
        int n;
        while ((n = reader.read(buffer)) != -1) {
            int offset = first && n > 0 && buffer[0] == '\uFEFF' ? 1 : 0;
            first = false;
            writer.write(buffer, offset, n - offset);
        }
    }

    private static OutputStream open(File file, byte[] header) throws IOException {
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()), 1 << 16);
        out.write(header);
        return out;
    }

    /**
     * Reads a record with its line break, null at the end of the input
     */
    private static byte[] readRecord(InputStream in, char quote) throws IOException {
        ByteArrayOutputStream record = new ByteArrayOutputStream(256);
        boolean quoted = false;
        int b;
        while ((b = in.read()) != -1) {
            record.write(b);
            if (b == quote) {
                // An escaped quote ("") toggles twice
                quoted = !quoted;
            } else if (b == LINE_FEED && !quoted) {
                return record.toByteArray();
            }
        }
        return record.size() > 0 ? record.toByteArray() : null;
    }
}
