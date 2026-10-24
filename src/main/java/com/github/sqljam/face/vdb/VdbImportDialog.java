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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.Branding;
import com.github.sqljam.face.view.ConnectionDialog;
import com.github.sqljam.face.view.DbIcons;
import com.github.sqljam.face.view.DbTypeCells;
import com.github.sqljam.face.view.Dialogs;
import com.github.sqljam.face.view.Icons;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.face.view.ProgressDialog;
import com.github.sqljam.face.view.TaskRunner;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollectionMetaData;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbExportListener;
import com.github.sqljam.vdb.VdbExportResult;
import com.github.sqljam.vdb.VdbExporter;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.dialect.VdbDialect;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * @Description: VdbImportDialog imports collections of a vector database into a data source with vectors, a wizard
 *               like the export of tables: the source (the vector database, its database when it has some, the
 *               collections and their tables, the records of a query, partitions, tenants or namespaces), the options
 *               and the target. Only data sources with vectors are targets. The import runs in the progress dialog
 *               of the imports of the data sources.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbImportDialog extends Dialog<Boolean> {

    /**
     * Types of data sources which may have vectors
     */
    static final Set<DbType> TARGET_TYPES = EnumSet.of(DbType.POSTGRESQL, DbType.MYSQL, DbType.MARIADB,
            DbType.ORACLE, DbType.SQLSERVER, DbType.DB2, DbType.DUCKDB, DbType.CLICKHOUSE);
    /**
     * Whether data sources have vectors, by their probe keys, for the session of the application
     */
    static final Map<String, Boolean> VECTOR_TARGETS = new ConcurrentHashMap<>();

    /**
     * A collection of the list: whether it is imported and the name of its table
     */
    static final class CollectionItem {
        final String name;
        final SimpleBooleanProperty selected = new SimpleBooleanProperty();
        final SimpleStringProperty tableName;

        CollectionItem(String name) {
            this.name = name;
            this.tableName = new SimpleStringProperty(VdbCollectionMetaData.getDefaultTableName(name));
        }
    }

    private final AppContext context;
    private final VdbProfileStore profileStore;
    private final VdbQuery query;
    private final List<String> preselected;
    private final ComboBox<VdbProfile> sourceCombo = new ComboBox<>();
    private final ComboBox<String> databaseCombo = new ComboBox<>();
    private final Label databaseLabel = new Label(Messages.get("connection.database"));
    private final TableView<CollectionItem> collectionTable = new TableView<>();
    private final CheckBox allCheck = new CheckBox(Messages.get("export.tables.all"));
    private final Label queryLabel = new Label();
    private final ComboBox<ConnectionProfile> targetCombo = new ComboBox<>();
    private final ComboBox<String> schemaCombo = new ComboBox<>();
    private final RadioButton recreateRadio = new RadioButton(Messages.get("vdb.mode.recreate"));
    private final RadioButton appendRadio = new RadioButton(Messages.get("vdb.mode.append"));
    private final RadioButton upsertRadio = new RadioButton(Messages.get("vdb.mode.upsert"));
    private final CheckBox indexCheck = new CheckBox(Messages.get("vdb.option.index"));
    private final CheckBox skipErrorsCheck = new CheckBox(Messages.get("vdb.option.skipErrors"));
    private final Label statusLabel = new Label();
    private final Button startButton;
    private VdbDialect targetDialect;
    private int targetsVersion;
    private String preselectedDatabase;

    public VdbImportDialog(Window owner, AppContext context, VdbProfileStore profileStore) {
        this(owner, context, profileStore, null, null, List.of(), null);
    }

    public VdbImportDialog(Window owner, AppContext context, VdbProfileStore profileStore, VdbProfile selected) {
        this(owner, context, profileStore, selected, null, List.of(), null);
    }

    /**
     * @param selected    vector database which is chosen first, null for the first one
     * @param database    database of the vector database (Milvus, Chroma), null for its default one
     * @param collections collections which are checked
     * @param query       records of the collections which are imported, all records if it is null
     */
    public VdbImportDialog(Window owner, AppContext context, VdbProfileStore profileStore, VdbProfile selected,
                           String database, List<String> collections, VdbQuery query) {
        this.context = context;
        this.profileStore = profileStore;
        this.query = query;
        this.preselected = collections;
        this.preselectedDatabase = database;
        Dialogs.initOwner(this, owner);
        Branding.applyIcons(this);
        setTitle(Messages.get("vdb.title"));
        setHeaderText(Messages.get("vdb.header"));
        setResizable(true);
        sourceCombo.setId("vdbSourceCombo");
        databaseCombo.setId("vdbDatabaseCombo");
        collectionTable.setId("vdbCollections");
        allCheck.setId("vdbAllCheck");
        queryLabel.setId("vdbQueryLabel");
        targetCombo.setId("vdbTargetCombo");
        schemaCombo.setId("vdbSchemaCombo");
        recreateRadio.setId("vdbRecreateRadio");
        appendRadio.setId("vdbAppendRadio");
        upsertRadio.setId("vdbUpsertRadio");
        indexCheck.setId("vdbIndexCheck");
        skipErrorsCheck.setId("vdbSkipErrorsCheck");
        statusLabel.setId("vdbStatus");

        VBox content = new VBox(16, section("export.section.source", createSourcePane()),
                section("export.section.options", createOptionsPane()),
                section("export.section.target", createTargetPane()));
        content.setPadding(new Insets(4, 8, 4, 4));
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefSize(720, 600);
        statusLabel.setWrapText(true);
        VBox root = new VBox(8, scrollPane, statusLabel);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        getDialogPane().setContent(root);
        ButtonType showSql = new ButtonType(Messages.get("vdb.showSql"), ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(showSql, ButtonType.OK, ButtonType.CANCEL);
        Button sqlButton = (Button) getDialogPane().lookupButton(showSql);
        sqlButton.setId("vdbShowSqlButton");
        sqlButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            showScripts();
        });
        startButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        startButton.setId("vdbStartButton");
        startButton.setText(Messages.get("import.start"));
        startButton.setGraphic(Icons.of(Icons.START));
        startButton.addEventFilter(ActionEvent.ACTION, event -> {
            String error = validate();
            if (error != null) {
                showStatus("status-error", error);
                event.consume();
            } else if (recreateRadio.isSelected() && !Dialogs.confirm(getOwnerWindow(), Messages.get("vdb.title"),
                    Messages.format("vdb.recreate.confirm", getSelectedCollections().size()))) {
                event.consume();
            }
        });
        setResultConverter(button -> button == ButtonType.OK);

        reloadSources(selected);
        reloadTargets(null);
        updateStart();
    }

    private static Node section(String key, Node body) {
        Label title = new Label(Messages.get(key));
        title.getStyleClass().add("section-title");
        VBox box = new VBox(8, title, body);
        box.getStyleClass().add("form-section");
        return box;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(150);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        return grid;
    }

    private Window getOwnerWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    // ------------------------------------------------------------------
    // Source
    // ------------------------------------------------------------------

    private Node createSourcePane() {
        sourceCombo.setMaxWidth(Double.MAX_VALUE);
        sourceCombo.setCellFactory(view -> profileCell());
        sourceCombo.setButtonCell(profileCell());
        sourceCombo.valueProperty().addListener((obs, oldProfile, profile) -> loadDatabases(profile));
        databaseCombo.setMaxWidth(Double.MAX_VALUE);
        databaseCombo.valueProperty().addListener((obs, oldDatabase, database) -> loadCollections());

        TableColumn<CollectionItem, Boolean> selectColumn = new TableColumn<>();
        selectColumn.setCellValueFactory(data -> data.getValue().selected);
        selectColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectColumn));
        selectColumn.setEditable(true);
        selectColumn.setPrefWidth(40);
        TableColumn<CollectionItem, String> nameColumn = new TableColumn<>(Messages.get("vdb.collection"));
        nameColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().name));
        nameColumn.setPrefWidth(280);
        TableColumn<CollectionItem, String> tableColumn = new TableColumn<>(Messages.get("vdb.table"));
        tableColumn.setCellValueFactory(data -> data.getValue().tableName);
        tableColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        tableColumn.setEditable(true);
        tableColumn.setPrefWidth(280);
        collectionTable.getColumns().setAll(List.of(selectColumn, nameColumn, tableColumn));
        collectionTable.setEditable(true);
        collectionTable.setPrefHeight(220);
        collectionTable.setPlaceholder(new Label(Messages.get("vdb.collections.empty")));
        allCheck.setOnAction(event -> collectionTable.getItems().forEach(item -> item.selected.set(allCheck
                .isSelected())));

        queryLabel.getStyleClass().add("muted");
        queryLabel.setWrapText(true);
        queryLabel.setText(describeQuery());
        GridPane grid = grid();
        grid.addRow(0, new Label(Messages.get("vdb.connection")), sourceCombo);
        grid.addRow(1, databaseLabel, databaseCombo);
        grid.addRow(2, new Label(Messages.get("vdb.rows")), queryLabel);
        return new VBox(8, grid, allCheck, collectionTable);
    }

    /**
     * Records of the query: all, or its conditions, filter, search and partitions
     */
    String describeQuery() {
        if (query == null || (!query.isFiltered() && !query.isNearest() && query.getPartitions().isEmpty()
                && query.getLimit() <= 0)) {
            return Messages.get("vdb.rows.all");
        }
        List<String> parts = new ArrayList<>();
        if (query.isNative()) {
            parts.add(query.getNativeFilter());
        }
        query.getConditions().forEach(condition -> parts.add(condition.getField() + " " + condition.getOperator()
                + " " + condition.getValue()));
        if (query.isNearest()) {
            parts.add(Messages.format("vdb.rows.nearest", query.getLimit(), query.getNearestField()));
        }
        if (!query.getPartitions().isEmpty()) {
            parts.add(String.join(", ", query.getPartitions()));
        }
        return String.join(", ", parts);
    }

    private static ListCell<VdbProfile> profileCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(VdbProfile profile, boolean empty) {
                super.updateItem(profile, empty);
                setText(empty || profile == null ? null : profile.getName());
                setGraphic(empty || profile == null ? null : DbIcons.of(profile.getType().getDisplayName()));
            }
        };
    }

    private void reloadSources(VdbProfile selected) {
        sourceCombo.getItems().setAll(profileStore.getProfiles());
        if (selected != null) {
            sourceCombo.getItems().stream().filter(selected::equals).findFirst().ifPresent(sourceCombo::setValue);
        } else if (!sourceCombo.getItems().isEmpty()) {
            sourceCombo.setValue(sourceCombo.getItems().get(0));
        }
    }

    /**
     * Databases of the vector database (Milvus, Chroma), the collections of the others
     */
    private void loadDatabases(VdbProfile profile) {
        databaseCombo.getItems().clear();
        collectionTable.getItems().clear();
        boolean databases = profile != null && profile.getType().isDatabaseSupported();
        databaseLabel.setVisible(databases);
        databaseLabel.setManaged(databases);
        databaseCombo.setVisible(databases);
        databaseCombo.setManaged(databases);
        if (profile == null) {
            return;
        }
        if (!databases) {
            loadCollections();
            return;
        }
        TaskRunner.run(() -> {
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                return client.getDatabases();
            }
        }, names -> {
            if (profile.equals(sourceCombo.getValue())) {
                databaseCombo.getItems().setAll(names);
                String database = preselectedDatabase != null ? preselectedDatabase : profile.getDatabase();
                preselectedDatabase = null;
                databaseCombo.setValue(names.contains(database) ? database : names.isEmpty() ? null : names.get(0));
            }
        }, e -> showStatus("status-error", Messages.format("connection.failure", e.getMessage())));
    }

    /**
     * The vector database of the chosen database
     */
    VdbProfile getSourceProfile() {
        VdbProfile profile = sourceCombo.getValue();
        if (profile == null || !profile.getType().isDatabaseSupported() || databaseCombo.getValue() == null) {
            return profile;
        }
        VdbProfile copy = profile.copy();
        copy.setDatabase(databaseCombo.getValue());
        return copy;
    }

    private void loadCollections() {
        collectionTable.getItems().clear();
        VdbProfile profile = getSourceProfile();
        if (profile == null) {
            return;
        }
        showStatus("status-pending", Messages.get("connection.connecting"));
        TaskRunner.run(() -> {
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                return client.getCollections();
            }
        }, names -> {
            for (String name : names) {
                CollectionItem item = new CollectionItem(name);
                item.selected.set(preselected.contains(name));
                item.selected.addListener((obs, oldValue, value) -> updateStart());
                collectionTable.getItems().add(item);
            }
            showStatus("status-success", Messages.format("vdb.collections.loaded", names.size()));
            updateStart();
        }, e -> showStatus("status-error", Messages.format("connection.failure", e.getMessage())));
    }

    List<CollectionItem> getSelectedCollections() {
        List<CollectionItem> items = new ArrayList<>();
        collectionTable.getItems().stream().filter(item -> item.selected.get()).forEach(items::add);
        return items;
    }

    // ------------------------------------------------------------------
    // Options and target
    // ------------------------------------------------------------------

    private Node createOptionsPane() {
        ToggleGroup modeGroup = new ToggleGroup();
        recreateRadio.setToggleGroup(modeGroup);
        appendRadio.setToggleGroup(modeGroup);
        upsertRadio.setToggleGroup(modeGroup);
        recreateRadio.setSelected(true);
        indexCheck.setSelected(true);
        skipErrorsCheck.setSelected(true);
        GridPane grid = grid();
        grid.addRow(0, new Label(Messages.get("parquet.mode")), new VBox(6, recreateRadio, appendRadio,
                upsertRadio));
        grid.addRow(1, new Label(Messages.get("vdb.options")), new VBox(6, indexCheck, skipErrorsCheck));
        return grid;
    }

    private Node createTargetPane() {
        DbTypeCells.setupProfileCombo(targetCombo);
        targetCombo.setMaxWidth(Double.MAX_VALUE);
        targetCombo.valueProperty().addListener((obs, oldProfile, profile) -> checkTarget(profile));
        schemaCombo.setEditable(true);
        schemaCombo.setMaxWidth(Double.MAX_VALUE);
        schemaCombo.setPromptText(Messages.get("import.schema.prompt"));
        GridPane grid = grid();
        grid.addRow(0, new Label(Messages.get("export.database.connection")), ConnectionDialog.targetField(
                targetCombo, context, this::reloadTargets));
        grid.addRow(1, new Label(Messages.get("export.database.schema")), schemaCombo);
        return grid;
    }

    /**
     * Lists the data sources which have vectors: data sources of the types which may have them are probed in the
     * background, those with vectors are added in their order, the others are never listed. Probes are kept for the
     * session of the application.
     *
     * @param selected data source which is selected when it is listed
     */
    private void reloadTargets(ConnectionProfile selected) {
        List<ConnectionProfile> candidates = new ArrayList<>();
        for (ConnectionProfile profile : context.getProfileRegistry().getProfiles()) {
            if (profile.getDbType() != null && TARGET_TYPES.contains(profile.getDbType())) {
                candidates.add(profile);
            }
        }
        int version = ++targetsVersion;
        targetCombo.getItems().clear();
        int[] pending = {0};
        for (ConnectionProfile profile : candidates) {
            Boolean vectors = VECTOR_TARGETS.get(probeKey(profile));
            if (Boolean.TRUE.equals(vectors)) {
                addTarget(candidates, profile, selected);
            } else if (vectors == null) {
                pending[0]++;
                TaskRunner.run(() -> hasVectors(profile), result -> {
                    VECTOR_TARGETS.put(probeKey(profile), result);
                    if (version != targetsVersion) {
                        return;
                    }
                    if (result) {
                        addTarget(candidates, profile, selected);
                    }
                    showTargetsPrompt(--pending[0]);
                }, e -> {
                    // A data source which is not reachable now is probed again next time
                    if (version == targetsVersion) {
                        showTargetsPrompt(--pending[0]);
                    }
                });
            }
        }
        showTargetsPrompt(pending[0]);
    }

    private void addTarget(List<ConnectionProfile> candidates, ConnectionProfile profile, ConnectionProfile selected) {
        int index = 0;
        for (ConnectionProfile listed : targetCombo.getItems()) {
            if (candidates.indexOf(listed) > candidates.indexOf(profile)) {
                break;
            }
            index++;
        }
        targetCombo.getItems().add(index, profile);
        if (selected != null && selected.getId().equals(profile.getId())) {
            targetCombo.setValue(profile);
        }
    }

    private void showTargetsPrompt(int pending) {
        targetCombo.setPromptText(Messages.get(pending > 0 ? "vdb.targets.checking" : targetCombo.getItems()
                .isEmpty() ? "vdb.targets.none" : "vdb.targets.choose"));
    }

    /**
     * Key of a probe: the data source as it connects, a probe is repeated after it is edited
     */
    static String probeKey(ConnectionProfile profile) {
        return profile.getId() + "|" + profile.getJdbcUrl() + "|" + profile.getUsername();
    }

    static boolean hasVectors(ConnectionProfile profile) throws SQLException {
        try (Connection connection = new ProfileDataSource(profile).getConnection()) {
            VdbDialect dialect = VdbDialect.of(connection);
            return dialect != null && dialect.isVectorSupported();
        }
    }

    private void checkTarget(ConnectionProfile profile) {
        targetDialect = null;
        schemaCombo.getItems().clear();
        updateStart();
        if (profile == null) {
            return;
        }
        showStatus("status-pending", Messages.get("vdb.target.checking"));
        TaskRunner.run(() -> {
            try (Connection connection = new ProfileDataSource(profile).getConnection()) {
                VdbDialect dialect = VdbExporter.getDialect(connection);
                List<String> schemas = new ArrayList<>();
                boolean catalogs = profile.getDbType() == DbType.MYSQL || profile.getDbType() == DbType.MARIADB
                        || profile.getDbType() == DbType.CLICKHOUSE;
                try (ResultSet rs = catalogs ? connection.getMetaData().getCatalogs()
                        : connection.getMetaData().getSchemas()) {
                    while (rs.next()) {
                        schemas.add(rs.getString(1));
                    }
                }
                String current = catalogs ? connection.getCatalog() : connection.getSchema();
                return new Object[]{dialect, schemas, current};
            }
        }, result -> {
            if (profile != targetCombo.getValue()) {
                return;
            }
            targetDialect = (VdbDialect) result[0];
            @SuppressWarnings("unchecked")
            List<String> schemas = (List<String>) result[1];
            schemaCombo.getItems().setAll(schemas);
            schemaCombo.setValue((String) result[2]);
            boolean upsert = targetDialect.isUpsertSupported();
            upsertRadio.setDisable(!upsert);
            if (!upsert && upsertRadio.isSelected()) {
                appendRadio.setSelected(true);
            }
            showStatus("status-success", Messages.format("vdb.target.ready", targetDialect.getName(),
                    targetDialect.getProductVersion(), String.join(", ", targetDialect.getFeatures().stream()
                            .map(Enum::name).toArray(String[]::new))));
            updateStart();
        }, e -> {
            if (profile != targetCombo.getValue()) {
                return;
            }
            // A data source without vectors is not a target
            VECTOR_TARGETS.remove(probeKey(profile));
            targetCombo.getItems().remove(profile);
            targetCombo.setValue(null);
            showStatus("status-error", e.getMessage());
        });
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    private void updateStart() {
        startButton.setDisable(targetDialect == null || sourceCombo.getValue() == null
                || getSelectedCollections().isEmpty());
    }

    private String validate() {
        if (getSelectedCollections().isEmpty() || targetDialect == null) {
            return Messages.get("vdb.start.error");
        }
        return null;
    }

    VdbExportConfiguration buildConfiguration() {
        VdbExportConfiguration configuration = new VdbExportConfiguration();
        configuration.setDialect(targetDialect);
        configuration.setTargetSchema(StringUtils.trimToNull(schemaCombo.getEditor().getText()));
        for (CollectionItem item : getSelectedCollections()) {
            configuration.addCollection(item.name, StringUtils.trimToNull(item.tableName.get()));
        }
        configuration.setQuery(query);
        configuration.setTableRecreated(recreateRadio.isSelected());
        configuration.setWriteMode(upsertRadio.isSelected() ? VdbExportConfiguration.WriteMode.UPSERT
                : VdbExportConfiguration.WriteMode.INSERT);
        configuration.setVectorIndexCreated(indexCheck.isSelected());
        configuration.setErrorSkipped(skipErrorsCheck.isSelected());
        configuration.setPageSize(Math.max(1, Math.min(context.getSettings().getTransferPageSize(), 1000)));
        return configuration;
    }

    private void showScripts() {
        String error = validate();
        if (error != null) {
            showStatus("status-error", error);
            return;
        }
        VdbProfile profile = getSourceProfile();
        ConnectionProfile target = targetCombo.getValue();
        VdbExportConfiguration configuration = buildConfiguration();
        TaskRunner.run(() -> {
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                return new VdbExporter(client, new ProfileDataSource(target), configuration).getScripts();
            }
        }, scripts -> {
            TextArea area = new TextArea(String.join(";\n\n", scripts) + (scripts.isEmpty() ? "" : ";"));
            area.setEditable(false);
            area.getStyleClass().add("mono");
            area.setPrefSize(820, 480);
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            Dialogs.initOwner(alert, getOwnerWindow());
            Branding.applyIcons(alert);
            alert.setTitle(Messages.get("vdb.showSql"));
            alert.setHeaderText(Messages.get("vdb.showSql"));
            alert.getDialogPane().setContent(area);
            alert.setResizable(true);
            alert.showAndWait();
        }, e -> showStatus("status-error", e.getMessage()));
    }

    /**
     * Shows the dialog and runs the import of the chosen collections in the progress dialog
     */
    public void showAndImport() {
        if (Boolean.TRUE.equals(showAndWait().orElse(false))) {
            startImport(getOwner(), context, getSourceProfile(), targetCombo.getValue(), buildConfiguration());
        }
    }

    /**
     * Runs an import in the progress dialog of the imports, each collection is a table of its progress
     */
    static void startImport(Window owner, AppContext context, VdbProfile source, ConnectionProfile target,
                            VdbExportConfiguration configuration) {
        ProgressDialog progress = new ProgressDialog(owner, context, Messages.format("progress.importTitle",
                target.getName()), configuration.getMappings().size());
        progress.setSkipErrors(configuration.isErrorSkipped());
        ExportListener listener = progress.getListener();
        String schema = configuration.getTargetSchema();
        VdbExportListener adapter = new VdbExportListener() {
            @Override
            public void onTableStarted(VdbExportResult.TableResult table) {
                listener.onTableStart(null, schema, table.getTableName(), table.getTotal());
            }

            @Override
            public void onRowsWritten(VdbExportResult.TableResult table) {
                listener.onTableProgress(null, schema, table.getTableName(), table.getRead(), table.getTotal());
            }

            @Override
            public void onRowSkipped(VdbExportResult.TableResult table, String key, Exception e) {
                listener.onError(Messages.format("vdb.progress.skipped", table.getTableName(), key, e.getMessage()),
                        e);
            }

            @Override
            public void onStatementSkipped(VdbExportResult.TableResult table, String statement, Exception e) {
                listener.onMessage(Messages.format("vdb.progress.statementSkipped", e.getMessage(), statement));
            }

            @Override
            public void onTableFinished(VdbExportResult.TableResult table) {
                listener.onTableEnd(null, schema, table.getTableName(), table.getWritten());
            }

            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }
        };
        progress.run(() -> {
            try (VdbClient client = source.getType().createClient(source.toSource())) {
                return new VdbExporter(client, new ProfileDataSource(target), configuration, adapter).export();
            }
        }, (VdbExportResult result) -> Messages.format("vdb.result.done", result.getWritten(), result.getSkipped()),
                () -> context.getSessionManager().closeSession(target.getId()));
    }

    private void showStatus(String styleClass, String text) {
        statusLabel.getStyleClass().setAll("label", styleClass);
        statusLabel.setText(text);
    }

    /**
     * Connections of a data source of the jdbc line, the vdb line writes by plain JDBC
     */
    static final class ProfileDataSource implements DataSource {

        private final ConnectionProfile profile;

        ProfileDataSource(ConnectionProfile profile) {
            this.profile = profile;
        }

        @Override
        public Connection getConnection() throws SQLException {
            String url = profile.getJdbcUrl();
            return StringUtils.isBlank(profile.getUsername()) ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, profile.getUsername(), profile.getPassword());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(profile.getJdbcUrl(), username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // Not used
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // DriverManager has its own timeout
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("Not a wrapper of " + type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
