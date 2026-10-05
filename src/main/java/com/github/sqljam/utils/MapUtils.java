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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import lombok.experimental.UtilityClass;

/**
 * @Description: MapUtils
 * @Author: Fred Feng
 * @Date: 20/12/2022
 * @Version 1.0.0
 */
@UtilityClass
public class MapUtils {

    public boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    public boolean isNotEmpty(Map<?, ?> map) {
        return !isEmpty(map);
    }

    public Map<String, Object> linkedCaseInsensitiveMap() {
        return new CaseInsensitiveMap<>(new LinkedHashMap<>());
    }

    public Map<String, Object> concurrentCaseInsensitiveMap() {
        return new CaseInsensitiveMap<>(new ConcurrentHashMap<>());
    }

    public <K, V> V getOrCreate(Map<K, V> map, K key, Supplier<V> supplier) {
        if (map == null) {
            return null;
        }
        V value = map.get(key);
        if (value == null && supplier != null) {
            synchronized (supplier) {
                value = map.get(key);
                if (value == null) {
                    map.putIfAbsent(key, supplier.get());
                }
            }
            value = map.get(key);
        }
        return value;
    }

    public Map<String, String> splitAsMap(String spec, String delimiter, String subDelimiter) {
        if (StringUtils.isBlank(spec)) {
            return Collections.emptyMap();
        }
        return Arrays.stream(spec.split(delimiter)).map(arg -> arg.split(subDelimiter))
                .collect(HashMap::new, (m, args) -> {
                    if (args.length > 1) {
                        m.put(args[0], args[1]);
                    } else {
                        m.put(args[0], null);
                    }
                }, HashMap::putAll);
    }

    public String toString(Map<String, ?> map, String delimiter, String subDelimiter) {
        if (isEmpty(map)) {
            return "";
        }
        return map.entrySet().stream().map(e -> e.getKey() + subDelimiter + e.getValue())
                .collect(Collectors.joining(delimiter));
    }

    public <K, V> Map<K, V> retainAll(Map<K, V> left, Collection<K> keys) {
        if (left == null || left.isEmpty() || keys == null || keys.isEmpty()) {
            return left;
        }
        Map<K, V> result = new HashMap<>(left);
        for (Map.Entry<K, V> e : left.entrySet()) {
            if (!keys.contains(e.getKey())) {
                result.remove(e.getKey());
            }
        }
        return result;
    }

    public static <K, V> void removeKeys(Map<K, V> map, Collection<?> keys) {
        if (CollectionUtils.isNotEmpty(keys)) {
            for (Object key : keys) {
                map.remove(key);
            }
        }
    }

    public static <K, V> void removeKeys(Map<K, V> map, Object[] keys) {
        if (ArrayUtils.isNotEmpty(keys)) {
            for (Object key : keys) {
                map.remove(key);
            }
        }
    }

    public static <K, V> Map<K, V> reverse(Map<K, V> map) {
        if (isEmpty(map)) {
            return Collections.emptyMap();
        }
        List<Map.Entry<K, V>> entries = new ArrayList<Map.Entry<K, V>>(map.entrySet());
        Collections.reverse(entries);
        return entries.stream().collect(LinkedHashMap::new,
                (m, e) -> m.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }
}
