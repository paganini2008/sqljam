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

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.github.sqljam.jdbc.Cursor;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: MetaDataOperations reads metadata by jdbc DatabaseMetaData, subclasses of each database fix and complete it
 * @Author: Fred Feng
 * @Date: 29/03/2023
 * @Version 1.0.0
 */
public class MetaDataOperations {

    protected String[] getTableTypes() {
        return new String[]{"TABLE"};
    }

    public List<Map<String, Object>> getCatalogInfos(DatabaseMetaData databaseMetaData) throws SQLException {
        Cursor<Map<String, Object>> cursor = JdbcUtils.getCatalogInfos(databaseMetaData);
        return cursor != null ? cursor.list() : Collections.emptyList();
    }

    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        Cursor<Map<String, Object>> cursor = JdbcUtils.getSchemaInfos(databaseMetaData, catalogName);
        return cursor != null ? cursor.list() : Collections.emptyList();
    }

    public List<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData, String catalogName, String schemaName)
            throws SQLException {
        Cursor<Map<String, Object>> cursor = JdbcUtils.getTableInfos(databaseMetaData, catalogName, schemaName,
                getTableTypes());
        return cursor != null ? cursor.list() : Collections.emptyList();
    }

    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName,
                                                    String tableName) throws SQLException {
        Cursor<Map<String, Object>> cursor = JdbcUtils.getColumnInfos(databaseMetaData, catalogName, schemaName, tableName);
        return cursor != null ? cursor.list() : Collections.emptyList();
    }

    public List<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                        String schemaName,
                                                        String tableName) throws SQLException {
        Cursor<Map<String, Object>> cursor = JdbcUtils.getPrimaryKeyInfos(databaseMetaData, catalogName, schemaName,
                tableName);
        return cursor != null ? cursor.list() : Collections.emptyList();
    }

    public List<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                   String schemaName,
                                                   String tableName) throws SQLException {
        Cursor<Map<String, Object>> cursor = JdbcUtils.getIndexInfos(databaseMetaData, catalogName, schemaName,
                tableName);
        List<Map<String, Object>> indexInfos = cursor != null ? cursor.list() : Collections.emptyList();
        // Table statistic rows have no index name
        return indexInfos.stream()
                .filter(info -> info.get("INDEX_NAME") != null && info.get("COLUMN_NAME") != null)
                .filter(info -> TableMetaData.getInt(info, "TYPE") != DatabaseMetaData.tableIndexStatistic)
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                         String schemaName,
                                                         String tableName) throws SQLException {
        Cursor<Map<String, Object>> cursor = JdbcUtils.getImportedKeyInfos(databaseMetaData, catalogName, schemaName,
                tableName);
        return cursor != null ? cursor.list() : Collections.emptyList();
    }
   
    /**
     * Sequences of the schema except those owned by identity/serial columns. Keys of each sequence: SEQUENCE_NAME,
     * START_VALUE (next value), INCREMENT, MIN_VALUE, MAX_VALUE, CYCLE, CACHE_SIZE, DATA_TYPE and optional
     * REFERENCED_TABLE_NAMES (tables using the sequence by triggers).
     */
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        return Collections.emptyList();
    }
}
