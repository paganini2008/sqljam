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
package com.github.sqljam.vdb.dialect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: MySQLVdbDialect writes into MySQL 9.0 and later: VECTOR columns of 32-bit floats, written as
 *               binaries of little endian floats, upserts by ON DUPLICATE KEY UPDATE of a row alias. MySQL has no
 *               vector indexes. Rules are copied from MySQLDialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class MySQLVdbDialect extends VdbDialect {

    static final int MAX_VECTOR_DIMENSIONS = 16383;
    /**
     * Characters of VARCHAR at most, longer text is LONGTEXT
     */
    static final int MAX_VARCHAR_LENGTH = 16383;

    @Override
    public String getName() {
        return "MySQL";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        if (probe(connection, "SELECT " + getVectorTextFunction() + "(" + getTextVectorFunction() + "('[1]'))")) {
            features.add(VdbFeature.VECTOR);
        }
    }

    protected String getVectorTextFunction() {
        return "VECTOR_TO_STRING";
    }

    protected String getTextVectorFunction() {
        return "STRING_TO_VECTOR";
    }

    @Override
    protected String getOpenQuote() {
        return "`";
    }

    @Override
    protected String getCloseQuote() {
        return "`";
    }

    @Override
    public int getMaxIdentifierLength() {
        return 64;
    }

    @Override
    protected boolean isCatalogQualifier() {
        return true;
    }

    @Override
    protected String getScalarTypeName(VdbField field) {
        switch (field.getKind()) {
            case BOOLEAN:
                return "BOOLEAN";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INT";
            case BIGINT:
                return "BIGINT";
            case REAL:
                return "FLOAT";
            case DOUBLE:
                return "DOUBLE";
            case VARCHAR:
                return field.getSize() > 0 && field.getSize() <= MAX_VARCHAR_LENGTH ? "VARCHAR(" + field.getSize()
                        + ")" : "LONGTEXT";
            case TIMESTAMP:
                return "DATETIME(6)";
            case BINARY:
                return "LONGBLOB";
            case JSON:
                return "JSON";
            default:
                return "LONGTEXT";
        }
    }

    @Override
    protected boolean isTimeZoneSupported() {
        return false;
    }

    @Override
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        int dimensions = vectorType.getDimensions();
        if (!hasFeature(VdbFeature.VECTOR) || vectorType.isSparse() || !vectorType.isFloat32Compatible()
                || dimensions < 1 || dimensions > MAX_VECTOR_DIMENSIONS) {
            return null;
        }
        return "VECTOR(" + dimensions + ")";
    }

    /**
     * Vectors are binaries of little endian 32-bit floats
     */
    @Override
    protected Object getVectorValue(VectorType.Values values, VectorType vectorType) {
        float[] floats = values.toFloats();
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : floats) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    @Override
    public String getColumnDefinition(Column column) {
        String remarks = column.getField().getRemarks();
        return super.getColumnDefinition(column) + (StringUtils.isNotBlank(remarks) ? " COMMENT "
                + quoteLiteral(StringUtils.left(remarks, 1024)) : "");
    }

    @Override
    public String getPrimaryKeyDefinition(String tableName, List<String> keyColumnNames) {
        return "PRIMARY KEY (" + getIdentifiers(keyColumnNames) + ")";
    }

    @Override
    public String getTableCommentStatement(String schema, String tableName, String comment) {
        return String.format("ALTER TABLE %s COMMENT = %s", getQualifiedTableName(schema, tableName),
                quoteLiteral(StringUtils.left(comment, 2048)));
    }

    /**
     * Comments of columns are in their definitions
     */
    @Override
    public String getColumnCommentStatement(String schema, String tableName, String columnName, String comment) {
        return null;
    }

    @Override
    protected String quoteLiteralText(String text) {
        return text.replace("\\", "\\\\");
    }

    /**
     * Whether the values of a row are named by an alias (MySQL 8.0.19 and later), VALUES() is deprecated there
     */
    protected boolean isRowAliasSupported() {
        return true;
    }

    @Override
    public String getUpsertStatement(String schema, String tableName, List<Column> columns,
                                     List<String> keyColumnNames) {
        boolean alias = isRowAliasSupported();
        String insert = getInsertStatement(schema, tableName, columns) + (alias ? " AS new_row" : "");
        List<String> updates = updated(columns, keyColumnNames);
        // A row of keys only sets a key to its own value, the existing row is kept
        List<String> updated = updates.isEmpty() ? keyColumnNames.subList(0, 1) : updates;
        return String.format("%s ON DUPLICATE KEY UPDATE %s", insert, updated.stream().map(name -> getIdentifier(name)
                + " = " + (alias ? "new_row." + getIdentifier(name) : "VALUES(" + getIdentifier(name) + ")"))
                .collect(Collectors.joining(", ")));
    }
}
