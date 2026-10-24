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
package com.github.sqljam.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import lombok.experimental.UtilityClass;

/**
 * @Description: FloatUtils converts floats of 16 bits to floats of 32 bits: IEEE 754 half precision (float16) and
 *               bfloat16, the upper 16 bits of a float, both of the binaries of vectors in little endian order
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@UtilityClass
public class FloatUtils {

    /**
     * Float of the bits of a half precision float, subnormal numbers, infinities and NaN included
     */
    public float halfToFloat(int bits) {
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1F;
        int mantissa = bits & 0x3FF;
        if (exponent == 0x1F) {
            // Infinity or NaN
            return Float.intBitsToFloat(sign | 0x7F800000 | (mantissa << 13));
        }
        if (exponent == 0) {
            if (mantissa == 0) {
                return Float.intBitsToFloat(sign);
            }
            // Subnormal: mantissa / 2^24
            float value = mantissa / 16777216f;
            return sign != 0 ? -value : value;
        }
        return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
    }

    /**
     * Float of the bits of a bfloat16
     */
    public float bfloat16ToFloat(int bits) {
        return Float.intBitsToFloat((bits & 0xFFFF) << 16);
    }

    /**
     * Floats of little endian half precision floats
     */
    public float[] halvesToFloats(byte[] bytes) {
        return toFloats(bytes, false);
    }

    /**
     * Floats of little endian bfloat16 floats
     */
    public float[] bfloat16sToFloats(byte[] bytes) {
        return toFloats(bytes, true);
    }

    private float[] toFloats(byte[] bytes, boolean bfloat16) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 2];
        for (int i = 0; i < floats.length; i++) {
            int bits = buffer.getShort(i * 2) & 0xFFFF;
            floats[i] = bfloat16 ? bfloat16ToFloat(bits) : halfToFloat(bits);
        }
        return floats;
    }

    /**
     * Floats of little endian 32-bit floats
     */
    public float[] littleEndianToFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat(i * Float.BYTES);
        }
        return floats;
    }
}
