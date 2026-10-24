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
 * @Description: VdbFieldMetaData is a field of a collection as a column of the target, like ColumnMetaData of the
 *               jdbc line
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbFieldMetaData implements VdbMetaData {

    private final VdbDialect.Column column;
    private final VdbCollectionMetaData collectionMetaData;

    public VdbFieldMetaData(VdbDialect.Column column, VdbCollectionMetaData collectionMetaData) {
        this.column = column;
        this.collectionMetaData = collectionMetaData;
    }

    public VdbDialect.Column getColumn() {
        return column;
    }

    public VdbCollectionMetaData getCollectionMetaData() {
        return collectionMetaData;
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
        return new String[]{getDialect().getColumnDefinition(column)};
    }
}
