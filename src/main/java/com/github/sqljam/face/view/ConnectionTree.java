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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.DbType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import lombok.Setter;

/**
 * @Description: ConnectionTree shows connections, catalogs, schemas and tables, children are loaded lazily in
 *               background when a node is expanded
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class ConnectionTree extends TreeView<DbNode> {

    private final AppContext context;
    private final TreeItem<DbNode> rootItem = new TreeItem<>(DbNode.root());

    @Setter
    private Consumer<DbNode> onOpenTable;
    @Setter
    private Consumer<DbNode> onExport;
    @Setter
    private Consumer<DbNode> onImportScripts;
    @Setter
    private Consumer<DbNode> onEditConnection;
    @Setter
    private Consumer<DbNode> onDeleteConnection;
    @Setter
    private Consumer<String> onStatus;

    public ConnectionTree(AppContext context) {
        this.context = context;
        setRoot(rootItem);
        setShowRoot(false);
        getStyleClass().add("connection-tree");
        setId("connectionTree");
        setCellFactory(view -> new DbNodeCell());
        reloadConnections();
    }

    /**
     * Reloads saved connections, expanded connections are collapsed
     */
    public void reloadConnections() {
        rootItem.getChildren().clear();
        for (ConnectionProfile profile : context.getProfileRegistry().getProfiles()) {
            rootItem.getChildren().add(new LazyItem(DbNode.connection(profile)));
        }
    }

    public DbNode getSelectedNode() {
        TreeItem<DbNode> item = getSelectionModel().getSelectedItem();
        return item != null ? item.getValue() : null;
    }

    /**
     * Selects and expands the connection of the profile
     */
    public void selectConnection(String profileId) {
        for (TreeItem<DbNode> item : rootItem.getChildren()) {
            if (item.getValue().getProfile().getId().equals(profileId)) {
                getSelectionModel().select(item);
                item.setExpanded(true);
                scrollTo(getRow(item));
                return;
            }
        }
    }

    /**
     * Reloads children of the selected node
     */
    public void refreshSelected() {
        TreeItem<DbNode> item = getSelectionModel().getSelectedItem();
        if (item instanceof LazyItem) {
            ((LazyItem) item).reload();
        } else if (item != null && item.getParent() instanceof LazyItem) {
            ((LazyItem) item.getParent()).reload();
        } else {
            reloadConnections();
        }
    }

    private List<TreeItem<DbNode>> loadChildren(DbNode node) throws Exception {
        ConnectionProfile profile = node.getProfile();
        DatabaseSession session = context.getSessionManager().getSession(profile);
        DbType dbType = profile.getDbType();
        List<TreeItem<DbNode>> children = new ArrayList<>();
        switch (node.getKind()) {
            case CONNECTION:
                String product = session.getDatabaseProduct();
                if (onStatus != null) {
                    javafx.application.Platform.runLater(() -> onStatus.accept(profile.getName() + " - " + product));
                }
                // Databases of the server, Oracle and SQLite have no databases
                List<String> catalogs = session.getCatalogs();
                if (!catalogs.isEmpty()) {
                    catalogs.forEach(catalog -> children.add(new LazyItem(DbNode.catalog(profile, catalog))));
                } else if (dbType.isSchemaSupported()) {
                    session.getSchemas(null).forEach(schema -> children.add(new LazyItem(
                            DbNode.schema(profile, null, schema))));
                } else {
                    addTables(children, profile, session.getTables(null, null));
                }
                break;
            case CATALOG:
                if (dbType.isSchemaSupported()) {
                    session.getSchemas(node.getCatalog()).forEach(schema -> children.add(new LazyItem(
                            DbNode.schema(profile, node.getCatalog(), schema))));
                } else {
                    addTables(children, profile, session.getTables(node.getCatalog(), null));
                }
                break;
            case SCHEMA:
                addTables(children, profile, session.getTables(node.getCatalog(), node.getSchema()));
                break;
            default:
                break;
        }
        if (children.isEmpty()) {
            children.add(new TreeItem<>(DbNode.message(Messages.get("tree.empty"))));
        }
        return children;
    }

    private static void addTables(List<TreeItem<DbNode>> children, ConnectionProfile profile, List<TableInfo> tables) {
        for (TableInfo table : tables) {
            children.add(new TreeItem<>(DbNode.table(profile, table)));
        }
    }

    /**
     * Tree item loading its children when expanded for the first time
     */
    private class LazyItem extends TreeItem<DbNode> {

        private boolean loaded;
        private boolean loading;

        LazyItem(DbNode node) {
            super(node);
            getChildren().add(new TreeItem<>(DbNode.loading()));
            expandedProperty().addListener((obs, wasExpanded, expanded) -> {
                if (expanded && !loaded && !loading) {
                    load();
                }
            });
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        void reload() {
            loaded = false;
            getChildren().setAll(Collections.singletonList(new TreeItem<>(DbNode.loading())));
            if (isExpanded()) {
                load();
            } else {
                setExpanded(true);
            }
        }

        private void load() {
            loading = true;
            TaskRunner.run(() -> loadChildren(getValue()), children -> {
                loading = false;
                loaded = true;
                getChildren().setAll(children);
            }, e -> {
                loading = false;
                getChildren().setAll(Collections.singletonList(new TreeItem<>(DbNode.message(Messages.format(
                        "tree.error", e.getMessage())))));
                Dialogs.showError(getScene() != null ? getScene().getWindow() : null,
                        Messages.format("tree.loadError", getValue().getLabel()), e);
            });
        }
    }

    private class DbNodeCell extends TreeCell<DbNode> {

        DbNodeCell() {
            setOnMouseClicked(event -> {
                DbNode node = getItem();
                if (node != null && node.getKind() == DbNode.Kind.TABLE && event.getButton() == MouseButton.PRIMARY
                        && event.getClickCount() == 2 && onOpenTable != null) {
                    onOpenTable.accept(node);
                }
            });
        }

        @Override
        protected void updateItem(DbNode node, boolean empty) {
            super.updateItem(node, empty);
            getStyleClass().removeAll("tree-message");
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setTooltip(null);
                return;
            }
            setText(node.getLabel());
            switch (node.getKind()) {
                case CONNECTION:
                    setGraphic(Icons.of(Icons.CONNECTION));
                    break;
                case CATALOG:
                    setGraphic(Icons.of(Icons.CATALOG));
                    break;
                case SCHEMA:
                    setGraphic(Icons.of(Icons.SCHEMA));
                    break;
                case TABLE:
                    setGraphic(Icons.of(node.getTable().isPartition() ? Icons.PARTITION : Icons.TABLE));
                    break;
                case LOADING:
                    setGraphic(Icons.of(Icons.LOADING));
                    getStyleClass().add("tree-message");
                    break;
                default:
                    setGraphic(null);
                    getStyleClass().add("tree-message");
                    break;
            }
            if (node.getKind() == DbNode.Kind.TABLE && node.getTable().getRemarks() != null) {
                setTooltip(new javafx.scene.control.Tooltip(node.getTable().getRemarks()));
            } else {
                setTooltip(null);
            }
            setContextMenu(createContextMenu(node));
        }

        private ContextMenu createContextMenu(DbNode node) {
            ContextMenu menu = new ContextMenu();
            switch (node.getKind()) {
                case CONNECTION:
                    menu.getItems().addAll(
                            menuItem("tree.connect", Icons.CONNECT, () -> getTreeItem().setExpanded(true)),
                            menuItem("action.refresh", Icons.REFRESH, ConnectionTree.this::refreshSelected),
                            new SeparatorMenuItem(),
                            menuItem("action.export", Icons.EXPORT, () -> fire(onExport, node)),
                            menuItem("action.importScripts", Icons.IMPORT, () -> fire(onImportScripts, node)),
                            new SeparatorMenuItem(),
                            menuItem("action.editConnection", Icons.EDIT, () -> fire(onEditConnection, node)),
                            menuItem("action.deleteConnection", Icons.DELETE, () -> fire(onDeleteConnection, node)));
                    break;
                case CATALOG:
                case SCHEMA:
                    menu.getItems().addAll(
                            menuItem("action.refresh", Icons.REFRESH, ConnectionTree.this::refreshSelected),
                            new SeparatorMenuItem(),
                            menuItem("action.export", Icons.EXPORT, () -> fire(onExport, node)),
                            menuItem("action.importScripts", Icons.IMPORT, () -> fire(onImportScripts, node)));
                    break;
                case TABLE:
                    menu.getItems().addAll(
                            menuItem("tree.openTable", Icons.TABLE, () -> fire(onOpenTable, node)),
                            menuItem("action.export", Icons.EXPORT, () -> fire(onExport, node)));
                    break;
                default:
                    return null;
            }
            return menu;
        }

        private void fire(Consumer<DbNode> consumer, DbNode node) {
            if (consumer != null) {
                consumer.accept(node);
            }
        }

        private MenuItem menuItem(String key, String icon, Runnable action) {
            MenuItem item = new MenuItem(Messages.get(key), Icons.of(icon));
            item.setOnAction(event -> {
                getTreeView().getSelectionModel().select(getTreeItem());
                action.run();
            });
            return item;
        }
    }
}
