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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.sqljam.utils.RestClient;

/**
 * @Description: VdbCoreTest tests the parts of the vdb line without servers: conditions of text, the columns of
 *               payloads without a schema, the text of vectors, collections and sources
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbCoreTest {

    static JsonNode json(String text) throws Exception {
        return RestClient.getMapper().readTree(text);
    }

    @Test
    void parsesConditions() {
        VdbQuery query = VdbQuery.all().where("n >= 6 AND title = 'rock and roll'\nlang IN ('en', 'zh')\n\n"
                + "score < 1.5 and active != true and x <> \"y\" and k > -3 and v <= 2 and w = abc");
        List<VdbQuery.Condition> conditions = query.getConditions();
        assertEquals(9, conditions.size());
        assertEquals(VdbQuery.Operator.GE, conditions.get(0).getOperator());
        assertEquals(6L, conditions.get(0).getValue());
        assertEquals("rock and roll", conditions.get(1).getValue());
        assertEquals(VdbQuery.Operator.IN, conditions.get(2).getOperator());
        assertEquals(List.of("en", "zh"), conditions.get(2).getValue());
        assertEquals(1.5, conditions.get(3).getValue());
        assertEquals(VdbQuery.Operator.LT, conditions.get(3).getOperator());
        assertEquals(Boolean.TRUE, conditions.get(4).getValue());
        assertEquals(VdbQuery.Operator.NE, conditions.get(5).getOperator());
        assertEquals("y", conditions.get(5).getValue());
        assertEquals(VdbQuery.Operator.GT, conditions.get(6).getOperator());
        assertEquals(-3L, conditions.get(6).getValue());
        assertEquals(VdbQuery.Operator.LE, conditions.get(7).getOperator());
        assertEquals(VdbQuery.Operator.EQ, conditions.get(8).getOperator());
        assertEquals("abc", conditions.get(8).getValue());
        assertTrue(query.isFiltered());
        assertFalse(query.isNative());
        assertFalse(query.isNearest());
        assertThrows(IllegalArgumentException.class, () -> VdbQuery.all().where("n ~ 3"));
        assertFalse(VdbQuery.all().where(null).isFiltered());
        assertEquals("it''s".replace("''", "'"), VdbQuery.parseValue("'it''s'"));

        VdbQuery nearest = new VdbQuery();
        nearest.setNearestField("embedding");
        assertFalse(nearest.isNearest());
        nearest.setNearestId(1);
        assertTrue(nearest.isNearest());
        nearest.setNativeFilter(" ");
        assertFalse(nearest.isNative());
    }

    @Test
    void findsColumnsOfPayloads() throws Exception {
        PayloadSchema schema = new PayloadSchema();
        assertTrue(schema.isEmpty());
        schema.observe(json("{\"a\": 1, \"b\": \"x\", \"c\": true, \"d\": 1.5, \"e\": {\"k\": 1}, \"f\": null,"
                + " \"g\": 1, \"h\": 1, \"i\": \"" + "y".repeat(5000) + "\"}"));
        schema.observe(json("{\"a\": 10000000000, \"b\": \"" + "z".repeat(300) + "\", \"c\": 1, \"d\": 2,"
                + " \"e\": 3, \"g\": 2.5, \"h\": \"text\", \"new\": 1}"));
        schema.observe(json("[1]"));
        schema.observe(null);
        VdbCollection collection = new VdbCollection("c");
        collection.addField(new VdbField("a", VdbField.Kind.TEXT));
        Map<String, VdbField> fields = new java.util.LinkedHashMap<>();
        schema.getFields(collection).forEach(field -> fields.put(field.getSourceName(), field));
        assertEquals("a_2", fields.get("a").getName());
        assertEquals(VdbField.Kind.BIGINT, fields.get("a").getKind());
        assertEquals(VdbField.Kind.VARCHAR, fields.get("b").getKind());
        assertEquals(300, fields.get("b").getSize());
        assertFalse(fields.get("b").isNullable());
        assertEquals(VdbField.Kind.TEXT, fields.get("c").getKind());
        assertEquals(VdbField.Kind.DOUBLE, fields.get("d").getKind());
        assertEquals(VdbField.Kind.JSON, fields.get("e").getKind());
        assertEquals(VdbField.Kind.TEXT, fields.get("f").getKind());
        assertTrue(fields.get("f").isNullable());
        assertEquals(VdbField.Kind.DOUBLE, fields.get("g").getKind());
        assertEquals(VdbField.Kind.TEXT, fields.get("h").getKind());
        assertEquals(VdbField.Kind.TEXT, fields.get("i").getKind());
        assertTrue(fields.get("i").isNullable());
        assertTrue(fields.get("new").isNullable());
        assertFalse(schema.isEmpty());
    }

    @Test
    void writesVectors() throws Exception {
        assertEquals("[1.0,-2.5]", VdbValues.toDenseText(new float[]{1, -2.5f}));
        assertEquals("[1.0,2.0]", VdbValues.toDenseText(json("[1, 2]")));
        assertNull(VdbValues.toDenseText(json("[]")));
        assertNull(VdbValues.toDenseText(json("[\"a\"]")));
        assertNull(VdbValues.toDenseText((JsonNode) null));
        assertEquals("[0,255]", VdbValues.toByteText(new byte[]{0, (byte) 255}));
        Map<Long, Float> elements = VdbValues.getSparseElements(json("[3, 1]"), json("[0.5, 2]"));
        assertEquals("{2:2.0,4:0.5}/10", VdbValues.toSparseText(elements, 10));
        assertEquals(elements, VdbValues.getSparseElements(json("{\"3\": 0.5, \"1\": 2}")));
        assertTrue(VdbValues.getSparseElements(null, null).isEmpty());
        assertTrue(VdbValues.getSparseElements(json("[]")).isEmpty());
        // 1.0 and -2.0 of 16-bit floats and of bfloat16
        assertArrayEquals(new float[]{1, -2}, VdbValues.decodeHalves("ADwAwA==", false));
        assertArrayEquals(new float[]{1, -2}, VdbValues.decodeHalves("gD8AwA==", true));
        assertNull(VdbValues.toJson(null));
        assertNull(VdbValues.toJson(json("null")));
        assertEquals("{\"a\":[1]}", VdbValues.toJson(Map.of("a", List.of(1))));
        assertThrows(VdbException.class, () -> VdbValues.toJson(new Object()));
    }

    @Test
    void describesCollections() {
        VdbCollection collection = new VdbCollection("c");
        VdbField id = VdbField.varchar("id", 36);
        id.setPrimaryKey(true);
        collection.addField(id).addField(VdbField.vector("v", VectorType.dense(VectorType.Element.FLOAT32, 3)));
        assertEquals(List.of(id), collection.getPrimaryKeyFields());
        assertEquals(1, collection.getVectorFields().size());
        assertEquals("V_2", collection.getUnusedName("V"));
        assertEquals("w", collection.getUnusedName("w"));
        assertTrue(collection.findField("id").isPresent());
        assertEquals("id", id.getSourceName());
        id.setSourceName("_id");
        assertEquals("_id", id.getSourceName());
        assertTrue(id.toString().contains("id"));

        VdbSource source = new VdbSource(VdbType.QDRANT, "http://h:6333");
        source.setApiKey("secret");
        source.setPassword("secret");
        assertFalse(source.toString().contains("secret"));
        assertEquals("https://h:9200", VdbType.OPENSEARCH.getUrl("h", 0));
        assertEquals("http://h:1", VdbType.CHROMA.getUrl("h", 1));
        for (VdbType type : VdbType.values()) {
            assertTrue(type.getDefaultPort() > 0);
            assertTrue(type.getDisplayName().length() > 3);
            assertEquals(type == VdbType.OPENSEARCH, type.isSecureByDefault());
        }
        VdbException e = new VdbException("m", new IllegalStateException());
        assertEquals("m", e.getMessage());
    }

    @Test
    void parsesVectorTypes() {
        VectorType type = VectorType.parse("FLOAT16,8,SPARSE");
        assertEquals(VectorType.Element.FLOAT16, type.getElement());
        assertTrue(type.isSparse());
        assertEquals(type, VectorType.parse(type.toString()));
        assertNull(VectorType.parse("bad"));
        assertNull(VectorType.parse(null));
        VectorType.Values values = VectorType.parseValues("{1:0.5,3:2}/4");
        assertTrue(values.isSparse());
        assertEquals(4, values.getDimensions());
        assertEquals("[4,[0,2],[0.5,2]]", values.toOracleSparseText());
        assertArrayEquals(new float[]{0.5f, 0, 2, 0}, values.toFloats());
        VectorType.Values dense = VectorType.parseValues("[1, 2.5]");
        assertEquals("[1,2.5]", dense.toDenseText());
        assertNull(VectorType.parseValues("x"));
        VectorIndex index = VectorIndex.parse(new VectorIndex(VectorIndex.Kind.HNSW, VectorIndex.Distance.DOT,
                Map.of("m", "8")).toString());
        assertEquals(VectorIndex.Distance.DOT, index.getDistance());
        assertEquals("8", index.getOption("m"));
    }
}
