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
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import atlantafx.base.controls.CustomTextField;
import atlantafx.base.theme.Styles;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.service.DatabaseInfo;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.face.service.JdbcMetrics;
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
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
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

    /**
     * Share of the connection tree of the width of the window
     */
    static final double TREE_WIDTH = 0.24;
    static final double MIN_SPLIT_WIDTH = 200;


    private final AppContext context;
    private final ConnectionTree connectionTree;
    private final TabPane tabPane = new TabPane();
    private final List<RadioMenuItem> themeRadios = new ArrayList<>();
    private MenuBar menuBar;
    private int sqlTabCount;
    private final Label statusLabel = new Label(Messages.get("status.ready"));

    public MainView(AppContext context) {
        this.context = context;
        getStyleClass().add("main-view");
        // Reports of the transfers are shown on their own pages
        context.setReportViewer(this::openReport);
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
        splitPane.setDividerPositions(TREE_WIDTH);
        SplitPane.setResizableWithParent(splitPane.getItems().get(0), false);
        // The tree keeps the width which the user dragged it to when the window is resized. Until then it has its
        // share of every width: the first layouts (before the view has its place in a window, or of a narrow
        // preferred width) would keep it narrow otherwise.
        boolean[] dragged = {false};
        splitPane.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getTarget() instanceof Node && ((Node) event.getTarget()).getStyleClass().contains(
                    "split-pane-divider")) {
                dragged[0] = true;
            }
        });
        splitPane.widthProperty().addListener((obs, oldWidth, width) -> {
            if (!dragged[0] && width.doubleValue() > MIN_SPLIT_WIDTH) {
                // After the layout of the new width, which keeps the width of the tree
                Platform.runLater(() -> {
                    if (!dragged[0]) {
                        splitPane.setDividerPositions(TREE_WIDTH);
                    }
                });
            }
        });

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
        // The first step without data sources, like the empty explorer of DataGrip
        Button newDataSource = new Button(Messages.get("welcome.newDataSource"), Icons.of(Icons.ADD));
        newDataSource.setId("welcomeNewDataSourceButton");
        newDataSource.getStyleClass().add(Styles.ACCENT);
        newDataSource.setOnAction(event -> newConnection());
        // The logo shows the name, no title is needed
        VBox welcome = new VBox(16, logo, hint, newDataSource);
        welcome.getStyleClass().add("welcome");
        welcome.setAlignment(Pos.CENTER);
        welcome.setMaxWidth(420);
        return welcome;
    }

    private static MenuItem item(String key, String icon, KeyCombination accelerator, Runnable action) {
        MenuItem item = new MenuItem(Messages.get(key), icon != null ? Icons.of(icon) : null);
        item.setId(key.replace('.', '-') + "-menu");
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.setOnAction(event -> action.run());
        return item;
    }

    private static KeyCombination shortcut(KeyCode code, KeyCombination.Modifier... modifiers) {
        KeyCombination.Modifier[] all = new KeyCombination.Modifier[modifiers.length + 1];
        all[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(modifiers, 0, all, 1, modifiers.length);
        return new KeyCodeCombination(code, all);
    }

    /**
     * The menus of the main window: what the toolbar and the menus of the tree do, in one place. Items which need a
     * data source are switched off while none is selected.
     */
    private MenuBar createMenuBar() {
        menuBar = new MenuBar(createFileMenu(), createDatabaseMenu(), createTransferMenu(), createViewMenu(),
                createHelpMenu());
        menuBar.setUseSystemMenuBar(true);
        return menuBar;
    }

    private Menu createFileMenu() {
        MenuItem newConnection = item("action.newConnection", Icons.ADD, shortcut(KeyCode.N), this::newConnection);
        MenuItem edit = item("action.editConnection", Icons.EDIT, null, this::editSelected);
        MenuItem delete = item("action.deleteConnection", Icons.DELETE, null, this::deleteSelected);
        MenuItem openPackage = item("package.open", Icons.FOLDER, shortcut(KeyCode.O), this::openPackage);
        openPackage.setId("openPackageMenuItem");
        MenuItem exit = item("action.exit", Icons.EXIT, null, this::exit);
        Menu menu = new Menu(Messages.get("menu.file"), null, newConnection, edit, delete, new SeparatorMenuItem(),
                openPackage, new SeparatorMenuItem(), exit);
        menu.setOnShowing(event -> {
            boolean source = isSourceSelected();
            edit.setDisable(!source);
            delete.setDisable(!source);
        });
        return menu;
    }

    private Menu createDatabaseMenu() {
        MenuItem connect = item("tree.connect", Icons.CONNECT, null, () -> {
            TreeItem<DbNode> item = selectedConnectionItem();
            if (item != null) {
                item.setExpanded(true);
            }
        });
        MenuItem disconnect = item("tree.disconnect", Icons.DISCONNECT, null, () -> {
            TreeItem<DbNode> item = selectedConnectionItem();
            if (item != null) {
                connectionTree.disconnect(item);
            }
        });
        MenuItem disconnectAll = item("action.disconnectAll", Icons.DISCONNECT, null, () -> List.copyOf(
                connectionTree.getConnectionItems()).forEach(connectionTree::disconnect));
        MenuItem refresh = item("action.refresh", Icons.REFRESH, new KeyCodeCombination(KeyCode.F5),
                connectionTree::refreshSelected);
        MenuItem sqlQuery = item("action.sqlQuery", Icons.SQL, shortcut(KeyCode.Q, KeyCombination.SHIFT_DOWN),
                () -> openSqlTab(connectionTree.getSelectedNode()));
        sqlQuery.setId("sqlQueryMenuItem");
        MenuItem info = item("action.info", Icons.INFO, null, this::infoSelected);
        MenuItem dashboard = item("action.dashboard", Icons.HEALTH, shortcut(KeyCode.D, KeyCombination.SHIFT_DOWN),
                this::dashboardSelected);
        Menu menu = new Menu(Messages.get("menu.database"), null, connect, disconnect, disconnectAll,
                new SeparatorMenuItem(), refresh, new SeparatorMenuItem(), sqlQuery, info, dashboard);
        menu.setOnShowing(event -> {
            TreeItem<DbNode> item = selectedConnectionItem();
            boolean connected = item != null && connectionTree.isConnected(item);
            connect.setDisable(item == null || connected);
            disconnect.setDisable(!connected);
            disconnectAll.setDisable(connectionTree.getConnectionItems().stream().noneMatch(
                    connectionTree::isConnected));
            sqlQuery.setDisable(connectionTree.getSelectedNode() == null);
            info.setDisable(!isInfoAvailable());
            DbNode source = connectionTree.getSelectedSource();
            dashboard.setDisable(item == null && (source == null || source.getSource().getDashboard() == null));
        });
        return menu;
    }

    private Menu createTransferMenu() {
        MenuItem export = item("action.export", Icons.EXPORT, shortcut(KeyCode.E),
                () -> showExportDialog(connectionTree.getSelectedNode()));
        MenuItem importScripts = item("action.importScripts", Icons.IMPORT, shortcut(KeyCode.I),
                () -> showImportDialog(connectionTree.getSelectedNode()));
        MenuItem importParquet = item("action.importParquet", Icons.IMPORT, null,
                () -> showImportParquetDialog(connectionTree.getSelectedNode()));
        importParquet.setId("importParquetMenuItem");
        Menu menu = new Menu(Messages.get("menu.transfer"), null, export, importScripts, importParquet);
        // Items of optional lines (vector databases) follow the imports when their settings switch them on
        for (MenuExtension extension : ServiceLoader.load(MenuExtension.class)) {
            if (extension.isEnabled(context.getSettingsStore().getConfig())) {
                MenuItem item = new MenuItem(extension.getText(), Icons.of(Icons.IMPORT));
                item.setId(extension.getId());
                item.setOnAction(event -> extension.show(getWindow(), context));
                menu.getItems().add(item);
            }
        }
        MenuItem sync = item("sync.title", Icons.REFRESH, null, this::openSync);
        sync.setId("syncMenuItem");
        MenuItem newSync = item("sync.new", Icons.ADD, null, () -> {
            openSync();
            Tab tab = tabPane.getSelectionModel().getSelectedItem();
            if (tab instanceof SyncTab) {
                ((SyncTab) tab).edit(null);
            }
        });
        newSync.setId("newSyncMenuItem");
        MenuItem history = item("history.title", Icons.HISTORY, shortcut(KeyCode.H, KeyCombination.SHIFT_DOWN),
                this::openHistory);
        history.setId("historyMenuItem");
        MenuItem reports = item("action.reportsFolder", Icons.FOLDER, null, () -> context.openDocument(context
                .getReportStore().getDirectory().toURI().toString()));
        menu.getItems().addAll(new SeparatorMenuItem(), newSync, sync, new SeparatorMenuItem(), history, reports);
        return menu;
    }

    private Menu createViewMenu() {
        Menu themeMenu = new Menu(Messages.get("menu.theme"), Icons.of(Icons.THEME));
        themeMenu.setId("themeMenu");
        themeMenu.getItems().addAll(themeItems());
        MenuItem nextTheme = item("action.nextTheme", Icons.THEME, shortcut(KeyCode.T, KeyCombination.SHIFT_DOWN),
                this::nextTheme);
        MenuItem filter = item("action.filter", Icons.SEARCH, shortcut(KeyCode.F), () -> {
            Node field = lookup("#treeFilterField");
            if (field != null) {
                field.requestFocus();
            }
        });
        MenuItem collapse = item("action.collapseAll", null, null, () -> connectionTree.getRoot().getChildren()
                .forEach(group -> group.getChildren().forEach(item -> item.setExpanded(false))));
        MenuItem closeTab = item("action.closeTab", Icons.CANCEL, shortcut(KeyCode.W), () -> {
            Tab tab = tabPane.getSelectionModel().getSelectedItem();
            if (tab != null) {
                closeTabs(List.of(tab));
            }
        });
        MenuItem closeOthers = item("action.closeOtherTabs", null, null, () -> {
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            closeTabs(tabPane.getTabs().stream().filter(tab -> tab != selected).collect(Collectors.toList()));
        });
        MenuItem closeAll = item("action.closeAllTabs", null, shortcut(KeyCode.W, KeyCombination.SHIFT_DOWN),
                () -> closeTabs(List.copyOf(tabPane.getTabs())));
        MenuItem fullScreen = item("action.fullScreen", null, new KeyCodeCombination(KeyCode.F11), () -> {
            if (getWindow() instanceof Stage) {
                Stage stage = (Stage) getWindow();
                stage.setFullScreen(!stage.isFullScreen());
            }
        });
        Menu menu = new Menu(Messages.get("menu.view"), null, themeMenu, nextTheme, new SeparatorMenuItem(), filter,
                collapse, new SeparatorMenuItem(), closeTab, closeOthers, closeAll, new SeparatorMenuItem(),
                fullScreen);
        menu.setOnShowing(event -> {
            int tabs = tabPane.getTabs().size();
            closeTab.setDisable(tabs == 0);
            closeOthers.setDisable(tabs < 2);
            closeAll.setDisable(tabs == 0);
        });
        return menu;
    }

    private Menu createHelpMenu() {
        String repository = Branding.REPOSITORY_URL;
        Menu videos = new Menu(Messages.get("help.videos"), Icons.of(Icons.START));
        for (String video : new String[]{"sql", "vectors", "sync"}) {
            videos.getItems().add(item("help.video." + video, null, null, () -> context.openDocument(repository
                    + "/blob/master/docs/assets/sqljam-" + video + ".mp4")));
        }
        MenuItem docs = item("help.docs", Icons.OPEN, null, () -> context.openDocument(repository + "#readme"));
        MenuItem guide = item("help.developerGuide", Icons.OPEN, null, () -> context.openDocument(repository
                + "/blob/master/docs/developer-guide.md"));
        MenuItem issue = item("help.issue", Icons.GITHUB, null, () -> context.openDocument(repository + "/issues"));
        MenuItem settings = item("help.settingsFile", Icons.SETTINGS, null, () -> {
            File file = context.getSettingsStore().getConfig().getUserFile();
            context.openDocument((file.exists() ? file : file.getParentFile()).toURI().toString());
        });
        MenuItem dataFolder = item("help.dataFolder", Icons.FOLDER, null, () -> context.openDocument(context
                .getSettingsStore().getConfig().getUserFile().getAbsoluteFile().getParentFile().toURI().toString()));
        MenuItem restoreExample = item("action.restoreExample", Icons.CATALOG, null, () -> ExampleActions.restore(
                getWindow(), context, profile -> {
                    // Tabs of the old example database are closed
                    closeTabs(profile);
                    selectConnection(profile);
                }));
        restoreExample.setId("restoreExampleMenuItem");
        MenuItem shortcuts = item("help.shortcuts", null, null, this::showShortcuts);
        MenuItem about = item("action.about", Icons.INFO, null, () -> Dialogs.showAbout(getWindow(),
                context::openDocument));
        return new Menu(Messages.get("menu.help"), null, videos, docs, guide, issue, new SeparatorMenuItem(),
                settings, dataFolder, new SeparatorMenuItem(), restoreExample, new SeparatorMenuItem(), shortcuts,
                about);
    }

    /**
     * Items of the themes, the theme in use is checked, the same items in the menu and in the toolbar
     */
    private List<MenuItem> themeItems() {
        ToggleGroup group = new ToggleGroup();
        List<MenuItem> items = new ArrayList<>();
        for (String themeName : ThemeManager.getThemeNames()) {
            RadioMenuItem item = new RadioMenuItem(themeName);
            item.setToggleGroup(group);
            item.setSelected(themeName.equals(context.getSettings().getTheme()));
            item.setOnAction(event -> applyTheme(themeName));
            items.add(item);
            themeRadios.add(item);
        }
        return items;
    }

    /**
     * Applies the theme, saves it and checks it in every list of the themes
     */
    void applyTheme(String themeName) {
        String applied = ThemeManager.apply(themeName);
        context.getSettings().setTheme(applied);
        context.saveSettings();
        themeRadios.forEach(item -> item.setSelected(item.getText().equals(applied)));
    }

    void nextTheme() {
        List<String> names = ThemeManager.getThemeNames();
        int index = names.indexOf(context.getSettings().getTheme());
        applyTheme(names.get((index + 1) % names.size()));
    }

    /**
     * The keys of the menus, read from the menus themselves
     */
    List<String[]> getShortcuts() {
        List<String[]> rows = new ArrayList<>();
        for (Menu menu : menuBar.getMenus()) {
            collectShortcuts(menu, rows);
        }
        return rows;
    }

    private static void collectShortcuts(Menu menu, List<String[]> rows) {
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu) {
                collectShortcuts((Menu) item, rows);
            } else if (item.getAccelerator() != null) {
                rows.add(new String[]{menu.getText() + " > " + item.getText(), item.getAccelerator()
                        .getDisplayText()});
            }
        }
    }

    private void showShortcuts() {
        TableView<String[]> table = new TableView<>();
        table.setId("shortcutsTable");
        TableColumn<String[], String> action = new TableColumn<>(Messages.get("shortcuts.action"));
        action.setCellValueFactory(features -> new javafx.beans.property.ReadOnlyStringWrapper(features.getValue()[0]));
        action.setPrefWidth(300);
        TableColumn<String[], String> keys = new TableColumn<>(Messages.get("shortcuts.keys"));
        keys.setCellValueFactory(features -> new javafx.beans.property.ReadOnlyStringWrapper(features.getValue()[1]));
        keys.setPrefWidth(160);
        table.getColumns().add(action);
        table.getColumns().add(keys);
        table.getItems().setAll(getShortcuts());
        table.setPrefSize(480, 420);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType
                .INFORMATION);
        Dialogs.initOwner(alert, getWindow());
        Branding.applyIcons(alert);
        alert.setTitle(Messages.get("help.shortcuts"));
        alert.setHeaderText(Messages.get("help.shortcuts"));
        alert.getDialogPane().setContent(table);
        alert.show();
    }

    /**
     * The connection of the selected node, e.g. of a table, null for groups and sources of extensions
     */
    private TreeItem<DbNode> selectedConnectionItem() {
        TreeItem<DbNode> item = connectionTree.getSelectionModel().getSelectedItem();
        while (item != null && item.getValue() != null && item.getValue().getKind() != DbNode.Kind.CONNECTION) {
            item = item.getParent();
        }
        return item != null && item.getValue() != null ? item : null;
    }

    private boolean isSourceSelected() {
        TreeItem<DbNode> item = connectionTree.getSelectionModel().getSelectedItem();
        return item != null && (item.getValue().getKind() == DbNode.Kind.CONNECTION || item.getValue().getKind()
                == DbNode.Kind.SOURCE);
    }

    private boolean isInfoAvailable() {
        TreeItem<DbNode> item = connectionTree.getSelectionModel().getSelectedItem();
        return item != null && (item.getValue().getProfile() != null || item.getValue().getKind()
                == DbNode.Kind.SOURCE);
    }

    private void editSelected() {
        DbNode node = connectionTree.getSelectedNode();
        if (node != null && node.getProfile() != null) {
            editConnection(node.getProfile());
        } else if (connectionTree.getSelectedSource() != null) {
            connectionTree.editSource(connectionTree.getSelectedSource());
        }
    }

    private void deleteSelected() {
        DbNode node = connectionTree.getSelectedNode();
        if (node != null && node.getProfile() != null) {
            deleteConnection(node.getProfile());
        } else if (connectionTree.getSelectedSource() != null) {
            connectionTree.deleteSource(connectionTree.getSelectedSource());
        }
    }

    private void infoSelected() {
        DbNode node = connectionTree.getSelectedNode();
        openInfo(connectionTree.getSelectedSource() != null ? connectionTree.getSelectedSource()
                : node != null && node.getProfile() != null ? DbNode.connection(node.getProfile()) : null);
    }

    /**
     * The info of the selected data source on its dashboard
     */
    private void dashboardSelected() {
        // A data source, or a source of an extension which tells its metrics, e.g. a vector database
        TreeItem<DbNode> item = selectedConnectionItem();
        DbNode source = connectionTree.getSelectedSource();
        if (item == null && (source == null || source.getSource().getDashboard() == null)) {
            return;
        }
        openInfo(item != null ? item.getValue() : source);
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        if (tab instanceof InfoTab) {
            ((InfoTab) tab).showDashboard();
        }
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
        Button editButton = toolButton(Icons.EDIT, "action.editConnection", this::editSelected);
        Button deleteButton = toolButton(Icons.DELETE, "action.deleteConnection", this::deleteSelected);
        Button infoButton = toolButton(Icons.INFO, "action.info", this::infoSelected);
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
        Button syncButton = toolButton(Icons.REFRESH, "sync.title", this::openSync);
        syncButton.setId("toolbarSyncButton");
        syncButton.setText(Messages.get("action.sync"));
        Button historyButton = toolButton(Icons.HISTORY, "history.title", this::openHistory);
        historyButton.setId("toolbarHistoryButton");
        historyButton.setText(Messages.get("action.history"));
        // The themes at hand, the same as View > Theme
        MenuButton themeButton = new MenuButton(null, Icons.of(Icons.THEME));
        themeButton.setId("toolbarThemeButton");
        themeButton.setTooltip(new Tooltip(Messages.get("menu.theme")));
        themeButton.getStyleClass().add("flat");
        themeButton.getItems().addAll(themeItems());
        return new ToolBar(markBox, new Separator(),
                toolButton(Icons.ADD, "action.newConnection", this::newConnection), editButton,
                deleteButton, infoButton, toolButton(Icons.REFRESH, "action.refresh",
                connectionTree::refreshSelected),
                new Separator(), sqlButton, exportButton, importButton, new Separator(), syncButton, historyButton,
                spacer, themeButton, exitButton);
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
     * Connects the data sources which were connected when SqlJam was closed
     */
    public void restoreConnected() {
        connectionTree.restoreConnected(context.getSettings().getConnectedSources());
    }

    /**
     * Selects and expands the connection, e.g. a restored example database
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
        closeTabs(tabPane.getTabs().stream().filter(tab -> tab.getUserData() instanceof String && ((String) tab
                .getUserData()).startsWith(profile.getId() + "|")).collect(Collectors.toList()));
    }

    /**
     * Removes the tabs and tells them they are closed, e.g. SQL editors close their connections and dashboards
     * their samples, like tabs closed by the user
     */
    void closeTabs(List<Tab> tabs) {
        tabPane.getTabs().removeAll(tabs);
        tabs.forEach(tab -> Event.fireEvent(tab, new Event(Tab.CLOSED_EVENT)));
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
     * Opens the report on its own page, an opened report is selected
     */
    public void openReport(TransferReport report) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof ReportTab && ((ReportTab) tab).getReport().getId().equals(report.getId())) {
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        ReportTab tab = new ReportTab(report);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        // A history which is open shows the new report
        tabPane.getTabs().stream().filter(HistoryTab.class::isInstance).forEach(history -> ((HistoryTab) history)
                .refresh());
    }

    /**
     * Opens the sync tasks, the open tab is selected
     */
    public void openSync() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof SyncTab) {
                ((SyncTab) tab).refresh();
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        SyncTab tab = new SyncTab(context, this::openReport);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    /**
     * Opens the history of the imports, it is read again when it is open already
     */
    public void openHistory() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof HistoryTab) {
                ((HistoryTab) tab).refresh();
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        HistoryTab tab = new HistoryTab(context, this::openReport);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
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
                    source::loadHealth, source.getDashboard());
        } else {
            ConnectionProfile profile = node.getProfile();
            DatabaseSession session = context.getSessionManager().getSession(profile);
            DatabaseInfo info = new DatabaseInfo(profile, session);
            tab = new InfoTab(profile.getName(), DbTypeCells.iconOf(profile), info::getOverview, info::getHealth,
                    () -> new JdbcMetrics(profile, session));
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

    ProgressDialog startTransfer(TransferRequest transferRequest, int listedTableCount) {
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
        TransferService.describeReport(transferRequest, progress.getReport());
        progress.setReportVerifier(report -> transferService.verifyReport(transferRequest, report));
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
        return progress;
    }

    private void showImportDialog(DbNode node) {
        // Without data sources, the target is created in the dialog
        Optional<ImportPackageDialog.ImportRequest> request = new ImportPackageDialog(getWindow(), context, node)
                .showAndWait();
        connectionTree.addNewConnections();
        request.ifPresent(this::startImport);
    }

    ProgressDialog startImport(ImportPackageDialog.ImportRequest importRequest) {
        int totalTables = importRequest.getManifest() != null ? importRequest.getManifest().getTables().size()
                : 0;
        ProgressDialog progress = new ProgressDialog(getWindow(), context, Messages.format(
                "progress.importTitle", importRequest.getTarget().getName()), totalTables);
        progress.setSkipErrors(!importRequest.isStopOnError());
        describeImport(progress.getReport(), TransferReport.Kind.PACKAGE_IMPORT, importRequest.getDirectory()
                .getAbsolutePath(), importRequest.getTarget(), importRequest.getCatalog(), importRequest.getSchema(),
                importRequest.isOneTransaction(), !importRequest.isStopOnError());
        if (importRequest.getManifest() != null) {
            // The tables of the package with their rows, which the target is checked against
            importRequest.getManifest().getTables().forEach(table -> progress.getReport().table(null, null, table
                    .getName()).setSourceRows(table.getRows()));
        }
        progress.getReport().getOptions().put("Rows per commit", String.valueOf(importRequest.getCommitRows()));
        // Tables which the package created hold its rows only, rows of tables which were there are not compared
        progress.setReportVerifier(report -> countRows(report, importRequest.getTarget(), importRequest.getCatalog(),
                importRequest.getSchema(), report.getObjects().containsKey("Tables")));
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
        return progress;
    }

    /**
     * Header of the report of an import of files into a database
     */
    private static void describeImport(TransferReport report, TransferReport.Kind kind, String source,
                                       ConnectionProfile target, String catalog, String schema, boolean oneTransaction,
                                       boolean skipErrors) {
        report.setKind(kind);
        report.setSource(source);
        StringBuilder text = new StringBuilder(target.getName());
        for (String part : new String[]{catalog, schema}) {
            if (StringUtils.isNotBlank(part)) {
                text.append(" / ").append(part);
            }
        }
        report.setTarget(text.toString());
        report.setTransaction(oneTransaction ? TransferReport.Transaction.ONE : TransferReport.Transaction.EVERY_ROWS);
        report.getOptions().put("Skip rows with errors", String.valueOf(skipErrors));
    }

    /**
     * Rows of the tables of the report in the target
     *
     * @param comparable whether the tables were created by the import, so that their rows are its rows
     */
    private static void countRows(TransferReport report, ConnectionProfile target, String catalog, String schema,
                                  boolean comparable) throws Exception {
        String url = target.getJdbcUrl(StringUtils.defaultIfBlank(catalog, null));
        try (Connection connection = StringUtils.isBlank(target.getUsername()) ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, target.getUsername(), target.getPassword())) {
            TransferService.countTargetRows(connection, catalog, schema, report, name -> name, comparable);
        }
    }

    private void showImportParquetDialog(DbNode node) {
        Optional<ImportParquetDialog.ParquetRequest> request = new ImportParquetDialog(getWindow(), context, node)
                .showAndWait();
        connectionTree.addNewConnections();
        request.ifPresent(this::startFileImport);
    }

    ProgressDialog startFileImport(ImportParquetDialog.ParquetRequest parquetRequest) {
        ProgressDialog progress = new ProgressDialog(getWindow(), context, Messages.format(
                "progress.importTitle", parquetRequest.getTarget().getName()), 1);
        progress.setSkipErrors(parquetRequest.isSkipErrors());
        describeImport(progress.getReport(), TransferReport.Kind.FILE_IMPORT, parquetRequest.getFiles().stream()
                .map(File::getName).collect(Collectors.joining(", ")), parquetRequest.getTarget(), parquetRequest
                .getCatalog(), parquetRequest.getSchema(), parquetRequest.isOneTransaction(), parquetRequest
                .isSkipErrors());
        progress.getReport().getOptions().put("Mode", String.valueOf(parquetRequest.getMode()));
        progress.getReport().getOptions().put("Rows per commit", String.valueOf(parquetRequest.getCommitRows()));
        progress.getReport().table(null, null, parquetRequest.getTableName());
        progress.setReportVerifier(report -> countRows(report, parquetRequest.getTarget(), parquetRequest
                .getCatalog(), parquetRequest.getSchema(), false));
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
        return progress;
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
