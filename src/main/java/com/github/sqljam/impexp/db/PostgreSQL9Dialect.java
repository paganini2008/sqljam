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
package com.github.sqljam.impexp.db;

import java.sql.Types;

/**
 * @Description: PostgreSQL9Dialect for PostgreSQL 9.x: no declarative partitioning, no IF NOT EXISTS of indexes and
 *               sequences (9.5), no data type of sequences, no smallserial (9.2)
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class PostgreSQL9Dialect extends PostgreSQL10Dialect {

    @Override
    public boolean isPartitionSupported() {
        return false;
    }

    @Override
    protected String getIfNotExists() {
        return "";
    }

    @Override
    protected boolean isSequenceDataTypeSupported() {
        return false;
    }

    @Override
    public String getSerialTypeName(int code) {
        if (code == Types.SMALLINT || code == Types.TINYINT) {
            return "serial";
        }
        return super.getSerialTypeName(code);
    }

    /**
     * The sequence is dropped before creating since IF NOT EXISTS is not supported
     */
    @Override
    public String[] getStatementBeforeSequenceCreated(String catalog, String schema, String sequenceName) {
        return new String[]{String.format("DROP SEQUENCE IF EXISTS %s CASCADE",
                getQualifiedSequenceName(catalog, schema, sequenceName))};
    }
}
