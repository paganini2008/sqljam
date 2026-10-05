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
import lombok.Getter;

/**
 * @Description: DbNode is a node of the connection tree: connection, catalog, schema or table
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

        MESSAGE
    }

    private final Kind kind;
    private final ConnectionProfile profile;
    private final String catalog;
    private final String schema;
    private final TableInfo table;
    private final String label;

    private DbNode(Kind kind, ConnectionProfile profile, String catalog, String schema, TableInfo table,
                   String label) {
        this.kind = kind;
        this.profile = profile;
        this.catalog = catalog;
        this.schema = schema;
        this.table = table;
        this.label = label;
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

    public boolean isContainer() {
        return kind == Kind.CONNECTION || kind == Kind.CATALOG || kind == Kind.SCHEMA;
    }

    @Override
    public String toString() {
        return label;
    }
}
