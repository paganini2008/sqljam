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
package com.github.sqljam.face.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * @Description: SqlStatements splits the text of the SQL editor into statements with their positions, so that the
 *               statement at the caret can be run. Statements end with a semicolon outside of strings, quoted names
 *               and comments. PL/SQL blocks (BEGIN, DECLARE, CREATE PROCEDURE ...) keep their semicolons and end
 *               with a line of a single slash, like SQL*Plus.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
public final class SqlStatements {

    private static final Pattern BLOCK = Pattern.compile("^(BEGIN|DECLARE|CREATE\\s+(OR\\s+REPLACE\\s+)?"
            + "(EDITIONABLE\\s+|NONEDITIONABLE\\s+)?(PROCEDURE|FUNCTION|TRIGGER|PACKAGE|TYPE\\s+BODY))\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern QUERY = Pattern.compile("^(SELECT|WITH|VALUES|TABLE|SHOW|DESCRIBE|DESC|EXPLAIN"
            + "|PRAGMA|CALL)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * A statement and its range in the text, the text has no end mark
     */
    public static final class Statement {

        private final String text;
        private final int start;
        private final int end;

        Statement(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }

        public String getText() {
            return text;
        }

        /**
         * Position of the first character of the statement
         */
        public int getStart() {
            return start;
        }

        /**
         * Position after the end mark of the statement, or after its last character
         */
        public int getEnd() {
            return end;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private SqlStatements() {
    }

    /**
     * Statements of the text in their order, blank statements are left out
     */
    public static List<Statement> split(String sql) {
        List<Statement> statements = new ArrayList<>();
        if (sql == null) {
            return statements;
        }
        int length = sql.length();
        int start = 0;
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '[' && isSqlServerName(sql, i)) {
                i = skipQuoted(sql, i, ']');
                continue;
            }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                i = skipLine(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? length : close + 2;
                continue;
            }
            if (c == ';' && !isBlock(sql, start, i)) {
                add(statements, sql, start, i, i + 1);
                start = i + 1;
            } else if (c == '/' && isSlashLine(sql, i)) {
                add(statements, sql, start, i, i + 1);
                start = i + 1;
            }
            i++;
        }
        add(statements, sql, start, length, length);
        return statements;
    }

    /**
     * The statement at the position: the statement which contains it, or the statement before it when the
     * position follows its end mark on the same line, null if there is none
     */
    public static Statement at(String sql, int position) {
        List<Statement> statements = split(sql);
        Statement before = null;
        for (Statement statement : statements) {
            if (position >= statement.getStart() && position <= statement.getEnd()) {
                return statement;
            }
            if (statement.getEnd() <= position) {
                before = statement;
            }
        }
        if (before != null && sql.substring(before.getEnd(), Math.min(position, sql.length())).indexOf('\n') < 0) {
            return before;
        }
        // The next statement after blank lines or comments
        return statements.stream().filter(statement -> statement.getStart() >= position).findFirst().orElse(before);
    }

    /**
     * Whether the statement returns rows, other statements report their update counts
     */
    public static boolean isQuery(String sql) {
        return QUERY.matcher(stripLeadingComments(sql)).matches();
    }

    /**
     * Text without the comments before the first word
     */
    public static String stripLeadingComments(String sql) {
        String text = sql == null ? "" : sql.trim();
        while (true) {
            if (text.startsWith("--")) {
                int end = text.indexOf('\n');
                text = end < 0 ? "" : text.substring(end + 1).trim();
            } else if (text.startsWith("/*")) {
                int end = text.indexOf("*/");
                text = end < 0 ? "" : text.substring(end + 2).trim();
            } else {
                return text;
            }
        }
    }

    private static void add(List<Statement> statements, String sql, int start, int end, int markEnd) {
        String text = sql.substring(start, end);
        String trimmed = text.trim();
        if (stripLeadingComments(trimmed).isEmpty()) {
            return;
        }
        int offset = start + text.indexOf(trimmed);
        statements.add(new Statement(trimmed, offset, markEnd));
    }

    /**
     * Position after the closing quote, a doubled quote is part of the text
     */
    private static int skipQuoted(String sql, int open, char close) {
        int i = open + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == close) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (sql.charAt(i) == '\\' && close == '\'' && i + 1 < sql.length()) {
                // MySQL escapes in strings
                i += 2;
                continue;
            }
            i++;
        }
        return sql.length();
    }

    /**
     * [name] of SQL Server, not an array index like a[1] of PostgreSQL
     */
    private static boolean isSqlServerName(String sql, int i) {
        return i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)) && sql.charAt(i - 1) != '_';
    }

    private static int skipLine(String sql, int i) {
        int end = sql.indexOf('\n', i);
        return end < 0 ? sql.length() : end + 1;
    }

    private static boolean isBlock(String sql, int start, int end) {
        return BLOCK.matcher(stripLeadingComments(sql.substring(start, end))).matches();
    }

    /**
     * A line of a single slash ends a PL/SQL block
     */
    private static boolean isSlashLine(String sql, int i) {
        int lineStart = sql.lastIndexOf('\n', i - 1) + 1;
        int lineEnd = sql.indexOf('\n', i);
        String line = sql.substring(lineStart, lineEnd < 0 ? sql.length() : lineEnd);
        return "/".equals(line.trim().toLowerCase(Locale.ENGLISH));
    }
}
