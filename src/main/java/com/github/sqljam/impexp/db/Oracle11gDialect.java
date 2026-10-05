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

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: Oracle11gDialect for Oracle 11g and earlier versions: identity columns are implemented by sequences
 *               and triggers, rows are paged by ROWNUM
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class Oracle11gDialect extends Oracle12cDialect {

    @Override
    protected String getIdentityClause() {
        return " NOT NULL";
    }

    private String getQualifiedName(String name) {
        String quotedName = quoteIdentifier(getLimitedIdentifier(foldIdentifier(name)));
        return StringUtils.isNotBlank(getTargetSchemaName()) ? getIdentifier(getTargetSchemaName()) + "." + quotedName
                : quotedName;
    }

    /**
     * Sequence and trigger generating values of the identity column
     */
    @Override
    public String[] getStatementAfterIncrementalColumnCreated(String catalog, String schema, String tableName,
                                                              String columnName) {
        String sequenceName = getQualifiedName("SEQ_" + tableName);
        String createSequence = String.format("BEGIN EXECUTE IMMEDIATE 'CREATE SEQUENCE %s START WITH 1'; "
                + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;", sequenceName);
        String createTrigger = String.format("CREATE OR REPLACE TRIGGER %s BEFORE INSERT ON %s FOR EACH ROW "
                        + "BEGIN IF :NEW.%s IS NULL THEN SELECT %s.NEXTVAL INTO :NEW.%s FROM DUAL; END IF; END;",
                getQualifiedName("TRG_" + tableName), getQualifiedTableName(catalog, schema, tableName),
                getIdentifier(columnName), sequenceName, getIdentifier(columnName));
        return new String[]{createSequence, createTrigger};
    }

    @Override
    public String getResetIdentityStatement(String catalog, String schema, String tableName, String columnName,
                                            long startValue) {
        return String.format("BEGIN EXECUTE IMMEDIATE 'DROP SEQUENCE %1$s'; "
                + "EXECUTE IMMEDIATE 'CREATE SEQUENCE %1$s START WITH %2$d'; END;", getQualifiedName("SEQ_" + tableName),
                startValue);
    }

    /**
     * OFFSET FETCH is supported since Oracle 12c
     */
    @Override
    public String getPageStatement(String sql, String orderBy, int limit, int offset) {
        String ordered = StringUtils.isNotBlank(orderBy) ? sql + " ORDER BY " + orderBy : sql;
        return String.format("SELECT * FROM (SELECT t__.*, ROWNUM rn__ FROM (%s) t__ WHERE ROWNUM <= %d)"
                + " WHERE rn__ > %d", ordered, offset + limit, offset);
    }
}
