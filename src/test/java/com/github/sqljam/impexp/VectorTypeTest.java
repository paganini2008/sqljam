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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * @Description: VectorTypeTest
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VectorTypeTest {

    @Test
    void types() {
        VectorType type = VectorType.dense(VectorType.Element.FLOAT32, 1024);
        assertEquals("FLOAT32,1024,DENSE", type.toString());
        assertEquals(type, VectorType.parse("float32, 1024, dense"));
        assertEquals(type.hashCode(), VectorType.parse(type.toString()).hashCode());
        assertNotEquals(type, VectorType.dense(VectorType.Element.FLOAT32, 3));
        assertNotEquals(type, new VectorType(VectorType.Element.FLOAT32, 1024, true));
        assertNotEquals(type, "FLOAT32,1024,DENSE");
        assertEquals("FLOAT16,0,SPARSE", new VectorType(VectorType.Element.FLOAT16, -1, true).toString());
        assertTrue(VectorType.dense(VectorType.Element.INT8, 3).isFloat32Compatible());
        assertTrue(VectorType.dense(VectorType.Element.FLOAT16, 3).isFloat32Compatible());
        assertFalse(VectorType.dense(VectorType.Element.FLOAT64, 3).isFloat32Compatible());
        assertFalse(VectorType.dense(VectorType.Element.BINARY, 8).isFloat32Compatible());
        assertThrows(NullPointerException.class, () -> new VectorType(null, 3, false));

        // Invalid texts are no vector types
        assertNull(VectorType.parse(null));
        assertNull(VectorType.parse("FLOAT32,3"));
        assertNull(VectorType.parse("FLOAT128,3,DENSE"));
        assertNull(VectorType.parse("FLOAT32,many,DENSE"));

        Map<String, Object> detail = new HashMap<>();
        assertNull(VectorType.of(detail));
        assertNull(VectorType.of(null));
        detail.put(VectorType.DETAIL_KEY, "INT8,4,DENSE");
        assertEquals(VectorType.dense(VectorType.Element.INT8, 4), VectorType.of(detail));
    }

    @Test
    void denseValues() {
        VectorType.Values values = VectorType.parseValues(" [1, 2.5E+000 ,-3] ");
        assertEquals(3, values.getDimensions());
        assertFalse(values.isSparse());
        assertEquals("[1,2.5E+000,-3]", values.toDenseText());
        assertArrayEquals(new float[]{1, 2.5f, -3}, values.toFloats());
        // Zeros are left out of sparse texts, indexes of pgvector start from 1 and of Oracle from 0
        assertEquals("{1:1,2:2.5E+000,3:-3}/3", values.toPostgreSqlSparseText());
        assertEquals("[3,[0,1,2],[1,2.5E+000,-3]]", values.toOracleSparseText());
        assertEquals("{2:5}/3", VectorType.parseValues("[0,5,0.0]").toPostgreSqlSparseText());
        assertEquals("[]", VectorType.parseValues("[]").toDenseText());
        assertEquals(0, VectorType.parseValues("[ ]").getDimensions());
    }

    @Test
    void sparseValues() {
        VectorType.Values postgres = VectorType.parseValues("{1:1.5,3:2}/5");
        assertTrue(postgres.isSparse());
        assertEquals(5, postgres.getDimensions());
        assertEquals("[1.5,0,2,0,0]", postgres.toDenseText());
        assertEquals("[5,[0,2],[1.5,2]]", postgres.toOracleSparseText());
        assertEquals("{1:1.5,3:2}/5", postgres.toPostgreSqlSparseText());

        VectorType.Values oracle = VectorType.parseValues("[5,[0,2],[1.5E+000,2.0E+000]]");
        assertTrue(oracle.isSparse());
        assertEquals("{1:1.5E+000,3:2.0E+000}/5", oracle.toPostgreSqlSparseText());
        assertArrayEquals(new float[]{1.5f, 0, 2, 0, 0}, oracle.toFloats());

        // A sparse vector without elements
        assertEquals("[0,0,0]", VectorType.parseValues("{}/3").toDenseText());
        assertEquals("[3,[],[]]", VectorType.parseValues("{}/3").toOracleSparseText());
        assertEquals("{}/3", VectorType.parseValues("[3,[],[]]").toPostgreSqlSparseText());
    }

    @Test
    void invalidValues() {
        assertNull(VectorType.parseValues(null));
        assertNull(VectorType.parseValues("1,2,3"));
        assertNull(VectorType.parseValues("[1,2"));
        assertNull(VectorType.parseValues("{1:1.5"));
        assertNull(VectorType.parseValues("{a:1}/3"));
        assertNull(VectorType.parseValues("{1:1}/x"));
        assertNull(VectorType.parseValues("[5,[0,2]]"));
        assertNull(VectorType.parseValues("[x,[0],[1]]"));
        // Indexes and numbers of different counts
        assertNull(VectorType.parseValues("[5,[0,2],[1]]"));
    }

    @Test
    void vectorIndexes() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("m", "16");
        options.put("ef_construction", "64");
        VectorIndex index = new VectorIndex(VectorIndex.Kind.HNSW, VectorIndex.Distance.COSINE, options);
        assertEquals("HNSW,COSINE,m=16;ef_construction=64", index.toString());
        assertEquals(index, VectorIndex.parse(index.toString()));
        assertEquals(index.hashCode(), VectorIndex.parse(index.toString()).hashCode());
        assertEquals("16", index.getOption("M"));
        assertNull(index.getOption("lists"));
        assertEquals("IVF,EUCLIDEAN,", new VectorIndex(VectorIndex.Kind.IVF, VectorIndex.Distance.EUCLIDEAN, null)
                .toString());
        VectorIndex parsed = VectorIndex.parse("ivf, dot, Lists = 100; ; bad");
        assertEquals(VectorIndex.Kind.IVF, parsed.getKind());
        assertEquals(VectorIndex.Distance.DOT, parsed.getDistance());
        assertEquals(Map.of("lists", "100"), parsed.getOptions());
        assertEquals(VectorIndex.parse("HNSW,COSINE"), VectorIndex.parse("HNSW,COSINE,"));
        assertNotEquals(index, "HNSW,COSINE,m=16;ef_construction=64");

        assertNull(VectorIndex.parse(null));
        assertNull(VectorIndex.parse("HNSW"));
        assertNull(VectorIndex.parse("GRAPH,COSINE"));
        assertNull(VectorIndex.parse("HNSW,NEAR"));
        Map<String, Object> detail = new HashMap<>();
        assertNull(VectorIndex.of(detail));
        assertNull(VectorIndex.of(null));
        detail.put(VectorIndex.DETAIL_KEY, "HNSW,EUCLIDEAN,");
        assertEquals(VectorIndex.Distance.EUCLIDEAN, VectorIndex.of(detail).getDistance());
    }
}
