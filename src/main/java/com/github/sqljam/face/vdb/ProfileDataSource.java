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
package com.github.sqljam.face.vdb;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;

/**
 * @Description: ProfileDataSource gives connections of a data source of the jdbc line, the vdb line writes by
 *               plain JDBC
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
final class ProfileDataSource implements DataSource {

    private final ConnectionProfile profile;

    ProfileDataSource(ConnectionProfile profile) {
        this.profile = profile;
    }

    @Override
    public Connection getConnection() throws SQLException {
        String url = profile.getJdbcUrl();
        return StringUtils.isBlank(profile.getUsername()) ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, profile.getUsername(), profile.getPassword());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(profile.getJdbcUrl(), username, password);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // Not used
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // DriverManager has its own timeout
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException("Not a wrapper of " + type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return false;
    }
}
