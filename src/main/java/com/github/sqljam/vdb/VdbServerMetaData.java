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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbServerMetaData is the root of the metadata tree of the vdb line: the vector database and the
 *               collections which are imported, each described once by the client
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbServerMetaData implements VdbMetaData {

    private final VdbClient client;
    private final VdbDialect dialect;
    private final VdbExportConfiguration configuration;
    private List<VdbCollectionMetaData> collectionMetaData;

    public VdbServerMetaData(VdbClient client, VdbDialect dialect, VdbExportConfiguration configuration) {
        this.client = client;
        this.dialect = dialect;
        this.configuration = configuration;
    }

    public VdbClient getClient() {
        return client;
    }

    @Override
    public VdbDialect getDialect() {
        return dialect;
    }

    /**
     * Collections of the configuration, in its order
     */
    public List<VdbCollectionMetaData> getCollectionMetaData() {
        if (collectionMetaData == null) {
            List<VdbCollectionMetaData> list = new ArrayList<>();
            for (VdbExportConfiguration.Mapping mapping : configuration.getMappings()) {
                list.add(new VdbCollectionMetaData(client.describe(mapping.getCollection()), mapping.getTableName(),
                        configuration.getTargetSchema(), configuration, this));
            }
            collectionMetaData = Collections.unmodifiableList(list);
        }
        return collectionMetaData;
    }

    @Override
    public void accept(VdbMetaDataVisitor visitor) throws SQLException {
        for (VdbCollectionMetaData metaData : getCollectionMetaData()) {
            metaData.accept(visitor);
        }
        visitor.visit(this);
    }

    @Override
    public String[] getStatements() {
        return new String[0];
    }
}
