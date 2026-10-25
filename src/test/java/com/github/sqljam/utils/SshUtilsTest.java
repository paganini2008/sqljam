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
package com.github.sqljam.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;

/**
 * @Description: SshUtilsTest opens tunnels through an embedded SSH server to a local echo server: by a password,
 *               by a key with or without a passphrase, shared and opened again on the same local port, refused for
 *               a wrong password, a missing key or a changed host key
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class SshUtilsTest {

    static ServerSocket echo;
    static Path knownHosts;

    @TempDir
    Path dir;

    private EmbeddedSshServer server;

    /**
     * Echoes each line of its clients, the target of the tunnels
     */
    @BeforeAll
    static void startEcho(@TempDir Path hostsDir) throws IOException {
        knownHosts = hostsDir.resolve("known_hosts");
        SshUtils.setKnownHostsFile(knownHosts);
        echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            while (!echo.isClosed()) {
                try {
                    Socket socket = echo.accept();
                    new Thread(() -> {
                        try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket
                                .getInputStream(), StandardCharsets.UTF_8)); PrintWriter out = new PrintWriter(socket
                                .getOutputStream(), true, StandardCharsets.UTF_8)) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                out.println("echo " + line);
                            }
                        } catch (IOException e) {
                            // The client went away
                        }
                    }).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @AfterAll
    static void stopEcho() throws IOException {
        echo.close();
        SshUtils.closeAll();
    }

    @AfterEach
    void stopServer() throws IOException {
        SshUtils.closeAll();
        if (server != null) {
            server.close();
        }
    }

    private EmbeddedSshServer start() throws Exception {
        server = new EmbeddedSshServer(dir.resolve("host.key"), 0);
        return server;
    }

    static String send(int port, String text) throws IOException {
        try (Socket socket = new Socket(SshUtils.LOCALHOST_ADDRESS, port); PrintWriter out = new PrintWriter(socket
                .getOutputStream(), true, StandardCharsets.UTF_8); BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(10000);
            out.println(text);
            return in.readLine();
        }
    }

    @Test
    void forwardsByPassword() throws Exception {
        start();
        SshSettings settings = server.passwordSettings();
        int port = SshUtils.open("a", settings, "127.0.0.1", echo.getLocalPort());
        assertEquals("echo hello", send(port, "hello"));
        assertTrue(SshUtils.isOpen("a"));
        assertEquals(port, SshUtils.getLocalPort("a"));
        // Shared by the connections of the data source
        assertEquals(port, SshUtils.open("a", settings, "127.0.0.1", echo.getLocalPort()));
        assertEquals(1, server.getSessions());
        // The host key is remembered
        assertTrue(Files.readString(knownHosts).contains("127.0.0.1"));

        // A broken session is opened again on its local port
        server.dropSessions();
        long deadline = System.currentTimeMillis() + 5000;
        while (SshUtils.isOpen("a") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(SshUtils.isOpen("a"));
        assertEquals(-1, SshUtils.getLocalPort("a"));
        assertEquals(port, SshUtils.open("a", settings, "127.0.0.1", echo.getLocalPort()));
        assertEquals("echo again", send(port, "again"));

        // Other settings open another tunnel, closed ones are gone
        SshSettings other = settings.copy();
        other.setPort(settings.getPort());
        int second = SshUtils.open("b", other, "127.0.0.1", echo.getLocalPort());
        assertNotEquals(port, second);
        SshUtils.close("a");
        SshUtils.close("missing");
        assertFalse(SshUtils.isOpen("a"));
        assertTrue(SshUtils.isOpen("b"));
        assertThrows(IOException.class, () -> send(port, "closed"));
    }

    @Test
    void forwardsByKey() throws Exception {
        start();
        SshSettings settings = server.passwordSettings();
        settings.setPassword(null);
        settings.setAuthentication(SshSettings.Authentication.KEY);
        settings.setPrivateKeyFile(server.writeUserKey(dir.resolve("id_plain"), null).toString());
        int port = SshUtils.open("key", settings, "127.0.0.1", echo.getLocalPort());
        assertEquals("echo key", send(port, "key"));

        settings.setPrivateKeyFile(server.writeUserKey(dir.resolve("id_secret"), "phrase").toString());
        settings.setPassphrase("phrase");
        int encrypted = SshUtils.open("encrypted", settings, "127.0.0.1", echo.getLocalPort());
        assertEquals("echo phrase", send(encrypted, "phrase"));

        // A wrong passphrase, a missing key
        settings.setPassphrase("wrong");
        assertThrows(UncheckedIOException.class, () -> SshUtils.open("wrong", settings, "127.0.0.1", echo
                .getLocalPort()));
        settings.setPrivateKeyFile(dir.resolve("missing").toString());
        UncheckedIOException missing = assertThrows(UncheckedIOException.class, () -> SshUtils.open("missing",
                settings, "127.0.0.1", echo.getLocalPort()));
        assertTrue(missing.getMessage().contains("private key file not found"), missing.getMessage());
        settings.setPrivateKeyFile("~/sqljam-missing-key");
        assertThrows(UncheckedIOException.class, () -> SshUtils.open("home", settings, "127.0.0.1", echo
                .getLocalPort()));
        settings.setPrivateKeyFile(Files.writeString(dir.resolve("not_a_key"), "not a key").toString());
        assertThrows(UncheckedIOException.class, () -> SshUtils.open("invalid", settings, "127.0.0.1", echo
                .getLocalPort()));
    }

    /**
     * Keys which other machines have most: ed25519 and RSA, an ed25519 key with a passphrase too
     */
    @Test
    void forwardsByKeysOfOtherTypes() throws Exception {
        start();
        SshSettings settings = server.passwordSettings();
        settings.setPassword(null);
        settings.setAuthentication(SshSettings.Authentication.KEY);
        server.setUserKey(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256));
        settings.setPrivateKeyFile(server.writeUserKey(dir.resolve("id_ed25519"), null).toString());
        assertEquals("echo ed25519", send(SshUtils.open("ed25519", settings, "127.0.0.1", echo.getLocalPort()),
                "ed25519"));
        settings.setPrivateKeyFile(server.writeUserKey(dir.resolve("id_ed25519_secret"), "phrase").toString());
        settings.setPassphrase("phrase");
        assertEquals("echo secret", send(SshUtils.open("ed25519 secret", settings, "127.0.0.1", echo
                .getLocalPort()), "secret"));
        server.setUserKey(KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, 2048));
        settings.setPassphrase(null);
        settings.setPrivateKeyFile(server.writeUserKey(dir.resolve("id_rsa"), null).toString());
        assertEquals("echo rsa", send(SshUtils.open("rsa", settings, "127.0.0.1", echo.getLocalPort()), "rsa"));
        // A key which the server does not know
        server.setUserKey(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256));
        assertThrows(UncheckedIOException.class, () -> SshUtils.open("unknown", settings, "127.0.0.1", echo
                .getLocalPort()));
    }

    /**
     * Servers and networks of other machines: no forwarding allowed, an unknown host, a target which is down and a
     * known hosts file in a directory which does not exist yet
     */
    @Test
    void reportsEnvironmentsOfOtherMachines(@TempDir Path other) throws Exception {
        start();
        // A directory which does not exist yet keeps the host keys
        Path nested = other.resolve("missing/dir/known_hosts");
        SshUtils.setKnownHostsFile(nested);
        try {
            int port = SshUtils.open("nested", server.passwordSettings(), "127.0.0.1", echo.getLocalPort());
            assertEquals("echo nested", send(port, "nested"));
            assertTrue(Files.readString(nested).contains("127.0.0.1"), "The host key is remembered");
        } finally {
            SshUtils.setKnownHostsFile(knownHosts);
        }

        // A target which the server does not reach, e.g. a database which is down, is told when the tunnel opens
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        UncheckedIOException down = assertThrows(UncheckedIOException.class, () -> SshUtils.open("down", server
                .passwordSettings(), "127.0.0.1", closedPort));
        assertTrue(down.getMessage().contains("does not reach 127.0.0.1:" + closedPort), down.getMessage());
        assertFalse(SshUtils.isOpen("down"));

        // An unknown host of the SSH server
        SshSettings unknown = server.passwordSettings();
        unknown.setHost("sqljam-no-such-host.invalid");
        UncheckedIOException unresolved = assertThrows(UncheckedIOException.class, () -> SshUtils.open(
                "unknown", unknown, "127.0.0.1", echo.getLocalPort()));
        assertTrue(unresolved.getMessage().contains("sqljam-no-such-host.invalid"), unresolved.getMessage());

        // A server which allows no forwarding
        server.refuseForwarding();
        SshUtils.closeAll();
        UncheckedIOException refused = assertThrows(UncheckedIOException.class, () -> SshUtils.open("refused",
                server.passwordSettings(), "127.0.0.1", echo.getLocalPort()));
        assertTrue(refused.getMessage().startsWith("SSH tunnel through") && refused.getMessage().contains(
                "may allow no forwarding"), refused.getMessage());
    }

    @Test
    void refusesWrongUsersAndHosts() throws Exception {
        start();
        SshSettings settings = server.passwordSettings();
        settings.setPassword("wrong");
        UncheckedIOException wrong = assertThrows(UncheckedIOException.class, () -> SshUtils.open("w", settings,
                "127.0.0.1", echo.getLocalPort()));
        assertTrue(wrong.getMessage().startsWith("SSH tunnel through " + EmbeddedSshServer.USER + "@127.0.0.1:"),
                wrong.getMessage());
        settings.setPassword(null);
        assertThrows(UncheckedIOException.class, () -> SshUtils.open("w", settings, "127.0.0.1", echo
                .getLocalPort()));
        settings.setHost(" ");
        assertTrue(assertThrows(UncheckedIOException.class, () -> SshUtils.open("w", settings, "127.0.0.1", echo
                .getLocalPort())).getMessage().contains("no SSH host"));
        // Nobody listens
        SshSettings closed = server.passwordSettings();
        int port = server.getPort();
        server.close();
        server = null;
        closed.setPort(port);
        assertThrows(UncheckedIOException.class, () -> SshUtils.open("c", closed, "127.0.0.1", echo
                .getLocalPort()));
        assertFalse(SshUtils.isOpen("w"));
    }

    @Test
    void refusesChangedHostKey(@TempDir Path other) throws Exception {
        start();
        int port = server.getPort();
        SshSettings settings = server.passwordSettings();
        assertEquals("echo first", send(SshUtils.open("h", settings, "127.0.0.1", echo.getLocalPort()), "first"));
        SshUtils.closeAll();
        server.close();
        // Another server with another host key on the same address
        server = new EmbeddedSshServer(other.resolve("other.key"), port);
        UncheckedIOException changed = assertThrows(UncheckedIOException.class, () -> SshUtils.open("h",
                settings, "127.0.0.1", echo.getLocalPort()));
        assertTrue(changed.getMessage().startsWith("SSH tunnel through"), changed.getMessage());
    }

    /**
     * The url of a profile through its tunnel: of its fields, of a custom url and never of a database file
     */
    @Test
    void tunnelsUrlsOfProfiles() throws Exception {
        start();
        ConnectionProfile profile = new ConnectionProfile();
        profile.setDbType(DbType.MYSQL);
        profile.setHostname("127.0.0.1");
        profile.setDatabase("shop");
        profile.setSsh(server.passwordSettings());
        // The default port of the type, worked out without a database listening on it
        assertArrayEquals(new String[]{"127.0.0.1", "3306"}, profile.tunnelTarget(false));
        profile.setPort(echo.getLocalPort());
        String url = profile.getJdbcUrl();
        int port = SshUtils.getLocalPort(profile.getId());
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:" + port + "/shop?"), url);
        assertEquals("echo url", send(port, "url"));

        // A custom url, e.g. Oracle, with its host and port
        ConnectionProfile oracle = new ConnectionProfile();
        oracle.setDbType(DbType.ORACLE);
        oracle.setUrl("jdbc:oracle:thin:@//127.0.0.1:" + echo.getLocalPort() + "/DEMO");
        oracle.setSsh(server.passwordSettings());
        String oracleUrl = oracle.getJdbcUrl();
        assertEquals("jdbc:oracle:thin:@//127.0.0.1:" + SshUtils.getLocalPort(oracle.getId()) + "/DEMO",
                oracleUrl);
        ConnectionProfile ipv6 = new ConnectionProfile();
        ipv6.setDbType(DbType.POSTGRESQL);
        ipv6.setUrl("jdbc:postgresql://[::1]:" + echo.getLocalPort() + "/app");
        ipv6.setSsh(server.passwordSettings());
        assertArrayEquals(new String[]{"::1", String.valueOf(echo.getLocalPort())}, ipv6.tunnelTarget(true));
        // The host in brackets is the target, the echo server listens on IPv4 only, so it is not reached
        UncheckedIOException ipv6Error = assertThrows(UncheckedIOException.class, ipv6::getJdbcUrl);
        assertTrue(ipv6Error.getMessage().contains("does not reach ::1:" + echo.getLocalPort()), ipv6Error
                .getMessage());

        // Without a host, with the tunnel disabled, of a database file
        ConnectionProfile hostless = new ConnectionProfile();
        hostless.setName("hostless");
        hostless.setDbType(DbType.MYSQL);
        hostless.setSsh(server.passwordSettings());
        assertTrue(assertThrows(IllegalStateException.class, hostless::getJdbcUrl).getMessage().contains(
                "hostless"));
        hostless.getSsh().setEnabled(false);
        hostless.setHostname("db");
        assertFalse(hostless.isSshEnabled());
        assertTrue(hostless.getJdbcUrl().startsWith("jdbc:mysql://db:"));
        ConnectionProfile file = new ConnectionProfile();
        file.setDbType(DbType.SQLITE);
        file.setDatabase(dir.resolve("a.db").toString());
        file.setSsh(server.passwordSettings());
        assertFalse(file.isSshEnabled());
        assertFalse(file.getJdbcUrl().contains("127.0.0.1"));
        assertFalse(SshUtils.isOpen(file.getId()));
    }

    @Test
    void describesSettings() {
        SshSettings settings = new SshSettings();
        assertEquals(22, settings.getEffectivePort());
        settings.setPort(0);
        assertEquals(22, settings.getEffectivePort());
        settings.setPassword("pw");
        assertEquals("pw", settings.getSecret());
        settings.setAuthentication(SshSettings.Authentication.KEY);
        settings.setSecret("phrase");
        assertEquals("phrase", settings.getPassphrase());
        assertEquals("phrase", settings.getSecret());
        settings.setAuthentication(SshSettings.Authentication.PASSWORD);
        settings.setSecret("new");
        assertEquals("new", settings.getPassword());
        String key = settings.getKey();
        settings.setPassword("other");
        assertNotEquals(key, settings.getKey());
        assertFalse(settings.toString().contains("other"));
        assertFalse(settings.copy().toString().contains("phrase"));
        assertEquals(settings.getKey(), settings.copy().getKey());
        assertEquals(knownHosts, SshUtils.getKnownHostsFile());
        // The file of sqljam.ssh.known-hosts.file by default
        SshUtils.setKnownHostsFile(null);
        assertTrue(SshUtils.getKnownHostsFile().endsWith("ssh_known_hosts"), SshUtils.getKnownHostsFile()
                .toString());
        SshUtils.setKnownHostsFile(knownHosts);
        assertTrue(SshUtils.describe(settings, new IOException()).endsWith("IOException"));
    }
}
