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
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbCommentMetaData is the comments of the table of a collection and of its columns, of the
 *               descriptions of the collection and of its fields, like CommentMetaData of the jdbc line
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbCommentMetaData implements VdbMetaData {

    private final VdbCollectionMetaData collectionMetaData;

    public VdbCommentMetaData(VdbCollectionMetaData collectionMetaData) {
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
        VdbDialect dialect = getDialect();
        String schema = collectionMetaData.getSchemaName();
        String table = collectionMetaData.getTableName();
        List<String> statements = new ArrayList<>();
        String remarks = collectionMetaData.getCollection().getRemarks();
        if (StringUtils.isNotBlank(remarks)) {
            addStatement(statements, dialect.getTableCommentStatement(schema, table, remarks));
        }
        for (VdbDialect.Column column : collectionMetaData.getColumns()) {
            if (StringUtils.isNotBlank(column.getField().getRemarks())) {
                addStatement(statements, dialect.getColumnCommentStatement(schema, table, column.getName(),
                        column.getField().getRemarks()));
            }
        }
        return statements.toArray(new String[0]);
    }

    private static void addStatement(List<String> statements, String statement) {
        if (statement != null) {
            statements.add(statement);
        }
    }
}
