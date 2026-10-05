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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.impexp.ExportListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: ProgressDialogTest verifies progress, errors, cancellation and finish states
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ProgressDialogTest {

    @TempDir
    File dir;

    private AppContext context;
    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        stage.setScene(new Scene(new StackPane(), 200, 200));
        stage.show();
    }

    private ProgressDialog create(int totalTables) {
        return FxTestSupport.call(() -> new ProgressDialog(stage, context, "Test", totalTables));
    }

    private static Parent root(ProgressDialog dialog) {
        return dialog.getStage().getScene().getRoot();
    }

    private static String text(ProgressDialog dialog, String id) {
        return FxTestSupport.call(() -> ((Label) root(dialog).lookup("#" + id)).getText());
    }

    private static double progress(ProgressDialog dialog, String id) {
        return FxTestSupport.call(() -> ((ProgressBar) root(dialog).lookup("#" + id)).getProgress());
    }

    /**
     * Runs a job which blocks until released, so that listener events can be verified while running
     */
    private static Object[] runBlocking(ProgressDialog dialog) {
        Object lock = new Object();
        AtomicReference<Boolean> released = new AtomicReference<>(false);
        FxTestSupport.run(() -> dialog.run(() -> {
            synchronized (lock) {
                while (!released.get()) {
                    lock.wait(50);
                }
            }
            return "done";
        }, result -> "Summary " + result, null));
        return new Object[]{lock, released};
    }

    @SuppressWarnings("unchecked")
    private static void release(Object[] handle) {
        synchronized (handle[0]) {
            ((AtomicReference<Boolean>) handle[1]).set(true);
            handle[0].notifyAll();
        }
    }

    @Test
    void showsUnknownTotalAsIndeterminate() {
        ProgressDialog dialog = create(0);
        assertEquals("Tables completed: 0", text(dialog, "overallLabel"));
        assertEquals("", text(dialog, "percentLabel"));
        assertTrue(progress(dialog, "overallBar") < 0);
    }

    @Test
    void showsPercentage() {
        ProgressDialog dialog = create(2);
        ExportListener listener = dialog.getListener();
        Object[] handle = runBlocking(dialog);
        listener.onProgress(50, 200);
        FxTestSupport.waitUntil(() -> "Overall 25%".equals(((Label) root(dialog).lookup("#percentLabel"))
                .getText()));
        assertEquals(0.25, progress(dialog, "overallBar"), 0.001);
        // Processed rows exceeding the total are clamped to 100%
        listener.onProgress(300, 200);
        FxTestSupport.waitUntil(() -> "Overall 100%".equals(((Label) root(dialog).lookup("#percentLabel"))
                .getText()));
        assertEquals(1.0, progress(dialog, "overallBar"), 0.001);
        // Unknown total is ignored
        listener.onProgress(5, 0);
        FxTestSupport.run(() -> {
        });
        assertEquals("Overall 100%", text(dialog, "percentLabel"));
        release(handle);
        FxTestSupport.waitUntil(dialog::isFinished);
    }

    @Test
    void showsTableProgress() {
        ProgressDialog dialog = create(2);
        ExportListener listener = dialog.getListener();
        Object[] handle = runBlocking(dialog);
        listener.onTableStart("db", "s", "t1", 100);
        listener.onTableProgress("db", "s", "t1", 50, 100);
        FxTestSupport.waitUntil(() -> ((ProgressBar) root(dialog).lookup("#tableBar")).getProgress() == 0.5);
        assertEquals("Table s.t1 (100 rows)", text(dialog, "tableLabel"));
        listener.onTableEnd("db", "s", "t1", 100);
        FxTestSupport.waitUntil(() -> "Tables: 1 / 2".equals(((Label) root(dialog).lookup("#overallLabel"))
                .getText()));
        // Tables without rows show indeterminate progress
        listener.onTableStart("db", null, "t2", 0);
        FxTestSupport.waitUntil(() -> ((ProgressBar) root(dialog).lookup("#tableBar")).getProgress() < 0);
        assertEquals("Table db.t2 (0 rows)", text(dialog, "tableLabel"));
        release(handle);
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Completed", text(dialog, "progressStatus"));
        assertEquals(1.0, progress(dialog, "overallBar"), 0.001);
        assertEquals(1.0, progress(dialog, "tableBar"), 0.001);
    }

    @Test
    void highlightsErrors() {
        ProgressDialog dialog = create(1);
        ExportListener listener = dialog.getListener();
        Object[] handle = runBlocking(dialog);
        listener.onMessage("plain message");
        listener.onMessage("  ");
        listener.onError("broken table", new RuntimeException("x"));
        FxTestSupport.waitUntil(() -> dialog.getErrorLogCount() == 1);
        release(handle);
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Completed with 1 errors", text(dialog, "progressStatus"));
        assertTrue(FxTestSupport.call(() -> root(dialog).lookupAll(".log-error").size() > 0));
        @SuppressWarnings("unchecked")
        ListView<Object> logList = (ListView<Object>) root(dialog).lookup("#logList");
        // Blank messages are not logged: started, plain message, error, summary and status
        assertEquals(5, FxTestSupport.call(() -> logList.getItems().size()));
    }

    @Test
    void cancelsRunningJob() {
        ProgressDialog dialog = create(1);
        ExportListener listener = dialog.getListener();
        FxTestSupport.run(() -> dialog.run(() -> {
            while (!listener.isCancelled()) {
                Thread.sleep(20);
            }
            throw new ExportCancelledException();
        }, null, null));
        assertFalse(listener.isCancelled());
        Button cancelButton = (Button) root(dialog).lookup("#cancelButton");
        FxTestSupport.run(cancelButton::fire);
        assertTrue(listener.isCancelled());
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Cancelled", text(dialog, "progressStatus"));
        assertEquals("Close", FxTestSupport.call(cancelButton::getText));
        assertEquals(0, dialog.getErrorLogCount());
        // Close button closes the window after finished
        FxTestSupport.run(cancelButton::fire);
        assertFalse(FxTestSupport.call(() -> dialog.getStage().isShowing()));
    }

    @Test
    void closingWindowCancelsJob() {
        ProgressDialog dialog = create(1);
        ExportListener listener = dialog.getListener();
        FxTestSupport.run(() -> dialog.run(() -> {
            while (!listener.isCancelled()) {
                Thread.sleep(20);
            }
            return null;
        }, null, null));
        FxTestSupport.run(() -> dialog.getStage().fireEvent(new javafx.stage.WindowEvent(dialog.getStage(),
                javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        assertTrue(listener.isCancelled());
    }

    @Test
    void showsFailure() {
        ProgressDialog dialog = create(1);
        FxTestSupport.run(() -> dialog.run(() -> {
            throw new IllegalStateException("database is down");
        }, null, null));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Failed", text(dialog, "progressStatus"));
        // The error message and the failed status
        assertEquals(2, dialog.getErrorLogCount());
        assertEquals(0.0, progress(dialog, "overallBar"), 0.001);
        assertFalse(FxTestSupport.call(() -> root(dialog).lookup("#openFolderButton").isVisible()));
    }

    @Test
    void showsOutputFolderAndManifestOnSuccess() throws Exception {
        Files.writeString(new File(dir, "manifest.json").toPath(), "{}");
        ProgressDialog dialog = create(0);
        dialog.setOutputDirectory(dir);
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        FxTestSupport.run(() -> dialog.run(() -> 3, count -> "Exported " + count + " tables",
                () -> completed.set(true)));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertTrue(completed.get());
        assertEquals("Completed", text(dialog, "progressStatus"));
        assertEquals("Overall 100%", text(dialog, "percentLabel"));
        assertTrue(FxTestSupport.call(() -> root(dialog).lookup("#openFolderButton").isVisible()));
        assertTrue(text(dialog, "manifestLabel").endsWith("manifest.json"));
        assertTrue(text(dialog, "elapsedLabel").startsWith("Elapsed 00:00:0"));
    }

    @Test
    void cancelledByListenerWithoutException() {
        ProgressDialog dialog = create(1);
        ExportListener listener = dialog.getListener();
        FxTestSupport.run(() -> dialog.run(() -> {
            while (!listener.isCancelled()) {
                Thread.sleep(20);
            }
            throw new IllegalStateException("interrupted");
        }, null, null));
        FxTestSupport.run(() -> ((Button) root(dialog).lookup("#cancelButton")).fire());
        FxTestSupport.waitUntil(dialog::isFinished);
        // Failures after cancelling are reported as cancelled
        assertEquals("Cancelled", text(dialog, "progressStatus"));
    }
}
