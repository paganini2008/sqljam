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
package com.github.sqljam.face.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.DdlScripter;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.ExportHandler;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.TableMetaData;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.page.EachPage;

/**
 * @Description: DdlGenerator generates DDL of a table for previewing, by the same metadata visiting of exporting
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class DdlGenerator {

    private final ConnectionProfile profile;

    public DdlGenerator(ConnectionProfile profile) {
        this.profile = profile;
    }

    /**
     * @param targetDbType database type of the DDL, null means the same as source
     */
    public String generate(String catalog, String schema, String table, DbType targetDbType) throws Exception {
        CapturingHandler handler = new CapturingHandler();
        Exporter exporter = new Exporter(handler);
        Exporter.ExportConfiguration configuration = exporter.getConfiguration();
        configure(configuration, profile, catalog, schema);
        configuration.setIncludedTableNames(new String[]{table});
        configuration.setTableRecreated(false);
        configuration.setConnectionPoolEnabled(false);
        configuration.setShowCreateUserSql(false);
        configuration.setShowCreateCatalogSql(false);
        configuration.setShowCreateSchemaSql(false);
        if (targetDbType != null && targetDbType != profile.getDbType()) {
            Dialect dialect = targetDbType.createDialect();
            dialect.setSourceDbType(profile.getDbType());
            configuration.setDialect(dialect);
        }
        exporter.export(ExportMode.DDL);
        return String.join(System.lineSeparator(), handler.lines).trim();
    }

    /**
     * Source configuration of the selected catalog and schema
     */
    static void configure(Exporter.ExportConfiguration configuration, ConnectionProfile profile, String catalog,
                          String schema) {
        DbType dbType = profile.getDbType();
        configuration.setDbType(dbType);
        configuration.setUrl(profile.getJdbcUrl(dbType == DbType.POSTGRESQL ? catalog : null));
        configuration.setUsername(profile.getUsername());
        configuration.setPassword(profile.getPassword());
        if (StringUtils.isNotBlank(catalog) && dbType.isCatalogSupported()) {
            configuration.setIncludedCatalogNames(new String[]{catalog});
        }
        if (StringUtils.isNotBlank(schema)) {
            configuration.setIncludedSchemaNames(new String[]{schema});
        }
    }

    private static class CapturingHandler implements ExportHandler {

        private final List<String> lines = new ArrayList<>();

        @Override
        public void exportDdl(DdlScripter ddlScripter) {
            for (DdlScripter.Catalog catalog : ddlScripter.getCatalogs().values()) {
                lines.addAll(catalog.getPrettyScripts());
            }
        }

        @Override
        public void exportConstraints(DdlScripter ddlScripter) {
            List<String> constraints = ddlScripter.getConstraintScripts();
            if (!constraints.isEmpty()) {
                lines.add(System.lineSeparator());
                lines.add(ddlScripter.getDialect().getScriptCommentPrefix() + " Foreign keys");
                lines.addAll(constraints);
            }
        }

        @Override
        public void exportData(String catalogName, String schemaName, String tableName, TableMetaData tableMetaData,
                               EachPage<Map<String, Object>> eachPage, boolean idReused,
                               ConnectionFactory connectionFactory) {
        }
    }
}
