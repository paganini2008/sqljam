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

import org.apache.commons.lang3.StringUtils;
import lombok.experimental.UtilityClass;

/**
 * @Description: StringHelper pads and repeats strings for pretty scripts
 * @Author: Fred Feng
 * @Date: 24/03/2023
 * @Version 1.0.0
 */
@UtilityClass
public class StringHelper {

    public String textRight(String str, int length) {
        return textRight(str, ' ', length);
    }

    public String textRight(String str, char place, int length) {
        return textRight(str, 0, place, length);
    }

    public String textRight(String str, int padding, int length) {
        return textRight(str, padding, ' ', length);
    }

    public String textRight(String str, int padding, char place, int length) {
        if (padding > 0) {
            str = str.concat(repeat(place, padding));
        }
        int remaining = length - str.length();
        if (remaining <= 0) {
            return str;
        }
        return repeat(place, remaining).concat(str);
    }

    public String textLeft(String str, int length) {
        return textLeft(str, ' ', length);
    }

    public String textLeft(String str, char place, int length) {
        return textLeft(str, 0, place, length);
    }

    public String textLeft(String str, int padding, int length) {
        return textLeft(str, padding, ' ', length);
    }

    public String textLeft(String str, int padding, char place, int length) {
        if (padding > 0) {
            str = repeat(place, padding).concat(str);
        }
        int remaining = length - str.length();
        if (remaining <= 0) {
            return str;
        }
        return str.concat(repeat(place, remaining));
    }

    public String textMiddle(String str, int length) {
        return textMiddle(str, ' ', length);
    }

    public String textMiddle(String str, char place, int length) {
        int remaining = length - str.length();
        if (remaining <= 0) {
            return str;
        }
        if (remaining == 1) {
            return String.valueOf(place).concat(str);
        } else if (remaining == 2) {
            return String.valueOf(place).concat(str).concat(String.valueOf(place));
        }
        int n = remaining / 2;
        int m = remaining % 2;
        return repeat(place, n + m).concat(str).concat(repeat(place, n));
    }

    public String repeat(char c, int count) {
        return repeat(String.valueOf(c), count);
    }

    public String repeat(String str, int count) {
        return repeat(str, null, count);
    }

    public String repeat(String str, String delim, int count) {
        if (count == 1) {
            return str;
        }
        boolean hasDelim = StringUtils.isNotBlank(delim);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(str);
            if (hasDelim && i != count - 1) {
                result.append(delim);
            }
        }
        return result.toString();
    }

    public String replaceOnce(String template, String placeholder, String replacement) {
        if (template == null) {
            return null;
        }
        int loc = template.indexOf(placeholder);
        if (loc < 0) {
            return template;
        } else {
            return template.substring(0, loc) + replacement + template.substring(loc + placeholder.length());
        }
    }
}