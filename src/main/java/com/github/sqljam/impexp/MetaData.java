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

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;

/**
 * @Description: MetaData is a node of the metadata tree (server, catalog, schema, table, column ...), which generates its statements by the dialect and accepts a visitor
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public interface MetaData {

    DatabaseMetaData getMetaData();
    
    MetaDataOperations getMetaDataOperations();

    Dialect getDialect();

    Map<String, Object> getDetail();

    void accept(MetaDataVisitor visitor) throws SQLException;

    String[] getStatements() throws SQLException;
}