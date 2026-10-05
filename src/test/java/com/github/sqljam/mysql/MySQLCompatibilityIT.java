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
package com.github.sqljam.mysql;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;

import org.junit.jupiter.api.Test;
import com.github.sqljam.it.ItDatabase;
import com.github.sqljam.it.ItRunner;
import com.github.sqljam.it.ItVerifier;

/**
 * @Description: MySQLCompatibilityIT runs sql of MySQL 5.6 (no JSON type, no expression defaults, no generated
 *               columns) on the current MySQL server
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class MySQLCompatibilityIT {

    @Test
    void importIntoMySql56() throws Exception {
        assumeTrue(ItDatabase.POSTGRESQL.isAvailable() && ItDatabase.MYSQL.isAvailable());
        ItRunner.importTables(ItDatabase.POSTGRESQL, ItDatabase.MYSQL, importer -> importer.setTargetVersion(5, 6));
        try (Connection connection = ItDatabase.MYSQL.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.POSTGRESQL, ItDatabase.MYSQL, connection);
            verifier.verifyCounts();
            verifier.verifyValues();
            verifier.verifyConstraints();
            verifier.verifyIdentity();
        }
    }

    @Test
    void importMySqlIntoMySql56() throws Exception {
        assumeTrue(ItDatabase.MYSQL.isAvailable());
        ItRunner.importTables(ItDatabase.MYSQL, ItDatabase.MYSQL, importer -> importer.setTargetVersion(5, 6));
        try (Connection connection = ItDatabase.MYSQL.getTargetConnection()) {
            ItVerifier verifier = new ItVerifier(ItDatabase.MYSQL, ItDatabase.MYSQL, connection);
            verifier.verifyCounts();
            verifier.verifyValues();
            verifier.verifyIdentity();
        }
    }
}
