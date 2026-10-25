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
package com.github.sqljam.vdb.dialect;

/**
 * @Description: VdbFeature is a capability of a target database which is detected on its connection before it
 *               is used: types and indexes of extensions (pgvector), of editions and of settings (vector memory of
 *               Oracle) are not told by the version. It is a copy of DatabaseFeature of impexp for the vdb line.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public enum VdbFeature {

    /**
     * Dense vectors of 32-bit floats, e.g. vector of pgvector, VECTOR of MySQL
     */
    VECTOR,

    /**
     * Dense vectors of 16-bit floats, e.g. halfvec of pgvector, VECTOR(n, float16) of SQL Server
     */
    HALF_VECTOR,

    /**
     * Sparse vectors, e.g. sparsevec of pgvector, VECTOR(n, FLOAT32, SPARSE) of Oracle
     */
    SPARSE_VECTOR,

    /**
     * Vector indexes of graphs (HNSW)
     */
    HNSW_INDEX,

    /**
     * Vector indexes of partitions (IVF)
     */
    IVF_INDEX
}
