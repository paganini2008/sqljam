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
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.sqljam.face.model.ColumnInfo;
import com.github.sqljam.face.model.DataPage;
import com.github.sqljam.face.model.ForeignKeyInfo;
import com.github.sqljam.face.model.IndexInfo;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.face.service.TransferService;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.ScriptImporter;

/**
 * @Description: AbstractSessionIT browses fixture tables by DatabaseSession and transfers them by TransferService
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public abstract class AbstractSessionIT {

    protected final ItDatabase source;

    protected AbstractSessionIT(ItDatabase source) {
        this.source = source;
    }

    @BeforeEach
    void loadFixture() throws Exception {
        assumeTrue(source.isAvailable());
        source.loadFixture();
    }

    private String findTable(DatabaseSession session, String name) throws Exception {
        return session.getTables(source.getSourceCatalog(), source.getSourceSchema()).stream()
                .map(TableInfo::getName).filter(table -> table.equalsIgnoreCase(source.getPrefix() + name))
                .findFirst().orElseThrow();
    }

    @Test
    void browse() throws Exception {
        assertTrue(DatabaseSession.testConnection(source.getSourceProfile()).length() > 0);
        try (DatabaseSession session = new DatabaseSession(source.getSourceProfile())) {
            assertNotNull(session.getDatabaseProduct());
            assertEquals(source.getDbType(), session.getDbType());
            List<String> catalogs = session.getCatalogs();
            if (source.getDbType().isCatalogSupported() && source.getSourceCatalog() != null) {
                assertTrue(catalogs.contains(source.getSourceCatalog()), "Catalogs: " + catalogs);
                assertTrue(DatabaseSession.getDatabases(source.getSourceProfile()).contains(
                        source.getSourceCatalog()));
            } else if (!source.getDbType().isCatalogSupported()) {
                assertTrue(catalogs.isEmpty());
                assertTrue(DatabaseSession.getDatabases(source.getSourceProfile()).isEmpty());
            } else {
                assertEquals(1, catalogs.size());
            }
            List<String> schemas = session.getSchemas(source.getSourceCatalog());
            if (source.getSourceSchema() != null) {
                assertTrue(schemas.contains(source.getSourceSchema()), "Schemas: " + schemas);
            }
            String catalog = source.getSourceCatalog();
            String schema = source.getSourceSchema();
            String emp = findTable(session, "emp");
            List<ColumnInfo> columns = session.getColumns(catalog, schema, emp);
            assertTrue(columns.size() >= 11);
            assertTrue(columns.get(0).isPrimaryKey(), "id is primary key");
            assertTrue(columns.stream().anyMatch(column -> "employee name".equalsIgnoreCase(
                    column.getRemarks())) || source == ItDatabase.SQLITE);
            List<IndexInfo> indexes = session.getIndexes(catalog, schema, emp);
            assertTrue(indexes.stream().anyMatch(index -> index.isUnique() && index.getColumns().stream()
                    .anyMatch("email"::equalsIgnoreCase)), "Unique index of email: " + indexes.size());
            List<ForeignKeyInfo> foreignKeys = session.getForeignKeys(catalog, schema, emp);
            assertEquals(1, foreignKeys.size());
            assertEquals((source.getPrefix() + "dept").toLowerCase(Locale.ENGLISH),
                    foreignKeys.get(0).getReferencedTable().toLowerCase(Locale.ENGLISH));
            assertEquals(ItDatabase.EMP_COUNT, session.countRows(catalog, schema, emp));

            DataPage page = session.getData(catalog, schema, emp, 3, 100);
            assertEquals(50, page.getRows().size());
            assertEquals(3, page.getTotalPages());
            assertEquals("201", page.getRows().get(0).get(0));
            int photoIndex = page.getColumns().stream().map(column -> column.toLowerCase(Locale.ENGLISH))
                    .collect(Collectors.toList()).indexOf("photo");
            assertTrue(page.getRows().get(9).get(photoIndex).startsWith("(466 bytes) 0x"));

            String ddl = session.getDdl(catalog, schema, emp, null);
            assertTrue(ddl.toUpperCase(Locale.ENGLISH).contains("CREATE TABLE"), ddl);
            assertTrue(ddl.toLowerCase(Locale.ENGLISH).contains((source.getPrefix() + "emp")), ddl);
            DbType otherType = source.getDbType() == DbType.MYSQL ? DbType.POSTGRESQL : DbType.MYSQL;
            String otherDdl = session.getDdl(catalog, schema, emp, otherType);
            assertTrue(otherDdl.contains("CREATE TABLE"), otherDdl);
        }
    }

    /**
     * Exports selected tables to an export directory and imports the directory into a file database
     */
    @Test
    void transferByScripts() throws Exception {
        ItDatabase target = source == ItDatabase.H2 ? ItDatabase.SQLITE : ItDatabase.H2;
        File dir = new File(ItDatabase.DATA_DIR, "transfer/" + source);
        FileUtils.deleteQuietly(dir);
        TransferRequest request = createRequest();
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(dir);
        request.setScriptDbType(target.getDbType());
        request.setDataFileStrategy(DataFileStrategy.FILE_PER_TABLE);
        CollectingListener listener = new CollectingListener();
        TransferService transferService = new TransferService();
        transferService.transfer(request, listener);
        assertEquals(List.of(), listener.getErrors());

        ScriptImporter importer = transferService.importScripts(target.getTargetProfile(), null,
                target.getTargetSchema(), dir, true, listener);
        assertEquals(List.of(), listener.getErrors());
        assertTrue(importer.getExecutedCount() > 0);
        verifyCounts(target);
    }

    /**
     * Imports selected tables into another database directly
     */
    @Test
    void transferToDatabase() throws Exception {
        ItDatabase target = source == ItDatabase.SQLITE ? ItDatabase.H2 : ItDatabase.SQLITE;
        TransferRequest request = createRequest();
        request.setTarget(TransferRequest.Target.DATABASE);
        request.setTargetProfile(target.getTargetProfile());
        request.setTargetSchema(target.getTargetSchema());
        CollectingListener listener = new CollectingListener();
        new TransferService().transfer(request, listener);
        assertEquals(List.of(), listener.getErrors());
        verifyCounts(target);
    }

    private TransferRequest createRequest() throws Exception {
        TransferRequest request = new TransferRequest();
        request.setSource(source.getSourceProfile());
        request.setSourceCatalog(source.getSourceCatalog());
        request.setSourceSchema(source.getSourceSchema());
        request.setExportMode(ExportMode.DDL_DATA);
        try (DatabaseSession session = new DatabaseSession(source.getSourceProfile())) {
            request.getTables().add(findTable(session, "dept"));
            request.getTables().add(findTable(session, "emp"));
        }
        request.setPageSize(64);
        return request;
    }

    private void verifyCounts(ItDatabase target) throws Exception {
        try (Connection connection = target.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(source, target, connection);
            assertEquals(3, verifier.count("dept"));
            assertEquals(ItDatabase.EMP_COUNT, verifier.count("emp"));
            verifier.verifyValues();
        }
        assertFalse(source.getPrefix().isEmpty());
    }
}
