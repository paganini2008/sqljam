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
package com.github.sqljam.impexp;

import java.sql.Connection;
import java.sql.SQLException;

import com.github.sqljam.config.Config;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * @Description: HikariDataSourceConnectionFactory provides pooled connections by HikariCP, pool settings are in sqljam.properties
 * @Author: Fred Feng
 * @Date: 31/03/2023
 * @Version 1.0.0
 */
public class HikariDataSourceConnectionFactory implements ConnectionFactory {

    public HikariDataSourceConnectionFactory(String driverClassName, String jdbcUrl, String username, String password) {
        this.dataSource = createDefaultDataSource(driverClassName, jdbcUrl, username, password);
    }

    private final HikariDataSource dataSource;

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void destroy() {
        dataSource.close();
    }

    private HikariDataSource createDefaultDataSource(String driverClassName, String jdbcUrl, String username,
                                                     String password) {
        final HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClassName);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        Config appConfig = Config.getInstance();
        config.setMinimumIdle(appConfig.getInt("sqljam.pool.minimum-idle", 1));
        config.setMaximumPoolSize(appConfig.getInt("sqljam.pool.maximum-size", 10));
        config.setMaxLifetime(appConfig.getLong("sqljam.pool.max-lifetime", 30 * 60 * 1000));
        config.setIdleTimeout(appConfig.getLong("sqljam.pool.idle-timeout", 5 * 60 * 1000));
        config.setValidationTimeout(3000);
        config.setReadOnly(false);
        config.setAutoCommit(true);
        config.setConnectionTimeout(appConfig.getLong("sqljam.pool.connection-timeout", 30 * 1000));
        return new HikariDataSource(config);
    }
}