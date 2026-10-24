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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: SQLServerVdbDialect writes into SQL Server 2025 and later: VECTOR columns of 32-bit floats (1998
 *               dimensions at most) and of 16-bit floats (3996), upserts by MERGE. Vector indexes of SQL Server are
 *               a preview, none are created. Rules are copied from SQLServerDialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class SQLServerVdbDialect extends VdbDialect {

    static final int MAX_VECTOR_DIMENSIONS = 1998;
    static final int MAX_HALF_VECTOR_DIMENSIONS = 3996;
    static final int MAX_NVARCHAR_LENGTH = 4000;

    @Override
    public String getName() {
        return "SQL Server";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        if (probe(connection, "SELECT CAST(CAST('[1]' AS VECTOR(1)) AS NVARCHAR(MAX))")) {
            features.add(VdbFeature.VECTOR);
            if (probe(connection, "SELECT CAST(CAST('[1]' AS VECTOR(1, float16)) AS NVARCHAR(MAX))")) {
                features.add(VdbFeature.HALF_VECTOR);
            }
        }
    }

    @Override
    protected String getOpenQuote() {
        return "[";
    }

    @Override
    protected String getCloseQuote() {
        return "]";
    }

    @Override
    protected String getScalarTypeName(VdbField field) {
        switch (field.getKind()) {
            case BOOLEAN:
                return "BIT";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INT";
            case BIGINT:
                return "BIGINT";
            case REAL:
                return "REAL";
            case DOUBLE:
                return "FLOAT";
            case VARCHAR:
                return field.getSize() > 0 && field.getSize() <= MAX_NVARCHAR_LENGTH ? "NVARCHAR(" + field.getSize()
                        + ")" : "NVARCHAR(MAX)";
            case TIMESTAMP:
                return "DATETIMEOFFSET(6)";
            case BINARY:
                return "VARBINARY(MAX)";
            default:
                return "NVARCHAR(MAX)";
        }
    }

    @Override
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        int dimensions = vectorType.getDimensions();
        if (!hasFeature(VdbFeature.VECTOR) || vectorType.isSparse() || !vectorType.isFloat32Compatible()
                || dimensions < 1) {
            return null;
        }
        if (vectorType.getElement() == VectorType.Element.FLOAT16 && hasFeature(VdbFeature.HALF_VECTOR)
                && dimensions <= MAX_HALF_VECTOR_DIMENSIONS) {
            return "VECTOR(" + dimensions + ", float16)";
        }
        // 16-bit floats are kept by 32-bit floats
        return dimensions <= MAX_VECTOR_DIMENSIONS ? "VECTOR(" + dimensions + ")" : null;
    }

    /**
     * Keys of NVARCHAR(512) are longer than 900 bytes of a clustered index, a nonclustered key has 1700 bytes
     */
    @Override
    public String getPrimaryKeyDefinition(String tableName, List<String> keyColumnNames) {
        return String.format("CONSTRAINT %s PRIMARY KEY NONCLUSTERED (%s)", getIdentifier("pk_" + tableName),
                getIdentifiers(keyColumnNames));
    }

    /**
     * Comments are extended properties, they are left out
     */
    @Override
    public String getTableCommentStatement(String schema, String tableName, String comment) {
        return null;
    }

    @Override
    public String getColumnCommentStatement(String schema, String tableName, String columnName, String comment) {
        return null;
    }

    @Override
    public String getUpsertStatement(String schema, String tableName, List<Column> columns,
                                     List<String> keyColumnNames) {
        String source = "(VALUES (" + columns.stream().map(this::getParameterMarker).collect(Collectors.joining(
                ", ")) + ")) AS src (" + getIdentifiers(names(columns)) + ")";
        return getMergeStatement(schema, tableName, columns, keyColumnNames, source, ";");
    }
}
