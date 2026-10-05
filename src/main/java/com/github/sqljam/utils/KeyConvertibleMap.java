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

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @Description: KeyConvertibleMap
 * @Author: Fred Feng
 * @Date: 24/03/2023
 * @Version 1.0.0
 */
public abstract class KeyConvertibleMap<K, V> extends AbstractMap<K, V>
        implements Map<K, V>, Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<K, V> delegate;
    private final Map<Object, K> keys;

    protected KeyConvertibleMap(Map<K, V> delegate) {
        this.delegate = delegate;
        this.keys = Collections.synchronizedMap(new HashMap<>());

        if (delegate.size() > 0) {
            delegate.entrySet().forEach(e -> {
                K key = e.getKey();
                keys.put(convertKey(key), key);
            });
        }
    }

    @Override
    public boolean containsKey(Object key) {
        Object convertedKey = convertKey(key);
        return keys.containsKey(convertedKey) && delegate.containsKey(keys.get(convertedKey));
    }

    @Override
    public V get(Object key) {
        Object convertedKey = convertKey(key);
        return keys.containsKey(convertedKey) ? delegate.get(keys.get(convertedKey)) : null;
    }

    @Override
    public V put(K key, V value) {
        keys.put(convertKey(key), key);
        return delegate.put(key, value);
    }

    @Override
    public V remove(Object key) {
        Object convertedKey = convertKey(key);
        if (!keys.containsKey(convertedKey)) {
            return null;
        }
        return delegate.remove(keys.remove(convertedKey));
    }

    @Override
    public void clear() {
        keys.clear();
        delegate.clear();
    }

    @Override
    public Set<K> keySet() {
        return delegate.keySet();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }

    protected abstract Object convertKey(Object key);
}
