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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.MetaDataOperations;
import com.github.sqljam.jdbc.JdbcUtils;

/**
 * @Description: ItVerifier verifies tables imported from a fixture database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ItVerifier {

    private static final Set<ItDatabase> SEQUENCE_DATABASES = EnumSet.of(ItDatabase.POSTGRESQL, ItDatabase.ORACLE,
            ItDatabase.SQLSERVER, ItDatabase.H2);
    private static final Set<ItDatabase> PARTITION_DATABASES = EnumSet.of(ItDatabase.MYSQL, ItDatabase.POSTGRESQL,
            ItDatabase.ORACLE, ItDatabase.SQLSERVER);

    private final ItDatabase source;
    private final ItDatabase target;
    private final Connection connection;
    private final MetaDataOperations operations;
    private final Dialect dialect;
    private final String catalog;
    private final String schema;
    private final Map<String, String> tableNames = new HashMap<>();

    public ItVerifier(ItDatabase source, ItDatabase target, Connection connection) throws SQLException {
        this(source, target, connection, target.getTargetSchema());
    }

    public ItVerifier(ItDatabase source, ItDatabase target, Connection connection, String schema)
            throws SQLException {
        this.source = source;
        this.target = target;
        this.connection = connection;
        this.operations = target.getDbType().createMetaDataOperations();
        this.dialect = target.getDbType().createDialect();
        DbType dbType = target.getDbType();
        this.catalog = dbType == DbType.ORACLE || dbType == DbType.SQLITE ? null : connection.getCatalog();
        this.schema = schema;
        for (Map<String, Object> info : operations.getTableInfos(connection.getMetaData(), catalog, schema)) {
            String tableName = (String) info.get("TABLE_NAME");
            tableNames.put(tableName.toLowerCase(Locale.ENGLISH), tableName);
        }
    }

    private DatabaseMetaData metaData() throws SQLException {
        return connection.getMetaData();
    }

    public String table(String name) {
        String tableName = tableNames.get((source.getPrefix() + name).toLowerCase(Locale.ENGLISH));
        assertNotNull(tableName, "Table not found in " + target + ": " + source.getPrefix() + name + ", tables: "
                + tableNames.keySet());
        return tableName;
    }

    private String ref(String name) {
        return dialect.getSourceTableName(catalog, schema, table(name));
    }

    private Map<String, String> columns(String name) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        for (Map<String, Object> info : operations.getColumnInfos(metaData(), catalog, schema, table(name))) {
            String columnName = (String) info.get("COLUMN_NAME");
            columns.put(columnName.toLowerCase(Locale.ENGLISH), columnName);
        }
        return columns;
    }

    private String column(Map<String, String> columns, String name) {
        String columnName = columns.get(name);
        assertNotNull(columnName, "Column not found: " + name + " in " + columns.keySet());
        return dialect.quoteIdentifier(columnName);
    }

    public long count(String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + ref(name))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Object queryEmp(Map<String, String> columns, String column, long id) throws SQLException {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", column(columns, column), ref("emp"),
                column(columns, "id"));
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Row not found: " + id);
                return JdbcUtils.getColumnValue(rs, 1, rs.getMetaData().getColumnType(1),
                        rs.getMetaData().getColumnTypeName(1));
            }
        }
    }

    public void verifyAll() throws Exception {
        verifyCounts();
        verifyValues();
        verifyConstraints();
        verifyComments();
        verifyIdentity();
        verifySequences();
        verifyPartitions();
        verifyTypes();
    }

    /**
     * Structure only: tables, keys, indexes, comments, sequences and partitions exist without rows
     */
    public void verifyStructureOnly() throws SQLException {
        for (String name : Arrays.asList("dept", "emp", "emp_tag", "sales", "types")) {
            assertEquals(0, count(name), name + " rows");
        }
        verifyConstraints();
        verifyComments();
        verifySequences();
        verifyPartitions();
    }

    public void verifyCounts() throws SQLException {
        assertEquals(3, count("dept"), "dept rows");
        assertEquals(ItDatabase.EMP_COUNT, count("emp"), "emp rows");
        assertEquals(10, count("emp_tag"), "emp_tag rows");
        assertEquals(20, count("sales"), "sales rows");
        assertEquals(3, count("types"), "types rows");
    }

    public void verifyValues() throws SQLException {
        Map<String, String> columns = columns("emp");
        assertEquals(ItDatabase.NOTE, queryEmp(columns, "note", 2));
        assertEquals(ItDatabase.PROFILE, queryEmp(columns, "profile", 1));
        Object photo = queryEmp(columns, "photo", 10);
        assertArrayEquals(ItDatabase.photo(10), (byte[]) photo);
        Object salary = queryEmp(columns, "salary", 3);
        assertEquals(0, new BigDecimal("30.75").compareTo(new BigDecimal(salary.toString())), "salary");
        assertEquals(null, queryEmp(columns, "salary", 7));
    }

    public void verifyConstraints() throws SQLException {
        List<Map<String, Object>> pkInfos = operations.getPrimaryKeyInfos(metaData(), catalog, schema,
                table("emp_tag"));
        assertEquals(2, pkInfos.size(), "Composite primary key of emp_tag");
        List<Map<String, Object>> fkInfos = operations.getImportedKeyInfos(metaData(), catalog, schema, table("emp"));
        assertEquals(1, fkInfos.size(), "Foreign key of emp: " + fkInfos);
        assertEquals((source.getPrefix() + "dept").toLowerCase(Locale.ENGLISH),
                ((String) fkInfos.get(0).get("PKTABLE_NAME")).toLowerCase(Locale.ENGLISH));
        List<Map<String, Object>> indexInfos = operations.getIndexInfos(metaData(), catalog, schema, table("emp"));
        boolean uniqueEmail = indexInfos.stream().anyMatch(info -> "email".equalsIgnoreCase(
                (String) info.get("COLUMN_NAME")) && !Boolean.TRUE.equals(asBoolean(info.get("NON_UNIQUE"))));
        assertTrue(uniqueEmail, "Unique index of email: " + indexInfos);
        Set<String> indexColumns = indexInfos.stream().map(info -> ((String) info.get("COLUMN_NAME"))
                .toLowerCase(Locale.ENGLISH)).collect(Collectors.toSet());
        assertTrue(indexColumns.containsAll(Arrays.asList("dept_id", "name")), "Index of dept_id,name: " + indexInfos);
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return value != null ? Boolean.valueOf(value.toString()) : null;
    }

    public void verifyComments() throws SQLException {
        if (source == ItDatabase.SQLITE || target == ItDatabase.SQLITE) {
            return;
        }
        Map<String, Object> nameColumn = operations.getColumnInfos(metaData(), catalog, schema, table("emp")).stream()
                .filter(info -> "name".equalsIgnoreCase((String) info.get("COLUMN_NAME"))).findFirst().orElseThrow();
        assertEquals("Employee name", nameColumn.get("REMARKS"), "Comment of emp.name");
    }

    /**
     * Identity continues from the max imported value
     */
    public void verifyIdentity() throws SQLException {
        Map<String, String> columns = columns("dept");
        String insert = String.format("INSERT INTO %s (%s) VALUES ('New Dept')", ref("dept"),
                column(columns, "name"));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(insert);
        }
        String query = String.format("SELECT %s FROM %s WHERE %s = 'New Dept'", column(columns, "id"), ref("dept"),
                column(columns, "name"));
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(query)) {
            assertTrue(rs.next());
            assertEquals(4L, rs.getLong(1), "Identity value after import");
        }
    }

    public void verifySequences() throws SQLException {
        if (!SEQUENCE_DATABASES.contains(source) || !SEQUENCE_DATABASES.contains(target)) {
            return;
        }
        String sequenceName = source.getPrefix() + "order_seq";
        List<Map<String, Object>> sequenceInfos = operations.getSequenceInfos(metaData(), catalog, schema);
        Map<String, Object> sequenceInfo = sequenceInfos.stream()
                .filter(info -> sequenceName.equalsIgnoreCase((String) info.get("SEQUENCE_NAME"))).findFirst()
                .orElse(null);
        assertNotNull(sequenceInfo, "Sequence not found: " + sequenceName + " in " + sequenceInfos);
        long startValue = new BigDecimal(sequenceInfo.get("START_VALUE").toString()).longValue();
        assertTrue(startValue >= 1000, "Sequence start value: " + sequenceInfo);
    }

    public void verifyPartitions() throws SQLException {
        verifyPartitions(source == target && PARTITION_DATABASES.contains(source));
    }

    public void verifyPartitions(boolean partitioned) throws SQLException {
        if (!partitioned) {
            Map<String, Object> salesInfo = operations.getTableInfos(metaData(), catalog, schema).stream()
                    .filter(info -> table("sales").equals(info.get("TABLE_NAME"))).findFirst().orElseThrow();
            assertTrue(!Boolean.TRUE.equals(salesInfo.get("IS_PARTITIONED")), "Not partitioned: " + salesInfo);
            return;
        }
        Map<String, Object> salesInfo = operations.getTableInfos(metaData(), catalog, schema).stream()
                .filter(info -> table("sales").equals(info.get("TABLE_NAME"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, salesInfo.get("IS_PARTITIONED"), "Partitioned sales: " + salesInfo);
    }

    /**
     * Values of all column types are the same when importing into the same database type
     */
    public void verifyTypes() throws Exception {
        if (source != target) {
            return;
        }
        List<Map<String, Object>> expected;
        try (Connection sourceConnection = source.getSourceConnection()) {
            MetaDataOperations sourceOperations = source.getDbType().createMetaDataOperations();
            String sourceCatalog = source.getDbType() == DbType.ORACLE || source.getDbType() == DbType.SQLITE ? null
                    : sourceConnection.getCatalog();
            String typesTable = sourceOperations.getTableInfos(sourceConnection.getMetaData(), sourceCatalog,
                    source.getSourceSchema()).stream().map(info -> (String) info.get("TABLE_NAME"))
                    .filter(name -> name.equalsIgnoreCase(source.getPrefix() + "types")).findFirst().orElseThrow();
            expected = readRows(sourceConnection, dialect.getSourceTableName(sourceCatalog, source.getSourceSchema(),
                    typesTable));
        }
        List<Map<String, Object>> actual = readRows(connection, ref("types"));
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            for (Map.Entry<String, Object> entry : expected.get(i).entrySet()) {
                String column = entry.getKey();
                if (column.endsWith("rowversion")) {
                    continue;
                }
                assertValueEquals(entry.getValue(), actual.get(i).get(column),
                        String.format("%s -> %s, row %d, column %s", source, target, i + 1, column));
            }
        }
    }

    private List<Map<String, Object>> readRows(Connection connection, String table) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
            ResultSetMetaData rsmd = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                    row.put(rsmd.getColumnLabel(i).toLowerCase(Locale.ENGLISH), JdbcUtils.getColumnValue(rs, i,
                            rsmd.getColumnType(i), rsmd.getColumnTypeName(i)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    static void assertValueEquals(Object expected, Object actual, String message) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, message);
            return;
        }
        if (expected instanceof byte[] && actual instanceof byte[]) {
            assertArrayEquals((byte[]) expected, (byte[]) actual, message);
        } else if (expected instanceof Object[] && actual instanceof Object[]) {
            assertArrayEquals((Object[]) expected, (Object[]) actual, message);
        } else if (expected instanceof BigDecimal && actual instanceof BigDecimal) {
            assertEquals(0, ((BigDecimal) expected).compareTo((BigDecimal) actual), message + ": " + expected
                    + " != " + actual);
        } else if (expected.getClass() != actual.getClass()) {
            assertEquals(String.valueOf(expected), String.valueOf(actual), message + " (" + expected.getClass()
                    .getSimpleName() + " vs " + actual.getClass().getSimpleName() + ")");
        } else if (!expected.equals(actual)) {
            fail(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
