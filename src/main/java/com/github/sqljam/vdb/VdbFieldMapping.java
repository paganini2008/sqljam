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

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import lombok.Getter;
import lombok.ToString;

/**
 * @Description: VdbFieldMapping is the target column of a field of a collection which the user chose: another name,
 *               a type of the target written as it is, or no column. A field without a mapping keeps its name and
 *               its type, the id of a collection can not be skipped.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@ToString
public class VdbFieldMapping {

    private final String columnName;
    private final String typeName;
    private final boolean skipped;

    public VdbFieldMapping(String columnName, String typeName, boolean skipped) {
        this.columnName = StringUtils.trimToNull(columnName);
        this.typeName = StringUtils.trimToNull(typeName);
        this.skipped = skipped;
    }

    public static VdbFieldMapping skip() {
        return new VdbFieldMapping(null, null, true);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VdbFieldMapping)) {
            return false;
        }
        VdbFieldMapping mapping = (VdbFieldMapping) other;
        return skipped == mapping.skipped && Objects.equals(columnName, mapping.columnName) && Objects.equals(
                typeName, mapping.typeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnName, typeName, skipped);
    }
}
