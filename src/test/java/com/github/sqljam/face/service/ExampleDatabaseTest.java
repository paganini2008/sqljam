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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.ExportManifest;

/**
 * @Description: ExampleDatabaseTest verifies the example database of the first start: tables, keys, indexes, rows,
 *               computed totals, later starts, the switch and failures which do not stop the application
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class ExampleDatabaseTest {

    @TempDir
    File dir;

    private File connectionsFile() {
        return new File(dir, "connections.json");
    }

    private static long count(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void createsShopAtFirstStart() throws Exception {
        ExampleDatabase example = new ExampleDatabase(true, new File(dir, "example"));
        Optional<ConnectionProfile> created = example.createAtFirstStart(connectionsFile());
        assertTrue(created.isPresent());
        ConnectionProfile profile = created.get();
        assertEquals(ExampleDatabase.PROFILE_NAME, profile.getName());
        assertEquals(DbType.H2, profile.getDbType());
        assertTrue(new File(dir, "example/shop.mv.db").isFile());
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getTables(null, "PUBLIC", null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertEquals(List.of("CATEGORIES", "CUSTOMERS", "ORDERS", "ORDER_ITEMS", "PRODUCTS"),
                    tables.stream().sorted().toList());
            assertEquals(6, count(statement, "SELECT COUNT(*) FROM categories"));
            assertEquals(50, count(statement, "SELECT COUNT(*) FROM customers"));
            assertEquals(30, count(statement, "SELECT COUNT(*) FROM products"));
            assertEquals(300, count(statement, "SELECT COUNT(*) FROM orders"));
            // 1 to 3 items per order
            assertEquals(600, count(statement, "SELECT COUNT(*) FROM order_items"));
            // Totals are the sums of the items, paid orders have a payment time
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM orders o WHERE o.total <> (SELECT SUM(i.quantity"
                    + " * i.unit_price) FROM order_items i WHERE i.order_id = o.id)"));
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM orders WHERE status IN ('NEW', 'CANCELLED')"
                    + " AND paid_at IS NOT NULL"));
            assertEquals(0, count(statement, "SELECT COUNT(*) FROM orders WHERE status IN ('PAID', 'SHIPPED',"
                    + " 'DELIVERED') AND paid_at IS NULL"));
            assertTrue(count(statement, "SELECT COUNT(*) FROM customers WHERE vip") > 0);
            assertEquals(50, count(statement, "SELECT COUNT(DISTINCT email) FROM customers"));
            // Foreign keys, unique and regular indexes and comments for every feature of SqlJam
            try (ResultSet rs = connection.getMetaData().getImportedKeys(null, "PUBLIC", "ORDER_ITEMS")) {
                int foreignKeys = 0;
                while (rs.next()) {
                    foreignKeys++;
                }
                assertEquals(2, foreignKeys);
            }
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME"
                    + " = 'ORDERS' AND INDEX_NAME = 'IDX_ORDERS_STATUS'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME"
                    + " = 'PRODUCTS' AND REMARKS = 'Products for sale'"));
        }
    }

    @Test
    void createsOnlyAtFirstStart() throws Exception {
        Files.writeString(connectionsFile().toPath(), "[]");
        ExampleDatabase example = new ExampleDatabase(true, new File(dir, "example"));
        // A data source removed by the user is not created again
        assertFalse(example.createAtFirstStart(connectionsFile()).isPresent());
        assertFalse(new File(dir, "example").exists());
    }

    @Test
    void canBeDisabled() {
        ExampleDatabase example = new ExampleDatabase(false, new File(dir, "example"));
        assertFalse(example.createAtFirstStart(connectionsFile()).isPresent());
    }

    @Test
    void replacesExistingDatabase() throws Exception {
        ExampleDatabase example = new ExampleDatabase(true, new File(dir, "example"));
        example.create();
        ConnectionProfile profile = example.create();
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(300, count(statement, "SELECT COUNT(*) FROM orders"));
        }
    }

    @Test
    void startsWithoutExampleWhenItFails() throws Exception {
        // A file where the directory should be
        File blocked = new File(dir, "blocked");
        Files.writeString(blocked.toPath(), "file");
        ExampleDatabase example = new ExampleDatabase(true, blocked);
        assertFalse(example.createAtFirstStart(connectionsFile()).isPresent());
    }

    @Test
    void exportsExampleAsParquetPackage() throws Exception {
        ConnectionProfile profile = new ExampleDatabase(true, new File(dir, "example")).create();
        File packageDir = new File(dir, "package");
        TransferRequest request = new TransferRequest();
        request.setSource(profile);
        request.setSourceSchema("PUBLIC");
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(packageDir);
        request.setScriptDbType(DbType.POSTGRESQL);
        request.setDataFormat(DataFormat.PARQUET);
        new TransferService().transfer(request, ExportListener.NONE);
        ExportManifest manifest = ExportManifest.read(packageDir);
        assertEquals(ExportManifest.Status.COMPLETED, manifest.getStatus());
        assertEquals(5, manifest.getTables().size());
        assertEquals(986, manifest.getTables().stream().mapToLong(ExportManifest.TableEntry::getRows).sum());
    }

    @Test
    void createsOneIndexOfSameColumnsForOracle() throws Exception {
        ConnectionProfile profile = new ExampleDatabase(true, new File(dir, "example")).create();
        File packageDir = new File(dir, "oracle");
        TransferRequest request = new TransferRequest();
        request.setSource(profile);
        request.setSourceSchema("PUBLIC");
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(packageDir);
        request.setScriptDbType(DbType.ORACLE);
        new TransferService().transfer(request, ExportListener.NONE);
        // H2 indexes the foreign key of orders.customer_id next to idx_orders_customer, Oracle refuses a second one
        List<String> indexes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageDir.toPath())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                Files.readAllLines(file).stream().filter(line -> line.contains("CREATE INDEX")
                        || line.contains("CREATE UNIQUE INDEX")).forEach(indexes::add);
            }
        }
        assertEquals(1, indexes.stream().filter(line -> line.contains("(CUSTOMER_ID)")).count(), indexes.toString());
        assertEquals(1, indexes.stream().filter(line -> line.contains("(CATEGORY_ID)")).count(), indexes.toString());
        assertEquals(indexes.size(), indexes.stream().distinct().count(), indexes.toString());
    }

    @Test
    void repairsDeletedDatabaseFile() throws Exception {
        ExampleDatabase example = new ExampleDatabase(true, new File(dir, "example"));
        ConnectionProfile profile = example.create();
        assertEquals(ExampleDatabase.PROFILE_ID, profile.getId());
        assertTrue(ExampleDatabase.isExample(profile));
        // The database file is there, nothing to repair
        assertFalse(example.repairAtStart(List.of(profile)).isPresent());
        // The user deleted the file, H2 would open an empty database
        assertTrue(new File(profile.getDatabase() + ".mv.db").delete());
        assertTrue(ExampleDatabase.isDatabaseMissing(profile));
        Optional<ConnectionProfile> repaired = example.repairAtStart(List.of(profile));
        assertTrue(repaired.isPresent());
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(30, count(statement, "SELECT COUNT(*) FROM products"));
        }
        // Other data sources and a disabled example are not repaired
        ConnectionProfile other = new ConnectionProfile();
        other.setDbType(DbType.H2);
        other.setDatabase(new File(dir, "missing").getAbsolutePath());
        assertFalse(example.repairAtStart(List.of(other)).isPresent());
        assertTrue(new File(profile.getDatabase() + ".mv.db").delete());
        assertFalse(new ExampleDatabase(false, new File(dir, "example")).repairAtStart(List.of(profile))
                .isPresent());
        assertFalse(example.repairAtStart(List.of()).isPresent());
    }
}
