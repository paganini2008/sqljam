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

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: CatalogMetaData is a catalog (database) of the server, it loads the included schemas
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class CatalogMetaData implements TiedMetaData {

    private final String catalogName;
    private final Map<String, Object> detail;
    private final ServerMetaData serverMetaData;
    private final List<SchemaMetaData> schemaMetaDatas = new ArrayList<>();

    public CatalogMetaData(String catalogName, Map<String, Object> detail, ServerMetaData serverMetaData) {
        this.catalogName = catalogName;
        this.detail = detail;
        this.serverMetaData = serverMetaData;
    }

    public Optional<SchemaMetaData> findSchemaMetaData(String schemaName) {
        return schemaMetaDatas.stream().filter(md -> md.getSchemaName().equals(schemaName)).findFirst();
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        if (log.isInfoEnabled()) {
            log.info("Begin to process catalog: {}", catalogName);
        }

        visitor.visit(this);

        Exporter.ExportConfiguration configuration = visitor.getConfiguration();
        if (configuration.getDbType().isSchemaSupported()) {
            List<Map<String, Object>> schemaInfos = getMetaDataOperations().getSchemaInfos(getMetaData(), catalogName);
            for (Map<String, Object> schemaInfo : schemaInfos) {
                String schemaName = (String) schemaInfo.get("TABLE_SCHEM");
                if (ArrayUtils.isEmpty(configuration.getIncludedSchemaNames())
                        || ArrayUtils.contains(configuration.getIncludedSchemaNames(), schemaName)) {
                    schemaMetaDatas.add(new SchemaMetaData(schemaName, schemaInfo, this));
                }
            }
        }

        if (schemaMetaDatas.isEmpty()) {
            SchemaMetaData schemaMetaData = new SchemaMetaData(null, Collections.emptyMap(), this);
            schemaMetaDatas.add(schemaMetaData);
        }
        for (SchemaMetaData schemaMetaData : schemaMetaDatas) {
            schemaMetaData.accept(visitor);
        }

        if (log.isInfoEnabled()) {
            log.info("End to process catalog: {}", catalogName);
        }
    }

    @Override
    public String getCatalogName() {
        return catalogName;
    }

    @Override
    public <T extends TiedMetaData> T unwrap(Class<T> clz) {
        throw new UnsupportedOperationException("For: " + clz.getName());
    }

    public ServerMetaData getServerMetaData() {
        return serverMetaData;
    }

    public List<SchemaMetaData> getSchemaMetaDatas() {
        return schemaMetaDatas;
    }

    @Override
    public Map<String, Object> getDetail() {
        return detail;
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return serverMetaData.getMetaData();
    }

    @Override
    public Dialect getDialect() {
        return serverMetaData.getDialect();
    }

    @Override
    public MetaDataOperations getMetaDataOperations() {
        return serverMetaData.getMetaDataOperations();
    }

    @Override
    public String[] getStatements() throws SQLException {
        if (StringUtils.isNotBlank(catalogName)) {
            List<String> sqls = new ArrayList<>();
            String catalogName = getCatalogName();
            String username = getMetaData().getUserName();
            String statement = getDialect().getCreateDatabaseStatement(catalogName, username);
            sqls.add(statement);
            String[] after = getDialect().getStatementAfterDatabaseCreated(catalogName, username);
            if (ArrayUtils.isNotEmpty(after)) {
                sqls.addAll(Arrays.asList(after));
            }
            return sqls.toArray(new String[0]);
        }
        return null;
    }
}