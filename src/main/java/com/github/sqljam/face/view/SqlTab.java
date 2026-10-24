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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SqlResult;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.face.service.SqlStatements;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.jdbc.JdbcUtils;
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
 * @Description: SqlTab runs SQL on a data source and exports the rows of a query, SqlJam is an import and export
 *               tool, the editor serves exports: Ctrl+Enter runs the selected text or the statement at the caret,
 *               Ctrl+Shift+Enter runs all statements, every statement is committed at once. The first rows of the
 *               last query are shown, Export sends the query to the export wizard which reads all its rows. The
 *               tab has a connection of its own, so session settings and temporary tables stay between runs.
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class SqlTab extends Tab {

    /**
     * Rows of a query shown in the editor, an export reads all of them
     */
    static final int MAX_ROWS = 500;
    private static final KeyCombination RUN = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination RUN_ALL = new KeyCodeCombination(KeyCode.ENTER,
            KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    private final ConnectionProfile profile;
    private final DatabaseSession session;
    private final SqlEditor editor = new SqlEditor();
    private final TableView<List<String>> resultTable = new TableView<>();
    private final ObservableList<List<String>> resultRows = TableColumns.sortedRows(resultTable);
    private final ListView<LogEntry> messageList = new ListView<>();
    private final TabPane resultTabs = new TabPane();
    private final Tab resultTab = new Tab(Messages.get("sql.result"));
    private final Tab messageTab = new Tab(Messages.get("sql.messages"));
    private final Button runButton = new Button(Messages.get("sql.run"), Icons.of(Icons.START));
    private final Button runAllButton = new Button(Messages.get("sql.runAll"), Icons.of(Icons.START));
    private final Button stopButton = new Button(Messages.get("sql.stop"), Icons.of(Icons.CANCEL));
    private final Button exportButton = new Button(Messages.get("sql.export"), Icons.of(Icons.EXPORT));
    private final Label statusLabel = new Label();
    private final AtomicReference<Statement> running = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Connection connection;
    private String catalog;
    private String schema;
    private String lastQuery;
    private boolean busy;
    private BiConsumer<DbNode, String> onExport;

    private static class LogEntry {

        private final String message;
        private final boolean error;

        LogEntry(String message, boolean error) {
            this.message = message;
            this.error = error;
        }
    }

    /**
     * @param node data source, catalog, schema or table of the tree, a table is selected in the editor
     */
    public SqlTab(AppContext context, DbNode node, int number) {
        this.profile = node.getProfile();
        this.session = context.getSessionManager().getSession(profile);
        this.catalog = node.getCatalog();
        this.schema = node.getSchema();
        setText(Messages.format("sql.tabTitle", number, profile.getName()));
        setGraphic(Icons.of(Icons.SQL));
        setTooltip(new Tooltip(describeTarget()));

        runButton.setId("sqlRunButton");
        runAllButton.setId("sqlRunAllButton");
        stopButton.setId("sqlStopButton");
        exportButton.setId("sqlExportButton");
        statusLabel.setId("sqlStatusLabel");
        resultTable.setId("sqlResultTable");
        TableColumns.fill(resultTable);
        messageList.setId("sqlMessageList");
        runButton.setTooltip(new Tooltip(Messages.get("sql.run.tooltip")));
        runAllButton.setTooltip(new Tooltip(Messages.get("sql.runAll.tooltip")));
        exportButton.setTooltip(new Tooltip(Messages.get("sql.export.tooltip")));
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
        if (node.getTable() != null) {
            Dialect dialect = profile.getDbType().createDialect();
            String table = dialect.quoteIdentifier(node.getTable().getName());
            String tableSchema = node.getTable().getSchema();
            editor.setText("SELECT * FROM " + (StringUtils.isNotBlank(tableSchema) ? dialect.quoteIdentifier(
                    tableSchema) + "." + table : table));
            this.schema = StringUtils.defaultIfBlank(tableSchema, schema);
        }

        Label targetLabel = new Label(describeTarget());
        targetLabel.setId("sqlTargetLabel");
        targetLabel.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar toolBar = new ToolBar(runButton, runAllButton, stopButton, exportButton, spacer, targetLabel);

        resultTable.setPlaceholder(new Label(Messages.get("sql.noResult")));
        resultTab.setContent(resultTable);
        resultTab.setClosable(false);
        messageList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(LogEntry item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("log-error");
                setText(empty || item == null ? null : item.message);
                if (!empty && item != null && item.error) {
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
        // The welcome page behind the tabs is covered
        root.getStyleClass().add("sql-tab");
        setContent(root);
        setOnClosed(event -> close());
    }

    /**
     * The export wizard receives the data source, catalog and schema of the editor and the SQL of the last query
     */
    public void setOnExport(BiConsumer<DbNode, String> onExport) {
        this.onExport = onExport;
    }

    SqlEditor getEditor() {
        return editor;
    }

    String getLastQuery() {
        return lastQuery;
    }

    boolean isBusy() {
        return busy;
    }

    private String describeTarget() {
        return profile.getName() + (StringUtils.isNotBlank(catalog) ? " / " + catalog : "")
                + (StringUtils.isNotBlank(schema) ? " / " + schema : "");
    }

    /**
     * Runs the selected text or the statement at the caret, or all statements. Statements run one after another,
     * the first error stops the rest.
     */
    void run(boolean all) {
        if (busy) {
            return;
        }
        String selected = editor.getSelectedText();
        List<String> statements;
        if (all) {
            statements = toTexts(SqlStatements.split(editor.getText()));
        } else if (StringUtils.isNotBlank(selected)) {
            statements = toTexts(SqlStatements.split(selected));
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
        List<SqlResult> results = new ArrayList<>();
        TaskRunner.run(() -> {
            Connection current = getConnection();
            for (String sql : statements) {
                if (cancelled.get()) {
                    break;
                }
                try {
                    SqlResult result = session.execute(current, sql, MAX_ROWS, running::set);
                    synchronized (results) {
                        results.add(result);
                    }
                } catch (SQLException e) {
                    throw new SqlFailure(sql, e, results);
                }
            }
            return results;
        }, this::showResults, e -> {
            if (e instanceof SqlFailure) {
                showResults(((SqlFailure) e).results);
                addMessage(Messages.format("sql.error", abbreviate(((SqlFailure) e).sql), e.getCause()
                        .getMessage()), true);
                statusLabel.setText(cancelled.get() ? Messages.get("sql.cancelled") : Messages.get("sql.failed"));
            } else {
                addMessage(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getName()), true);
                statusLabel.setText(Messages.get("sql.failed"));
            }
            resultTabs.getSelectionModel().select(messageTab);
            setBusy(false);
        });
    }

    /**
     * Error of a statement, the results of the statements before it are shown
     */
    private static class SqlFailure extends RuntimeException {

        private final String sql;
        private final transient List<SqlResult> results;

        SqlFailure(String sql, SQLException cause, List<SqlResult> results) {
            super(cause.getMessage(), cause);
            this.sql = sql;
            this.results = results;
        }
    }

    private static List<String> toTexts(List<SqlStatements.Statement> statements) {
        return statements.stream().map(SqlStatements.Statement::getText).collect(Collectors.toList());
    }

    /**
     * The connection of the editor, opened in the catalog and schema of the tab at the first run
     */
    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = session.openSqlConnection(catalog, schema);
            // The defaults of the connection are the source of exports
            DbType dbType = profile.getDbType();
            if (StringUtils.isBlank(catalog) && dbType.isCatalogSupported() && dbType != DbType.POSTGRESQL) {
                catalog = connection.getCatalog();
            }
            if (StringUtils.isBlank(schema) && dbType.isSchemaSupported()) {
                schema = connection.getSchema();
            }
        }
        return connection;
    }

    private void showResults(List<SqlResult> results) {
        SqlResult shown = null;
        for (SqlResult result : results) {
            if (result.isQuery()) {
                addMessage(Messages.format(result.isTruncated() ? "sql.rowsFirst" : "sql.rows", result.getRows()
                        .size(), result.getElapsedMillis(), abbreviate(result.getSql())), false);
                shown = result;
            } else {
                addMessage(Messages.format("sql.updated", result.getUpdateCount(), result.getElapsedMillis(),
                        abbreviate(result.getSql())), false);
            }
        }
        if (shown != null) {
            showRows(shown);
            lastQuery = shown.getSql();
            resultTabs.getSelectionModel().select(resultTab);
            statusLabel.setText(Messages.format(shown.isTruncated() ? "sql.status.first" : "sql.status.rows",
                    shown.getRows().size()));
        } else if (!results.isEmpty()) {
            resultTabs.getSelectionModel().select(messageTab);
            statusLabel.setText(Messages.format("sql.status.done", results.size()));
        }
        setBusy(false);
    }

    private void showRows(SqlResult result) {
        resultTable.getColumns().clear();
        List<String> columns = result.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            final int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(columns.get(i));
            column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(features.getValue().get(index)));
            column.setCellFactory(tableColumn -> new DataPane.NullableCell());
            column.setPrefWidth(DataPane.getColumnWidth(columns.get(i), result.getRows(), index));
            column.setComparator(ValueCell.COMPARATOR);
            resultTable.getColumns().add(column);
        }
        resultRows.setAll(result.getRows());
        resultTable.setPlaceholder(new Label(Messages.get("data.empty")));
    }

    private void addMessage(String message, boolean error) {
        messageList.getItems().add(new LogEntry(message, error));
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
     * Cancels the running statement, the statements after it are not run
     */
    void stop() {
        cancelled.set(true);
        Statement statement = running.get();
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Unable to cancel the statement: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Opens the export wizard for the last query, it reads all rows of the query
     */
    void export() {
        if (lastQuery == null || onExport == null) {
            return;
        }
        DbNode node = StringUtils.isNotBlank(schema) ? DbNode.schema(profile, catalog, schema)
                : StringUtils.isNotBlank(catalog) ? DbNode.catalog(profile, catalog) : DbNode.connection(profile);
        onExport.accept(node, lastQuery);
    }

    /**
     * Closes the connection of the editor, a running statement is cancelled
     */
    public void close() {
        stop();
        Connection current;
        synchronized (this) {
            current = connection;
            connection = null;
        }
        if (current != null) {
            TaskRunner.execute(() -> JdbcUtils.closeQuietly(current));
        }
    }

    public ConnectionProfile getProfile() {
        return profile;
    }
}
