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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.utils.SshSettings;
import com.github.sqljam.utils.SshTunnels;
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
    /**
     * SSH server through which the host and port are reached, none if it is null or disabled
     */
    private SshSettings ssh;

    /**
     * Host and port of a custom url, e.g. //host:5432/ or @host:1521
     */
    static final Pattern URL_ADDRESS = Pattern.compile("(?<=//|@)([\\w.-]+|\\[[^\\]]+\\]):(\\d+)");

    /**
     * Jdbc url of the database, not saved: it is made of the fields, and an SSH tunnel is opened for it
     */
    @JsonIgnore
    public String getJdbcUrl() {
        return getJdbcUrl(database);
    }

    /**
     * Jdbc url connecting to the given database (catalog), PostgreSQL connects to one database per connection
     */
    public String getJdbcUrl(String catalog) {
        if (dbType != null && dbType.isFileBased()) {
            // The catalog of a database file is the file itself
            catalog = null;
        }
        boolean custom = StringUtils.isNotBlank(url) && (StringUtils.isBlank(catalog) || StringUtils.equals(catalog,
                database));
        if (isSshEnabled()) {
            return getTunnelUrl(custom, catalog);
        }
        if (custom) {
            return dbType != null ? dbType.normalizeUrl(url) : url;
        }
        return dbType.getUrl(hostname, port, StringUtils.defaultIfBlank(catalog, database));
    }

    /**
     * Whether the data source is reached through its SSH server, a database file never is
     */
    @JsonIgnore
    public boolean isSshEnabled() {
        return ssh != null && ssh.isEnabled() && (dbType == null || !dbType.isFileBased());
    }

    /**
     * Url of the local end of the SSH tunnel: the host and port, as the SSH server reaches them, are forwarded to
     * a local port. The tunnel is opened when the url is first asked for.
     */
    private String getTunnelUrl(boolean custom, String catalog) {
        String targetHost = hostname;
        int targetPort = port > 0 ? port : dbType.getDefaultPort();
        Matcher matcher = URL_ADDRESS.matcher(StringUtils.defaultString(url));
        if (StringUtils.isNotBlank(url) && matcher.find() && (custom || StringUtils.isBlank(hostname))) {
            targetHost = matcher.group(1);
            targetPort = Integer.parseInt(matcher.group(2));
        }
        if (StringUtils.isBlank(targetHost)) {
            throw new IllegalStateException("No host of " + name + " to reach through SSH");
        }
        int localPort = SshTunnels.open(id, ssh, targetHost.replaceAll("^\\[|\\]$", ""), targetPort);
        if (custom) {
            String local = matcher.find(0) ? matcher.replaceFirst(SshTunnels.LOCALHOST_ADDRESS + ":" + localPort)
                    : url;
            return dbType != null ? dbType.normalizeUrl(local) : local;
        }
        return dbType.getUrl(SshTunnels.LOCALHOST_ADDRESS, localPort, StringUtils.defaultIfBlank(catalog, database));
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
        copy.setSsh(ssh != null ? ssh.copy() : null);
        return copy;
    }
}
