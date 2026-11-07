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
package com.github.sqljam.face.vdb;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.SqlStatements;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.DataPane;
import com.github.sqljam.face.view.DataSourceExtension;
import com.github.sqljam.face.view.DbNode;
import com.github.sqljam.face.view.Dialogs;
import com.github.sqljam.face.view.Icons;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.face.view.SqlCompletion;
import com.github.sqljam.face.view.SqlEditor;
import com.github.sqljam.face.view.TableColumns;
import com.github.sqljam.face.view.TaskRunner;
import com.github.sqljam.face.view.ValueCell;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbSqlResult;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * @Description: VdbSqlTab runs SQL on the indexes of Elasticsearch or OpenSearch and exports the rows of a query
 *               like the rows of a table: Ctrl+Enter runs the selected text or the statement at the caret, the
 *               first rows are shown, Export reads all rows by the cursor of the query into a DuckDB file and opens
 *               the export wizard on it. The SQL of these servers reads only. Vectors with their indexes are
 *               imported by Import Vectors into Database, the rows of a query carry vectors as JSON text.
 *               Ctrl+Space completes the names of indexes and their fields.
 * @Author: Fred Feng
 * @Date: 07/11/2026
 * @Version 1.0.0
 */
public class VdbSqlTab extends Tab {

    /**
     * Rows of a query shown in the editor, an export reads all of them
     */
    static final int MAX_ROWS = 500;
    private static final KeyCombination RUN = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination RUN_ALL = new KeyCodeCombination(KeyCode.ENTER,
            KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    private final VdbProfile profile;
    private final DataSourceExtension.QueryExport export;
    private final SqlEditor editor = new SqlEditor();
    private final TableView<List<String>> resultTable = new TableView<>();
    private final ObservableList<List<String>> resultRows = TableColumns.sortedRows(resultTable);
    private final ListView<String[]> messageList = new ListView<>();
    private final TabPane resultTabs = new TabPane();
    private final Tab resultTab = new Tab(Messages.get("sql.result"));
    private final Tab messageTab = new Tab(Messages.get("sql.messages"));
    private final Button runButton = new Button(Messages.get("sql.run"), Icons.of(Icons.START));
    private final Button runAllButton = new Button(Messages.get("sql.runAll"), Icons.of(Icons.START));
    private final Button stopButton = new Button(Messages.get("sql.stop"), Icons.of(Icons.CANCEL));
    private final Button exportButton = new Button(Messages.get("sql.export"), Icons.of(Icons.EXPORT));
    private final Label statusLabel = new Label();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private VdbClient client;
    private String lastQuery;
    private List<String[]> lastColumns = new ArrayList<>();
    private List<List<Object>> lastRows = new ArrayList<>();
    private boolean busy;
    private final SqlCompletion completion;

    public VdbSqlTab(AppContext context, VdbProfile profile, int number, String index,
                     DataSourceExtension.QueryExport export) {
        this.profile = profile;
        this.export = export;
        setText(Messages.format("sql.tabTitle", number, profile.getName()));
        setGraphic(Icons.of(Icons.SQL));
        setTooltip(new Tooltip(Messages.get("vdb.sql.hint")));

        runButton.setId("sqlRunButton");
        runAllButton.setId("sqlRunAllButton");
        stopButton.setId("sqlStopButton");
        exportButton.setId("sqlExportButton");
        statusLabel.setId("sqlStatusLabel");
        resultTable.setId("sqlResultTable");
        messageList.setId("sqlMessageList");
        TableColumns.fill(resultTable);
        runButton.setTooltip(new Tooltip(Messages.get("sql.run.tooltip")));
        runAllButton.setTooltip(new Tooltip(Messages.get("sql.runAll.tooltip")));
        exportButton.setTooltip(new Tooltip(Messages.get("vdb.sql.export.tooltip")));
        runButton.setOnAction(event -> run(false));
        runAllButton.setOnAction(event -> run(true));
        stopButton.setOnAction(event -> stop());
        exportButton.setOnAction(event -> export());
        stopButton.setDisable(true);
        exportButton.setDisable(true);
        editor.getCodeArea().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (RUN_ALL.match(event)) {
                run(true);
                event.consume();
            } else if (RUN.match(event)) {
                run(false);
                event.consume();
            }
        });
        if (StringUtils.isNotBlank(index)) {
            editor.setText("SELECT * FROM \"" + index.replace("\"", "\"\"") + "\"");
        }
        // Indexes and their fields
        completion = SqlCompletion.attach(editor, new SqlCompletion.Source() {
            @Override
            public List<String> tables() {
                return getClient().getCollections();
            }

            @Override
            public List<String> columns(String table) {
                return getClient().describe(table).getFields().stream().map(VdbField::getName).collect(Collectors
                        .toList());
            }
        });

        // Rows of indexes are exported like tables, vectors take the other way
        Label hint = new Label(Messages.get("vdb.sql.hint"));
        hint.setId("vdbSqlHint");
        hint.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar toolBar = new ToolBar(runButton, runAllButton, stopButton, exportButton, spacer, hint);

        resultTable.setPlaceholder(new Label(Messages.get("sql.noResult")));
        resultTab.setContent(resultTable);
        resultTab.setClosable(false);
        messageList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String[] item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("log-error");
                setText(empty || item == null ? null : item[0]);
                if (!empty && item != null && item.length > 1) {
                    getStyleClass().add("log-error");
                }
            }
        });
        messageList.getStyleClass().add("log-list");
        messageTab.setContent(messageList);
        messageTab.setClosable(false);
        resultTabs.getTabs().addAll(resultTab, messageTab);
        BorderPane resultPane = new BorderPane(resultTabs);
        HBox statusBar = new HBox(statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("sql-status");
        resultPane.setBottom(statusBar);
        SplitPane splitPane = new SplitPane(editor.getNode(), resultPane);
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(0.45);
        BorderPane root = new BorderPane(splitPane);
        root.setTop(toolBar);
        root.getStyleClass().add("sql-tab");
        setContent(root);
        setOnClosed(event -> close());
    }

    /**
     * Runs the selected text, the statement at the caret or all statements, the rows of the last query are shown
     */
    void run(boolean all) {
        if (busy) {
            return;
        }
        String selected = editor.getSelectedText();
        List<String> statements;
        if (all) {
            statements = texts(SqlStatements.split(editor.getText()));
        } else if (StringUtils.isNotBlank(selected)) {
            statements = texts(SqlStatements.split(selected));
        } else {
            SqlStatements.Statement statement = SqlStatements.at(editor.getText(), editor.getCaretPosition());
            statements = statement != null ? List.of(statement.getText()) : List.of();
        }
        if (statements.isEmpty()) {
            statusLabel.setText(Messages.get("sql.nothing"));
            return;
        }
        setBusy(true);
        cancelled.set(false);
        statusLabel.setText(Messages.get("sql.running"));
        List<Object[]> results = new ArrayList<>();
        TaskRunner.run(() -> {
            for (String sql : statements) {
                if (cancelled.get()) {
                    break;
                }
                long start = System.currentTimeMillis();
                try {
                    VdbSqlResult result = getClient().querySql(sql, MAX_ROWS);
                    if (result.hasMore()) {
                        // The first rows are shown, an export reads all of them again
                        getClient().closeSqlCursor(result.getCursor());
                    }
                    results.add(new Object[]{sql, result, System.currentTimeMillis() - start});
                } catch (RuntimeException e) {
                    results.add(new Object[]{sql, e, System.currentTimeMillis() - start});
                    break;
                }
            }
            return results;
        }, this::showResults, e -> {
            addMessage(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getName()), true);
            statusLabel.setText(Messages.get("sql.failed"));
            resultTabs.getSelectionModel().select(messageTab);
            setBusy(false);
        });
    }

    private static List<String> texts(List<SqlStatements.Statement> statements) {
        return statements.stream().map(SqlStatements.Statement::getText).collect(Collectors.toList());
    }

    private void showResults(List<Object[]> results) {
        Object[] shown = null;
        boolean failed = false;
        for (Object[] result : results) {
            String sql = (String) result[0];
            if (result[1] instanceof Throwable) {
                addMessage(Messages.format("sql.error", abbreviate(sql), ((Throwable) result[1]).getMessage()), true);
                failed = true;
            } else {
                VdbSqlResult rows = (VdbSqlResult) result[1];
                addMessage(Messages.format(rows.hasMore() ? "sql.rowsFirst" : "sql.rows", rows.getRows().size(),
                        result[2], abbreviate(sql)), false);
                shown = result;
            }
        }
        if (shown != null) {
            VdbSqlResult result = (VdbSqlResult) shown[1];
            showRows(result);
            lastQuery = (String) shown[0];
            lastColumns = result.getColumns();
            lastRows = result.getRows();
            statusLabel.setText(Messages.format(result.hasMore() ? "sql.status.first" : "sql.status.rows",
                    result.getRows().size()));
        }
        if (failed) {
            resultTabs.getSelectionModel().select(messageTab);
            statusLabel.setText(cancelled.get() ? Messages.get("sql.cancelled") : Messages.get("sql.failed"));
        } else if (shown != null) {
            resultTabs.getSelectionModel().select(resultTab);
        }
        setBusy(false);
    }

    private void showRows(VdbSqlResult result) {
        resultTable.getColumns().clear();
        List<List<String>> rows = new ArrayList<>();
        for (List<Object> row : result.getRows()) {
            rows.add(row.stream().map(VdbSqlStaging::text).collect(Collectors.toList()));
        }
        List<String[]> columns = result.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            final int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(columns.get(i)[0]);
            column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(index < features.getValue().size()
                    ? features.getValue().get(index) : null));
            column.setCellFactory(tableColumn -> new ValueCell());
            column.setPrefWidth(DataPane.getColumnWidth(columns.get(i)[0], rows, index));
            column.setComparator(ValueCell.COMPARATOR);
            resultTable.getColumns().add(column);
        }
        resultRows.setAll(rows);
        resultTable.setPlaceholder(new Label(Messages.get("data.empty")));
    }

    /**
     * Reads all rows of the last query into a DuckDB file and opens the export wizard on them, a query with
     * vectors tells that they are exported as text
     */
    void export() {
        if (lastQuery == null || export == null || busy) {
            return;
        }
        List<String> vectors = VdbSqlStaging.vectorColumns(lastColumns, lastRows);
        String note = vectors.isEmpty() ? Messages.get("vdb.sql.note") : Messages.format("vdb.sql.vectorNote",
                String.join(", ", vectors));
        if (!vectors.isEmpty() && !Dialogs.confirm(getTabPane() != null ? getTabPane().getScene().getWindow()
                : null, Messages.get("sql.export"), note)) {
            return;
        }
        String sql = lastQuery;
        String table = VdbSqlStaging.tableName(sql);
        setBusy(true);
        cancelled.set(false);
        TaskRunner.run(() -> VdbSqlStaging.stage(getClient(), sql, table, cancelled, rows -> javafx.application.Platform.runLater(() ->
                statusLabel.setText(Messages.format("vdb.sql.reading", rows)))), staged -> {
            setBusy(false);
            statusLabel.setText(Messages.format("sql.status.rows", staged.rows));
            ConnectionProfile results = new ConnectionProfile();
            results.setName(Messages.format("vdb.sql.results", profile.getName()));
            results.setDbType(DbType.DUCKDB);
            results.setDatabase(staged.file.getAbsolutePath());
            export.export(DbNode.connection(results), "SELECT * FROM \"" + staged.table.replace("\"", "\"\"")
                    + "\"", staged.table, note);
        }, e -> {
            setBusy(false);
            boolean stopped = e instanceof ExportCancelledException;
            statusLabel.setText(stopped ? Messages.get("sql.cancelled") : Messages.get("sql.failed"));
            if (!stopped) {
                addMessage(Messages.format("sql.error", abbreviate(sql), e.getMessage()), true);
                resultTabs.getSelectionModel().select(messageTab);
            }
        });
    }

    private synchronized VdbClient getClient() {
        if (client == null) {
            client = profile.getType().createClient(profile.toSource());
        }
        return client;
    }

    private void addMessage(String message, boolean error) {
        messageList.getItems().add(error ? new String[]{message, "error"} : new String[]{message});
        messageList.scrollTo(messageList.getItems().size() - 1);
    }

    private static String abbreviate(String sql) {
        return StringUtils.abbreviate(sql.replaceAll("\\s+", " "), 120);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        runButton.setDisable(busy);
        runAllButton.setDisable(busy);
        stopButton.setDisable(!busy);
        exportButton.setDisable(busy || lastQuery == null);
    }

    /**
     * Stops after the page or the statement which is read, the cursor of the query is closed
     */
    void stop() {
        cancelled.set(true);
    }

    SqlEditor getEditor() {
        return editor;
    }

    public SqlCompletion getCompletion() {
        return completion;
    }

    String getLastQuery() {
        return lastQuery;
    }

    public void close() {
        stop();
        VdbClient current;
        synchronized (this) {
            current = client;
            client = null;
        }
        if (current != null) {
            TaskRunner.execute(current::close);
        }
    }

    public VdbProfile getProfile() {
        return profile;
    }
}
