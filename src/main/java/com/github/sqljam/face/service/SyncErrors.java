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

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: SyncErrors tells the errors which a sync task runs again after from the errors which it does not:
 *               a connection which was lost or refused, a timeout, a deadlock or an SSH tunnel which broke may pass,
 *               a constraint, a type, a syntax or a missing password does not, running again would fail the same.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class SyncErrors {

    private SyncErrors() {
    }

    /**
     * An error which can not pass by running again, e.g. the password of a data source is not remembered
     */
    public static class PermanentException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public PermanentException(String message) {
            super(message);
        }
    }

    public static boolean isRetryable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PermanentException) {
                return false;
            }
            if (cause instanceof SQLException) {
                SQLException sqlException = (SQLException) cause;
                String state = StringUtils.defaultString(sqlException.getSQLState());
                // Data, integrity, syntax and access: the same rows fail again
                if (state.startsWith("22") || state.startsWith("23") || state.startsWith("42") || state.startsWith(
                        "28")) {
                    return false;
                }
                // Connection, transaction rolled back by the database (deadlocks, serialization), resources
                if (state.startsWith("08") || state.startsWith("40") || state.startsWith("53") || state.startsWith(
                        "57") || state.equals("HYT00") || state.equals("HYT01")) {
                    return true;
                }
                if (cause instanceof SQLTransientException || cause instanceof SQLRecoverableException
                        || cause instanceof SQLNonTransientConnectionException) {
                    return true;
                }
                // H2: the connection is broken, the database was closed
                if (state.equals("90067") || state.equals("90098") || state.equals("90121")) {
                    return true;
                }
            }
            if (cause instanceof IOException || cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getClass().getName().startsWith("org.apache.sshd.")) {
                return true;
            }
        }
        return false;
    }
}
