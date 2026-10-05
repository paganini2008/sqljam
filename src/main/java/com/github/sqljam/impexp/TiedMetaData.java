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
 * @Description: TiedMetaData is metadata tied to its parent node, names of catalog/schema/table are inherited from parents
 * @Author: Fred Feng
 * @Date: 17/05/2023
 * @Version 1.0.0
 */
public interface TiedMetaData extends MetaData {

    String getCatalogName();

    default String getSchemaName() {
        return null;
    }

    default String getTableName() {
        return null;
    }

    <T extends TiedMetaData> T unwrap(Class<T> clz);
}