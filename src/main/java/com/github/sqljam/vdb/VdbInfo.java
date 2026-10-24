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

import java.util.Locale;
import java.util.concurrent.Callable;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.utils.RestException;

/**
 * @Description: VdbInfo reads the items of the overview and of the health of a vector database one by one: an item
 *               which is not readable (no privilege, an endpoint of another version) tells why instead of failing
 *               the others
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class VdbInfo {

    public static final String UNAVAILABLE = "Unavailable: ";

    private VdbInfo() {
    }

    public static String read(Callable<?> item) {
        try {
            Object value = item.call();
            return value == null || StringUtils.isBlank(value.toString()) ? "-" : value.toString();
        } catch (RestException e) {
            return UNAVAILABLE + (e.isAccessDenied() ? "no privilege, HTTP " + e.getStatus() : e.getStatus() > 0
                    ? "HTTP " + e.getStatus() : "not reachable");
        } catch (Exception e) {
            return UNAVAILABLE + StringUtils.abbreviate(StringUtils.defaultString(e.getMessage(), e.getClass()
                    .getSimpleName()), 200);
        }
    }

    /**
     * Bytes as KB, MB or GB
     */
    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ENGLISH, "%.1f %s", value, units[unit]);
    }
}
