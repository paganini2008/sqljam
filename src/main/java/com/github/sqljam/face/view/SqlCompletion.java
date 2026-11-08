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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.fxmisc.richtext.CodeArea;
import com.github.sqljam.face.service.SqlStatements;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SqlCompletion suggests names in a SQL editor like DBeaver and DataGrip: Ctrl+Space lists the
 *               columns of the tables of the statement, the tables and the keywords which start with the word at
 *               the caret, a dot after a table or an alias lists the columns of that table. Tables and columns
 *               come from the source of the editor (the metadata of a database, the indexes of a search engine),
 *               read in the background once and kept. A name which is no plain identifier is inserted quoted.
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
@Slf4j
public final class SqlCompletion {

    /**
     * Suggestions shown at most
     */
    static final int MAX_ITEMS = 60;
    static final KeyCombination COMPLETE = new KeyCodeCombination(KeyCode.SPACE,
            KeyCombination.CONTROL_DOWN);
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+"
            + "((?:\"[^\"]+\"|`[^`]+`|[\\w$-]+)(?:\\.(?:\"[^\"]+\"|`[^`]+`|[\\w$-]+))*)"
            + "(?:\\s+(?:AS\\s+)?(?!(?i:WHERE|ON|JOIN|INNER|LEFT|RIGHT|FULL|CROSS|GROUP|ORDER|LIMIT|UNION|SET"
            + "|VALUES|HAVING|SELECT)\\b)(\\w+))?");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    /**
     * Tables and their columns of the source of an editor
     */
    public interface Source {

        List<String> tables() throws Exception;

        List<String> columns(String table) throws Exception;
    }

    /**
     * The word at the caret and the name before its dot
     */
    static final class Context {

        final int start;
        final String prefix;
        final String qualifier;

        Context(int start, String prefix, String qualifier) {
            this.start = start;
            this.prefix = prefix;
            this.qualifier = qualifier;
        }
    }

    private final SqlEditor editor;
    private final Source source;
    private final ContextMenu menu = new ContextMenu();
    private volatile List<String> tables;
    private final Map<String, List<String>> columns = new ConcurrentHashMap<>();

    private SqlCompletion(SqlEditor editor, Source source) {
        this.editor = editor;
        this.source = source;
        menu.setId("sqlCompletionMenu");
        CodeArea area = editor.getCodeArea();
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (COMPLETE.match(event)) {
                show();
                event.consume();
            }
        });
        area.addEventHandler(KeyEvent.KEY_TYPED, event -> {
            if (".".equals(event.getCharacter())) {
                // After the dot is in the text
                Platform.runLater(this::show);
            }
        });
    }

    /**
     * Suggests names in the editor from the source
     */
    public static SqlCompletion attach(SqlEditor editor, Source source) {
        return new SqlCompletion(editor, source);
    }

    /**
     * Tables and columns are read again at the next completion, e.g. after another schema is chosen
     */
    public void reset() {
        tables = null;
        columns.clear();
    }

    /**
     * Lists the suggestions at the caret, read in the background
     */
    public void show() {
        String text = editor.getText();
        int caret = editor.getCaretPosition();
        Context context = contextAt(text, caret);
        SqlStatements.Statement statement = SqlStatements.at(text, caret);
        String sql = statement != null ? statement.getText() : text;
        TaskRunner.run(() -> suggest(context, sql), names -> {
            if (editor.getCaretPosition() == caret && editor.getText().equals(text)) {
                showMenu(context, names);
            }
        }, e -> {
            if (log.isDebugEnabled()) {
                log.debug("Unable to read the names of the completion: {}", e.getMessage());
            }
        });
    }

    List<String> suggest(Context context, String sql) throws Exception {
        Map<String, String> aliases = aliases(sql);
        if (context.qualifier != null) {
            String table = aliases.getOrDefault(context.qualifier.toLowerCase(Locale.ENGLISH), context.qualifier);
            return filter(columnsOf(table), context.prefix);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String table : new LinkedHashSet<>(aliases.values())) {
            names.addAll(columnsOf(table));
        }
        names.addAll(tables());
        List<String> suggestions = filter(new ArrayList<>(names), context.prefix);
        if (!context.prefix.isEmpty()) {
            // Keywords complete a word, they are not listed for nothing
            for (String keyword : SqlEditor.keywords()) {
                if (keyword.startsWith(context.prefix.toUpperCase(Locale.ENGLISH))
                        && !keyword.equalsIgnoreCase(context.prefix)) {
                    suggestions.add(keyword);
                }
            }
        }
        return suggestions.size() > MAX_ITEMS ? suggestions.subList(0, MAX_ITEMS) : suggestions;
    }

    private List<String> tables() throws Exception {
        List<String> read = tables;
        if (read == null) {
            read = Collections.unmodifiableList(new ArrayList<>(source.tables()));
            tables = read;
        }
        return read;
    }

    /**
     * Columns of a table, read once. A table which the source does not know has none
     */
    private List<String> columnsOf(String table) {
        String name = unquote(table.contains(".") && !table.startsWith("\"") ? StringUtils.substringAfterLast(table,
                ".") : table);
        return columns.computeIfAbsent(name.toLowerCase(Locale.ENGLISH), key -> {
            try {
                return Collections.unmodifiableList(new ArrayList<>(source.columns(name)));
            } catch (Exception e) {
                return Collections.emptyList();
            }
        });
    }

    private void showMenu(Context context, List<String> names) {
        menu.hide();
        menu.getItems().clear();
        if (names.isEmpty()) {
            return;
        }
        for (String name : names) {
            Label label = new Label(name);
            CustomMenuItem item = new CustomMenuItem(label, true);
            item.setOnAction(event -> insert(context, name));
            menu.getItems().add(item);
        }
        CodeArea area = editor.getCodeArea();
        Optional<Bounds> caret = area.getCaretBounds();
        if (caret.isPresent()) {
            menu.show(area, caret.get().getMinX(), caret.get().getMaxY());
        } else {
            // No place on the screen while the editor is not shown, e.g. its tab is closed meanwhile
            Bounds bounds = area.localToScreen(area.getBoundsInLocal());
            if (bounds == null) {
                return;
            }
            menu.show(area, bounds.getMinX(), bounds.getMinY());
        }
        // The first suggestion is chosen by Enter
        if (menu.getSkin() != null) {
            menu.getSkin().getNode().requestFocus();
        }
    }

    /**
     * Replaces the word at the caret by the name, quoted when it is no plain identifier
     */
    void insert(Context context, String name) {
        CodeArea area = editor.getCodeArea();
        boolean keyword = SqlEditor.keywords().contains(name);
        area.replaceText(context.start, context.start + context.prefix.length(), keyword ? name : quoteIfNeeded(
                name));
        area.requestFocus();
    }

    ContextMenu getMenu() {
        return menu;
    }

    /**
     * The word before the caret and the name before its dot: "o.na" is the qualifier o and the prefix na
     */
    static Context contextAt(String text, int caret) {
        int end = Math.max(0, Math.min(caret, text.length()));
        int start = end;
        while (start > 0 && isNamePart(text.charAt(start - 1))) {
            start--;
        }
        String prefix = text.substring(start, end);
        String qualifier = null;
        if (start > 0 && text.charAt(start - 1) == '.') {
            int qualifierEnd = start - 1;
            int qualifierStart = qualifierEnd;
            if (qualifierEnd > 0 && text.charAt(qualifierEnd - 1) == '"') {
                int open = text.lastIndexOf('"', qualifierEnd - 2);
                if (open >= 0) {
                    qualifierStart = open;
                }
            } else {
                while (qualifierStart > 0 && isNamePart(text.charAt(qualifierStart - 1))) {
                    qualifierStart--;
                }
            }
            if (qualifierStart < qualifierEnd) {
                qualifier = unquote(text.substring(qualifierStart, qualifierEnd));
            }
        }
        return new Context(start, prefix, qualifier);
    }

    /**
     * Tables of a statement by their aliases and names in lower case: FROM orders o JOIN items i maps o and
     * orders to orders, i and items to items
     */
    static Map<String, String> aliases(String sql) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher matcher = TABLE.matcher(StringUtils.defaultString(sql));
        while (matcher.find()) {
            String table = matcher.group(1);
            String name = unquote(table.contains(".") && !table.startsWith("\"") && !table.startsWith("`")
                    ? StringUtils.substringAfterLast(table, ".") : table);
            aliases.put(name.toLowerCase(Locale.ENGLISH), name);
            if (matcher.group(2) != null) {
                aliases.put(matcher.group(2).toLowerCase(Locale.ENGLISH), name);
            }
        }
        return aliases;
    }

    /**
     * Names which start with the prefix, without case
     */
    static List<String> filter(List<String> names, String prefix) {
        List<String> matched = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ENGLISH);
        for (String name : names) {
            if (name.toLowerCase(Locale.ENGLISH).startsWith(lower) && !name.equals(prefix)) {
                matched.add(name);
            }
        }
        return matched;
    }

    static String quoteIfNeeded(String name) {
        return PLAIN.matcher(name).matches() ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String unquote(String name) {
        if (name.length() >= 2 && (name.startsWith("\"") && name.endsWith("\"") || name.startsWith("`")
                && name.endsWith("`"))) {
            return name.substring(1, name.length() - 1).replace("\"\"", "\"");
        }
        return name;
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
