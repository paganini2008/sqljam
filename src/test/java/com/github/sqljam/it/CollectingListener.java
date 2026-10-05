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
package com.github.sqljam.it;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.sqljam.impexp.ExportListener;

/**
 * @Description: CollectingListener collects errors and messages of an export
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class CollectingListener implements ExportListener {

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean cancelled;
    private volatile Boolean successful;
    private volatile long processed = -1;
    private volatile long total = -1;

    @Override
    public void onProgress(long processed, long total) {
        if (processed < this.processed && total == this.total) {
            throw new IllegalStateException("Progress goes backwards: " + processed + " < " + this.processed);
        }
        this.processed = processed;
        this.total = total;
    }

    /**
     * Whether the last reported progress is 100%
     */
    public boolean isCompleted() {
        return total > 0 && processed == total;
    }

    @Override
    public void onMessage(String message) {
        messages.add(message);
    }

    @Override
    public void onError(String message, Throwable e) {
        errors.add(message);
    }

    @Override
    public void onEnd(boolean successful) {
        this.successful = successful;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getMessages() {
        return messages;
    }

    public Boolean getSuccessful() {
        return successful;
    }
}
