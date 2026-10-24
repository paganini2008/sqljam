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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbExporter imports collections of a vector database into a target database which has vectors, the
 *               pipeline of the vdb line: the features of the target are detected, the collections are described
 *               into the metadata tree, a visitor collects the statements of their tables into VdbDdlScripter, the
 *               tables are created, the records of the query are read page by page and written by
 *               VdbImportHandler, then the vector indexes are created. A target without vectors is refused, an index
 *               which the target can not create is reported and left out. Like Exporter of the jdbc line.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class VdbExporter {

    private final VdbClient client;
    private final DataSource dataSource;
    private final VdbExportConfiguration configuration;
    private final VdbExportListener listener;
    private volatile boolean cancelled;

    public VdbExporter(VdbClient client, DataSource dataSource, VdbExportConfiguration configuration) {
        this(client, dataSource, configuration, new VdbExportListener() {
        });
    }

    public VdbExporter(VdbClient client, DataSource dataSource, VdbExportConfiguration configuration,
                       VdbExportListener listener) {
        this.client = client;
        this.dataSource = dataSource;
        this.configuration = configuration;
        this.listener = listener;
    }

    /**
     * Stops the import after the page which is written
     */
    public void cancel() {
        cancelled = true;
    }

    private boolean isCancelled() {
        return cancelled || listener.isCancelled();
    }

    /**
     * Dialect of the target, which must have vectors
     */
    public static VdbDialect getDialect(Connection connection) throws SQLException {
        VdbDialect dialect = VdbDialect.of(connection);
        if (dialect == null) {
            throw new VdbException("Vector databases are imported into PostgreSQL, MySQL, MariaDB, Oracle,"
                    + " SQL Server, Db2, DuckDB and ClickHouse: " + connection.getMetaData()
                    .getDatabaseProductName());
        }
        if (!dialect.isVectorSupported()) {
            throw new VdbException(String.format("%s %s has no vectors (e.g. pgvector of PostgreSQL, MySQL 9.0,"
                            + " MariaDB 11.7, Oracle 23ai, SQL Server 2025, Db2 12.1.2), vector databases are imported"
                            + " into targets with vectors only", dialect.getName(), dialect.getProductVersion()));
        }
        return dialect;
    }

    /**
     * Statements of the tables of the collections, which are not executed
     */
    public List<String> getScripts() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            VdbDdlScripter scripter = visit(new VdbServerMetaData(client, prepareDialect(connection),
                    configuration));
            List<String> scripts = new ArrayList<>();
            scripter.getTables().forEach(table -> scripts.addAll(table.getScripts()));
            return scripts;
        }
    }

    private VdbDialect prepareDialect(Connection connection) throws SQLException {
        VdbDialect dialect = configuration.getDialect();
        if (dialect == null) {
            dialect = getDialect(connection);
            configuration.setDialect(dialect);
        } else {
            dialect.prepareSession(connection);
        }
        return dialect;
    }

    private VdbDdlScripter visit(VdbServerMetaData serverMetaData) throws SQLException {
        VdbDdlScripter scripter = new VdbDdlScripter();
        serverMetaData.accept(new DefaultVdbMetaDataVisitor(configuration, scripter));
        return scripter;
    }

    public VdbExportResult export() throws SQLException {
        VdbExportResult result = new VdbExportResult();
        try (Connection connection = dataSource.getConnection()) {
            VdbDialect dialect = prepareDialect(connection);
            VdbServerMetaData serverMetaData = new VdbServerMetaData(client, dialect, configuration);
            VdbDdlScripter scripter = visit(serverMetaData);
            for (VdbCollectionMetaData metaData : serverMetaData.getCollectionMetaData()) {
                if (isCancelled()) {
                    break;
                }
                VdbExportResult.TableResult tableResult = new VdbExportResult.TableResult(metaData.getCollection()
                        .getName(), metaData.getTableName());
                result.getTables().add(tableResult);
                exportCollection(connection, metaData, scripter.getTable(metaData.getSchemaName(),
                        metaData.getTableName()), tableResult);
            }
        }
        result.setCancelled(isCancelled());
        listener.onCompleted(result);
        return result;
    }

    private void exportCollection(Connection connection, VdbCollectionMetaData metaData, VdbDdlScripter.Table table,
                                  VdbExportResult.TableResult result) throws SQLException {
        long start = System.currentTimeMillis();
        VdbDialect dialect = metaData.getDialect();
        boolean existing = !configuration.isTableRecreated() && dialect.isTableExisting(connection,
                metaData.getSchemaName(), metaData.getTableName());
        if (!existing) {
            for (String script : table.getCreateScripts()) {
                if (table.getCommentStatements().contains(script)) {
                    executeQuietly(connection, script, result);
                } else {
                    execute(connection, script);
                    if (script.equals(table.getCreateTableScript())) {
                        listener.onTableCreated(result, script);
                    }
                }
            }
            result.setCreated(true);
        }
        VdbQuery query = configuration.getQuery() != null ? configuration.getQuery() : VdbQuery.all();
        result.setTotal(client.count(metaData.getCollection(), query));
        listener.onTableStarted(result);
        SQLException[] error = {null};
        try (VdbImportHandler handler = new VdbImportHandler(connection, metaData, configuration, listener, result)) {
            client.read(metaData.getCollection(), query, configuration.getPageSize(), (records, last) -> {
                try {
                    handler.write(records);
                } catch (SQLException e) {
                    error[0] = e;
                    return false;
                }
                return !isCancelled();
            });
        }
        if (error[0] != null) {
            throw error[0];
        }
        if (!existing) {
            // Indexes are built after the rows, an index which the target can not create is left out
            for (String script : table.getIndexScripts()) {
                if (executeQuietly(connection, script, result)) {
                    result.setIndexes(result.getIndexes() + 1);
                }
            }
        }
        result.setElapsed(System.currentTimeMillis() - start);
        listener.onTableFinished(result);
        if (log.isInfoEnabled()) {
            log.info("Import {} of {} rows of collection {} into table {}, {} skipped", result.getWritten(),
                    result.getRead(), result.getCollection(), result.getTableName(), result.getSkipped());
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        if (log.isInfoEnabled()) {
            log.info("Execute ddl: {}", sql);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
    }

    private boolean executeQuietly(Connection connection, String sql, VdbExportResult.TableResult result) {
        try {
            execute(connection, sql);
            return true;
        } catch (SQLException e) {
            try {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            } catch (SQLException ignored) {
                // The connection is closed
            }
            if (log.isWarnEnabled()) {
                log.warn("Skip statement {}: {}", sql, e.getMessage());
            }
            result.getWarnings().add(e.getMessage() + ": " + sql);
            listener.onStatementSkipped(result, sql, e);
            return false;
        }
    }
}
