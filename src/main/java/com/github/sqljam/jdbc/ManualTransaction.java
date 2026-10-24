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
package com.github.sqljam.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ManualTransaction runs an import in one transaction which is committed or rolled back by the user
 *               afterwards. Importers work with {@link #getConnection()}: their commits are ignored, auto commit
 *               stays off, and a rollback (after an error) rolls back the whole transaction, so an import is all or
 *               nothing. Databases which commit around DDL statements (MySQL, MariaDB, Oracle, H2) would commit the
 *               written rows with the first DDL statement after them, such statements (foreign keys, identity and
 *               sequence values) are kept and executed after {@link #commit()}.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class ManualTransaction implements AutoCloseable {

    private static final Pattern DDL = Pattern.compile("^(CREATE|ALTER|DROP|TRUNCATE|RENAME|COMMENT|GRANT|REVOKE)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PLSQL_DDL = Pattern.compile("^(BEGIN|DECLARE)\\b.*EXECUTE\\s+IMMEDIATE.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DML = Pattern.compile("^(INSERT|UPDATE|DELETE|MERGE|UPSERT|REPLACE)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /**
     * PL/SQL blocks of INSERT statements, e.g. batches of Oracle scripts
     */
    private static final Pattern PLSQL_DML = Pattern.compile("^(BEGIN|DECLARE)\\b.*\\b(INSERT|UPDATE|DELETE|MERGE)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * DELETE of all rows of a table, which is emptied before it is dropped (Access)
     */
    private static final Pattern EMPTY_TABLE = Pattern.compile("^DELETE\\s+FROM\\s+(\\S+)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE = Pattern.compile("^DROP\\s+TABLE\\s+(\\S+)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final Connection connection;
    private final boolean transactionalDdl;
    private final Connection proxy;
    private final List<String> deferredStatements = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean rowsWritten;
    private final Set<String> emptiedTables = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean rolledBack;
    private volatile boolean finished;

    /**
     * @param connection       the connection of the target database, it is closed by {@link #close()}
     * @param transactionalDdl whether DDL statements are part of the transaction, e.g. PostgreSQL
     */
    public ManualTransaction(Connection connection, boolean transactionalDdl) throws SQLException {
        this.connection = connection;
        this.transactionalDdl = transactionalDdl;
        connection.setAutoCommit(false);
        this.proxy = (Connection) Proxy.newProxyInstance(ManualTransaction.class.getClassLoader(),
                new Class<?>[]{Connection.class, Managed.class}, (target, method, args) -> invokeConnection(method,
                        args));
    }

    /**
     * Marks the connection of a transaction, its commits and roll backs belong to the whole import
     */
    public interface Managed {
    }

    /**
     * Whether the connection is the connection of a transaction which is committed at the end of the import
     */
    public static boolean isManaged(Connection connection) {
        if (connection instanceof Managed) {
            return true;
        }
        // Connections shared by a connection factory are unwrapped
        try {
            Connection unwrapped = connection.unwrap(Connection.class);
            return unwrapped != connection && unwrapped instanceof Managed;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Connection for importers, it is not closed by them
     */
    public Connection getConnection() {
        return proxy;
    }

    /**
     * Statements executed after the commit, they would commit the transaction (DDL after written rows)
     */
    public List<String> getDeferredStatements() {
        synchronized (deferredStatements) {
            return new ArrayList<>(deferredStatements);
        }
    }

    /**
     * Whether the transaction was rolled back by an importer, e.g. after an error
     */
    public boolean isRolledBack() {
        return rolledBack;
    }

    public boolean isTransactionalDdl() {
        return transactionalDdl;
    }

    public boolean isFinished() {
        return finished;
    }

    /**
     * Commits the transaction, then executes the deferred statements one by one
     *
     * @return errors of deferred statements, empty if all of them are executed
     */
    public List<String> commit() throws SQLException {
        checkOpen();
        connection.commit();
        finished = true;
        List<String> errors = new ArrayList<>();
        for (String sql : getDeferredStatements()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                connection.commit();
            } catch (SQLException e) {
                errors.add(sql + ": " + e.getMessage());
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // The next statement runs in a new transaction
                }
            }
        }
        if (log.isInfoEnabled()) {
            log.info("Transaction committed, {} deferred statements, {} failed", deferredStatements.size(),
                    errors.size());
        }
        return errors;
    }

    public void rollback() throws SQLException {
        checkOpen();
        connection.rollback();
        finished = true;
        deferredStatements.clear();
        if (log.isInfoEnabled()) {
            log.info("Transaction rolled back");
        }
    }

    private void checkOpen() {
        if (finished) {
            throw new IllegalStateException("The transaction is committed or rolled back already");
        }
    }

    /**
     * Rolls back an open transaction and closes the connection
     */
    @Override
    public void close() {
        try {
            if (!finished && !connection.isClosed()) {
                connection.rollback();
                finished = true;
            }
        } catch (SQLException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to roll back the transaction: {}", e.getMessage());
            }
        }
        JdbcUtils.closeQuietly(connection);
    }

    private Object invokeConnection(Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "commit":
            case "close":
                return null;
            case "setAutoCommit":
                // Auto commit stays off until the user decides
                return null;
            case "getAutoCommit":
                return false;
            case "rollback":
                if (args == null || args.length == 0) {
                    rolledBack = true;
                }
                break;
            case "unwrap":
                if (args != null && args.length == 1 && args[0] == Connection.class) {
                    return proxy;
                }
                break;
            case "prepareStatement":
                if (args != null && args.length > 0 && args[0] instanceof String && !isDdl((String) args[0])
                        && isDml((String) args[0])) {
                    rowsWritten = true;
                }
                break;
            default:
                break;
        }
        Object result = invoke(connection, method, args);
        if (result instanceof Statement && "createStatement".equals(method.getName())) {
            Statement statement = (Statement) result;
            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                    (target, statementMethod, statementArgs) -> invokeStatement(statement, statementMethod,
                            statementArgs));
        }
        return result;
    }

    /**
     * DDL after written rows is deferred if it would commit them, a deferred statement reports no update
     */
    private Object invokeStatement(Statement statement, Method method, Object[] args) throws Throwable {
        if ("getConnection".equals(method.getName())) {
            return proxy;
        }
        if (args != null && args.length > 0 && args[0] instanceof String) {
            String sql = ((String) args[0]).trim();
            switch (method.getName()) {
                case "execute":
                case "executeUpdate":
                case "executeLargeUpdate":
                case "addBatch":
                    if (isDdl(sql)) {
                        if (!emptiedTables.isEmpty() && !emptiedTables.remove(getTableName(DROP_TABLE, sql))) {
                            // DDL commits the rows deleted from a table which is not dropped
                            rowsWritten = true;
                        }
                        if (shouldDefer()) {
                            deferredStatements.add(sql);
                            if (log.isInfoEnabled()) {
                                log.info("Deferred until the commit: {}", sql);
                            }
                            return defaultResult(method.getReturnType());
                        }
                    } else if (getTableName(EMPTY_TABLE, sql) != null) {
                        // The rows are discarded with the table if it is dropped next
                        emptiedTables.add(getTableName(EMPTY_TABLE, sql));
                    } else if (isDml(sql)) {
                        rowsWritten = true;
                    }
                    break;
                default:
                    break;
            }
        }
        return invoke(statement, method, args);
    }

    private boolean shouldDefer() {
        return !transactionalDdl && rowsWritten;
    }

    /**
     * Table of a statement of the pattern, unquoted and in lower case, null if the statement does not match
     */
    static String getTableName(Pattern pattern, String sql) {
        Matcher matcher = pattern.matcher(sql.trim());
        return matcher.matches() ? matcher.group(1).replaceAll("[`\"\\[\\]]", "").toLowerCase(Locale.ENGLISH)
                : null;
    }

    static boolean isDdl(String sql) {
        String text = sql.trim();
        return DDL.matcher(text).matches() || PLSQL_DDL.matcher(text).matches();
    }

    static boolean isDml(String sql) {
        String text = sql.trim();
        return DML.matcher(text).matches() || PLSQL_DML.matcher(text).matches();
    }

    private static Object defaultResult(Class<?> type) {
        if (type == boolean.class) {
            return false;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ENGLISH, "ManualTransaction[rowsWritten=%s, deferred=%d, rolledBack=%s]",
                rowsWritten, deferredStatements.size(), rolledBack);
    }
}
