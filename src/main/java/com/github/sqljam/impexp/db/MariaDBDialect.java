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

import com.github.sqljam.impexp.DbType;

/**
 * @Description: MariaDBDialect generates ddl/dml of MariaDB, which is compatible with MySQL. Differences of MariaDB:
 *               sequences (CREATE SEQUENCE, nextval defaults), native UUID, INET4 and INET6 types, JSON stored as
 *               LONGTEXT with a json_valid check.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class MariaDBDialect extends MySQLDialect {

    @Override
    public DbType getDbType() {
        return DbType.MARIADB;
    }

    /**
     * Native types of MariaDB are kept for MariaDB, UUID values of other databases are UUID
     */
    @Override
    protected String getSpecialTypeName(int dataType, String sourceTypeName, int columnSize, int columnScale) {
        String baseTypeName = sourceTypeName.replaceAll("\\(.*\\)", "").trim();
        switch (baseTypeName) {
            case "uuid":
            case "uniqueidentifier":
                return "uuid";
            case "inet4":
            case "inet6":
                return isCrossDatabase() ? "varchar(45)" : baseTypeName;
            default:
                break;
        }
        return super.getSpecialTypeName(dataType, sourceTypeName, columnSize, columnScale);
    }

    @Override
    protected String getVectorTextFunction() {
        return "VEC_ToText";
    }

    @Override
    public boolean isSequenceSupported() {
        return true;
    }

    @Override
    public String[] getStatementBeforeSequenceCreated(String catalog, String schema, String sequenceName) {
        return new String[]{String.format("DROP SEQUENCE IF EXISTS %s",
                getQualifiedSequenceName(catalog, schema, sequenceName))};
    }

    /**
     * MAXVALUE of MariaDB is a BIGINT, larger values of other databases (e.g. NUMBER of Oracle) are left out
     */
    @Override
    public String getCreateSequenceStatement(String catalog, String schema, String sequenceName, long startValue,
                                             long increment, Long minValue, Long maxValue, boolean cycle,
                                             Long cacheSize, String dataType) {
        return super.getCreateSequenceStatement(catalog, schema, sequenceName, startValue, increment, minValue,
                maxValue != null && maxValue >= Long.MAX_VALUE - 1 ? null : maxValue, cycle, cacheSize, dataType);
    }

    /**
     * NULL of columns reported as OTHER (UUID, INET4, INET6) is bound as VARCHAR
     */
    @Override
    public int getNullSqlType(int sqlType) {
        return sqlType == Types.OTHER ? Types.VARCHAR : super.getNullSqlType(sqlType);
    }
}
