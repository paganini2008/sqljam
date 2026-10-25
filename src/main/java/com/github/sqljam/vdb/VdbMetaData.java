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

import java.sql.SQLException;

import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbMetaData is a node of the metadata tree of the vdb line (server, collection, field, primary key,
 *               vector index, comment), which generates its statements of the target by the vdb dialect and accepts a
 *               visitor, like MetaData of the jdbc line
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public interface VdbMetaData {

    VdbDialect getDialect();

    void accept(VdbMetaDataVisitor visitor) throws SQLException;

    String[] getStatements() throws SQLException;
}
