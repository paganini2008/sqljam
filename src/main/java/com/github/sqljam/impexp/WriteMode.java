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
package com.github.sqljam.impexp;

/**
 * @Description: WriteMode of rows in the target table: INSERT adds the rows, UPSERT updates the rows of the same key
 *               and inserts the others, SKIP_EXISTING inserts the rows whose key is missing and keeps the others.
 *               The key is the primary key of the table by default, or the columns chosen by the user. A table
 *               without both is inserted.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public enum WriteMode {

    INSERT,

    UPSERT,

    SKIP_EXISTING;

    /**
     * Whether rows are matched by their key
     */
    public boolean isKeyed() {
        return this != INSERT;
    }

    /**
     * Write mode of a name, e.g. of a manifest, INSERT if it is unknown
     */
    public static WriteMode of(Object name) {
        for (WriteMode mode : values()) {
            if (mode.name().equals(String.valueOf(name))) {
                return mode;
            }
        }
        return INSERT;
    }
}
