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
package com.github.sqljam.impexp.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.spannm.jackcess.Column;
import io.github.spannm.jackcess.DataType;
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DatabaseBuilder;
import io.github.spannm.jackcess.PropertyMap;
import io.github.spannm.jackcess.Table;

/**
 * @Description: AccessMetaDataOperationsTest
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class AccessMetaDataOperationsTest {

    @TempDir
    File dir;

    @Test
    void defaults() {
        assertNull(AccessMetaDataOperations.normalizeDefault(null, true));
        assertNull(AccessMetaDataOperations.normalizeDefault(" ", true));
        assertEquals("'n/a'", AccessMetaDataOperations.normalizeDefault("\"n/a\"", true));
        assertEquals("'say \"hi\" it''s'", AccessMetaDataOperations.normalizeDefault("\"say \"\"hi\"\" it's\"",
                true));
        assertEquals("'x'", AccessMetaDataOperations.normalizeDefault("'x'", true));
        assertEquals("Now()", AccessMetaDataOperations.normalizeDefault("=Now()", false));
        assertEquals("CURRENT_DATE", AccessMetaDataOperations.normalizeDefault("=Date()", false));
        // Written by UCanAccess without quotes
        assertEquals("'n/a'", AccessMetaDataOperations.normalizeDefault("n/a", true));
        assertEquals("'it''s'", AccessMetaDataOperations.normalizeDefault("it's", true));
        assertEquals("UCase('a')", AccessMetaDataOperations.normalizeDefault("UCase('a')", true));
        assertEquals("0", AccessMetaDataOperations.normalizeDefault("0", false));
    }

    @Test
    void columnsOfAccessFile() throws IOException {
        try (Database database = DatabaseBuilder.create(Database.FileFormat.V2016, new File(dir, "types.accdb"))) {
            Table table = DatabaseBuilder.newTable("t")
                    .addColumn(DatabaseBuilder.newColumn("c_counter", DataType.LONG).withAutoNumber(true))
                    .addColumn(DatabaseBuilder.newColumn("c_yesno", DataType.BOOLEAN))
                    .addColumn(DatabaseBuilder.newColumn("c_byte", DataType.BYTE))
                    .addColumn(DatabaseBuilder.newColumn("c_int", DataType.INT))
                    .addColumn(DatabaseBuilder.newColumn("c_bigint", DataType.BIG_INT))
                    .addColumn(DatabaseBuilder.newColumn("c_money", DataType.MONEY))
                    .addColumn(DatabaseBuilder.newColumn("c_float", DataType.FLOAT))
                    .addColumn(DatabaseBuilder.newColumn("c_double", DataType.DOUBLE))
                    .addColumn(DatabaseBuilder.newColumn("c_numeric", DataType.NUMERIC).withPrecision(12)
                            .withScale(3))
                    .addColumn(DatabaseBuilder.newColumn("c_date", DataType.SHORT_DATE_TIME))
                    .addColumn(DatabaseBuilder.newColumn("c_text", DataType.TEXT).withLengthInUnits(40)
                            .withProperty(PropertyMap.DESCRIPTION_PROP, "Name of it"))
                    .addColumn(DatabaseBuilder.newColumn("c_memo", DataType.MEMO))
                    .addColumn(DatabaseBuilder.newColumn("c_binary", DataType.BINARY).withLength(16))
                    .addColumn(DatabaseBuilder.newColumn("c_ole", DataType.OLE))
                    .addColumn(DatabaseBuilder.newColumn("c_guid", DataType.GUID).withAutoNumber(true))
                    .putProperty(PropertyMap.DESCRIPTION_PROP, "Types")
                    .toTable(database);
            Map<String, String> types = new LinkedHashMap<>();
            for (Column column : table.getColumns()) {
                Map<String, Object> info = new HashMap<>();
                info.put("COLUMN_DEF", "\"x\"");
                AccessMetaDataOperations.normalizeColumn(info, column);
                types.put(column.getName(), info.get("TYPE_NAME") + "(" + info.get("COLUMN_SIZE") + ","
                        + info.get("DECIMAL_DIGITS") + ") " + info.get("DATA_TYPE") + " " + info.get("IS_AUTOINCREMENT")
                        + (info.get("REMARKS") != null ? " " + info.get("REMARKS") : ""));
                if ("c_yesno".equals(column.getName())) {
                    assertEquals("NO", info.get("IS_NULLABLE"));
                }
                if ("c_counter".equals(column.getName())) {
                    assertNull(info.get("COLUMN_DEF"));
                } else {
                    assertEquals("'x'", info.get("COLUMN_DEF"));
                }
            }
            assertEquals("INTEGER(10,0) " + Types.INTEGER + " YES", types.get("c_counter"));
            assertEquals("BOOLEAN(1,0) " + Types.BOOLEAN + " NO", types.get("c_yesno"));
            assertEquals("SMALLINT(3,0) " + Types.SMALLINT + " NO", types.get("c_byte"));
            assertEquals("SMALLINT(5,0) " + Types.SMALLINT + " NO", types.get("c_int"));
            assertEquals("BIGINT(19,0) " + Types.BIGINT + " NO", types.get("c_bigint"));
            assertEquals("DECIMAL(19,4) " + Types.DECIMAL + " NO", types.get("c_money"));
            assertEquals("REAL(7,0) " + Types.REAL + " NO", types.get("c_float"));
            assertEquals("DOUBLE(15,0) " + Types.DOUBLE + " NO", types.get("c_double"));
            assertEquals("DECIMAL(12,3) " + Types.DECIMAL + " NO", types.get("c_numeric"));
            assertEquals("TIMESTAMP(23,3) " + Types.TIMESTAMP + " NO", types.get("c_date"));
            assertEquals("VARCHAR(40,0) " + Types.VARCHAR + " NO Name of it", types.get("c_text"));
            assertEquals("CLOB(" + Integer.MAX_VALUE + ",0) " + Types.CLOB + " NO", types.get("c_memo"));
            assertEquals("VARBINARY(16,0) " + Types.VARBINARY + " NO", types.get("c_binary"));
            assertEquals("BLOB(" + Integer.MAX_VALUE + ",0) " + Types.BLOB + " NO", types.get("c_ole"));
            // Replication ids are not identities
            assertEquals("VARCHAR(38,0) " + Types.VARCHAR + " NO", types.get("c_guid"));
            assertEquals("Types", AccessMetaDataOperations.getDescription(table::getProperties));
        }
    }

    @Test
    void descriptionsAndConnections() throws SQLException {
        assertNull(AccessMetaDataOperations.getDescription(() -> {
            throw new IOException("broken");
        }));
        assertNull(AccessMetaDataOperations.getDescription(() -> null));
        assertNull(AccessMetaDataOperations.getDatabase(null));
        assertNull(AccessMetaDataOperations.getDatabase(connection(false)));
        assertNull(AccessMetaDataOperations.getDatabase(connection(true)));
        AccessMetaDataOperations operations = new AccessMetaDataOperations();
        assertEquals(1, operations.getCatalogInfos(null).size());
        assertEquals("", operations.getCatalogInfos(null).get(0).get("TABLE_CAT"));
        assertEquals(0, operations.getSchemaInfos(null, "").size());
        assertEquals(0, operations.getSequenceInfos(null, "", null).size());
    }

    /**
     * Connection of another driver, or a pool which fails to unwrap
     */
    private static Connection connection(boolean failing) {
        return (Connection) Proxy.newProxyInstance(AccessMetaDataOperationsTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("isWrapperFor".equals(method.getName())) {
                        if (failing) {
                            throw new SQLException("closed");
                        }
                        return false;
                    }
                    return null;
                });
    }
}
