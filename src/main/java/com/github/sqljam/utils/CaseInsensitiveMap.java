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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @Description: CaseInsensitiveMap
 * @Author: Fred Feng
 * @Date: 24/03/2023
 * @Version 1.0.0
 */
public class CaseInsensitiveMap<T> extends KeyConvertibleMap<String, T> {

    private static final long serialVersionUID = 7726273319207033245L;

    public CaseInsensitiveMap() {
        this(new HashMap<>());
    }

    public CaseInsensitiveMap(Map<String, T> delegate) {
        super(delegate);
    }

    @Override
    protected Object convertKey(Object key) {
        if (key != null) {
            return key.toString().toLowerCase(Locale.ENGLISH);
        }
        return "";
    }
}