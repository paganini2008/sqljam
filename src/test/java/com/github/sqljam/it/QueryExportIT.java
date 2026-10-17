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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ImportExporter;

/**
 * @Description: QueryExportIT exports free queries of every database like the SQL editor does: the table of all
 *               column types into the same database, and a join with expressions as a CSV package which is loaded
 *               into H2. Rows are read by the cursor of each driver (MySQL streaming, PostgreSQL in a transaction)
 *               and the tables are created from the columns of the queries.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class QueryExportIT {

    private static String ref(ItDatabase db, Connection connection, String name) throws Exception {
        return new ItVerifier(db, db, connection, db.getSourceSchema()).ref(name);
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Rows of all column types are copied by a query into a new table of the target schema of the same database
     */
    @ParameterizedTest(name = "query of all types of {0}")
    @EnumSource(ItDatabase.class)
    void exportsQueryOfAllTypes(ItDatabase db) throws Exception {
        assumeTrue(AbstractImportIT.isTargetSelected(db));
        assumeTrue(db.isAvailable());
        db.loadFixture();
        String query;
        long rows;
        try (Connection source = db.getSourceConnection()) {
            // Types which a table export converts (XMLTYPE, VECTOR, Map, Tuple ...) are selected again by them
            query = "SELECT * FROM " + ref(db, source, "types");
            rows = count(source, "SELECT COUNT(*) FROM " + ref(db, source, "types"));
        }
        String table = db.getPrefix() + "query_types";
        ImportExporter importExporter = new ImportExporter();
        db.configureSource(importExporter.getExportConfiguration());
        importExporter.getExportConfiguration().getQueries().put(table, query);
        importExporter.getExportConfiguration().setPageSize(2);
        db.configureTarget(importExporter.getImportConfiguration());
        CollectingListener listener = new CollectingListener();
        importExporter.setExportListener(listener);
        try {
            importExporter.exportDdlAndData();
            assertEquals(List.of(), listener.getErrors(), db.toString());
            try (Connection target = db.getTargetConnection()) {
                ItVerifier verifier = new ItVerifier(db, db, target);
                assertEquals(rows, verifier.count("query_types"), db.toString());
            }
        } finally {
            // Databases of one schema (MariaDB, Oracle) have the table next to the fixture of other tests
            try (Connection target = db.getTargetConnection(); Statement statement = target.createStatement()) {
                statement.execute("DROP TABLE " + new ItVerifier(db, db, target).ref("query_types"));
            } catch (Exception | AssertionError e) {
                // The table was not created
            }
        }
    }

    /**
     * A join with aggregates and names of the same columns is written as a CSV package and loaded into H2
     */
    @ParameterizedTest(name = "query of {0} as CSV package")
    @EnumSource(ItDatabase.class)
    void exportsJoinAsCsvPackage(ItDatabase db) throws Exception {
        assumeTrue(AbstractImportIT.isTargetSelected(db));
        assumeTrue(db.isAvailable());
        db.loadFixture();
        String query;
        long rows;
        try (Connection source = db.getSourceConnection()) {
            String emp = ref(db, source, "emp");
            String dept = ref(db, source, "dept");
            query = "SELECT d.id, d.name, COUNT(e.id) AS employees, SUM(e.salary) AS salaries, MAX(e.name) AS last_name"
                    + " FROM " + dept + " d LEFT JOIN " + emp + " e ON e.dept_id = d.id GROUP BY d.id, d.name";
            rows = count(source, "SELECT COUNT(*) FROM " + dept);
        }
        File dir = new File(ItDatabase.DATA_DIR, "query/" + db);
        FileUtils.deleteQuietly(dir);
        CsvExporter exporter = new CsvExporter(dir);
        db.configureSource(exporter.getConfiguration());
        exporter.getConfiguration().getQueries().put("DEPT_SUMMARY", query);
        exporter.setTargetDbType(DbType.H2);
        CollectingListener listener = new CollectingListener();
        exporter.setExportListener(listener);
        exporter.exportDdlAndData();
        assertEquals(List.of(), listener.getErrors(), db.toString());
        String url = "jdbc:h2:mem:qx" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            AbstractFileImporter importer = AbstractFileImporter.forDirectory(dir, connection, DbType.H2);
            CollectingListener importListener = new CollectingListener();
            importer.setExportListener(importListener);
            importer.importDirectory(dir);
            assertEquals(List.of(), importListener.getErrors(), db.toString());
            assertEquals(rows, count(connection, "SELECT COUNT(*) FROM DEPT_SUMMARY"), db.toString());
            assertEquals(ItDatabase.EMP_COUNT, count(connection, "SELECT SUM(EMPLOYEES) FROM DEPT_SUMMARY"),
                    db.toString());
        }
    }
}
