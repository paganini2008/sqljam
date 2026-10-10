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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.jdbc.JdbcUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SqlScriptRunner executes sql scripts. A statement ends with ';' at the end of a line, a line with
 *               '/' (Oracle) or 'GO' (SQL Server). PL/SQL blocks end with 'END;' at the end of a line. Consecutive
 *               INSERT statements are executed in JDBC batches (Oracle: PL/SQL blocks), which is 2 to 10 times
 *               faster than statement by statement. When a batch fails, it is rolled back and executed statement by
 *               statement again, so that the failed statement is reported.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class SqlScriptRunner {

    private static final Pattern BLOCK_START = Pattern.compile(
            "(?is)^(BEGIN|DECLARE\\s+(?!@)|CREATE\\s+(OR\\s+REPLACE\\s+)?(TRIGGER|PROCEDURE|FUNCTION|PACKAGE|TYPE)\\b).*");
    /**
     * END; or END name; but not END IF; END LOOP; END CASE;
     */
    private static final Pattern BLOCK_END = Pattern.compile(
            "(?is).*\\bEND(\\s+(?!IF\\b|LOOP\\b|CASE\\b)[A-Za-z_][A-Za-z0-9_$]*)?\\s*;$");

    /**
     * Statements per JDBC batch. Measured: 1000 is close to the best of PostgreSQL, MySQL and SQL Server
     */
    public static final int DEFAULT_BATCH_SIZE = 1000;
    /**
     * INSERT statements per PL/SQL block of Oracle. Measured: 2.5 times faster than statement by statement, the
     * same from 100 to 1000. INSERT ALL is not used since it raises ORA-00600 with XMLTYPE columns and closes the
     * session.
     */
    public static final int ORACLE_BLOCK_SIZE = 100;

    private static final Pattern INSERT_STATEMENT = Pattern.compile("(?is)^INSERT\\s+INTO\\s.*");

    private final Connection connection;
    private boolean stopOnError = true;
    private int batchCommitSize = 500;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private Boolean oracle;
    private ExportListener exportListener = ExportListener.NONE;
    private Statement statement;
    /**
     * Whether statements run in a transaction, false if the database has no transactions (ClickHouse)
     */
    private boolean transactional;

    @Getter
    private int executedCount;
    @Getter
    private int failedCount;

    public SqlScriptRunner(Connection connection) {
        this.connection = connection;
    }

    public void setStopOnError(boolean stopOnError) {
        this.stopOnError = stopOnError;
    }

    public void setBatchCommitSize(int batchCommitSize) {
        this.batchCommitSize = batchCommitSize;
    }

    /**
     * Statements per batch, 1 or less executes statement by statement
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setExportListener(ExportListener exportListener) {
        this.exportListener = exportListener != null ? exportListener : ExportListener.NONE;
    }

    public void runScript(File file) throws IOException, SQLException {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            runScript(reader);
        }
    }

    public void runScript(String script) throws IOException, SQLException {
        runScript(new StringReader(script));
    }

    public void runScript(Reader reader) throws IOException, SQLException {
        boolean autoCommit = connection.getAutoCommit();
        transactional = JdbcUtils.beginTransaction(connection);
        statement = connection.createStatement();
        try {
            List<String> statements = parseStatements(reader);
            int total = statements.size();
            List<String> batch = new ArrayList<>();
            int uncommitted = 0;
            int index = 0;
            exportListener.onProgress(0, total);
            for (String sql : statements) {
                index++;
                if (exportListener.isCancelled()) {
                    throw new ExportCancelledException();
                }
                if (batchSize > 1 && isBatchable(sql)) {
                    if (batch.isEmpty() && uncommitted > 0) {
                        // A batch is rolled back alone when it fails
                        commit();
                        uncommitted = 0;
                    }
                    batch.add(sql);
                    if (batch.size() >= batchSize) {
                        executeBatch(batch);
                        exportListener.onProgress(index, total);
                    }
                    continue;
                }
                if (!batch.isEmpty()) {
                    executeBatch(batch);
                }
                if (execute(sql)) {
                    exportListener.onProgress(index, total);
                    if (++uncommitted >= batchCommitSize) {
                        commit();
                        uncommitted = 0;
                        exportListener.onMessage(String.format("%d statements executed", executedCount));
                    }
                }
            }
            if (!batch.isEmpty()) {
                executeBatch(batch);
            }
            commit();
            exportListener.onProgress(total, total);
        } finally {
            closeStatement();
            // A closed connection would hide the original exception
            if (transactional && !connection.isClosed()) {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Commits executed statements, they are committed already if the database has no transactions
     */
    private void commit() throws SQLException {
        if (transactional) {
            connection.commit();
        }
    }

    private void rollback() throws SQLException {
        if (transactional) {
            connection.rollback();
        }
    }

    /**
     * The statement in use. DuckDB closes a statement when its sql fails, a new one is created then.
     */
    private Statement statement() throws SQLException {
        if (statement == null || statement.isClosed()) {
            statement = connection.createStatement();
        }
        return statement;
    }

    private void closeStatement() {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                // Closed already
            }
            statement = null;
        }
    }

    static boolean isBatchable(String sql) {
        return INSERT_STATEMENT.matcher(sql).matches();
    }

    /**
     * Executes and commits a batch. A failed batch is rolled back and executed statement by statement
     */
    private void executeBatch(List<String> batch) throws SQLException {
        try {
            if (isOracle()) {
                for (String sql : getOracleBlockStatements(batch)) {
                    statement().execute(sql);
                }
            } else {
                for (String sql : batch) {
                    statement().addBatch(sql);
                }
                statement().executeBatch();
            }
            commit();
            executedCount += batch.size();
        } catch (SQLException e) {
            if (connection.isClosed()) {
                throw e;
            }
            statement().clearBatch();
            rollback();
            if (log.isDebugEnabled()) {
                log.debug("Batch failed, statements are executed one by one: {}", e.getMessage());
            }
            for (String sql : batch) {
                execute(sql);
            }
            commit();
        } finally {
            batch.clear();
        }
    }

    /**
     * Executes a statement, returns false if it fails and errors are ignored. When errors are ignored, a savepoint
     * keeps the transaction usable after a failure (PostgreSQL aborts the whole transaction otherwise)
     */
    private boolean execute(String sql) throws SQLException {
        Savepoint savepoint = stopOnError || !transactional ? null : connection.setSavepoint();
        try {
            statement().execute(sql);
            executedCount++;
            releaseSavepoint(savepoint);
            return true;
        } catch (SQLException e) {
            failedCount++;
            String message = String.format("Unable to execute sql: %s, cause: %s", StringUtils.abbreviate(sql, 500),
                    e.getMessage());
            if (log.isErrorEnabled()) {
                log.error(message);
            }
            exportListener.onError(message, e);
            if (stopOnError) {
                rollback();
                throw e;
            }
            if (savepoint != null) {
                connection.rollback(savepoint);
            }
            return false;
        }
    }

    private void releaseSavepoint(Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            // Oracle does not support releasing savepoints, they are released by commit
        }
    }

    private boolean isOracle() throws SQLException {
        if (oracle == null) {
            String productName = connection.getMetaData().getDatabaseProductName();
            oracle = productName != null && productName.toLowerCase(Locale.ENGLISH).contains("oracle");
        }
        return oracle;
    }

    /**
     * Combines INSERT statements into PL/SQL blocks of Oracle, which has no real batch of Statement:
     * BEGIN INSERT INTO t VALUES (1); INSERT INTO t VALUES (2); END;
     */
    static List<String> getOracleBlockStatements(List<String> batch) {
        List<String> sqls = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        int count = 0;
        for (String sql : batch) {
            if (count == 0) {
                block.append("BEGIN\n");
            }
            block.append(sql).append(";\n");
            if (++count >= ORACLE_BLOCK_SIZE) {
                sqls.add(block.append("END;").toString());
                block.setLength(0);
                count = 0;
            }
        }
        if (count > 0) {
            sqls.add(block.append("END;").toString());
        }
        return sqls;
    }

    /**
     * Splits script into statements without end marks (except PL/SQL blocks which keep 'END;')
     */
    public static List<String> parseStatements(String script) throws IOException {
        return parseStatements(new StringReader(script));
    }

    public static List<String> parseStatements(Reader reader) throws IOException {
        List<String> statements = new ArrayList<>();
        BufferedReader bufferedReader = new BufferedReader(reader);
        StringBuilder current = new StringBuilder();
        // Quote char of the unterminated string literal across lines
        char quote = 0;
        boolean blockComment = false;
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            String trimmed = line.trim();
            if (quote == 0 && !blockComment) {
                if (current.length() == 0 && (trimmed.isEmpty() || trimmed.startsWith("--"))) {
                    continue;
                }
                if ("/".equals(trimmed) || "GO".equalsIgnoreCase(trimmed)) {
                    addStatement(statements, current.toString(), true);
                    current.setLength(0);
                    continue;
                }
            }
            StringBuilder content = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                if (blockComment) {
                    if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                        blockComment = false;
                        i += 2;
                        continue;
                    }
                    i++;
                    continue;
                }
                if (quote != 0) {
                    content.append(c);
                    if (c == quote) {
                        if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
                            content.append(quote);
                            i += 2;
                            continue;
                        }
                        quote = 0;
                    } else if (c == '\\' && quote == '`') {
                        // no escape in identifiers
                    }
                    i++;
                    continue;
                }
                if (c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                    break;
                }
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*'
                        && !(i + 2 < line.length() && line.charAt(i + 2) == '!')) {
                    blockComment = true;
                    i += 2;
                    continue;
                }
                if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                }
                content.append(c);
                i++;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(content);
            if (quote == 0 && !blockComment) {
                String sql = StringUtils.stripEnd(current.toString(), null);
                if (sql.endsWith(";")) {
                    String trimmedSql = sql.trim();
                    boolean block = BLOCK_START.matcher(trimmedSql).matches();
                    if (!block || BLOCK_END.matcher(trimmedSql).matches()) {
                        addStatement(statements, trimmedSql, block);
                        current.setLength(0);
                    }
                }
            }
        }
        addStatement(statements, current.toString(), BLOCK_START.matcher(current.toString().trim()).matches());
        return statements;
    }

    private static void addStatement(List<String> statements, String sql, boolean block) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (block) {
            if (!trimmed.endsWith(";") && trimmed.toUpperCase(Locale.ENGLISH).endsWith("END")) {
                trimmed = trimmed + ";";
            }
            statements.add(trimmed);
        } else {
            while (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
    }
}
