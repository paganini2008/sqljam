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
package com.github.sqljam.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.DatabaseInfo;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.utils.EmbeddedSshServer;
import com.github.sqljam.utils.SshSettings;
import com.github.sqljam.utils.SshUtils;

/**
 * @Description: SshTunnelIT connects to the server databases of the tests through SSH tunnels of an embedded SSH
 *               server: the test of a connection, the browsing of a session (a tunnel for all databases of
 *               PostgreSQL), SQL, the Info page and a custom url. A real SSH server is tested too when
 *               -Dsqljam.it.ssh.host (with .port, .user, .password or .key, .passphrase and .target) tells it.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class SshTunnelIT {

    @TempDir
    static Path dir;

    static EmbeddedSshServer server;

    @BeforeAll
    static void startServer() throws Exception {
        SshUtils.setKnownHostsFile(dir.resolve("known_hosts"));
        server = new EmbeddedSshServer(dir.resolve("host.key"), 0);
    }

    @AfterAll
    static void stopServer() throws Exception {
        SshUtils.closeAll();
        server.close();
    }

    static ConnectionProfile tunneled(ItDatabase database, SshSettings settings) {
        ConnectionProfile profile = database.getSourceProfile();
        profile.setSsh(settings);
        return profile;
    }

    /**
     * Catalogs of the database, schemas of a database without catalogs (Oracle)
     */
    static List<String> browse(DatabaseSession session) throws Exception {
        return session.getDbType().isCatalogSupported() ? session.getCatalogs() : session.getSchemas(null);
    }

    @ParameterizedTest
    @EnumSource(value = ItDatabase.class, names = {"MYSQL", "POSTGRESQL", "SQLSERVER", "ORACLE"})
    void connectsThroughTunnel(ItDatabase database) throws Exception {
        assumeTrue(database.isAvailable());
        ConnectionProfile profile = tunneled(database, server.passwordSettings());
        String url = profile.getJdbcUrl();
        assertTrue(url.contains(SshUtils.LOCALHOST_ADDRESS + ":" + SshUtils.getLocalPort(profile.getId())),
                url);
        assertFalse(url.contains(database.getSourceProfile().getHostname()), url);
        // A tunnel opened for the test is closed after it
        SshUtils.close(profile.getId());
        assertFalse(StringUtils.isBlank(DatabaseSession.testConnection(profile)));
        assertFalse(SshUtils.isOpen(profile.getId()));

        try (DatabaseSession session = new DatabaseSession(profile)) {
            assertFalse(browse(session).isEmpty());
            try (Connection connection = session.openSqlConnection(null, null); Statement statement = connection
                    .createStatement(); ResultSet rs = statement.executeQuery(database == ItDatabase.ORACLE
                            ? "SELECT 1 FROM DUAL" : "SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            Map<String, Map<String, String>> overview = new DatabaseInfo(profile, session).getOverview();
            String tunnel = overview.get("Server").get("SSH tunnel");
            assertTrue(tunnel.startsWith(EmbeddedSshServer.USER + "@127.0.0.1:" + server.getPort()), tunnel);
            // A broken tunnel is opened again on its port, the pool of the session reaches it
            int port = SshUtils.getLocalPort(profile.getId());
            server.dropSessions();
            long deadline = System.currentTimeMillis() + 5000;
            while (SshUtils.isOpen(profile.getId()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(browse(session).isEmpty());
            assertEquals(port, SshUtils.getLocalPort(profile.getId()));
        }
        // The session closes its tunnel
        assertFalse(SshUtils.isOpen(profile.getId()));
    }

    /**
     * PostgreSQL connects to a database per connection, all of them through one tunnel
     */
    @Test
    void browsesDatabasesOfPostgreSqlThroughOneTunnel() throws Exception {
        ItDatabase database = ItDatabase.POSTGRESQL;
        assumeTrue(database.isAvailable());
        ConnectionProfile profile = tunneled(database, server.passwordSettings());
        int sessions = server.getSessions();
        try (DatabaseSession session = new DatabaseSession(profile)) {
            for (String catalog : session.getCatalogs()) {
                session.getSchemas(catalog);
            }
            assertEquals(sessions + 1, server.getSessions());
        }
    }

    /**
     * The host and port of a custom url are reached through the tunnel
     */
    @Test
    void rewritesCustomUrl() throws Exception {
        ItDatabase database = ItDatabase.MYSQL;
        assumeTrue(database.isAvailable());
        ConnectionProfile profile = database.getSourceProfile();
        profile.setUrl(profile.getJdbcUrl());
        profile.setHostname(null);
        profile.setSsh(server.passwordSettings());
        String url = profile.getJdbcUrl();
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:" + SshUtils.getLocalPort(profile.getId())), url);
        assertFalse(StringUtils.isBlank(DatabaseSession.testConnection(profile)));
        SshUtils.close(profile.getId());
    }

    /**
     * A real SSH server, e.g. OpenSSH of Windows or of a Tailscale network: -Dsqljam.it.ssh.host (with .port,
     * .user, .password or .key and .passphrase). The databases of the tests are reached as -Dsqljam.it.ssh.targetHost
     * names them from the SSH server, e.g. localhost, their own host by default. -Dsqljam.it.ssh.targets limits
     * the databases, e.g. MYSQL,ORACLE.
     */
    @ParameterizedTest
    @EnumSource(value = ItDatabase.class, names = {"MYSQL", "POSTGRESQL", "SQLSERVER", "ORACLE"})
    void connectsThroughRealServer(ItDatabase database) throws Exception {
        String host = System.getProperty("sqljam.it.ssh.host");
        assumeTrue(StringUtils.isNotBlank(host), "-Dsqljam.it.ssh.host is not given");
        String targets = System.getProperty("sqljam.it.ssh.targets", "");
        assumeTrue(targets.isBlank() || List.of(targets.split(",")).contains(database.name()));
        assumeTrue(database.isAvailable());
        SshSettings settings = new SshSettings();
        settings.setEnabled(true);
        settings.setHost(host);
        settings.setPort(Integer.parseInt(System.getProperty("sqljam.it.ssh.port", "22")));
        settings.setUsername(System.getProperty("sqljam.it.ssh.user", System.getProperty("user.name")));
        String key = System.getProperty("sqljam.it.ssh.key");
        if (StringUtils.isNotBlank(key)) {
            settings.setAuthentication(SshSettings.Authentication.KEY);
            settings.setPrivateKeyFile(key);
            settings.setPassphrase(System.getProperty("sqljam.it.ssh.passphrase"));
        } else {
            settings.setPassword(System.getProperty("sqljam.it.ssh.password"));
        }
        // Host keys of a real server are kept apart from those of the embedded one
        Path knownHosts = dir.resolve("real_known_hosts");
        SshUtils.setKnownHostsFile(knownHosts);
        try {
            ConnectionProfile profile = tunneled(database, settings);
            String targetHost = System.getProperty("sqljam.it.ssh.targetHost");
            if (StringUtils.isNotBlank(targetHost)) {
                profile.setHostname(targetHost);
            }
            assertFalse(StringUtils.isBlank(DatabaseSession.testConnection(profile)));
            assertTrue(Files.readString(knownHosts).contains(host), "The host key is remembered");
            try (DatabaseSession session = new DatabaseSession(profile)) {
                assertFalse(browse(session).isEmpty());
                try (Connection connection = session.openSqlConnection(null, null); Statement statement = connection
                        .createStatement(); ResultSet rs = statement.executeQuery(database == ItDatabase.ORACLE
                                ? "SELECT 1 FROM DUAL" : "SELECT 1")) {
                    assertTrue(rs.next());
                }
                String tunnel = new DatabaseInfo(profile, session).getOverview().get("Server").get("SSH tunnel");
                assertTrue(tunnel.startsWith(settings.getUsername() + "@" + host), tunnel);
            }
            assertFalse(SshUtils.isOpen(profile.getId()));
        } finally {
            SshUtils.setKnownHostsFile(dir.resolve("known_hosts"));
        }
    }
}
