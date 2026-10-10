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
import java.util.Map;

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

    MYSQL(DbType.MYSQL, "sjm_", "test", null, "test_test", null) {
        @Override
        public String getUrl(String catalog) {
            return DbType.MYSQL.getUrl("localhost", 3306, catalog);
        }
    },

    MARIADB(DbType.MARIADB, "sjb_", "demo", null, "demo", null) {
        @Override
        public String getUrl(String catalog) {
            return DbType.MARIADB.getUrl("localhost", 3307, "demo");
        }
    },

    POSTGRESQL(DbType.POSTGRESQL, "sjp_", "demo", "sjit_src", "demo", "sjit_dst") {
        @Override
        public String getUrl(String catalog) {
            return DbType.POSTGRESQL.getUrl("localhost", 5432, "demo");
        }
    },

    ORACLE(DbType.ORACLE, "sjo_", null, "FENGY", null, "FENGY") {
        @Override
        public String getUrl(String catalog) {
            return DbType.ORACLE.getUrl("localhost", 1521, "demo");
        }
    },

    SQLSERVER(DbType.SQLSERVER, "sjs_", "demo", "dbo", "demo", "sjit_dst") {
        @Override
        public String getUrl(String catalog) {
            return DbType.SQLSERVER.getUrl("localhost", 1433, catalog);
        }
    },

    H2(DbType.H2, "sjh_", null, "PUBLIC", null, "PUBLIC") {
        @Override
        public String getUrl(String catalog) {
            return DbType.H2.getUrl(null, 0, new File(DATA_DIR, catalog == null ? "h2-src" : "h2-dst").getAbsolutePath());
        }
    },

    SQLITE(DbType.SQLITE, "sjl_", null, null, null, null) {
        @Override
        public String getUrl(String catalog) {
            return DbType.SQLITE.getUrl(null, 0, new File(DATA_DIR, catalog == null ? "src.sqlite" : "dst.sqlite")
                    .getAbsolutePath());
        }
    },

    DUCKDB(DbType.DUCKDB, "sjd_", null, "main", null, "main") {
        @Override
        public String getUrl(String catalog) {
            return DbType.DUCKDB.getUrl(null, 0, getDuckDBFile(catalog == null).getAbsolutePath());
        }
    },

    CLICKHOUSE(DbType.CLICKHOUSE, "sjc_", null, "app", null, "sjit_dst") {
        @Override
        public String getUrl(String catalog) {
            return DbType.CLICKHOUSE.getUrl("localhost", 18123, "app");
        }
    };

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
    private static final Map<ItDatabase, Boolean> LOADED = new EnumMap<>(ItDatabase.class);

    ItDatabase(DbType dbType, String prefix, String sourceCatalog, String sourceSchema, String targetCatalog,
               String targetSchema) {
        this.dbType = dbType;
        this.prefix = prefix;
        this.sourceCatalog = sourceCatalog;
        this.sourceSchema = sourceSchema;
        this.targetCatalog = targetCatalog;
        this.targetSchema = targetSchema;
    }

    /**
     * @param catalog null for source database, otherwise target database
     */
    public abstract String getUrl(String catalog);

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
        switch (this) {
            case MYSQL:
            case POSTGRESQL:
            case ORACLE:
            case SQLSERVER:
            case CLICKHOUSE:
            case MARIADB:
                return "fengy";
            case H2:
                return "sa";
            default:
                return null;
        }
    }

    public String getPassword() {
        switch (this) {
            case MYSQL:
                return "12345678";
            case POSTGRESQL:
            case ORACLE:
            case SQLSERVER:
            case CLICKHOUSE:
            case MARIADB:
                return "123456";
            case H2:
                return "";
            default:
                return null;
        }
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
            case MYSQL:
                profile.setHostname("localhost");
                profile.setPort(3306);
                profile.setDatabase(catalog);
                break;
            case POSTGRESQL:
                profile.setHostname("localhost");
                profile.setPort(5432);
                profile.setDatabase("demo");
                break;
            case ORACLE:
                profile.setHostname("localhost");
                profile.setPort(1521);
                profile.setDatabase("demo");
                break;
            case SQLSERVER:
                profile.setHostname("localhost");
                profile.setPort(1433);
                profile.setDatabase("demo");
                break;
            case H2:
                profile.setDatabase(new File(DATA_DIR, fileKey == null ? "h2-src" : "h2-dst").getAbsolutePath());
                break;
            case DUCKDB:
                profile.setDatabase(getDuckDBFile(fileKey == null).getAbsolutePath());
                break;
            case CLICKHOUSE:
                profile.setHostname("localhost");
                profile.setPort(18123);
                profile.setDatabase("app");
                break;
            case MARIADB:
                profile.setHostname("localhost");
                profile.setPort(3307);
                profile.setDatabase("demo");
                break;
            default:
                profile.setDatabase(new File(DATA_DIR, fileKey == null ? "src.sqlite" : "dst.sqlite")
                        .getAbsolutePath());
                break;
        }
        return profile;
    }

    public Connection getSourceConnection() throws SQLException {
        return connect(getSourceUrl());
    }

    public Connection getTargetConnection() throws SQLException {
        Connection connection = connect(getTargetUrl());
        if (dbType == DbType.POSTGRESQL || dbType == DbType.ORACLE || dbType == DbType.CLICKHOUSE) {
            connection.setSchema(targetSchema);
        }
        return connection;
    }

    private Connection connect(String url) throws SQLException {
        if (getUsername() == null) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, getUsername(), getPassword());
    }

    public synchronized boolean isAvailable() {
        return AVAILABLE.computeIfAbsent(this, db -> {
            DriverManager.setLoginTimeout(5);
            try (Connection ignored = getSourceConnection()) {
                return true;
            } catch (Exception e) {
                System.err.println("Database is not available: " + this + ", " + e.getMessage());
                return false;
            }
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
        configuration.setIncludedTableNamePattern("(?i)" + prefix + ".*");
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
