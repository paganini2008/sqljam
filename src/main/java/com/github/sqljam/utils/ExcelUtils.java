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
package com.github.sqljam.utils;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FilenameUtils;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ExcelUtils writes rows of a result set into Excel workbooks (.xlsx, one sheet with a header row,
 *               split into orders.xlsx, orders_2.xlsx ... at a number of rows) and reads a sheet of a workbook as a
 *               CSV file. Numbers, booleans, dates and timestamps are cells of their types, values which a cell
 *               would change (more than 15 digits, microseconds, days before 1900-03-01) are text, so that the values are read back exactly.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public final class ExcelUtils {

    public static final String EXCEL_EXTENSION = ".xlsx";
    /**
     * Rows of a sheet without its header row
     */
    public static final int MAX_ROWS = Worksheet.MAX_ROWS - 1;
    /**
     * Characters of a cell which Excel shows, longer values are kept but cut by Excel
     */
    public static final int MAX_CELL_LENGTH = 32767;

    private static final int MAX_SHEET_NAME = 31;
    private static final int DIGITS_OF_DOUBLE = 15;
    private static final int FLUSHED_ROWS = 10000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /**
     * The first day which a date cell keeps: Excel counts days from 1900 and takes 1900 for a leap year, so earlier
     * days are text
     */
    private static final LocalDate FIRST_CELL_DATE = LocalDate.of(1900, 3, 1);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private ExcelUtils() {
    }

    public static boolean isExcel(File file) {
        return file.getName().toLowerCase(Locale.ENGLISH).endsWith(EXCEL_EXTENSION);
    }

    /**
     * Writes the rows of the result set into workbooks of at most maxRows rows each, the first one is the file
     *
     * @param columns names of the columns of the result set, the header row
     * @param sheetName name of the sheet, made acceptable to Excel
     * @return the written files: orders.xlsx, orders_2.xlsx ...
     */
    public static List<File> write(ResultSet rs, List<String> columns, String sheetName, File file, int maxRows)
            throws SQLException, IOException {
        int rowsPerFile = maxRows > 0 ? Math.min(maxRows, MAX_ROWS) : MAX_ROWS;
        List<File> files = new ArrayList<>();
        boolean more = rs.next();
        do {
            File part = files.isEmpty() ? file : new File(file.getParentFile(), FilenameUtils.getBaseName(file
                    .getName()) + "_" + (files.size() + 1) + EXCEL_EXTENSION);
            more = writePart(rs, more, columns, sheetName, part, rowsPerFile);
            files.add(part);
        } while (more);
        return files;
    }

    /**
     * Writes rows into one workbook, the current row of the result set is the first one
     *
     * @return whether rows are left for the next workbook
     */
    private static boolean writePart(ResultSet rs, boolean more, List<String> columns, String table, File file,
                                     int rowsPerFile) throws SQLException, IOException {
        Files.createDirectories(file.getAbsoluteFile().getParentFile().toPath());
        boolean hasRows = more;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()))) {
            Workbook workbook = new Workbook(out, "SqlJam", null);
            Worksheet sheet = workbook.newWorksheet(sheetName(table));
            for (int c = 0; c < columns.size(); c++) {
                sheet.inlineString(0, c, columns.get(c));
                sheet.style(0, c).bold().set();
            }
            sheet.freezePane(0, 1);
            int r = 0;
            boolean warned = false;
            while (hasRows && r < rowsPerFile) {
                r++;
                for (int c = 0; c < columns.size(); c++) {
                    warned |= writeCell(sheet, r, c, valueOf(rs, c + 1), warned, table, columns.get(c));
                }
                if (r % FLUSHED_ROWS == 0) {
                    sheet.flush();
                }
                hasRows = rs.next();
            }
            workbook.finish();
        }
        return hasRows;
    }

    /**
     * A value of the result set, dates and timestamps as java.time values: java.sql.Date and Timestamp take the
     * Julian calendar for days before 1582, e.g. 0001-01-01 would be two days off
     */
    private static Object valueOf(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        try {
            if (value instanceof java.sql.Date) {
                return rs.getObject(column, LocalDate.class);
            }
            if (value instanceof Timestamp) {
                return rs.getObject(column, LocalDateTime.class);
            }
        } catch (SQLException e) {
            // A driver without java.time values, the value as it is
        }
        return value;
    }

    /**
     * Writes a value as a cell of its type, null is no cell
     *
     * @return whether a value longer than Excel shows was written
     */
    private static boolean writeCell(Worksheet sheet, int r, int c, Object value, boolean warned, String table,
                                     String column) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            sheet.value(r, c, (Boolean) value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) {
                sheet.value(r, c, (Number) value);
            } else {
                // inf, -inf and nan as databases read them back
                sheet.inlineString(r, c, value.toString().toLowerCase(Locale.ENGLISH).replace("infinity", "inf"));
            }
        } else if (value instanceof Number) {
            BigDecimal number = value instanceof BigDecimal ? (BigDecimal) value : value instanceof BigInteger
                    ? new BigDecimal((BigInteger) value) : BigDecimal.valueOf(((Number) value).longValue());
            if (number.precision() <= DIGITS_OF_DOUBLE) {
                sheet.value(r, c, number);
            } else {
                // A cell keeps 15 digits, larger numbers are text
                sheet.inlineString(r, c, number.toPlainString());
            }
        } else if (value instanceof LocalDate || value instanceof java.sql.Date) {
            LocalDate date = value instanceof LocalDate ? (LocalDate) value : ((java.sql.Date) value).toLocalDate();
            if (date.isBefore(FIRST_CELL_DATE)) {
                sheet.inlineString(r, c, date.format(DATE));
            } else {
                sheet.value(r, c, date);
                sheet.style(r, c).format("yyyy-mm-dd").set();
            }
        } else if (value instanceof Timestamp || value instanceof LocalDateTime) {
            LocalDateTime timestamp = value instanceof Timestamp ? ((Timestamp) value).toLocalDateTime()
                    : (LocalDateTime) value;
            if (timestamp.getNano() % 1_000_000 == 0 && !timestamp.toLocalDate().isBefore(FIRST_CELL_DATE)) {
                sheet.value(r, c, timestamp);
                sheet.style(r, c).format(timestamp.getNano() == 0 ? "yyyy-mm-dd hh:mm:ss"
                        : "yyyy-mm-dd hh:mm:ss.000").set();
            } else {
                // A cell keeps milliseconds and days from 1900-03-01, others are text
                sheet.inlineString(r, c, timestamp.format(TIMESTAMP));
            }
        } else {
            String text = value.toString();
            sheet.inlineString(r, c, text);
            if (text.length() > MAX_CELL_LENGTH && !warned) {
                log.warn("{}.{} has values longer than the {} characters which Excel shows, they are kept", table,
                        column, MAX_CELL_LENGTH);
                return true;
            }
        }
        return false;
    }

    /**
     * A name which Excel accepts for a sheet: 31 characters at most, without : \ / ? * [ ]
     */
    static String sheetName(String table) {
        String name = table.replaceAll("[:\\\\/?*\\[\\]]", "_").replaceAll("^'+|'+$", "");
        name = name.isEmpty() ? "Sheet1" : name;
        return name.length() > MAX_SHEET_NAME ? name.substring(0, MAX_SHEET_NAME) : name;
    }

    /**
     * Names of the sheets of a workbook in their order
     */
    public static List<String> sheetNames(File file) throws IOException {
        try (ReadableWorkbook workbook = open(file)) {
            return workbook.getSheets().map(Sheet::getName).collect(Collectors.toList());
        }
    }

    /**
     * Writes a sheet of a workbook as a CSV file in UTF-8 with the first row as its header: numbers in plain
     * notation, dates as yyyy-MM-dd, timestamps as yyyy-MM-dd HH:mm:ss.SSS, booleans as true and false, formulas
     * by their computed values. An empty cell is NULL, an empty text is "", errors (#DIV/0!) are NULL.
     *
     * @param sheetName name of the sheet, null for the first sheet
     * @return rows written without the header row
     */
    public static long toCsv(File file, String sheetName, File csv) throws IOException {
        try (ReadableWorkbook workbook = open(file)) {
            Sheet sheet = sheetName == null ? workbook.getFirstSheet() : workbook.findSheet(sheetName)
                    .orElseThrow(() -> new IllegalArgumentException(String.format("%s has no sheet %s",
                            file.getName(), sheetName)));
            Files.createDirectories(csv.getAbsoluteFile().getParentFile().toPath());
            long rows = 0;
            try (Stream<Row> stream = sheet.openStream();
                 Writer writer = new BufferedWriter(Files.newBufferedWriter(csv.toPath(), StandardCharsets.UTF_8))) {
                Iterator<Row> iterator = stream.iterator();
                int width = -1;
                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    if (width < 0) {
                        // The first row is the header, its last named cell ends the columns
                        width = headerWidth(row);
                        if (width == 0) {
                            width = -1;
                            continue;
                        }
                        writeHeader(writer, row, width);
                        continue;
                    }
                    if (isEmpty(row, width)) {
                        continue;
                    }
                    for (int c = 0; c < width; c++) {
                        if (c > 0) {
                            writer.write(',');
                        }
                        writer.write(csvValue(row.getOptionalCell(c).orElse(null)));
                    }
                    writer.write('\n');
                    rows++;
                }
            }
            return rows;
        }
    }

    private static ReadableWorkbook open(File file) throws IOException {
        byte[] header = new byte[8];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int read = in.read(header);
            if (read == header.length && ReadableWorkbook.isOLE2Header(header)) {
                throw new IOException(String.format("%s is a workbook of Excel 97-2003, save it as .xlsx",
                        file.getName()));
            }
        }
        return new ReadableWorkbook(file, new ReadingOptions(true, false));
    }

    private static int headerWidth(Row row) {
        int width = 0;
        for (int c = 0; c < row.getCellCount(); c++) {
            Cell cell = row.getOptionalCell(c).orElse(null);
            if (cell != null && cell.getType() != CellType.EMPTY && !cell.getText().isEmpty()) {
                width = c + 1;
            }
        }
        return width;
    }

    private static void writeHeader(Writer writer, Row row, int width) throws IOException {
        for (int c = 0; c < width; c++) {
            if (c > 0) {
                writer.write(',');
            }
            Cell cell = row.getOptionalCell(c).orElse(null);
            String name = cell != null ? cell.getText().trim() : "";
            writer.write(quote(name.isEmpty() ? "column" + (c + 1) : name));
        }
        writer.write('\n');
    }

    private static boolean isEmpty(Row row, int width) {
        for (int c = 0; c < width; c++) {
            Cell cell = row.getOptionalCell(c).orElse(null);
            if (cell != null && cell.getType() != CellType.EMPTY) {
                return false;
            }
        }
        return true;
    }

    /**
     * A value of a cell in CSV, an empty field for NULL
     */
    static String csvValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        Object value = cell.getValue();
        switch (cell.getType()) {
            case EMPTY:
            case ERROR:
                return "";
            case BOOLEAN:
                return String.valueOf(cell.asBoolean());
            case NUMBER:
                return number(cell, (BigDecimal) value);
            case FORMULA:
                // The value computed by the spreadsheet, empty when it never computed it
                if (value instanceof BigDecimal) {
                    return number(cell, (BigDecimal) value);
                }
                return value == null ? "" : quote(value.toString());
            default:
                return quote(value == null ? "" : value.toString());
        }
    }

    private static String number(Cell cell, BigDecimal value) {
        if (value == null) {
            return "";
        }
        if (isDateFormat(cell.getDataFormatId(), cell.getDataFormatString())) {
            LocalDateTime date = cell.asDate();
            if (date.toLocalTime().equals(LocalTime.MIDNIGHT) && !hasTime(cell.getDataFormatString())) {
                return date.format(DATE);
            }
            String text = date.format(TIMESTAMP);
            // Milliseconds at most, trailing zeros removed
            return text.replaceAll("\\.?0+$", "");
        }
        return value.toPlainString();
    }

    /**
     * Whether a number format shows a date: the built-in formats of dates and times, or a format with days,
     * months, years, hours or seconds outside of quotes, brackets and escapes, fractions of seconds included
     */
    static boolean isDateFormat(Integer id, String format) {
        if (id != null && (id >= 14 && id <= 22 || id >= 45 && id <= 47)) {
            return true;
        }
        if (format == null || format.isEmpty() || "General".equalsIgnoreCase(format)) {
            return false;
        }
        // Fractions of seconds (ss.000) are not digits of numbers
        String plain = format.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\\\.", "")
                .toLowerCase(Locale.ENGLISH).replaceAll("s\\.0+", "s");
        return plain.matches(".*[dmyhs].*") && !plain.matches(".*[0#?].*");
    }

    private static boolean hasTime(String format) {
        return format != null && format.replaceAll("\"[^\"]*\"", "").toLowerCase(Locale.ENGLISH).matches(".*[hs].*");
    }

    /**
     * A field quoted when needed, an empty text is "" to keep it apart from NULL
     */
    static String quote(String text) {
        if (text.isEmpty()) {
            return "\"\"";
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || !text.trim().equals(text)) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
