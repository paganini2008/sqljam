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

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.sqljam.utils.FloatUtils;
import com.github.sqljam.utils.RestClient;
import lombok.experimental.UtilityClass;

/**
 * @Description: VdbValues converts the values of vector databases into the values of records: vectors as text
 *               (dense [1.0,2.5,-3.0], sparse {1:1.5,3:2.0}/5 with indexes from 1), binaries of 16-bit floats
 *               (Float16Vector and BFloat16Vector of Milvus, base64) as floats, JSON as text.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@UtilityClass
public class VdbValues {

    /**
     * Text of a dense vector, e.g. [1.0,2.5,-3.0]
     */
    public String toDenseText(float[] values) {
        StringBuilder text = new StringBuilder(values.length * 12 + 2).append('[');
        for (int i = 0; i < values.length; i++) {
            text.append(i > 0 ? "," : "").append(values[i]);
        }
        return text.append(']').toString();
    }

    /**
     * Text of a dense vector of a JSON array of numbers, null if it is not one
     */
    public String toDenseText(JsonNode array) {
        if (array == null || !array.isArray() || array.size() == 0 || !array.get(0).isNumber()) {
            return null;
        }
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).floatValue();
        }
        return toDenseText(values);
    }

    /**
     * Text of a vector of bytes of unsigned integers, e.g. a binary vector of 16 bits [255,0]
     */
    public String toByteText(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 4 + 2).append('[');
        for (int i = 0; i < bytes.length; i++) {
            text.append(i > 0 ? "," : "").append(bytes[i] & 0xFF);
        }
        return text.append(']').toString();
    }

    /**
     * Text of a sparse vector by its elements of indexes from 0
     *
     * @param dimensions dimensions of the vector, larger than every index
     */
    public String toSparseText(Map<Long, Float> elements, long dimensions) {
        StringBuilder text = new StringBuilder("{");
        new TreeMap<>(elements).forEach((index, value) -> text.append(text.length() > 1 ? "," : "")
                .append(index + 1).append(':').append(value));
        return text.append("}/").append(dimensions).toString();
    }

    /**
     * Elements of a sparse vector of Qdrant and Pinecone: {"indices": [..], "values": [..]}
     */
    public Map<Long, Float> getSparseElements(JsonNode indices, JsonNode values) {
        Map<Long, Float> elements = new TreeMap<>();
        if (indices != null && values != null) {
            for (int i = 0; i < Math.min(indices.size(), values.size()); i++) {
                elements.put(indices.get(i).asLong(), values.get(i).floatValue());
            }
        }
        return elements;
    }

    /**
     * Elements of a sparse vector of Milvus: {"index": value}
     */
    public Map<Long, Float> getSparseElements(JsonNode object) {
        Map<Long, Float> elements = new TreeMap<>();
        if (object != null && object.isObject()) {
            object.fields().forEachRemaining(entry -> elements.put(Long.parseLong(entry.getKey()),
                    entry.getValue().floatValue()));
        }
        return elements;
    }

    /**
     * Floats of a base64 binary of 16-bit floats, half precision or bfloat16
     */
    public float[] decodeHalves(String base64, boolean bfloat16) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        return bfloat16 ? FloatUtils.bfloat16sToFloats(bytes) : FloatUtils.halvesToFloats(bytes);
    }

    /**
     * JSON text of a value, null of null
     */
    public String toJson(Object value) {
        if (value == null || (value instanceof JsonNode && ((JsonNode) value).isNull())) {
            return null;
        }
        try {
            return RestClient.getMapper().writeValueAsString(value);
        } catch (IOException e) {
            throw new VdbException("Unable to write JSON: " + e.getMessage(), e);
        }
    }
}
