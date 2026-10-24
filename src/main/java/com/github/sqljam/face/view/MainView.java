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

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import atlantafx.base.controls.CustomTextField;
import atlantafx.base.theme.Styles;

import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.service.DatabaseInfo;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.face.service.TransferService;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.DataFileImporter;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.SqlScriptRunner;
import com.github.sqljam.impexp.TableQuery;
import com.github.sqljam.jdbc.ManualTransaction;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * @Description: MainView is the main window: connection tree on the left and opened tables on the right
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class MainView extends BorderPane {

    private final AppContext context;
    private final ConnectionTree connectionTree;
    private final TabPane tabPane = new TabPane();
    private int sqlTabCount;
    private final Label statusLabel = new Label(Messages.get("status.ready"));

    public MainView(AppContext context) {
        this.context = context;
        getStyleClass().add("main-view");
        connectionTree = new ConnectionTree(context);
        connectionTree.setOnOpenTable(this::openTable);
        connectionTree.setOnOpenSourceItem(this::openSourceItem);
        connectionTree.setOnInfo(this::openInfo);
        connectionTree.setOnSqlQuery(this::openSqlTab);
        connectionTree.setOnExport(this::showExportDialog);
        connectionTree.setOnImportScripts(this::showImportDialog);
        connectionTree.setOnImportParquet(this::showImportParquetDialog);
        connectionTree.setOnEditConnection(node -> editConnection(node.getProfile()));
        connectionTree.setOnDeleteConnection(node -> deleteConnection(node.getProfile()));
        connectionTree.setOnStatus(statusLabel::setText);
        connectionTree.setOnDisconnect(this::closeTabs);

        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabPane.setId("tableTabs");
        statusLabel.setId("statusLabel");
        // The welcome is shown without tabs only, it would show through transparent rows of tables
        Node welcome = createWelcome();
        welcome.visibleProperty().bind(Bindings.isEmpty(tabPane.getTabs()));
        StackPane center = new StackPane(welcome, tabPane);
        tabPane.visibleProperty().bind(Bindings.isNotEmpty(tabPane.getTabs()));
        SplitPane splitPane = new SplitPane(createTreePane(), center);
        splitPane.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(splitPane.getItems().get(0), false);

        setTop(new VBox(createMenuBar(), createToolBar()));
        setCenter(splitPane);
        setBottom(createStatusBar());
    }

    /**
     * The connection tree under its filter, Escape clears the filter
     */
    private Node createTreePane() {
        CustomTextField filterField = new CustomTextField();
        filterField.setId("treeFilterField");
        filterField.setPromptText(Messages.get("tree.filter.prompt"));
        filterField.setLeft(Icons.of(Icons.SEARCH));
        Button clearButton = new Button(null, Icons.of(Icons.CANCEL));
        clearButton.setId("treeFilterClearButton");
        clearButton.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
        clearButton.setFocusTraversable(false);
        clearButton.visibleProperty().bind(filterField.textProperty().isNotEmpty());
        clearButton.setOnAction(event -> filterField.clear());
        filterField.setRight(clearButton);
        filterField.textProperty().bindBidirectional(connectionTree.filterProperty());
        filterField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                filterField.clear();
            }
        });
        VBox pane = new VBox(4, filterField, connectionTree);
        pane.setPadding(new Insets(4, 0, 0, 0));
        VBox.setVgrow(connectionTree, Priority.ALWAYS);
        return pane;
    }

    private Window getWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private VBox createWelcome() {
        Label hint = new Label(Messages.get("welcome.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setTextAlignment(TextAlignment.CENTER);
        ImageView logo = Branding.logoView(170);
        logo.setId("welcomeLogo");
        // The logo shows the name, no title is needed
        VBox welcome = new VBox(16, logo, hint);
        welcome.getStyleClass().add("welcome");
        welcome.setAlignment(Pos.CENTER);
        welcome.setMaxWidth(420);
        return welcome;
    }

    private MenuBar createMenuBar() {
        MenuItem newConnection = new MenuItem(Messages.get("action.newConnection"), Icons.of(Icons.ADD));
        newConnection.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));
        newConnection.setOnAction(event -> newConnection());
        MenuItem export = new MenuItem(Messages.get("action.export"), Icons.of(Icons.EXPORT));
        export.setAccelerator(new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN));
        export.setOnAction(event -> showExportDialog(connectionTree.getSelectedNode()));
        MenuItem importScripts = new MenuItem(Messages.get("action.importScripts"), Icons.of(Icons.IMPORT));
        importScripts.setAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN));
        importScripts.setOnAction(event -> showImportDialog(connectionTree.getSelectedNode()));
        MenuItem importParquet = new MenuItem(Messages.get("action.importParquet"), Icons.of(Icons.IMPORT));
        importParquet.setId("importParquetMenuItem");
        importParquet.setOnAction(event -> showImportParquetDialog(connectionTree.getSelectedNode()));
        MenuItem sqlQuery = new MenuItem(Messages.get("action.sqlQuery"), Icons.of(Icons.SQL));
        sqlQuery.setId("sqlQueryMenuItem");
        sqlQuery.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN));
        sqlQuery.setOnAction(event -> openSqlTab(connectionTree.getSelectedNode()));
        MenuItem openPackage = new MenuItem(Messages.get("package.open"), Icons.of(Icons.FOLDER));
        openPackage.setId("openPackageMenuItem");
        openPackage.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        openPackage.setOnAction(event -> openPackage());
        MenuItem exit = new MenuItem(Messages.get("action.exit"), Icons.of(Icons.EXIT));
        exit.setOnAction(event -> exit());
        Menu fileMenu = new Menu(Messages.get("menu.file"), null, newConnection, new SeparatorMenuItem(), sqlQuery,
                export, importScripts, importParquet);
        // Items of optional lines (vector databases) follow the imports when their settings switch them on
        for (MenuExtension extension : ServiceLoader.load(MenuExtension.class)) {
            if (extension.isEnabled(context.getSettingsStore().getConfig())) {
                MenuItem item = new MenuItem(extension.getText(), Icons.of(Icons.IMPORT));
                item.setId(extension.getId());
                item.setOnAction(event -> extension.show(getWindow(), context));
                fileMenu.getItems().add(item);
            }
        }
        fileMenu.getItems().addAll(new SeparatorMenuItem(), openPackage, new SeparatorMenuItem(), exit);

        Menu themeMenu = new Menu(Messages.get("menu.theme"), Icons.of(Icons.THEME));
        ToggleGroup themeGroup = new ToggleGroup();
        for (String themeName : ThemeManager.getThemeNames()) {
            RadioMenuItem item = new RadioMenuItem(themeName);
            item.setToggleGroup(themeGroup);
            item.setSelected(themeName.equals(context.getSettings().getTheme()));
            item.setOnAction(event -> {
                context.getSettings().setTheme(ThemeManager.apply(themeName));
                context.saveSettings();
            });
            themeMenu.getItems().add(item);
        }
        MenuItem refresh = new MenuItem(Messages.get("action.refresh"), Icons.of(Icons.REFRESH));
        refresh.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        refresh.setOnAction(event -> connectionTree.refreshSelected());
        Menu viewMenu = new Menu(Messages.get("menu.view"), null, themeMenu, new SeparatorMenuItem(), refresh);

        MenuItem restoreExample = new MenuItem(Messages.get("action.restoreExample"), Icons.of(Icons.CATALOG));
        restoreExample.setId("restoreExampleMenuItem");
        restoreExample.setOnAction(event -> ExampleActions.restore(getWindow(), context, profile -> {
            // Tabs of the old example database are closed
            closeTabs(profile);
            selectConnection(profile);
        }));
        MenuItem about = new MenuItem(Messages.get("action.about"), Icons.of(Icons.INFO));
        about.setOnAction(event -> Dialogs.showAbout(getWindow(), context::openDocument));
        Menu helpMenu = new Menu(Messages.get("menu.help"), null, restoreExample, new SeparatorMenuItem(), about);
        MenuBar menuBar = new MenuBar(fileMenu, viewMenu, helpMenu);
        menuBar.setUseSystemMenuBar(true);
        return menuBar;
    }

    private Button toolButton(String icon, String key, Runnable action) {
        Button button = new Button(null, Icons.of(icon));
        button.setId(key.replace('.', '-'));
        button.setTooltip(new Tooltip(Messages.get(key)));
        button.getStyleClass().add("flat");
        button.setOnAction(event -> action.run());
        return button;
    }

    private ToolBar createToolBar() {
        Button editButton = toolButton(Icons.EDIT, "action.editConnection", () -> {
            DbNode node = connectionTree.getSelectedNode();
            if (node != null && node.getProfile() != null) {
                editConnection(node.getProfile());
            } else if (connectionTree.getSelectedSource() != null) {
                connectionTree.editSource(connectionTree.getSelectedSource());
            }
        });
        Button deleteButton = toolButton(Icons.DELETE, "action.deleteConnection", () -> {
            DbNode node = connectionTree.getSelectedNode();
            if (node != null && node.getProfile() != null) {
                deleteConnection(node.getProfile());
            } else if (connectionTree.getSelectedSource() != null) {
                connectionTree.deleteSource(connectionTree.getSelectedSource());
            }
        });
        Button infoButton = toolButton(Icons.INFO, "action.info", () -> {
            DbNode node = connectionTree.getSelectedNode();
            openInfo(connectionTree.getSelectedSource() != null ? connectionTree.getSelectedSource()
                    : node != null && node.getProfile() != null ? DbNode.connection(node.getProfile()) : null);
        });
        connectionTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, item) -> {
            boolean connection = item != null && (item.getValue().getKind() == DbNode.Kind.CONNECTION
                    || item.getValue().getKind() == DbNode.Kind.SOURCE);
            editButton.setDisable(!connection);
            deleteButton.setDisable(!connection);
            // The info of the data source of any of its nodes, e.g. of a table
            infoButton.setDisable(item == null || (item.getValue().getProfile() == null && item.getValue()
                    .getKind() != DbNode.Kind.SOURCE));
        });
        infoButton.setText(Messages.get("action.info"));
        infoButton.setDisable(true);
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        Button exportButton = toolButton(Icons.EXPORT, "action.export",
                () -> showExportDialog(connectionTree.getSelectedNode()));
        exportButton.setText(Messages.get("action.export"));
        Button importButton = toolButton(Icons.IMPORT, "action.importScripts",
                () -> showImportDialog(connectionTree.getSelectedNode()));
        importButton.setText(Messages.get("action.import"));
        Button sqlButton = toolButton(Icons.SQL, "action.sqlQuery", () -> openSqlTab(connectionTree
                .getSelectedNode()));
        sqlButton.setText(Messages.get("action.sqlQuery"));
        ImageView mark = Branding.logoMarkView(22);
        mark.setId("toolbarLogo");
        StackPane markBox = new StackPane(mark);
        markBox.setPadding(new Insets(0, 4, 0, 6));
        // Exit at the right end
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button exitButton = toolButton(Icons.EXIT, "action.exit", this::exit);
        exitButton.setText(Messages.get("action.exit"));
        return new ToolBar(markBox, new Separator(),
                toolButton(Icons.ADD, "action.newConnection", this::newConnection), editButton,
                deleteButton, infoButton, toolButton(Icons.REFRESH, "action.refresh",
                connectionTree::refreshSelected),
                new Separator(), sqlButton, exportButton, importButton, spacer, exitButton);
    }

    /**
     * Closes the window like its close button, running transfers are confirmed by the application
     */
    private void exit() {
        Window window = getWindow();
        if (window != null) {
            window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
            if (window.isShowing()) {
                ((Stage) window).close();
            }
        }
    }

    private HBox createStatusBar() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(14, 14);
        indicator.visibleProperty().bind(TaskRunner.runningProperty().greaterThan(0));
        Label taskLabel = new Label();
        taskLabel.textProperty().bind(Bindings.when(TaskRunner.runningProperty().greaterThan(0))
                .then(Messages.get("status.working")).otherwise(""));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(8, statusLabel, spacer, taskLabel, indicator,
                Branding.repositoryMark(context::openDocument));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        return statusBar;
    }

    /**
     * Selects and expands the connection, e.g. after login
     */
    public void selectConnection(ConnectionProfile profile) {
        if (profile != null) {
            connectionTree.reloadConnections();
            Platform.runLater(() -> connectionTree.selectConnection(profile.getId()));
        }
    }

    /**
     * A new data source of the category which the user chooses in the form: relational, OLAP, or a kind of an
     * extension (vector databases) when one is switched on
     */
    private void newConnection() {
        connectionTree.createConnection(null);
    }

    private void editConnection(ConnectionProfile profile) {
        Optional<ConnectionProfile> edited = ConnectionDialog.show(getWindow(), context,
                context.getProfileRegistry().findProfile(profile.getId()).orElse(profile));
        edited.ifPresent(result -> {
            context.getSessionManager().closeSession(result.getId());
            selectConnection(result);
        });
    }

    private void deleteConnection(ConnectionProfile profile) {
        if (!Dialogs.confirm(getWindow(), Messages.get("connection.delete.title"),
                Messages.format("connection.delete.message", profile.getName()))) {
            return;
        }
        try {
            context.getProfileRegistry().removeProfile(profile.getId());
            context.getSessionManager().closeSession(profile.getId());
            closeTabs(profile);
            connectionTree.reloadConnections();
        } catch (IOException e) {
            Dialogs.showError(getWindow(), Messages.get("connection.save.error"), e);
        }
    }

    /**
     * Closes table tabs of the data source, e.g. after it is disconnected or deleted
     */
    private void closeTabs(ConnectionProfile profile) {
        List<Tab> closed = tabPane.getTabs().stream().filter(tab -> tab.getUserData() instanceof String
                && ((String) tab.getUserData()).startsWith(profile.getId() + "|")).collect(Collectors.toList());
        tabPane.getTabs().removeAll(closed);
        // Removed tabs are not closed by the user, the connections of SQL editors are closed here
        closed.stream().filter(tab -> tab instanceof SqlTab).forEach(tab -> ((SqlTab) tab).close());
    }

    /**
     * Opens a SQL editor on the selected data source, catalog, schema or table, the first data source if nothing
     * is selected
     */
    void openSqlTab(DbNode node) {
        DbNode target = node;
        if (target == null || target.getProfile() == null) {
            if (context.getProfileRegistry().getProfiles().isEmpty()) {
                Dialogs.showInfo(getWindow(), Messages.get("action.sqlQuery"), Messages.get(
                        "export.error.noConnections"));
                return;
            }
            target = DbNode.connection(context.getProfileRegistry().getProfiles().get(0));
        }
        SqlTab tab = new SqlTab(context, target, ++sqlTabCount);
        tab.setOnExport(this::showQueryExportDialog);
        tab.setUserData(target.getProfile().getId() + "|sql|" + sqlTabCount);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    /**
     * Exports all rows of a query of the SQL editor as a table
     */
    private void showQueryExportDialog(DbNode node, String sql) {
        ExportDialog dialog = ExportDialog.forQuery(getWindow(), context, node, sql);
        Optional<TransferRequest> request = dialog.showAndWait();
        connectionTree.addNewConnections();
        request.ifPresent(transferRequest -> startTransfer(transferRequest, 1));
    }

    /**
     * Opens the tab of an item of a source of an extension, e.g. a collection of a vector database, like a table
     */
    void openSourceItem(DbNode node) {
        String key = node.getSource().getId() + "|item|" + node.getItem().getKey();
        for (Tab tab : tabPane.getTabs()) {
            if (key.equals(tab.getUserData())) {
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        Tab tab = node.getSource().openItem(node.getItem(), getWindow(), context);
        tab.setUserData(key);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    /**
     * Opens the info tab of a data source or of a source of an extension, an opened one is selected
     */
    void openInfo(DbNode node) {
        if (node == null || (node.getProfile() == null && node.getSource() == null)) {
            return;
        }
        String key = (node.getSource() != null ? node.getSource().getId() : node.getProfile().getId()) + "|info";
        for (Tab tab : tabPane.getTabs()) {
            if (key.equals(tab.getUserData())) {
                tabPane.getSelectionModel().select(tab);
                ((InfoTab) tab).refresh();
                return;
            }
        }
        InfoTab tab;
        if (node.getSource() != null) {
            DataSourceExtension.Source source = node.getSource();
            tab = new InfoTab(source.getName(), DbIcons.of(source.getTypeName()), source::loadOverview,
                    source::loadHealth);
        } else {
            ConnectionProfile profile = node.getProfile();
            DatabaseInfo info = new DatabaseInfo(profile, context.getSessionManager().getSession(profile));
            tab = new InfoTab(profile.getName(), DbTypeCells.iconOf(profile), info::getOverview, info::getHealth);
        }
        tab.setUserData(key);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    void openTable(DbNode node) {
        String key = node.getProfile().getId() + "|" + TableTab.getKey(node);
        for (Tab tab : tabPane.getTabs()) {
            if (key.equals(tab.getUserData())) {
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        TableTab tab = new TableTab(context, node);
        tab.setOnExport(this::showExportDialog);
        tab.setUserData(key);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    private void showExportDialog(DbNode node) {
        showExportDialog(node, null);
    }

    /**
     * Export of the selected node, rows of a table may be narrowed by the query of its data pane
     */
    private void showExportDialog(DbNode node, TableQuery query) {
        if (context.getProfileRegistry().getProfiles().isEmpty()) {
            Dialogs.showInfo(getWindow(), Messages.get("export.title"), Messages.get("export.error.noConnections"));
            return;
        }
        ExportDialog dialog = new ExportDialog(getWindow(), context, node, query);
        Optional<TransferRequest> request = dialog.showAndWait();
        // Data sources created as the target in the dialog
        connectionTree.addNewConnections();
        request.ifPresent(transferRequest -> startTransfer(transferRequest, dialog.getListedTableCount()));
    }

    private void startTransfer(TransferRequest transferRequest, int listedTableCount) {
        int totalTables = transferRequest.getTables().isEmpty() ? listedTableCount
                : transferRequest.getTables().size();
        String title = transferRequest.getTarget() == TransferRequest.Target.SCRIPT
                ? Messages.get("progress.exportTitle") : Messages.format("progress.importTitle",
                transferRequest.getTargetProfile().getName());
        ProgressDialog progress = new ProgressDialog(getWindow(), context, title, totalTables);
        if (transferRequest.getTarget() == TransferRequest.Target.SCRIPT) {
            progress.setOutputDirectory(transferRequest.getOutputDirectory());
        }
        TransferService transferService = context.getTransferService();
        progress.setSkipErrors(!transferRequest.isFailFast());
        // One transaction is committed when the import is complete, or by the user when it is confirmed
        AtomicReference<ManualTransaction> transaction = new AtomicReference<>();
        progress.run(() -> {
            if (transferRequest.getTarget() == TransferRequest.Target.DATABASE
                    && transferRequest.isOneTransaction()) {
                transaction.set(transferService.beginTransaction(transferRequest.getTargetProfile(),
                        transferRequest.getTargetCatalog(), null));
            }
            transferService.transfer(transferRequest, transaction.get(), progress.getListener());
            return null;
        }, null, transaction::get, transferRequest.isConfirmCommit(), () -> {
            if (transferRequest.getTarget() == TransferRequest.Target.DATABASE) {
                context.getSessionManager().closeSession(transferRequest.getTargetProfile().getId());
            }
        });
    }

    private void showImportDialog(DbNode node) {
        // Without data sources, the target is created in the dialog
        Optional<ImportPackageDialog.ImportRequest> request = new ImportPackageDialog(getWindow(), context, node)
                .showAndWait();
        connectionTree.addNewConnections();
        request.ifPresent(this::startImport);
    }

    private void startImport(ImportPackageDialog.ImportRequest importRequest) {
        int totalTables = importRequest.getManifest() != null ? importRequest.getManifest().getTables().size()
                : 0;
        ProgressDialog progress = new ProgressDialog(getWindow(), context, Messages.format(
                "progress.importTitle", importRequest.getTarget().getName()), totalTables);
        progress.setSkipErrors(!importRequest.isStopOnError());
        AtomicReference<ManualTransaction> transaction = new AtomicReference<>();
        progress.run(() -> {
            if (importRequest.isOneTransaction()) {
                transaction.set(context.getTransferService().beginTransaction(importRequest.getTarget(),
                        importRequest.getCatalog(), importRequest.getSchema()));
            }
            return context.getTransferService().importScripts(importRequest.getTarget(), importRequest
                    .getCatalog(), importRequest.getSchema(), importRequest.getDirectory(), importRequest
                    .isStopOnError(), importRequest.getCommitRows(), transaction.get(), progress.getListener());
        }, (AbstractFileImporter importer) -> Messages.format("import.summary", importer.getExecutedCount(),
                importer.getLobCount(), importer.getFailedCount()), transaction::get, importRequest.isConfirmCommit(),
                () -> context.getSessionManager().closeSession(importRequest.getTarget().getId()));
    }

    private void showImportParquetDialog(DbNode node) {
        Optional<ImportParquetDialog.ParquetRequest> request = new ImportParquetDialog(getWindow(), context, node)
                .showAndWait();
        connectionTree.addNewConnections();
        request.ifPresent(this::startFileImport);
    }

    private void startFileImport(ImportParquetDialog.ParquetRequest parquetRequest) {
        ProgressDialog progress = new ProgressDialog(getWindow(), context, Messages.format(
                "progress.importTitle", parquetRequest.getTarget().getName()), 1);
        progress.setSkipErrors(parquetRequest.isSkipErrors());
        AtomicReference<ManualTransaction> transaction = new AtomicReference<>();
        progress.run(() -> {
            if (parquetRequest.isOneTransaction()) {
                transaction.set(context.getTransferService().beginTransaction(parquetRequest.getTarget(),
                        parquetRequest.getCatalog(), parquetRequest.getSchema()));
            }
            return context.getTransferService().importFiles(parquetRequest.getTarget(), parquetRequest
                    .getCatalog(), parquetRequest.getSchema(), parquetRequest.getFiles(), parquetRequest
                    .getTableName(), parquetRequest.getMode(), parquetRequest.getKeyColumns(), parquetRequest
                    .getCsvOptions(), parquetRequest.isSkipErrors(), parquetRequest.getCommitRows(), transaction.get(),
                    progress.getListener());
        }, (DataFileImporter importer) -> Messages.format("parquet.summary", importer.getImportedRows(),
                parquetRequest.getTableName()), transaction::get, parquetRequest.isConfirmCommit(),
                () -> context.getSessionManager().closeSession(parquetRequest.getTarget().getId()));
    }

    /**
     * Lists and previews files of an export package (sql scripts or Parquet files)
     */
    private void openPackage() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.get("package.chooseDirectory"));
        String lastDirectory = context.getSettings().getLastImportDirectory();
        if (lastDirectory != null && new File(lastDirectory).isDirectory()) {
            chooser.setInitialDirectory(new File(lastDirectory));
        }
        File directory = chooser.showDialog(getWindow());
        if (directory != null) {
            new PackageViewer(getWindow(), context, directory).show();
        }
    }
}
