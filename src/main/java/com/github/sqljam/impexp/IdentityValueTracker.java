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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.sqljam.utils.MapUtils;

/**
 * @Description: IdentityValueTracker keeps the max value of identity (auto increment) columns while exporting rows,
 *               so that the identity can be reset after rows imported with explicit values.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class IdentityValueTracker {

    private final Map<TableMetaData, Map<String, Long>> maxValues = new HashMap<>();

    public void track(TableMetaData tableMetaData, List<String> columnNames, List<Map<String, Object>> rows) {
        if (columnNames.isEmpty()) {
            return;
        }
        Map<String, Long> values = MapUtils.getOrCreate(maxValues, tableMetaData, LinkedHashMap::new);
        for (Map<String, Object> row : rows) {
            for (String columnName : columnNames) {
                Object value = row.get(columnName);
                if (value instanceof Number) {
                    long longValue = ((Number) value).longValue();
                    values.merge(columnName, longValue, Math::max);
                }
            }
        }
    }

    public Map<String, Long> get(TableMetaData tableMetaData) {
        return maxValues.get(tableMetaData);
    }

    public Map<String, Long> remove(TableMetaData tableMetaData) {
        return maxValues.remove(tableMetaData);
    }
}
