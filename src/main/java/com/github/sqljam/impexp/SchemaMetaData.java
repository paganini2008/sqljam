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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SchemaMetaData is a schema of a catalog, it loads the included tables and the sequences used by them
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class SchemaMetaData implements TiedMetaData {

    private final String schemaName;
    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;

    public SchemaMetaData(String schemaName, Map<String, Object> detail, TiedMetaData tiedMetaData) {
        this.schemaName = schemaName;
        this.detail = detail;
        this.tiedMetaData = tiedMetaData;
    }

    private final List<TableMetaData> tableMetaDatas = new ArrayList<>();
    private final List<SequenceMetaData> sequenceMetaDatas = new ArrayList<>();

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        if (log.isInfoEnabled()) {
            log.info("Begin to process schema: {}", schemaName);
        }

        visitor.visit(this);

        Exporter.ExportConfiguration configuration = visitor.getConfiguration();
        List<Map<String, Object>> tableInfos = getMetaDataOperations().getTableInfos(getMetaData(), getCatalogName(),
                schemaName);
        for (Map<String, Object> tableInfo : tableInfos) {
            String tableName = (String) tableInfo.get("TABLE_NAME");
            if (StringUtils.isNotBlank(configuration.getIncludedTableNamePattern()) &&
                    !tableName.matches(configuration.getIncludedTableNamePattern())) {
                continue;
            } 
            if (ArrayUtils.isEmpty(configuration.getIncludedTableNames())
                    || ArrayUtils.contains(configuration.getIncludedTableNames(), tableName)) {
                tableMetaDatas.add(new TableMetaData(tableName, tableInfo, this));
            }
        }
        for (TableMetaData tableMetaData : tableMetaDatas) {
            tableMetaData.accept(visitor);
        }
        if (configuration.isSequenceIncluded()) {
            boolean allTables = ArrayUtils.isEmpty(configuration.getIncludedTableNames())
                    && StringUtils.isBlank(configuration.getIncludedTableNamePattern());
            for (Map<String, Object> sequenceInfo : getMetaDataOperations().getSequenceInfos(getMetaData(),
                    getCatalogName(), schemaName)) {
                String sequenceName = (String) sequenceInfo.get("SEQUENCE_NAME");
                if (allTables || isSequenceReferenced(sequenceName, sequenceInfo)) {
                    sequenceMetaDatas.add(new SequenceMetaData(sequenceName, sequenceInfo, this));
                }
            }
            for (SequenceMetaData sequenceMetaData : sequenceMetaDatas) {
                sequenceMetaData.accept(visitor);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("End to process schema: {}", schemaName);
        }
    }

    @Override
    public String getCatalogName() {
        return tiedMetaData.getCatalogName();
    }

    @Override
    public String getSchemaName() {
        return schemaName;
    }

    @Override
    public <T extends TiedMetaData> T unwrap(Class<T> clz) {
        try {
            return clz.cast(tiedMetaData);
        } catch (RuntimeException e) {
            return tiedMetaData.unwrap(clz);
        }
    }

    /**
     * Whether the sequence is used by the exported tables, by column default values or triggers
     */
    private boolean isSequenceReferenced(String sequenceName, Map<String, Object> sequenceInfo) {
        String[] referencedTableNames = (String[]) sequenceInfo.get("REFERENCED_TABLE_NAMES");
        if (ArrayUtils.isNotEmpty(referencedTableNames)) {
            for (String tableName : referencedTableNames) {
                if (findTableMetaData(tableName).isPresent()) {
                    return true;
                }
            }
        }
        String lowerSequenceName = sequenceName.toLowerCase();
        return tableMetaDatas.stream().flatMap(md -> md.getColumnMetaDatas().stream())
                .map(md -> StringUtils.defaultString((String) md.getDetail().get("COLUMN_DEF")).toLowerCase())
                .anyMatch(def -> def.matches("(?s).*\\b" + java.util.regex.Pattern.quote(lowerSequenceName)
                        + "\\b.*"));
    }

    public List<SequenceMetaData> getSequenceMetaDatas() {
        return sequenceMetaDatas;
    }

    public List<TableMetaData> getTableMetaDatas() {
        return tableMetaDatas;
    }

    public Optional<TableMetaData> findTableMetaData(String tableName) {
        return tableMetaDatas.stream().filter(md -> md.getTableName().equals(tableName)).findFirst();
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

    @Override
    public String[] getStatements() throws SQLException {
        if (StringUtils.isNotBlank(schemaName)
                && !StringUtils.equalsIgnoreCase(schemaName, getDialect().getDefaultSchemaName(getCatalogName()))) {
            List<String> sqls = new ArrayList<>();
            String catalogName = getCatalogName();
            String schemaName = getSchemaName();
            String username = getMetaData().getUserName();
            String statement = getDialect().getCreateSchemaStatement(catalogName, schemaName, username);
            sqls.add(statement);
            String[] after = getDialect().getStatementAfterSchemaCreated(catalogName, schemaName, username);
            if (ArrayUtils.isNotEmpty(after)) {
                sqls.addAll(Arrays.asList(after));
            }
            return sqls.toArray(new String[0]);
        }
        return null;
    }
}