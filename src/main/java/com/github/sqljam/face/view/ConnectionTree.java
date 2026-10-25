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

import java.util.stream.Stream;
import java.util.Optional;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.javafx.FontIcon;
import lombok.Setter;
import com.github.sqljam.utils.SshSettings;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.DbCategory;
import com.github.sqljam.impexp.DbType;
import javafx.stage.Window;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;

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
    private final List<DataSourceExtension> extensions;
    /**
     * Items of all data sources and of the sources of the extensions, the tree shows those of the filter
     */
    private final List<TreeItem<DbNode>> connectionItems = new ArrayList<>();
    private final Map<DataSourceExtension, List<TreeItem<DbNode>>> sourceItems = new LinkedHashMap<>();
    private final Map<Object, TreeItem<DbNode>> groupItems = new HashMap<>();
    private final StringProperty filter = new SimpleStringProperty("");

    @Setter
    private Consumer<DbNode> onOpenTable;
    /**
     * Opens an item of a source of an extension, e.g. the tab of a collection
     */
    @Setter
    private Consumer<DbNode> onOpenSourceItem;
    /**
     * Opens the info of a data source or of a source of an extension
     */
    @Setter
    private Consumer<DbNode> onInfo;
    /**
     * Opens a SQL editor on the data source, catalog, schema or table of the node
     */
    @Setter
    private Consumer<DbNode> onSqlQuery;
    @Setter
    private Consumer<DbNode> onExport;
    @Setter
    private Consumer<DbNode> onImportScripts;
    @Setter
    private Consumer<DbNode> onImportParquet;
    @Setter
    private Consumer<DbNode> onEditConnection;
    @Setter
    private Consumer<DbNode> onDeleteConnection;
    @Setter
    private Consumer<String> onStatus;
    @Setter
    private Consumer<ConnectionProfile> onDisconnect;

    public ConnectionTree(AppContext context) {
        this(context, DataSourceExtension.load(context.getSettingsStore().getConfig()));
    }

    public ConnectionTree(AppContext context, List<DataSourceExtension> extensions) {
        this.context = context;
        this.extensions = extensions;
        setRoot(rootItem);
        setShowRoot(false);
        getStyleClass().add("connection-tree");
        setId("connectionTree");
        setCellFactory(view -> new DbNodeCell());
        filter.addListener((obs, oldText, text) -> applyFilter());
        reloadConnections();
    }

    /**
     * Reloads saved connections in the groups of their categories (relational, OLAP), then the groups of the
     * extensions (vector databases). Expanded connections are collapsed.
     */
    public void reloadConnections() {
        connectionItems.clear();
        for (ConnectionProfile profile : context.getProfileRegistry().getProfiles()) {
            connectionItems.add(new LazyItem(DbNode.connection(profile)));
        }
        reloadSources();
    }

    /**
     * Shows the data sources whose names or types contain the text, all for a blank text. The items are kept, a
     * connection which is opened stays opened. Groups without data sources are not shown.
     */
    public void setFilter(String text) {
        filter.set(text);
    }

    public String getFilter() {
        return StringUtils.defaultString(filter.get());
    }

    public StringProperty filterProperty() {
        return filter;
    }

    private boolean matches(DbNode node) {
        String text = StringUtils.trimToEmpty(filter.get()).toLowerCase(Locale.ENGLISH);
        if (text.isEmpty()) {
            return true;
        }
        String type = node.getProfile() != null ? (node.getProfile().getDbType() != null ? node.getProfile()
                .getDbType().getDisplayName() : "") : node.getSource().getTypeName();
        return node.getLabel().toLowerCase(Locale.ENGLISH).contains(text) || type.toLowerCase(Locale.ENGLISH)
                .contains(text);
    }

    /**
     * Groups of the matching data sources and sources, in the order of the categories and of the extensions
     */
    private void applyFilter() {
        TreeItem<DbNode> selected = getSelectionModel().getSelectedItem();
        List<TreeItem<DbNode>> groups = new ArrayList<>();
        for (DbCategory category : DbCategory.values()) {
            List<TreeItem<DbNode>> members = connectionItems.stream().filter(item -> DbTypeCells.categoryOf(item
                    .getValue().getProfile()) == category && matches(item.getValue())).collect(Collectors.toList());
            if (!members.isEmpty()) {
                TreeItem<DbNode> group = groupItems.computeIfAbsent(category, key -> {
                    TreeItem<DbNode> created = new TreeItem<>(DbNode.group(category));
                    created.setExpanded(true);
                    return created;
                });
                group.getChildren().setAll(members);
                groups.add(group);
            }
        }
        for (Map.Entry<DataSourceExtension, List<TreeItem<DbNode>>> entry : sourceItems.entrySet()) {
            List<TreeItem<DbNode>> members = entry.getValue().stream().filter(item -> matches(item.getValue()))
                    .collect(Collectors.toList());
            if (!members.isEmpty()) {
                TreeItem<DbNode> group = groupItems.computeIfAbsent(entry.getKey(), key -> {
                    TreeItem<DbNode> created = new TreeItem<>(DbNode.group(entry.getKey()));
                    created.setExpanded(true);
                    return created;
                });
                group.getChildren().setAll(members);
                groups.add(group);
            }
        }
        rootItem.getChildren().setAll(groups);
        if (selected != null && getRow(selected) >= 0) {
            getSelectionModel().select(selected);
        }
    }

    /**
     * Group of the data sources of a category, created in the order of the categories when it is missing: a
     * category without data sources has no group
     */
    TreeItem<DbNode> getGroup(DbCategory category) {
        return groupItems.get(category);
    }

    /**
     * Items of the connections of all categories
     */
    public List<TreeItem<DbNode>> getConnectionItems() {
        List<TreeItem<DbNode>> items = new ArrayList<>();
        for (TreeItem<DbNode> group : rootItem.getChildren()) {
            if (group.getValue().getCategory() != null) {
                items.addAll(group.getChildren());
            }
        }
        return items;
    }

    /**
     * Reloads the groups of the sources of extensions (vector databases) after the groups of the categories
     */
    public void reloadSources() {
        sourceItems.clear();
        for (DataSourceExtension extension : extensions) {
            List<TreeItem<DbNode>> items = new ArrayList<>();
            for (DataSourceExtension.Source source : extension.getSources()) {
                // Its items (collections) are read when it is expanded, like the tables of a connection
                items.add(new LazyItem(DbNode.source(extension, source)));
            }
            sourceItems.put(extension, items);
        }
        applyFilter();
    }

    /**
     * Selects the source of the id in its group
     */
    public void selectSource(String sourceId) {
        if (sourceItems.values().stream().flatMap(List::stream).noneMatch(item -> item.getValue().getSource()
                .getId().equals(sourceId) && matches(item.getValue()))) {
            // A source hidden by the filter is shown
            setFilter("");
        }
        for (TreeItem<DbNode> group : rootItem.getChildren()) {
            for (TreeItem<DbNode> item : group.getChildren()) {
                if (item.getValue().getKind() == DbNode.Kind.SOURCE && item.getValue().getSource().getId()
                        .equals(sourceId)) {
                    getSelectionModel().select(item);
                    scrollTo(getRow(item));
                    return;
                }
            }
        }
    }

    public List<DataSourceExtension> getExtensions() {
        return extensions;
    }

    /**
     * Adds connections of saved profiles which are not in the tree yet, e.g. created as the target of an import,
     * opened connections are kept
     */
    public void addNewConnections() {
        for (ConnectionProfile profile : context.getProfileRegistry().getProfiles()) {
            boolean exists = connectionItems.stream().anyMatch(item -> item.getValue().getProfile().getId().equals(
                    profile.getId()));
            if (!exists) {
                connectionItems.add(new LazyItem(DbNode.connection(profile)));
            }
        }
        applyFilter();
    }

    /**
     * Whether the connection of the item is opened: its children are loaded
     */
    public boolean isConnected(TreeItem<DbNode> item) {
        return item instanceof LazyItem && item.getValue().getKind() == DbNode.Kind.CONNECTION
                && ((LazyItem) item).loaded;
    }

    /**
     * Closes the session of the connection, the connection is opened again when it is expanded
     */
    public void disconnect(TreeItem<DbNode> item) {
        if (!isConnected(item)) {
            return;
        }
        ConnectionProfile profile = item.getValue().getProfile();
        ((LazyItem) item).unload();
        context.getSessionManager().closeSession(profile.getId());
        rememberConnected(item.getValue(), false);
        if (onDisconnect != null) {
            onDisconnect.accept(profile);
        }
        if (onStatus != null) {
            onStatus.accept(Messages.format("tree.disconnected", profile.getName()));
        }
    }

    /**
     * The selected node of the relational data sources, null for a group or a source of an extension
     */
    public DbNode getSelectedNode() {
        TreeItem<DbNode> item = getSelectionModel().getSelectedItem();
        return item != null && item.getValue().getKind() != DbNode.Kind.GROUP && item.getValue().getKind()
                != DbNode.Kind.SOURCE && item.getValue().getKind() != DbNode.Kind.SOURCE_ITEM ? item.getValue() : null;
    }

    /**
     * The selected source of an extension, e.g. a vector database
     */
    public DbNode getSelectedSource() {
        TreeItem<DbNode> item = getSelectionModel().getSelectedItem();
        return item != null && item.getValue().getKind() == DbNode.Kind.SOURCE ? item.getValue() : null;
    }

    /**
     * Selects and expands the connection of the profile
     */
    public void selectConnection(String profileId) {
        if (connectionItems.stream().noneMatch(item -> item.getValue().getProfile().getId().equals(profileId)
                && matches(item.getValue()))) {
            setFilter("");
        }
        for (TreeItem<DbNode> item : getConnectionItems()) {
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
        if (node.getKind() == DbNode.Kind.SOURCE || node.getKind() == DbNode.Kind.SOURCE_ITEM) {
            // Databases, collections and partitions as the source has them
            List<TreeItem<DbNode>> items = new ArrayList<>();
            for (SourceItem item : node.getSource().loadItems(node.getItem())) {
                DbNode child = DbNode.sourceItem(node.getExtension(), node.getSource(), item);
                items.add(item.isLeaf() ? new TreeItem<>(child) : new LazyItem(child));
            }
            if (items.isEmpty()) {
                items.add(new TreeItem<>(DbNode.message(Messages.get("tree.empty"))));
            }
            return items;
        }
        ConnectionProfile profile = node.getProfile();
        DatabaseSession session = context.getSessionManager().getSession(profile);
        DbType dbType = profile.getDbType();
        List<TreeItem<DbNode>> children = new ArrayList<>();
        switch (node.getKind()) {
            case CONNECTION:
                String product = session.getDatabaseProduct();
                if (onStatus != null) {
                    Platform.runLater(() -> onStatus.accept(profile.getName() + " - " + product));
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

    /**
     * Partitions (PostgreSQL) are children of their partitioned tables, like DBeaver and DataGrip show them
     */
    static void addTables(List<TreeItem<DbNode>> children, ConnectionProfile profile, List<TableInfo> tables) {
        Map<String, TreeItem<DbNode>> items = new LinkedHashMap<>();
        for (TableInfo table : tables) {
            items.put(table.getName(), new TreeItem<>(DbNode.table(profile, table)));
        }
        for (TableInfo table : tables) {
            TreeItem<DbNode> parent = table.isPartition() ? items.get(table.getParent()) : null;
            if (parent != null) {
                parent.getChildren().add(items.get(table.getName()));
            } else {
                children.add(items.get(table.getName()));
            }
        }
    }

    /**
     * Badge of a partitioned table showing its partitioning, e.g. RANGE, LIST, HASH, or the partition key of
     * ClickHouse. Null for other tables.
     */
    static Label partitionBadge(TableInfo table) {
        if (!table.isPartitioned()) {
            return null;
        }
        String text = StringUtils.isNotBlank(table.getPartitionType())
                ? table.getPartitionType().toUpperCase(Locale.ENGLISH)
                : StringUtils.defaultIfBlank(table.getPartitionKey(), Messages.get("tree.partition"));
        Label badge = new Label(StringUtils.abbreviate(text, 24));
        badge.getStyleClass().add("partition-badge");
        return badge;
    }

    /**
     * Icon of a table: partitioned tables (accent layers) and partitions (muted slices) differ from plain tables
     */
    static FontIcon tableIcon(TableInfo table) {
        if (table.isPartitioned()) {
            FontIcon icon = Icons.of(Icons.PARTITIONED);
            icon.getStyleClass().add("partitioned-icon");
            return icon;
        }
        if (table.isPartition()) {
            FontIcon icon = Icons.of(Icons.PARTITION);
            icon.getStyleClass().add("partition-icon");
            return icon;
        }
        return Icons.of(Icons.TABLE);
    }

    /**
     * Partitioning and comment of a table, null if it has none
     */
    static String tableTooltip(TableInfo table) {
        List<String> lines = new ArrayList<>();
        if (table.isPartitioned()) {
            // e.g. RANGE (sale_year), toYYYYMM(created) of ClickHouse
            String partitioning = StringUtils.defaultString(table.getPartitionType());
            if (StringUtils.isNotBlank(table.getPartitionKey())) {
                partitioning = StringUtils.isBlank(partitioning) ? table.getPartitionKey()
                        : partitioning + " (" + table.getPartitionKey() + ")";
            }
            lines.add(Messages.format("tree.partitioned", partitioning).trim());
        }
        if (table.isPartition() && table.getParent() != null) {
            lines.add(Messages.format("tree.partitionOf", table.getParent()));
        }
        if (table.getRemarks() != null) {
            lines.add(table.getRemarks());
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /**
     * Tree item loading its children when expanded for the first time
     */
    /**
     * Creates a data source of the category, which the form may change, and selects it: a DbCategory, an extension
     * (vector databases) or null for the relational databases
     */
    public void createConnection(Object category) {
        ConnectionDialog dialog = new ConnectionDialog(getScene().getWindow(), context, null, category, extensions);
        dialog.showAndWait().ifPresent(profile -> {
            addNewConnections();
            selectConnection(profile.getId());
        });
        if (dialog.getSavedSource() != null) {
            reloadSources();
            selectSource(dialog.getSavedSource().getId());
        }
    }

    public void editSource(DbNode node) {
        if (node.getSource().edit(getScene().getWindow(), context)) {
            reloadSources();
            selectSource(node.getSource().getId());
        }
    }

    public void deleteSource(DbNode node) {
        if (node.getSource().delete(getScene().getWindow(), context)) {
            reloadSources();
        }
    }

    private Window getWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    /**
     * The password of a data source which is not remembered is asked before it is connected, like DBeaver does.
     * Database files without users (SQLite, DuckDB, Access) need none.
     *
     * @return false when it is cancelled
     */
    boolean askMissingPassword(ConnectionProfile profile) {
        if (!needsPassword(profile)) {
            return true;
        }
        Optional<CredentialsDialog.Credentials> credentials = CredentialsDialog.ask(getWindow(), profile.getName(),
                Messages.get("connection.password"), StringUtils.defaultString(profile.getUsername()), null);
        credentials.ifPresent(entered -> {
            profile.setUsername(entered.getUsername());
            profile.setPassword(entered.getSecret());
            saveCredentials(profile, entered.isSaved());
        });
        return credentials.isPresent();
    }

    boolean needsPassword(ConnectionProfile profile) {
        DbType dbType = profile.getDbType();
        return profile.getPassword() == null && dbType != null && (!dbType.isFileBased() || dbType == DbType.H2)
                && !context.getProfileRegistry().isPasswordRemembered(profile.getId());
    }

    /**
     * Asks again for the secret which was refused: the password of the SSH tunnel or the key, the password of the
     * database, or the key of a source of an extension
     *
     * @return true when a new secret is entered and the node is connected again
     */
    boolean askAfterFailure(DbNode node, Throwable error) {
        if (node.getKind() == DbNode.Kind.SOURCE) {
            return node.getSource().authenticate(getWindow(), error);
        }
        if (node.getKind() != DbNode.Kind.CONNECTION) {
            return false;
        }
        ConnectionProfile profile = node.getProfile();
        String message = StringUtils.abbreviate(StringUtils.defaultString(error.getMessage()), 300);
        Optional<CredentialsDialog.Credentials> credentials;
        if (profile.isSshEnabled() && AuthFailures.isSshFailure(error)) {
            SshSettings ssh = profile.getSsh();
            boolean key = ssh.getAuthentication() == SshSettings.Authentication.KEY;
            credentials = CredentialsDialog.ask(getWindow(), profile.getName(), Messages.get(key
                    ? "credentials.ssh.passphrase" : "credentials.ssh.password"), null, message);
            credentials.ifPresent(entered -> ssh.setSecret(entered.getSecret()));
        } else if (AuthFailures.isDatabaseFailure(error)) {
            credentials = CredentialsDialog.ask(getWindow(), profile.getName(), Messages.get("connection.password"),
                    StringUtils.defaultString(profile.getUsername()), message);
            credentials.ifPresent(entered -> {
                profile.setUsername(entered.getUsername());
                profile.setPassword(entered.getSecret());
            });
        } else {
            return false;
        }
        credentials.ifPresent(entered -> {
            // The session of the refused secret is closed, the next one connects with the new secret
            context.getSessionManager().closeSessionNow(profile.getId());
            saveCredentials(profile, entered.isSaved());
        });
        return credentials.isPresent();
    }

    private void saveCredentials(ConnectionProfile profile, boolean saved) {
        try {
            context.getProfileRegistry().saveProfile(profile, saved);
        } catch (IOException e) {
            Dialogs.showError(getWindow(), Messages.get("connection.saveError"), e);
        }
    }

    /**
     * Data sources which are connected when the window is closed are connected again when it is opened
     */
    void rememberConnected(DbNode node, boolean connected) {
        String id = node.getProfile() != null ? node.getProfile().getId() : node.getSource().getId();
        List<String> ids = context.getSettings().getConnectedSources();
        boolean changed = connected ? !ids.contains(id) && ids.add(id) : ids.remove(id);
        if (changed) {
            context.saveSettings();
        }
    }

    /**
     * Expands the data sources of the ids, which connects them, e.g. those connected when SqlJam was closed
     */
    public void restoreConnected(List<String> ids) {
        for (String id : new ArrayList<>(ids)) {
            Stream.concat(connectionItems.stream(), sourceItems.values().stream().flatMap(List::stream))
                    .filter(item -> id.equals(item.getValue().getProfile() != null ? item.getValue().getProfile()
                            .getId() : item.getValue().getSource().getId())).findFirst()
                    .ifPresentOrElse(item -> item.setExpanded(true), () -> {
                        // A data source which was deleted meanwhile
                        context.getSettings().getConnectedSources().remove(id);
                    });
        }
    }

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

        /**
         * Collapsed with a placeholder child, the children are loaded again when it is expanded
         */
        void unload() {
            loaded = false;
            setExpanded(false);
            getChildren().setAll(Collections.singletonList(new TreeItem<>(DbNode.loading())));
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
            DbNode node = getValue();
            if (node.getKind() == DbNode.Kind.CONNECTION && needsPassword(node.getProfile())) {
                // Asked after the expansion is handled, a dialog may not wait during a layout
                loading = true;
                Platform.runLater(() -> {
                    loading = false;
                    if (askMissingPassword(node.getProfile())) {
                        connect(node);
                    } else {
                        // Cancelled, nothing is connected
                        setExpanded(false);
                    }
                });
                return;
            }
            connect(node);
        }

        private void connect(DbNode node) {
            loading = true;
            TaskRunner.run(() -> loadChildren(node), children -> {
                loading = false;
                loaded = true;
                getChildren().setAll(children);
                if (node.getKind() == DbNode.Kind.CONNECTION || node.getKind() == DbNode.Kind.SOURCE) {
                    rememberConnected(node, true);
                }
            }, e -> {
                loading = false;
                // A secret which the database or the SSH server refused is asked again, then connected again
                if (askAfterFailure(node, e)) {
                    connect(node);
                    return;
                }
                getChildren().setAll(Collections.singletonList(new TreeItem<>(DbNode.message(Messages.format(
                        "tree.error", e.getMessage())))));
                Dialogs.showError(getWindow(), Messages.format("tree.loadError", node.getLabel()), e);
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
                } else if (node != null && node.getKind() == DbNode.Kind.SOURCE_ITEM && node.getItem().getKind()
                        != SourceItem.Kind.DATABASE && event.getButton() == MouseButton.PRIMARY
                        && event.getClickCount() == 2 && onOpenSourceItem != null) {
                    onOpenSourceItem.accept(node);
                }
            });
        }

        @Override
        protected void updateItem(DbNode node, boolean empty) {
            super.updateItem(node, empty);
            getStyleClass().removeAll("tree-message", "tree-group");
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
                    setGraphic(DbTypeCells.iconOf(node.getProfile()));
                    break;
                case CATALOG:
                    setGraphic(Icons.of(Icons.CATALOG));
                    break;
                case SCHEMA:
                    setGraphic(Icons.of(Icons.SCHEMA));
                    break;
                case TABLE:
                    TableInfo table = node.getTable();
                    Label badge = partitionBadge(table);
                    if (badge != null) {
                        // Name with the badge of the partitioning after it
                        setText(null);
                        HBox graphic = new HBox(6, tableIcon(table), new Label(node.getLabel()), badge);
                        graphic.setAlignment(Pos.CENTER_LEFT);
                        setGraphic(graphic);
                    } else {
                        setGraphic(tableIcon(table));
                    }
                    break;
                case GROUP:
                    setGraphic(DbIcons.of(node.getLabel()));
                    getStyleClass().add("tree-group");
                    break;
                case SOURCE:
                    setGraphic(DbIcons.of(node.getSource().getTypeName()));
                    break;
                case SOURCE_ITEM:
                    SourceItem.Kind itemKind = node.getItem().getKind();
                    setGraphic(Icons.of(itemKind == SourceItem.Kind.DATABASE ? Icons.CATALOG
                            : itemKind == SourceItem.Kind.PARTITION ? Icons.PARTITION : Icons.TABLE));
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
            String tooltip = node.getKind() == DbNode.Kind.TABLE ? tableTooltip(node.getTable())
                    : node.getKind() == DbNode.Kind.SOURCE ? node.getSource().getTypeName()
                    : node.getKind() == DbNode.Kind.SOURCE_ITEM ? node.getItem().getKindName() : null;
            if (tooltip != null) {
                setTooltip(new Tooltip(tooltip));
            } else {
                setTooltip(null);
            }
            setContextMenu(createContextMenu(node));
        }

        private ContextMenu createContextMenu(DbNode node) {
            ContextMenu menu = new ContextMenu();
            switch (node.getKind()) {
                case CONNECTION:
                    // Connect before the connection is opened, disconnect after it is opened
                    MenuItem connect = menuItem("tree.connect", Icons.CONNECT, () -> getTreeItem().setExpanded(true));
                    connect.setId("connectMenuItem");
                    MenuItem disconnect = menuItem("tree.disconnect", Icons.DISCONNECT, () -> disconnect(
                            getTreeItem()));
                    disconnect.setId("disconnectMenuItem");
                    menu.setOnShowing(event -> {
                        boolean connected = isConnected(getTreeItem());
                        connect.setVisible(!connected);
                        disconnect.setVisible(connected);
                    });
                    menu.getItems().addAll(connect, disconnect,
                            menuItem("action.refresh", Icons.REFRESH, ConnectionTree.this::refreshSelected),
                            menuItem("action.info", Icons.INFO, () -> fire(onInfo, node)),
                            new SeparatorMenuItem(),
                            menuItem("action.sqlQuery", Icons.SQL, () -> fire(onSqlQuery, node)),
                            menuItem("action.export", Icons.EXPORT, () -> fire(onExport, node)),
                            menuItem("action.importScripts", Icons.IMPORT, () -> fire(onImportScripts, node)),
                            menuItem("action.importParquet", Icons.IMPORT, () -> fire(onImportParquet, node)),
                            new SeparatorMenuItem(),
                            menuItem("action.editConnection", Icons.EDIT, () -> fire(onEditConnection, node)),
                            menuItem("action.deleteConnection", Icons.DELETE, () -> fire(onDeleteConnection, node)));
                    break;
                case CATALOG:
                case SCHEMA:
                    menu.getItems().addAll(
                            menuItem("action.refresh", Icons.REFRESH, ConnectionTree.this::refreshSelected),
                            new SeparatorMenuItem(),
                            menuItem("action.sqlQuery", Icons.SQL, () -> fire(onSqlQuery, node)),
                            menuItem("action.export", Icons.EXPORT, () -> fire(onExport, node)),
                            menuItem("action.importScripts", Icons.IMPORT, () -> fire(onImportScripts, node)),
                            menuItem("action.importParquet", Icons.IMPORT, () -> fire(onImportParquet, node)));
                    break;
                case TABLE:
                    menu.getItems().addAll(
                            menuItem("tree.openTable", Icons.TABLE, () -> fire(onOpenTable, node)),
                            menuItem("action.sqlQuery", Icons.SQL, () -> fire(onSqlQuery, node)),
                            menuItem("action.export", Icons.EXPORT, () -> fire(onExport, node)));
                    break;
                case GROUP:
                    if (node.getExtension() != null) {
                        menu.getItems().addAll(
                                menuItem("connection.kind.new", Icons.ADD, () -> createConnection(node.getExtension())),
                                menuItem("action.refresh", Icons.REFRESH, ConnectionTree.this::reloadSources));
                    } else {
                        menu.getItems().addAll(
                                menuItem("connection.kind.new", Icons.ADD, () -> createConnection(node.getCategory())),
                                menuItem("action.refresh", Icons.REFRESH, ConnectionTree.this::reloadConnections));
                    }
                    break;
                case SOURCE:
                    MenuItem open = new MenuItem(node.getExtension().getOpenText(), Icons.of(Icons.IMPORT));
                    open.setId("openSourceMenuItem");
                    open.setOnAction(event -> node.getSource().open(getScene().getWindow(), context));
                    menu.getItems().addAll(open, menuItem("action.refresh", Icons.REFRESH,
                            ConnectionTree.this::refreshSelected), menuItem("action.info", Icons.INFO,
                            () -> fire(onInfo, node)), new SeparatorMenuItem(),
                            menuItem("action.editConnection", Icons.EDIT, () -> editSource(node)),
                            menuItem("action.deleteConnection", Icons.DELETE, () -> deleteSource(node)));
                    break;
                case SOURCE_ITEM:
                    MenuItem importItem = new MenuItem(node.getExtension().getOpenText(), Icons.of(Icons.IMPORT));
                    importItem.setId("importSourceItemMenuItem");
                    importItem.setOnAction(event -> node.getSource().open(List.of(node.getItem()),
                            getScene().getWindow(), context));
                    if (node.getItem().getKind() == SourceItem.Kind.DATABASE) {
                        menu.getItems().addAll(importItem, menuItem("action.refresh", Icons.REFRESH,
                                ConnectionTree.this::refreshSelected));
                    } else {
                        MenuItem openItem = new MenuItem(node.getExtension().getOpenItemText(),
                                Icons.of(Icons.TABLE));
                        openItem.setId("openSourceItemMenuItem");
                        openItem.setOnAction(event -> fire(onOpenSourceItem, node));
                        menu.getItems().addAll(openItem, importItem);
                    }
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
