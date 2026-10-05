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
package com.github.sqljam.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import com.github.sqljam.impexp.TransactionIsolationLevel;

/**
 * @Description: SimpleConnectionFactory opens a new connection by DriverManager each time
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public class SimpleConnectionFactory implements ConnectionFactory {

    private String driverClassName;
    private String url;
    private String user;
    private String password;
    private Boolean autoCommit;
    private TransactionIsolationLevel transactionIsolationLevel;

    public SimpleConnectionFactory() {
    }

    public SimpleConnectionFactory(String driverClassName, String url, String user, String password) {
        setDriverClassName(driverClassName);
        setUrl(url);
        setUser(user);
        setPassword(password);
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(driverClassName);
        }
        this.driverClassName = driverClassName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(Boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public TransactionIsolationLevel getTransactionIsolationLevel() {
        return transactionIsolationLevel;
    }

    public void setTransactionIsolationLevel(TransactionIsolationLevel transactionIsolationLevel) {
        this.transactionIsolationLevel = transactionIsolationLevel;
    }

    public Connection getConnection() throws SQLException {
        Connection connection = JdbcUtils.getConnection(url, user, password);
        if (autoCommit != null) {
            connection.setAutoCommit(autoCommit);
        }
        if (transactionIsolationLevel != null) {
            connection.setTransactionIsolation(transactionIsolationLevel.getLevel());
        }
        return connection;
    }

    @Override
    public void close(Connection connection) throws SQLException {
        JdbcUtils.close(connection);
    }
}