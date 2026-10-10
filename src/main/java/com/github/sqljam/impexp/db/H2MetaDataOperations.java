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
package com.github.sqljam.impexp.db;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: H2MetaDataOperations reads tables, generated columns and sequences of H2
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class H2MetaDataOperations extends MetaDataOperations {

    /**
     * H2 2.x reports normal tables as 'BASE TABLE'
     */
    @Override
    protected String[] getTableTypes() {
        return new String[]{"BASE TABLE", "TABLE"};
    }

    @Override
    public List<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData, String catalogName)
            throws SQLException {
        return super.getSchemaInfos(databaseMetaData, catalogName).stream()
                .filter(info -> !"INFORMATION_SCHEMA".equalsIgnoreCase((String) info.get("TABLE_SCHEM")))
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getSequenceInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                      String schemaName) throws SQLException {
        String sql = "SELECT SEQUENCE_NAME, BASE_VALUE AS START_VALUE, INCREMENT, MINIMUM_VALUE AS MIN_VALUE,"
                + " MAXIMUM_VALUE AS MAX_VALUE, CYCLE_OPTION, CACHE AS CACHE_SIZE, DATA_TYPE"
                + " FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ? ORDER BY SEQUENCE_NAME";
        List<Map<String, Object>> sequenceInfos;
        try {
            sequenceInfos = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql, new Object[]{schemaName});
        } catch (SQLException e) {
            // H2 1.4 has different columns
            return super.getSequenceInfos(databaseMetaData, catalogName, schemaName);
        }
        for (Map<String, Object> sequenceInfo : sequenceInfos) {
            sequenceInfo.put("CYCLE", "YES".equalsIgnoreCase(String.valueOf(sequenceInfo.get("CYCLE_OPTION"))));
        }
        return sequenceInfos;
    }

    /**
     * ROW(...) types of H2
     */
    static boolean isRowType(String typeName) {
        return StringUtils.startsWithIgnoreCase(StringUtils.trim(typeName), "ROW(");
    }

    @Override
    public List<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData, String catalogName,
                                                    String schemaName, String tableName) throws SQLException {
        List<Map<String, Object>> columnInfos = super.getColumnInfos(databaseMetaData, catalogName, schemaName,
                tableName);
        for (Map<String, Object> columnInfo : columnInfos) {
            if (isRowType((String) columnInfo.get("TYPE_NAME"))) {
                // Values of ROW are text, they are not parameters of other databases
                columnInfo.put("DATA_TYPE", Types.VARCHAR);
                columnInfo.put("COLUMN_SIZE", Integer.MAX_VALUE);
            }
        }
        String sql = "SELECT COLUMN_NAME, GENERATION_EXPRESSION FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND IS_GENERATED = 'ALWAYS'";
        List<Map<String, Object>> generatedColumns;
        try {
            generatedColumns = JdbcUtils.fetchAll(databaseMetaData.getConnection(), sql,
                    new Object[]{schemaName, tableName});
        } catch (SQLException e) {
            return columnInfos;
        }
        for (Map<String, Object> generatedColumn : generatedColumns) {
            for (Map<String, Object> columnInfo : columnInfos) {
                if (generatedColumn.get("COLUMN_NAME").equals(columnInfo.get("COLUMN_NAME"))) {
                    columnInfo.put("IS_GENERATEDCOLUMN", "YES");
                    columnInfo.put("GENERATION_EXPRESSION", generatedColumn.get("GENERATION_EXPRESSION"));
                    columnInfo.put("GENERATION_TYPE", "VIRTUAL");
                    columnInfo.put("COLUMN_DEF", null);
                }
            }
        }
        return columnInfos;
    }
}
