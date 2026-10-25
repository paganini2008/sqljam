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
package com.github.sqljam.face.service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.MetricSource.Chart;
import com.github.sqljam.face.service.MetricSource.Series;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: JdbcMetrics reads the metrics of the dashboard of a data source by SQL, as many as the server tells:
 *               sessions, transactions, statements, rows, cache, locks, network, CPU, memory and size, from the
 *               statistics views of each database. Embedded databases (H2, SQLite, Access, DuckDB) run in SqlJam,
 *               their dashboard shows the CPU and the memory of SqlJam and the size of their file. A metric which
 *               can not be read is told once with its reason and not read again, a connection which was lost is
 *               opened again.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class JdbcMetrics implements MetricSource {

    static final String SAMPLE_TIME = "sample.millis";

    @FunctionalInterface
    interface Probe {
        Double read(Context context) throws Exception;
    }

    /**
     * Queries of one sample, each runs once whatever the number of metrics it gives
     */
    static final class Context {

        private final Connection connection;
        private final Map<String, Object> results = new HashMap<>();

        Context(Connection connection) {
            this.connection = connection;
        }

        Connection getConnection() {
            return connection;
        }

        private Object cached(String sql, boolean pairs) throws Exception {
            Object result = results.computeIfAbsent((pairs ? "P:" : "R:") + sql, key -> {
                try {
                    return pairs ? readPairs(connection, sql) : readRow(connection, sql);
                } catch (Exception e) {
                    return e;
                }
            });
            if (result instanceof Exception) {
                throw (Exception) result;
            }
            return result;
        }

        /**
         * Names and values of the rows of a query of two columns
         */
        @SuppressWarnings("unchecked")
        Map<String, Double> pairs(String sql) throws Exception {
            return (Map<String, Double>) cached(sql, true);
        }

        /**
         * Values of the first row by the names of the columns in upper case
         */
        @SuppressWarnings("unchecked")
        Map<String, Double> row(String sql) throws Exception {
            return (Map<String, Double>) cached(sql, false);
        }
    }

    private final ConnectionProfile profile;
    private final DatabaseSession session;
    private final List<Chart> charts = new ArrayList<>();
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    private final Map<String, String> unavailable = new LinkedHashMap<>();
    private Connection connection;

    public JdbcMetrics(ConnectionProfile profile, DatabaseSession session) {
        this.profile = profile;
        this.session = session;
        define();
        chart("Sample time", Unit.MILLIS, Series.gauge("All metrics", SAMPLE_TIME));
    }

    @Override
    public List<Chart> getCharts() {
        return charts;
    }

    @Override
    public synchronized Map<String, String> getUnavailable() {
        return new LinkedHashMap<>(unavailable);
    }

    private void chart(String title, Unit unit, Series... series) {
        charts.add(new Chart(title, unit, series));
    }

    private void probe(String key, Probe probe) {
        probes.put(key, probe);
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Map<String, Double> readPairs(Connection connection, String sql) throws SQLException {
        Map<String, Double> values = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                Double value = number(rs.getObject(2));
                if (name != null && value != null) {
                    values.merge(name.trim(), value, Double::sum);
                }
            }
        }
        return values;
    }

    static Map<String, Double> readRow(Connection connection, String sql) throws SQLException {
        Map<String, Double> values = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                ResultSetMetaData metaData = rs.getMetaData();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    values.put(metaData.getColumnLabel(i).toUpperCase(Locale.ENGLISH), number(rs.getObject(i)));
                }
            }
        }
        return values;
    }

    /**
     * A value of the rows of a query of names and values, 0 for a name which is missing when zero is true (e.g.
     * sessions of a state which no session has now)
     */
    private static Probe pair(String sql, String name, boolean zero) {
        return context -> {
            Double value = context.pairs(sql).get(name);
            return value == null && zero ? Double.valueOf(0) : value;
        };
    }

    private static Probe column(String sql, String column) {
        return context -> context.row(sql).get(column.toUpperCase(Locale.ENGLISH));
    }

    private static Probe single(String sql) {
        return context -> {
            Map<String, Double> row = context.row(sql);
            return row.isEmpty() ? null : row.values().iterator().next();
        };
    }

    private void define() {
        switch (profile.getDbType()) {
            case POSTGRESQL:
                definePostgreSql();
                break;
            case MYSQL:
            case MARIADB:
                defineMySql();
                break;
            case SQLSERVER:
                defineSqlServer();
                break;
            case ORACLE:
                defineOracle();
                break;
            case DB2:
                defineDb2();
                break;
            case CLICKHOUSE:
                defineClickHouse();
                break;
            default:
                defineEmbedded();
                break;
        }
    }

    private void definePostgreSql() {
        String states = "SELECT state, count(*) FROM pg_stat_activity WHERE datname IS NOT NULL AND pid <> "
                + "pg_backend_pid() GROUP BY state";
        String database = "SELECT xact_commit, xact_rollback, tup_fetched, tup_inserted, tup_updated, tup_deleted, "
                + "blks_hit, blks_read FROM pg_stat_database WHERE datname = current_database()";
        chart("Sessions", Unit.COUNT, Series.gauge("Active", "pg.active"), Series.gauge("Idle", "pg.idle"),
                Series.gauge("Idle in transaction", "pg.idleTx"));
        probe("pg.active", pair(states, "active", true));
        probe("pg.idle", pair(states, "idle", true));
        probe("pg.idleTx", pair(states, "idle in transaction", true));
        chart("Transactions", Unit.PER_SECOND, Series.rate("Commits", "pg.commit"), Series.rate("Rollbacks",
                "pg.rollback"));
        probe("pg.commit", column(database, "xact_commit"));
        probe("pg.rollback", column(database, "xact_rollback"));
        chart("Rows", Unit.PER_SECOND, Series.rate("Fetched", "pg.fetched"), Series.rate("Inserted",
                "pg.inserted"), Series.rate("Updated", "pg.updated"), Series.rate("Deleted", "pg.deleted"));
        probe("pg.fetched", column(database, "tup_fetched"));
        probe("pg.inserted", column(database, "tup_inserted"));
        probe("pg.updated", column(database, "tup_updated"));
        probe("pg.deleted", column(database, "tup_deleted"));
        chart("Buffer cache hits", Unit.PERCENT, Series.ratio("Hits", "pg.hit", "pg.hit", "pg.read"));
        probe("pg.hit", column(database, "blks_hit"));
        probe("pg.read", column(database, "blks_read"));
        chart("Locks waiting", Unit.COUNT, Series.gauge("Not granted", "pg.locks"));
        probe("pg.locks", single("SELECT count(*) FROM pg_locks WHERE NOT granted"));
        chart("Database size", Unit.BYTES, Series.gauge("Size", "pg.size"));
        probe("pg.size", single("SELECT pg_database_size(current_database())"));
    }

    private void defineMySql() {
        String status = "SHOW GLOBAL STATUS";
        chart("Threads", Unit.COUNT, Series.gauge("Connected", "my.connected"), Series.gauge("Running",
                "my.running"));
        probe("my.connected", pair(status, "Threads_connected", false));
        probe("my.running", pair(status, "Threads_running", false));
        chart("Statements", Unit.PER_SECOND, Series.rate("All", "my.questions"), Series.rate("Select",
                "my.select"), Series.rate("Insert", "my.insert"), Series.rate("Update", "my.update"), Series.rate(
                "Delete", "my.delete"));
        probe("my.questions", pair(status, "Questions", false));
        probe("my.select", pair(status, "Com_select", true));
        probe("my.insert", pair(status, "Com_insert", true));
        probe("my.update", pair(status, "Com_update", true));
        probe("my.delete", pair(status, "Com_delete", true));
        chart("Network", Unit.BYTES_PER_SECOND, Series.rate("Received", "my.received"), Series.rate("Sent",
                "my.sent"));
        probe("my.received", pair(status, "Bytes_received", false));
        probe("my.sent", pair(status, "Bytes_sent", false));
        chart("InnoDB buffer pool hits", Unit.PERCENT, Series.ratio("Hits", "my.hits", "my.requests"));
        probe("my.requests", pair(status, "Innodb_buffer_pool_read_requests", false));
        probe("my.hits", context -> {
            Map<String, Double> values = context.pairs(status);
            Double requests = values.get("Innodb_buffer_pool_read_requests");
            Double reads = values.get("Innodb_buffer_pool_reads");
            return requests != null && reads != null ? requests - reads : null;
        });
        chart("Row lock waits", Unit.COUNT, Series.gauge("Waiting", "my.lockWaits"));
        probe("my.lockWaits", pair(status, "Innodb_row_lock_current_waits", false));
        chart("InnoDB buffer pool data", Unit.BYTES, Series.gauge("Data", "my.poolData"));
        probe("my.poolData", pair(status, "Innodb_buffer_pool_bytes_data", false));
    }

    private void defineSqlServer() {
        String counters = "SELECT RTRIM(counter_name), cntr_value FROM sys.dm_os_performance_counters WHERE "
                + "(object_name LIKE '%SQL Statistics%' AND counter_name IN ('Batch Requests/sec', "
                + "'SQL Compilations/sec')) OR (object_name LIKE '%:Databases%' AND counter_name = "
                + "'Transactions/sec' AND instance_name = '_Total') OR (object_name LIKE '%Buffer Manager%' AND "
                + "counter_name = 'Page life expectancy')";
        String health = "SELECT TOP 1 CONVERT(xml, record).value('(./Record/SchedulerMonitorEvent/SystemHealth/"
                + "ProcessUtilization)[1]', 'int') AS process_cpu, 100 - CONVERT(xml, record).value('(./Record/"
                + "SchedulerMonitorEvent/SystemHealth/SystemIdle)[1]', 'int') AS system_cpu FROM "
                + "sys.dm_os_ring_buffers WHERE ring_buffer_type = N'RING_BUFFER_SCHEDULER_MONITOR' AND record LIKE N'%<SystemHealth>%' "
                + "ORDER BY timestamp DESC";
        chart("Sessions", Unit.COUNT, Series.gauge("User sessions", "ms.sessions"), Series.gauge("Running requests",
                "ms.requests"));
        probe("ms.sessions", single("SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE is_user_process = 1"));
        probe("ms.requests", single("SELECT COUNT(*) FROM sys.dm_exec_requests WHERE session_id > 50 AND "
                + "session_id <> @@SPID"));
        chart("Requests", Unit.PER_SECOND, Series.rate("Batches", "ms.batches"), Series.rate("Transactions",
                "ms.transactions"), Series.rate("Compilations", "ms.compilations"));
        probe("ms.batches", pair(counters, "Batch Requests/sec", false));
        probe("ms.transactions", pair(counters, "Transactions/sec", false));
        probe("ms.compilations", pair(counters, "SQL Compilations/sec", false));
        chart("CPU", Unit.PERCENT, Series.gauge("SQL Server", "ms.cpu"), Series.gauge("System", "ms.systemCpu"));
        probe("ms.cpu", column(health, "process_cpu"));
        probe("ms.systemCpu", column(health, "system_cpu"));
        chart("Memory", Unit.BYTES, Series.gauge("In use", "ms.memory"));
        probe("ms.memory", single("SELECT physical_memory_in_use_kb * 1024 FROM sys.dm_os_process_memory"));
        chart("Page life expectancy", Unit.COUNT, Series.gauge("Seconds", "ms.ple"));
        probe("ms.ple", pair(counters, "Page life expectancy", false));
    }

    private void defineOracle() {
        String stats = "SELECT name, value FROM v$sysstat WHERE name IN ('user commits', 'user rollbacks', "
                + "'user calls', 'execute count', 'session logical reads', 'physical reads', 'physical writes', "
                + "'bytes sent via SQL*Net to client', 'bytes received via SQL*Net from client')";
        String sessions = "SELECT status, COUNT(*) FROM v$session WHERE type = 'USER' GROUP BY status";
        String os = "SELECT stat_name, value FROM v$osstat WHERE stat_name IN ('BUSY_TIME', 'IDLE_TIME')";
        chart("Sessions", Unit.COUNT, Series.gauge("Active", "ora.active"), Series.gauge("Inactive",
                "ora.inactive"));
        probe("ora.active", pair(sessions, "ACTIVE", true));
        probe("ora.inactive", pair(sessions, "INACTIVE", true));
        chart("Calls", Unit.PER_SECOND, Series.rate("Commits", "ora.commits"), Series.rate("Rollbacks",
                "ora.rollbacks"), Series.rate("User calls", "ora.calls"), Series.rate("Executions", "ora.executes"));
        probe("ora.commits", pair(stats, "user commits", false));
        probe("ora.rollbacks", pair(stats, "user rollbacks", false));
        probe("ora.calls", pair(stats, "user calls", false));
        probe("ora.executes", pair(stats, "execute count", false));
        chart("Reads and writes", Unit.PER_SECOND, Series.rate("Logical reads", "ora.logical"), Series.rate(
                "Physical reads", "ora.physicalReads"), Series.rate("Physical writes", "ora.physicalWrites"));
        probe("ora.logical", pair(stats, "session logical reads", false));
        probe("ora.physicalReads", pair(stats, "physical reads", false));
        probe("ora.physicalWrites", pair(stats, "physical writes", false));
        chart("Network", Unit.BYTES_PER_SECOND, Series.rate("Received", "ora.received"), Series.rate("Sent",
                "ora.sent"));
        probe("ora.received", pair(stats, "bytes received via SQL*Net from client", false));
        probe("ora.sent", pair(stats, "bytes sent via SQL*Net to client", false));
        chart("Host CPU", Unit.PERCENT, Series.ratio("Busy", "ora.busy", "ora.busy", "ora.idle"));
        probe("ora.busy", pair(os, "BUSY_TIME", false));
        probe("ora.idle", pair(os, "IDLE_TIME", false));
        chart("Memory", Unit.BYTES, Series.gauge("SGA", "ora.sga"), Series.gauge("PGA", "ora.pga"));
        probe("ora.sga", single("SELECT SUM(value) FROM v$sga"));
        probe("ora.pga", single("SELECT value FROM v$pgastat WHERE name = 'total PGA allocated'"));
    }

    private void defineDb2() {
        String database = "SELECT APPLS_CUR_CONS, TOTAL_APP_COMMITS, TOTAL_APP_ROLLBACKS, ROWS_READ, ROWS_MODIFIED "
                + "FROM TABLE(MON_GET_DATABASE(-2))";
        String resources = "SELECT CPU_USAGE_TOTAL, MEMORY_TOTAL * 1048576 AS MEMORY_TOTAL, (MEMORY_TOTAL - "
                + "MEMORY_FREE) * 1048576 AS MEMORY_USED FROM TABLE(SYSPROC.ENV_GET_SYSTEM_RESOURCES())";
        chart("Connections", Unit.COUNT, Series.gauge("Connected", "db2.connections"));
        probe("db2.connections", column(database, "APPLS_CUR_CONS"));
        chart("Transactions", Unit.PER_SECOND, Series.rate("Commits", "db2.commits"), Series.rate("Rollbacks",
                "db2.rollbacks"));
        probe("db2.commits", column(database, "TOTAL_APP_COMMITS"));
        probe("db2.rollbacks", column(database, "TOTAL_APP_ROLLBACKS"));
        chart("Rows", Unit.PER_SECOND, Series.rate("Read", "db2.read"), Series.rate("Modified", "db2.modified"));
        probe("db2.read", column(database, "ROWS_READ"));
        probe("db2.modified", column(database, "ROWS_MODIFIED"));
        chart("CPU", Unit.PERCENT, Series.gauge("Host", "db2.cpu"));
        probe("db2.cpu", column(resources, "CPU_USAGE_TOTAL"));
        chart("Memory", Unit.BYTES, Series.gauge("Used", "db2.memoryUsed"), Series.gauge("Total",
                "db2.memoryTotal"));
        probe("db2.memoryUsed", column(resources, "MEMORY_USED"));
        probe("db2.memoryTotal", column(resources, "MEMORY_TOTAL"));
    }

    private void defineClickHouse() {
        String metrics = "SELECT metric, value FROM system.metrics WHERE metric IN ('TCPConnection', "
                + "'HTTPConnection', 'Query', 'MemoryTracking')";
        String events = "SELECT event, value FROM system.events WHERE event IN ('Query', 'SelectQuery', "
                + "'InsertQuery', 'InsertedRows', 'SelectedRows')";
        String async = "SELECT metric, value FROM system.asynchronous_metrics WHERE metric IN ('LoadAverage1', "
                + "'OSMemoryAvailable', 'OSMemoryTotal')";
        chart("Connections and queries", Unit.COUNT, Series.gauge("TCP", "ch.tcp"), Series.gauge("HTTP", "ch.http"),
                Series.gauge("Running queries", "ch.running"));
        probe("ch.tcp", pair(metrics, "TCPConnection", true));
        probe("ch.http", pair(metrics, "HTTPConnection", true));
        probe("ch.running", pair(metrics, "Query", true));
        chart("Queries", Unit.PER_SECOND, Series.rate("All", "ch.queries"), Series.rate("Select", "ch.selects"),
                Series.rate("Insert", "ch.inserts"));
        probe("ch.queries", pair(events, "Query", true));
        probe("ch.selects", pair(events, "SelectQuery", true));
        probe("ch.inserts", pair(events, "InsertQuery", true));
        chart("Rows", Unit.PER_SECOND, Series.rate("Inserted", "ch.insertedRows"), Series.rate("Selected",
                "ch.selectedRows"));
        probe("ch.insertedRows", pair(events, "InsertedRows", true));
        probe("ch.selectedRows", pair(events, "SelectedRows", true));
        chart("Memory", Unit.BYTES, Series.gauge("Tracked", "ch.memory"), Series.gauge("Available of the host",
                "ch.available"));
        probe("ch.memory", pair(metrics, "MemoryTracking", false));
        probe("ch.available", pair(async, "OSMemoryAvailable", false));
        chart("Load average", Unit.COUNT, Series.gauge("1 minute", "ch.load"));
        probe("ch.load", pair(async, "LoadAverage1", false));
    }

    /**
     * H2, SQLite, Access and DuckDB run in SqlJam: its CPU and memory, and the file of the database
     */
    private void defineEmbedded() {
        chart("CPU of SqlJam", Unit.PERCENT, Series.gauge("SqlJam", "jvm.cpu"), Series.gauge("System",
                "jvm.systemCpu"));
        probe("jvm.cpu", context -> cpu(true));
        probe("jvm.systemCpu", context -> cpu(false));
        chart("Memory of SqlJam", Unit.BYTES, Series.gauge("Heap used", "jvm.heap"), Series.gauge("Heap maximum",
                "jvm.heapMax"));
        probe("jvm.heap", context -> (double) ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        probe("jvm.heapMax", context -> {
            long max = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
            return max > 0 ? (double) max : null;
        });
        switch (profile.getDbType()) {
            case H2:
                chart("Sessions", Unit.COUNT, Series.gauge("Sessions", "h2.sessions"));
                probe("h2.sessions", single("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"));
                break;
            case DUCKDB:
                chart("Memory of DuckDB", Unit.BYTES, Series.gauge("Used", "duck.memory"));
                probe("duck.memory", single("SELECT SUM(memory_usage_bytes) FROM duckdb_memory()"));
                break;
            default:
                break;
        }
        chart("File", Unit.BYTES, Series.gauge("Size", "file.size"));
        probe("file.size", context -> {
            File file = databaseFile();
            return file != null ? (double) file.length() : null;
        });
    }

    File databaseFile() {
        String database = StringUtils.defaultString(profile.getDatabase());
        for (String name : new String[]{database, database + ".mv.db"}) {
            File file = new File(name);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static Double cpu(boolean process) {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) bean;
            double load = process ? os.getProcessCpuLoad() : os.getCpuLoad();
            // Negative before the first measure of the system
            return Math.max(0, load) * 100;
        }
        return null;
    }

    private Connection connection() throws SQLException {
        if (connection == null) {
            connection = session.openSqlConnection(null, null);
            connection.setAutoCommit(true);
        }
        return connection;
    }

    @Override
    public synchronized Map<String, Double> read() throws Exception {
        long start = System.nanoTime();
        Context context = new Context(connection());
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, Probe> entry : probes.entrySet()) {
            if (unavailable.containsKey(entry.getKey())) {
                continue;
            }
            try {
                Double value = entry.getValue().read(context);
                if (value == null) {
                    unavailable.put(entry.getKey(), "Not reported by this server");
                } else {
                    values.put(entry.getKey(), value);
                }
            } catch (Exception e) {
                if (!isValid()) {
                    // The connection was lost, not the metric: it is opened again by the next sample
                    closeConnection();
                    throw e;
                }
                unavailable.put(entry.getKey(), StringUtils.defaultIfBlank(e.getMessage(), e.getClass()
                        .getSimpleName()).trim());
            }
        }
        values.put(SAMPLE_TIME, (System.nanoTime() - start) / 1_000_000.0);
        return values;
    }

    /**
     * The connection of the samples, null before the first one or after it was lost
     */
    public synchronized Connection getConnection() {
        return connection;
    }

    private boolean isValid() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Connection of the dashboard is closed already: {}", e.getMessage());
            }
            connection = null;
        }
    }

    @Override
    public synchronized void close() {
        closeConnection();
    }
}
