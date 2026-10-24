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

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: SshSettings is the SSH server through which a data source is reached: its host, port and user, and
 *               a password or a private key (with its passphrase). Without both the server decides, e.g. Tailscale SSH
 *               authorizes the machines of its network.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
@ToString(exclude = {"password", "passphrase"})
public class SshSettings {

    public static final int DEFAULT_PORT = 22;

    public enum Authentication {
        PASSWORD, KEY
    }

    private boolean enabled;
    private String host;
    private int port = DEFAULT_PORT;
    private String username;
    private Authentication authentication = Authentication.PASSWORD;
    private String password;
    /**
     * Private key file, e.g. ~/.ssh/id_ed25519
     */
    private String privateKeyFile;
    private String passphrase;

    public int getEffectivePort() {
        return port > 0 ? port : DEFAULT_PORT;
    }

    /**
     * The secret of the authentication, the password or the passphrase of the key
     */
    public String getSecret() {
        return authentication == Authentication.KEY ? passphrase : password;
    }

    public void setSecret(String secret) {
        if (authentication == Authentication.KEY) {
            passphrase = secret;
        } else {
            password = secret;
        }
    }

    /**
     * Key of a tunnel of these settings, a tunnel is opened again when they change
     */
    public String getKey() {
        return String.join("|", host, String.valueOf(getEffectivePort()), username, String.valueOf(authentication),
                StringUtils.defaultString(privateKeyFile), String.valueOf(Objects.hashCode(getSecret())));
    }

    public SshSettings copy() {
        SshSettings copy = new SshSettings();
        copy.setEnabled(enabled);
        copy.setHost(host);
        copy.setPort(port);
        copy.setUsername(username);
        copy.setAuthentication(authentication);
        copy.setPassword(password);
        copy.setPrivateKeyFile(privateKeyFile);
        copy.setPassphrase(passphrase);
        return copy;
    }
}
