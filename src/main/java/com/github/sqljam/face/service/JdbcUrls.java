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
package com.github.sqljam.face.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;

/**
 * @Description: JdbcUrls reads the database type, host, port and database of a jdbc url, and builds the url of them,
 *               so that the fields of a data source and its url follow each other like DBeaver does
 * @Author: Fred Feng
 * @Date: 18/10/2026
 * @Version 1.0.0
 */
public final class JdbcUrls {

    /**
     * jdbc:mysql://host:port/db?..., jdbc:mariadb, jdbc:postgresql, jdbc:db2 (db:props;) and ClickHouse alike
     */
    private static final Pattern SERVER = Pattern.compile(
            "(?i)^jdbc:(?:mysql|mariadb|postgresql|db2|clickhouse|ch):(?:https?:)?//([^/:?;\\\\]+)(?::(\\d+))?"
                    + "(?:/([^?;:]*))?.*$");
    /**
     * jdbc:oracle:thin:@//host:port/service, @host:port/service and @host:port:SID
     */
    private static final Pattern ORACLE = Pattern.compile(
            "(?i)^jdbc:oracle:thin:@(?://)?([^/:]+)(?::(\\d+))?[/:]([^?]+?)(?:\\?.*)?$");
    /**
     * jdbc:sqlserver://host\instance:port;databaseName=db;...
     */
    private static final Pattern SQLSERVER = Pattern.compile(
            "(?i)^jdbc:sqlserver://([^;:\\\\]+)(?:\\\\[^;:]+)?(?::(\\d+))?(;.*)?$");
    private static final Pattern SQLSERVER_DATABASE = Pattern.compile("(?i);(?:databaseName|database)=([^;]+)");
    private static final Pattern H2_SERVER = Pattern.compile("(?i)^jdbc:h2:(?:tcp|ssl)://([^/:]+)(?::(\\d+))?/(.+)$");

    private JdbcUrls() {
    }

    /**
     * Database type, host, port and database of the url, null if it is not a url of a supported database
     */
    public static ConnectionProfile parse(String url) {
        DbType dbType = DbType.forUrl(StringUtils.trim(url));
        if (dbType == null) {
            return null;
        }
        String text = url.trim();
        ConnectionProfile profile = new ConnectionProfile();
        profile.setDbType(dbType);
        profile.setPort(dbType.getDefaultPort());
        Matcher matcher;
        switch (dbType) {
            case ORACLE:
                matcher = ORACLE.matcher(text);
                // A TNS descriptor, e.g. @(DESCRIPTION=...), is kept as the url only
                if (!matcher.matches()) {
                    return profile;
                }
                return fill(profile, matcher.group(1), matcher.group(2), matcher.group(3));
            case SQLSERVER:
                matcher = SQLSERVER.matcher(text);
                if (!matcher.matches()) {
                    return profile;
                }
                Matcher database = SQLSERVER_DATABASE.matcher(StringUtils.defaultString(matcher.group(3)));
                return fill(profile, matcher.group(1), matcher.group(2), database.find() ? database.group(1) : null);
            case H2:
                matcher = H2_SERVER.matcher(text);
                if (matcher.matches()) {
                    return fill(profile, matcher.group(1), matcher.group(2), matcher.group(3));
                }
                // jdbc:h2:file:path, jdbc:h2:path and jdbc:h2:mem:name, options follow a semicolon
                String path = StringUtils.substringBefore(text.substring("jdbc:h2:".length()), ";");
                if (path.toLowerCase(Locale.ENGLISH).startsWith("file:")) {
                    path = path.substring("file:".length());
                }
                return fill(profile, null, null, path);
            case SQLITE:
                return fill(profile, null, null, StringUtils.substringBefore(text.substring("jdbc:sqlite:".length()),
                        "?"));
            case DUCKDB:
                return fill(profile, null, null, StringUtils.substringBefore(text.substring("jdbc:duckdb:".length()),
                        "?"));
            default:
                matcher = SERVER.matcher(text);
                if (!matcher.matches()) {
                    return profile;
                }
                return fill(profile, matcher.group(1), matcher.group(2), matcher.group(3));
        }
    }

    private static ConnectionProfile fill(ConnectionProfile profile, String host, String port, String database) {
        profile.setHostname(StringUtils.trimToNull(host));
        if (StringUtils.isNotBlank(port)) {
            profile.setPort(Integer.parseInt(port));
        } else if (profile.getHostname() == null) {
            // Files have no port
            profile.setPort(0);
        }
        profile.setDatabase(StringUtils.trimToNull(database));
        return profile;
    }

    /**
     * Url of the fields, while they are being entered: a missing host or a required database is left empty
     */
    public static String build(DbType dbType, String host, int port, String database) {
        if (dbType == null) {
            return "";
        }
        String hostname = StringUtils.trimToNull(host);
        boolean file = dbType.isFileBased() && hostname == null;
        if (file && StringUtils.isBlank(database)) {
            return "";
        }
        // A placeholder passes the checks of required databases, it is removed from the url
        String placeholder = "\u0000";
        String url = dbType.getUrl(file ? null : StringUtils.defaultString(hostname), port,
                StringUtils.isBlank(database) ? placeholder : database.trim());
        return url.replace(placeholder, "");
    }
}
