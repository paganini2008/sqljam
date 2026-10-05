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
package com.github.sqljam.face.model;

import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.impexp.DbType;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: ConnectionProfile is a saved database connection
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Getter
@Setter
@ToString(exclude = "password")
public class ConnectionProfile {

    private String id = UUID.randomUUID().toString();
    private String name;
    private DbType dbType;
    private String hostname;
    private int port;
    /**
     * Database name (MySQL/PostgreSQL/SQL Server), service name (Oracle) or file path (H2/SQLite)
     */
    private String database;
    private String username;
    private String password;
    /**
     * Custom jdbc url, overrides hostname, port and database
     */
    private String url;

    public String getJdbcUrl() {
        return getJdbcUrl(database);
    }

    /**
     * Jdbc url connecting to the given database (catalog), PostgreSQL connects to one database per connection
     */
    public String getJdbcUrl(String catalog) {
        if (StringUtils.isNotBlank(url) && (StringUtils.isBlank(catalog) || StringUtils.equals(catalog, database))) {
            return url;
        }
        return dbType.getUrl(hostname, port, StringUtils.defaultIfBlank(catalog, database));
    }

    public ConnectionProfile copy() {
        ConnectionProfile copy = new ConnectionProfile();
        copy.setId(id);
        copy.setName(name);
        copy.setDbType(dbType);
        copy.setHostname(hostname);
        copy.setPort(port);
        copy.setDatabase(database);
        copy.setUsername(username);
        copy.setPassword(password);
        copy.setUrl(url);
        return copy;
    }
}
