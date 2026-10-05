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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * @Description: StringHelperTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class StringHelperTest {

    @Test
    void pad() {
        assertEquals("ab   ", StringHelper.textLeft("ab", 5));
        assertEquals("--ab---", StringHelper.textLeft("ab", 2, '-', 7));
        assertEquals("abcdef", StringHelper.textLeft("abcdef", 3));
        assertEquals("   ab", StringHelper.textRight("ab", 5));
        assertEquals("**ab**", StringHelper.textRight("ab", 2, '*', 6));
        assertEquals("abc", StringHelper.textRight("abc", 2));
        assertEquals(" ab", StringHelper.textMiddle("ab", 3));
        assertEquals(" ab ", StringHelper.textMiddle("ab", 4));
        assertEquals("  ab ", StringHelper.textMiddle("ab", 5));
        assertEquals("abc", StringHelper.textMiddle("abc", 2));
        assertEquals("ab ", StringHelper.textLeft("ab", 0, 3));
        assertEquals(" ab", StringHelper.textRight("ab", 0, 3));
    }

    @Test
    void repeatAndReplace() {
        assertEquals("a,a,a", StringHelper.repeat("a", ",", 3));
        assertEquals("aaa", StringHelper.repeat('a', 3));
        assertEquals("x", StringHelper.repeat("x", 1));
        assertEquals("numeric(10,2)", StringHelper.replaceOnce("numeric($p,2)", "$p", "10"));
        assertEquals("text", StringHelper.replaceOnce("text", "$p", "10"));
        assertNull(StringHelper.replaceOnce(null, "$p", "10"));
    }
}
