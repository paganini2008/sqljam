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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;

/**
 * @Description: VdbCollection is a collection of a vector database (a collection of Milvus and Qdrant, a class of
 *               Weaviate, an index of Pinecone, Elasticsearch and OpenSearch) as a table: its fields in the order of
 *               the columns, the id first, then the vectors, the scalars of the payload and the whole payload.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
public class VdbCollection {

    private final String name;
    private final List<VdbField> fields = new ArrayList<>();
    /**
     * Records of the collection, -1 if it is not known
     */
    private long count = -1;
    private String remarks;
    /**
     * Details of the database for reading the collection, e.g. the host of a Pinecone index
     */
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public VdbCollection(String name) {
        this.name = name;
    }

    public VdbCollection addField(VdbField field) {
        fields.add(field);
        return this;
    }

    public Optional<VdbField> findField(String name) {
        return fields.stream().filter(field -> field.getName().equalsIgnoreCase(name)).findFirst();
    }

    public List<VdbField> getPrimaryKeyFields() {
        return fields.stream().filter(VdbField::isPrimaryKey).collect(Collectors.toList());
    }

    public List<VdbField> getVectorFields() {
        return fields.stream().filter(VdbField::isVector).collect(Collectors.toList());
    }

    /**
     * A column name which is not used by a field yet: the name, else the name with a suffix (_2, _3 ...)
     */
    public String getUnusedName(String name) {
        String candidate = name;
        for (int i = 2; findField(candidate).isPresent(); i++) {
            candidate = name + "_" + i;
        }
        return candidate;
    }
}
