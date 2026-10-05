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
package com.github.sqljam.postgresql;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.it.ItRunner;
import com.github.sqljam.it.ItVerifier;

/**
 * @Description: PostgreSQLSchemaIT imports a whole schema, target schemas are created automatically
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class PostgreSQLSchemaIT {

    /**
     * Imports into a new schema of the same database
     */
    @Test
    void importIntoNewSchema() throws Exception {
        assumeTrue(ItDatabase.POSTGRESQL.isAvailable());
        try (Connection connection = ItDatabase.POSTGRESQL.getTargetConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS sjit_new CASCADE");
        }
        ItRunner.importTables(ItDatabase.POSTGRESQL, ItDatabase.POSTGRESQL,
                importer -> importer.getImportConfiguration().setTargetSchemaName("sjit_new"));
        try (Connection connection = ItDatabase.POSTGRESQL.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.POSTGRESQL, ItDatabase.POSTGRESQL, connection,
                    "sjit_new");
            verifier.verifyCounts();
            verifier.verifyConstraints();
            verifier.verifyIdentity();
            verifier.verifySequences();
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA sjit_new CASCADE");
            }
        }
    }

    /**
     * Without target schema, tables are imported into the schema with the same name as source schema
     */
    @Test
    void importSchemaIntoSqlServer() throws Exception {
        assumeTrue(ItDatabase.POSTGRESQL.isAvailable() && ItDatabase.SQLSERVER.isAvailable());
        try (Connection connection = ItDatabase.SQLSERVER.getTargetConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("IF SCHEMA_ID('sjit_src') IS NOT NULL BEGIN DECLARE @sql NVARCHAR(MAX) = N'';"
                    + " SELECT @sql += N'ALTER TABLE sjit_src.' + QUOTENAME(OBJECT_NAME(parent_object_id))"
                    + " + N' DROP CONSTRAINT ' + QUOTENAME(name) + N';' FROM sys.foreign_keys"
                    + " WHERE SCHEMA_NAME(schema_id) = 'sjit_src'; EXEC sp_executesql @sql;"
                    + " SET @sql = N''; SELECT @sql += N'DROP TABLE sjit_src.' + QUOTENAME(name) + N';'"
                    + " FROM sys.tables WHERE SCHEMA_NAME(schema_id) = 'sjit_src'; EXEC sp_executesql @sql;"
                    + " SET @sql = N''; SELECT @sql += N'DROP SEQUENCE sjit_src.' + QUOTENAME(name) + N';'"
                    + " FROM sys.sequences WHERE SCHEMA_NAME(schema_id) = 'sjit_src'; EXEC sp_executesql @sql;"
                    + " DROP SCHEMA sjit_src; END");
        }
        ItRunner.importTables(ItDatabase.POSTGRESQL, ItDatabase.SQLSERVER,
                importer -> importer.getImportConfiguration().setTargetSchemaName(null));
        try (Connection connection = ItDatabase.SQLSERVER.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.POSTGRESQL, ItDatabase.SQLSERVER, connection,
                    "sjit_src");
            verifier.verifyCounts();
            verifier.verifyConstraints();
            verifier.verifyIdentity();
            verifier.verifySequences();
        }
    }
}
