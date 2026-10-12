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
package com.github.sqljam.face.view;

import java.util.List;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.CsvOptions;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

/**
 * @Description: CsvOptionsPane edits the options of CSV files: delimiter, quote character, text of NULL, header row,
 *               encoding and date format. Options of files of other tools may be detected: an empty delimiter and an
 *               empty date format are detected from the files.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
final class CsvOptionsPane {

    private final ComboBox<String> delimiterCombo = new ComboBox<>();
    private final ComboBox<String> quoteCombo = new ComboBox<>();
    private final TextField nullField = new TextField();
    private final CheckBox headerCheck = new CheckBox(Messages.get("csv.header"));
    private final ComboBox<String> encodingCombo = new ComboBox<>();
    private final ComboBox<String> dateFormatCombo = new ComboBox<>();
    private final GridPane node = new GridPane();
    private final boolean detected;

    /**
     * @param detected whether the delimiter and the date format of files of other tools may be detected
     */
    CsvOptionsPane(CsvOptions options, boolean detected) {
        this.detected = detected;
        delimiterCombo.setId("csvDelimiterCombo");
        quoteCombo.setId("csvQuoteCombo");
        nullField.setId("csvNullField");
        headerCheck.setId("csvHeaderCheck");
        encodingCombo.setId("csvEncodingCombo");
        dateFormatCombo.setId("csvDateFormatCombo");
        if (detected) {
            delimiterCombo.getItems().add("");
        }
        delimiterCombo.getItems().addAll(CsvExporter.DELIMITERS);
        delimiterCombo.setConverter(converter(CsvOptionsPane::delimiterName));
        quoteCombo.getItems().addAll(CsvOptions.QUOTES);
        quoteCombo.setConverter(converter(quote -> "\"".equals(quote) ? Messages.get("csv.quote.double")
                : Messages.get("csv.quote.single")));
        nullField.setPromptText(Messages.get("csv.null.prompt"));
        encodingCombo.getItems().addAll(CsvOptions.ENCODINGS);
        dateFormatCombo.getItems().addAll(CsvOptions.DATE_FORMATS);
        dateFormatCombo.setConverter(converter(format -> StringUtils.isEmpty(format) ? Messages.get(detected
                ? "csv.dateFormat.detect" : "csv.dateFormat.iso") : format.replace("%Y", "yyyy").replace("%m", "MM")
                .replace("%d", "dd")));
        for (ComboBox<String> combo : List.of(delimiterCombo, quoteCombo, encodingCombo, dateFormatCombo)) {
            combo.setMaxWidth(Double.MAX_VALUE);
        }
        node.setHgap(10);
        node.setVgap(8);
        node.addRow(0, new Label(Messages.get("csv.delimiter")), delimiterCombo, new Label(Messages.get("csv.quote")),
                quoteCombo);
        node.addRow(1, new Label(Messages.get("csv.null")), nullField, new Label(Messages.get("csv.encoding")),
                encodingCombo);
        node.addRow(2, new Label(Messages.get("csv.dateFormat")), dateFormatCombo, headerCheck);
        setOptions(options);
    }

    private static StringConverter<String> converter(Function<String, String> name) {
        return new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : name.apply(value);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        };
    }

    /**
     * Name of a delimiter: a tab has no visible character, an empty delimiter is detected
     */
    static String delimiterName(String delimiter) {
        if (delimiter == null) {
            return "";
        }
        switch (delimiter) {
            case "":
                return Messages.get("csv.delimiter.detect");
            case ",":
                return Messages.get("export.format.delimiter.comma");
            case ";":
                return Messages.get("export.format.delimiter.semicolon");
            case "\t":
                return Messages.get("export.format.delimiter.tab");
            case "|":
                return Messages.get("export.format.delimiter.pipe");
            default:
                return delimiter;
        }
    }

    void setOptions(CsvOptions options) {
        CsvOptions value = options != null ? options : new CsvOptions();
        delimiterCombo.setValue(delimiterCombo.getItems().contains(value.getDelimiter()) ? value.getDelimiter()
                : detected ? "" : ",");
        quoteCombo.setValue(quoteCombo.getItems().contains(value.getQuote()) ? value.getQuote() : "\"");
        nullField.setText(value.getNullString());
        headerCheck.setSelected(value.isHeader());
        encodingCombo.setValue(encodingCombo.getItems().contains(value.getEncoding()) ? value.getEncoding()
                : "UTF-8");
        dateFormatCombo.setValue(dateFormatCombo.getItems().contains(value.getDateFormat()) ? value.getDateFormat()
                : "");
    }

    CsvOptions getOptions() {
        CsvOptions options = new CsvOptions();
        options.setDelimiter(StringUtils.defaultString(delimiterCombo.getValue()));
        options.setQuote(StringUtils.defaultIfEmpty(quoteCombo.getValue(), "\""));
        options.setNullString(StringUtils.defaultString(nullField.getText()));
        options.setHeader(headerCheck.isSelected());
        options.setEncoding(StringUtils.defaultIfEmpty(encodingCombo.getValue(), "UTF-8"));
        options.setDateFormat(StringUtils.defaultString(dateFormatCombo.getValue()));
        return options;
    }

    /**
     * Runs the action when an option is changed, e.g. to preview the files again
     */
    void setOnChange(Runnable action) {
        delimiterCombo.valueProperty().addListener((obs, oldValue, value) -> action.run());
        quoteCombo.valueProperty().addListener((obs, oldValue, value) -> action.run());
        nullField.textProperty().addListener((obs, oldValue, value) -> action.run());
        headerCheck.selectedProperty().addListener((obs, oldValue, value) -> action.run());
        encodingCombo.valueProperty().addListener((obs, oldValue, value) -> action.run());
        dateFormatCombo.valueProperty().addListener((obs, oldValue, value) -> action.run());
    }

    Node getNode() {
        return node;
    }
}
