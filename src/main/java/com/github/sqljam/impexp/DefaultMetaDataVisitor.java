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

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DdlScripter.Catalog;
import com.github.sqljam.impexp.DdlScripter.PartitionTable;
import com.github.sqljam.impexp.DdlScripter.Schema;
import com.github.sqljam.impexp.DdlScripter.Table;

/**
 * @Description: DefaultMetaDataVisitor collects statements of metadata into DdlScripter
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public class DefaultMetaDataVisitor implements MetaDataVisitor {

    private final Exporter.ExportConfiguration configuration;
    private final DdlScripter ddlScripter;

    public DefaultMetaDataVisitor(Exporter.ExportConfiguration configuration, DdlScripter ddlScripter) {
        this.configuration = configuration;
        this.ddlScripter = ddlScripter;
    }

    @Override
    public Exporter.ExportConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public DdlScripter getDdlScripter() {
        return ddlScripter;
    }

    private static void addStatements(List<String> target, String[] statements) {
        for (String statement : statements) {
            if (StringUtils.isNotBlank(statement)) {
                target.add(statement);
            }
        }
    }

    @Override
    public void visit(ServerMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements) && configuration.isShowCreateUserSql()) {
            addStatements(getDdlScripter().getBeforeStatements(), statements);
        }
    }

    @Override
    public void visit(CatalogMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements) && configuration.isShowCreateCatalogSql()) {
            Catalog catalog = getDdlScripter().getCatalog(metaData.getCatalogName());
            addStatements(catalog.getCatalogStatements(), statements);
        }
    }

    @Override
    public void visit(SchemaMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements) && configuration.isShowCreateSchemaSql()) {
            Schema schema = getDdlScripter().getSchema(metaData.getCatalogName(), metaData.getSchemaName());
            addStatements(schema.getSchemaStatements(), statements);
        }
    }

    @Override
    public void visit(TableMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getCreateTableStatements(), statements);
            String tableOptions = configuration.getDialect().getTableOptions(metaData.getCatalogName(),
                    metaData.getSchemaName(), configuration.getDialect().getTargetTableName(metaData.getTableName()),
                    metaData.getDetail(),
                    metaData.getPrimaryKeyColumnNames());
            if (StringUtils.isNotBlank(tableOptions)) {
                table.getTableOptions().add(tableOptions);
            }
            if (configuration.isTableRecreated()) {
                String dropStatement = configuration.getDialect().getDropTableStatement(metaData.getCatalogName(),
                        metaData.getSchemaName(),
                        configuration.getDialect().getTargetTableName(metaData.getTableName()));
                if (configuration.getDialect().isForeignKeyOrderRequired()) {
                    // Tables are visited with referenced tables first, they are dropped in the reverse order
                    Schema schema = getDdlScripter().getSchema(metaData.getCatalogName(), metaData.getSchemaName());
                    schema.getBeforeStatements().add(0, dropStatement);
                } else {
                    table.getBeforeStatements().add(0, dropStatement);
                }
            }
        }
    }
    
    @Override
    public void visit(PartitionTableMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            PartitionTable table = getDdlScripter().getPartitionTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getCreateTableStatements(), statements);
            if (configuration.isTableRecreated()) {
                String dropStatement = configuration.getDialect().getDropTableStatement(metaData.getCatalogName(),
                        metaData.getSchemaName(),
                        configuration.getDialect().getTargetTableName(metaData.getTableName()));
                table.getBeforeStatements().add(0, dropStatement);
            }
        }
    }

    @Override
    public void visit(ColumnMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getColumnStatements(), statements);
            // User defined types are created before the tables of the schema, once
            String userTypeStatement = metaData.getUserTypeStatement();
            if (StringUtils.isNotBlank(userTypeStatement)) {
                Schema schema = getDdlScripter().getSchema(metaData.getCatalogName(), metaData.getSchemaName());
                if (!schema.getBeforeStatements().contains(userTypeStatement)) {
                    schema.getBeforeStatements().add(userTypeStatement);
                }
            }
            if (metaData.unwrap(TableMetaData.class).getIncrementalColumnNames(configuration.getDialect())
                    .contains(metaData.getColumnName())) {
                String[] afterStatements = configuration.getDialect().getStatementAfterIncrementalColumnCreated(
                        metaData.getCatalogName(), metaData.getSchemaName(),
                        configuration.getDialect().getTargetTableName(metaData.getTableName()),
                        metaData.getColumnName());
                if (ArrayUtils.isNotEmpty(afterStatements)) {
                    addStatements(table.getAfterStatements(), afterStatements);
                }
            }
        }
    }

    @Override
    public void visit(PrimaryKeyMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            String tableName = metaData.getTableName();
            Optional<TableMetaData> opt = metaData.unwrap(SchemaMetaData.class).findTableMetaData(tableName);
            if(opt.get().isPartitionTable()) {
                PartitionTable table = getDdlScripter().getPartitionTable(metaData.getCatalogName(), metaData.getSchemaName(),
                        metaData.getTableName());
                addStatements(table.getPrimaryKeyStatements(), statements);
            }else {
                Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                        metaData.getTableName());
                addStatements(table.getPrimaryKeyStatements(), statements);
            }
        }
    }

    @Override
    public void visit(PartitionExpressionMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            PartitionTable table = getDdlScripter().getPartitionTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getPartitionStatements(), statements);
        }
    }

    @Override
    public void visit(CommentMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getCommentStatements(), statements);
        }
    }

    @Override
    public void visit(IndexMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getIndexStatements(), statements);
        }
    }

    @Override
    public void visit(CombinedIndexMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getIndexStatements(), statements);
        }
    }

    @Override
    public void visit(PartitionMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                    metaData.getTableName());
            addStatements(table.getPartitionStatements(), statements);
            String[] beforeStatements = configuration.getDialect().getStatementBeforePartitionTableCreated(
                    metaData.getCatalogName(), metaData.getSchemaName(), metaData.getTableName(),
                    metaData.getDetail());
            if (ArrayUtils.isNotEmpty(beforeStatements)) {
                addStatements(table.getBeforeStatements(), beforeStatements);
            }
        }
    }

    @Override
    public void visit(ForeignKeyMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            if (configuration.getDialect().isForeignKeyInline()) {
                Table table = getDdlScripter().getTable(metaData.getCatalogName(), metaData.getSchemaName(),
                        metaData.getTableName());
                addStatements(table.getPrimaryKeyStatements(), statements);
            } else {
                Schema schema = getDdlScripter().getSchema(metaData.getCatalogName(), metaData.getSchemaName());
                addStatements(schema.getConstraintStatements(), statements);
            }
        }
    }

    @Override
    public void visit(SequenceMetaData metaData) throws SQLException {
        String[] statements = metaData.getStatements();
        if (ArrayUtils.isNotEmpty(statements)) {
            Schema schema = getDdlScripter().getSchema(metaData.getCatalogName(), metaData.getSchemaName());
            addStatements(schema.getSequenceStatements(), statements);
        }
    }
}
