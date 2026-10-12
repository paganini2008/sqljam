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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.jdbc.ManualTransaction;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

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
        FxTestSupport.run(() -> dialog.getStage().fireEvent(new WindowEvent(dialog.getStage(),
                WindowEvent.WINDOW_CLOSE_REQUEST)));
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
        assertFalse(FxTestSupport.call(() -> root(dialog).lookup("#viewPackageButton").isVisible()));
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
        // Files of the export are viewed in the package viewer
        assertTrue(FxTestSupport.call(() -> root(dialog).lookup("#viewPackageButton").isVisible()));
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

    private String newDatabase() throws Exception {
        String url = "jdbc:h2:mem:pd" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INT)");
        }
        return url;
    }

    private static long count(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Runs an import of one row in a manual transaction, an index after the row is deferred (H2 commits DDL)
     */
    private static ManualTransaction runInTransaction(ProgressDialog dialog, String url, boolean error,
                                                      AtomicReference<Boolean> completed) throws Exception {
        ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false);
        FxTestSupport.run(() -> dialog.run(() -> {
            try (Statement statement = transaction.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1)");
                statement.execute("CREATE INDEX idx_t ON t (id)");
            }
            if (error) {
                dialog.getListener().onError("value too long", null);
            }
            return 1;
        }, rows -> "Imported " + rows + " rows", () -> transaction, () -> completed.set(true)));
        return transaction;
    }

    private static DialogPane decisionPane() {
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> "decisionPane"
                .equals(pane.getId())));
        return FxTestSupport.getDialogPanes().stream().filter(pane -> "decisionPane".equals(pane.getId()))
                .findFirst().orElseThrow();
    }

    private static void press(DialogPane pane, String text) {
        Button button = FxTestSupport.call(() -> pane.getButtonTypes().stream().map(type -> (Button) pane
                .lookupButton(type)).filter(item -> text.equals(item.getText())).findFirst().orElseThrow());
        FxTestSupport.fireLater(button);
    }

    @Test
    void commitsAfterQuestion() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        ManualTransaction transaction = runInTransaction(dialog, url, false, completed);
        DialogPane pane = decisionPane();
        assertEquals("Commit the Import?", FxTestSupport.call(pane::getHeaderText));
        assertTrue(FxTestSupport.call(pane::getContentText).contains("\"Test\""));
        assertTrue(dialog.isPending());
        assertEquals("Waiting for commit", text(dialog, "progressStatus"));
        assertFalse(dialog.isFinished());
        assertEquals("Tables: 1 / 1", text(dialog, "overallLabel"));
        // Nothing is visible before the commit, the index waits for it
        assertEquals(0, count(url));
        assertTrue(logs(dialog).stream().anyMatch(log -> log.startsWith("This database commits DDL at once")));
        assertTrue(logs(dialog).contains("1 statements (foreign keys, identity values) run after the commit."));
        assertFalse(completed.get());
        press(pane, "Commit");
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Committed", text(dialog, "progressStatus"));
        assertEquals(1, count(url));
        assertTrue(completed.get());
        assertFalse(dialog.isPending());
        assertTrue(transaction.isFinished());
        assertTrue(FxTestSupport.call(() -> root(dialog).lookup("#cancelButton").isVisible()));
        assertFalse(FxTestSupport.call(() -> root(dialog).lookup("#commitButton").isVisible()));
        assertEquals(0, dialog.getErrorLogCount());
    }

    @Test
    void rollsBackFromQuestion() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        runInTransaction(dialog, url, false, completed);
        press(decisionPane(), "Roll Back");
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Rolled back", text(dialog, "progressStatus"));
        assertEquals(0, count(url));
        assertTrue(completed.get());
        // A roll back by the user is not an error
        assertEquals(0, dialog.getErrorLogCount());
    }

    @Test
    void decidesLaterWithButtons() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        runInTransaction(dialog, url, false, new AtomicReference<>(false));
        press(decisionPane(), "Decide Later");
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().isEmpty());
        // The import still waits, the window offers both decisions
        assertTrue(dialog.isPending());
        assertTrue(FxTestSupport.call(() -> root(dialog).lookup("#commitButton").isVisible()));
        assertTrue(FxTestSupport.call(() -> root(dialog).lookup("#rollbackButton").isVisible()));
        assertFalse(FxTestSupport.call(() -> root(dialog).lookup("#cancelButton").isVisible()));
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> root(dialog).lookup("#commitButton")));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Committed", text(dialog, "progressStatus"));
        assertEquals(1, count(url));
    }

    @Test
    void closingPendingWindowRollsBack() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        runInTransaction(dialog, url, false, new AtomicReference<>(false));
        press(decisionPane(), "Decide Later");
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().isEmpty());
        // Cancelled: the window stays and still waits
        Platform.runLater(() -> dialog.getStage().fireEvent(new WindowEvent(dialog.getStage(),
                WindowEvent.WINDOW_CLOSE_REQUEST)));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        assertTrue(dialog.isPending());
        assertTrue(FxTestSupport.call(() -> dialog.getStage().isShowing()));
        // Confirmed: rolled back and closed
        Platform.runLater(() -> dialog.getStage().fireEvent(new WindowEvent(dialog.getStage(),
                WindowEvent.WINDOW_CLOSE_REQUEST)));
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> !dialog.getStage().isShowing());
        assertEquals("Rolled back", text(dialog, "progressStatus"));
        assertEquals(0, count(url));
    }

    @Test
    void rollsBackImportWithErrors() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        ManualTransaction transaction = runInTransaction(dialog, url, true, completed);
        FxTestSupport.waitUntil(dialog::isFinished);
        // No question, nothing of the import is kept
        assertTrue(FxTestSupport.getDialogPanes().isEmpty());
        assertEquals("Rolled back because of errors", text(dialog, "progressStatus"));
        assertEquals(0, count(url));
        assertTrue(transaction.isFinished());
        assertTrue(completed.get());
    }

    @Test
    void rollsBackFailedImport() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false);
        FxTestSupport.run(() -> dialog.run(() -> {
            try (Statement statement = transaction.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1)");
            }
            throw new IllegalStateException("connection lost");
        }, null, () -> transaction, null));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Failed", text(dialog, "progressStatus"));
        FxTestSupport.waitUntil(() -> logs(dialog).contains("Rolled back"));
        assertEquals(0, count(url));
        assertTrue(transaction.isFinished());
    }

    @SuppressWarnings("unchecked")
    private static List<String> logs(ProgressDialog dialog) {
        return FxTestSupport.call(() -> ((ListView<Object>) root(dialog).lookup("#logList")).getItems().stream()
                .map(item -> {
                    try {
                        Field field = item.getClass().getDeclaredField("message");
                        field.setAccessible(true);
                        return (String) field.get(item);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }).collect(Collectors.toList()));
    }

    @Test
    void remindsWhileWaitingForCommit() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        dialog.setReminderInterval(Duration.millis(400));
        runInTransaction(dialog, url, false, new AtomicReference<>(false));
        press(decisionPane(), "Decide Later");
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> "reminderPane"
                .equals(pane.getId())));
        DialogPane reminder = FxTestSupport.getDialogPanes().stream().filter(pane -> "reminderPane".equals(pane
                .getId())).findFirst().orElseThrow();
        assertTrue(FxTestSupport.call(reminder::getContentText).contains("holds locks, undo and log space"));
        // The reminder does not block the buttons of the window, later reminders are logged only
        FxTestSupport.waitUntil(() -> logs(dialog).stream().filter(log -> log.startsWith("Waiting for commit for"))
                .count() >= 2);
        assertEquals(1, FxTestSupport.getDialogPanes().stream().filter(pane -> "reminderPane".equals(pane.getId()))
                .count());
        assertTrue(text(dialog, "progressStatus").startsWith("Waiting for commit ("));
        FxTestSupport.run(() -> reminder.getScene().getWindow().hide());
        FxTestSupport.fireLater((Button) FxTestSupport.call(() -> root(dialog).lookup("#commitButton")));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Committed", text(dialog, "progressStatus"));
        long reminders = logs(dialog).stream().filter(log -> log.startsWith("Waiting for commit for")).count();
        Thread.sleep(1000);
        // No reminder after the decision
        assertEquals(reminders, logs(dialog).stream().filter(log -> log.startsWith("Waiting for commit for"))
                .count());
        assertTrue(FxTestSupport.getDialogPanes().isEmpty());
    }

    @Test
    void remindsNotWhenTurnedOff() throws Exception {
        context.getSettings().setConfirmCommitReminderMinutes(0);
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        runInTransaction(dialog, url, false, new AtomicReference<>(false));
        press(decisionPane(), "Roll Back");
        FxTestSupport.waitUntil(dialog::isFinished);
        assertTrue(logs(dialog).stream().noneMatch(log -> log.startsWith("Waiting for commit for")));
    }

    /**
     * One transaction without confirm: committed when the import succeeds, like the import wizards of other tools
     */
    @Test
    void commitsOneTransactionAutomatically() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false);
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        FxTestSupport.run(() -> dialog.run(() -> {
            try (Statement statement = transaction.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1)");
            }
            return 1;
        }, rows -> "Imported " + rows + " rows", () -> transaction, false, () -> completed.set(true)));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertTrue(FxTestSupport.getDialogPanes().isEmpty());
        assertEquals("Completed", text(dialog, "progressStatus"));
        assertEquals(1, count(url));
        assertTrue(transaction.isFinished());
        assertTrue(completed.get());
    }

    @Test
    void rollsBackOneTransactionWithErrors() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false);
        FxTestSupport.run(() -> dialog.run(() -> {
            try (Statement statement = transaction.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1)");
            }
            dialog.getListener().onError("value too long", null);
            return 1;
        }, null, () -> transaction, false, null));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Rolled back because of errors", text(dialog, "progressStatus"));
        assertEquals(0, count(url));
    }

    /**
     * Rows with errors were skipped: one transaction is committed with the other rows
     */
    @Test
    void commitsSkippedErrorsAutomatically() throws Exception {
        String url = newDatabase();
        ProgressDialog dialog = create(1);
        FxTestSupport.run(() -> dialog.setSkipErrors(true));
        ManualTransaction transaction = new ManualTransaction(DriverManager.getConnection(url), false);
        FxTestSupport.run(() -> dialog.run(() -> {
            try (Statement statement = transaction.getConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO t VALUES (1)");
            }
            dialog.getListener().onError("Skipped row of table 'T' (ID=2): value too long", null);
            return 1;
        }, null, () -> transaction, false, null));
        FxTestSupport.waitUntil(dialog::isFinished);
        assertEquals("Completed with 1 errors", text(dialog, "progressStatus"));
        assertEquals(1, count(url));
        assertTrue(logs(dialog).contains("Skipped row of table 'T' (ID=2): value too long"));
    }

    /**
     * Confirm before commit with skipped rows: the user is asked, commit keeps the other rows, roll back is not
     * caused by the errors
     */
    @Test
    void confirmsImportWithSkippedErrors() throws Exception {
        for (String decision : new String[]{"Commit", "Roll Back"}) {
            String url = newDatabase();
            ProgressDialog dialog = create(1);
            FxTestSupport.run(() -> dialog.setSkipErrors(true));
            AtomicReference<Boolean> completed = new AtomicReference<>(false);
            runInTransaction(dialog, url, true, completed);
            press(decisionPane(), decision);
            FxTestSupport.waitUntil(dialog::isFinished);
            if ("Commit".equals(decision)) {
                assertEquals("Committed with 1 errors skipped", text(dialog, "progressStatus"));
                assertEquals(1, count(url));
            } else {
                assertEquals("Rolled back", text(dialog, "progressStatus"));
                assertEquals(0, count(url));
            }
            assertTrue(completed.get());
            FxTestSupport.run(() -> dialog.getStage().close());
        }
    }

    /**
     * The window shows the last entries, errors only on demand, the saved log keeps every entry
     */
    @Test
    @SuppressWarnings("unchecked")
    void filtersAndSavesLog(@org.junit.jupiter.api.io.TempDir File dir) throws Exception {
        ProgressDialog dialog = create(1);
        FxTestSupport.run(() -> dialog.run(() -> {
            for (int i = 1; i <= 6000; i++) {
                dialog.getListener().onMessage("Row " + i);
            }
            dialog.getListener().onError("Skipped row of table 'T' (ID=7): bad value", null);
            return 1;
        }, null, null, false, null));
        FxTestSupport.waitUntil(dialog::isFinished);
        ListView<Object> list = (ListView<Object>) root(dialog).lookup("#logList");
        assertEquals(5000, FxTestSupport.call(() -> list.getItems().size()));
        CheckBox errorsOnly = (CheckBox) root(dialog).lookup("#errorsOnlyCheck");
        FxTestSupport.run(() -> errorsOnly.setSelected(true));
        assertEquals(List.of("Skipped row of table 'T' (ID=7): bad value"), logs(dialog));
        FxTestSupport.run(() -> errorsOnly.setSelected(false));
        assertEquals(5000, logs(dialog).size());
        assertTrue(FxTestSupport.call(() -> root(dialog).lookup("#saveLogButton").isVisible()));
        File file = new File(dir, "import.log");
        FxTestSupport.run(() -> {
            try {
                dialog.writeLog(file);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        List<String> lines = Files.readAllLines(file.toPath());
        assertEquals("Status: Completed with 1 errors, errors and skipped items: 1", lines.get(1));
        assertTrue(lines.stream().anyMatch(line -> line.endsWith(" INFO  Row 1")));
        assertTrue(lines.stream().anyMatch(line -> line.endsWith(" ERROR Skipped row of table 'T' (ID=7): bad value")));
        assertTrue(lines.size() > 6000);
    }
}
