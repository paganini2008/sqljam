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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * @Description: SharedConnectionFactory hands out an existing connection which stays open: closing a connection
 *               of this factory does nothing, the owner of the connection closes it
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SharedConnectionFactory implements ConnectionFactory {

    private final Connection connection;
    private final Connection proxy;

    public SharedConnectionFactory(Connection connection) {
        this.connection = connection;
        this.proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (target, method, args) -> {
                    switch (method.getName()) {
                        case "close":
                            return null;
                        case "isClosed":
                            return connection.isClosed();
                        case "unwrap":
                            if (args != null && args.length == 1 && args[0] == Connection.class) {
                                return connection;
                            }
                            break;
                        default:
                            break;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Override
    public Connection getConnection() {
        return proxy;
    }

    /**
     * The shared connection itself
     */
    public Connection getSharedConnection() {
        return connection;
    }
}
