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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ReportExporter;
import com.github.sqljam.face.service.ReportRecorder;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;

/**
 * @Description: ReportTabTest verifies the page of a report, the history of the imports, and how the main window
 *               opens them: one page a report and one history
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ReportTabTest {

    @TempDir
    File dir;

    private AppContext context;
    private MainView mainView;

    @Start
    void start(Stage stage) {
        context = FxTestSupport.createContext(dir);
        mainView = new MainView(context);
        stage.setScene(new Scene(mainView, 1200, 800));
        stage.show();
    }

    private static TransferReport report(long startedAt, TransferReport.Status status) {
        ReportRecorder recorder = new ReportRecorder(new TransferReport());
        TransferReport report = recorder.getReport();
        report.setTitle("Import into pg");
        report.setKind(TransferReport.Kind.DATABASE);
        report.setSource("h2 / PUBLIC");
        report.setTarget("pg / app");
        report.getOptions().put("Batch size", "500");
        recorder.ddlExecuted("CREATE TABLE users (id INT)", true);
        recorder.tableStarted(null, "app", "users", 3);
        recorder.rowSkipped(null, null, "users", "id=2", new SQLException("bad date"));
        recorder.tableEnded(null, "app", "users", 2);
        recorder.tableStarted(null, "app", "orders", 10);
        recorder.finish(status, status == TransferReport.Status.FAILED ? new SQLException("connection lost")
                : null, null);
        report.setStartedAt(startedAt);
        report.setEndedAt(startedAt + 2000);
        return report;
    }

    /**
     * Shows the tab in the main window, the content of its scroll pane is found once it is laid out
     */
    private ReportTab show(TransferReport report) {
        return FxTestSupport.call(() -> {
            ReportTab tab = new ReportTab(report);
            tabs().getTabs().add(tab);
            tabs().getSelectionModel().select(tab);
            mainView.applyCss();
            mainView.layout();
            return tab;
        });
    }

    private TabPane tabs() {
        return (TabPane) mainView.lookup("#tableTabs");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> rows(Tab tab, String id) {
        return FxTestSupport.call(() -> ((TableView<List<String>>) tab.getContent().lookup("#" + id)).getItems()
                .stream().collect(Collectors.toList()));
    }

    @Test
    void showsTheReport() {
        TransferReport report = report(1_700_000_000_000L, TransferReport.Status.FAILED);
        ReportTab tab = show(report);
        Node content = tab.getContent();
        Label status = (Label) content.lookup("#reportStatus");
        assertEquals("Failed", FxTestSupport.call(status::getText));
        assertTrue(status.getStyleClass().contains("status-error"));
        assertEquals("Import into pg", tab.getTooltip().getText());
        assertEquals(report.getId(), tab.getReport().getId());
        assertNotNull(content.lookup("#reportSummary"));
        assertEquals(List.of("Batch size", "500"), rows(tab, "reportOptions").get(0));
        List<List<String>> tables = rows(tab, "reportTables");
        assertEquals(2, tables.size());
        assertEquals(List.of("app.users", "Completed with errors", "3", "2", "1"), tables.get(0).subList(0, 5));
        assertEquals("Failed", tables.get(1).get(1));
        assertEquals(List.of("Tables", "1"), rows(tab, "reportObjects").get(0));
        assertEquals(List.of("users", "id=2", "bad date"), rows(tab, "reportSkippedRows").get(0));
        assertTrue(((TextArea) content.lookup("#reportTrace")).getText().contains("connection lost"));
        // Options are folded, the tables are open
        List<TitledPane> sections = FxTestSupport.call(() -> content.lookupAll(".titled-pane").stream()
                .map(TitledPane.class::cast).collect(Collectors.toList()));
        assertFalse(sections.get(0).isExpanded());
        assertTrue(sections.get(1).isExpanded());
    }

    @Test
    void showsAReportWithoutErrors() {
        TransferReport report = new TransferReport();
        report.setStatus(TransferReport.Status.COMPLETED);
        report.setKind(TransferReport.Kind.PACKAGE);
        ReportTab tab = show(report);
        Node content = tab.getContent();
        assertTrue(content.lookup("#reportStatus").getStyleClass().contains("status-success"));
        assertNull(content.lookup("#reportOptions"));
        assertNull(content.lookup("#reportObjects"));
        assertNull(content.lookup("#reportSkippedRows"));
        assertNull(content.lookup("#reportTrace"));
        assertNotNull(content.lookup("#reportTables"));
    }

    @Test
    void exportsTheReport() throws Exception {
        TransferReport report = report(1_700_000_000_000L, TransferReport.Status.COMPLETED_WITH_ERRORS);
        ReportTab tab = FxTestSupport.call(() -> new ReportTab(report));
        for (ReportExporter.Format format : ReportExporter.Format.values()) {
            File file = new File(dir, "report." + format.getExtension());
            FxTestSupport.run(() -> tab.exportTo(file, format));
            assertEquals(ReportExporter.export(report, format), Files.readString(file.toPath(),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    void opensOnePageAReport() {
        TransferReport report = report(1_700_000_000_000L, TransferReport.Status.COMPLETED);
        FxTestSupport.run(() -> context.getReportViewer().accept(report));
        FxTestSupport.run(() -> mainView.openReport(report));
        List<Tab> reports = FxTestSupport.call(() -> tabs().getTabs().stream().filter(ReportTab.class::isInstance)
                .collect(Collectors.toList()));
        assertEquals(1, reports.size());
        assertEquals(reports.get(0), FxTestSupport.call(() -> tabs().getSelectionModel().getSelectedItem()));
        FxTestSupport.run(() -> mainView.openReport(report(1_700_000_100_000L, TransferReport.Status.COMPLETED)));
        assertEquals(2, FxTestSupport.call(() -> tabs().getTabs().stream().filter(ReportTab.class::isInstance)
                .count()));
    }

    @Test
    void listsTheHistory() throws Exception {
        TransferReport older = report(1_700_000_000_000L, TransferReport.Status.COMPLETED);
        TransferReport newer = report(1_700_000_100_000L, TransferReport.Status.FAILED);
        context.getReportStore().save(older);
        context.getReportStore().save(newer);
        // From the File menu, opened once
        MenuItem item = FxTestSupport.menuItem(mainView, "historyMenuItem");
        FxTestSupport.run(item::fire);
        FxTestSupport.run(item::fire);
        List<HistoryTab> histories = FxTestSupport.call(() -> tabs().getTabs().stream().filter(
                HistoryTab.class::isInstance).map(HistoryTab.class::cast).collect(Collectors.toList()));
        assertEquals(1, histories.size());
        HistoryTab history = histories.get(0);
        FxTestSupport.waitUntil(() -> history.getReports().size() == 2);
        assertEquals(newer.getId(), history.getReports().get(0).getId());
        TableView<TransferReport> table = history.getTable();
        assertEquals("Failed", FxTestSupport.call(() -> table.getColumns().get(4).getCellData(0)));
        assertEquals("2", FxTestSupport.call(() -> table.getColumns().get(5).getCellData(0)));
        assertEquals("2.0 s", FxTestSupport.call(() -> table.getColumns().get(6).getCellData(0)));
        assertEquals("Database", FxTestSupport.call(() -> table.getColumns().get(1).getCellData(0)));

        // Open shows the selected report on its own page
        Button open = (Button) history.getContent().lookup("#historyOpenButton");
        assertTrue(open.isDisabled());
        FxTestSupport.run(() -> table.getSelectionModel().select(1));
        FxTestSupport.run(open::fire);
        ReportTab opened = (ReportTab) FxTestSupport.call(() -> tabs().getSelectionModel().getSelectedItem());
        assertEquals(older.getId(), opened.getReport().getId());

        // A report of a new import shows in the open history
        context.getReportStore().save(report(1_700_000_200_000L, TransferReport.Status.CANCELLED));
        FxTestSupport.run(() -> mainView.openReport(context.getReportStore().list().get(0)));
        FxTestSupport.waitUntil(() -> history.getReports().size() == 3);
    }

    @Test
    void deletesFromTheHistory() throws Exception {
        TransferReport report = report(1_700_000_000_000L, TransferReport.Status.COMPLETED);
        context.getReportStore().save(report);
        HistoryTab history = FxTestSupport.call(() -> new HistoryTab(context, opened -> { }));
        FxTestSupport.run(() -> tabs().getTabs().add(history));
        FxTestSupport.waitUntil(() -> history.getReports().size() == 1);
        // Kept when the question is cancelled
        Platform.runLater(() -> history.delete(report));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        assertEquals(1, context.getReportStore().list().size());
        Platform.runLater(() -> history.delete(report));
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> history.getReports().isEmpty());
        assertTrue(context.getReportStore().list().isEmpty());
        FxTestSupport.run(() -> history.delete(null));
    }

    @Test
    void showsWhenTheHistoryCanNotBeRead() throws Exception {
        File file = new File(dir, "not-a-directory");
        Files.writeString(file.toPath(), "x");
        context.setReportStore(new com.github.sqljam.face.service.ReportStore(file, 10));
        HistoryTab history = FxTestSupport.call(() -> new HistoryTab(context, opened -> { }));
        FxTestSupport.waitUntil(() -> history.getReports().isEmpty());
        assertEquals("No imports yet, each import and export leaves its report here", FxTestSupport.call(() ->
                ((Label) history.getTable().getPlaceholder()).getText()));
    }
}
