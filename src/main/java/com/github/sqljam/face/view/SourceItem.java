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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @Description: SourceItem is a node of a source of an extension in the connection tree, like a catalog or a table of
 *               a data source: a database (Milvus, Chroma), a collection, or a part of a collection which is read
 *               apart (a partition of Milvus, a tenant of Weaviate, a namespace of Pinecone). Its path is the names
 *               from the source down to it.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class SourceItem {

    public enum Kind {
        DATABASE, COLLECTION, PARTITION
    }

    private final Kind kind;
    private final List<String> path;
    /**
     * Name of the kind of a partition, e.g. Tenant
     */
    private final String kindName;
    private final boolean leaf;

    public SourceItem(Kind kind, List<String> path, String kindName, boolean leaf) {
        this.kind = kind;
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
        this.kindName = kindName;
        this.leaf = leaf;
    }

    /**
     * A child of this item
     */
    public SourceItem child(Kind childKind, String name, String childKindName, boolean childLeaf) {
        List<String> childPath = new ArrayList<>(path);
        childPath.add(name);
        return new SourceItem(childKind, childPath, childKindName, childLeaf);
    }

    public Kind getKind() {
        return kind;
    }

    public List<String> getPath() {
        return path;
    }

    public String getName() {
        return path.get(path.size() - 1);
    }

    public String getKindName() {
        return kindName;
    }

    public boolean isLeaf() {
        return leaf;
    }

    /**
     * Unique key of the item in its source
     */
    public String getKey() {
        return kind + ":" + String.join("/", path);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SourceItem && kind == ((SourceItem) other).kind && path.equals(((SourceItem) other)
                .path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, path);
    }

    @Override
    public String toString() {
        return getName();
    }
}
