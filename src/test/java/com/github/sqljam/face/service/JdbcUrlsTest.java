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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;

/**
 * @Description: JdbcUrlsTest verifies the database type, host, port and database read from the urls of each database,
 *               default ports, files, options, and the urls built while the fields are being entered
 * @Author: Fred Feng
 * @Date: 18/10/2026
 * @Version 1.0.0
 */
class JdbcUrlsTest {

    private static String parts(String url) {
        ConnectionProfile profile = JdbcUrls.parse(url);
        return profile == null ? null : profile.getDbType().name() + " " + profile.getHostname() + " " + profile.getPort()
                + " " + profile.getDatabase();
    }

    @Test
    void readsServerUrls() {
        assertEquals("MYSQL db.example.com 3307 app", parts("jdbc:mysql://db.example.com:3307/app?useSSL=false"));
        assertEquals("MYSQL localhost 3306 null", parts("jdbc:mysql://localhost"));
        assertEquals("MARIADB 192.168.0.63 13307 demo", parts("jdbc:mariadb://192.168.0.63:13307/demo"));
        assertEquals("POSTGRESQL pg 5432 demo", parts("jdbc:postgresql://pg/demo?stringtype=unspecified"));
        assertEquals("DB2 db2 60000 demo", parts("jdbc:db2://db2:60000/demo:progressiveStreaming=2;"));
        assertEquals("CLICKHOUSE ch 18123 app", parts("jdbc:clickhouse:http://ch:18123/app?compress=0"));
        assertEquals("CLICKHOUSE ch 8123 null", parts("jdbc:ch://ch:8123"));
    }

    @Test
    void readsOracleAndSqlServerUrls() {
        assertEquals("ORACLE 192.168.0.63 11521 DEMO", parts("jdbc:oracle:thin:@//192.168.0.63:11521/DEMO"));
        assertEquals("ORACLE ora 1521 XE", parts("jdbc:oracle:thin:@ora:1521:XE"));
        assertEquals("ORACLE ora 1521 FREEPDB1", parts("jdbc:oracle:thin:@ora/FREEPDB1"));
        // A TNS descriptor is kept as the url only
        assertEquals("ORACLE null 1521 null", parts("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=ora)))"));
        assertEquals("SQLSERVER mssql 11433 demo",
                parts("jdbc:sqlserver://mssql:11433;databaseName=demo;encrypt=true"));
        assertEquals("SQLSERVER mssql 1433 sales", parts("jdbc:sqlserver://mssql\\SQLEXPRESS;database=sales"));
        assertEquals("SQLSERVER mssql 1433 null", parts("jdbc:sqlserver://mssql"));
    }

    @Test
    void readsFiles() {
        assertEquals("H2 null 0 /data/shop", parts("jdbc:h2:file:/data/shop;MODE=MySQL"));
        assertEquals("H2 null 0 mem:test", parts("jdbc:h2:mem:test"));
        assertEquals("H2 h2 9093 ~/shop", parts("jdbc:h2:tcp://h2:9093/~/shop"));
        assertEquals("SQLITE null 0 /data/app.sqlite", parts("jdbc:sqlite:/data/app.sqlite?date_class=TEXT"));
        assertEquals("DUCKDB null 0 /data/lake.duckdb", parts("jdbc:duckdb:/data/lake.duckdb"));
        assertEquals("ACCESS null 0 /data/shop.accdb", parts("jdbc:ucanaccess:///data/shop.accdb;memory=false"));
        assertEquals("ACCESS null 0 C:/data/old.mdb", parts("jdbc:ucanaccess://C:/data/old.mdb"));
        assertNull(parts("jdbc:informix-sqli://host:9088/db"));
        assertNull(parts(null));
        // Being typed
        assertEquals("MYSQL null 3306 null", parts("jdbc:mysql:"));
    }

    @Test
    void buildsUrlsOfFields() {
        assertEquals("jdbc:oracle:thin:@//ora:1521/DEMO", JdbcUrls.build(DbType.ORACLE, "ora", 1521, "DEMO"));
        // A required database not entered yet
        assertEquals("jdbc:oracle:thin:@//ora:1521/", JdbcUrls.build(DbType.ORACLE, "ora", 1521, " "));
        assertEquals(DbType.POSTGRESQL.getUrl("pg", 5432, "demo"), JdbcUrls.build(DbType.POSTGRESQL, " pg ", 5432,
                "demo "));
        assertEquals("jdbc:h2:file:/data/shop", JdbcUrls.build(DbType.H2, "", 0, "/data/shop"));
        assertEquals("jdbc:h2:tcp://h2:9092//data/shop", JdbcUrls.build(DbType.H2, "h2", 9092, "/data/shop"));
        assertEquals("", JdbcUrls.build(DbType.SQLITE, null, 0, ""));
        assertEquals("jdbc:ucanaccess:///data/shop.accdb;newdatabaseversion=V2016", JdbcUrls.build(DbType.ACCESS,
                null, 0, "/data/shop.accdb"));
        assertEquals("jdbc:ucanaccess:///data/old.mdb;newdatabaseversion=V2003", JdbcUrls.build(DbType.ACCESS,
                null, 0, "/data/old.mdb"));
        assertEquals("", JdbcUrls.build(null, "host", 1, "db"));
        // Read back
        assertEquals("ORACLE ora 1521 DEMO", parts(JdbcUrls.build(DbType.ORACLE, "ora", 1521, "DEMO")));
        assertEquals("SQLSERVER mssql 1433 demo", parts(JdbcUrls.build(DbType.SQLSERVER, "mssql", 1433, "demo")));
    }
}
