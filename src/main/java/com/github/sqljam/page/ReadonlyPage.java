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

import java.util.List;

/**
 * @Description: ReadonlyPage
 * @Author: Fred Feng
 * @Date: 08/03/2023
 * @Version 1.0.0
 */
public class ReadonlyPage<T> implements EachPage<T> {

    private final PageContent<T> content;
    private final PageResponse<T> pageResponse;

    ReadonlyPage(PageContent<T> content, PageResponse<T> pageResponse) {
        this.content = content;
        this.pageResponse = pageResponse;
    }

    @Override
    public boolean isEmpty() {
        return pageResponse.isEmpty();
    }

    @Override
    public boolean isLastPage() {
        return pageResponse.isLastPage();
    }

    @Override
    public boolean isFirstPage() {
        return pageResponse.isFirstPage();
    }

    @Override
    public boolean hasNextPage() {
        return pageResponse.hasNextPage();
    }

    @Override
    public boolean hasPreviousPage() {
        return pageResponse.hasPreviousPage();
    }

    @Override
    public int getTotalPages() {
        return pageResponse.getTotalPages();
    }

    @Override
    public long getTotalRecords() {
        return pageResponse.getTotalRecords();
    }

    @Override
    public int getOffset() {
        return pageResponse.getOffset();
    }

    @Override
    public int getPageSize() {
        return pageResponse.getPageSize();
    }

    @Override
    public int getPageNumber() {
        return pageResponse.getPageNumber();
    }

    @Override
    public List<T> getContent() {
        return content.getContent();
    }

    @Override
    public Object getNextToken() {
        return content.getNextToken();
    }
}
