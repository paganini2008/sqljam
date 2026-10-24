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
 * @Description: VdbPrimaryKeyMetaData is the primary key of the table of a collection, its id and the columns of
 *               its partitions (tenants of Weaviate, namespaces of Pinecone), like PrimaryKeyMetaData of the jdbc
 *               line. Targets without constraints (ClickHouse) order the table by the key instead.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbPrimaryKeyMetaData implements VdbMetaData {

    private final VdbCollectionMetaData collectionMetaData;

    public VdbPrimaryKeyMetaData(VdbCollectionMetaData collectionMetaData) {
        this.collectionMetaData = collectionMetaData;
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
        String definition = getDialect().getPrimaryKeyDefinition(collectionMetaData.getTableName(),
                collectionMetaData.getPrimaryKeyColumnNames());
        return definition != null ? new String[]{definition} : new String[0];
    }
}
