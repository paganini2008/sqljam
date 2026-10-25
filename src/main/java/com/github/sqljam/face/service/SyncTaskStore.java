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
package com.github.sqljam.face.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.SyncTask;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SyncTaskStore keeps the sync tasks with their watermarks in sqljam.sync.file, by default
 *               sync-tasks.json in the home directory of SqlJam. A task is saved after each run, its watermarks with
 *               it.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class SyncTaskStore {

    private final JsonStore jsonStore;
    private final String fileName;
    private final File file;
    private final List<SyncTask> tasks = new ArrayList<>();
    /**
     * Time of the file which was read or written last: the command line saves the tasks too
     */
    private long readModified;
    private long readLength;

    public SyncTaskStore(Config config) {
        this(config.getFile("sqljam.sync.file", new File(config.getString("sqljam.home", JsonStore.DEFAULT_DIRECTORY
                .getAbsolutePath()), "sync-tasks.json")));
    }

    public SyncTaskStore(File file) {
        this.jsonStore = new JsonStore(file.getAbsoluteFile().getParentFile());
        this.fileName = file.getName();
        this.file = file.getAbsoluteFile();
        read();
    }

    public File getFile() {
        return file;
    }

    private void read() {
        readModified = file.lastModified();
        readLength = file.length();
        SyncTask[] saved = jsonStore.read(fileName, SyncTask[].class, null);
        if (saved == null && file.exists()) {
            // A file which can not be read is kept aside, the next save would replace the tasks in it
            File broken = new File(file.getAbsoluteFile().getParentFile(), fileName + ".broken-" + System
                    .currentTimeMillis());
            if (file.renameTo(broken) && log.isWarnEnabled()) {
                log.warn("Sync tasks can not be read, the file is kept as {}", broken);
            }
        }
        tasks.clear();
        if (saved != null) {
            tasks.addAll(Arrays.asList(saved));
        }
    }

    /**
     * Reads the file again when another process (SqlJam or the command line) wrote it meanwhile
     */
    private void reloadIfChanged() {
        if (file.lastModified() != readModified || file.length() != readLength) {
            read();
        }
    }

    private void write() throws IOException {
        jsonStore.write(fileName, tasks);
        readModified = file.lastModified();
        readLength = file.length();
    }

    public synchronized List<SyncTask> getTasks() {
        reloadIfChanged();
        return new ArrayList<>(tasks);
    }

    public synchronized Optional<SyncTask> findTask(String id) {
        reloadIfChanged();
        return tasks.stream().filter(task -> task.getId().equals(id)).findFirst();
    }

    /**
     * Adds a new task or replaces the task with the same id, in its place
     */
    public synchronized void saveTask(SyncTask task) throws IOException {
        reloadIfChanged();
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(task.getId())) {
                tasks.set(i, task);
                write();
                return;
            }
        }
        tasks.add(task);
        write();
    }

    /**
     * Changes the saved task of the id, e.g. its watermarks after a run, and keeps the other changes of the user
     * which were saved meanwhile. Nothing is changed when the task was removed.
     */
    public synchronized Optional<SyncTask> updateTask(String id, Consumer<SyncTask> change) throws IOException {
        Optional<SyncTask> task = findTask(id);
        if (task.isPresent()) {
            change.accept(task.get());
            write();
        }
        return task;
    }

    public synchronized void removeTask(String id) throws IOException {
        reloadIfChanged();
        tasks.removeIf(task -> task.getId().equals(id));
        write();
    }
}
