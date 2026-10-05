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
package com.github.sqljam.oracle;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;

import org.junit.jupiter.api.Test;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.it.ItRunner;
import com.github.sqljam.it.ItVerifier;

/**
 * @Description: OracleCompatibilityIT runs sql of Oracle 11g (sequence and trigger identities, ROWNUM pagination,
 *               30 bytes identifiers) on the current Oracle server
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class OracleCompatibilityIT {

    @Test
    void importIntoOracle11g() throws Exception {
        assumeTrue(ItDatabase.H2.isAvailable() && ItDatabase.ORACLE.isAvailable());
        ItRunner.importTables(ItDatabase.H2, ItDatabase.ORACLE, importer -> importer.setTargetVersion(11, 2));
        try (Connection connection = ItDatabase.ORACLE.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.H2, ItDatabase.ORACLE, connection);
            verifier.verifyCounts();
            verifier.verifyValues();
            verifier.verifyConstraints();
            verifier.verifyComments();
            // Identity by sequence and trigger
            verifier.verifyIdentity();
            verifier.verifySequences();
        }
    }

    @Test
    void readFromOracle11g() throws Exception {
        assumeTrue(ItDatabase.ORACLE.isAvailable());
        ItRunner.importTables(ItDatabase.ORACLE, ItDatabase.H2, importer -> {
            importer.getExportConfiguration().setSourceDialect(DbType.ORACLE.createDialect(11, 2));
        });
        try (Connection connection = ItDatabase.H2.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.ORACLE, ItDatabase.H2, connection);
            verifier.verifyCounts();
            verifier.verifyValues();
        }
    }
}
