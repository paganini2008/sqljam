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
 * @Description: SQLServer2008Dialect for SQL Server 2008: sequences are not supported, rows are paged by
 *               ROW_NUMBER()
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SQLServer2008Dialect extends SQLServerDialect {

    @Override
    public boolean isSequenceSupported() {
        return false;
    }

    /**
     * OFFSET FETCH is supported since SQL Server 2012
     */
    @Override
    public String getPageStatement(String sql, String orderBy, int limit, int offset) {
        return String.format("SELECT * FROM (SELECT t__.*, ROW_NUMBER() OVER (ORDER BY %s) AS rn__ FROM (%s) t__)"
                        + " q__ WHERE rn__ > %d AND rn__ <= %d",
                StringUtils.isNotBlank(orderBy) ? orderBy : "(SELECT NULL)", sql, offset, offset + limit);
    }
}
