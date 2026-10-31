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
package com.github.sqljam.face.view;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.impexp.DbCategory;
import lombok.Getter;

/**
 * @Description: DbNode is a node of the connection tree: a group of the data sources of a category (relational,
 *               OLAP) or of an extension (vector databases), a connection, catalog, schema or table, or a source of
 *               an extension
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Getter
public class DbNode {

    public enum Kind {

        ROOT,

        CONNECTION,

        CATALOG,

        SCHEMA,

        TABLE,

        LOADING,

        MESSAGE,

        /**
         * A data source which could not be connected, its full error is the detail
         */
        ERROR,

        /**
         * Group of the sources of an extension, e.g. Vector databases
         */
        GROUP,

        /**
         * A source of an extension, e.g. a vector database
         */
        SOURCE,

        /**
         * An item of a source of an extension, e.g. a collection of a vector database
         */
        SOURCE_ITEM
    }

    private final Kind kind;
    private final ConnectionProfile profile;
    private final String catalog;
    private final String schema;
    private final TableInfo table;
    private final String label;
    private final DataSourceExtension extension;
    private final DataSourceExtension.Source source;
    /**
     * Category of a group of relational or OLAP data sources
     */
    private final DbCategory category;
    /**
     * Item of a source of an extension: a database, a collection or a partition
     */
    private final SourceItem item;
    /**
     * Full text of an error, the label is its summary
     */
    private String detail;

    private DbNode(Kind kind, ConnectionProfile profile, String catalog, String schema, TableInfo table,
                   String label) {
        this(kind, profile, catalog, schema, table, label, null, null, null, null);
    }

    private DbNode(Kind kind, ConnectionProfile profile, String catalog, String schema, TableInfo table,
                   String label, DataSourceExtension extension, DataSourceExtension.Source source,
                   DbCategory category, SourceItem item) {
        this.kind = kind;
        this.profile = profile;
        this.catalog = catalog;
        this.schema = schema;
        this.table = table;
        this.label = label;
        this.extension = extension;
        this.source = source;
        this.category = category;
        this.item = item;
    }

    /**
     * Group of the data sources of a category, e.g. Relational databases
     */
    public static DbNode group(DbCategory category) {
        return new DbNode(Kind.GROUP, null, null, null, null, category.getDisplayName(), null, null, category, null);
    }

    public static DbNode group(DataSourceExtension extension) {
        return new DbNode(Kind.GROUP, null, null, null, null, extension.getGroupName(), extension, null, null, null);
    }

    public static DbNode sourceItem(DataSourceExtension extension, DataSourceExtension.Source source,
                                    SourceItem item) {
        return new DbNode(Kind.SOURCE_ITEM, null, null, null, null, item.getName(), extension, source, null, item);
    }

    public static DbNode source(DataSourceExtension extension, DataSourceExtension.Source source) {
        return new DbNode(Kind.SOURCE, null, null, null, null, source.getName(), extension, source, null, null);
    }

    public static DbNode root() {
        return new DbNode(Kind.ROOT, null, null, null, null, "");
    }

    public static DbNode connection(ConnectionProfile profile) {
        return new DbNode(Kind.CONNECTION, profile, null, null, null, profile.getName());
    }

    public static DbNode catalog(ConnectionProfile profile, String catalog) {
        return new DbNode(Kind.CATALOG, profile, catalog, null, null, catalog);
    }

    public static DbNode schema(ConnectionProfile profile, String catalog, String schema) {
        return new DbNode(Kind.SCHEMA, profile, catalog, schema, null, schema);
    }

    public static DbNode table(ConnectionProfile profile, TableInfo table) {
        return new DbNode(Kind.TABLE, profile, table.getCatalog(), table.getSchema(), table, table.getName());
    }

    public static DbNode loading() {
        return new DbNode(Kind.LOADING, null, null, null, null, Messages.get("tree.loading"));
    }

    public static DbNode message(String message) {
        return new DbNode(Kind.MESSAGE, null, null, null, null, message);
    }

    public static DbNode error(String summary, String detail) {
        DbNode node = new DbNode(Kind.ERROR, null, null, null, null, summary);
        node.detail = detail;
        return node;
    }

    public boolean isContainer() {
        return kind == Kind.CONNECTION || kind == Kind.CATALOG || kind == Kind.SCHEMA;
    }

    @Override
    public String toString() {
        return label;
    }
}
