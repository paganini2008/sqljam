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
package com.github.sqljam.vdb;

/**
 * @Description: VdbException is an error of reading a vector database which is not an error of its REST API (those
 *               are RestException), e.g. a collection of a structure which can not be imported, a query which the
 *               database does not take
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VdbException(String message) {
        super(message);
    }

    public VdbException(String message, Throwable cause) {
        super(message, cause);
    }
}
