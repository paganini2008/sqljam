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
package com.github.sqljam.vdb;

import java.sql.SQLException;

import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbIndexMetaData is the vector index of a vector column, of the kind, the distance and the options
 *               of the index of the collection when the target has them, like IndexMetaData of the jdbc line
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbIndexMetaData implements VdbMetaData {

    private final VdbDialect.Column column;
    private final VdbCollectionMetaData collectionMetaData;

    public VdbIndexMetaData(VdbDialect.Column column, VdbCollectionMetaData collectionMetaData) {
        this.column = column;
        this.collectionMetaData = collectionMetaData;
    }

    public VdbDialect.Column getColumn() {
        return column;
    }

    public VdbCollectionMetaData getCollectionMetaData() {
        return collectionMetaData;
    }

    public String getIndexName() {
        return "idx_" + collectionMetaData.getTableName() + "_" + column.getName();
    }

    @Override
    public VdbDialect getDialect() {
        return collectionMetaData.getDialect();
    }

    @Override
    public void accept(VdbMetaDataVisitor visitor) throws SQLException {
        visitor.visit(this);
    }

    @Override
    public String[] getStatements() {
        VdbField field = column.getField();
        String statement = getDialect().getCreateVectorIndexStatement(collectionMetaData.getSchemaName(),
                collectionMetaData.getTableName(), getIndexName(), column.getName(), column.getTypeName(),
                field.getVectorType(), column.isNullable(), field.getVectorIndex());
        return statement != null ? new String[]{statement} : new String[0];
    }
}
