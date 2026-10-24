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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.vdb.VdbDdlScripter.Table;

/**
 * @Description: DefaultVdbMetaDataVisitor collects statements of the metadata of the vdb line into VdbDdlScripter,
 *               like DefaultMetaDataVisitor of the jdbc line
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class DefaultVdbMetaDataVisitor implements VdbMetaDataVisitor {

    private final VdbExportConfiguration configuration;
    private final VdbDdlScripter ddlScripter;

    public DefaultVdbMetaDataVisitor(VdbExportConfiguration configuration, VdbDdlScripter ddlScripter) {
        this.configuration = configuration;
        this.ddlScripter = ddlScripter;
    }

    @Override
    public VdbExportConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public VdbDdlScripter getDdlScripter() {
        return ddlScripter;
    }

    private static void addStatements(List<String> target, String[] statements) {
        for (String statement : statements) {
            if (StringUtils.isNotBlank(statement)) {
                target.add(statement);
            }
        }
    }

    private Table getTable(VdbCollectionMetaData metaData) {
        return ddlScripter.getTable(metaData.getSchemaName(), metaData.getTableName());
    }

    @Override
    public void visit(VdbServerMetaData metaData) {
        // Schemas are of the target connection, they are not created
    }

    @Override
    public void visit(VdbCollectionMetaData metaData) {
        Table table = getTable(metaData);
        addStatements(table.getCreateTableStatements(), metaData.getStatements());
        String tableOptions = metaData.getDialect().getTableOptions(metaData.getPrimaryKeyColumnNames());
        if (StringUtils.isNotBlank(tableOptions)) {
            table.getTableOptions().add(tableOptions);
        }
        if (configuration.isTableRecreated()) {
            table.getBeforeStatements().add(0, metaData.getDialect().getDropTableStatement(metaData.getSchemaName(),
                    metaData.getTableName()));
        }
    }

    @Override
    public void visit(VdbFieldMetaData metaData) {
        addStatements(getTable(metaData.getCollectionMetaData()).getColumnStatements(), metaData.getStatements());
    }

    @Override
    public void visit(VdbPrimaryKeyMetaData metaData) {
        addStatements(getTable(metaData.getCollectionMetaData()).getPrimaryKeyStatements(), metaData.getStatements());
    }

    @Override
    public void visit(VdbIndexMetaData metaData) {
        addStatements(getTable(metaData.getCollectionMetaData()).getIndexStatements(), metaData.getStatements());
    }

    @Override
    public void visit(VdbCommentMetaData metaData) {
        addStatements(getTable(metaData.getCollectionMetaData()).getCommentStatements(), metaData.getStatements());
    }
}
