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
package com.github.sqljam.vdb.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbExportResult;
import com.github.sqljam.vdb.VdbExporter;
import com.github.sqljam.vdb.VdbFieldMapping;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbMappingIT imports the Qdrant fixture with the fields which the user mapped: a renamed payload
 *               field, a field with a type written as it is, and a skipped field. The rows are bound to the mapped
 *               columns, the vectors are searchable as before.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbMappingIT {

    static final VdbServer SERVER = VdbServer.QDRANT;
    static final ItDatabase TARGET = ItDatabase.DUCKDB;
    static final String TABLE = "sjvt_mapped";

    @BeforeAll
    static void createFixture() throws Exception {
        if (SERVER.isAvailable()) {
            SERVER.createFixture();
        }
    }

    @AfterAll
    static void dropFixture() throws Exception {
        if (SERVER.isAvailable()) {
            SERVER.dropFixture();
        }
        if (TARGET.isAvailable()) {
            try (Connection connection = TARGET.getTargetConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS main." + TABLE);
            }
        }
    }

    private static List<String> values(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = TARGET.getTargetConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    @Test
    void importsMappedFields() throws Exception {
        assumeTrue(SERVER.isAvailable() && TARGET.isAvailable());
        VdbExportConfiguration configuration = new VdbExportConfiguration();
        configuration.setTargetSchema(VdbImportIT.getSchema(TARGET));
        configuration.addCollection(SERVER.getCollection(), TABLE);
        configuration.setTableRecreated(true);
        configuration.getMappings().get(0).getFields().put("title", new VdbFieldMapping("headline", null, false));
        configuration.getMappings().get(0).getFields().put("n", new VdbFieldMapping(null, "BIGINT", false));
        configuration.getMappings().get(0).getFields().put("tags", VdbFieldMapping.skip());
        VdbExportResult result;
        try (VdbClient client = SERVER.createClient()) {
            result = new VdbExporter(client, VdbImportIT.dataSource(TARGET), configuration).export();
        }
        assertEquals(VdbServer.RECORDS, result.getWritten());
        String table;
        try (Connection connection = TARGET.getTargetConnection()) {
            table = VdbDialect.of(connection).getQualifiedTableName("main", TABLE);
        }
        List<String> columns = values("SELECT column_name FROM information_schema.columns WHERE table_name = '" + TABLE
                + "'");
        assertTrue(columns.contains("headline"), columns.toString());
        assertFalse(columns.contains("title") || columns.contains("tags"), columns.toString());
        assertEquals(List.of("BIGINT"), values("SELECT data_type FROM information_schema.columns WHERE table_name = '"
                + TABLE + "' AND column_name = 'n'"));
        // Each row is bound to its mapped column
        assertEquals(VdbServer.getDocuments().get(1).title, values("SELECT headline FROM " + table + " WHERE n = 2")
                .get(0));
        assertEquals(List.of(String.valueOf(VdbServer.RECORDS)), values("SELECT COUNT(*) FROM " + table
                + " WHERE headline IS NOT NULL"));
    }
}
