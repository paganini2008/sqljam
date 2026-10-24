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
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DdlScripter.Catalog;
import com.github.sqljam.impexp.DdlScripter.Schema;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.jdbc.ManualTransaction;
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
        /**
         * Rows are inserted, upserted (the rows of the same key are updated) or inserted unless their key exists
         */
        private WriteMode writeMode = WriteMode.INSERT;
        /**
         * Key columns of upserts by source table name, the primary key of the table by default
         */
        private Map<String, List<String>> keyColumnNames = new HashMap<>();
        /**
         * Whether target tables are dropped and created, existing tables, indexes and constraints are skipped when
         * they are kept
         */
        private boolean tableRecreated = true;
        /**
         * Rows with errors are skipped: a failed page is written row by row and the failed rows are reported.
         * Otherwise the error ends the page.
         */
        private boolean skipErrors;
    }

    private ConnectionFactory connectionFactory;
    private Dialect dialect;
    private ExportListener exportListener = ExportListener.NONE;
    private final IdentityValueTracker identityValueTracker = new IdentityValueTracker();
    private final Map<String, Map<String, String>> targetColumnTypes = new ConcurrentHashMap<>();
    private final Map<String, String[]> keyColumns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> skippedRows = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> targetNames = new ConcurrentHashMap<>();

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
                int[] version = dialect.readDatabaseVersion(databaseMetaData);
                dialect = dialect.forVersion(version[0], version[1]);
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
            // Kept tables are not dropped: their tables, indexes and constraints exist and are skipped
            boolean existingSkipped = !configuration.isTableRecreated();
            for (String sql : sqls) {
                sql = SqlTextUtils.removeEndMark(sql);
                try {
                    if (existingSkipped && !executeUnlessExists(connection, sql)) {
                        continue;
                    }
                    if (!existingSkipped) {
                        JdbcUtils.execute(connection, sql);
                    }
                    if (log.isInfoEnabled()) {
                        log.info("Execute ddl: {}", sql);
                    }
                } catch (Exception e) {
                    // A drop without IF EXISTS of a missing table (Access)
                    if (e instanceof SQLException && dialect.isMissingDropError(sql, (SQLException) e)) {
                        if (log.isInfoEnabled()) {
                            log.info("Does not exist, skipped: {}", sql);
                        }
                        continue;
                    }
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

    /**
     * Executes DDL of an object which may exist. In a transaction a savepoint keeps the transaction usable after
     * the error (PostgreSQL aborts it otherwise).
     *
     * @return false if the object exists and the statement is skipped
     */
    private boolean executeUnlessExists(Connection connection, String sql) throws SQLException {
        Savepoint savepoint = null;
        if (!connection.getAutoCommit()) {
            try {
                savepoint = connection.setSavepoint();
            } catch (SQLException e) {
                // Databases without savepoints
            }
        }
        try {
            JdbcUtils.execute(connection, sql);
            releaseQuietly(connection, savepoint);
            return true;
        } catch (SQLException e) {
            if (savepoint != null) {
                try {
                    connection.rollback(savepoint);
                } catch (SQLException ignored) {
                    // DDL of some databases ends the transaction and its savepoints
                }
            }
            if (!dialect.isAlreadyExistsError(e)) {
                throw e;
            }
            if (log.isInfoEnabled()) {
                log.info("Exists already, skipped: {}", sql);
            }
            return false;
        }
    }

    private static void releaseQuietly(Connection connection, Savepoint savepoint) {
        if (savepoint != null) {
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException ignored) {
                // Released by the end of the transaction, e.g. DDL of MySQL
            }
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

        // Labels of a query may differ in case from its metadata, e.g. ID and id of UCanAccess
        Map<String, String> columnsInLowerCase = new HashMap<>();
        template.keySet().forEach(name -> columnsInLowerCase.putIfAbsent(name.toLowerCase(Locale.ENGLISH), name));
        for (Map<String, Object> data : dataList) {
            Map<String, Object> row = new LinkedHashMap<>(template);
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String column = row.containsKey(entry.getKey()) ? entry.getKey()
                        : columnsInLowerCase.get(entry.getKey().toLowerCase(Locale.ENGLISH));
                if (column != null) {
                    ColumnMetaData columnMetaData = tableMetaData.findColumnMetaData(column).orElse(null);
                    row.put(column, ScriptExportHandler.getJdbcValue(dialect, columnMetaData, entry.getValue()));
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
        boolean upsert = configuration.getWriteMode().isKeyed();
        String insertSql = upsert ? null : dialect.getInsertTableStatement(catalogName, schemaName, targetTableName,
                columns);
        Connection connection = null;
        boolean autoCommit = true;
        boolean transactional = false;
        try {
            connection = getConnection(catalogName, schemaName);
            autoCommit = connection.getAutoCommit();
            transactional = JdbcUtils.beginTransaction(connection);
            // An existing table is written by its own names, names which differ in case only are matched
            Map<String, String> existingNames = getTargetNames(connection, schemaName, targetTableName);
            if (!existingNames.isEmpty()) {
                dialect.addExactIdentifiers(existingNames.values());
                targetTableName = existingNames.get("");
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = existingNames.getOrDefault(columns[i].toLowerCase(Locale.ENGLISH), columns[i]);
                }
                if (!upsert) {
                    insertSql = dialect.getInsertTableStatement(catalogName, schemaName, targetTableName, columns);
                }
            }
            if (dialect.isTargetTypeConversionRequired()) {
                targetTypes = getTargetColumnTypes(connection, targetTableName);
            }
            String[] keys = upsert ? getKeyColumnNames(connection, schemaName, tableName, targetTableName,
                    tableMetaData, columns) : null;
            if (keys != null && keys.length == 0) {
                // A table without key is inserted
                upsert = false;
                insertSql = dialect.getInsertTableStatement(catalogName, schemaName, targetTableName, columns);
            }
            if (upsert) {
                Set<String> fixed = new HashSet<>();
                Arrays.stream(keys).forEach(name -> fixed.add(name.toLowerCase(Locale.ENGLISH)));
                // Identity values of existing rows are kept
                identityColumnNames.forEach(name -> fixed.add(name.toLowerCase(Locale.ENGLISH)));
                if (!dialect.isIndexedColumnUpdatable()) {
                    fixed.addAll(dialect.getIndexedColumns(connection, getTargetCatalog(connection),
                            getTargetSchema(connection, schemaName), dialect.foldIdentifier(targetTableName)));
                    tableMetaData.getIndexMetaDatas().forEach(index -> fixed.add(index.getColumnName().toLowerCase(
                            Locale.ENGLISH)));
                }
                // Existing rows are kept when they are skipped
                String[] updates = configuration.getWriteMode() == WriteMode.SKIP_EXISTING ? new String[0]
                        : Arrays.stream(columns).filter(name -> !fixed.contains(name.toLowerCase(Locale.ENGLISH)))
                        .toArray(String[]::new);
                Map<String, String> columnTypes = targetTypes;
                insertSql = dialect.getUpsertStatement(catalogName, schemaName, targetTableName, columns, keys,
                        updates, Arrays.stream(columns).map(name -> dialect.getParameterMarker(columnTypes.get(
                                name.toLowerCase(Locale.ENGLISH)))).toArray());
            }
            executeQuietly(connection, dialect.getStatementBeforeInsert(catalogName, schemaName, targetTableName,
                    identityIncluded));
            int rows = 0;
            Set<String> arrayColumns = upsert && dialect.isArrayParameterSupported() ? getTargetArrayColumns(
                    connection, schemaName, targetTableName) : Collections.emptySet();
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                Map<Integer, Boolean> arrayParameters = new HashMap<>();
                final Map<String, String> types = targetTypes;
                final Connection target = connection;
                RowBinder binder = (statement, row) -> bindRow(target, statement, row, columns, nullTypes,
                        emptyLobTypes, types, arrayColumns, arrayParameters);
                Savepoint savepoint = configuration.isSkipErrors() && transactional ? setSavepointQuietly(connection)
                        : null;
                try {
                    for (Map<String, Object> row : rowList) {
                        binder.bind(ps, row);
                        ps.addBatch();
                    }
                    int[] counts = ps.executeBatch();
                    releaseQuietly(connection, savepoint);
                    if (upsert) {
                        // Updated rows count twice in MySQL, every row is written once
                        rows = rowList.size();
                    } else {
                        for (int n : counts) {
                            rows += n > 0 ? n : (n == Statement.SUCCESS_NO_INFO ? 1 : 0);
                        }
                    }
                } catch (SQLException e) {
                    if (!configuration.isSkipErrors()) {
                        throw e;
                    }
                    // Rows are named by their keys in the log, or by their first columns
                    List<String> named = keys != null && keys.length > 0 ? Arrays.asList(keys)
                            : tableMetaData.getPrimaryKeyColumnNames();
                    rows = writeRowByRow(connection, ps, insertSql, savepoint, transactional, rowList, binder, tableName,
                            named.isEmpty() ? Arrays.asList(columns).subList(0, Math.min(3, columns.length))
                                    : named, e);
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

    /**
     * Binds the values of a row to the parameters of the statement
     */
    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement ps, Map<String, Object> row) throws SQLException;
    }

    private void bindRow(Connection connection, PreparedStatement ps, Map<String, Object> row, String[] columns,
                         int[] nullTypes, int[] emptyLobTypes, Map<String, String> targetTypes,
                         Set<String> arrayColumns, Map<Integer, Boolean> arrayParameters) throws SQLException {
        int index = 0;
        for (Object value : row.values()) {
            if (value == null) {
                ps.setNull(index + 1, nullTypes[index]);
            } else {
                if (isArrayText(value) && dialect.isArrayParameterSupported()
                        && (arrayColumns.contains(columns[index].toLowerCase(Locale.ENGLISH))
                        || isArrayParameter(ps, index + 1, arrayParameters))) {
                    // Arrays kept as text (Parquet packages) are bound as arrays
                    value = parseArrayText(value.toString());
                }
                if (emptyLobTypes[index] == Types.BLOB && value instanceof byte[] && ((byte[]) value).length == 0) {
                    ps.setBlob(index + 1, connection.createBlob());
                } else if (emptyLobTypes[index] == Types.CLOB && "".equals(value)) {
                    ps.setClob(index + 1, connection.createClob());
                } else {
                    ps.setObject(index + 1, targetTypes.isEmpty() ? value : dialect.getTargetJdbcValue(value,
                            targetTypes.get(columns[index].toLowerCase(Locale.ENGLISH))));
                }
            }
            index++;
        }
    }

    /**
     * Writes the rows of a failed page one by one and skips the rows with errors, each skipped row is reported
     * with its key and its error. Each row has its own savepoint, or its own commit when the database has no
     * savepoints. A transaction of the whole import without savepoints (DuckDB) can not go on after an error, the
     * error is thrown.
     *
     * @param namedColumns columns which name a row in the log, e.g. its key
     * @return rows written
     */
    private int writeRowByRow(Connection connection, PreparedStatement ps, String sql, Savepoint pageSavepoint,
                              boolean transactional, List<Map<String, Object>> rowList, RowBinder binder,
                              String tableName, List<String> namedColumns, SQLException batchError)
            throws SQLException {
        try {
            ps.clearBatch();
        } catch (SQLException ignored) {
            // The batch ended with the error
        }
        boolean savepoints = pageSavepoint != null;
        if (transactional) {
            if (savepoints) {
                connection.rollback(pageSavepoint);
            } else if (ManualTransaction.isManaged(connection)) {
                // The transaction can not go on after the error, the whole import is rolled back
                connection.rollback();
                throw new SQLException(String.format("Rows of table '%s' with errors can not be skipped in one"
                        + " transaction without savepoints, the import is rolled back: %s", tableName,
                        batchError.getMessage()), batchError.getSQLState(), batchError.getErrorCode(), batchError);
            } else {
                // Rows of this page are written again, rows of former pages are committed already
                connection.rollback();
            }
        }
        int rows = 0;
        PreparedStatement statement = ps;
        try {
            for (Map<String, Object> row : rowList) {
                // DuckDB closes a statement when it fails
                if (statement.isClosed()) {
                    statement = connection.prepareStatement(sql);
                }
                if (writeRow(connection, statement, row, binder, savepoints, transactional, tableName,
                        namedColumns)) {
                    rows++;
                }
            }
        } finally {
            if (statement != ps) {
                JdbcUtils.closeQuietly(statement);
            }
        }
        return rows;
    }

    /**
     * Writes a row in its own savepoint or commit, a row with an error is rolled back and reported
     *
     * @return whether the row is written
     */
    private boolean writeRow(Connection connection, PreparedStatement statement, Map<String, Object> row,
                             RowBinder binder, boolean savepoints, boolean transactional, String tableName,
                             List<String> namedColumns) throws SQLException {
        Savepoint savepoint = savepoints ? connection.setSavepoint() : null;
        try {
            binder.bind(statement, row);
            statement.executeUpdate();
            if (savepoints) {
                releaseQuietly(connection, savepoint);
            } else if (transactional) {
                connection.commit();
            }
            return true;
        } catch (SQLException e) {
            if (savepoints) {
                connection.rollback(savepoint);
            } else if (transactional) {
                connection.rollback();
            }
            skippedRows.computeIfAbsent(tableName, name -> new AtomicLong()).incrementAndGet();
            String message = String.format("Skipped row of table '%s' (%s): %s", tableName,
                    describeRow(row, namedColumns), e.getMessage());
            if (log.isWarnEnabled()) {
                log.warn(message);
            }
            exportListener.onError(message, e);
            return false;
        }
    }

    /**
     * Rows of the table which were skipped because of errors since the last call
     */
    public long takeSkippedRows(String tableName) {
        AtomicLong rows = skippedRows.remove(tableName);
        return rows != null ? rows.get() : 0;
    }

    /**
     * Values of the named columns of a row, e.g. ID=5, long values are abbreviated
     */
    static String describeRow(Map<String, Object> row, List<String> namedColumns) {
        List<String> values = new ArrayList<>();
        for (String name : namedColumns) {
            Object value = row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            String text = value instanceof byte[] ? "<" + ((byte[]) value).length + " bytes>"
                    : String.valueOf(value);
            values.add(name + "=" + StringUtils.abbreviate(text, 50));
        }
        return String.join(", ", values);
    }

    private static Savepoint setSavepointQuietly(Connection connection) {
        try {
            return connection.setSavepoint();
        } catch (SQLException e) {
            // Databases without savepoints, e.g. DuckDB
            return null;
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
            // The existing table by its own name
            String targetTableName = targetDialect.getTargetTableName(tableName);
            Map<String, String> existingNames = targetNames.getOrDefault(targetTableName, Collections.emptyMap());
            String sql = targetDialect.getResetIdentityStatement(catalogName, schemaName, existingNames.getOrDefault(
                    "", targetTableName), existingNames.getOrDefault(entry.getKey().toLowerCase(Locale.ENGLISH),
                    entry.getKey()), entry.getValue() + 1);
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
    /**
     * Key columns of upserts: the chosen columns, or the primary key of the source table or of the existing target
     * table (e.g. rows of files). Indexes are not guessed, a table without key is inserted.
     *
     * @return key columns as named in the imported columns, empty if the table has no key
     */
    private String[] getKeyColumnNames(Connection connection, String schemaName, String tableName,
                                       String targetTableName, TableMetaData tableMetaData, String[] columns)
            throws SQLException {
        String[] cached = keyColumns.get(tableName);
        if (cached != null) {
            return cached;
        }
        List<String> keys = configuration.getKeyColumnNames().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(tableName)).map(Map.Entry::getValue).findFirst()
                .orElse(null);
        if (CollectionUtils.isEmpty(keys)) {
            keys = tableMetaData.getPrimaryKeyColumnNames();
        }
        if (CollectionUtils.isEmpty(keys)) {
            keys = dialect.getPrimaryKeyColumns(connection, getTargetCatalog(connection), getTargetSchema(connection,
                    schemaName), dialect.foldIdentifier(targetTableName));
        }
        if (CollectionUtils.isEmpty(keys)) {
            exportListener.onMessage(String.format("Table %s has no primary key and no chosen key columns, its rows "
                    + "are inserted", tableName));
            keyColumns.put(tableName, new String[0]);
            return keyColumns.get(tableName);
        }
        String[] resolved = new String[keys.size()];
        for (int i = 0; i < resolved.length; i++) {
            String key = keys.get(i);
            resolved[i] = Arrays.stream(columns).filter(column -> column.equalsIgnoreCase(key)).findFirst()
                    .orElseThrow(() -> new ImpExpException(String.format("Key column %s of table %s is not imported",
                            key, tableName)));
        }
        keyColumns.put(tableName, resolved);
        return resolved;
    }

    /**
     * Names of an existing target table and its columns by their lower case names, the table name under the empty
     * key. Empty if the table does not exist or its names can not be read, the names of the import are used then.
     */
    private Map<String, String> getTargetNames(Connection connection, String schemaName, String targetTableName) {
        Map<String, String> names = targetNames.get(targetTableName);
        if (names != null) {
            return names;
        }
        names = new HashMap<>();
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = getTargetCatalog(connection);
            String schema = getTargetSchema(connection, schemaName);
            String table = findTargetTable(metaData, catalog, schema, targetTableName);
            if (table != null) {
                try (ResultSet rs = metaData.getColumns(catalog, schema, table, null)) {
                    while (rs.next()) {
                        String column = rs.getString("COLUMN_NAME");
                        names.putIfAbsent(column.toLowerCase(Locale.ENGLISH), column);
                    }
                }
                if (!names.isEmpty()) {
                    names.put("", table);
                }
            }
        } catch (SQLException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to read names of target table {}: {}", targetTableName, e.getMessage());
            }
            names.clear();
        }
        targetNames.put(targetTableName, names);
        return names;
    }

    /**
     * Name of the existing target table: the name of the import, its folded name, or a name which differs in case
     * only (e.g. Orders of MySQL on Linux for orders)
     */
    private String findTargetTable(DatabaseMetaData metaData, String catalog, String schema, String tableName)
            throws SQLException {
        for (String candidate : new String[]{dialect.foldIdentifier(tableName), tableName, null}) {
            try (ResultSet rs = metaData.getTables(catalog, schema, candidate, null)) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null && name.equalsIgnoreCase(tableName)) {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Catalog of the target table: the target catalog or the catalog of the connection
     */
    private String getTargetCatalog(Connection connection) throws SQLException {
        return StringUtils.defaultIfBlank(configuration.getTargetCatalogName(), connection.getCatalog());
    }

    /**
     * Schema of the target table as the import resolves it, the schema of the connection (e.g. dbo of SQL Server)
     * is only the default
     */
    private String getTargetSchema(Connection connection, String schemaName) throws SQLException {
        String schema = dialect != null ? dialect.getTargetSchemaName(schemaName) : configuration.getTargetSchemaName();
        return StringUtils.defaultIfBlank(schema, connection.getSchema());
    }

    /**
     * Array columns of the target table, for statements whose parameters have no types (e.g. MERGE of H2)
     */
    private Set<String> getTargetArrayColumns(Connection connection, String schemaName, String targetTableName)
            throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = connection.getMetaData().getColumns(getTargetCatalog(connection),
                getTargetSchema(connection, schemaName), dialect.foldIdentifier(targetTableName), null)) {
            while (rs.next()) {
                String typeName = StringUtils.defaultString(rs.getString("TYPE_NAME")).toUpperCase(Locale.ENGLISH);
                if (rs.getInt("DATA_TYPE") == Types.ARRAY || typeName.endsWith("ARRAY") || typeName.endsWith("[]")) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ENGLISH));
                }
            }
        }
        return columns;
    }

    private Map<String, String> getTargetColumnTypes(Connection connection, String targetTableName)
            throws SQLException {
        Map<String, String> types = targetColumnTypes.get(targetTableName);
        if (types != null) {
            return types;
        }
        types = new HashMap<>();
        // Schemas of Db2 are padded with blanks
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(),
                StringUtils.trimToNull(connection.getSchema()), dialect.foldIdentifier(targetTableName), null)) {
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
