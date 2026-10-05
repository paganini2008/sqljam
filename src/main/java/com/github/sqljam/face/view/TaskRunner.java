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
package com.github.sqljam.face.view;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.concurrent.Task;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: TaskRunner runs database work in background threads and delivers results on the FX thread
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public final class TaskRunner {

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "sqljam-worker-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static final IntegerProperty RUNNING = new SimpleIntegerProperty(0);

    private TaskRunner() {
    }

    /**
     * Number of running background tasks, bound by the status bar indicator
     */
    public static IntegerProperty runningProperty() {
        return RUNNING;
    }

    public static <T> void run(Callable<T> callable, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return callable.call();
            }
        };
        task.setOnSucceeded(event -> {
            RUNNING.set(RUNNING.get() - 1);
            if (onSuccess != null) {
                onSuccess.accept(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            RUNNING.set(RUNNING.get() - 1);
            Throwable e = task.getException();
            if (log.isErrorEnabled()) {
                log.error(e.getMessage(), e);
            }
            if (onFailure != null) {
                onFailure.accept(e);
            }
        });
        if (Platform.isFxApplicationThread()) {
            RUNNING.set(RUNNING.get() + 1);
        } else {
            Platform.runLater(() -> RUNNING.set(RUNNING.get() + 1));
        }
        EXECUTOR.submit(task);
    }

    /**
     * Runs a long task in a background thread without FX callbacks, e.g. exports reporting their own progress
     */
    public static void execute(Runnable runnable) {
        EXECUTOR.submit(runnable);
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
