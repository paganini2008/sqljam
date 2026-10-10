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

import java.math.BigDecimal;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * @Description: SequenceMetaData generates a sequence used by the exported tables, starting with the next value of the source sequence
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SequenceMetaData implements TiedMetaData {

    private final String sequenceName;
    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;

    public SequenceMetaData(String sequenceName, Map<String, Object> detail, TiedMetaData tiedMetaData) {
        this.sequenceName = sequenceName;
        this.detail = detail;
        this.tiedMetaData = tiedMetaData;
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

    /**
     * The sequence starts with the next value of the source sequence, so that rows inserted later will not conflict
     * with the imported rows.
     */
    @Override
    public String[] getStatements() throws SQLException {
        Dialect dialect = getDialect();
        if (dialect.isTableRenamed()) {
            // Copies of tables use the sequences of the source tables
            return null;
        }
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        long startValue = getLong("START_VALUE", 1L);
        String statement = dialect.getCreateSequenceStatement(catalogName, schemaName, sequenceName, startValue,
                getLong("INCREMENT", 1L), getLong("MIN_VALUE", null), getLong("MAX_VALUE", null),
                Boolean.TRUE.equals(detail.get("CYCLE")), getLong("CACHE_SIZE", null),
                (String) detail.get("DATA_TYPE"));
        if (StringUtils.isBlank(statement)) {
            return null;
        }
        List<String> sqls = new ArrayList<>();
        String[] before = dialect.getStatementBeforeSequenceCreated(catalogName, schemaName, sequenceName);
        if (ArrayUtils.isNotEmpty(before)) {
            sqls.addAll(Arrays.asList(before));
        }
        sqls.add(statement);
        String[] after = dialect.getStatementAfterSequenceCreated(catalogName, schemaName, sequenceName, startValue);
        if (ArrayUtils.isNotEmpty(after)) {
            sqls.addAll(Arrays.asList(after));
        }
        return sqls.toArray(new String[0]);
    }

    /**
     * Values out of range of long (e.g. default max value of Oracle sequence) are treated as absent
     */
    private Long getLong(String key, Long defaultValue) {
        Object value = detail.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            BigDecimal number = value instanceof BigDecimal ? (BigDecimal) value
                    : new BigDecimal(value.toString().trim());
            if (number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                    || number.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) {
                return defaultValue;
            }
            return number.longValue();
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getSequenceName() {
        return sequenceName;
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
