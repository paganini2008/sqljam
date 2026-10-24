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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbImportHandler writes the rows of a collection into its table of the target page by page: a page
 *               is a batch of INSERT or upsert statements committed in its transaction. A page which fails is rolled
 *               back and, when errors are skipped, written again row by row, each row which fails is reported with
 *               its key. Without transactions every row is written and committed by itself. Like
 *               ImportExportHandler of the jdbc line.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class VdbImportHandler implements AutoCloseable {

    private final Connection connection;
    private final VdbCollectionMetaData metaData;
    private final VdbExportConfiguration configuration;
    private final VdbExportListener listener;
    private final VdbExportResult.TableResult result;
    private final VdbDialect dialect;
    private final List<VdbDialect.Column> columns;
    private final String sql;
    private final boolean transactional;
    private final boolean autoCommit;
    private PreparedStatement statement;

    public VdbImportHandler(Connection connection, VdbCollectionMetaData metaData, VdbExportConfiguration configuration,
                            VdbExportListener listener, VdbExportResult.TableResult result) throws SQLException {
        this.connection = connection;
        this.metaData = metaData;
        this.configuration = configuration;
        this.listener = listener;
        this.result = result;
        this.dialect = metaData.getDialect();
        this.columns = metaData.getColumns();
        boolean upsert = configuration.getWriteMode() == VdbExportConfiguration.WriteMode.UPSERT;
        if (upsert && !dialect.isUpsertSupported()) {
            throw new VdbException(dialect.getName() + " has no upserts, please insert the rows into a new table");
        }
        this.sql = upsert ? dialect.getUpsertStatement(metaData.getSchemaName(), metaData.getTableName(), columns,
                metaData.getPrimaryKeyColumnNames()) : dialect.getInsertStatement(metaData.getSchemaName(),
                metaData.getTableName(), columns);
        this.transactional = configuration.isTransactional() && dialect.isTransactionSupported();
        this.autoCommit = connection.getAutoCommit();
        if (transactional) {
            connection.setAutoCommit(false);
        }
        this.statement = connection.prepareStatement(sql);
        if (log.isInfoEnabled()) {
            log.info("Execute dml: {}", sql);
        }
    }

    public String getSql() {
        return sql;
    }

    /**
     * Writes the rows of a page, returns the rows written
     */
    public int write(List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        result.setRead(result.getRead() + rows.size());
        // A target without transactions writes a batch as one block, other targets commit each row by itself
        // when pages are not transactions
        int written = transactional || !dialect.isTransactionSupported() ? writeBatch(rows) : writeRowByRow(rows);
        result.setWritten(result.getWritten() + written);
        listener.onRowsWritten(result);
        return written;
    }

    private int writeBatch(List<Map<String, Object>> rows) throws SQLException {
        try {
            for (Map<String, Object> row : rows) {
                bind(row);
                statement.addBatch();
            }
            statement.executeBatch();
            if (transactional) {
                connection.commit();
            }
            return rows.size();
        } catch (SQLException | RuntimeException e) {
            clearBatch();
            if (transactional) {
                connection.rollback();
            }
            if (!configuration.isErrorSkipped()) {
                throw e;
            }
            if (log.isWarnEnabled()) {
                log.warn("Unable to write a page of {} rows into table {}, the rows are written one by one: {}",
                        rows.size(), metaData.getTableName(), e.getMessage());
            }
            return writeRowByRow(rows);
        }
    }

    private int writeRowByRow(List<Map<String, Object>> rows) throws SQLException {
        int written = 0;
        for (Map<String, Object> row : rows) {
            try {
                // Some drivers close a statement when it fails (DuckDB)
                if (statement.isClosed()) {
                    statement = connection.prepareStatement(sql);
                }
                bind(row);
                statement.executeUpdate();
                if (transactional) {
                    connection.commit();
                }
                written++;
            } catch (SQLException | RuntimeException e) {
                if (transactional) {
                    connection.rollback();
                }
                if (!configuration.isErrorSkipped()) {
                    throw e;
                }
                String key = getKey(row);
                result.setSkipped(result.getSkipped() + 1);
                if (log.isWarnEnabled()) {
                    log.warn("Skip row {} of table {}: {}", key, metaData.getTableName(), e.getMessage());
                }
                listener.onRowSkipped(result, key, e);
            }
        }
        return written;
    }

    private void bind(Map<String, Object> row) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            VdbDialect.Column column = columns.get(i);
            Object value = dialect.getJdbcValue(row.get(column.getField().getName()), column);
            if (value == null) {
                statement.setNull(i + 1, dialect.getNullSqlType(column));
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    private void clearBatch() {
        try {
            if (!statement.isClosed()) {
                statement.clearBatch();
            }
        } catch (SQLException ignored) {
            // The batch ended with the error
        }
    }

    /**
     * Key of a row in the log, e.g. namespace=ns1, id=doc-1
     */
    String getKey(Map<String, Object> row) {
        List<VdbDialect.Column> keys = columns.stream().filter(column -> column.getField().isPrimaryKey())
                .collect(Collectors.toList());
        List<String> parts = new ArrayList<>();
        for (VdbDialect.Column column : keys.isEmpty() ? columns.subList(0, Math.min(1, columns.size())) : keys) {
            parts.add(column.getName() + "=" + StringUtils.abbreviate(String.valueOf(row.get(column.getField()
                    .getName())), 80));
        }
        return String.join(", ", parts);
    }

    @Override
    public void close() throws SQLException {
        try {
            statement.close();
        } finally {
            if (transactional) {
                connection.setAutoCommit(autoCommit);
            }
        }
    }
}
