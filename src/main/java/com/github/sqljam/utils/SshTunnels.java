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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.core.CoreModuleProperties;
import lombok.extern.slf4j.Slf4j;
import com.github.sqljam.config.Config;

/**
 * @Description: SshTunnels forwards a local port through an SSH server to the host and port of a data source, like
 *               the SSH tunnels of DBeaver and DataGrip. A tunnel is opened for a data source when it is first
 *               connected, shared by its connections and opened again when its settings change or its session ends.
 *               Host keys are trusted on first use and kept in a known hosts file, a changed host key is refused.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public final class SshTunnels {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final Duration HEARTBEAT = Duration.ofSeconds(30);
    public static final String LOCALHOST_ADDRESS = "127.0.0.1";

    private static final Map<String, Tunnel> TUNNELS = new ConcurrentHashMap<>();
    private static SshClient client;
    /**
     * Known hosts file of sqljam.ssh.known-hosts.file, read when the first tunnel is opened
     */
    private static Path knownHostsFile;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(SshTunnels::closeAll, "sqljam-ssh-tunnels"));
    }

    private SshTunnels() {
    }

    /**
     * An open tunnel: its session and the local port forwarded to the target
     */
    static final class Tunnel {

        final String key;
        final ClientSession session;
        final ExplicitPortForwardingTracker tracker;

        Tunnel(String key, ClientSession session, ExplicitPortForwardingTracker tracker) {
            this.key = key;
            this.session = session;
            this.tracker = tracker;
        }

        boolean isOpen() {
            return session.isOpen() && tracker.isOpen();
        }

        int getLocalPort() {
            return tracker.getBoundAddress().getPort();
        }

        void close() {
            try {
                tracker.close();
            } catch (IOException e) {
                log.debug("Unable to close port forwarding: {}", e.getMessage());
            }
            session.close(true);
        }
    }

    /**
     * Known hosts file of the host keys, e.g. of a test or of a configured directory
     */
    public static synchronized void setKnownHostsFile(Path file) {
        knownHostsFile = file;
        if (client != null) {
            client.setServerKeyVerifier(createVerifier());
        }
    }

    public static synchronized Path getKnownHostsFile() {
        if (knownHostsFile == null) {
            Config config = Config.getInstance();
            File home = new File(config.getString("sqljam.home", System.getProperty("user.home") + "/.sqljam"));
            knownHostsFile = config.getFile("sqljam.ssh.known-hosts.file", new File(home, "ssh_known_hosts"))
                    .toPath();
        }
        return knownHostsFile;
    }

    private static KnownHostsServerKeyVerifier createVerifier() {
        // A new host is accepted and remembered, a changed key of a known host is refused
        KnownHostsServerKeyVerifier verifier = new KnownHostsServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE,
                getKnownHostsFile());
        verifier.setModifiedServerKeyAcceptor((session, address, entry, expected, actual) -> false);
        return verifier;
    }

    private static synchronized SshClient getClient() {
        if (client == null) {
            SshClient created = SshClient.setUpDefaultClient();
            created.setServerKeyVerifier(createVerifier());
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(created, HEARTBEAT);
            created.start();
            client = created;
        }
        return client;
    }

    /**
     * Local port of the tunnel of the data source to the target host and port, the tunnel is opened if it is not
     * open with these settings
     *
     * @param id id of the data source
     * @throws UncheckedIOException if the SSH server is not reached, refuses the user or the forwarding
     */
    public static int open(String id, SshSettings settings, String targetHost, int targetPort) {
        String key = settings.getKey() + "|" + targetHost + ":" + targetPort;
        synchronized (TUNNELS) {
            Tunnel tunnel = TUNNELS.get(id);
            if (tunnel != null && tunnel.key.equals(key) && tunnel.isOpen()) {
                return tunnel.getLocalPort();
            }
            // A tunnel opened again keeps its local port, so the pools of its connections reach it
            int localPort = 0;
            if (tunnel != null) {
                localPort = tunnel.getLocalPort();
                tunnel.close();
                TUNNELS.remove(id);
            }
            try {
                tunnel = connect(key, settings, targetHost, targetPort, localPort);
            } catch (IOException e) {
                throw new UncheckedIOException(describe(settings, e), e);
            }
            TUNNELS.put(id, tunnel);
            if (log.isInfoEnabled()) {
                log.info("SSH tunnel {} -> {}:{} via {}@{}:{}", tunnel.getLocalPort(), targetHost, targetPort,
                        settings.getUsername(), settings.getHost(), settings.getEffectivePort());
            }
            return tunnel.getLocalPort();
        }
    }

    static String describe(SshSettings settings, IOException e) {
        return "SSH tunnel through " + settings.getUsername() + "@" + settings.getHost() + ":" + settings
                .getEffectivePort() + " failed: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass()
                .getSimpleName());
    }

    private static Tunnel connect(String key, SshSettings settings, String targetHost, int targetPort,
            int localPort) throws IOException {
        if (StringUtils.isBlank(settings.getHost())) {
            throw new IOException("no SSH host");
        }
        ClientSession session = getClient().connect(settings.getUsername(), settings.getHost(), settings
                .getEffectivePort()).verify(TIMEOUT).getSession();
        try {
            if (settings.getAuthentication() == SshSettings.Authentication.KEY) {
                for (KeyPair keyPair : loadKeys(session, settings)) {
                    session.addPublicKeyIdentity(keyPair);
                }
            } else if (StringUtils.isNotEmpty(settings.getPassword())) {
                session.addPasswordIdentity(settings.getPassword());
            }
            session.auth().verify(TIMEOUT);
            SshdSocketAddress target = new SshdSocketAddress(targetHost, targetPort);
            probe(session, target);
            ExplicitPortForwardingTracker tracker;
            try {
                tracker = session.createLocalPortForwardingTracker(new SshdSocketAddress(LOCALHOST_ADDRESS,
                        localPort), target);
            } catch (IOException e) {
                if (localPort == 0) {
                    throw e;
                }
                // The port was taken meanwhile
                tracker = session.createLocalPortForwardingTracker(new SshdSocketAddress(LOCALHOST_ADDRESS, 0),
                        target);
            }
            return new Tunnel(key, session, tracker);
        } catch (IOException | RuntimeException e) {
            session.close(true);
            throw e;
        }
    }

    /**
     * Opens one channel to the target through the server, so that a server which allows no forwarding or a target
     * which it does not reach (a database which is down) is told now, not by connections which are reset later
     */
    static void probe(ClientSession session, SshdSocketAddress target) throws IOException {
        try (ChannelDirectTcpip channel = session.createDirectTcpipChannel(new SshdSocketAddress(LOCALHOST_ADDRESS,
                0), target)) {
            channel.open().verify(TIMEOUT);
        } catch (IOException e) {
            throw new IOException("the SSH server does not reach " + target.getHostName() + ":" + target.getPort()
                    + ", it may allow no forwarding or the database is down (" + StringUtils.defaultIfBlank(e
                    .getMessage(), e.getClass().getSimpleName()) + ")", e);
        }
    }

    private static Iterable<KeyPair> loadKeys(ClientSession session, SshSettings settings) throws IOException {
        String file = StringUtils.trimToEmpty(settings.getPrivateKeyFile());
        if (file.startsWith("~")) {
            file = System.getProperty("user.home") + file.substring(1);
        }
        Path path = Path.of(file);
        if (file.isEmpty() || !Files.isRegularFile(path)) {
            throw new IOException("private key file not found: " + settings.getPrivateKeyFile());
        }
        FileKeyPairProvider provider = new FileKeyPairProvider(path);
        provider.setPasswordFinder(FilePasswordProvider.of(settings.getPassphrase()));
        try {
            return provider.loadKeys(session);
        } catch (RuntimeException e) {
            throw new IOException("unable to read the private key " + settings.getPrivateKeyFile() + ": " + e
                    .getMessage(), e);
        }
    }

    /**
     * Local port of the open tunnel of the data source, -1 if it has none
     */
    public static int getLocalPort(String id) {
        Tunnel tunnel = TUNNELS.get(id);
        return tunnel != null && tunnel.isOpen() ? tunnel.getLocalPort() : -1;
    }

    /**
     * Whether the data source has an open tunnel
     */
    public static boolean isOpen(String id) {
        Tunnel tunnel = TUNNELS.get(id);
        return tunnel != null && tunnel.isOpen();
    }

    /**
     * Closes the tunnel of the data source, e.g. when its session ends
     */
    public static void close(String id) {
        synchronized (TUNNELS) {
            Tunnel tunnel = TUNNELS.remove(id);
            if (tunnel != null) {
                tunnel.close();
            }
        }
    }

    public static void closeAll() {
        synchronized (TUNNELS) {
            TUNNELS.values().forEach(Tunnel::close);
            TUNNELS.clear();
        }
    }
}
