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
package com.github.sqljam.face.view;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;

/**
 * @Description: UiDatabase is an embedded H2 database for UI tests: an empty table, a table of one page and a table
 *               of several pages with NULL, long and binary values
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class UiDatabase {

    public static final String CATALOG = "UI";
    public static final String SCHEMA = "PUBLIC";
    public static final int PAGED_ROWS = 250;
    public static final int LONG_TEXT_LENGTH = 5000;

    private UiDatabase() {
    }

    public static ConnectionProfile create(File dir, String name) throws SQLException {
        File file = new File(dir, "ui");
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName(name);
        profile.setDbType(DbType.H2);
        profile.setDatabase(file.getAbsolutePath());
        profile.setUsername("sa");
        profile.setPassword("");
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS T_EMPTY (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            statement.execute("CREATE TABLE IF NOT EXISTS T_ONE (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            statement.execute("CREATE TABLE IF NOT EXISTS T_PAGED (ID INT PRIMARY KEY, NAME VARCHAR(100),"
                    + " NOTE CLOB, DATA BLOB, CREATED TIMESTAMP)");
            statement.execute("COMMENT ON TABLE T_PAGED IS 'Paged rows'");
            statement.execute("CREATE INDEX IF NOT EXISTS IDX_T_PAGED_NAME ON T_PAGED (NAME)");
            statement.execute("DELETE FROM T_ONE");
            statement.execute("DELETE FROM T_PAGED");
            for (int i = 1; i <= 5; i++) {
                statement.execute("INSERT INTO T_ONE VALUES (" + i + ", 'one " + i + "')");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO T_PAGED VALUES (?, ?, ?, ?, TIMESTAMP '2024-01-01 10:00:00')")) {
                for (int i = 1; i <= PAGED_ROWS; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, i == 2 ? null : "name " + i);
                    ps.setString(3, i == 1 ? "x".repeat(LONG_TEXT_LENGTH) : "note " + i);
                    ps.setBytes(4, i == 3 ? new byte[40] : null);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        return profile;
    }
}
