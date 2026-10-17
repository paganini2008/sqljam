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
package com.github.sqljam.it;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.ImportExportHandler;
import com.github.sqljam.impexp.SqlScriptRunner;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: ItDatabase holds the databases used by integration tests. Each database has its own fixture tables
 *               with a distinct prefix, so that tables imported from other databases never collide with them.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public enum ItDatabase {

    MYSQL(DbType.MYSQL, "sjm_"),

    MARIADB(DbType.MARIADB, "sjb_"),

    POSTGRESQL(DbType.POSTGRESQL, "sjp_"),

    ORACLE(DbType.ORACLE, "sjo_"),

    SQLSERVER(DbType.SQLSERVER, "sjs_"),

    DB2(DbType.DB2, "sji_"),

    H2(DbType.H2, "sjh_") {
        @Override
        public String getUrl(String catalog) {
            return DbType.H2.getUrl(null, 0, new File(DATA_DIR, catalog == null ? "h2-src" : "h2-dst").getAbsolutePath());
        }
    },

    SQLITE(DbType.SQLITE, "sjl_") {
        @Override
        public String getUrl(String catalog) {
            return DbType.SQLITE.getUrl(null, 0, new File(DATA_DIR, catalog == null ? "src.sqlite" : "dst.sqlite")
                    .getAbsolutePath());
        }
    },

    DUCKDB(DbType.DUCKDB, "sjd_") {
        @Override
        public String getUrl(String catalog) {
            return DbType.DUCKDB.getUrl(null, 0, getDuckDBFile(catalog == null).getAbsolutePath());
        }
    },

    CLICKHOUSE(DbType.CLICKHOUSE, "sjc_");

    /**
     * Settings of the environment: it-databases-test.properties (192.168.0.63, the default) or
     * it-databases-local.properties (-Dit.env=local), system properties of the same names win. A holder class,
     * because static fields of an enum are not set yet when its constants are created.
     */
    static final class Settings {

        /**
         * Environment of the database servers: test or local
         */
        static final String ENVIRONMENT = System.getProperty("it.env", "test");
        private static final Properties PROPERTIES = new Properties();

        static {
            String file = "/it-databases-" + ENVIRONMENT + ".properties";
            try (InputStream in = ItDatabase.class.getResourceAsStream(file)) {
                if (in == null) {
                    throw new IllegalStateException("No settings of the environment " + ENVIRONMENT + ": " + file);
                }
                PROPERTIES.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static String get(ItDatabase db, String key) {
            String name = db.name().toLowerCase(Locale.ENGLISH) + "." + key;
            return StringUtils.trimToNull(System.getProperty(name, PROPERTIES.getProperty(name)));
        }

        /**
         * A value which may be empty, e.g. the empty password of H2
         */
        static String getRaw(ItDatabase db, String key) {
            String name = db.name().toLowerCase(Locale.ENGLISH) + "." + key;
            return System.getProperty(name, PROPERTIES.getProperty(name));
        }
    }

    static File getDuckDBFile(boolean source) {
        return new File(DATA_DIR, source ? "duck-src.duckdb" : "duck-dst.duckdb");
    }

    static final File DATA_DIR = new File("target/it-data");

    public static final String NOTE = "O'Brien \\ \"quoted\" 中文 -- not a comment; /* nor this */";
    public static final int EMP_COUNT = 250;
    public static final String PROFILE;

    static {
        StringBuilder profile = new StringBuilder();
        while (profile.length() < 5000) {
            profile.append("中文abc'");
        }
        PROFILE = profile.substring(0, 5000);
        DATA_DIR.mkdirs();
    }

    private final DbType dbType;
    private final String prefix;
    private final String sourceCatalog;
    private final String sourceSchema;
    private final String targetCatalog;
    private final String targetSchema;

    private static final Map<ItDatabase, Boolean> AVAILABLE = new EnumMap<>(ItDatabase.class);
    // Connections kept open during the tests, a database without connections may be deactivated (Db2)
    private static final Map<ItDatabase, Connection> KEPT = new EnumMap<>(ItDatabase.class);
    private static final Map<ItDatabase, Boolean> LOADED = new EnumMap<>(ItDatabase.class);

    ItDatabase(DbType dbType, String prefix) {
        this.dbType = dbType;
        this.prefix = prefix;
        this.sourceCatalog = Settings.get(this, "sourceCatalog");
        this.sourceSchema = Settings.get(this, "sourceSchema");
        this.targetCatalog = Settings.get(this, "targetCatalog");
        this.targetSchema = Settings.get(this, "targetSchema");
    }

    /**
     * Url of the server and the catalog, the database of the settings when the catalog is not given
     *
     * @param catalog null for source database of file databases, otherwise target database
     */
    public String getUrl(String catalog) {
        if (!isConfigured()) {
            throw new IllegalStateException(this + " has no server in the environment " + Settings.ENVIRONMENT);
        }
        return dbType.getUrl(Settings.get(this, "host"), Integer.parseInt(Settings.get(this, "port")),
                catalog != null ? catalog : Settings.get(this, "database"));
    }

    /**
     * Whether the environment has the database: files always, servers when their host is set
     */
    public boolean isConfigured() {
        return dbType.isFileBased() || Settings.get(this, "host") != null;
    }

    public String getSourceUrl() {
        return dbType.isFileBased() ? getUrl(null) : getUrl(sourceCatalog);
    }

    public String getTargetUrl() {
        return dbType.isFileBased() ? getUrl("target") : getUrl(targetCatalog);
    }

    public DbType getDbType() {
        return dbType;
    }

    /**
     * The test user has only one schema (database), imports of the same database type are copies in that schema
     */
    public boolean isSingleSchema() {
        return this == ORACLE || this == MARIADB;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getSourceCatalog() {
        return sourceCatalog;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public String getTargetCatalog() {
        return targetCatalog;
    }

    public String getTargetSchema() {
        return targetSchema;
    }

    public String getUsername() {
        return Settings.get(this, "username");
    }

    public String getPassword() {
        return Settings.getRaw(this, "password");
    }

    /**
     * Connection profile of the source database (fixture tables)
     */
    public ConnectionProfile getSourceProfile() {
        return createProfile(sourceCatalog, null);
    }

    /**
     * Connection profile of the target database
     */
    public ConnectionProfile getTargetProfile() {
        return createProfile(targetCatalog, "target");
    }

    private ConnectionProfile createProfile(String catalog, String fileKey) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name().toLowerCase() + (fileKey != null ? "-target" : "-source"));
        profile.setDbType(dbType);
        profile.setUsername(getUsername());
        profile.setPassword(getPassword());
        switch (this) {
            case H2:
                profile.setDatabase(new File(DATA_DIR, fileKey == null ? "h2-src" : "h2-dst").getAbsolutePath());
                break;
            case DUCKDB:
                profile.setDatabase(getDuckDBFile(fileKey == null).getAbsolutePath());
                break;
            case SQLITE:
                profile.setDatabase(new File(DATA_DIR, fileKey == null ? "src.sqlite" : "dst.sqlite")
                        .getAbsolutePath());
                break;
            default:
                profile.setHostname(Settings.get(this, "host"));
                profile.setPort(Integer.parseInt(Settings.get(this, "port")));
                profile.setDatabase(catalog != null ? catalog : Settings.get(this, "database"));
                break;
        }
        return profile;
    }

    public Connection getSourceConnection() throws SQLException {
        Connection connection = connect(getSourceUrl());
        if (dbType == DbType.DB2) {
            // The fixture is created in its own schema, which is created first
            useSchema(connection, sourceSchema);
        }
        return connection;
    }

    public Connection getTargetConnection() throws SQLException {
        Connection connection = connect(getTargetUrl());
        if (dbType == DbType.DB2) {
            useSchema(connection, targetSchema);
        } else if (dbType == DbType.POSTGRESQL || dbType == DbType.ORACLE || dbType == DbType.CLICKHOUSE) {
            connection.setSchema(targetSchema);
        }
        return connection;
    }

    /**
     * Creates the schema if it is missing and makes it the current schema
     */
    private void useSchema(Connection connection, String schema) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(dbType.createDialect().getCreateSchemaIfNotExistsStatement(schema));
        }
        connection.setSchema(schema);
    }

    private Connection connect(String url) throws SQLException {
        if (getUsername() == null) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, getUsername(), getPassword());
    }

    public synchronized boolean isAvailable() {
        return AVAILABLE.computeIfAbsent(this, db -> {
            if (!isConfigured()) {
                System.err.println("Database is not in the environment " + Settings.ENVIRONMENT + ": " + this);
                return false;
            }
            // The first connection of an inactive database activates it, which takes some seconds (Db2)
            DriverManager.setLoginTimeout(20);
            Exception error = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Connection connection = getSourceConnection();
                    // Some drivers connect lazily (ClickHouse), the connection is verified by the server
                    if (!connection.isValid(20)) {
                        connection.close();
                        throw new SQLException("The connection is not valid");
                    }
                    if (dbType == DbType.DB2) {
                        KEPT.put(this, connection);
                    } else {
                        connection.close();
                    }
                    return true;
                } catch (Exception e) {
                    error = e;
                }
            }
            System.err.println("Database is not available: " + this + ", " + error.getMessage());
            return false;
        });
    }

    /**
     * Configures exporter to read fixture tables of this database
     */
    public void configureSource(Exporter.ExportConfiguration configuration) {
        configuration.setDbType(dbType);
        configuration.setUrl(getSourceUrl());
        configuration.setUsername(getUsername());
        configuration.setPassword(getPassword());
        configuration.setConnectionPoolEnabled(true);
        if (StringUtils.isNotBlank(sourceCatalog) && dbType.isCatalogSupported()) {
            configuration.setIncludedCatalogNames(new String[]{sourceCatalog});
        }
        if (StringUtils.isNotBlank(sourceSchema)) {
            configuration.setIncludedSchemaNames(new String[]{sourceSchema});
        }
        // Partitioned tables of PartitionIT (prefix + pt_) are not fixture tables of other tests
        configuration.setIncludedTableNamePattern("(?i)" + prefix + "(?!pt_).*");
    }

    public void configureTarget(ImportExportHandler.ImportConfiguration configuration) {
        configuration.setDbType(dbType);
        configuration.setUrl(getTargetUrl());
        configuration.setUsername(getUsername());
        configuration.setPassword(getPassword());
        configuration.setTargetCatalogName(targetCatalog);
        configuration.setTargetSchemaName(targetSchema);
    }

    /**
     * Creates fixture tables and rows once per test run
     */
    public synchronized void loadFixture() throws Exception {
        if (Boolean.TRUE.equals(LOADED.get(this))) {
            return;
        }
        try (Connection connection = getSourceConnection()) {
            SqlScriptRunner runner = new SqlScriptRunner(connection);
            try (InputStream in = ItDatabase.class.getResourceAsStream("/" + name().toLowerCase() + "/fixture.sql")) {
                runner.runScript(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            insertRows(connection);
        }
        LOADED.put(this, true);
    }

    private String table(String name) {
        String tableName = prefix + name;
        return dbType == DbType.POSTGRESQL ? sourceSchema + "." + tableName : tableName;
    }

    /**
     * ClickHouse has no identity columns, bulk inserts of MariaDB leave gaps of identity values, ids are given
     */
    private boolean isIdentitySupported() {
        return dbType != DbType.CLICKHOUSE && dbType != DbType.MARIADB;
    }

    /**
     * ClickHouse converts bound temporal values by the time zone of the client, they are bound as text
     */
    private void setTemporal(PreparedStatement ps, int index, Temporal value) throws SQLException {
        if (dbType == DbType.CLICKHOUSE) {
            ps.setString(index, value.toString().replace('T', ' '));
        } else if (value instanceof LocalDate) {
            ps.setDate(index, Date.valueOf((LocalDate) value));
        } else {
            ps.setTimestamp(index, Timestamp.valueOf((LocalDateTime) value));
        }
    }

    private void insertRows(Connection connection) throws SQLException {
        boolean transactional = JdbcUtils.beginTransaction(connection);
        String[] deptNames = {"Sales", "R&D", "Ops"};
        String idColumn = isIdentitySupported() ? "" : "id, ";
        String idParameter = isIdentitySupported() ? "" : "?, ";
        int offset = isIdentitySupported() ? 0 : 1;
        try (PreparedStatement ps = connection.prepareStatement(String.format(
                "INSERT INTO %s (%sname, budget, created_at) VALUES (%s?, ?, ?)", table("dept"), idColumn,
                idParameter))) {
            for (int i = 0; i < deptNames.length; i++) {
                if (offset > 0) {
                    ps.setInt(1, i + 1);
                }
                ps.setString(offset + 1, deptNames[i]);
                ps.setBigDecimal(offset + 2, new BigDecimal("1000.50").multiply(BigDecimal.valueOf(i + 1)));
                setTemporal(ps, offset + 3, LocalDateTime.of(2024, 1, 1, 8, 0, 0));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement(String.format(
                "INSERT INTO %s (%sdept_id, name, email, salary, active, hired, updated_at, photo, profile, note)"
                        + " VALUES (%s?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", table("emp"), idColumn, idParameter))) {
            for (int i = 1; i <= EMP_COUNT; i++) {
                if (offset > 0) {
                    ps.setLong(1, i);
                }
                ps.setInt(offset + 1, i % 3 + 1);
                ps.setString(offset + 2, "Emp " + i);
                ps.setString(offset + 3, "emp" + i + "@sqljam.io");
                if (i % 7 == 0) {
                    ps.setNull(offset + 4, Types.DECIMAL);
                } else {
                    ps.setBigDecimal(offset + 4, new BigDecimal(i).multiply(new BigDecimal("10.25")));
                }
                if (dbType == DbType.ORACLE) {
                    ps.setInt(offset + 5, i % 2);
                } else {
                    ps.setBoolean(offset + 5, i % 2 == 0);
                }
                setTemporal(ps, offset + 6, LocalDate.of(2020, 1, 1).plusDays(i));
                setTemporal(ps, offset + 7, LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123_000_000)
                        .plusMinutes(i));
                if (i % 10 == 0 && dbType == DbType.CLICKHOUSE) {
                    // ClickHouse has no binary type, bytes are kept as hex text
                    ps.setString(offset + 8, HexFormat.of().formatHex(photo(i)));
                } else if (i % 10 == 0) {
                    ps.setBytes(offset + 8, photo(i));
                } else {
                    ps.setNull(offset + 8, Types.VARBINARY);
                }
                ps.setString(offset + 9, i == 1 ? PROFILE : "profile " + i);
                ps.setString(offset + 10, i == 2 ? NOTE : "note " + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                String.format("INSERT INTO %s (emp_id, tag_name) VALUES (?, ?)", table("emp_tag")))) {
            for (int i = 1; i <= 5; i++) {
                for (String tag : new String[]{"a", "b"}) {
                    ps.setLong(1, i);
                    ps.setString(2, tag);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                String.format("INSERT INTO %s (id, sale_year, amount) VALUES (?, ?, ?)", table("sales")))) {
            for (int i = 1; i <= 20; i++) {
                ps.setInt(1, i);
                ps.setInt(2, i <= 10 ? 2023 : 2024);
                ps.setBigDecimal(3, new BigDecimal(i).multiply(new BigDecimal("1.5")));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        if (transactional) {
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    public static byte[] photo(int i) {
        byte[] bytes = new byte[256 + i];
        for (int j = 0; j < bytes.length; j++) {
            bytes[j] = (byte) (j * 31 + i);
        }
        return bytes;
    }
}
