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
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbExportConfiguration tells how collections of a vector database are imported into a target
 *               database: the target dialect and schema, the collections and their tables, the query, whether the
 *               tables are recreated, inserts or upserts, pages, transactions and skipped errors
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
public class VdbExportConfiguration {

    public enum WriteMode {
        INSERT, UPSERT
    }

    /**
     * Collection and the name of its table in the target, the table is named as the collection when it is blank
     */
    @Getter
    public static final class Mapping {

        private final String collection;
        private final String tableName;

        public Mapping(String collection, String tableName) {
            this.collection = collection;
            this.tableName = tableName;
        }
    }

    private VdbDialect dialect;
    /**
     * Schema of the tables (the database of MySQL, MariaDB and ClickHouse), null for the schema of the connection
     */
    private String targetSchema;
    private final List<Mapping> mappings = new ArrayList<>();
    /**
     * Query of the records, all records when it is null. A search for the nearest neighbors names a vector field of
     * each collection.
     */
    private VdbQuery query;
    /**
     * Whether existing tables are dropped and created again, rows are added to existing tables otherwise
     */
    private boolean tableRecreated;
    private WriteMode writeMode = WriteMode.INSERT;
    private boolean vectorIndexCreated = true;
    private boolean commentCreated = true;
    /**
     * Records read and written at once
     */
    private int pageSize = 500;
    /**
     * Whether each page is committed in its transaction, each row is committed otherwise
     */
    private boolean transactional = true;
    /**
     * Whether rows which fail are skipped and reported, the import stops at the first error otherwise
     */
    private boolean errorSkipped;

    public VdbExportConfiguration addCollection(String collection, String tableName) {
        mappings.add(new Mapping(collection, tableName));
        return this;
    }
}
