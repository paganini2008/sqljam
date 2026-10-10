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

import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import com.github.sqljam.impexp.db.ClickHouseDialect;
import com.github.sqljam.impexp.db.ClickHouseMetaDataOperations;
import com.github.sqljam.impexp.db.DuckDBDialect;
import com.github.sqljam.impexp.db.DuckDBMetaDataOperations;
import com.github.sqljam.impexp.db.H2Dialect;
import com.github.sqljam.impexp.db.H2MetaDataOperations;
import com.github.sqljam.impexp.db.MariaDBDialect;
import com.github.sqljam.impexp.db.MariaDBMetaDataOperations;
import com.github.sqljam.impexp.db.MySQL55Dialect;
import com.github.sqljam.impexp.db.MySQL56Dialect;
import com.github.sqljam.impexp.db.MySQL57Dialect;
import com.github.sqljam.impexp.db.MySQLDialect;
import com.github.sqljam.impexp.db.MySQLMetaDataOperations;
import com.github.sqljam.impexp.db.Oracle11gDialect;
import com.github.sqljam.impexp.db.Oracle12cDialect;
import com.github.sqljam.impexp.db.OracleDialect;
import com.github.sqljam.impexp.db.OracleMetaDataOperations;
import com.github.sqljam.impexp.db.PostgreSQL10Dialect;
import com.github.sqljam.impexp.db.PostgreSQL9Dialect;
import com.github.sqljam.impexp.db.PostgreSQLDialect;
import com.github.sqljam.impexp.db.PostgreSQLMetaDataOperations;
import com.github.sqljam.impexp.db.SQLServer2008Dialect;
import com.github.sqljam.impexp.db.SQLServerDialect;
import com.github.sqljam.impexp.db.SQLServerMetaDataOperations;
import com.github.sqljam.impexp.db.SQLiteDialect;
import com.github.sqljam.impexp.db.SQLiteMetaDataOperations;
import com.github.sqljam.utils.CaseInsensitiveMap;

/**
 * @Description: DbType is a supported database type, it creates dialects (by versions) and metadata operations
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public enum DbType {

    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver", 3306, true, false, false, MySQLDialect::new,
            MySQLMetaDataOperations::new) {
        @Override
        protected Dialect createVersionDialect(int majorVersion, int minorVersion) {
            if (majorVersion > 5 || majorVersion < 0) {
                return new MySQLDialect();
            } else if (minorVersion >= 7) {
                return new MySQL57Dialect();
            } else if (minorVersion == 6) {
                return new MySQL56Dialect();
            }
            return new MySQL55Dialect();
        }

        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            if (StringUtils.isNotBlank(catalogName)) {
                catalogName = "/" + catalogName;
            } else {
                catalogName = "";
            }
            return String.format(
                    "jdbc:mysql://%s:%d%s?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&yearIsDateType=false",
                    hostname, port, catalogName);
        }
    },

    MARIADB("MariaDB", "org.mariadb.jdbc.Driver", 3306, true, false, false, MariaDBDialect::new,
            MariaDBMetaDataOperations::new) {
        /**
         * Sequences of MariaDB 10.3+ are supported by the dialect, versions are not subclassed
         */
        @Override
        protected Dialect createVersionDialect(int majorVersion, int minorVersion) {
            return new MariaDBDialect();
        }

        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            String database = StringUtils.isNotBlank(catalogName) ? "/" + catalogName : "";
            // YEAR is a number, as YEAR of MySQL
            return String.format("jdbc:mariadb://%s:%d%s?yearIsDateType=false", hostname, port, database);
        }
    },

    POSTGRESQL("PostgreSQL", "org.postgresql.Driver", 5432, false, true, true, PostgreSQLDialect::new,
            PostgreSQLMetaDataOperations::new) {
        @Override
        protected Dialect createVersionDialect(int majorVersion, int minorVersion) {
            if (majorVersion >= 12 || majorVersion < 0) {
                return new PostgreSQLDialect();
            } else if (majorVersion >= 10) {
                return new PostgreSQL10Dialect();
            }
            return new PostgreSQL9Dialect();
        }

        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            Validate.notBlank(catalogName,
                    "Catalog name must be required while using jdbc api to operate postgresql database.");
            return String.format(
                    "jdbc:postgresql://%s:%d/%s?characterEncoding=utf8&allowMultiQueries=true&useSSL=false&stringtype=unspecified",
                    hostname, port, catalogName);
        }
    },

    ORACLE("Oracle", "oracle.jdbc.OracleDriver", 1521, false, true, true, OracleDialect::new,
            OracleMetaDataOperations::new) {
        @Override
        protected Dialect createVersionDialect(int majorVersion, int minorVersion) {
            if (majorVersion > 12 || majorVersion < 0 || (majorVersion == 12 && minorVersion >= 2)) {
                return new OracleDialect();
            } else if (majorVersion == 12) {
                return new Oracle12cDialect();
            }
            return new Oracle11gDialect();
        }

        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            Validate.notBlank(catalogName, "Service name must be required while connecting to oracle database.");
            return String.format("jdbc:oracle:thin:@//%s:%d/%s", hostname, port, catalogName);
        }

        @Override
        public boolean isCatalogSupported() {
            return false;
        }

        @Override
        public String getValidationQuery() {
            return "SELECT 1 FROM DUAL";
        }
    },

    SQLSERVER("SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, true, false, true,
            SQLServerDialect::new, SQLServerMetaDataOperations::new) {
        @Override
        protected Dialect createVersionDialect(int majorVersion, int minorVersion) {
            return majorVersion >= 11 || majorVersion < 0 ? new SQLServerDialect() : new SQLServer2008Dialect();
        }

        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            String database = StringUtils.isNotBlank(catalogName) ? ";databaseName=" + catalogName : "";
            return String.format("jdbc:sqlserver://%s:%d%s;encrypt=false;trustServerCertificate=true;sendTimeAsDatetime=false", hostname, port,
                    database);
        }
    },

    H2("H2", "org.h2.Driver", 9092, false, true, true, H2Dialect::new, H2MetaDataOperations::new) {
        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            Validate.notBlank(catalogName, "Database path must be required while connecting to h2 database.");
            if (StringUtils.isBlank(hostname)) {
                return catalogName.startsWith("mem:") ? "jdbc:h2:" + catalogName : "jdbc:h2:file:" + catalogName;
            }
            return String.format("jdbc:h2:tcp://%s:%d/%s", hostname, port, catalogName);
        }

        @Override
        public boolean isFileBased() {
            return true;
        }
    },

    SQLITE("SQLite", "org.sqlite.JDBC", 0, false, false, false, SQLiteDialect::new, SQLiteMetaDataOperations::new) {
        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            Validate.notBlank(catalogName, "Database file must be required while connecting to sqlite database.");
            return "jdbc:sqlite:" + catalogName + (catalogName.contains("?") ? "&" : "?") + "date_class=TEXT";
        }

        @Override
        public boolean isCatalogSupported() {
            return false;
        }

        @Override
        public boolean isFileBased() {
            return true;
        }
    },

    DUCKDB("DuckDB", "org.duckdb.DuckDBDriver", 0, false, true, true, DuckDBDialect::new,
            DuckDBMetaDataOperations::new) {
        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            Validate.notBlank(catalogName, "Database file must be required while connecting to duckdb database.");
            return "jdbc:duckdb:" + catalogName;
        }

        @Override
        public boolean isCatalogSupported() {
            return false;
        }

        @Override
        public boolean isFileBased() {
            return true;
        }

        @Override
        public DbCategory getCategory() {
            return DbCategory.OLAP;
        }
    },

    CLICKHOUSE("ClickHouse", "com.clickhouse.jdbc.ClickHouseDriver", 8123, false, true, true, ClickHouseDialect::new,
            ClickHouseMetaDataOperations::new) {
        @Override
        public String getUrl(String hostname, int port, String catalogName) {
            String database = StringUtils.isNotBlank(catalogName) ? "/" + catalogName : "";
            return normalizeUrl(String.format("jdbc:clickhouse:http://%s:%d%s", hostname, port, database));
        }

        /**
         * Compression of ClickHouse responses is turned off unless it is configured, the LZ4 frames of recent
         * servers are not read by the driver
         */
        @Override
        public String normalizeUrl(String url) {
            if (StringUtils.isBlank(url) || StringUtils.containsIgnoreCase(url, "compress=")) {
                return url;
            }
            return url + (url.contains("?") ? "&" : "?") + "compress=0";
        }

        @Override
        public boolean isCatalogSupported() {
            return false;
        }

        @Override
        public DbCategory getCategory() {
            return DbCategory.OLAP;
        }
    };

    private DbType(String displayName, String driverClassName, int defaultPort, boolean canSetCatalog,
                   boolean canSetSchema, boolean schemaSupported, Supplier<Dialect> dialectSupplier,
                   Supplier<MetaDataOperations> metaDataOperationsSupplier) {
        this.displayName = displayName;
        this.driverClassName = driverClassName;
        this.defaultPort = defaultPort;
        this.canSetCatalog = canSetCatalog;
        this.canSetSchema = canSetSchema;
        this.schemaSupported = schemaSupported;
        this.dialectSupplier = dialectSupplier;
        this.metaDataOperationsSupplier = metaDataOperationsSupplier;
    }

    private final String displayName;
    private final String driverClassName;
    private final int defaultPort;
    private final boolean canSetCatalog;
    private final boolean canSetSchema;
    private final boolean schemaSupported;
    private final Supplier<Dialect> dialectSupplier;
    private final Supplier<MetaDataOperations> metaDataOperationsSupplier;

    public String getDisplayName() {
        return displayName;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public boolean isCanSetCatalog() {
        return canSetCatalog;
    }

    public boolean isCanSetSchema() {
        return canSetSchema;
    }

    public boolean isSchemaSupported() {
        return schemaSupported;
    }

    /**
     * Whether the database server has multiple catalogs (databases) which can be listed
     */
    public boolean isCatalogSupported() {
        return true;
    }

    /**
     * Relational database (default) or OLAP database
     */
    public DbCategory getCategory() {
        return DbCategory.RELATIONAL;
    }

    /**
     * Whether the database is addressed by a file path instead of host and port
     */
    public boolean isFileBased() {
        return false;
    }

    public String getValidationQuery() {
        return "SELECT 1";
    }

    /**
     * Dialect of the latest version
     */
    public Dialect createDialect() {
        return dialectSupplier.get();
    }

    /**
     * Dialect of the given database version, differences of old versions are implemented by subclasses, e.g.
     * Oracle11gDialect, PostgreSQL9Dialect
     */
    public Dialect createDialect(int majorVersion, int minorVersion) {
        Dialect dialect = createVersionDialect(majorVersion, minorVersion);
        dialect.setDatabaseVersion(majorVersion, minorVersion);
        return dialect;
    }

    protected Dialect createVersionDialect(int majorVersion, int minorVersion) {
        return createDialect();
    }

    public MetaDataOperations createMetaDataOperations() {
        return metaDataOperationsSupplier.get();
    }

    public abstract String getUrl(String hostname, int port, String catalogName);

    /**
     * Jdbc url with the options required by SqlJam, e.g. a url entered by the user
     */
    public String normalizeUrl(String url) {
        return url;
    }

    @Override
    public String toString() {
        return displayName;
    }

    private static Map<String, DbType> cache = new CaseInsensitiveMap<DbType>();

    static {
        for (DbType dbType : DbType.values()) {
            cache.put(dbType.name().toLowerCase(), dbType);
        }
    }

    public static DbType forName(String name) {
        return cache.get(name);
    }

    public static DbType forUrl(String jdbcUrl) {
        if (StringUtils.isBlank(jdbcUrl)) {
            return null;
        }
        String url = jdbcUrl.toLowerCase();
        if (url.startsWith("jdbc:mysql:")) {
            return MYSQL;
        } else if (url.startsWith("jdbc:mariadb:")) {
            return MARIADB;
        } else if (url.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL;
        } else if (url.startsWith("jdbc:oracle:")) {
            return ORACLE;
        } else if (url.startsWith("jdbc:sqlserver:")) {
            return SQLSERVER;
        } else if (url.startsWith("jdbc:h2:")) {
            return H2;
        } else if (url.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        } else if (url.startsWith("jdbc:duckdb:")) {
            return DUCKDB;
        } else if (url.startsWith("jdbc:clickhouse:") || url.startsWith("jdbc:ch:")) {
            return CLICKHOUSE;
        }
        return null;
    }
}
