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

import java.math.BigDecimal;
import java.math.BigInteger;
import lombok.experimental.UtilityClass;

/**
 * @Description: ConvertUtils (minimal replacement of doodler ConvertUtils for scalar values)
 * @Author: Fred Feng
 * @Version 1.0.0
 */
@UtilityClass
public class ConvertUtils {

    @SuppressWarnings("unchecked")
    public <T> T convert(Object value, Class<T> requiredType) {
        if (value == null || requiredType.isInstance(value)) {
            return (T) value;
        }
        if (requiredType == String.class) {
            return (T) value.toString();
        }
        if (value instanceof Number || value instanceof CharSequence) {
            String str = value.toString().trim();
            BigDecimal number = value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(str);
            if (requiredType == Long.class || requiredType == long.class) {
                return (T) Long.valueOf(number.longValue());
            } else if (requiredType == Integer.class || requiredType == int.class) {
                return (T) Integer.valueOf(number.intValue());
            } else if (requiredType == Short.class || requiredType == short.class) {
                return (T) Short.valueOf(number.shortValue());
            } else if (requiredType == Double.class || requiredType == double.class) {
                return (T) Double.valueOf(number.doubleValue());
            } else if (requiredType == Float.class || requiredType == float.class) {
                return (T) Float.valueOf(number.floatValue());
            } else if (requiredType == BigDecimal.class) {
                return (T) number;
            } else if (requiredType == BigInteger.class) {
                return (T) number.toBigInteger();
            } else if (requiredType == Boolean.class || requiredType == boolean.class) {
                return (T) Boolean.valueOf(number.intValue() != 0);
            }
        }
        if ((requiredType == Boolean.class || requiredType == boolean.class) && value instanceof Boolean) {
            return (T) value;
        }
        throw new IllegalArgumentException(
                String.format("Cannot convert %s to %s", value.getClass().getName(), requiredType.getName()));
    }
}
