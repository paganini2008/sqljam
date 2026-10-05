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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * @Description: UtilsTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class UtilsTest {

    @Test
    void convert() {
        assertNull(ConvertUtils.convert(null, Long.class));
        assertEquals("1", ConvertUtils.convert(1, String.class));
        assertEquals(1L, ConvertUtils.convert(1, Long.class));
        assertEquals(1L, ConvertUtils.convert("1", long.class));
        assertEquals(2, ConvertUtils.convert(new BigDecimal("2.0"), Integer.class));
        assertEquals((short) 3, ConvertUtils.convert(3L, Short.class));
        assertEquals(1.5, ConvertUtils.convert("1.5", Double.class));
        assertEquals(1.5f, ConvertUtils.convert(1.5, Float.class));
        assertEquals(new BigDecimal("7"), ConvertUtils.convert(7, BigDecimal.class));
        assertEquals(BigInteger.TEN, ConvertUtils.convert(10, BigInteger.class));
        assertEquals(true, ConvertUtils.convert(1, Boolean.class));
        assertEquals(Boolean.TRUE, ConvertUtils.convert(Boolean.TRUE, Boolean.class));
        assertThrows(IllegalArgumentException.class, () -> ConvertUtils.convert(new Object(), Long.class));
        assertThrows(IllegalArgumentException.class, () -> ConvertUtils.convert(1, java.util.Date.class));
    }

    @Test
    void caseInsensitiveMap() {
        Map<String, Object> map = new CaseInsensitiveMap<>();
        map.put("Name", 1);
        assertEquals(1, map.get("NAME"));
        assertTrue(map.containsKey("name"));
        assertEquals(Arrays.asList("Name"), Arrays.asList(map.keySet().toArray()));
        assertEquals(1, map.entrySet().size());
        assertEquals(1, map.remove("NAME"));
        assertNull(map.remove("missing"));
        assertNull(map.get("missing"));
        map.put(null, 2);
        assertEquals(2, map.get(null));
        map.clear();
        assertTrue(map.isEmpty());
        Map<String, Object> initial = new HashMap<>();
        initial.put("A", 1);
        assertEquals(1, new CaseInsensitiveMap<>(initial).get("a"));
    }

    @Test
    void maps() {
        assertTrue(MapUtils.isEmpty(null));
        assertTrue(MapUtils.isNotEmpty(Map.of("a", 1)));
        Map<String, Object> linked = MapUtils.linkedCaseInsensitiveMap();
        linked.put("A", 1);
        assertEquals(1, linked.get("a"));
        MapUtils.concurrentCaseInsensitiveMap().put("B", 2);
        Map<String, List<Integer>> groups = new HashMap<>();
        MapUtils.getOrCreate(groups, "x", java.util.ArrayList::new).add(1);
        MapUtils.getOrCreate(groups, "x", java.util.ArrayList::new).add(2);
        assertEquals(List.of(1, 2), groups.get("x"));
        assertNull(MapUtils.getOrCreate(null, "x", () -> 1));
        Map<String, String> split = MapUtils.splitAsMap("a=1&b", "&", "=");
        assertEquals("1", split.get("a"));
        assertTrue(split.containsKey("b"));
        assertTrue(MapUtils.splitAsMap(" ", "&", "=").isEmpty());
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", 1);
        ordered.put("b", 2);
        assertEquals("a=1&b=2", MapUtils.toString(ordered, "&", "="));
        assertEquals("", MapUtils.toString(null, "&", "="));
        assertEquals(Map.of("a", 1), MapUtils.retainAll(ordered, List.of("a")));
        assertEquals(ordered, MapUtils.retainAll(ordered, List.of()));
        assertEquals(Arrays.asList("b", "a"), Arrays.asList(MapUtils.reverse(ordered).keySet().toArray()));
        assertTrue(MapUtils.reverse(null).isEmpty());
        Map<String, Object> removable = new HashMap<>(ordered);
        MapUtils.removeKeys(removable, List.of("a"));
        MapUtils.removeKeys(removable, new Object[]{"b"});
        assertTrue(removable.isEmpty());
    }

    @Test
    void observable() {
        Observable observable = new Observable();
        AtomicInteger notified = new AtomicInteger();
        Observer observer = (o, arg) -> notified.incrementAndGet();
        observable.addObserver(observer);
        observable.addObserver(observer);
        assertEquals(1, observable.countObservers());
        observable.notifyObservers();
        assertEquals(0, notified.get());
        assertFalse(observable.hasChanged());
        observable.deleteObserver(observer);
        observable.deleteObservers();
        assertEquals(0, observable.countObservers());
        assertThrows(NullPointerException.class, () -> observable.addObserver(null));
    }
}
