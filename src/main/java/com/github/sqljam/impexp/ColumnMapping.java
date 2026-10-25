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

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: ColumnMapping is the target of a column of a source table which the user chose: another name, a
 *               type of the target database written as it is, or no column at all. A column without a mapping keeps
 *               its name and its translated type, so an import without mappings is the import of before.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Getter
@Setter
@ToString
public class ColumnMapping {

    /**
     * Name of the target column, null for the name of the source column
     */
    private String targetName;
    /**
     * Type of the target column in the language of the target database, null for the translated type
     */
    private String targetType;
    /**
     * The column is not read and not written
     */
    private boolean skipped;

    public ColumnMapping() {
    }

    public ColumnMapping(String targetName, String targetType, boolean skipped) {
        this.targetName = StringUtils.trimToNull(targetName);
        this.targetType = StringUtils.trimToNull(targetType);
        this.skipped = skipped;
    }

    public static ColumnMapping skip() {
        return new ColumnMapping(null, null, true);
    }

    /**
     * Whether the mapping changes nothing: same name, translated type, not skipped
     */
    public boolean isIdentity() {
        return !skipped && StringUtils.isBlank(targetName) && StringUtils.isBlank(targetType);
    }

    /**
     * Name of the target column of the source column
     */
    public String getTargetName(String sourceName) {
        return StringUtils.defaultIfBlank(targetName, sourceName);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ColumnMapping)) {
            return false;
        }
        ColumnMapping mapping = (ColumnMapping) other;
        return skipped == mapping.skipped && Objects.equals(targetName, mapping.targetName) && Objects.equals(
                targetType, mapping.targetType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetName, targetType, skipped);
    }
}
