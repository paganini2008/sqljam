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
package com.github.sqljam.face.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Description: SqlResult is the result of a statement of the SQL editor: the first rows of a query for viewing,
 *               or the rows changed by another statement
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Getter
@AllArgsConstructor
public class SqlResult {

    private final String sql;
    /**
     * Columns of a query, null for other statements
     */
    private final List<String> columns;
    private final List<List<String>> rows;
    /**
     * Whether the query has more rows than are shown
     */
    private final boolean truncated;
    /**
     * Rows changed by a statement which is not a query, -1 if it is unknown
     */
    private final long updateCount;
    private final long elapsedMillis;

    public boolean isQuery() {
        return columns != null;
    }
}
