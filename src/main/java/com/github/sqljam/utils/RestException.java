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

/**
 * @Description: RestException is an error of a REST API with its HTTP status, 0 if the server was not reached, e.g.
 *               401 of a wrong key, 403 of a key which may not read
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class RestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public RestException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /**
     * Whether the credentials were refused or may not read: 401 and 403
     */
    public boolean isAccessDenied() {
        return status == 401 || status == 403;
    }
}
