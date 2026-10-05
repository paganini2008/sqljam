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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;

import org.apache.commons.lang3.StringUtils;

import lombok.experimental.UtilityClass;

/**
 * @Description: SqlTextUtils handles end marks and escapes of sql text
 * @Author: Fred Feng
 * @Date: 01/04/2023
 * @Version 1.0.0
 */
@UtilityClass
public class SqlTextUtils {

    public String addEscapeChar(String str, char escapeChar) {
        StringBuilder content = new StringBuilder();
        for (char c : str.toCharArray()) {
            if (c == '\'') {
                content.append(escapeChar);
            }
            content.append(c);
        }
        return content.toString();
    }

    public List<String> addEndMarks(Collection<String> list) {
        List<String> copy = new ArrayList<>(list);
        ListIterator<String> iter = copy.listIterator();
        String sql;
        while (iter.hasNext()) {
            sql = iter.next();
            if (StringUtils.isBlank(sql)) {
                iter.remove();
            } else if(!sql.endsWith(";")){
                iter.set(sql + ";");
            }
        }
        return copy;
    }

    /**
     * Removes the end mark of a statement before executing it by jdbc, PL/SQL blocks keep their end mark.
     */
    public String removeEndMark(String sql) {
        String trimmed = sql.trim();
        if (!trimmed.endsWith(";")) {
            return trimmed;
        }
        String upper = trimmed.toUpperCase();
        if (upper.matches("(?s).*\\bEND\\s*;$") && upper.matches(
                "(?s)^(BEGIN|DECLARE\\s+(?!@)|CREATE\\s+(OR\\s+REPLACE\\s+)?(TRIGGER|PROCEDURE|FUNCTION|PACKAGE)).*")) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.length() - 1);
    }
}
