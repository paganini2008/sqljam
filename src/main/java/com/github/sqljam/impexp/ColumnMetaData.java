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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: ColumnMetaData generates the column definition, identity and generated columns are created by the dialect
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public class ColumnMetaData implements TiedMetaData {

    private final String columnName;
    private final Map<String, Object> detail;
    private final TiedMetaData tiedMetaData;

    private final List<CommentMetaData> commentMetaDatas = new ArrayList<>();

    public ColumnMetaData(String columnName, Map<String, Object> detail, TiedMetaData tiedMetaData) {
        this.columnName = columnName;
        this.detail = detail;
        this.tiedMetaData = tiedMetaData;
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);

        String comment = (String) detail.get("REMARKS");
        Exporter.ExportConfiguration configuration = visitor.getConfiguration();
        if (StringUtils.isNotBlank(comment) && (configuration == null || configuration.isCommentIncluded())) {
            commentMetaDatas.add(new CommentMetaData(columnName, detail, this));
        }
        for (CommentMetaData commentMetaData : commentMetaDatas) {
            commentMetaData.accept(visitor);
        }
    }

    @Override
    public String[] getStatements() throws SQLException {
        Dialect dialect = getDialect();
        String catalogName = getCatalogName();
        String schemaName = getSchemaName();
        String tableName = dialect.getTargetTableName(getTableName());
        int dataType = TableMetaData.getInt(detail, "DATA_TYPE");
        String typeName = getUserTypeName(dialect);
        if (typeName == null) {
            typeName = (String) detail.get("TYPE_NAME");
        }
        int columnSize = TableMetaData.getInt(detail, "COLUMN_SIZE");
        int columnScale = TableMetaData.getInt(detail, "DECIMAL_DIGITS");
        String comment = (String) detail.get("REMARKS");
        String defaultValue = isGenerated() ? null : replaceSchema((String) detail.get("COLUMN_DEF"), dialect);
        boolean nullable = "YES".equalsIgnoreCase((String) detail.get("IS_NULLABLE"));
        String statement;
        String expression = (String) detail.get("GENERATION_EXPRESSION");
        if (isGenerated() && StringUtils.isNotBlank(expression) && !dialect.isCrossDatabase()
                && dialect.isGeneratedColumnSupported()) {
            statement = dialect.getGeneratedColumnStatement(catalogName, schemaName, tableName, columnName, dataType,
                    typeName, columnSize, columnScale, expression,
                    !"VIRTUAL".equalsIgnoreCase((String) detail.get("GENERATION_TYPE")));
        } else if (unwrap(TableMetaData.class).getIncrementalColumnNames(dialect).contains(columnName)) {
            statement = dialect.getIncrementalColumnStatement(catalogName, schemaName, tableName, columnName, dataType,
                    typeName,
                    columnSize, columnScale, defaultValue, nullable);
        } else {
            statement = dialect.getColumnStatement(catalogName, schemaName, tableName, columnName, dataType, typeName,
                    columnSize,
                    columnScale, defaultValue, nullable, comment);
        }
        dialect.registerColumnTypeName(getTableName(), columnName,
                dialect.getColumnTypeName(dataType, typeName, columnSize, columnScale));
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

    @Override
    public <T extends TiedMetaData> T unwrap(Class<T> clz) {
        try {
            return clz.cast(tiedMetaData);
        } catch (RuntimeException e) {
            return tiedMetaData.unwrap(clz);
        }
    }

    public String getColumnName() {
        return columnName;
    }

    /**
     * User defined type (enum, domain) of the column for the same database type, null otherwise
     */
    @SuppressWarnings("unchecked")
    private String getUserTypeName(Dialect dialect) {
        Map<String, Object> userType = (Map<String, Object>) detail.get("USER_TYPE");
        if (userType == null || dialect.isCrossDatabase()) {
            return null;
        }
        return Dialect.VERBATIM_TYPE_PREFIX + dialect.getUserTypeName(getCatalogName(), getSchemaName(),
                (String) userType.get("NAME"));
    }

    /**
     * Statement creating the user defined type of the column for the same database type, null otherwise
     */
    @SuppressWarnings("unchecked")
    public String getUserTypeStatement() {
        Map<String, Object> userType = (Map<String, Object>) detail.get("USER_TYPE");
        Dialect dialect = getDialect();
        if (userType == null || dialect.isCrossDatabase()) {
            return null;
        }
        return dialect.getCreateUserTypeStatement(getCatalogName(), getSchemaName(), userType);
    }

    /**
     * Objects referenced by default values (e.g. sequences) are moved to the target schema
     */
    private String replaceSchema(String defaultValue, Dialect dialect) {
        String schemaName = getSchemaName();
        String targetSchemaName = dialect.getTargetSchemaName(schemaName);
        if (StringUtils.isBlank(defaultValue) || StringUtils.isBlank(schemaName)
                || StringUtils.isBlank(targetSchemaName) || schemaName.equalsIgnoreCase(targetSchemaName)
                || dialect.isCrossDatabase()) {
            return defaultValue;
        }
        String quotedSchema = Pattern.quote(schemaName);
        String replacement = Matcher.quoteReplacement(dialect.quoteIdentifier(targetSchemaName));
        return defaultValue.replaceAll("(?i)(\\[" + quotedSchema + "\\]|\"" + quotedSchema + "\"|`" + quotedSchema
                + "`|\\b" + quotedSchema + "\\b)(?=\\s*\\.)", replacement);
    }

    /**
     * Generated (computed) columns and row version columns, whose values are maintained by database
     */
    public boolean isGenerated() {
        return "YES".equalsIgnoreCase((String) detail.get("IS_GENERATEDCOLUMN"));
    }

    /**
     * Whether the column value is maintained by the target database of the same type: generated columns with
     * expressions and row version columns
     */
    public boolean isMaintainedByDatabase(Dialect dialect) {
        if (!isGenerated() || dialect.isCrossDatabase()) {
            return false;
        }
        return Boolean.TRUE.equals(detail.get("IS_ROWVERSION")) || (dialect.isGeneratedColumnSupported()
                && StringUtils.isNotBlank((String) detail.get("GENERATION_EXPRESSION")));
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
    public Dialect getDialect() {
        return tiedMetaData.getDialect();
    }

    @Override
    public MetaDataOperations getMetaDataOperations() {
        return tiedMetaData.getMetaDataOperations();
    }
}