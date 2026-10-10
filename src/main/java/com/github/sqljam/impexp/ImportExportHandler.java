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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DdlScripter.Catalog;
import com.github.sqljam.impexp.DdlScripter.Schema;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.jdbc.SimpleConnectionFactory;
import com.github.sqljam.page.EachPage;
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
        /**
         * Name pattern of target tables, {table} is replaced with the source table name, e.g. {table}_copy copies
         * tables in the same schema. Target tables have the same names by default.
         */
        private String tableNamePattern;
    }

    private ConnectionFactory connectionFactory;
    private Dialect dialect;
    private ExportListener exportListener = ExportListener.NONE;
    private final IdentityValueTracker identityValueTracker = new IdentityValueTracker();
    private final Map<String, Map<String, String>> targetColumnTypes = new ConcurrentHashMap<>();

    @Override
    public void setExportListener(ExportListener exportListener) {
        this.exportListener = exportListener != null ? exportListener : ExportListener.NONE;
    }

    /**
     * Connections of the target database, e.g. an existing connection shared by {@link com.github.sqljam.jdbc.SharedConnectionFactory}.
     * By default a connection pool is created from the configuration.
     */
    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
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
        Set<String> schemaNames = new LinkedHashSet<>();
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
        // Empty LOBs of databases which store empty values as NULL (Oracle) are bound as LOB objects
        int[] emptyLobTypes = new int[columns.length];
        if (dialect.isEmptyValueNull()) {
            for (int i = 0; i < columns.length; i++) {
                emptyLobTypes[i] = getLobType(dialect.getRegisteredColumnTypeName(tableName, columns[i]),
                        tableMetaData.findColumnMetaData(columns[i]).orElse(null));
            }
        }
        String targetTableName = dialect.getTargetTableName(tableName);
        Map<String, String> targetTypes = Collections.emptyMap();
        String insertSql = dialect.getInsertTableStatement(catalogName, schemaName, targetTableName, columns);
        Connection connection = null;
        boolean autoCommit = true;
        boolean transactional = false;
        try {
            connection = getConnection(catalogName, schemaName);
            autoCommit = connection.getAutoCommit();
            transactional = JdbcUtils.beginTransaction(connection);
            if (dialect.isTargetTypeConversionRequired()) {
                targetTypes = getTargetColumnTypes(connection, targetTableName);
            }
            executeQuietly(connection, dialect.getStatementBeforeInsert(catalogName, schemaName, targetTableName,
                    identityIncluded));
            int rows = 0;
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                Map<Integer, Boolean> arrayParameters = new HashMap<>();
                for (Map<String, Object> row : rowList) {
                    int index = 0;
                    for (Object value : row.values()) {
                        if (value == null) {
                            ps.setNull(index + 1, nullTypes[index]);
                        } else {
                            if (isArrayText(value) && dialect.isArrayParameterSupported()
                                    && isArrayParameter(ps, index + 1, arrayParameters)) {
                                // Arrays kept as text (Parquet packages) are bound as arrays
                                value = parseArrayText(value.toString());
                            }
                            if (emptyLobTypes[index] == Types.BLOB && value instanceof byte[]
                                    && ((byte[]) value).length == 0) {
                                ps.setBlob(index + 1, connection.createBlob());
                            } else if (emptyLobTypes[index] == Types.CLOB && "".equals(value)) {
                                ps.setClob(index + 1, connection.createClob());
                            } else {
                                ps.setObject(index + 1, targetTypes.isEmpty() ? value
                                        : dialect.getTargetJdbcValue(value, targetTypes.get(columns[index]
                                        .toLowerCase(Locale.ENGLISH))));
                            }
                        }
                        index++;
                    }
                    ps.addBatch();
                }
                for (int n : ps.executeBatch()) {
                    rows += n > 0 ? n : (n == Statement.SUCCESS_NO_INFO ? 1 : 0);
                }
            }
            executeQuietly(connection, dialect.getStatementAfterInsert(catalogName, schemaName, targetTableName,
                    identityIncluded));
            if (transactional) {
                connection.commit();
            }
            if (log.isInfoEnabled()) {
                log.info("Execute dml: {}", insertSql);
                log.info("Add {} rows to table: {}", rows, tableName);
            }
        } catch (Exception e) {
            if (connection != null && transactional) {
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
            if (connection != null && transactional) {
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
            Dialect targetDialect = tableMetaData.getDialect();
            String sql = targetDialect.getResetIdentityStatement(catalogName, schemaName,
                    targetDialect.getTargetTableName(tableName), entry.getKey(), entry.getValue() + 1);
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
    /**
     * BLOB or CLOB if the target column is a LOB, 0 otherwise. The type registered by created tables is preferred,
     * the type of the source column is used when tables are not created.
     */
    /**
     * Type names of the columns of a target table by lower case column name, read once per table
     */
    private Map<String, String> getTargetColumnTypes(Connection connection, String targetTableName)
            throws SQLException {
        Map<String, String> types = targetColumnTypes.get(targetTableName);
        if (types != null) {
            return types;
        }
        types = new HashMap<>();
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(),
                connection.getSchema(), dialect.foldIdentifier(targetTableName), null)) {
            while (rs.next()) {
                types.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ENGLISH),
                        rs.getString("TYPE_NAME"));
            }
        }
        targetColumnTypes.put(targetTableName, types);
        return types;
    }

    static int getLobType(String targetTypeName, ColumnMetaData columnMetaData) {
        String typeName = StringUtils.defaultString(targetTypeName).toLowerCase(Locale.ENGLISH);
        if (typeName.isEmpty() && columnMetaData != null) {
            int dataType = TableMetaData.getInt(columnMetaData.getDetail(), "DATA_TYPE");
            return dataType == Types.BLOB ? Types.BLOB : dataType == Types.CLOB || dataType == Types.NCLOB
                    ? Types.CLOB : 0;
        }
        return typeName.equals("blob") ? Types.BLOB : typeName.equals("clob") || typeName.equals("nclob")
                ? Types.CLOB : 0;
    }

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

    static boolean isArrayText(Object value) {
        if (!(value instanceof CharSequence)) {
            return false;
        }
        String text = value.toString();
        return text.length() >= 2 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']';
    }

    /**
     * Whether the parameter is bound to an array column, the type is read once from parameter metadata
     */
    private static boolean isArrayParameter(PreparedStatement ps, int parameterIndex,
                                            Map<Integer, Boolean> arrayParameters) {
        return arrayParameters.computeIfAbsent(parameterIndex, index -> {
            try {
                return ps.getParameterMetaData().getParameterType(index) == Types.ARRAY;
            } catch (SQLException | RuntimeException e) {
                return false;
            }
        });
    }

    /**
     * Elements of an array text, e.g. [1, 2, 3] or [a, b], NULL elements are nulls
     */
    static Object[] parseArrayText(String text) {
        String content = text.substring(1, text.length() - 1).trim();
        if (content.isEmpty()) {
            return new Object[0];
        }
        String[] items = content.split(",\\s*");
        Object[] elements = new Object[items.length];
        for (int i = 0; i < items.length; i++) {
            String item = items[i].trim();
            if (item.length() >= 2 && item.startsWith("'") && item.endsWith("'")) {
                item = item.substring(1, item.length() - 1).replace("''", "'");
            }
            elements[i] = "NULL".equalsIgnoreCase(item) ? null : item;
        }
        return elements;
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
