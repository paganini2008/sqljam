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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: VectorType is the type of a vector column of any database: the type of its elements, the number of
 *               its dimensions (0 if it is not fixed) and whether it is sparse. It is kept in the column metadata as
 *               text (FLOAT32,1024,DENSE), so that it is written into the manifest of packages like other details.
 *               Vectors are passed between databases as text: dense [1,2.5,-3], sparse of PostgreSQL {1:1.5,3:2}/5
 *               (indexes from 1) and sparse of Oracle [5,[0,2],[1.5,2]] (indexes from 0).
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class VectorType {

    /**
     * Key of the vector type in the details of a column
     */
    public static final String DETAIL_KEY = "VECTOR_TYPE";

    /**
     * Type of the elements of a vector
     */
    public enum Element {
        FLOAT16, FLOAT32, FLOAT64, INT8, BINARY
    }

    private final Element element;
    private final int dimensions;
    private final boolean sparse;

    public VectorType(Element element, int dimensions, boolean sparse) {
        this.element = Objects.requireNonNull(element);
        this.dimensions = Math.max(dimensions, 0);
        this.sparse = sparse;
    }

    public static VectorType dense(Element element, int dimensions) {
        return new VectorType(element, dimensions, false);
    }

    public Element getElement() {
        return element;
    }

    /**
     * Number of dimensions, 0 if the column takes vectors of any dimensions
     */
    public int getDimensions() {
        return dimensions;
    }

    public boolean isSparse() {
        return sparse;
    }

    /**
     * Whether the elements are exact as 32-bit floats: half precision floats and bytes are, doubles and bits are not
     */
    public boolean isFloat32Compatible() {
        return element == Element.FLOAT16 || element == Element.FLOAT32 || element == Element.INT8;
    }

    /**
     * Vector type of the details of a column, null if the column is not a vector
     */
    public static VectorType of(Map<String, Object> detail) {
        Object value = detail != null ? detail.get(DETAIL_KEY) : null;
        return value != null ? parse(value.toString()) : null;
    }

    /**
     * Vector type of its text, e.g. FLOAT32,1024,DENSE, null if the text is blank or invalid
     */
    public static VectorType parse(String text) {
        String[] parts = StringUtils.defaultString(text).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new VectorType(Element.valueOf(parts[0].trim().toUpperCase(Locale.ENGLISH)),
                    Integer.parseInt(parts[1].trim()), "SPARSE".equalsIgnoreCase(parts[2].trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return element + "," + dimensions + "," + (sparse ? "SPARSE" : "DENSE");
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VectorType)) {
            return false;
        }
        VectorType type = (VectorType) other;
        return element == type.element && dimensions == type.dimensions && sparse == type.sparse;
    }

    @Override
    public int hashCode() {
        return Objects.hash(element, dimensions, sparse);
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /**
     * Elements of a vector by its text, numbers are kept as they are written. Null if the text is not a vector.
     */
    public static Values parseValues(String text) {
        String value = StringUtils.trimToEmpty(text);
        if (value.startsWith("{")) {
            return parsePostgreSqlSparse(value);
        }
        if (!value.startsWith("[") || !value.endsWith("]")) {
            return null;
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.contains("[")) {
            return parseOracleSparse(body);
        }
        String[] items = body.isEmpty() ? new String[0] : body.split(",");
        String[] numbers = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            numbers[i] = items[i].trim();
        }
        return new Values(numbers.length, null, numbers);
    }

    /**
     * {1:1.5,3:2}/5 of pgvector, indexes start from 1
     */
    private static Values parsePostgreSqlSparse(String text) {
        int slash = text.lastIndexOf("}/");
        if (slash < 0) {
            return null;
        }
        try {
            int dimensions = Integer.parseInt(text.substring(slash + 2).trim());
            String body = text.substring(1, slash).trim();
            List<Integer> indexes = new ArrayList<>();
            List<String> numbers = new ArrayList<>();
            for (String item : body.isEmpty() ? new String[0] : body.split(",")) {
                String[] pair = item.split(":");
                indexes.add(Integer.parseInt(pair[0].trim()) - 1);
                numbers.add(pair[1].trim());
            }
            return new Values(dimensions, indexes.stream().mapToInt(Integer::intValue).toArray(),
                    numbers.toArray(new String[0]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * [5,[0,2],[1.5,2]] of Oracle, indexes start from 0
     */
    private static Values parseOracleSparse(String body) {
        int first = body.indexOf('[');
        int second = body.indexOf('[', first + 1);
        if (first < 0 || second < 0) {
            return null;
        }
        try {
            int dimensions = Integer.parseInt(StringUtils.removeEnd(body.substring(0, first).trim(), ",").trim());
            String indexText = StringUtils.substringBetween(body.substring(first), "[", "]").trim();
            String numberText = StringUtils.substringBetween(body.substring(second), "[", "]").trim();
            int[] indexes = indexText.isEmpty() ? new int[0] : Arrays.stream(indexText.split(","))
                    .mapToInt(index -> Integer.parseInt(index.trim())).toArray();
            String[] numbers = numberText.isEmpty() ? new String[0] : Arrays.stream(numberText.split(","))
                    .map(String::trim).toArray(String[]::new);
            return indexes.length == numbers.length ? new Values(dimensions, indexes, numbers) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Elements of a dense or sparse vector
     */
    public static final class Values {

        private final int dimensions;
        private final int[] indexes;
        private final String[] numbers;

        Values(int dimensions, int[] indexes, String[] numbers) {
            this.dimensions = dimensions;
            this.indexes = indexes;
            this.numbers = numbers;
        }

        public int getDimensions() {
            return dimensions;
        }

        public boolean isSparse() {
            return indexes != null;
        }

        /**
         * All elements, zeros of a sparse vector included
         */
        public String[] getDenseNumbers() {
            if (indexes == null) {
                return numbers.clone();
            }
            String[] dense = new String[dimensions];
            Arrays.fill(dense, "0");
            for (int i = 0; i < indexes.length; i++) {
                dense[indexes[i]] = numbers[i];
            }
            return dense;
        }

        /**
         * [1,2.5,-3]
         */
        public String toDenseText() {
            return "[" + String.join(",", getDenseNumbers()) + "]";
        }

        /**
         * {1:1.5,3:2}/5 of pgvector, zeros of a dense vector are left out
         */
        public String toPostgreSqlSparseText() {
            StringBuilder text = new StringBuilder("{");
            forEachNonZero((index, number) -> text.append(text.length() > 1 ? "," : "").append(index + 1).append(':')
                    .append(number));
            return text.append("}/").append(dimensions).toString();
        }

        /**
         * [5,[0,2],[1.5,2]] of Oracle, zeros of a dense vector are left out
         */
        public String toOracleSparseText() {
            List<String> indexTexts = new ArrayList<>();
            List<String> numberTexts = new ArrayList<>();
            forEachNonZero((index, number) -> {
                indexTexts.add(String.valueOf(index));
                numberTexts.add(number);
            });
            return "[" + dimensions + ",[" + String.join(",", indexTexts) + "],[" + String.join(",", numberTexts)
                    + "]]";
        }

        /**
         * Numbers as floats, zeros of a sparse vector included
         */
        public float[] toFloats() {
            String[] dense = getDenseNumbers();
            float[] floats = new float[dense.length];
            for (int i = 0; i < dense.length; i++) {
                floats[i] = Float.parseFloat(dense[i]);
            }
            return floats;
        }

        private void forEachNonZero(NumberConsumer consumer) {
            if (indexes != null) {
                for (int i = 0; i < indexes.length; i++) {
                    consumer.accept(indexes[i], numbers[i]);
                }
                return;
            }
            for (int i = 0; i < numbers.length; i++) {
                if (Double.parseDouble(numbers[i]) != 0) {
                    consumer.accept(i, numbers[i]);
                }
            }
        }

        @FunctionalInterface
        private interface NumberConsumer {
            void accept(int index, String number);
        }
    }
}
