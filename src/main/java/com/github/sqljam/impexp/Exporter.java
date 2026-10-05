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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.jdbc.SimpleConnectionFactory;
import com.github.sqljam.jdbc.page.MapBasedPageReader;
import com.github.sqljam.page.EachPage;
import com.github.sqljam.page.PageReader;
import com.github.sqljam.page.PageRequest;
import com.github.sqljam.page.PageResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: Exporter reads metadata and rows of the source database and sends them to the export handler
 * @Author: Fred Feng
 * @Date: 28/03/2023
 * @Version 1.0.0
 */
@Slf4j
@Getter
@Setter
@ToString
public class Exporter {

    public static final int DEFAULT_PAGE_SIZE = 5000;
    public static final int DEFAULT_LOB_PAGE_SIZE = 100;

    private final ExportHandler exportHandler;
    private ExportConfiguration configuration = new ExportConfiguration();
    private MetaDataOperations metaDataOperations;
    private ExportListener exportListener = ExportListener.NONE;

    public Exporter(ExportHandler exportHandler) {
        this.exportHandler = exportHandler;
    }

    /**
     * @Description: Export
     * @Author: Fred Feng
     * @Date: 30/03/2023
     * @Version 1.0.0
     */
    @Getter
    @Setter
    @ToString
    public static class ExportConfiguration {

        private int port;
        private String hostname;
        private DbType dbType;
        private String username;
        private String password;
        private String url;
        private String defaultCatalogName;
        private String[] includedCatalogNames;
        private String[] includedSchemaNames;
        private String[] includedTableNames;
        private String includedTableNamePattern;
        private String[] includedTableNamesForBackupingData;
        private boolean idReused = false;
        private boolean showCreateUserSql = true;
        private boolean showCreateCatalogSql = true;
        private boolean showCreateSchemaSql = true;
        private boolean tableRecreated = true;
        private boolean failFast = true;
        private boolean connectionPoolEnabled = true;
        private boolean indexIncluded = true;
        private boolean commentIncluded = true;
        private boolean foreignKeyIncluded = true;
        private boolean sequenceIncluded = true;
        /**
         * Rows read per page. Larger pages mean fewer round trips, the throughput levels off around 5000 to 10000
         */
        private int pageSize = DEFAULT_PAGE_SIZE;
        /**
         * Rows read per page of a table with LOB columns, which keeps the memory of a page small
         */
        private int lobPageSize = DEFAULT_LOB_PAGE_SIZE;
        /**
         * Dialect to generate ddl/dml, it's the dialect of target database. Default is the dialect of dbType.
         */
        private Dialect dialect;
        /**
         * Dialect of source database to read data. Default is the dialect of dbType.
         */
        private Dialect sourceDialect;

        public Dialect getDialect() {
            if (dialect == null && dbType != null) {
                dialect = dbType.createDialect();
            }
            return dialect;
        }

        public Dialect getSourceDialect() {
            if (sourceDialect == null && dbType != null) {
                sourceDialect = dbType.createDialect();
            }
            return sourceDialect;
        }
    }

    public void exportDdl() throws Exception {
        export(ExportMode.DDL);
    }

    public void exportDdlAndData() throws Exception {
        export(ExportMode.DDL_DATA);
    }

    public void exportData() throws Exception {
        export(ExportMode.DATA);
    }

    public void export(ExportMode exportMode) throws Exception {
        prepare();
        exportHandler.setExportListener(exportListener);
        exportHandler.prepare(configuration, exportMode);
        exportListener.onStart(exportMode);
        boolean successful = false;
        ConnectionFactory connectionFactory = null;
        try {
            exportHandler.start();
            configuration.setDialect(exportHandler.configureDialect(configuration.getDialect()));
            connectionFactory = createConnectionFactory();
            DdlScripter ddlScripter;
            ServerMetaData serverMetaData = null;
            Connection connection = null;
            try {
                connection = connectionFactory.getConnection();
                configureSourceDialect(connection.getMetaData());
                ddlScripter = new DdlScripter(configuration.getDialect());
                serverMetaData = new ServerMetaData(configuration.getUsername(), configuration.getPassword(),
                        connection.getMetaData(),
                        getMetaDataOperations(), configuration.getDialect());
                MetaDataVisitor metaDataVisitor = new DefaultMetaDataVisitor(configuration, ddlScripter);
                exportListener.onMessage("Reading metadata ...");
                serverMetaData.accept(metaDataVisitor);
                checkCancelled();
                exportHandler.prepare(serverMetaData);
                if (exportMode != ExportMode.DATA) {
                    exportListener.onMessage("Exporting ddl ...");
                    exportHandler.exportDdl(ddlScripter);
                }
            } finally {
                connectionFactory.close(connection);
            }

            if (exportMode != ExportMode.DDL) {
                countRows(serverMetaData, connectionFactory);
                for (CatalogMetaData catalogMd : serverMetaData.getCatalogMetaDatas()) {
                    final String catalogName = catalogMd.getCatalogName();
                    for (SchemaMetaData schemaMd : catalogMd.getSchemaMetaDatas()) {
                        final String schemaName = schemaMd.getSchemaName();
                        for (TableMetaData tableMd : schemaMd.getTableMetaDatas()) {
                            final String tableName = tableMd.getTableName();
                            // Rows of partitions are read from the partitioned table
                            if (tableMd.isPartitionTable()) {
                                continue;
                            }
                            if (isDataIncluded(tableName)) {
                                scanTable(catalogName, schemaName, tableName, tableMd, connectionFactory);
                            }
                        }
                    }
                }
            }

            if (exportMode != ExportMode.DATA && configuration.isForeignKeyIncluded()) {
                checkCancelled();
                exportHandler.exportConstraints(ddlScripter);
            }
            exportListener.onProgress(Math.max(totalRows, 1), Math.max(totalRows, 1));
            successful = true;
        } catch (Exception e) {
            exportListener.onError(e.getMessage(), e);
            throw e;
        } finally {
            try {
                exportHandler.finish(successful);
            } catch (Exception e) {
                successful = false;
                exportListener.onError(e.getMessage(), e);
            }
            exportHandler.releaseExternalResource();
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
            exportListener.onEnd(successful);
        }
    }

    private final Map<TableMetaData, Long> tableRows = new java.util.HashMap<>();
    private long totalRows;
    private long processedRows;

    /**
     * Counts rows of the tables to export, so that overall progress can be reported
     */
    private void countRows(ServerMetaData serverMetaData, ConnectionFactory connectionFactory) throws Exception {
        tableRows.clear();
        totalRows = 0;
        processedRows = 0;
        Dialect sourceDialect = configuration.getSourceDialect();
        Connection connection = connectionFactory.getConnection();
        try {
            for (CatalogMetaData catalogMd : serverMetaData.getCatalogMetaDatas()) {
                for (SchemaMetaData schemaMd : catalogMd.getSchemaMetaDatas()) {
                    for (TableMetaData tableMd : schemaMd.getTableMetaDatas()) {
                        if (tableMd.isPartitionTable() || !isDataIncluded(tableMd.getTableName())) {
                            continue;
                        }
                        checkCancelled();
                        String sql = sourceDialect.getCountTableStatement(catalogMd.getCatalogName(),
                                schemaMd.getSchemaName(), tableMd.getTableName());
                        Long rows = com.github.sqljam.jdbc.JdbcUtils.fetchOne(connection, sql, Long.class);
                        tableRows.put(tableMd, rows != null ? rows : 0L);
                        totalRows += rows != null ? rows : 0L;
                    }
                }
            }
        } finally {
            connectionFactory.close(connection);
        }
        exportListener.onMessage(String.format("%d rows of %d tables to export", totalRows, tableRows.size()));
        exportListener.onProgress(0, totalRows);
    }

    private boolean isDataIncluded(String tableName) {
        return ArrayUtils.isEmpty(configuration.getIncludedTableNamesForBackupingData())
                || ArrayUtils.contains(configuration.getIncludedTableNamesForBackupingData(), tableName);
    }

    private void prepare() {
        if (configuration.getDbType() == null && StringUtils.isNotBlank(configuration.getUrl())) {
            configuration.setDbType(DbType.forUrl(configuration.getUrl()));
        }
        if (configuration.getDbType() == null) {
            throw new IllegalArgumentException("Database type must be required.");
        }
        Dialect dialect = configuration.getDialect();
        if (dialect.getDbType() != configuration.getDbType() && dialect.getSourceDbType() == null) {
            dialect.setSourceDbType(configuration.getDbType());
        }
    }

    private void configureSourceDialect(DatabaseMetaData databaseMetaData) throws SQLException {
        int majorVersion = databaseMetaData.getDatabaseMajorVersion();
        int minorVersion = databaseMetaData.getDatabaseMinorVersion();
        // A version specified explicitly is kept
        if (configuration.getSourceDialect().getDatabaseMajorVersion() < 0) {
            configuration.setSourceDialect(configuration.getSourceDialect().forVersion(majorVersion, minorVersion));
        }
        Dialect dialect = configuration.getDialect();
        // Script of the same database type is generated for the same version by default
        if (dialect.getDbType() == configuration.getDbType() && dialect.getDatabaseMajorVersion() < 0) {
            configuration.setDialect(dialect.forVersion(majorVersion, minorVersion));
        }
        exportListener.onMessage(String.format("Source database: %s %s", databaseMetaData.getDatabaseProductName(),
                databaseMetaData.getDatabaseProductVersion()));
    }

    public MetaDataOperations getMetaDataOperations() {
        if (metaDataOperations == null) {
            metaDataOperations = configuration.getDbType().createMetaDataOperations();
        }
        return metaDataOperations;
    }

    private void checkCancelled() {
        if (exportListener.isCancelled()) {
            throw new ExportCancelledException();
        }
    }

    private ConnectionFactory createConnectionFactory() {
        String jdbcUrl = configuration.getUrl();
        if (StringUtils.isBlank(jdbcUrl)) {
            if (!configuration.getDbType().isFileBased() && StringUtils.isBlank(configuration.getHostname())) {
                throw new IllegalArgumentException("Database server host name must be required.");
            }
            if (!configuration.getDbType().isFileBased() && configuration.getPort() <= 0) {
                throw new IllegalArgumentException("Database server port is invalid.");
            }
            String catalogName = configuration.getDefaultCatalogName();
            jdbcUrl = configuration.getDbType().getUrl(configuration.getHostname(), configuration.getPort(),
                    catalogName);
        }
        if (configuration.isConnectionPoolEnabled()) {
            return new HikariDataSourceConnectionFactory(configuration.getDbType().getDriverClassName(), jdbcUrl,
                    configuration.getUsername(), configuration.getPassword());
        }
        return new SimpleConnectionFactory(configuration.getDbType().getDriverClassName(), jdbcUrl,
                configuration.getUsername(), configuration.getPassword());
    }

    private void scanTable(String catalogName, String schemaName, String tableName, TableMetaData tableMetaData,
                           ConnectionFactory connectionFactory) {
        checkCancelled();
        Dialect sourceDialect = configuration.getSourceDialect();
        String[] columnNames = tableMetaData.getColumnMetaDatas().stream().map(ColumnMetaData::getColumnName)
                .toArray(String[]::new);
        String[] typeNames = tableMetaData.getColumnMetaDatas().stream()
                .map(md -> (String) md.getDetail().get("TYPE_NAME")).toArray(String[]::new);
        String sql = columnNames.length > 0
                ? sourceDialect.getSelectTableStatement(catalogName, schemaName, tableName, columnNames, typeNames)
                : sourceDialect.getSelectTableStatement(catalogName, schemaName, tableName);
        List<String> orderColumns = tableMetaData.getPrimaryKeyColumnNames();
        String orderBy = orderColumns.isEmpty() ? null
                : orderColumns.stream().map(sourceDialect::quoteIdentifier).collect(Collectors.joining(","));
        // Rows were counted already
        long rows = tableRows.getOrDefault(tableMetaData, -1L);
        PageReader<Map<String, Object>> pageReader = new MapBasedPageReader(connectionFactory, sql, new Object[0],
                rows > 0 ? rows : -1, sourceDialect, orderBy);
        int pageSize = getPageSize(configuration.getPageSize(), configuration.getLobPageSize(),
                tableMetaData.getColumnMetaDatas().stream().anyMatch(ScriptExportHandler::isLobColumn));
        PageResponse<Map<String, Object>> pageResponse = pageReader.list(PageRequest.of(1, pageSize));
        long tableTotalRows = pageResponse.getTotalRecords();
        long tableProcessedRows = 0;
        exportListener.onTableStart(catalogName, schemaName, tableName, tableTotalRows);
        for (EachPage<Map<String, Object>> eachPage : pageResponse) {
            checkCancelled();
            try {
                exportHandler.exportData(catalogName, schemaName, tableName, tableMetaData, eachPage,
                        configuration.isIdReused(), connectionFactory);
            } catch (Exception e) {
                if (configuration.isFailFast()) {
                    throw new ImpExpException(e.getMessage(), e);
                } else {
                    exportListener.onError(String.format("Failed to export data of table '%s': %s", tableName,
                            e.getMessage()), e);
                    if (log.isErrorEnabled()) {
                        log.error(e.getMessage(), e);
                    }
                }
            }
            tableProcessedRows += eachPage.getContent().size();
            this.processedRows += eachPage.getContent().size();
            exportListener.onTableProgress(catalogName, schemaName, tableName, tableProcessedRows, tableTotalRows);
            exportListener.onProgress(this.processedRows, Math.max(this.totalRows, this.processedRows));
        }
        exportListener.onTableEnd(catalogName, schemaName, tableName, tableProcessedRows);
    }

    /**
     * Page size of a table, a table with LOB columns is read by smaller pages
     */
    static int getPageSize(int pageSize, int lobPageSize, boolean lobIncluded) {
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return lobIncluded && lobPageSize > 0 ? Math.min(size, lobPageSize) : size;
    }
}
