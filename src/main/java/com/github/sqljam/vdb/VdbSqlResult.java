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
package com.github.sqljam.vdb;

import java.util.Collections;
import java.util.List;

/**
 * @Description: VdbSqlResult is a page of the rows of a SQL query of a vector database (the SQL of Elasticsearch
 *               and OpenSearch): the columns with the types of the server, the rows with values of JSON (strings,
 *               numbers, booleans, null, arrays and objects as JSON text) and the cursor of the next page, null on
 *               the last page
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public class VdbSqlResult {

    private final List<String[]> columns;
    private final List<List<Object>> rows;
    private final String cursor;

    public VdbSqlResult(List<String[]> columns, List<List<Object>> rows, String cursor) {
        this.columns = columns != null ? columns : Collections.emptyList();
        this.rows = rows != null ? rows : Collections.emptyList();
        this.cursor = cursor;
    }

    /**
     * Name and type of each column as the server tells them, e.g. keyword, integer, datetime. The next pages of a
     * cursor have none, the columns of the first page apply
     */
    public List<String[]> getColumns() {
        return columns;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    /**
     * Cursor of the next page, null when the rows are all read
     */
    public String getCursor() {
        return cursor;
    }

    public boolean hasMore() {
        return cursor != null && !cursor.isEmpty();
    }
}
