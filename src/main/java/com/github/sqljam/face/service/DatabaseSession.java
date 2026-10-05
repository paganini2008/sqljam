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

import java.io.Closeable;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ColumnInfo;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.DataPage;
import com.github.sqljam.face.model.ForeignKeyInfo;
import com.github.sqljam.face.model.IndexInfo;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.HikariDataSourceConnectionFactory;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.impexp.TableMetaData;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.jdbc.JdbcUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: DatabaseSession browses a database: catalogs, schemas, tables, metadata and rows of tables
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class DatabaseSession implements Closeable {

    private static final Set<String> MYSQL_SYSTEM_DATABASES = new HashSet<>(
            Arrays.asList("information_schema", "performance_schema", "mysql", "sys"));
    private static final int MAX_CELL_LENGTH = 1000;

    private final ConnectionProfile profile;
    private final DbType dbType;
    private final MetaDataOperations metaDataOperations;
    private final Dialect dialect;
    /**
     * Connection factories by catalog, PostgreSQL needs a connection per database
     */
    private final Map<String, ConnectionFactory> connectionFactories = new ConcurrentHashMap<>();

    public DatabaseSession(ConnectionProfile profile) {
        this.profile = profile;
        this.dbType = profile.getDbType();
        this.metaDataOperations = dbType.createMetaDataOperations();
        this.dialect = dbType.createDialect();
    }

    public ConnectionProfile getProfile() {
        return profile;
    }

    public DbType getDbType() {
        return dbType;
    }

    /**
     * Connects to the database and returns product name and version
     */
    public static String testConnection(ConnectionProfile profile) throws SQLException {
        DriverManager.setLoginTimeout(10);
        try (Connection connection = connect(profile, profile.getJdbcUrl())) {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion();
        }
    }

    private static Connection connect(ConnectionProfile profile, String url) throws SQLException {
        if (StringUtils.isBlank(profile.getUsername())) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, profile.getUsername(), profile.getPassword());
    }

    private ConnectionFactory getConnectionFactory(String catalog) {
        String key = dbType == DbType.POSTGRESQL ? StringUtils.defaultString(catalog, profile.getDatabase()) : "";
        return connectionFactories.computeIfAbsent(key, k -> {
            String url = dbType == DbType.POSTGRESQL ? profile.getJdbcUrl(k) : profile.getJdbcUrl();
            return new HikariDataSourceConnectionFactory(dbType.getDriverClassName(), url, profile.getUsername(),
                    profile.getPassword());
        });
    }

    private Connection getConnection(String catalog) throws SQLException {
        return getConnectionFactory(catalog).getConnection();
    }

    /**
     * Catalog name used by metadata operations
     */
    private String getMetaCatalog(String catalog) {
        if (!dbType.isCatalogSupported() || dbType == DbType.POSTGRESQL) {
            return null;
        }
        return catalog;
    }

    public String getDatabaseProduct() throws SQLException {
        try (Connection connection = getConnection(null)) {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion();
        }
    }

    /**
     * Databases of the server which the user can access, empty if the database type has no catalogs (Oracle,
     * SQLite). H2 has only the current database.
     */
    public List<String> getCatalogs() throws SQLException {
        if (!dbType.isCatalogSupported()) {
            return Collections.emptyList();
        }
        if (dbType.isFileBased()) {
            try (Connection connection = getConnection(null)) {
                return new ArrayList<>(Collections.singletonList(connection.getCatalog()));
            }
        }
        return getDatabases(profile);
    }

    /**
     * All databases of the server which the user can access, e.g. for choosing the database of a data source
     */
    public static List<String> getDatabases(ConnectionProfile profile) throws SQLException {
        DbType dbType = profile.getDbType();
        if (!dbType.isCatalogSupported() || dbType.isFileBased()) {
            return Collections.emptyList();
        }
        try (Connection connection = connect(profile, dbType == DbType.POSTGRESQL && StringUtils.isBlank(
                profile.getDatabase()) ? profile.getJdbcUrl("postgres") : profile.getJdbcUrl())) {
            switch (dbType) {
                case POSTGRESQL:
                    return queryStrings(connection, "SELECT datname FROM pg_database WHERE datallowconn"
                            + " AND NOT datistemplate AND has_database_privilege(datname, 'CONNECT') ORDER BY datname");
                case SQLSERVER:
                    return queryStrings(connection, "SELECT name FROM sys.databases WHERE state = 0"
                            + " AND database_id > 4 AND HAS_DBACCESS(name) = 1 ORDER BY name");
                default:
                    return dbType.createMetaDataOperations().getCatalogInfos(connection.getMetaData()).stream()
                            .map(info -> (String) info.get("TABLE_CAT"))
                            .filter(name -> !MYSQL_SYSTEM_DATABASES.contains(name.toLowerCase(Locale.ENGLISH)))
                            .collect(Collectors.toList());
            }
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    /**
     * Schemas of the catalog, empty if the database type has no schemas (MySQL, SQLite)
     */
    public List<String> getSchemas(String catalog) throws SQLException {
        if (!dbType.isSchemaSupported()) {
            return Collections.emptyList();
        }
        try (Connection connection = getConnection(catalog)) {
            return metaDataOperations.getSchemaInfos(connection.getMetaData(), getMetaCatalog(catalog)).stream()
                    .map(info -> (String) info.get("TABLE_SCHEM")).sorted().collect(Collectors.toList());
        }
    }

    public List<TableInfo> getTables(String catalog, String schema) throws SQLException {
        try (Connection connection = getConnection(catalog)) {
            List<TableInfo> tables = new ArrayList<>();
            for (Map<String, Object> info : metaDataOperations.getTableInfos(connection.getMetaData(),
                    getMetaCatalog(catalog), schema)) {
                tables.add(new TableInfo(catalog, schema, (String) info.get("TABLE_NAME"),
                        (String) info.get("REMARKS"), Boolean.TRUE.equals(info.get("IS_PARTITIONED")),
                        Boolean.TRUE.equals(info.get("IS_PARTITION_TABLE"))));
            }
            tables.sort(Comparator.comparing(TableInfo::getName, String.CASE_INSENSITIVE_ORDER));
            return tables;
        }
    }

    public List<ColumnInfo> getColumns(String catalog, String schema, String table) throws SQLException {
        try (Connection connection = getConnection(catalog)) {
            DatabaseMetaData metaData = connection.getMetaData();
            Set<String> primaryKeys = metaDataOperations.getPrimaryKeyInfos(metaData, getMetaCatalog(catalog),
                    schema, table).stream().map(info -> (String) info.get("COLUMN_NAME")).collect(Collectors.toSet());
            List<ColumnInfo> columns = new ArrayList<>();
            for (Map<String, Object> info : metaDataOperations.getColumnInfos(metaData, getMetaCatalog(catalog),
                    schema, table)) {
                String columnName = (String) info.get("COLUMN_NAME");
                columns.add(new ColumnInfo(TableMetaData.getInt(info, "ORDINAL_POSITION"), columnName,
                        (String) info.get("TYPE_NAME"), TableMetaData.getInt(info, "COLUMN_SIZE"),
                        TableMetaData.getInt(info, "DECIMAL_DIGITS"),
                        "YES".equalsIgnoreCase((String) info.get("IS_NULLABLE")), (String) info.get("COLUMN_DEF"),
                        primaryKeys.contains(columnName), "YES".equalsIgnoreCase((String) info.get(
                        "IS_AUTOINCREMENT")), "YES".equalsIgnoreCase((String) info.get("IS_GENERATEDCOLUMN")),
                        (String) info.get("REMARKS")));
            }
            columns.sort(Comparator.comparingInt(ColumnInfo::getPosition));
            return columns;
        }
    }

    public List<IndexInfo> getIndexes(String catalog, String schema, String table) throws SQLException {
        try (Connection connection = getConnection(catalog)) {
            Map<String, IndexInfo> indexes = new LinkedHashMap<>();
            List<Map<String, Object>> infos = new ArrayList<>(metaDataOperations.getIndexInfos(
                    connection.getMetaData(), getMetaCatalog(catalog), schema, table));
            infos.sort(Comparator.comparing((Map<String, Object> info) -> (String) info.get("INDEX_NAME"))
                    .thenComparingInt(info -> TableMetaData.getInt(info, "ORDINAL_POSITION")));
            for (Map<String, Object> info : infos) {
                String indexName = (String) info.get("INDEX_NAME");
                Object nonUnique = info.get("NON_UNIQUE");
                boolean unique = nonUnique instanceof Boolean ? !(Boolean) nonUnique
                        : "0".equals(String.valueOf(nonUnique)) || "false".equalsIgnoreCase(String.valueOf(nonUnique));
                indexes.computeIfAbsent(indexName, name -> new IndexInfo(name, unique, new ArrayList<>()))
                        .getColumns().add((String) info.get("COLUMN_NAME"));
            }
            return new ArrayList<>(indexes.values());
        }
    }

    public List<ForeignKeyInfo> getForeignKeys(String catalog, String schema, String table) throws SQLException {
        try (Connection connection = getConnection(catalog)) {
            Map<String, ForeignKeyInfo> foreignKeys = new LinkedHashMap<>();
            for (Map<String, Object> info : metaDataOperations.getImportedKeyInfos(connection.getMetaData(),
                    getMetaCatalog(catalog), schema, table)) {
                String name = StringUtils.defaultString((String) info.get("FK_NAME"),
                        "fk_" + info.get("PKTABLE_NAME"));
                ForeignKeyInfo foreignKey = foreignKeys.computeIfAbsent(name, key -> new ForeignKeyInfo(key,
                        new ArrayList<>(), (String) info.get("PKTABLE_SCHEM"), (String) info.get("PKTABLE_NAME"),
                        new ArrayList<>(), getRuleName(info.get("UPDATE_RULE")),
                        getRuleName(info.get("DELETE_RULE"))));
                foreignKey.getColumns().add((String) info.get("FKCOLUMN_NAME"));
                foreignKey.getReferencedColumns().add((String) info.get("PKCOLUMN_NAME"));
            }
            return new ArrayList<>(foreignKeys.values());
        }
    }

    static String getRuleName(Object rule) {
        if (!(rule instanceof Number)) {
            return "";
        }
        switch (((Number) rule).intValue()) {
            case DatabaseMetaData.importedKeyCascade:
                return "CASCADE";
            case DatabaseMetaData.importedKeySetNull:
                return "SET NULL";
            case DatabaseMetaData.importedKeySetDefault:
                return "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict:
                return "RESTRICT";
            default:
                return "NO ACTION";
        }
    }

    public long countRows(String catalog, String schema, String table) throws SQLException {
        try (Connection connection = getConnection(catalog)) {
            String sql = dialect.getCountTableStatement(getMetaCatalog(catalog), schema, table);
            Long count = JdbcUtils.fetchOne(connection, sql, Long.class);
            return count != null ? count : 0;
        }
    }

    /**
     * A page of rows formatted as text, rows are ordered by primary key if exists
     *
     * @param pageNumber 1-based page number
     */
    public DataPage getData(String catalog, String schema, String table, int pageNumber, int pageSize)
            throws SQLException {
        long totalRows = countRows(catalog, schema, table);
        try (Connection connection = getConnection(catalog)) {
            Dialect dialect = this.dialect.forVersion(connection.getMetaData().getDatabaseMajorVersion(),
                    connection.getMetaData().getDatabaseMinorVersion());
            List<String> primaryKeys = metaDataOperations.getPrimaryKeyInfos(connection.getMetaData(),
                    getMetaCatalog(catalog), schema, table).stream()
                    .sorted(Comparator.comparingInt(info -> TableMetaData.getInt(info, "KEY_SEQ")))
                    .map(info -> dialect.quoteIdentifier((String) info.get("COLUMN_NAME")))
                    .collect(Collectors.toList());
            List<Map<String, Object>> columnInfos = new ArrayList<>(metaDataOperations.getColumnInfos(
                    connection.getMetaData(), getMetaCatalog(catalog), schema, table));
            columnInfos.sort(Comparator.comparingInt(info -> TableMetaData.getInt(info, "ORDINAL_POSITION")));
            String sql = columnInfos.isEmpty() ? dialect.getSelectTableStatement(getMetaCatalog(catalog), schema,
                    table) : dialect.getSelectTableStatement(getMetaCatalog(catalog), schema, table,
                    columnInfos.stream().map(info -> (String) info.get("COLUMN_NAME")).toArray(String[]::new),
                    columnInfos.stream().map(info -> (String) info.get("TYPE_NAME")).toArray(String[]::new));
            String pageSql = dialect.getPageStatement(sql, primaryKeys.isEmpty() ? null
                    : String.join(",", primaryKeys), pageSize, Math.max(pageNumber - 1, 0) * pageSize);
            List<String> columns = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(pageSql); ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                List<Integer> indexes = new ArrayList<>();
                for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                    if (!"rn__".equalsIgnoreCase(rsmd.getColumnLabel(i))) {
                        columns.add(rsmd.getColumnLabel(i));
                        indexes.add(i);
                    }
                }
                while (rs.next()) {
                    List<String> row = new ArrayList<>(indexes.size());
                    for (int i : indexes) {
                        row.add(formatValue(JdbcUtils.getColumnValue(rs, i, rsmd.getColumnType(i),
                                rsmd.getColumnTypeName(i))));
                    }
                    rows.add(row);
                }
            }
            return new DataPage(columns, rows, pageNumber, pageSize, totalRows);
        }
    }

    /**
     * Text of a value for viewing, binary values are shown as size and hex prefix
     */
    static String formatValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 16); i++) {
                hex.append(String.format("%02X", bytes[i]));
            }
            return String.format("(%d bytes) 0x%s%s", bytes.length, hex, bytes.length > 16 ? "..." : "");
        }
        if (value instanceof Object[]) {
            return Arrays.deepToString((Object[]) value);
        }
        String text = value instanceof java.math.BigDecimal ? ((java.math.BigDecimal) value).toPlainString()
                : value.toString();
        return text.length() > MAX_CELL_LENGTH ? text.substring(0, MAX_CELL_LENGTH) + "..." : text;
    }

    /**
     * DDL of a table generated by the dialect of the given database type
     */
    public String getDdl(String catalog, String schema, String table, DbType targetDbType) throws Exception {
        return new DdlGenerator(profile).generate(catalog, schema, table, targetDbType);
    }

    @Override
    public void close() {
        for (ConnectionFactory connectionFactory : connectionFactories.values()) {
            try {
                connectionFactory.destroy();
            } catch (RuntimeException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Unable to close connection pool: {}", e.getMessage());
                }
            }
        }
        connectionFactories.clear();
    }
}
