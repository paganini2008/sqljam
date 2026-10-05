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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ServerMetaData is the root of the metadata tree, it loads the included catalogs
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class ServerMetaData implements MetaData {

    private final String username;
    private final String password;
    private final DatabaseMetaData metaData;
    private final MetaDataOperations metaDataOperations;
    private final Dialect dialect;

    public ServerMetaData(String username, String password, DatabaseMetaData metaData, MetaDataOperations metaDataOperations,
            Dialect dialect) {
        this.username = username;
        this.password = password;
        this.metaData = metaData;
        this.metaDataOperations = metaDataOperations;
        this.dialect = dialect;
    }

    private final List<CatalogMetaData> catalogMetaDatas = new ArrayList<>();

    public Optional<CatalogMetaData> findCatalogMetaData(String catalog) {
        return catalogMetaDatas.stream().filter(md -> md.getCatalogName().equals(catalog)).findFirst();
    }

    public Optional<SchemaMetaData> findSchemaMetaData(String catalog, String schema) {
        Optional<CatalogMetaData> opt = findCatalogMetaData(catalog);
        if (opt.isPresent()) {
            return opt.get().findSchemaMetaData(schema);
        }
        return Optional.empty();
    }

    public Optional<TableMetaData> findTableMetaData(String catalog, String schema, String table) {
        Optional<SchemaMetaData> opt = findSchemaMetaData(catalog, schema);
        if (opt.isPresent()) {
            return opt.get().getTableMetaDatas().stream().filter(md -> md.getTableName().equals(table)).findFirst();
        }
        return Optional.empty();
    }

    @Override
    public void accept(MetaDataVisitor visitor) throws SQLException {
        long startTime = System.currentTimeMillis();
        if(log.isInfoEnabled()) {
            log.info("Begin to process ...");
        }
        visitor.visit(this);

        Exporter.ExportConfiguration configuration = visitor.getConfiguration();
        
        List<Map<String, Object>> infoList = metaDataOperations.getCatalogInfos(metaData);
        for (Map<String, Object> catalogInfo : infoList) {
            String catalogName = (String) catalogInfo.get("TABLE_CAT");
            if (ArrayUtils.isEmpty(configuration.getIncludedCatalogNames())
                    || ArrayUtils.contains(configuration.getIncludedCatalogNames(), catalogName)) {
                catalogMetaDatas.add(new CatalogMetaData(catalogName, catalogInfo, this));
            }
        }

        for (CatalogMetaData catalogMetaData : catalogMetaDatas) {
            long catalogStartTime = System.currentTimeMillis();
            catalogMetaData.accept(visitor);
            if (log.isInfoEnabled()) {
                log.info("Catalog {} processed in {} (ms)", catalogMetaData.getCatalogName(), System.currentTimeMillis() - catalogStartTime);
            }
        }
        if(log.isInfoEnabled()) {
            log.info("End to process. Total time: {} (ms)\n", (System.currentTimeMillis()-startTime));
        }
    }

    @Override
    public String[] getStatements() throws SQLException {
        String username = getUsername();
        String password = getPassword();
        if (StringUtils.isNotBlank(username)) {
            List<String> sqls = new ArrayList<>();
            String statement = getDialect().getCreateUserStatement(username, password);
            sqls.add(statement);

            String[] after = getDialect().getStatementAfterUserCreated(username);
            if (ArrayUtils.isNotEmpty(after)) {
                sqls.addAll(Arrays.asList(after));
            }
            return sqls.toArray(new String[0]);
        }
        return null;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return metaData;
    }

    @Override
    public Dialect getDialect() {
        return dialect;
    }

    @Override
    public Map<String, Object> getDetail() {
        return null;
    }

    public MetaDataOperations getMetaDataOperations() {
        return metaDataOperations;
    }

    public List<CatalogMetaData> getCatalogMetaDatas() {
        return catalogMetaDatas;
    }
}