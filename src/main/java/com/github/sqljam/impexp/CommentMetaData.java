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
import java.util.Map;

/**
 * @Description: CommentMetaData generates comments of a column, or of the table when column name is null
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public class CommentMetaData implements TiedMetaData {

    private final String columnName;
    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;

    public CommentMetaData(String columnName, Map<String, Object> detail, TiedMetaData tiedMetaData) {
        this.columnName = columnName;
        this.detail = detail;
        this.tiedMetaData = tiedMetaData;
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

    @Override
    public String[] getStatements() throws SQLException {
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        String tableName = getTableName();
        String comment = (String) detail.get("REMARKS");
        String statement;
        if (columnName == null) {
            statement = getDialect().getCreateTableCommentStatement(catalogName, schemaName, tableName, comment);
        } else {
            statement = getDialect().getCreateCommentStatement(catalogName, schemaName, tableName, columnName,
                    comment);
        }
        return new String[]{statement};
    }

    @Override
    public String getCatalogName() {
        return tiedMetaData.getCatalogName();
    }

    @Override
    public String getSchemaName() {
        return tiedMetaData.getSchemaName();
    }

    @Override
    public String getTableName() {
        return tiedMetaData.getTableName();
    }

    public String getColumnName() {
        return columnName;
    }

    @Override
    public <T extends TiedMetaData> T unwrap(Class<T> clz) {
        try {
            return clz.cast(tiedMetaData);
        } catch (RuntimeException e) {
            return tiedMetaData.unwrap(clz);
        }
    }

    @Override
    public Map<String, Object> getDetail() {
        return detail;
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return tiedMetaData.getMetaData();
    }

    @Override
    public MetaDataOperations getMetaDataOperations() {
        return tiedMetaData.getMetaDataOperations();
    }

    @Override
    public Dialect getDialect() {
        return tiedMetaData.getDialect();
    }
}