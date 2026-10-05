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

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * @Description: TypeNames maps jdbc type codes to type names of a dialect, optionally by capacity
 * @Author: Fred Feng
 * @Date: 24/03/2023
 * @Version 1.0.0
 */
public final class TypeNames {

    private final Map<Integer, String> defaults = new HashMap<Integer, String>();

    private final Map<Integer, Map<Long, String>> weighted = new HashMap<Integer, Map<Long, String>>();

    public String get(final int typeCode) throws TypeMappingException {
        final Integer integer = Integer.valueOf(typeCode);
        final String result = defaults.get(integer);
        if (result == null) {
            throw new TypeMappingException("No Dialect mapping for JDBC type: " + typeCode);
        }
        return result;
    }

    public String get(int typeCode, long size, int precision, int scale) throws TypeMappingException {
        final Integer integer = Integer.valueOf(typeCode);
        final Map<Long, String> map = weighted.get(integer);
        if (map != null && map.size() > 0) {
            for (Map.Entry<Long, String> entry : map.entrySet()) {
                if (size <= entry.getKey()) {
                    return replace(entry.getValue(), size, precision, scale);
                }
            }
        }
        return replace(get(typeCode), size, precision, scale);
    }

    private static String replace(String type, long size, int precision, int scale) {
        type = StringHelper.replaceOnce(type, "$s", Integer.toString(scale));
        type = StringHelper.replaceOnce(type, "$l", Long.toString(size));
        return StringHelper.replaceOnce(type, "$p", Integer.toString(precision));
    }

    public void put(int typeCode, long capacity, String value) {
        final Integer integer = Integer.valueOf(typeCode);
        Map<Long, String> map = weighted.get(integer);
        if (map == null) {
            map = new TreeMap<Long, String>();
            weighted.put(integer, map);
        }
        map.put(capacity, value);
    }

    public void put(int typeCode, String value) {
        final Integer integer = Integer.valueOf(typeCode);
        defaults.put(integer, value);
    }

    public boolean containsTypeName(final String typeName) {
        return this.defaults.containsValue(typeName);
    }
}