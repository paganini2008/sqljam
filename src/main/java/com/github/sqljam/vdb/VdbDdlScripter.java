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

/**
 * @Description: VdbDdlScripter collects the statements of the tables of collections and puts them in their order:
 *               drops, CREATE TABLE of the columns and the primary key, comments, then the vector indexes, which
 *               are created after the rows are written. Like DdlScripter of the jdbc line.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbDdlScripter {

    private final Map<String, Table> tables = new LinkedHashMap<>();

    public Table getTable(String schemaName, String tableName) {
        return tables.computeIfAbsent(schemaName + "." + tableName, key -> new Table());
    }

    public List<Table> getTables() {
        return new ArrayList<>(tables.values());
    }

    public static class Table {

        private final List<String> beforeStatements = new ArrayList<>();
        private final List<String> createTableStatements = new ArrayList<>();
        private final List<String> columnStatements = new ArrayList<>();
        private final List<String> primaryKeyStatements = new ArrayList<>();
        private final List<String> tableOptions = new ArrayList<>();
        private final List<String> commentStatements = new ArrayList<>();
        private final List<String> indexStatements = new ArrayList<>();

        public List<String> getBeforeStatements() {
            return beforeStatements;
        }

        public List<String> getCreateTableStatements() {
            return createTableStatements;
        }

        public List<String> getColumnStatements() {
            return columnStatements;
        }

        public List<String> getPrimaryKeyStatements() {
            return primaryKeyStatements;
        }

        public List<String> getTableOptions() {
            return tableOptions;
        }

        public List<String> getCommentStatements() {
            return commentStatements;
        }

        public List<String> getIndexStatements() {
            return indexStatements;
        }

        /**
         * CREATE TABLE of the columns and the primary key, with its options
         */
        public String getCreateTableScript() {
            if (createTableStatements.isEmpty()) {
                return null;
            }
            List<String> definitions = new ArrayList<>(columnStatements);
            definitions.addAll(primaryKeyStatements);
            return createTableStatements.get(0) + " (\n    " + String.join(",\n    ", definitions) + "\n)"
                    + String.join("", tableOptions);
        }

        /**
         * Statements which create the table: drops, CREATE TABLE and comments
         */
        public List<String> getCreateScripts() {
            List<String> scripts = new ArrayList<>(beforeStatements);
            String createTable = getCreateTableScript();
            if (createTable != null) {
                scripts.add(createTable);
            }
            scripts.addAll(commentStatements);
            return scripts;
        }

        /**
         * Statements of the vector indexes, after the rows
         */
        public List<String> getIndexScripts() {
            return new ArrayList<>(indexStatements);
        }

        /**
         * All statements of the table in their order, e.g. to show them
         */
        public List<String> getScripts() {
            List<String> scripts = getCreateScripts();
            scripts.addAll(indexStatements);
            return scripts;
        }
    }
}
