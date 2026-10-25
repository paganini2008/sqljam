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
package com.github.sqljam.face.view;

import java.sql.SQLException;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: AuthFailures tells the errors which a new secret may fix: a database which refused the user or the
 *               password, and an SSH server which refused the password or could not read the key without its
 *               passphrase. Other errors (host unknown, database down) are shown as they are.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class AuthFailures {

    /**
     * Error codes of a refused login: MySQL and MariaDB 1045, Oracle 1017, SQL Server 18456, Db2 -4214 and
     * -30082, ClickHouse 516
     */
    static final Set<Integer> ERROR_CODES = Set.of(1045, 1017, 18456, -4214, -30082, 516);
    static final String SSH_TUNNEL = "SSH tunnel through";

    private AuthFailures() {
    }

    /**
     * Whether the database refused the user or the password, SQLState class 28 or a known error code
     */
    public static boolean isDatabaseFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (StringUtils.startsWith(cause.getMessage(), SSH_TUNNEL)) {
                // The SSH server refused, not the database
                return false;
            }
            if (cause instanceof SQLException) {
                SQLException e = (SQLException) cause;
                if (StringUtils.startsWith(e.getSQLState(), "28") || ERROR_CODES.contains(e.getErrorCode())) {
                    return true;
                }
            }
            String message = StringUtils.defaultString(cause.getMessage());
            if (message.contains("password authentication failed") || message.contains("AUTHENTICATION_FAILED")
                    || message.contains("Authentication failed")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * Whether the SSH server of the tunnel refused the password or the key could not be read without its
     * passphrase
     */
    public static boolean isSshFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = StringUtils.defaultString(cause.getMessage());
            if (message.startsWith(SSH_TUNNEL) && (message.contains("No more authentication methods")
                    || message.contains("unable to read the private key"))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
