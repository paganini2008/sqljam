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
import java.util.List;
import java.util.Map;

/**
 * @Description: ForeignKeyMetaData generates a foreign key constraint, which is created after rows imported
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ForeignKeyMetaData implements TiedMetaData {

    private final String foreignKeyName;
    private final List<Map<String, Object>> details;
    private final TiedMetaData tiedMetaData;

    public ForeignKeyMetaData(String foreignKeyName, List<Map<String, Object>> details, TiedMetaData tiedMetaData) {
        this.foreignKeyName = foreignKeyName;
        this.details = details;
        this.tiedMetaData = tiedMetaData;
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

    @Override
    public String[] getStatements() throws SQLException {
        String[] columnNames = details.stream().map(info -> (String) info.get("FKCOLUMN_NAME")).toArray(String[]::new);
        String[] refColumnNames = details.stream().map(info -> (String) info.get("PKCOLUMN_NAME"))
                .toArray(String[]::new);
        String statement = getDialect().getCreateForeignKeyStatement(getCatalogName(), getSchemaName(), getTableName(),
                foreignKeyName, columnNames, getReferencedTableName(), refColumnNames,
                getRuleName(getDetail().get("UPDATE_RULE")), getRuleName(getDetail().get("DELETE_RULE")));
        return new String[]{statement};
    }

    /**
     * importedKeyCascade is 0, so absent rules must not be treated as CASCADE
     */
    static String getRuleName(Object value) {
        if (!(value instanceof Number) && (value == null || !value.toString().trim().matches("\\d+"))) {
            return null;
        }
        int rule = value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString().trim());
        switch (rule) {
            case DatabaseMetaData.importedKeyCascade:
                return "CASCADE";
            case DatabaseMetaData.importedKeySetNull:
                return "SET NULL";
            case DatabaseMetaData.importedKeySetDefault:
                return "SET DEFAULT";
            default:
                return null;
        }
    }

    public String getForeignKeyName() {
        return foreignKeyName;
    }

    public String getReferencedTableName() {
        return (String) getDetail().get("PKTABLE_NAME");
    }

    public String getReferencedSchemaName() {
        return (String) getDetail().get("PKTABLE_SCHEM");
    }

    public List<Map<String, Object>> getDetails() {
        return details;
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
        return details.get(0);
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
