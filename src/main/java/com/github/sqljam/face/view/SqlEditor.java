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

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import javafx.scene.Node;

/**
 * @Description: SqlEditor is the text area of the SQL editor with line numbers and syntax highlighting: keywords,
 *               strings, quoted names, numbers and comments. Highlighting follows each change of the text.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
final class SqlEditor {

    private static final String[] KEYWORDS = {
            "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BEGIN", "BETWEEN", "BY", "CALL", "CASE", "CAST",
            "CHECK", "COLUMN", "COMMENT", "COMMIT", "CONSTRAINT", "CREATE", "CROSS", "CURRENT_DATE",
            "CURRENT_TIMESTAMP", "DATABASE", "DECLARE", "DEFAULT", "DELETE", "DESC", "DESCRIBE", "DISTINCT", "DROP",
            "ELSE", "END", "EXCEPT", "EXEC", "EXISTS", "EXPLAIN", "FALSE", "FETCH", "FIRST", "FOR", "FOREIGN", "FROM",
            "FULL", "FUNCTION", "GRANT", "GROUP", "HAVING", "IF", "IN", "INDEX", "INNER", "INSERT", "INTERSECT",
            "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "MERGE", "MINUS", "NATURAL", "NEXT", "NOT", "NULL",
            "OFFSET", "ON", "OR", "ORDER", "OUTER", "OVER", "PARTITION", "PRAGMA", "PRIMARY", "PROCEDURE",
            "REFERENCES", "REPLACE", "RETURNING", "REVOKE", "RIGHT", "ROLLBACK", "ROW", "ROWS", "SCHEMA", "SELECT",
            "SEQUENCE", "SET", "SHOW", "TABLE", "THEN", "TOP", "TRIGGER", "TRUE", "TRUNCATE", "UNION", "UNIQUE",
            "UPDATE", "USE", "USING", "VALUES", "VIEW", "WHEN", "WHERE", "WINDOW", "WITH"
    };

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>--[^\\n]*|/\\*[\\s\\S]*?(\\*/|$))"
                    + "|(?<STRING>'(?:[^']|'')*'?)"
                    + "|(?<NAME>\"(?:[^\"]|\"\")*\"?|`[^`]*`?)"
                    + "|(?<KEYWORD>\\b(?i:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)");

    private final CodeArea codeArea = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);

    SqlEditor() {
        codeArea.setId("sqlEditor");
        codeArea.getStyleClass().add("sql-editor");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.textProperty().addListener((obs, oldText, text) -> codeArea.setStyleSpans(0, highlight(text)));
    }

    Node getNode() {
        return scrollPane;
    }

    CodeArea getCodeArea() {
        return codeArea;
    }

    String getText() {
        return codeArea.getText();
    }

    void setText(String text) {
        codeArea.replaceText(text != null ? text : "");
        codeArea.moveTo(codeArea.getLength());
    }

    String getSelectedText() {
        return codeArea.getSelectedText();
    }

    int getCaretPosition() {
        return codeArea.getCaretPosition();
    }

    /**
     * Style classes of the text: sql-keyword, sql-string, sql-name, sql-number and sql-comment
     */
    static StyleSpans<Collection<String>> highlight(String text) {
        Matcher matcher = PATTERN.matcher(text);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int last = 0;
        while (matcher.find()) {
            String style = matcher.group("COMMENT") != null ? "sql-comment"
                    : matcher.group("STRING") != null ? "sql-string"
                    : matcher.group("NAME") != null ? "sql-name"
                    : matcher.group("KEYWORD") != null ? "sql-keyword" : "sql-number";
            spans.add(Collections.emptyList(), matcher.start() - last);
            spans.add(Collections.singleton(style), matcher.end() - matcher.start());
            last = matcher.end();
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }
}
