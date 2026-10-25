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
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DatabaseFeature;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.utils.SshSettings;
import com.github.sqljam.utils.SshUtils;

/**
 * @Description: DatabaseInfo reads the overview and the health of a data source for its info tab: the server, the
 *               driver, the session and the features which SqlJam uses (vectors and their indexes), then whether it
 *               answers and how fast, its uptime, connections and the space of the database. Each item is read by
 *               itself, an item which is not readable (no privilege, another version) tells why. It is read only
 *               and serves the imports and exports, it manages nothing.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class DatabaseInfo {

    public static final String UNAVAILABLE = "Unavailable: ";

    private final ConnectionProfile profile;
    private final DatabaseSession session;

    public DatabaseInfo(ConnectionProfile profile, DatabaseSession session) {
        this.profile = profile;
        this.session = session;
    }

    static String read(Callable<?> item) {
        try {
            Object value = item.call();
            return value == null || StringUtils.isBlank(value.toString()) ? "-" : value.toString();
        } catch (Exception e) {
            return UNAVAILABLE + StringUtils.abbreviate(StringUtils.defaultString(e.getMessage(), e.getClass()
                    .getSimpleName()).trim(), 200);
        }
    }

    /**
     * First column of the first row of a query
     */
    static String query(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            throw e;
        }
    }

    static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ENGLISH, "%.1f %s", value, units[unit]);
    }

    static String duration(long seconds) {
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        return days > 0 ? String.format("%dd %dh %dm", days, hours, minutes) : String.format("%dh %dm", hours,
                minutes);
    }

    private Connection connect() throws SQLException {
        return session.openSqlConnection(null, null);
    }

    /**
     * Server, driver, session and the features of SqlJam
     */
    public Map<String, Map<String, String>> getOverview() throws SQLException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        DbType dbType = profile.getDbType();
        try (Connection connection = connect()) {
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, String> server = new LinkedHashMap<>();
            // The version of some drivers starts with the name (Oracle)
            server.put("Product", read(() -> metaData.getDatabaseProductVersion().startsWith(metaData
                    .getDatabaseProductName()) ? metaData.getDatabaseProductVersion() : metaData
                    .getDatabaseProductName() + " " + metaData.getDatabaseProductVersion()));
            server.put("Version", read(() -> metaData.getDatabaseMajorVersion() + "." + metaData
                    .getDatabaseMinorVersion()));
            server.put("URL", read(metaData::getURL));
            server.put("User", read(metaData::getUserName));
            if (profile.isSshEnabled()) {
                // The url is of the local end of the tunnel
                SshSettings ssh = profile.getSsh();
                server.put("SSH tunnel", ssh.getUsername() + "@" + ssh.getHost() + ":" + ssh.getEffectivePort()
                        + ", local port " + SshUtils.getLocalPort(profile.getId()));
            }
            sections.put("Server", server);

            Map<String, String> driver = new LinkedHashMap<>();
            driver.put("Driver", read(() -> metaData.getDriverName() + " " + metaData.getDriverVersion()));
            driver.put("JDBC", read(() -> metaData.getJDBCMajorVersion() + "." + metaData.getJDBCMinorVersion()));
            sections.put("Driver", driver);

            Map<String, String> sessionInfo = new LinkedHashMap<>();
            sessionInfo.put("Catalog", read(connection::getCatalog));
            sessionInfo.put("Schema", read(connection::getSchema));
            sessionInfo.put("Read only", read(() -> connection.isReadOnly() ? "Yes" : "No"));
            sessionInfo.put("Isolation", read(() -> isolation(connection.getTransactionIsolation())));
            sessionInfo.put("Transactions", read(() -> metaData.supportsTransactions() ? "Supported"
                    : "Not supported"));
            sessionInfo.put("Client time zone", TimeZone.getDefault().getID());
            sections.put("Session", sessionInfo);

            Map<String, String> sqljam = new LinkedHashMap<>();
            Dialect dialect = read(() -> dbType.createDialect(metaData.getDatabaseMajorVersion(), metaData
                    .getDatabaseMinorVersion()).getClass().getSimpleName()).startsWith(UNAVAILABLE) ? null
                    : dbType.createDialect(metaData.getDatabaseMajorVersion(), metaData.getDatabaseMinorVersion());
            sqljam.put("Dialect", dialect != null ? dialect.getClass().getSimpleName() : "-");
            sqljam.put("Features", read(() -> {
                Set<DatabaseFeature> features = dialect.readDatabaseFeatures(connection);
                return features.isEmpty() ? "None detected" : features.stream().map(Enum::name).collect(
                        Collectors.joining(", "));
            }));
            sqljam.put("Category", dbType.getCategory().getDisplayName());
            sections.put("SqlJam", sqljam);
        }
        return sections;
    }

    static String isolation(int level) {
        switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED:
                return "Read uncommitted";
            case Connection.TRANSACTION_READ_COMMITTED:
                return "Read committed";
            case Connection.TRANSACTION_REPEATABLE_READ:
                return "Repeatable read";
            case Connection.TRANSACTION_SERIALIZABLE:
                return "Serializable";
            default:
                return "None";
        }
    }

    /**
     * Whether the server answers and how fast, its uptime, its connections and the space of the database
     */
    public Map<String, Map<String, String>> getHealth() {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> reach = new LinkedHashMap<>();
        sections.put("Connection", reach);
        long start = System.nanoTime();
        Connection connection;
        try {
            connection = connect();
        } catch (SQLException e) {
            reach.put("Reachable", UNAVAILABLE + e.getMessage());
            return sections;
        }
        try (Connection opened = connection) {
            reach.put("Reachable", "Yes");
            reach.put("Connect time", (System.nanoTime() - start) / 1_000_000 + " ms");
            long ping = System.nanoTime();
            reach.put("Valid", read(() -> opened.isValid(10) ? "Yes" : "No"));
            reach.put("Response time", (System.nanoTime() - ping) / 1_000_000 + " ms");
            Map<String, String> server = new LinkedHashMap<>();
            sections.put("Server", server);
            readServer(opened, server);
        } catch (SQLException e) {
            reach.put("Closed", UNAVAILABLE + e.getMessage());
        }
        return sections;
    }

    private void readServer(Connection connection, Map<String, String> server) {
        switch (profile.getDbType()) {
            case POSTGRESQL:
                server.put("Uptime", read(() -> duration(Long.parseLong(query(connection,
                        "SELECT CAST(EXTRACT(EPOCH FROM now() - pg_postmaster_start_time()) AS bigint)")))));
                server.put("Connections", read(() -> query(connection, "SELECT count(*) FROM pg_stat_activity")
                        + " of " + query(connection, "SELECT current_setting('max_connections')")));
                server.put("Database size", read(() -> bytes(Long.parseLong(query(connection,
                        "SELECT pg_database_size(current_database())")))));
                server.put("Encoding", read(() -> query(connection, "SELECT current_setting('server_encoding')")));
                server.put("Time zone", read(() -> query(connection, "SELECT current_setting('TimeZone')")));
                server.put("pgvector", read(() -> StringUtils.defaultIfBlank(query(connection,
                        "SELECT extversion FROM pg_extension WHERE extname = 'vector'"), "Not installed")));
                break;
            case MYSQL:
            case MARIADB:
                server.put("Uptime", read(() -> duration(Long.parseLong(status(connection, "Uptime")))));
                server.put("Connections", read(() -> status(connection, "Threads_connected") + " of "
                        + query(connection, "SELECT @@max_connections")));
                server.put("Database size", read(() -> bytes(Long.parseLong(StringUtils.defaultIfBlank(query(
                        connection, "SELECT SUM(data_length + index_length) FROM information_schema.tables"
                                + " WHERE table_schema = DATABASE()"), "0")))));
                server.put("Character set", read(() -> query(connection, "SELECT @@character_set_server")));
                server.put("Time zone", read(() -> query(connection, "SELECT @@time_zone")));
                break;
            case ORACLE:
                server.put("Uptime", read(() -> duration(Long.parseLong(query(connection,
                        "SELECT ROUND((SYSDATE - startup_time) * 86400) FROM v$instance")))));
                server.put("Sessions", read(() -> query(connection, "SELECT COUNT(*) FROM v$session")
                        + " of " + query(connection, "SELECT value FROM v$parameter WHERE name = 'sessions'")));
                server.put("Schema size", read(() -> bytes(Long.parseLong(StringUtils.defaultIfBlank(query(
                        connection, "SELECT SUM(bytes) FROM user_segments"), "0")))));
                server.put("Character set", read(() -> query(connection, "SELECT value FROM"
                        + " nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'")));
                server.put("Vector memory", read(() -> bytes(Long.parseLong(query(connection,
                        "SELECT value FROM v$parameter WHERE name = 'vector_memory_size'")))));
                break;
            case SQLSERVER:
                server.put("Uptime", read(() -> duration(Long.parseLong(query(connection,
                        "SELECT DATEDIFF(SECOND, sqlserver_start_time, SYSDATETIME()) FROM sys.dm_os_sys_info")))));
                server.put("Sessions", read(() -> query(connection,
                        "SELECT COUNT(*) FROM sys.dm_exec_sessions WHERE is_user_process = 1") + " of "
                        + query(connection, "SELECT @@MAX_CONNECTIONS")));
                server.put("Database size", read(() -> bytes(Long.parseLong(query(connection,
                        "SELECT CAST(SUM(size) AS bigint) * 8192 FROM sys.database_files")))));
                server.put("Collation", read(() -> query(connection,
                        "SELECT CAST(DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS nvarchar(128))")));
                break;
            case DB2:
                server.put("Connected since", read(() -> query(connection,
                        "SELECT DB_CONN_TIME FROM TABLE(MON_GET_DATABASE(-2))")));
                server.put("Connections", read(() -> query(connection,
                        "SELECT APPLS_CUR_CONS FROM TABLE(MON_GET_DATABASE(-2))")));
                server.put("Code page", read(() -> query(connection,
                        "SELECT VALUE FROM SYSIBMADM.DBCFG WHERE NAME = 'codeset'")));
                break;
            case CLICKHOUSE:
                server.put("Uptime", read(() -> duration(Long.parseLong(query(connection, "SELECT uptime()")))));
                server.put("Connections", read(() -> query(connection,
                        "SELECT value FROM system.metrics WHERE metric = 'TCPConnection'") + " TCP, "
                        + query(connection, "SELECT value FROM system.metrics WHERE metric = 'HTTPConnection'")
                        + " HTTP"));
                server.put("Database size", read(() -> bytes(Long.parseLong(StringUtils.defaultIfBlank(query(
                        connection, "SELECT SUM(bytes_on_disk) FROM system.parts WHERE active AND database"
                                + " = currentDatabase()"), "0")))));
                server.put("Free disk", read(() -> bytes(Long.parseLong(query(connection,
                        "SELECT free_space FROM system.disks WHERE name = 'default'")))));
                break;
            case DUCKDB:
                server.put("Database size", read(() -> query(connection,
                        "SELECT database_size FROM pragma_database_size() LIMIT 1")));
                server.put("Memory", read(() -> query(connection,
                        "SELECT memory_usage FROM pragma_database_size() LIMIT 1") + " of " + query(connection,
                        "SELECT current_setting('memory_limit')")));
                server.put("File", read(() -> fileSize()));
                break;
            default:
                // Embedded databases of a file: H2, SQLite, Access
                server.put("File", read(() -> fileSize()));
                break;
        }
    }

    private static String status(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SHOW GLOBAL STATUS LIKE '" + name + "'")) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    /**
     * Size of the file of an embedded database
     */
    String fileSize() {
        String database = StringUtils.defaultString(profile.getDatabase());
        File file = new File(database);
        if (!file.isFile() && new File(database + ".mv.db").isFile()) {
            file = new File(database + ".mv.db");
        }
        return file.isFile() ? file.getAbsolutePath() + ", " + bytes(file.length()) : "Not a local file";
    }
}
