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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.api.Trigger;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.DataFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.ExportManifest;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;

/**
 * @Description: ReportBoundaryTest runs transfers of the main window which end badly and verifies their reports:
 *               the target database stops while rows are written, the user cancels while rows are written with and
 *               without one transaction, a row fails with rows committed every page, and a package can not be
 *               written. Every report is saved, tells how it ended, where it stopped and what the target keeps.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ReportBoundaryTest {

    private static final int ROWS = 3000;
    private static final int PAGE = 500;

    /**
     * Blocks the insert of the row of BLOCK_AT of the target until it is released, so that the database is stopped
     * or the transfer is cancelled in the middle of its rows
     */
    public static class BlockingTrigger implements Trigger {

        static final AtomicInteger ROWS_SEEN = new AtomicInteger();
        static volatile int blockAt = -1;
        static volatile CountDownLatch reached = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);

        static void reset(int row) {
            ROWS_SEEN.set(0);
            blockAt = row;
            reached = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (ROWS_SEEN.incrementAndGet() == blockAt) {
                reached.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Forwards connections to the database until it is cut: the client sees its connections reset, like a network
     * which goes down or a database host which stops
     */
    static final class CuttableProxy implements AutoCloseable {

        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        CuttableProxy(int targetPort) throws IOException {
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        Socket database = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                        sockets.add(client);
                        sockets.add(database);
                        pump(client, database);
                        pump(database, client);
                    } catch (IOException e) {
                        // Cut
                    }
                }
            }, "proxy-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private static void pump(Socket from, Socket to) {
            Thread thread = new Thread(() -> {
                try {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (IOException e) {
                    // Cut
                } finally {
                    closeQuietly(from);
                    closeQuietly(to);
                }
            }, "proxy-pump");
            thread.setDaemon(true);
            thread.start();
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException e) {
                // Closed
            }
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        /**
         * Resets every connection and refuses new ones
         */
        void cut() throws IOException {
            if (serverSocket.isClosed()) {
                return;
            }
            serverSocket.close();
            for (Socket socket : sockets) {
                try {
                    // Reset, not closed gracefully
                    socket.setSoLinger(true, 0);
                } catch (IOException e) {
                    // Closed already
                }
                closeQuietly(socket);
            }
        }

        @Override
        public void close() throws IOException {
            cut();
        }
    }

    @TempDir
    File dir;

    private AppContext context;
    private MainView mainView;
    private Server server;
    private CuttableProxy proxy;
    private String directUrl;
    private ConnectionProfile source;
    private ConnectionProfile target;

    @Start
    void start(Stage stage) throws Exception {
        context = FxTestSupport.createContext(dir);
        mainView = new MainView(context);
        stage.setScene(new Scene(mainView, 1000, 700));
        stage.show();
        source = new ConnectionProfile();
        source.setName("source");
        source.setDbType(DbType.H2);
        source.setDatabase(new File(dir, "source").getAbsolutePath());
        source.setUsername("sa");
        source.setPassword("");
        try (Connection connection = DriverManager.getConnection(source.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE BIG (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO BIG VALUES (?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, "name " + i);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        server = Server.createTcpServer("-tcpPort", "0", "-ifNotExists", "-baseDir", new File(dir, "server")
                .getAbsolutePath()).start();
        proxy = new CuttableProxy(server.getPort());
        directUrl = "jdbc:h2:tcp://localhost:" + server.getPort() + "/target";
        target = new ConnectionProfile();
        target.setName("target");
        target.setDbType(DbType.H2);
        // The transfer reaches the database through the proxy, the test reaches it directly
        target.setUrl("jdbc:h2:tcp://localhost:" + proxy.getPort() + "/target");
        target.setUsername("sa");
        target.setPassword("");
    }

    @AfterEach
    void stop() throws IOException {
        BlockingTrigger.release.countDown();
        context.getSessionManager().closeAll();
        proxy.close();
        server.stop();
    }

    /**
     * The target table, kept by the transfer, with the blocking trigger and a check which a row may fail
     */
    private void createTarget(String check) throws SQLException {
        try (Connection connection = DriverManager.getConnection(directUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE BIG (ID INT PRIMARY KEY, NAME VARCHAR(20)" + (check != null
                    ? ", CONSTRAINT BIG_CHECK CHECK (" + check + ")" : "") + ")");
            statement.execute("CREATE TRIGGER BIG_BLOCK BEFORE INSERT ON BIG FOR EACH ROW CALL \""
                    + BlockingTrigger.class.getName() + "\"");
        }
    }

    private long targetRows() throws SQLException {
        try (Connection connection = DriverManager.getConnection(directUrl, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM BIG")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private TransferRequest request(boolean oneTransaction, boolean failFast) {
        TransferRequest request = new TransferRequest();
        request.setSource(source);
        request.setSourceSchema("PUBLIC");
        request.setTarget(TransferRequest.Target.DATABASE);
        request.setTargetProfile(target);
        request.setTargetSchema("PUBLIC");
        request.setTables(List.of("BIG"));
        request.setTableRecreated(false);
        request.setOneTransaction(oneTransaction);
        request.setFailFast(failFast);
        request.setPageSize(PAGE);
        return request;
    }

    private static TransferReport saved(AppContext context, ProgressDialog dialog) {
        FxTestSupport.waitUntil(() -> dialog.getStage().getScene().getRoot().lookup("#viewReportButton")
                .isVisible());
        return context.getReportStore().find(dialog.getReport().getId()).orElseThrow();
    }

    private static TransferReport.TableReport big(TransferReport report) {
        return report.getTables().stream().filter(table -> "BIG".equalsIgnoreCase(table.getName())).findFirst()
                .orElseThrow();
    }

    @Test
    void reportsTargetWhichStops() throws Exception {
        createTarget(null);
        BlockingTrigger.reset(1200);
        ProgressDialog dialog = FxTestSupport.call(() -> mainView.startTransfer(request(false, true), 1));
        assertTrue(BlockingTrigger.reached.await(30, TimeUnit.SECONDS));
        // The database goes away in the middle of a page
        proxy.cut();
        BlockingTrigger.release.countDown();
        TransferReport report = saved(context, dialog);
        assertEquals(TransferReport.Status.FAILED, report.getStatus());
        assertNotNull(report.getFailure());
        assertNotNull(report.getFailureTrace());
        assertTrue(report.getStoppedAt().contains("PUBLIC.BIG after "), report.getStoppedAt());
        assertEquals(TransferReport.TableStatus.FAILED, big(report).getStatus());
        assertEquals(ROWS, big(report).getSourceRows());
        // Rows of the target are not counted while it is down, the report is saved all the same
        assertNull(big(report).getTargetRows());
        assertEquals(TransferReport.Kind.DATABASE, report.getKind());
        assertEquals(TransferReport.Transaction.EVERY_ROWS, report.getTransaction());
    }

    @Test
    void reportsCancelKeepingCommittedPages() throws Exception {
        createTarget(null);
        BlockingTrigger.reset(1200);
        ProgressDialog dialog = FxTestSupport.call(() -> mainView.startTransfer(request(false, true), 1));
        assertTrue(BlockingTrigger.reached.await(30, TimeUnit.SECONDS));
        FxTestSupport.run(((Button) dialog.getStage().getScene().getRoot().lookup("#cancelButton"))::fire);
        BlockingTrigger.release.countDown();
        TransferReport report = saved(context, dialog);
        assertEquals(TransferReport.Status.CANCELLED, report.getStatus());
        assertFalse(report.isRolledBack());
        assertNull(report.getFailure());
        assertTrue(report.getStoppedAt().contains("PUBLIC.BIG after "), report.getStoppedAt());
        // The pages which were committed before the cancel are kept and counted
        long kept = targetRows();
        assertTrue(kept >= PAGE && kept < ROWS, "Kept " + kept);
        assertEquals(kept, big(report).getTargetRows());
        // Rows of a table which was there are shown, not compared
        assertNull(big(report).getVerified());
    }

    @Test
    void reportsCancelRollingBackOneTransaction() throws Exception {
        createTarget(null);
        BlockingTrigger.reset(1200);
        ProgressDialog dialog = FxTestSupport.call(() -> mainView.startTransfer(request(true, true), 1));
        assertTrue(BlockingTrigger.reached.await(30, TimeUnit.SECONDS));
        FxTestSupport.run(((Button) dialog.getStage().getScene().getRoot().lookup("#cancelButton"))::fire);
        BlockingTrigger.release.countDown();
        TransferReport report = saved(context, dialog);
        assertEquals(TransferReport.Status.CANCELLED, report.getStatus());
        assertTrue(report.isRolledBack());
        assertEquals(TransferReport.Transaction.ONE, report.getTransaction());
        assertEquals(0, targetRows());
        assertEquals(0L, big(report).getTargetRows());
    }

    @Test
    void reportsRowWhichFailsWithPagesCommitted() throws Exception {
        createTarget("ID <> 1700");
        BlockingTrigger.reset(-1);
        ProgressDialog dialog = FxTestSupport.call(() -> mainView.startTransfer(request(false, true), 1));
        TransferReport report = saved(context, dialog);
        assertEquals(TransferReport.Status.FAILED, report.getStatus());
        assertTrue(report.getFailure().contains("BIG_CHECK"), report.getFailure());
        long kept = targetRows();
        assertTrue(kept >= PAGE && kept < 1700, "Kept " + kept);
        assertEquals(kept, big(report).getTargetRows());
        assertFalse(report.isRolledBack());
    }

    @Test
    void reportsRowWhichIsSkipped() throws Exception {
        createTarget("ID <> 1700");
        BlockingTrigger.reset(-1);
        ProgressDialog dialog = FxTestSupport.call(() -> mainView.startTransfer(request(false, false), 1));
        TransferReport report = saved(context, dialog);
        assertEquals(TransferReport.Status.COMPLETED_WITH_ERRORS, report.getStatus());
        assertEquals(1, report.getSkippedRowCount());
        assertEquals(1, big(report).getSkipped());
        assertEquals(ROWS - 1, big(report).getWritten());
        assertEquals(ROWS - 1L, big(report).getTargetRows());
        assertTrue(report.getSkippedRows().get(0).getKey().contains("1700"), report.getSkippedRows().get(0)
                .getKey());
        assertTrue(report.getSkippedRows().get(0).getError().contains("BIG_CHECK"));
    }

    @Test
    void reportsPackageWhichCanNotBeWritten() throws Exception {
        File file = new File(dir, "not-a-directory");
        Files.writeString(file.toPath(), "x");
        TransferRequest request = new TransferRequest();
        request.setSource(source);
        request.setSourceSchema("PUBLIC");
        request.setTarget(TransferRequest.Target.SCRIPT);
        request.setOutputDirectory(new File(file, "package"));
        request.setScriptDbType(DbType.H2);
        request.setDataFormat(DataFormat.CSV);
        ProgressDialog dialog = FxTestSupport.call(() -> mainView.startTransfer(request, 1));
        TransferReport report = saved(context, dialog);
        assertEquals(TransferReport.Status.FAILED, report.getStatus());
        assertEquals(TransferReport.Kind.PACKAGE, report.getKind());
        assertEquals(TransferReport.Transaction.NONE, report.getTransaction());
        assertNotNull(report.getFailure());
    }

    /**
     * A package and its files into a database: the tables of the manifest with their rows, the objects which were
     * created, and the rows of the target compared with them
     */
    @Test
    void reportsImportsOfPackagesAndFiles() throws Exception {
        File packageDir = new File(dir, "package");
        TransferRequest export = new TransferRequest();
        export.setSource(source);
        export.setSourceSchema("PUBLIC");
        export.setTarget(TransferRequest.Target.SCRIPT);
        export.setOutputDirectory(packageDir);
        export.setScriptDbType(DbType.H2);
        export.setDataFormat(DataFormat.CSV);
        context.getTransferService().transfer(export, ExportListener.NONE);
        ExportManifest manifest = ExportManifest.read(packageDir);

        ProgressDialog dialog = FxTestSupport.call(() -> mainView.startImport(new ImportPackageDialog.ImportRequest(
                packageDir, target, null, null, false, manifest, false, false, 0)));
        TransferReport report = saved(context, dialog);
        assertEquals(TransferReport.Status.COMPLETED, report.getStatus(), report.getFailure());
        assertEquals(TransferReport.Kind.PACKAGE_IMPORT, report.getKind());
        assertEquals(packageDir.getAbsolutePath(), report.getSource());
        assertEquals(ROWS, big(report).getSourceRows());
        assertEquals(1, report.getObjects().get("Tables"));
        assertEquals((long) ROWS, big(report).getTargetRows());
        assertTrue(big(report).getVerified());

        List<File> files = manifest.getTables().get(0).getDataFiles().stream().map(path -> new File(packageDir,
                path)).toList();
        ProgressDialog files2 = FxTestSupport.call(() -> mainView.startFileImport(new ImportParquetDialog
                .ParquetRequest(files, "BIG_COPY", DataFileImporter.Mode.CREATE, List.of(), null, target, null, null,
                true, false, false, 0)));
        TransferReport fileReport = saved(context, files2);
        assertEquals(TransferReport.Status.COMPLETED, fileReport.getStatus(), fileReport.getFailure());
        assertEquals(TransferReport.Kind.FILE_IMPORT, fileReport.getKind());
        assertEquals(TransferReport.Transaction.ONE, fileReport.getTransaction());
        TransferReport.TableReport copy = fileReport.getTables().get(0);
        assertEquals("BIG_COPY", copy.getName());
        assertEquals((long) ROWS, copy.getTargetRows());
        assertEquals(2, context.getReportStore().list().size());
    }
}
