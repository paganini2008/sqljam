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
package com.github.sqljam.impexp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.utils.MapUtils;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: DdlScripter holds statements of catalogs, schemas and tables, and formats them as pretty scripts or plain statements
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
@Getter
@Setter
public class DdlScripter {

    private static final String NEWLINE = System.getProperty("line.separator");

    private final Dialect dialect;

    public DdlScripter(Dialect dialect) {
        this.dialect = dialect;
    }

    private List<String> beforeStatements = new ArrayList<>();
    private Map<String, Catalog> catalogs = new LinkedHashMap<>();
    private List<String> afterStatements = new ArrayList<>();

    public Catalog getCatalog(String catalogName) {
        return MapUtils.getOrCreate(catalogs, catalogName, () -> new Catalog(catalogName, dialect));
    }

    public Schema getSchema(String catalogName, String schemaName) {
        Catalog catalog = getCatalog(catalogName);
        return MapUtils.getOrCreate(catalog.getSchemas(), schemaName, () -> new Schema(catalogName, schemaName, dialect));
    }

    public Table getTable(String catalogName, String schemaName, String tableName) {
        Schema schema = getSchema(catalogName, schemaName);
        return MapUtils.getOrCreate(schema.getTables(), tableName,
                () -> new Table(catalogName, schemaName, tableName, dialect));
    }

    public PartitionTable getPartitionTable(String catalogName, String schemaName, String tableName) {
        Schema schema = getSchema(catalogName, schemaName);
        return MapUtils.getOrCreate(schema.getPartitionTables(), tableName,
                () -> new PartitionTable(catalogName, schemaName, tableName, dialect));
    }

    public List<String> getPrettyScripts() {
        List<String> sqls = new ArrayList<>();
        String commentPrefix = getDialect().getScriptCommentPrefix();
        String comment = null;
        if (beforeStatements.size() > 0) {
            comment = String.format("%s To do something before backup operation.", commentPrefix);
            sqls.add(comment);
            sqls.addAll(SqlTextUtils.addEndMarks(beforeStatements));
        }
        for (Map.Entry<String, Catalog> entry : catalogs.entrySet()) {
            sqls.addAll(entry.getValue().getPrettyScripts());
        }
        if (afterStatements.size() > 0) {
            sqls.add(NEWLINE);
            comment = String.format("%s To do something after backup operation.", commentPrefix);
            sqls.add(comment);
            sqls.addAll(SqlTextUtils.addEndMarks(afterStatements));
        }
        return sqls;
    }

    public List<String> getPlainScripts() {
        List<String> sqls = new ArrayList<>();
        sqls.addAll(beforeStatements);
        for (Map.Entry<String, Catalog> entry : catalogs.entrySet()) {
            sqls.addAll(entry.getValue().getPlainScripts());
        }
        sqls.addAll(afterStatements);
        return SqlTextUtils.addEndMarks(sqls);
    }

    /**
     * Constraint statements (foreign keys) which should be executed after data imported
     */
    public List<String> getConstraintScripts() {
        List<String> sqls = new ArrayList<>();
        for (Catalog catalog : catalogs.values()) {
            for (Schema schema : catalog.getSchemas().values()) {
                sqls.addAll(schema.getConstraintStatements());
            }
        }
        return SqlTextUtils.addEndMarks(sqls);
    }

    @Getter
    @Setter
    public static class Catalog {

        private final String catalogName;
        private final Dialect dialect;

        Catalog(String catalogName, Dialect dialect) {
            this.catalogName = catalogName;
            this.dialect = dialect;
        }

        private List<String> beforeStatements = new ArrayList<>();
        private List<String> catalogStatements = new ArrayList<>();
        private List<String> afterStatements = new ArrayList<>();

        private Map<String, Schema> schemas = new LinkedHashMap<>();

        public List<String> getPrettyScripts() {
            List<String> sqls = new ArrayList<>();
            String commentPrefix = getDialect().getScriptCommentPrefix();
            String comment = null;
            if (beforeStatements.size() > 0) {
                comment = String.format("%s To do something before creating catalog: %s", commentPrefix, catalogName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(beforeStatements));
            }

            if (StringUtils.isNotBlank(catalogName) && catalogStatements.size() > 0) {
                sqls.add(NEWLINE);
                sqls.add(commentPrefix);
                comment = String.format("%s Create catalog: %s", commentPrefix, catalogName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(catalogStatements));
            }

            if (afterStatements.size() > 0) {
                sqls.add(NEWLINE);
                comment = String.format("%s To do something after catalog '%s' created", commentPrefix, catalogName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(afterStatements));
            }

            for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
                sqls.addAll(entry.getValue().getPrettyScripts());
            }
            return sqls;
        }

        public List<String> getPlainScripts() {
            List<String> sqls = new ArrayList<>();
            sqls.addAll(beforeStatements);
            sqls.addAll(catalogStatements);
            sqls.addAll(afterStatements);
            for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
                sqls.addAll(entry.getValue().getPlainScripts());
            }
            return SqlTextUtils.addEndMarks(sqls);
        }
    }

    @Getter
    @Setter
    public static class Schema {

        private final String catalogName;
        private final String schemaName;
        private final Dialect dialect;

        Schema(String catalogName, String schemaName, Dialect dialect) {
            this.catalogName = catalogName;
            this.schemaName = schemaName;
            this.dialect = dialect;
        }

        private List<String> beforeStatements = new ArrayList<>();
        private List<String> schemaStatements = new ArrayList<>();
        private List<String> afterStatements = new ArrayList<>();
        private List<String> sequenceStatements = new ArrayList<>();
        private List<String> constraintStatements = new ArrayList<>();

        private Map<String, Table> tables = new LinkedHashMap<>();
        private Map<String, PartitionTable> partitionTables = new LinkedHashMap<>();

        public List<String> getPrettyScripts() {
            List<String> sqls = new ArrayList<>();
            String commentPrefix = getDialect().getScriptCommentPrefix();
            String comment = null;
            if (beforeStatements.size() > 0) {
                comment = String.format("%s To do something before creating schema: %s", commentPrefix, schemaName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(beforeStatements));
            }

            if (StringUtils.isNotBlank(schemaName) && schemaStatements.size() > 0) {
                sqls.add(NEWLINE);
                sqls.add(commentPrefix);
                comment = String.format("%s Create schema: %s", commentPrefix, schemaName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(schemaStatements));
            }

            if (afterStatements.size() > 0) {
                sqls.add(NEWLINE);
                comment = String.format("%s To do something after schema '%s' created", commentPrefix, schemaName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(afterStatements));
            }

            if (sequenceStatements.size() > 0) {
                sqls.add(NEWLINE);
                sqls.add(commentPrefix);
                comment = String.format("%s Create sequences", commentPrefix);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(sequenceStatements));
            }

            for (Map.Entry<String, Table> entry : tables.entrySet()) {
                sqls.addAll(entry.getValue().getPrettyScripts());
                sqls.add(NEWLINE);
            }

            for (Map.Entry<String, PartitionTable> entry : partitionTables.entrySet()) {
                sqls.addAll(entry.getValue().getPrettyScripts());
                sqls.add(NEWLINE);
            }
            return sqls;
        }

        public List<String> getPlainScripts() {
            List<String> sqls = new ArrayList<>();
            sqls.addAll(beforeStatements);
            sqls.addAll(schemaStatements);
            sqls.addAll(afterStatements);
            sqls.addAll(sequenceStatements);
            for (Map.Entry<String, Table> entry : tables.entrySet()) {
                sqls.addAll(entry.getValue().getPlainScripts());
            }
            for (Map.Entry<String, PartitionTable> entry : partitionTables.entrySet()) {
                sqls.addAll(entry.getValue().getPlainScripts());
            }
            return SqlTextUtils.addEndMarks(sqls);
        }
    }

    @Getter
    @Setter
    public static class Table {

        Table(String catalogName, String schemaName, String tableName, Dialect dialect) {
            this.catalogName = catalogName;
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.dialect = dialect;
        }

        private final String catalogName;
        private final String schemaName;
        private final String tableName;
        private final Dialect dialect;

        private List<String> beforeStatements = new ArrayList<>();
        private List<String> createTableStatements = new ArrayList<>();
        private List<String> columnStatements = new ArrayList<>();
        private List<String> primaryKeyStatements = new ArrayList<>();
        private List<String> tableOptions = new ArrayList<>();
        private List<String> commentStatements = new ArrayList<>();
        private List<String> indexStatements = new ArrayList<>();
        private List<String> partitionStatements = new ArrayList<>();
        private List<String> afterStatements = new ArrayList<>();

        public List<String> getPrettyScripts() {

            List<String> sqls = new ArrayList<>();
            String commentPrefix = getDialect().getScriptCommentPrefix();
            String comment = "";

            if (beforeStatements.size() > 0) {
                comment = String.format("%s To do something before creating table: %s", commentPrefix, tableName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(beforeStatements));
            }

            sqls.add(NEWLINE);
            sqls.add(commentPrefix);
            comment = String.format("%s Create table: %s", commentPrefix, tableName);
            sqls.add(comment);
            sqls.add(toPrettySql().trim() + ";");

            if (commentStatements.size() > 0) {
                sqls.add(NEWLINE);
                comment = String.format("%s Create comments for table: %s", commentPrefix, tableName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(commentStatements));
            }

            if (indexStatements.size() > 0) {
                sqls.add(NEWLINE);
                comment = String.format("%s Create indexes for table: %s", commentPrefix, tableName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(indexStatements));
            }

            if (afterStatements.size() > 0) {
                sqls.add(NEWLINE);
                comment = String.format("%s To do something after table '%s' created", commentPrefix, tableName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(afterStatements));
            }
            return sqls;
        }

        public List<String> getPlainScripts() {
            List<String> sqls = new ArrayList<>();
            sqls.addAll(beforeStatements);
            sqls.add(toInlineSql());
            sqls.addAll(commentStatements);
            sqls.addAll(indexStatements);
            sqls.addAll(afterStatements);
            return SqlTextUtils.addEndMarks(sqls);
        }

        private String toInlineSql() {
            StringBuilder sql = new StringBuilder();
            for (String statement : createTableStatements) {
                sql.append(statement);
            }
            sql.append("(");
            sql.append(StringUtils.join(columnStatements, ","));
            if (primaryKeyStatements.size() > 0) {
                sql.append(",");
                sql.append(StringUtils.join(primaryKeyStatements, ","));
            }
            sql.append(")");
            if (tableOptions.size() > 0) {
                sql.append(" ").append(StringUtils.join(tableOptions, " "));
            }
            if (partitionStatements.size() > 0) {
                sql.append(" ").append(StringUtils.join(partitionStatements, " "));
            }
            return sql.toString();
        }

        private String toPrettySql() {
            StringBuilder sql = new StringBuilder();
            for (String statement : createTableStatements) {
                sql.append(statement).append(NEWLINE);
            }
            sql.append("(").append(NEWLINE);
            List<String> definitions = new ArrayList<>(columnStatements);
            definitions.addAll(primaryKeyStatements);
            int i = 0;
            for (String statement : definitions) {
                sql.append("    ").append(statement);
                if (i++ != definitions.size() - 1) {
                    sql.append(",");
                }
                sql.append(NEWLINE);
            }
            sql.append(")");
            for (String statement : tableOptions) {
                sql.append(" ").append(statement);
            }
            if (partitionStatements.size() > 0) {
                for (String statement : partitionStatements) {
                    sql.append(" ").append(statement).append(NEWLINE);
                }
                sql.append(NEWLINE);
            }
            sql.append(NEWLINE);
            return sql.toString();
        }
    }

    @Getter
    @Setter
    public static class PartitionTable {

        private final String catalogName;
        private final String schemaName;
        private final String tableName;
        private final Dialect dialect;

        public PartitionTable(String catalogName, String schemaName, String tableName, Dialect dialect) {
            this.catalogName = catalogName;
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.dialect = dialect;
        }

        private List<String> beforeStatements = new ArrayList<>();
        private List<String> createTableStatements = new ArrayList<>();
        private List<String> primaryKeyStatements = new ArrayList<>();
        private List<String> partitionStatements = new ArrayList<>();
        private List<String> afterStatements = new ArrayList<>();

        public List<String> getPrettyScripts() {
            List<String> sqls = new ArrayList<>();
            String commentPrefix = getDialect().getScriptCommentPrefix();
            String comment = "";

            if (beforeStatements.size() > 0) {
                comment = String.format("%s To do something before creating table: %s", commentPrefix, tableName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(beforeStatements));
            }

            sqls.add(NEWLINE);
            sqls.add(commentPrefix);
            comment = String.format("%s Create table: %s", commentPrefix, tableName);
            sqls.add(comment);
            sqls.add(toPrettySql().trim() + ";");

            if (afterStatements.size() > 0) {
                sqls.add(NEWLINE);
                comment = String.format("%s To do something after table '%s' created", commentPrefix, tableName);
                sqls.add(comment);
                sqls.addAll(SqlTextUtils.addEndMarks(afterStatements));
            }
            return sqls;
        }

        private String toPrettySql() {
            StringBuilder sql = new StringBuilder();
            for (String statement : createTableStatements) {
                sql.append(statement).append(NEWLINE);
            }
            if (primaryKeyStatements.size() > 0) {
                sql.append("(").append(NEWLINE);
                sql.append("    ").append(StringUtils.join(primaryKeyStatements, "," + NEWLINE + "    "));
                sql.append(NEWLINE);
                sql.append(")");
            }
            if (partitionStatements.size() > 0) {
                for (String statement : partitionStatements) {
                    sql.append(" ").append(statement).append(NEWLINE);
                }
                sql.append(NEWLINE);
            }
            sql.append(NEWLINE);
            return sql.toString();
        }

        public List<String> getPlainScripts() {
            List<String> sqls = new ArrayList<>();
            sqls.addAll(beforeStatements);
            sqls.add(toInlineSql());
            sqls.addAll(afterStatements);
            return SqlTextUtils.addEndMarks(sqls);
        }

        private String toInlineSql() {
            StringBuilder sql = new StringBuilder();
            for (String statement : createTableStatements) {
                sql.append(statement);
            }
            if (primaryKeyStatements.size() > 0) {
                sql.append("(");
                sql.append(StringUtils.join(primaryKeyStatements, ","));
                sql.append(")");
            }
            if (partitionStatements.size() > 0) {
                sql.append(" ").append(StringUtils.join(partitionStatements, " "));
            }
            return sql.toString();
        }
    }
}
