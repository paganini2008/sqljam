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

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.ReportExporter;
import com.github.sqljam.face.service.SyncScheduler;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * @Description: SyncTab lists the sync tasks with their schedules, next and last runs, the status and the rows of
 *               their last runs: tasks are created, edited, deleted, run now, cancelled, switched on and off, and the
 *               report of the last run is opened. The list follows the runs of the scheduler.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class SyncTab extends Tab {

    private final AppContext context;
    private final SyncScheduler scheduler;
    private final Consumer<TransferReport> opener;
    private final TableView<SyncTask> table = new TableView<>();
    private final Button edit = new Button(Messages.get("sync.editButton"), Icons.of(Icons.EDIT));
    private final Button delete = new Button(Messages.get("sync.delete"), Icons.of(Icons.DELETE));
    private final Button run = new Button(Messages.get("sync.run"), Icons.of(Icons.START));
    private final Button cancel = new Button(Messages.get("sync.cancel"), Icons.of(Icons.CANCEL));
    private final Button toggle = new Button(Messages.get("sync.disable"));
    private final Button report = new Button(Messages.get("sync.report"), Icons.of(Icons.HISTORY));
    private final Consumer<SyncTask> listener = task -> Platform.runLater(this::refresh);

    public SyncTab(AppContext context, Consumer<TransferReport> opener) {
        this.context = context;
        this.scheduler = context.getSyncScheduler();
        this.opener = opener;
        setText(Messages.get("sync.tab"));
        setGraphic(Icons.of(Icons.REFRESH));
        setId("syncTab");
        table.setId("syncTable");
        // What matters first: the name, how the last run ended, then when it runs; the route last
        table.getColumns().add(column(Messages.get("sync.column.name"), SyncTask::getName, 160));
        TableColumn<SyncTask, String> status = column(Messages.get("sync.column.status"), this::status, 160);
        status.setCellFactory(tableColumn -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-success", "status-error", "status-pending");
                setText(empty ? null : item);
                SyncTask task = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (task != null && (scheduler.isRunning(task.getId()) || scheduler.isRunningElsewhere(task
                        .getId()))) {
                    getStyleClass().add("status-pending");
                } else if (task != null && task.getLastStatus() != null) {
                    getStyleClass().add(task.getLastStatus().isKept() ? "status-success" : "status-error");
                }
            }
        });
        table.getColumns().add(status);
        table.getColumns().add(column(Messages.get("sync.column.rows"), task -> task.getLastRunAt() > 0 ? String
                .format("%,d", task.getLastRowCount()) : "", 90));
        table.getColumns().add(column(Messages.get("sync.column.schedule"), SyncTab::schedule, 140));
        table.getColumns().add(column(Messages.get("sync.column.runner"), task -> Messages.get("sync.runner.short."
                + task.getRunner().name().toLowerCase(java.util.Locale.ENGLISH)), 130));
        table.getColumns().add(column(Messages.get("sync.column.next"), this::nextRun, 150));
        table.getColumns().add(column(Messages.get("sync.column.last"), task -> task.getLastRunAt() > 0
                ? ReportExporter.time(task.getLastRunAt()) : Messages.get("sync.never"), 150));
        table.getColumns().add(column(Messages.get("sync.column.kind"), task -> Messages.get("sync.kind." + task
                .getKind().name().toLowerCase(java.util.Locale.ENGLISH)), 160));
        table.getColumns().add(column(Messages.get("sync.column.route"), this::route, 260));
        table.setRowFactory(view -> {
            TableRow<SyncTask> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    edit(row.getItem());
                }
            });
            return row;
        });
        table.setPlaceholder(new Label(Messages.get("sync.empty")));
        TableColumns.fill(table);

        Button add = new Button(Messages.get("sync.add"), Icons.of(Icons.ADD));
        add.setId("syncAddButton");
        add.setOnAction(event -> edit(null));
        edit.setId("syncEditButton");
        edit.setOnAction(event -> edit(selected()));
        delete.setId("syncDeleteButton");
        delete.setOnAction(event -> delete(selected()));
        run.setId("syncRunButton");
        run.setOnAction(event -> runNow(selected()));
        cancel.setId("syncCancelButton");
        cancel.setOnAction(event -> {
            if (selected() != null) {
                scheduler.cancel(selected().getId());
            }
        });
        toggle.setId("syncToggleButton");
        toggle.setOnAction(event -> toggle(selected()));
        report.setId("syncReportButton");
        report.setOnAction(event -> openReport(selected()));
        Button refresh = new Button(Messages.get("sync.refresh"), Icons.of(Icons.REFRESH));
        refresh.setId("syncRefreshButton");
        refresh.setOnAction(event -> refresh());
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, task) -> updateButtons());
        BorderPane root = new BorderPane(table);
        root.setTop(new ToolBar(add, edit, delete, run, cancel, toggle, report, refresh));
        setContent(root);
        scheduler.addListener(listener);
        // Runs of the command line are read from the file of the tasks
        Timeline poll = new Timeline(new KeyFrame(Duration.seconds(5), event -> refresh()));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
        setOnClosed(event -> {
            scheduler.removeListener(listener);
            poll.stop();
        });
        refresh();
    }

    private static TableColumn<SyncTask, String> column(String title, Function<SyncTask, String> value,
                                                       double width) {
        TableColumn<SyncTask, String> column = new TableColumn<>(title);
        column.setCellValueFactory(features -> new ReadOnlyStringWrapper(value.apply(features.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private String name(String profileId) {
        if (profileId == null) {
            return "";
        }
        Optional<ConnectionProfile> profile = context.getProfileRegistry().findProfile(profileId);
        if (profile.isPresent()) {
            return profile.get().getName();
        }
        for (DataSourceExtension extension : DataSourceExtension.load(context.getSettingsStore().getConfig())) {
            for (DataSourceExtension.Source source : extension.getSources()) {
                if (source.getId().equals(profileId)) {
                    return source.getName();
                }
            }
        }
        return profileId;
    }

    String route(SyncTask task) {
        return name(task.getSourceId()) + part(task.getSourceCatalog()) + part(task.getSourceSchema()) + " → "
                + name(task.getTargetId()) + part(task.getTargetCatalog()) + part(task.getTargetSchema());
    }

    private static String part(String name) {
        return StringUtils.isNotBlank(name) ? " / " + name : "";
    }

    static String schedule(SyncTask task) {
        switch (task.getScheduleType()) {
            case INTERVAL:
                return Messages.format("sync.every", task.getIntervalMinutes());
            case DAILY:
                return Messages.format("sync.daily", task.getDailyTime());
            default:
                return Messages.get("sync.manual");
        }
    }

    private String nextRun(SyncTask task) {
        if (!task.isEnabled()) {
            return Messages.get("sync.disabled");
        }
        long next = scheduler.nextRunAt(task);
        return next < 0 ? "" : ReportExporter.time(Math.max(next, System.currentTimeMillis()));
    }

    private String status(SyncTask task) {
        if (scheduler.isRunning(task.getId())) {
            return Messages.get("sync.running");
        }
        if (scheduler.isRunningElsewhere(task.getId())) {
            return Messages.get("sync.runningElsewhere");
        }
        return task.getLastStatus() != null ? ReportExporter.words(task.getLastStatus()) : "";
    }

    private SyncTask selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private Window window() {
        return table.getScene() != null ? table.getScene().getWindow() : null;
    }

    private void updateButtons() {
        SyncTask task = selected();
        boolean running = task != null && scheduler.isRunning(task.getId());
        edit.setDisable(task == null);
        delete.setDisable(task == null || running);
        run.setDisable(task == null || running);
        cancel.setDisable(!running);
        toggle.setDisable(task == null);
        toggle.setText(Messages.get(task != null && !task.isEnabled() ? "sync.enable" : "sync.disable"));
        report.setDisable(task == null || task.getLastReportId() == null);
    }

    /**
     * Reads the tasks again, keeping the selection
     */
    public void refresh() {
        SyncTask task = selected();
        table.getItems().setAll(scheduler.getStore().getTasks());
        if (task != null) {
            table.getItems().stream().filter(item -> item.getId().equals(task.getId())).findFirst().ifPresent(
                    item -> table.getSelectionModel().select(item));
        }
        table.refresh();
        updateButtons();
    }

    void edit(SyncTask task) {
        new SyncTaskDialog(window(), context, task).showAndWait().ifPresent(this::save);
    }

    void save(SyncTask task) {
        boolean wasSystem = scheduler.getStore().findTask(task.getId()).map(saved -> saved.getRunner()
                == SyncTask.Runner.SYSTEM).orElse(false);
        try {
            scheduler.getStore().saveTask(task);
        } catch (IOException e) {
            Dialogs.showError(window(), Messages.get("sync.title"), e);
            return;
        }
        refresh();
        if (wasSystem || task.getRunner() == SyncTask.Runner.SYSTEM) {
            applySystem(task);
        }
        warnPasswords(task);
    }

    /**
     * Registers a task of the system scheduler with the system, or removes it from there
     */
    private void applySystem(SyncTask task) {
        try {
            context.getSystemScheduler().apply(task);
        } catch (IOException e) {
            Dialogs.showWarning(window(), Messages.get("sync.title"), Messages.format("sync.system.error", e
                    .getMessage()));
        }
    }

    /**
     * A scheduled run alone fails without the passwords, it is told when the task is saved
     */
    private void warnPasswords(SyncTask task) {
        if (task.getScheduleType() == SyncTask.ScheduleType.MANUAL) {
            return;
        }
        for (String id : new String[]{task.getSourceId(), task.getTargetId()}) {
            Optional<ConnectionProfile> profile = context.getProfileRegistry().findProfile(id);
            if (profile.isPresent() && !context.getProfileRegistry().isPasswordRemembered(id) && StringUtils
                    .isNotBlank(profile.get().getUsername())) {
                Dialogs.showWarning(window(), Messages.get("sync.title"), Messages.format("sync.password", profile
                        .get().getName()));
            }
        }
    }

    void delete(SyncTask task) {
        if (task == null || !Dialogs.confirm(window(), Messages.get("sync.delete"), Messages.format(
                "sync.delete.confirm", task.getName()))) {
            return;
        }
        try {
            scheduler.getStore().removeTask(task.getId());
            if (task.getRunner() == SyncTask.Runner.SYSTEM) {
                context.getSystemScheduler().unregister(task.getId());
            }
        } catch (IOException e) {
            Dialogs.showError(window(), Messages.get("sync.delete"), e);
        }
        refresh();
    }

    CompletableFuture<TransferReport> runNow(SyncTask task) {
        if (task == null) {
            return null;
        }
        CompletableFuture<TransferReport> future = scheduler.runNow(task.getId());
        refresh();
        return future;
    }

    void toggle(SyncTask task) {
        if (task == null) {
            return;
        }
        Optional<SyncTask> changed;
        try {
            changed = scheduler.getStore().updateTask(task.getId(), saved -> saved.setEnabled(!saved.isEnabled()));
        } catch (IOException e) {
            Dialogs.showError(window(), Messages.get("sync.title"), e);
            return;
        }
        refresh();
        // A task of the system which is switched off is removed from there, it is registered again when it is on
        changed.filter(saved -> saved.getRunner() == SyncTask.Runner.SYSTEM).ifPresent(this::applySystem);
    }

    void openReport(SyncTask task) {
        if (task == null || task.getLastReportId() == null) {
            return;
        }
        context.getReportStore().find(task.getLastReportId()).ifPresent(opener);
    }

    List<SyncTask> getTasks() {
        return table.getItems();
    }

    TableView<SyncTask> getTable() {
        return table;
    }
}
