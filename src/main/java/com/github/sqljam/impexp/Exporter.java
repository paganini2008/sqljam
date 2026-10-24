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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.jdbc.SimpleConnectionFactory;
import com.github.sqljam.jdbc.page.MapBasedPageReader;
import com.github.sqljam.page.EachPage;
import com.github.sqljam.page.PageReader;
import com.github.sqljam.page.PageRequest;
import com.github.sqljam.page.PageResponse;
import com.github.sqljam.utils.CaseInsensitiveMap;
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
        /**
         * Queries narrowing the rows and columns of tables by table name, e.g. rows filtered in the data viewer
         */
        private Map<String, TableQuery> tableQueries = new HashMap<>();
        /**
         * Rows are inserted into the target, or upserted: the rows of the same key are updated
         */
        private WriteMode writeMode = WriteMode.INSERT;
        /**
         * Key columns of upserts by table name, the primary key of the table by default. A table without key is
         * inserted.
         */
        private Map<String, List<String>> keyColumnNames = new HashMap<>();
        /**
         * Free queries exported as tables, SQL by table name, e.g. results of the SQL editor. Only the queries are
         * exported then, as tables of the included catalog and schema.
         */
        private Map<String, String> queries = new LinkedHashMap<>();

        /**
         * Whether free queries are exported instead of tables
         */
        public boolean isQueryExport() {
            return queries != null && !queries.isEmpty();
        }

        /**
         * Query of a table, null if all rows and columns are exported. Grouped rows are not rows of the table.
         */
        public TableQuery getTableQuery(String tableName) {
            TableQuery query = tableQueries != null ? tableQueries.get(tableName) : null;
            if (query != null && query.isGrouped()) {
                throw new ImpExpException("Grouped rows of table " + tableName + " can not be exported,"
                        + " the rows are exported without GROUP BY");
            }
            return query == null || query.isEmpty() ? null : query;
        }

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
                        getSourceMetaDataOperations(), configuration.getDialect());
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

    private final Map<TableMetaData, Long> tableRows = new HashMap<>();
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
                        String query = configuration.isQueryExport() ? configuration.getQueries().get(
                                tableMd.getTableName()) : null;
                        if (query != null) {
                            // Rows of a query which can not be counted are reported as they are read
                            Long rows = countQueryRows(connection, query);
                            tableRows.put(tableMd, rows != null ? rows : -1L);
                            totalRows += rows != null ? rows : 0L;
                            continue;
                        }
                        String sql = sourceDialect.getCountQueryStatement(catalogMd.getCatalogName(),
                                schemaMd.getSchemaName(), tableMd.getTableName(),
                                configuration.getTableQuery(tableMd.getTableName()));
                        Long rows = JdbcUtils.fetchOne(connection, sql, Long.class);
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

    /**
     * Rows of a free query, null if it can not be counted, e.g. SHOW statements
     */
    private Long countQueryRows(Connection connection, String query) {
        try {
            return JdbcUtils.fetchOne(connection, "SELECT COUNT(*) FROM (" + query + ") q__", Long.class);
        } catch (SQLException e) {
            if (log.isInfoEnabled()) {
                log.info("Rows of the query can not be counted: {}", e.getMessage());
            }
            return null;
        }
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
        if (configuration.isQueryExport()) {
            // The queries are the tables of the export, filters of table names do not apply to them
            configuration.setIncludedTableNames(configuration.getQueries().keySet().toArray(new String[0]));
            configuration.setIncludedTableNamePattern(null);
            configuration.setIncludedTableNamesForBackupingData(null);
        }
        Dialect dialect = configuration.getDialect();
        if (dialect.getDbType() != configuration.getDbType() && dialect.getSourceDbType() == null) {
            dialect.setSourceDbType(configuration.getDbType());
        }
    }

    private void configureSourceDialect(DatabaseMetaData databaseMetaData) throws SQLException {
        int[] version = configuration.getSourceDialect().readDatabaseVersion(databaseMetaData);
        int majorVersion = version[0];
        int minorVersion = version[1];
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

    /**
     * Metadata of the source: tables of the database, or free queries presented as tables
     */
    private MetaDataOperations getSourceMetaDataOperations() {
        return configuration.isQueryExport() ? new QueryMetaDataOperations(getMetaDataOperations(),
                configuration.getQueries()) : getMetaDataOperations();
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
        if (configuration.getDialect() != null) {
            sourceDialect.setReadTargetDbType(configuration.getDialect().getDbType());
        }
        String freeQuery = configuration.isQueryExport() ? configuration.getQueries().get(tableName) : null;
        if (freeQuery != null) {
            scanQuery(catalogName, schemaName, tableName, tableMetaData, freeQuery, sourceDialect,
                    connectionFactory);
            return;
        }
        String[] columnNames = tableMetaData.getColumnMetaDatas().stream().map(ColumnMetaData::getColumnName)
                .toArray(String[]::new);
        String[] typeNames = tableMetaData.getColumnMetaDatas().stream()
                .map(md -> (String) md.getDetail().get("TYPE_NAME")).toArray(String[]::new);
        TableQuery query = configuration.getTableQuery(tableName);
        String sql = columnNames.length > 0
                ? sourceDialect.getQueryStatement(catalogName, schemaName, tableName, columnNames, typeNames, query)
                : sourceDialect.getSelectTableStatement(catalogName, schemaName, tableName);
        String orderBy = sourceDialect.getQueryOrderBy(query, tableMetaData.getPrimaryKeyColumnNames());
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
            tableProcessedRows = exportPage(catalogName, schemaName, tableName, tableMetaData, eachPage,
                    connectionFactory, tableProcessedRows, tableTotalRows);
        }
        exportListener.onTableEnd(catalogName, schemaName, tableName, tableProcessedRows);
    }

    /**
     * Exports a page of rows and reports the progress
     *
     * @return rows of the table processed with this page
     */
    private long exportPage(String catalogName, String schemaName, String tableName, TableMetaData tableMetaData,
                            EachPage<Map<String, Object>> eachPage, ConnectionFactory connectionFactory,
                            long tableProcessedRows, long tableTotalRows) {
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
        long processed = tableProcessedRows + eachPage.getContent().size();
        this.processedRows += eachPage.getContent().size();
        exportListener.onTableProgress(catalogName, schemaName, tableName, processed, tableTotalRows);
        exportListener.onProgress(this.processedRows, Math.max(this.totalRows, this.processedRows));
        return processed;
    }

    /**
     * Rows of a free query are read once by a cursor and exported page by page: paging by OFFSET would run the
     * query again for each page, and its rows have no stable order
     */
    private void scanQuery(String catalogName, String schemaName, String tableName, TableMetaData tableMetaData,
                           String query, Dialect sourceDialect, ConnectionFactory connectionFactory) {
        long tableTotalRows = tableRows.getOrDefault(tableMetaData, -1L);
        int pageSize = getPageSize(configuration.getPageSize(), configuration.getLobPageSize(),
                tableMetaData.getColumnMetaDatas().stream().anyMatch(ScriptExportHandler::isLobColumn));
        exportListener.onTableStart(catalogName, schemaName, tableName, tableTotalRows);
        long tableProcessedRows = 0;
        Connection connection = null;
        boolean transactionStarted = false;
        try {
            connection = connectionFactory.getConnection();
            try (Statement statement = connection.createStatement()) {
                transactionStarted = sourceDialect.prepareStreaming(connection, statement, pageSize);
                try (ResultSet rs = statement.executeQuery(getQueryStatement(query, tableMetaData, sourceDialect))) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    List<String> names = QueryMetaDataOperations.getColumnNames(metaData);
                    int columnCount = metaData.getColumnCount();
                    int[] types = new int[columnCount];
                    String[] typeNames = new String[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        types[i] = metaData.getColumnType(i + 1);
                        typeNames[i] = metaData.getColumnTypeName(i + 1);
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    int pageNumber = 1;
                    boolean more = rs.next();
                    while (more) {
                        Map<String, Object> row = new CaseInsensitiveMap<>(new LinkedHashMap<>(columnCount));
                        for (int i = 1; i <= columnCount; i++) {
                            try {
                                row.put(names.get(i - 1), sourceDialect.getColumnValue(rs, i, types[i - 1],
                                        typeNames[i - 1]));
                            } catch (LinkageError e) {
                                throw QueryMetaDataOperations.toReadError(metaData, i, e);
                            }
                        }
                        rows.add(row);
                        more = rs.next();
                        if (rows.size() >= pageSize || !more) {
                            tableProcessedRows = exportPage(catalogName, schemaName, tableName, tableMetaData,
                                    new QueryPage(rows, pageNumber++, pageSize, !more, tableTotalRows),
                                    connectionFactory, tableProcessedRows, tableTotalRows);
                            rows = new ArrayList<>();
                        }
                    }
                }
            }
        } catch (ImpExpException e) {
            throw e;
        } catch (Exception e) {
            throw new ImpExpException(String.format("Unable to read the rows of query '%s': %s", tableName,
                    e.getMessage()), e);
        } finally {
            if (connection != null && transactionStarted) {
                try {
                    // The cursor only read rows
                    connection.rollback();
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
            if (connection != null) {
                try {
                    connectionFactory.close(connection);
                } catch (SQLException ignored) {
                }
            }
        }
        exportListener.onTableEnd(catalogName, schemaName, tableName, tableProcessedRows);
    }

    /**
     * The query, or the query selected again with the conversions of a table export when its columns need them,
     * e.g. text of Map and Tuple of ClickHouse, FROM_VECTOR and XMLSERIALIZE of Oracle. A query whose labels were
     * renamed (columns of the same name) can not be selected again, it is read as it is.
     */
    static String getQueryStatement(String query, TableMetaData tableMetaData, Dialect sourceDialect) {
        List<String> expressions = new ArrayList<>();
        boolean converted = false;
        for (ColumnMetaData column : tableMetaData.getColumnMetaDatas()) {
            String name = column.getColumnName();
            if (!name.equals(column.getDetail().get(QueryMetaDataOperations.QUERY_LABEL))) {
                return query;
            }
            String expression = sourceDialect.getSelectColumnExpression(name, (String) column.getDetail().get(
                    "TYPE_NAME"));
            converted |= !expression.equals(sourceDialect.quoteIdentifier(name));
            expressions.add(expression);
        }
        return converted ? "SELECT " + String.join(", ", expressions) + " FROM (" + query + ") q__" : query;
    }

    /**
     * A page of rows read from the cursor of a free query
     */
    private static class QueryPage implements EachPage<Map<String, Object>> {

        private final List<Map<String, Object>> content;
        private final int pageNumber;
        private final int pageSize;
        private final boolean lastPage;
        private final long totalRecords;

        QueryPage(List<Map<String, Object>> content, int pageNumber, int pageSize, boolean lastPage,
                  long totalRecords) {
            this.content = content;
            this.pageNumber = pageNumber;
            this.pageSize = pageSize;
            this.lastPage = lastPage;
            this.totalRecords = totalRecords;
        }

        @Override
        public boolean isEmpty() {
            return content.isEmpty();
        }

        @Override
        public boolean isLastPage() {
            return lastPage;
        }

        @Override
        public boolean isFirstPage() {
            return pageNumber == 1;
        }

        @Override
        public boolean hasNextPage() {
            return !lastPage;
        }

        @Override
        public boolean hasPreviousPage() {
            return pageNumber > 1;
        }

        @Override
        public int getTotalPages() {
            return totalRecords < 0 ? -1 : (int) ((totalRecords + pageSize - 1) / pageSize);
        }

        @Override
        public long getTotalRecords() {
            return totalRecords;
        }

        @Override
        public int getOffset() {
            return (pageNumber - 1) * pageSize;
        }

        @Override
        public int getPageSize() {
            return pageSize;
        }

        @Override
        public int getPageNumber() {
            return pageNumber;
        }

        @Override
        public List<Map<String, Object>> getContent() {
            return content;
        }

        @Override
        public Object getNextToken() {
            return null;
        }
    }

    /**
     * Page size of a table, a table with LOB columns is read by smaller pages
     */
    static int getPageSize(int pageSize, int lobPageSize, boolean lobIncluded) {
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        return lobIncluded && lobPageSize > 0 ? Math.min(size, lobPageSize) : size;
    }
}
