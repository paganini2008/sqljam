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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: CsvOptions of CSV files like the CSV settings of spreadsheets: delimiter, quote character (doubled
 *               inside quoted fields), text of NULL, header row, encoding (UTF-8 with BOM is opened by Excel) and
 *               formats of dates and timestamps. The defaults (comma, double quote, an empty field for NULL, header,
 *               UTF-8 and ISO dates) keep every value exactly, other formats of timestamps may drop fractions of
 *               seconds. The options of an export are kept in manifest.json and used by its import.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Getter
@Setter
public class CsvOptions {

    public static final String UTF_8_BOM = "UTF-8 (BOM)";
    /**
     * Encodings offered by the user interface, any encoding of Java can be used
     */
    public static final String[] ENCODINGS = {"UTF-8", UTF_8_BOM, "GBK", "GB18030", "UTF-16", "ISO-8859-1"};
    public static final String[] QUOTES = {"\"", "'"};
    /**
     * Date formats offered by the user interface (strftime of DuckDB), empty for ISO dates
     */
    public static final String[] DATE_FORMATS = {"", "%Y/%m/%d", "%d.%m.%Y", "%m/%d/%Y", "%d/%m/%Y"};

    private String delimiter = DuckDBWorkspace.DEFAULT_DELIMITER;
    private String quote = "\"";
    /**
     * Text of NULL, an empty field by default (an empty string is quoted)
     */
    private String nullString = "";
    private boolean header = true;
    private String encoding = "UTF-8";
    /**
     * Format of dates, empty for ISO dates (2024-01-31), timestamps use it with the time (%H:%M:%S)
     */
    private String dateFormat = "";

    /**
     * Charset of the encoding, UTF-8 with BOM is UTF-8
     */
    public Charset getCharset() {
        return isBom() ? StandardCharsets.UTF_8 : Charset.forName(StringUtils.defaultIfBlank(encoding, "UTF-8"));
    }

    public boolean isBom() {
        return UTF_8_BOM.equals(encoding);
    }

    /**
     * Whether DuckDB reads and writes the files directly, other encodings are converted
     */
    public boolean isUtf8() {
        return getCharset().equals(StandardCharsets.UTF_8) && !isBom();
    }

    /**
     * Format of timestamps: the date format with the time, empty for ISO timestamps
     */
    public String getTimestampFormat() {
        return StringUtils.isEmpty(dateFormat) ? "" : dateFormat + " %H:%M:%S";
    }

    /**
     * Options of manifest.json
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("delimiter", delimiter);
        map.put("quote", quote);
        map.put("nullString", nullString);
        map.put("header", header);
        map.put("encoding", encoding);
        map.put("dateFormat", dateFormat);
        return map;
    }

    /**
     * Options of an export package, missing options of older packages keep their defaults
     */
    public static CsvOptions fromMap(Map<String, Object> map) {
        CsvOptions options = new CsvOptions();
        if (map.get("delimiter") != null) {
            options.setDelimiter(map.get("delimiter").toString());
        }
        if (map.get("quote") != null) {
            options.setQuote(map.get("quote").toString());
        }
        if (map.get("nullString") != null) {
            options.setNullString(map.get("nullString").toString());
        }
        if (map.get("header") != null) {
            options.setHeader(Boolean.parseBoolean(map.get("header").toString()));
        }
        if (map.get("encoding") != null) {
            options.setEncoding(map.get("encoding").toString());
        }
        if (map.get("dateFormat") != null) {
            options.setDateFormat(map.get("dateFormat").toString());
        }
        return options;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CsvOptions && toMap().equals(((CsvOptions) other).toMap());
    }

    @Override
    public int hashCode() {
        return Objects.hash(toMap());
    }
}
