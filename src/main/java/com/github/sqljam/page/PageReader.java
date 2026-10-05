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
package com.github.sqljam.page;

import java.util.Collections;
import java.util.List;

/**
 * 
 * @Description: PageReader
 * @Author: Fred Feng
 * @Date: 08/10/2024
 * @Version 1.0.0
 */
public interface PageReader<T> extends Countable {

    default List<T> list(int offset, int limit) throws Exception {
        return list(1, offset, limit);
    }

    default List<T> list(int pageNumber, int offset, int limit) throws Exception {
        PageContent<T> pageContent = list(pageNumber, offset, limit, null);
        return pageContent != null ? pageContent.getContent() : Collections.emptyList();
    }

    PageContent<T> list(int pageNumber, int offset, int limit, Object nextToken) throws Exception;

    default PageResponse<T> list(PageRequest pageRequest) {
        return new SimplePageResponse<T>(pageRequest, this);
    }
}
