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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbCollectionMetaData is a collection as a table of the target: its columns of the fields (named
 *               uniquely in the target), its primary key, the vector indexes of its vector columns and its comments,
 *               like TableMetaData of the jdbc line
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbCollectionMetaData implements VdbMetaData {

    private final VdbCollection collection;
    private final String tableName;
    private final String schemaName;
    private final VdbExportConfiguration configuration;
    private final VdbServerMetaData serverMetaData;
    private final List<VdbDialect.Column> columns;

    public VdbCollectionMetaData(VdbCollection collection, String tableName, String schemaName,
                                 VdbExportConfiguration configuration, VdbServerMetaData serverMetaData) {
        this.collection = collection;
        this.tableName = StringUtils.isNotBlank(tableName) ? tableName.trim() : getDefaultTableName(collection
                .getName());
        this.schemaName = StringUtils.trimToNull(schemaName);
        this.configuration = configuration;
        this.serverMetaData = serverMetaData;
        this.columns = Collections.unmodifiableList(createColumns());
    }

    /**
     * Name of the table of a collection: characters other than letters, digits and underscores are underscores,
     * e.g. sjv-doc of Pinecone is sjv_doc
     */
    public static String getDefaultTableName(String collection) {
        String name = collection.replaceAll("[^\\p{L}\\p{N}_]", "_");
        return Character.isDigit(name.charAt(0)) ? "t_" + name : name;
    }

    private List<VdbDialect.Column> createColumns() {
        VdbDialect dialect = getDialect();
        Set<String> used = new HashSet<>();
        List<VdbDialect.Column> list = new ArrayList<>();
        for (VdbField field : collection.getFields()) {
            String name = field.getName();
            // Names of the target are compared without their cases, e.g. Title and title of PostgreSQL
            String unique = name;
            for (int i = 2; !used.add(key(dialect, unique)); i++) {
                unique = name + "_" + i;
            }
            list.add(new VdbDialect.Column(unique, field, dialect.getTypeName(field)));
        }
        return list;
    }

    private static String key(VdbDialect dialect, String name) {
        return dialect.getLimitedIdentifier(dialect.foldIdentifier(name)).toLowerCase(Locale.ENGLISH);
    }

    public VdbCollection getCollection() {
        return collection;
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public VdbServerMetaData getServerMetaData() {
        return serverMetaData;
    }

    public List<VdbDialect.Column> getColumns() {
        return columns;
    }

    public List<String> getPrimaryKeyColumnNames() {
        return columns.stream().filter(column -> column.getField().isPrimaryKey()).map(VdbDialect.Column::getName)
                .collect(Collectors.toList());
    }

    @Override
    public VdbDialect getDialect() {
        return serverMetaData.getDialect();
    }

    @Override
    public void accept(VdbMetaDataVisitor visitor) throws SQLException {
        for (VdbDialect.Column column : columns) {
            new VdbFieldMetaData(column, this).accept(visitor);
        }
        if (!getPrimaryKeyColumnNames().isEmpty()) {
            new VdbPrimaryKeyMetaData(this).accept(visitor);
        }
        if (configuration.isVectorIndexCreated() && getDialect().isVectorIndexAllowed(columns.stream()
                .filter(column -> column.getField().isPrimaryKey()).collect(Collectors.toList()))) {
            // Vectors of the first indexes are indexed when the target has fewer indexes of a table
            int indexes = 0;
            for (VdbDialect.Column column : columns) {
                if (column.getField().getVectorIndex() != null && getDialect().isVectorTypeName(column.getField(),
                        column.getTypeName())) {
                    VdbIndexMetaData indexMetaData = new VdbIndexMetaData(column, this);
                    if (indexMetaData.getStatements().length > 0 && indexes++ >= getDialect().getMaxVectorIndexes()) {
                        break;
                    }
                    indexMetaData.accept(visitor);
                }
            }
        }
        if (configuration.isCommentCreated()) {
            new VdbCommentMetaData(this).accept(visitor);
        }
        visitor.visit(this);
    }

    @Override
    public String[] getStatements() {
        return new String[]{"CREATE TABLE " + getDialect().getQualifiedTableName(schemaName, tableName)};
    }
}
