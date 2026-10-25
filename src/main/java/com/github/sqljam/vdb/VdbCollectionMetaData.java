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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        Map<String, VdbFieldMapping> mappings = getFieldMappings();
        checkFieldMappings(mappings);
        Set<String> used = new HashSet<>();
        // Names which the user chose are kept, other names give way to them
        mappings.values().stream().filter(mapping -> !mapping.isSkipped() && mapping.getColumnName() != null)
                .forEach(mapping -> used.add(key(dialect, mapping.getColumnName())));
        List<VdbDialect.Column> list = new ArrayList<>();
        for (VdbField field : collection.getFields()) {
            VdbFieldMapping mapping = mappings.get(field.getName());
            if (mapping != null && mapping.isSkipped()) {
                continue;
            }
            String unique;
            if (mapping != null && mapping.getColumnName() != null) {
                unique = mapping.getColumnName();
            } else {
                String name = field.getName();
                // Names of the target are compared without their cases, e.g. Title and title of PostgreSQL
                unique = name;
                for (int i = 2; !used.add(key(dialect, unique)); i++) {
                    unique = name + "_" + i;
                }
            }
            String typeName = mapping != null && mapping.getTypeName() != null ? mapping.getTypeName()
                    : dialect.getTypeName(field);
            list.add(new VdbDialect.Column(unique, field, typeName));
        }
        return list;
    }

    /**
     * Fields of the collection which the user mapped, none for an import as it is
     */
    private Map<String, VdbFieldMapping> getFieldMappings() {
        if (configuration == null) {
            return Collections.emptyMap();
        }
        return configuration.getMappings().stream().filter(mapping -> mapping.getCollection().equals(collection
                .getName())).findFirst().map(VdbExportConfiguration.Mapping::getFields).orElse(Collections
                .emptyMap());
    }

    /**
     * Mappings which can not be imported are refused before a table is created: a field which the collection does
     * not have, a skipped id, and two fields with the same column
     */
    private void checkFieldMappings(Map<String, VdbFieldMapping> mappings) {
        if (mappings.isEmpty()) {
            return;
        }
        VdbDialect dialect = getDialect();
        Set<String> names = new HashSet<>();
        collection.getFields().forEach(field -> names.add(field.getName()));
        Map<String, String> columns = new HashMap<>();
        for (Map.Entry<String, VdbFieldMapping> entry : mappings.entrySet()) {
            if (!names.contains(entry.getKey())) {
                throw new VdbException(String.format("Field %s of the mapping is not a field of collection %s",
                        entry.getKey(), collection.getName()));
            }
            VdbFieldMapping mapping = entry.getValue();
            if (mapping.isSkipped() && collection.findField(entry.getKey()).map(VdbField::isPrimaryKey)
                    .orElse(false)) {
                throw new VdbException(String.format("Field %s is the id of collection %s, it can not be skipped",
                        entry.getKey(), collection.getName()));
            }
            if (!mapping.isSkipped() && mapping.getColumnName() != null) {
                String other = columns.putIfAbsent(key(dialect, mapping.getColumnName()), entry.getKey());
                if (other != null) {
                    throw new VdbException(String.format("Fields %s and %s of collection %s are both mapped to %s",
                            other, entry.getKey(), collection.getName(), mapping.getColumnName()));
                }
            }
        }
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
