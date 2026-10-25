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
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Set;

import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: DuckDBVdbDialect writes into DuckDB 1.0 and later: vectors are arrays of a fixed size (FLOAT[n],
 *               DOUBLE[n]), upserts by ON CONFLICT. Indexes of the vss extension are experimental, none are created.
 *               Rules are copied from DuckDBDialect of impexp.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class DuckDBVdbDialect extends VdbDialect {

    @Override
    public String getName() {
        return "DuckDB";
    }

    @Override
    protected void detectFeatures(Connection connection, Set<VdbFeature> features) throws SQLException {
        if (probe(connection, "SELECT [1.0]::FLOAT[1]")) {
            features.add(VdbFeature.VECTOR);
        }
    }

    @Override
    protected String getScalarTypeName(VdbField field) {
        switch (field.getKind()) {
            case BOOLEAN:
                return "BOOLEAN";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case REAL:
                return "REAL";
            case DOUBLE:
                return "DOUBLE";
            case TIMESTAMP:
                return "TIMESTAMPTZ";
            case BINARY:
                return "BLOB";
            default:
                // JSON of the json extension is not loaded offline, it is text
                return "VARCHAR";
        }
    }

    @Override
    public String getVectorTypeName(VectorType vectorType, boolean nullable) {
        if (!hasFeature(VdbFeature.VECTOR) || vectorType.isSparse() || vectorType.getDimensions() < 1
                || vectorType.getElement() == VectorType.Element.BINARY) {
            return null;
        }
        return (vectorType.getElement() == VectorType.Element.FLOAT64 ? "DOUBLE[" : "FLOAT[")
                + vectorType.getDimensions() + "]";
    }

    /**
     * Arrays and times are cast from their text
     */
    @Override
    public String getParameterMarker(Column column) {
        String type = column.getTypeName();
        return type.endsWith("]") || "TIMESTAMPTZ".equals(type) ? "CAST(? AS " + type + ")" : "?";
    }

    @Override
    public Object getJdbcValue(Object value, Column column) {
        Object jdbcValue = super.getJdbcValue(value, column);
        return jdbcValue instanceof OffsetDateTime ? ((OffsetDateTime) jdbcValue).format(OFFSET_TIMESTAMP_FORMAT)
                : jdbcValue;
    }

    @Override
    public int getNullSqlType(Column column) {
        return column.getField().getKind() == VdbField.Kind.TIMESTAMP ? Types.VARCHAR : super.getNullSqlType(column);
    }
}
