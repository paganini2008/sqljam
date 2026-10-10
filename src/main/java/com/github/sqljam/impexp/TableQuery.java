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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: TableQuery narrows the rows of a table: selected columns, a WHERE condition, GROUP BY columns and
 *               ORDER BY. Conditions are sql of the source database. Exports use the columns, the condition and the
 *               order, grouped rows are for viewing.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class TableQuery {

    /**
     * Name of the count column of grouped rows
     */
    public static final String COUNT_COLUMN = "row_count";

    private List<String> columns = new ArrayList<>();
    private String where;
    private List<String> groupBy = new ArrayList<>();
    private String orderBy;

    public TableQuery() {
    }

    public TableQuery(List<String> columns, String where, List<String> groupBy, String orderBy) {
        setColumns(columns);
        setWhere(where);
        setGroupBy(groupBy);
        setOrderBy(orderBy);
    }

    /**
     * Selected columns, all columns if empty
     */
    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns != null ? new ArrayList<>(columns) : new ArrayList<>();
    }

    /**
     * Condition without the WHERE keyword, e.g. amount > 100 AND status = 'PAID'
     */
    public String getWhere() {
        return where;
    }

    public void setWhere(String where) {
        this.where = trimClause(where, "where");
    }

    /**
     * Grouped rows have the group columns and the count of rows
     */
    public List<String> getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(List<String> groupBy) {
        this.groupBy = groupBy != null ? new ArrayList<>(groupBy) : new ArrayList<>();
    }

    /**
     * Order without the ORDER BY keywords, e.g. created_at DESC, id
     */
    public String getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(String orderBy) {
        this.orderBy = trimClause(orderBy, "order\\s+by");
    }

    public boolean isGrouped() {
        return !groupBy.isEmpty();
    }

    /**
     * Whether the query narrows nothing, all rows and columns in the default order
     */
    public boolean isEmpty() {
        return columns.isEmpty() && where == null && groupBy.isEmpty() && orderBy == null;
    }

    /**
     * A clause typed by the user, the keyword and trailing semicolons are removed. Semicolons inside the clause
     * are rejected, a clause is not a statement.
     */
    static String trimClause(String clause, String keyword) {
        String text = StringUtils.trimToNull(clause);
        if (text == null) {
            return null;
        }
        text = text.replaceFirst("(?is)^" + keyword + "\\s+", "").replaceAll(";+\\s*$", "").trim();
        if (hasStatementSeparator(text)) {
            throw new IllegalArgumentException("A clause must not contain ';': " + text);
        }
        return text.isEmpty() ? null : text;
    }

    /**
     * Semicolons outside quoted text and identifiers
     */
    private static boolean hasStatementSeparator(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == ';') {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        if (!columns.isEmpty()) {
            text.append("SELECT ").append(String.join(", ", columns));
        }
        if (where != null) {
            text.append(text.length() > 0 ? " " : "").append("WHERE ").append(where);
        }
        if (!groupBy.isEmpty()) {
            text.append(text.length() > 0 ? " " : "").append("GROUP BY ").append(String.join(", ", groupBy));
        }
        if (orderBy != null) {
            text.append(text.length() > 0 ? " " : "").append("ORDER BY ").append(orderBy);
        }
        return text.toString();
    }
}
