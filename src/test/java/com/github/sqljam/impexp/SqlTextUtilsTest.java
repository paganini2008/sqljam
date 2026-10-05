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

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * @Description: SqlTextUtilsTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
class SqlTextUtilsTest {

    @Test
    void addEscapeChar() {
        assertEquals("O''Brien", SqlTextUtils.addEscapeChar("O'Brien", '\''));
    }

    @Test
    void addEndMarks() {
        assertEquals(Arrays.asList("a;", "b;"), SqlTextUtils.addEndMarks(Arrays.asList("a", " ", "b;", null)));
    }

    @Test
    void removeEndMark() {
        assertEquals("SELECT 1", SqlTextUtils.removeEndMark("SELECT 1;"));
        assertEquals("SELECT 1", SqlTextUtils.removeEndMark(" SELECT 1 "));
        assertEquals("BEGIN NULL; END;", SqlTextUtils.removeEndMark("BEGIN NULL; END;"));
        assertEquals("CREATE OR REPLACE TRIGGER t BEGIN NULL; END;",
                SqlTextUtils.removeEndMark("CREATE OR REPLACE TRIGGER t BEGIN NULL; END;"));
        assertEquals("IF 1 = 1 BEGIN SELECT 1; END", SqlTextUtils.removeEndMark("IF 1 = 1 BEGIN SELECT 1; END;"));
        assertEquals("DECLARE @a INT = 1; SELECT @a", SqlTextUtils.removeEndMark("DECLARE @a INT = 1; SELECT @a;"));
    }
}
