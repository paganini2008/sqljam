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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.ImportExportHandler;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.impexp.ScriptExporter;
import com.github.sqljam.impexp.ScriptImporter;
import com.github.sqljam.impexp.SqlScriptRunner;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: PartitionIT backs up and restores partitioned tables of every partitioning type of each database
 *               (partitions.sql of the database, RANGE is the fixture table sales): imported directly and restored
 *               from a SQL package into the same database type, the partitioning, keys, partitions, bounds and rows
 *               of each partition are the same as the source tables. Other databases get the rows without
 *               partitions, and the data sources tell partitioned tables apart.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class PartitionIT {

    private static final String COPY_SUFFIX = "_cp";
    private static final Set<ItDatabase> LOADED = EnumSet.noneOf(ItDatabase.class);

    /**
     * Partitioned fixture tables are created once per test run, after the fixture of the database
     */
    private static synchronized void loadPartitions(ItDatabase db) throws Exception {
        db.loadFixture();
        if (LOADED.contains(db)) {
            return;
        }
        try (Connection connection = db.getSourceConnection();
             InputStream in = PartitionIT.class.getResourceAsStream("/" + db.name().toLowerCase(Locale.ENGLISH)
                     + "/partitions.sql")) {
            assertNotNull(in, "partitions.sql of " + db);
            new SqlScriptRunner(connection).runScript(new InputStreamReader(in,
                    StandardCharsets.UTF_8));
        }
        LOADED.add(db);
    }

    /**
     * Partitioned tables and their partitions, copies of earlier runs are not read
     */
    private static String tablePattern(ItDatabase db) {
        return "(?i)" + db.getPrefix() + "(sales|pt_(?!.*" + COPY_SUFFIX + "$).*)";
    }

    /**
     * The test users of MariaDB and Oracle have one schema, tables are copied next to the source tables with a
     * suffix. Other databases restore the tables of the same names into the target schema.
     */
    private static Connection getCopyConnection(ItDatabase db) throws SQLException {
        return db.isSingleSchema() ? db.getSourceConnection() : db.getTargetConnection();
    }

    private static String getCopySchema(ItDatabase db) {
        if (db.getDbType() == DbType.MYSQL || db.getDbType() == DbType.MARIADB) {
            return db.isSingleSchema() ? db.getSourceCatalog() : db.getTargetCatalog();
        }
        return db.isSingleSchema() ? db.getSourceSchema() : db.getTargetSchema();
    }

    private static String getSourceSchema(ItDatabase db) {
        return db.getDbType() == DbType.MYSQL || db.getDbType() == DbType.MARIADB ? db.getSourceCatalog()
                : db.getSourceSchema();
    }

    /**
     * Partitioned tables of the source schema, not their partitions (PostgreSQL)
     */
    private static List<String> getPartitionedTables(ItDatabase db, Connection connection) throws SQLException {
        MetaDataOperations operations = db.getDbType().createMetaDataOperations();
        String catalog = db.getDbType().isCatalogSupported() ? db.getSourceCatalog() : null;
        if (db.getDbType() == DbType.ORACLE) {
            catalog = null;
        }
        Pattern pattern = Pattern.compile(tablePattern(db));
        List<String> tables = new ArrayList<>();
        for (Map<String, Object> info : operations.getTableInfos(connection.getMetaData(), catalog,
                db.getSourceSchema())) {
            String name = (String) info.get("TABLE_NAME");
            if (pattern.matcher(name).matches() && !Boolean.TRUE.equals(info.get("IS_PARTITION_TABLE"))) {
                tables.add(name);
            }
        }
        assertTrue(tables.size() > 1, "Partitioned tables of " + db + ": " + tables);
        return tables;
    }

    private static String copyName(ItDatabase db, String table) {
        if (!db.isSingleSchema()) {
            return table;
        }
        return table.equals(table.toUpperCase(Locale.ENGLISH)) ? table + COPY_SUFFIX.toUpperCase(Locale.ENGLISH)
                : table + COPY_SUFFIX;
    }

    /**
     * Copies of the single schema databases are dropped
     */
    private static void dropCopies(ItDatabase db, List<String> tables) throws SQLException {
        if (!db.isSingleSchema()) {
            return;
        }
        try (Connection connection = db.getSourceConnection()) {
            dropTables(db, connection, tables.stream().map(table -> copyName(db, table)).collect(Collectors.toList()));
        }
    }

    @ParameterizedTest(name = "partitions of {0} imported directly")
    @EnumSource(value = ItDatabase.class, names = {"MYSQL", "MARIADB", "POSTGRESQL", "ORACLE", "SQLSERVER", "DB2",
            "CLICKHOUSE"})
    void copiesPartitionsDirectly(ItDatabase db) throws Exception {
        assumeTrue(AbstractImportIT.isTargetSelected(db));
        assumeTrue(db.isAvailable());
        loadPartitions(db);
        List<String> tables;
        try (Connection connection = db.getSourceConnection()) {
            tables = getPartitionedTables(db, connection);
        }
        dropCopies(db, tables);
        try {
            ImportExporter importExporter = new ImportExporter();
            db.configureSource(importExporter.getExportConfiguration());
            importExporter.getExportConfiguration().setIncludedTableNamePattern(tablePattern(db));
            ImportExportHandler.ImportConfiguration configuration = importExporter.getImportConfiguration();
            if (db.isSingleSchema()) {
                configuration.setDbType(db.getDbType());
                configuration.setUrl(db.getSourceUrl());
                configuration.setUsername(db.getUsername());
                configuration.setPassword(db.getPassword());
                configuration.setTargetCatalogName(db.getSourceCatalog());
                configuration.setTargetSchemaName(db.getSourceSchema());
                configuration.setTableNamePattern("{table}" + COPY_SUFFIX);
            } else {
                db.configureTarget(configuration);
            }
            CollectingListener listener = new CollectingListener();
            importExporter.setExportListener(listener);
            try {
                importExporter.exportDdlAndData();
            } catch (Exception e) {
                throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
            }
            assertEquals(List.of(), listener.getErrors(), "Errors of " + db);
            verifyPartitions(db, tables);
        } finally {
            dropCopies(db, tables);
        }
    }

    /**
     * A SQL package is a backup: other databases restore it into the target schema. The test users of MariaDB and
     * Oracle have one schema, their tables are dropped after the backup and restored from it.
     */
    @ParameterizedTest(name = "partitions of {0} restored from a SQL package")
    @EnumSource(value = ItDatabase.class, names = {"MYSQL", "MARIADB", "POSTGRESQL", "ORACLE", "SQLSERVER", "DB2",
            "CLICKHOUSE"})
    void restoresPartitionsFromScripts(ItDatabase db) throws Exception {
        assumeTrue(AbstractImportIT.isTargetSelected(db));
        assumeTrue(db.isAvailable());
        loadPartitions(db);
        List<String> tables;
        Map<String, List<String>> expected;
        try (Connection connection = db.getSourceConnection()) {
            tables = getPartitionedTables(db, connection);
            expected = getPartitions(db, connection, getSourceSchema(db), tables);
        }
        File dir = new File(ItDatabase.DATA_DIR, "partitions/" + db);
        FileUtils.deleteQuietly(dir);
        ScriptExporter scriptExporter = new ScriptExporter(dir, false);
        db.configureSource(scriptExporter.getConfiguration());
        scriptExporter.getConfiguration().setIncludedTableNamePattern(tablePattern(db));
        if (!db.isSingleSchema()) {
            scriptExporter.setTargetCatalogName(db.getTargetCatalog());
            scriptExporter.setTargetSchemaName(db.getTargetSchema());
        }
        CollectingListener listener = new CollectingListener();
        scriptExporter.setExportListener(listener);
        scriptExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors(), "Errors of exporting " + db);

        Connection connection = db.isSingleSchema() ? db.getSourceConnection() : db.getTargetConnection();
        try {
            if (db.isSingleSchema()) {
                // The fixture is loaded again by the next run
                LOADED.remove(db);
                dropTables(db, connection, tables);
            }
            ScriptImporter importer = new ScriptImporter(connection, db.getDbType());
            listener = new CollectingListener();
            importer.setExportListener(listener);
            try {
                importer.importDirectory(dir);
            } catch (Exception e) {
                throw new AssertionError(e.getMessage() + "\nErrors: " + listener.getErrors(), e);
            }
            assertEquals(List.of(), listener.getErrors(), "Errors of restoring " + db);
            Map<String, List<String>> actual = getPartitions(db, connection, db.isSingleSchema()
                    ? getSourceSchema(db) : getCopySchema(db), tables);
            for (String table : tables) {
                assertEquals(String.join("\n", expected.get(table)), String.join("\n", actual.get(table)),
                        "Partitions of " + table);
            }
        } finally {
            connection.close();
        }
    }

    /**
     * Tables are dropped, children of references first (Oracle)
     */
    private static void dropTables(ItDatabase db, Connection connection, List<String> tables) throws SQLException {
        List<String> ordered = new ArrayList<>(tables);
        ordered.sort((a, b) -> Boolean.compare(!a.toLowerCase(Locale.ENGLISH).contains("child"),
                !b.toLowerCase(Locale.ENGLISH).contains("child")));
        for (String table : ordered) {
            String sql = db == ItDatabase.ORACLE ? String.format("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"%s\" "
                    + "CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;", table)
                    : "DROP TABLE IF EXISTS `" + table + "`";
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private static Map<String, List<String>> getPartitions(ItDatabase db, Connection connection, String schema,
                                                           List<String> tables) throws SQLException {
        Map<String, List<String>> partitions = new LinkedHashMap<>();
        for (String table : tables) {
            List<String> lines = getPartitions(db, connection, schema, table);
            assertFalse(lines.isEmpty(), "Partitions of " + table + " in " + schema);
            partitions.put(table, lines);
        }
        return partitions;
    }

    /**
     * Other databases create plain tables with all rows
     */
    @ParameterizedTest(name = "partitioned tables of {0} into H2")
    @EnumSource(value = ItDatabase.class, names = {"MYSQL", "MARIADB", "POSTGRESQL", "ORACLE", "SQLSERVER", "DB2",
            "CLICKHOUSE"})
    void exportsPartitionedTablesIntoOtherDatabases(ItDatabase db) throws Exception {
        assumeTrue(AbstractImportIT.isTargetSelected(db));
        ItDatabase target = ItDatabase.H2;
        assumeTrue(db.isAvailable() && target.isAvailable());
        loadPartitions(db);
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = db.getSourceConnection()) {
            Dialect dialect = db.getDbType().createDialect();
            String catalog = db.getDbType() == DbType.ORACLE || !db.getDbType().isCatalogSupported() ? null
                    : db.getSourceCatalog();
            for (String table : getPartitionedTables(db, connection)) {
                counts.put(table.toUpperCase(Locale.ENGLISH), count(connection, "SELECT COUNT(*) FROM "
                        + dialect.getSourceTableName(catalog, db.getSourceSchema(), table)));
            }
        }
        ImportExporter importExporter = new ImportExporter();
        db.configureSource(importExporter.getExportConfiguration());
        importExporter.getExportConfiguration().setIncludedTableNamePattern(tablePattern(db));
        target.configureTarget(importExporter.getImportConfiguration());
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        importExporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors(), "Errors of " + db + " -> " + target);
        try (Connection connection = target.getTargetConnection()) {
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                assertEquals(entry.getValue(), count(connection, "SELECT COUNT(*) FROM \"" + entry.getKey() + "\""),
                        "Rows of " + entry.getKey());
            }
        }
    }

    /**
     * Data sources mark partitioned tables with their partitioning, partitions know their partitioned tables
     */
    @ParameterizedTest(name = "partitioned tables of {0} in the data source")
    @EnumSource(value = ItDatabase.class, names = {"MYSQL", "MARIADB", "POSTGRESQL", "ORACLE", "SQLSERVER", "DB2",
            "CLICKHOUSE"})
    void listsPartitionedTables(ItDatabase db) throws Exception {
        assumeTrue(AbstractImportIT.isTargetSelected(db));
        assumeTrue(db.isAvailable());
        loadPartitions(db);
        List<String> partitioned;
        try (Connection connection = db.getSourceConnection()) {
            partitioned = getPartitionedTables(db, connection);
        }
        try (DatabaseSession session = new DatabaseSession(db.getSourceProfile())) {
            Map<String, TableInfo> tables = session.getTables(db.getSourceCatalog(), db.getSourceSchema()).stream()
                    .collect(Collectors.toMap(TableInfo::getName, table -> table, (a, b) -> a));
            for (String name : partitioned) {
                TableInfo table = tables.get(name);
                assertNotNull(table, name + " in " + tables.keySet());
                assertTrue(table.isPartitioned(), name + " is partitioned");
                assertTrue(StringUtils.isNotBlank(table.getPartitionType())
                        || StringUtils.isNotBlank(table.getPartitionKey()), "Partitioning of " + name);
            }
            TableInfo dept = tables.values().stream().filter(table -> table.getName().equalsIgnoreCase(
                    db.getPrefix() + "dept")).findFirst().orElseThrow();
            assertFalse(dept.isPartitioned(), "A plain table");
            if (db == ItDatabase.POSTGRESQL) {
                TableInfo partition = tables.get(db.getPrefix() + "pt_sub_2024_n");
                assertTrue(partition.isPartition());
                assertEquals(db.getPrefix() + "pt_sub_2024", partition.getParent());
                assertTrue(tables.get(db.getPrefix() + "pt_sub_2024").isPartitioned(), "A partitioned partition");
            }
        }
    }

    private void verifyPartitions(ItDatabase db, List<String> tables) throws Exception {
        try (Connection source = db.getSourceConnection(); Connection copy = getCopyConnection(db)) {
            for (String table : tables) {
                List<String> expected = getPartitions(db, source, getSourceSchema(db), table);
                assertFalse(expected.isEmpty(), "Partitions of the source table " + table);
                List<String> actual = getPartitions(db, copy, getCopySchema(db), copyName(db, table));
                assertEquals(String.join("\n", expected), String.join("\n", actual), "Partitions of " + table);
            }
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<Map<String, Object>> query(Connection connection, String sql, Object... parameters)
            throws SQLException {
        return JdbcUtils.fetchAll(connection, sql, parameters);
    }

    /**
     * Partitioning of a table as lines: the partitioning and key, then each partition with its bound and rows.
     * Empty if the table is not partitioned. Names generated by the database (Oracle SYS_P...) are not compared.
     */
    static List<String> getPartitions(ItDatabase db, Connection connection, String schema, String table)
            throws SQLException {
        switch (db.getDbType()) {
            case MYSQL:
            case MARIADB:
                return getMySQLPartitions(connection, schema, table);
            case POSTGRESQL:
                return getPostgreSQLPartitions(connection, schema, table);
            case ORACLE:
                return getOraclePartitions(connection, schema, table);
            case SQLSERVER:
                return getSQLServerPartitions(connection, schema, table);
            case DB2:
                return getDb2Partitions(connection, schema, table);
            case CLICKHOUSE:
                return getClickHousePartitions(connection, schema, table);
            default:
                throw new IllegalArgumentException(db.toString());
        }
    }

    private static List<String> getMySQLPartitions(Connection connection, String schema, String table)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : query(connection, "SELECT PARTITION_NAME, SUBPARTITION_NAME, PARTITION_METHOD,"
                + " SUBPARTITION_METHOD, PARTITION_EXPRESSION, SUBPARTITION_EXPRESSION, PARTITION_DESCRIPTION"
                + " FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
                + " AND PARTITION_NAME IS NOT NULL ORDER BY PARTITION_ORDINAL_POSITION, SUBPARTITION_ORDINAL_POSITION",
                schema, table)) {
            String subpartition = (String) row.get("SUBPARTITION_NAME");
            String name = subpartition != null ? subpartition : (String) row.get("PARTITION_NAME");
            long rows = count(connection, String.format("SELECT COUNT(*) FROM `%s`.`%s` PARTITION (`%s`)", schema,
                    table, name));
            lines.add(String.join(" | ", String.valueOf(row.get("PARTITION_METHOD")),
                    String.valueOf(row.get("PARTITION_EXPRESSION")), String.valueOf(row.get("SUBPARTITION_METHOD")),
                    String.valueOf(row.get("SUBPARTITION_EXPRESSION")), (String) row.get("PARTITION_NAME"),
                    String.valueOf(subpartition), String.valueOf(row.get("PARTITION_DESCRIPTION")),
                    "rows " + rows));
        }
        return lines;
    }

    private static List<String> getPostgreSQLPartitions(Connection connection, String schema, String table)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        String sql = "WITH RECURSIVE tree AS (SELECT c.oid, c.relname::text AS name, 0 AS depth FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?"
                + " UNION ALL SELECT i.inhrelid, ch.relname::text, t.depth + 1 FROM tree t"
                + " JOIN pg_inherits i ON i.inhparent = t.oid JOIN pg_class ch ON ch.oid = i.inhrelid)"
                + " SELECT t.name, t.depth, c.relkind::text AS kind, pg_get_partkeydef(c.oid) AS partkey,"
                + " pg_get_expr(c.relpartbound, c.oid) AS bound FROM tree t JOIN pg_class c ON c.oid = t.oid"
                + " ORDER BY t.depth, t.name";
        List<Map<String, Object>> rows = query(connection, sql, schema, table);
        if (rows.isEmpty() || !"p".equals(rows.get(0).get("kind"))) {
            return lines;
        }
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("name");
            long count = count(connection, String.format("SELECT COUNT(*) FROM \"%s\".\"%s\"", schema, name));
            lines.add(String.join(" | ", row.get("depth").toString(), name.equals(table) ? "*" : name,
                    String.valueOf(row.get("partkey")), String.valueOf(row.get("bound")), "rows " + count));
        }
        return lines;
    }

    private static String normalizeOracleName(String name) {
        return name == null ? null : name.replaceAll("^SYS_SUBP\\d+$", "SYS_SUBP").replaceAll("^SYS_P\\d+$", "SYS_P");
    }

    private static List<String> getOraclePartitions(Connection connection, String schema, String table)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> partTables = query(connection, "SELECT PARTITIONING_TYPE, SUBPARTITIONING_TYPE,"
                + " INTERVAL, AUTOLIST FROM ALL_PART_TABLES WHERE OWNER = ? AND TABLE_NAME = ?", schema, table);
        if (partTables.isEmpty()) {
            return lines;
        }
        Map<String, Object> partTable = partTables.get(0);
        String keys = query(connection, "SELECT COLUMN_NAME FROM ALL_PART_KEY_COLUMNS WHERE OWNER = ? AND NAME = ?"
                + " AND OBJECT_TYPE = 'TABLE' ORDER BY COLUMN_POSITION", schema, table).stream()
                .map(row -> (String) row.get("COLUMN_NAME")).collect(Collectors.joining(","));
        String subKeys = query(connection, "SELECT COLUMN_NAME FROM ALL_SUBPART_KEY_COLUMNS WHERE OWNER = ?"
                + " AND NAME = ? AND OBJECT_TYPE = 'TABLE' ORDER BY COLUMN_POSITION", schema, table).stream()
                .map(row -> (String) row.get("COLUMN_NAME")).collect(Collectors.joining(","));
        lines.add(String.join(" | ", (String) partTable.get("PARTITIONING_TYPE"), keys,
                (String) partTable.get("SUBPARTITIONING_TYPE"), subKeys, String.valueOf(partTable.get("INTERVAL")),
                String.valueOf(partTable.get("AUTOLIST"))));
        // HIGH_VALUE is a LONG, it is read in the order of the columns
        List<String[]> partitions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT PARTITION_NAME, PARTITION_POSITION,"
                + " HIGH_VALUE FROM ALL_TAB_PARTITIONS WHERE TABLE_OWNER = ? AND TABLE_NAME = ?"
                + " ORDER BY PARTITION_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    rs.getInt(2);
                    partitions.add(new String[]{name, rs.getString(3)});
                }
            }
        }
        for (String[] partition : partitions) {
            long rows = count(connection, String.format("SELECT COUNT(*) FROM \"%s\".\"%s\" PARTITION (\"%s\")",
                    schema, table, partition[0]));
            lines.add(String.join(" | ", normalizeOracleName(partition[0]), String.valueOf(partition[1]),
                    "rows " + rows));
            try (PreparedStatement ps = connection.prepareStatement("SELECT SUBPARTITION_NAME,"
                    + " SUBPARTITION_POSITION, HIGH_VALUE FROM ALL_TAB_SUBPARTITIONS WHERE TABLE_OWNER = ?"
                    + " AND TABLE_NAME = ? AND PARTITION_NAME = ? ORDER BY SUBPARTITION_POSITION")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                ps.setString(3, partition[0]);
                List<String[]> subpartitions = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        rs.getInt(2);
                        subpartitions.add(new String[]{name, rs.getString(3)});
                    }
                }
                for (String[] subpartition : subpartitions) {
                    long subRows = count(connection, String.format("SELECT COUNT(*) FROM \"%s\".\"%s\""
                            + " SUBPARTITION (\"%s\")", schema, table, subpartition[0]));
                    lines.add(String.join(" | ", "  " + normalizeOracleName(subpartition[0]),
                            String.valueOf(subpartition[1]), "rows " + subRows));
                }
            }
        }
        return lines;
    }

    private static List<String> getSQLServerPartitions(Connection connection, String schema, String table)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> functions = query(connection, "SELECT pf.function_id, pf.name AS function_name,"
                + " pf.boundary_value_on_right, ps.name AS scheme_name, c.name AS column_name"
                + " FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id"
                + " JOIN sys.indexes i ON i.object_id = t.object_id AND i.index_id IN (0, 1)"
                + " JOIN sys.partition_schemes ps ON ps.data_space_id = i.data_space_id"
                + " JOIN sys.partition_functions pf ON pf.function_id = ps.function_id"
                + " JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id"
                + " AND ic.partition_ordinal > 0 JOIN sys.columns c ON c.object_id = t.object_id"
                + " AND c.column_id = ic.column_id WHERE s.name = ? AND t.name = ?", schema, table);
        if (functions.isEmpty()) {
            return lines;
        }
        Map<String, Object> function = functions.get(0);
        String boundaries = query(connection, "SELECT CAST(value AS NVARCHAR(100)) AS v FROM"
                + " sys.partition_range_values WHERE function_id = ? ORDER BY boundary_id",
                function.get("function_id")).stream().map(row -> (String) row.get("v"))
                .collect(Collectors.joining(","));
        lines.add(String.join(" | ", (String) function.get("function_name"), (String) function.get("scheme_name"),
                (String) function.get("column_name"), Boolean.TRUE.equals(function.get("boundary_value_on_right"))
                        ? "RIGHT" : "LEFT", boundaries));
        String sql = String.format("SELECT $PARTITION.[%s]([%s]) AS p, COUNT(*) AS n FROM [%s].[%s]"
                        + " GROUP BY $PARTITION.[%s]([%s]) ORDER BY p", function.get("function_name"),
                function.get("column_name"), schema, table, function.get("function_name"),
                function.get("column_name"));
        for (Map<String, Object> row : query(connection, sql)) {
            lines.add("partition " + row.get("p") + " | rows " + row.get("n"));
        }
        return lines;
    }

    private static List<String> getDb2Partitions(Connection connection, String schema, String table)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> keys = query(connection, "SELECT CAST(DATAPARTITIONEXPRESSION AS VARCHAR(512))"
                + " AS EXPR, NULLSFIRST FROM SYSCAT.DATAPARTITIONEXPRESSION WHERE TABSCHEMA = ? AND TABNAME = ?"
                + " ORDER BY DATAPARTITIONKEYSEQ", schema, table);
        if (keys.isEmpty()) {
            return lines;
        }
        String key = keys.stream().map(row -> StringUtils.trim((String) row.get("EXPR")))
                .collect(Collectors.joining(","));
        lines.add("RANGE | " + key);
        Map<Integer, Long> rows = new LinkedHashMap<>();
        String first = StringUtils.trim((String) keys.get(0).get("EXPR"));
        for (Map<String, Object> row : query(connection, String.format("SELECT DATAPARTITIONNUM(%s) AS P,"
                + " COUNT(*) AS N FROM \"%s\".\"%s\" GROUP BY DATAPARTITIONNUM(%s)", first, schema, table, first))) {
            rows.put(((Number) row.get("P")).intValue(), ((Number) row.get("N")).longValue());
        }
        for (Map<String, Object> row : query(connection, "SELECT SEQNO, DATAPARTITIONNAME,"
                + " CAST(LOWVALUE AS VARCHAR(512)) AS LOWVALUE, LOWINCLUSIVE, CAST(HIGHVALUE AS VARCHAR(512))"
                + " AS HIGHVALUE, HIGHINCLUSIVE FROM SYSCAT.DATAPARTITIONS WHERE TABSCHEMA = ? AND TABNAME = ?"
                + " ORDER BY SEQNO", schema, table)) {
            int seqNo = ((Number) row.get("SEQNO")).intValue();
            lines.add(String.join(" | ", (String) row.get("DATAPARTITIONNAME"), (String) row.get("LOWVALUE"),
                    (String) row.get("LOWINCLUSIVE"), (String) row.get("HIGHVALUE"),
                    (String) row.get("HIGHINCLUSIVE"), "rows " + rows.getOrDefault(seqNo, 0L)));
        }
        return lines;
    }

    private static List<String> getClickHousePartitions(Connection connection, String schema, String table)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> tables = query(connection, "SELECT partition_key FROM system.tables"
                + " WHERE database = ? AND name = ?", schema, table);
        if (tables.isEmpty() || StringUtils.isBlank((String) tables.get(0).get("partition_key"))) {
            return lines;
        }
        lines.add("PARTITION BY " + tables.get(0).get("partition_key"));
        for (Map<String, Object> row : query(connection, "SELECT partition, toString(sum(rows)) AS n"
                + " FROM system.parts WHERE database = ? AND table = ? AND active GROUP BY partition"
                + " ORDER BY partition", schema, table)) {
            lines.add(row.get("partition") + " | rows " + row.get("n"));
        }
        return lines;
    }
}
