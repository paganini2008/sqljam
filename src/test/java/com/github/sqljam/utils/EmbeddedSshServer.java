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

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/**
 * @Description: EmbeddedSshServer is an SSH server of the tests: a user with a password or with a key, and local
 *               port forwarding to any host, so that tunnels are tested without an SSH server on the network
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class EmbeddedSshServer implements Closeable {

    public static final String USER = "sjtunnel";
    public static final String PASSWORD = "sj-secret";

    private final SshServer server;
    private KeyPair userKey;
    private final AtomicInteger sessions = new AtomicInteger();

    /**
     * @param hostKeyFile host key of the server, generated if it does not exist
     * @param port port of the server, 0 for a free one
     */
    public EmbeddedSshServer(Path hostKeyFile, int port) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        userKey = generator.generateKeyPair();
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(port);
        SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider(hostKeyFile);
        hostKeys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        server.setKeyPairProvider(hostKeys);
        server.setPasswordAuthenticator((username, password, session) -> USER.equals(username) && PASSWORD.equals(
                password));
        server.setPublickeyAuthenticator((username, key, session) -> USER.equals(username) && KeyUtils
                .compareKeys(key, userKey.getPublic()));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.addSessionListener(new SessionListener() {
            @Override
            public void sessionCreated(Session session) {
                sessions.incrementAndGet();
            }
        });
        server.start();
    }

    /**
     * Key of the user, e.g. ed25519 or RSA instead of the generated ECDSA key
     */
    public void setUserKey(KeyPair userKey) {
        this.userKey = userKey;
    }

    /**
     * Refuses port forwarding, like AllowTcpForwarding no of OpenSSH
     */
    public void refuseForwarding() {
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
    }

    public int getPort() {
        return server.getPort();
    }

    /**
     * Sessions which were opened to the server
     */
    public int getSessions() {
        return sessions.get();
    }

    /**
     * Closes the sessions of the clients, as a broken network would
     */
    public void dropSessions() {
        server.getActiveSessions().forEach(session -> session.close(true));
    }

    /**
     * Writes the key of the user in the OpenSSH format, encrypted by the passphrase if it is given
     */
    public Path writeUserKey(Path file, String passphrase) throws Exception {
        OpenSSHKeyPairResourceWriter writer = new OpenSSHKeyPairResourceWriter();
        OpenSSHKeyEncryptionContext context = null;
        if (passphrase != null) {
            context = new OpenSSHKeyEncryptionContext();
            context.setPassword(passphrase);
            context.setCipherName("AES");
            context.setCipherMode("CTR");
            context.setCipherType("256");
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            writer.writePrivateKey(userKey, "sqljam test", context, out);
        }
        return file;
    }

    public SshSettings passwordSettings() {
        SshSettings settings = new SshSettings();
        settings.setEnabled(true);
        settings.setHost("127.0.0.1");
        settings.setPort(getPort());
        settings.setUsername(USER);
        settings.setPassword(PASSWORD);
        return settings;
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }
}
