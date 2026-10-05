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
package com.github.sqljam.sqlserver;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.it.ItRunner;
import com.github.sqljam.it.ItVerifier;

/**
 * @Description: SQLServerCompatibilityIT runs sql of SQL Server 2008 (no sequences, ROW_NUMBER pagination) on the
 *               current SQL Server
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class SQLServerCompatibilityIT {

    @Test
    void importIntoSqlServer2008() throws Exception {
        assumeTrue(ItDatabase.POSTGRESQL.isAvailable() && ItDatabase.SQLSERVER.isAvailable());
        ItRunner.importTables(ItDatabase.POSTGRESQL, ItDatabase.SQLSERVER,
                importer -> importer.setTargetVersion(10, 50));
        try (Connection connection = ItDatabase.SQLSERVER.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.POSTGRESQL, ItDatabase.SQLSERVER, connection);
            verifier.verifyCounts();
            verifier.verifyValues();
            verifier.verifyConstraints();
            verifier.verifyComments();
            verifier.verifyIdentity();
        }
    }

    @Test
    void readFromSqlServer2008() throws Exception {
        assumeTrue(ItDatabase.SQLSERVER.isAvailable());
        ItRunner.importTables(ItDatabase.SQLSERVER, ItDatabase.H2, importer -> {
            importer.getExportConfiguration().setSourceDialect(DbType.SQLSERVER.createDialect(10, 50));
        });
        try (Connection connection = ItDatabase.H2.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.SQLSERVER, ItDatabase.H2, connection);
            verifier.verifyCounts();
            verifier.verifyValues();
        }
    }
}
