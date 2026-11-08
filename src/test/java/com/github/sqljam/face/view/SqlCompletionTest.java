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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: SqlCompletionTest verifies the completion of SQL editors: the word and the name before the dot at
 *               the caret, aliases and names of tables of a statement (quoted, qualified, with AS), the
 *               suggestions of columns, tables and keywords, names quoted when they are no identifiers, and the
 *               names of a source read once until the completion is reset
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class SqlCompletionTest {

    private SqlEditor editor;

    @Start
    void start(Stage stage) {
        editor = new SqlEditor();
        stage.setScene(new Scene(new StackPane(editor.getNode()), 400, 300));
        stage.show();
    }

    @Test
    void readsTheWordAtTheCaret() {
        SqlCompletion.Context context = SqlCompletion.contextAt("SELECT o.na FROM orders o", 11);
        assertEquals("na", context.prefix);
        assertEquals("o", context.qualifier);
        assertEquals(9, context.start);
        context = SqlCompletion.contextAt("SELECT \"my orders\".", 19);
        assertEquals("", context.prefix);
        assertEquals("my orders", context.qualifier);
        context = SqlCompletion.contextAt("SELECT ", 7);
        assertEquals("", context.prefix);
        assertNull(context.qualifier);
        context = SqlCompletion.contextAt(".x", 2);
        assertNull(context.qualifier);
        assertEquals("ab", SqlCompletion.contextAt("ab", 99).prefix);
    }

    @Test
    void findsTablesAndAliases() {
        Map<String, String> aliases = SqlCompletion.aliases("SELECT * FROM shop.orders o JOIN \"Line Items\" AS"
                + " li ON o.id = li.order_id LEFT JOIN `logs-2024` WHERE x = 1");
        assertEquals("orders", aliases.get("o"));
        assertEquals("orders", aliases.get("orders"));
        assertEquals("Line Items", aliases.get("li"));
        assertEquals("Line Items", aliases.get("line items"));
        assertEquals("logs-2024", aliases.get("logs-2024"));
        assertNull(aliases.get("where"));
        assertTrue(SqlCompletion.aliases(null).isEmpty());
        assertEquals(List.of("NAME"), SqlCompletion.filter(List.of("ID", "NAME", "note", "na"), "na"));
        assertEquals("orders", SqlCompletion.quoteIfNeeded("orders"));
        assertEquals("\"logs-2024\"", SqlCompletion.quoteIfNeeded("logs-2024"));
        assertEquals("\"a\"\"b\"", SqlCompletion.quoteIfNeeded("a\"b"));
    }

    @Test
    void suggestsColumnsTablesAndKeywordsOnce() throws Exception {
        AtomicInteger tableReads = new AtomicInteger();
        AtomicInteger columnReads = new AtomicInteger();
        SqlCompletion completion = FxTestSupport.call(() -> SqlCompletion.attach(editor, new SqlCompletion.Source() {
            @Override
            public List<String> tables() {
                tableReads.incrementAndGet();
                return List.of("orders", "order_items", "logs-2024");
            }

            @Override
            public List<String> columns(String table) {
                columnReads.incrementAndGet();
                if ("missing".equals(table)) {
                    throw new IllegalStateException("no such table");
                }
                return "orders".equals(table) ? List.of("id", "ordered_at", "total") : List.of("item");
            }
        }));
        String sql = "SELECT * FROM orders o";
        // Columns of the tables of the statement, then tables, then keywords
        assertEquals(List.of("ordered_at", "orders", "order_items", "ORDER"), completion.suggest(
                SqlCompletion.contextAt("SELECT ord", 10), sql));
        assertEquals(List.of("id", "ordered_at", "total"), completion.suggest(SqlCompletion.contextAt("o.", 2),
                sql));
        assertEquals(List.of("total"), completion.suggest(SqlCompletion.contextAt("orders.t", 8), sql));
        // A table which the source does not know has no columns
        assertEquals(List.of(), completion.suggest(SqlCompletion.contextAt("m.", 2), "SELECT * FROM missing m"));
        // Nothing typed: names only, no keywords
        assertTrue(completion.suggest(SqlCompletion.contextAt("", 0), "SELECT 1").stream().noneMatch("SELECT"::equals));
        assertEquals(1, tableReads.get());
        assertEquals(2, columnReads.get());
        completion.reset();
        completion.suggest(SqlCompletion.contextAt("o.", 2), sql);
        assertEquals(3, columnReads.get());

        // Chosen names replace the word, names which are no identifiers are quoted
        FxTestSupport.run(() -> {
            editor.setText("SELECT * FROM log");
            completion.insert(SqlCompletion.contextAt(editor.getText(), 17), "logs-2024");
        });
        assertEquals("SELECT * FROM \"logs-2024\"", FxTestSupport.call(editor::getText));
        FxTestSupport.run(() -> {
            editor.setText("sel");
            completion.insert(SqlCompletion.contextAt(editor.getText(), 3), "SELECT");
        });
        assertEquals("SELECT", FxTestSupport.call(editor::getText));
    }

    /**
     * An editor which is not shown, e.g. its tab is closed while the names are read, opens no menu
     */
    @Test
    void showsNothingForEditorNotShown() {
        AtomicInteger tableReads = new AtomicInteger();
        SqlEditor hidden = FxTestSupport.call(SqlEditor::new);
        SqlCompletion completion = FxTestSupport.call(() -> SqlCompletion.attach(hidden, new SqlCompletion.Source() {
            @Override
            public List<String> tables() {
                tableReads.incrementAndGet();
                return List.of("orders");
            }

            @Override
            public List<String> columns(String table) {
                return List.of();
            }
        }));
        FxTestSupport.run(completion::show);
        FxTestSupport.waitUntil(() -> tableReads.get() == 1);
        // After the names are handed to the FX thread
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        assertFalse(FxTestSupport.call(completion.getMenu()::isShowing));
    }
}
