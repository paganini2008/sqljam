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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.jdbc.SimpleConnectionFactory;
import com.github.sqljam.page.EachPage;
import com.github.sqljam.impexp.DdlScripter.Catalog;
import com.github.sqljam.impexp.DdlScripter.Schema;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ImportExportHandler imports ddl and rows into the target database directly
 * @Author: Fred Feng
 * @Date: 30/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class ImportExportHandler implements ExportHandler {

    @Getter
    @Setter
    private ImportConfiguration configuration = new ImportConfiguration();

    @Getter
    @Setter
    @ToString
    public static class ImportConfiguration {

        private int port;
        private String hostname;
        private String defaultCatalogName;
        private DbType dbType;
        private String url;
        private String username;
        private String password;
        private boolean connectionPoolEnabled = true;
        /**
         * Target catalog (database) to import into. Default is the same name as source catalog.
         */
        private String targetCatalogName;
        /**
         * Target schema to import into. Default is the same name as source schema.
         */
        private String targetSchemaName;
        /**
         * Creates target schema (database of MySQL) if it does not exist
         */
        private boolean targetSchemaCreated = true;
    }

    private ConnectionFactory connectionFactory;
    private Dialect dialect;
    private ExportListener exportListener = ExportListener.NONE;
    private final IdentityValueTracker identityValueTracker = new IdentityValueTracker();

    @Override
    public void setExportListener(ExportListener exportListener) {
        this.exportListener = exportListener != null ? exportListener : ExportListener.NONE;
    }

    @Override
    public void start() {
        if (connectionFactory == null) {
            this.connectionFactory = createConnectionFactory();
        }
    }

    @Override
    public Dialect configureDialect(Dialect dialect) {
        Connection connection = null;
        try {
            connection = connectionFactory.getConnection();
            DatabaseMetaData databaseMetaData = connection.getMetaData();
            // A version specified explicitly is kept
            if (dialect.getDatabaseMajorVersion() < 0) {
                dialect = dialect.forVersion(databaseMetaData.getDatabaseMajorVersion(),
                        databaseMetaData.getDatabaseMinorVersion());
            }
            this.dialect = dialect;
            exportListener.onMessage(String.format("Target database: %s %s",
                    databaseMetaData.getDatabaseProductName(), databaseMetaData.getDatabaseProductVersion()));
        } catch (SQLException e) {
            throw new ImpExpException("Unable to connect to target database: " + e.getMessage(), e);
        } finally {
            JdbcUtils.closeQuietly(connection);
        }
        return dialect;
    }

    private ConnectionFactory createConnectionFactory() {
        String jdbcUrl = configuration.getUrl();
        if (StringUtils.isBlank(jdbcUrl)) {
            jdbcUrl = configuration.getDbType().getUrl(configuration.getHostname(), configuration.getPort(),
                    configuration.getDefaultCatalogName());
        }
        if (configuration.isConnectionPoolEnabled()) {
            return new HikariDataSourceConnectionFactory(configuration.getDbType().getDriverClassName(), jdbcUrl,
                    configuration.getUsername(), configuration.getPassword());
        }
        return new SimpleConnectionFactory(configuration.getDbType().getDriverClassName(), jdbcUrl,
                configuration.getUsername(),
                configuration.getPassword());
    }

    private Connection getConnection(String catalogName, String schemaName) throws SQLException {
        if (StringUtils.isNotBlank(configuration.getTargetCatalogName())) {
            catalogName = configuration.getTargetCatalogName();
        }
        if (dialect != null) {
            schemaName = dialect.getTargetSchemaName(schemaName);
        } else if (StringUtils.isNotBlank(configuration.getTargetSchemaName())) {
            schemaName = configuration.getTargetSchemaName();
        }
        DbType dbType = configuration.getDbType();
        Connection connection;
        if (dbType.isCanSetCatalog() && dbType.isCanSetSchema()) {
            connection = connectionFactory.getConnection(catalogName, schemaName);
        } else if (!dbType.isCanSetCatalog() && dbType.isCanSetSchema()) {
            connection = connectionFactory.getConnection(null, schemaName);
        } else if (dbType.isCanSetCatalog() && !dbType.isCanSetSchema()) {
            connection = connectionFactory.getConnection(catalogName, null);
        } else {
            connection = connectionFactory.getConnection();
        }
        executeQuietly(connection, dialect != null ? dialect.getSessionStatements() : null);
        return connection;
    }

    @Override
    public void exportDdl(DdlScripter ddlScripter) throws Exception {
        if (configuration.isTargetSchemaCreated()) {
            createTargetSchemas(ddlScripter);
        }
        for (Map.Entry<String, Catalog> catalogEntry : ddlScripter.getCatalogs().entrySet()) {
            final String catalogName = catalogEntry.getKey();
            if (log.isInfoEnabled()) {
                log.info("Switch to catalog: {}", catalogName);
            }
            for (Map.Entry<String, Schema> schemaEntry : catalogEntry.getValue().getSchemas().entrySet()) {
                final String schemaName = schemaEntry.getKey();
                if (log.isInfoEnabled()) {
                    log.info("Switch to schema: {}", schemaName);
                }
                executeStatements(catalogName, schemaName, schemaEntry.getValue().getPlainScripts());
                if (log.isInfoEnabled()) {
                    log.info("Get out of schema: {}", schemaName);
                }
            }
            if (log.isInfoEnabled()) {
                log.info("Get out of catalog: {}", catalogName);
            }
        }
    }

    private void createTargetSchemas(DdlScripter ddlScripter) throws SQLException {
        java.util.Set<String> schemaNames = new java.util.LinkedHashSet<>();
        if (!configuration.getDbType().isSchemaSupported()) {
            if (StringUtils.isNotBlank(configuration.getTargetCatalogName())) {
                schemaNames.add(configuration.getTargetCatalogName());
            }
        } else {
            for (Catalog catalog : ddlScripter.getCatalogs().values()) {
                for (String schemaName : catalog.getSchemas().keySet()) {
                    String targetSchemaName = dialect.getTargetSchemaName(schemaName);
                    if (StringUtils.isNotBlank(targetSchemaName)) {
                        schemaNames.add(targetSchemaName);
                    }
                }
            }
        }
        if (schemaNames.isEmpty()) {
            return;
        }
        Connection connection = null;
        try {
            connection = connectionFactory.getConnection();
            for (String schemaName : schemaNames) {
                String sql = dialect.getCreateSchemaIfNotExistsStatement(schemaName);
                if (StringUtils.isNotBlank(sql)) {
                    try {
                        JdbcUtils.execute(connection, sql);
                    } catch (SQLException e) {
                        exportListener.onError(String.format("Unable to create schema '%s': %s", schemaName,
                                e.getMessage()), e);
                    }
                }
            }
        } finally {
            connectionFactory.close(connection);
        }
    }

    @Override
    public void exportConstraints(DdlScripter ddlScripter) throws Exception {
        for (Map.Entry<String, Catalog> catalogEntry : ddlScripter.getCatalogs().entrySet()) {
            for (Map.Entry<String, Schema> schemaEntry : catalogEntry.getValue().getSchemas().entrySet()) {
                List<String> sqls = SqlTextUtils.addEndMarks(schemaEntry.getValue().getConstraintStatements());
                if (sqls.size() > 0) {
                    exportListener.onMessage(String.format("Creating %d foreign keys ...", sqls.size()));
                    executeStatements(catalogEntry.getKey(), schemaEntry.getKey(), sqls);
                }
            }
        }
    }

    private void executeStatements(String catalogName, String schemaName, List<String> sqls) throws SQLException {
        Connection connection = null;
        try {
            connection = getConnection(catalogName, schemaName);
            for (String sql : sqls) {
                sql = SqlTextUtils.removeEndMark(sql);
                try {
                    JdbcUtils.execute(connection, sql);
                    if (log.isInfoEnabled()) {
                        log.info("Execute ddl: {}", sql);
                    }
                } catch (Exception e) {
                    if (log.isErrorEnabled()) {
                        log.error("Unable to execute sql: {}", sql, e);
                    }
                    exportListener.onError(String.format("Unable to execute sql: %s, cause: %s", sql,
                            e.getMessage()), e);
                }
            }
        } finally {
            connectionFactory.close(connection);
        }
    }

    @Override
    public void exportData(String catalogName,
                           String schemaName,
                           String tableName,
                           TableMetaData tableMetaData,
                           EachPage<Map<String, Object>> eachPage,
                           boolean idReused,
                           ConnectionFactory sourceConnectionFactory) throws Exception {
        List<Map<String, Object>> dataList = eachPage.getContent();
        if (CollectionUtils.isEmpty(dataList)) {
            return;
        }
        Dialect dialect = tableMetaData.getDialect();
        List<Map<String, Object>> rowList = new ArrayList<>();
        Map<String, Object> template = tableMetaData.getColumnMetaDatas().stream()
                .filter(md -> idReused || !shouldFilterColumn(md.getColumnName(), tableMetaData))
                .filter(md -> tableMetaData.isInsertableColumn(md.getColumnName(), tableMetaData.getDialect()))
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getColumnName(), null), LinkedHashMap::putAll);

        for (Map<String, Object> data : dataList) {
            Map<String, Object> row = new LinkedHashMap<>(template);
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (row.containsKey(entry.getKey())) {
                    ColumnMetaData columnMetaData = tableMetaData.findColumnMetaData(entry.getKey()).orElse(null);
                    row.put(entry.getKey(), ScriptExportHandler.getJdbcValue(dialect, columnMetaData,
                            entry.getValue()));
                }
            }
            rowList.add(row);
        }

        if (CollectionUtils.isEmpty(rowList)) {
            return;
        }
        List<String> identityColumnNames = idReused ? tableMetaData.getIncrementalColumnNames(dialect)
                : new ArrayList<>();
        identityValueTracker.track(tableMetaData, identityColumnNames, rowList);
        boolean identityIncluded = identityColumnNames.stream().anyMatch(template::containsKey);

        String[] columns = template.keySet().toArray(new String[0]);
        int[] nullTypes = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            nullTypes[i] = dialect.getNullSqlType(getNullType(tableMetaData.findColumnMetaData(columns[i]).orElse(null)));
        }
        String insertSql = dialect.getInsertTableStatement(catalogName, schemaName, tableName, columns);
        Connection connection = null;
        boolean autoCommit = true;
        try {
            connection = getConnection(catalogName, schemaName);
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            executeQuietly(connection, dialect.getStatementBeforeInsert(catalogName, schemaName, tableName,
                    identityIncluded));
            int rows = 0;
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                for (Map<String, Object> row : rowList) {
                    int index = 0;
                    for (Object value : row.values()) {
                        if (value == null) {
                            ps.setNull(index + 1, nullTypes[index]);
                        } else {
                            ps.setObject(index + 1, value);
                        }
                        index++;
                    }
                    ps.addBatch();
                }
                for (int n : ps.executeBatch()) {
                    rows += n > 0 ? n : (n == java.sql.Statement.SUCCESS_NO_INFO ? 1 : 0);
                }
            }
            executeQuietly(connection, dialect.getStatementAfterInsert(catalogName, schemaName, tableName,
                    identityIncluded));
            connection.commit();
            if (log.isInfoEnabled()) {
                log.info("Execute dml: {}", insertSql);
                log.info("Add {} rows to table: {}", rows, tableName);
            }
        } catch (Exception e) {
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
            }
            if (log.isErrorEnabled()) {
                log.error("Unable to execute sql: {}", insertSql, e);
            }
            throw e;
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                }
            }
            connectionFactory.close(connection);
        }

        if (eachPage.isLastPage()) {
            resetIdentityValues(catalogName, schemaName, tableName, tableMetaData);
        }
    }

    private void resetIdentityValues(String catalogName, String schemaName, String tableName,
                                     TableMetaData tableMetaData) throws SQLException {
        Map<String, Long> maxValues = identityValueTracker.remove(tableMetaData);
        if (maxValues == null || maxValues.isEmpty()) {
            return;
        }
        List<String> sqls = new ArrayList<>();
        for (Map.Entry<String, Long> entry : maxValues.entrySet()) {
            String sql = tableMetaData.getDialect().getResetIdentityStatement(catalogName, schemaName, tableName,
                    entry.getKey(), entry.getValue() + 1);
            if (StringUtils.isNotBlank(sql)) {
                sqls.add(sql);
            }
        }
        executeStatements(catalogName, schemaName, sqls);
    }

    private void executeQuietly(Connection connection, String[] sqls) {
        if (ArrayUtils.isEmpty(sqls)) {
            return;
        }
        for (String sql : sqls) {
            try {
                JdbcUtils.execute(connection, sql);
            } catch (SQLException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Unable to execute sql: {}, cause: {}", sql, e.getMessage());
                }
            }
        }
    }

    /**
     * Sql type to bind NULL, some drivers (SQL Server) can not convert untyped NULL to binary columns
     */
    static int getNullType(ColumnMetaData columnMetaData) {
        if (columnMetaData == null) {
            return Types.VARCHAR;
        }
        int dataType = Dialect.resolveDataType(TableMetaData.getInt(columnMetaData.getDetail(), "DATA_TYPE"),
                (String) columnMetaData.getDetail().get("TYPE_NAME"));
        switch (dataType) {
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return Types.VARBINARY;
            case Types.BIT:
            case Types.BOOLEAN:
                return TableMetaData.getInt(columnMetaData.getDetail(), "COLUMN_SIZE") > 1 ? Types.NUMERIC
                        : Types.BOOLEAN;
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return Types.NUMERIC;
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
                return dataType;
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return Types.TIMESTAMP;
            default:
                return Types.VARCHAR;
        }
    }

    protected boolean shouldFilterColumn(String columnName, TableMetaData tableMetaData) {
        return tableMetaData.isAutoIncrementColumn(columnName);
    }

    @Override
    public void releaseExternalResource() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
            connectionFactory = null;
        }
    }
}
