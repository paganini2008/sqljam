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

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.service.SyncEngine;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.DataSourceExtension;
import com.github.sqljam.face.view.Dialogs;
import com.github.sqljam.face.view.Messages;
import com.github.sqljam.face.view.SourceItem;
import com.github.sqljam.face.view.CredentialsDialog;
import com.github.sqljam.utils.RestException;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbType;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: VdbDataSourceExtension makes the vector databases a category of data sources: the data source
 *               form offers it next to the relational and the OLAP databases with the vector databases as its types
 *               and VdbConnectionForm as its fields, the login page and the connection tree list the vector databases
 *               in a group of their own, which opens the import of a vector database. It is switched by
 *               sqljam.vdb.enabled like the menu item.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@Slf4j
public class VdbDataSourceExtension implements DataSourceExtension {

    /**
     * A vector database of the store
     */
    static final class VdbSourceItem implements Source {

        private final VdbProfile profile;
        private final VdbProfileStore store;

        VdbSourceItem(VdbProfile profile, VdbProfileStore store) {
            this.profile = profile;
            this.store = store;
        }

        @Override
        public String getId() {
            return profile.getId();
        }

        @Override
        public String getName() {
            return profile.getName();
        }

        @Override
        public String getTypeName() {
            return profile.getType().getDisplayName();
        }

        @Override
        public void open(Window owner, AppContext context) {
            new VdbImportDialog(owner, context, store, profile).showAndImport();
        }

        /**
         * Levels of the source: databases (Milvus, Chroma), collections, then their partitions (partitions of
         * Milvus, tenants of Weaviate, namespaces of Pinecone)
         */
        @Override
        public List<SourceItem> loadItems(SourceItem parent) throws Exception {
            VdbType type = profile.getType();
            List<SourceItem> items = new ArrayList<>();
            if (parent == null && type.isDatabaseSupported()) {
                List<String> databases = listDatabases();
                for (String database : databases) {
                    items.add(new SourceItem(SourceItem.Kind.DATABASE, List.of(database), Messages.get(
                            "connection.database"), false));
                }
                if (!databases.isEmpty()) {
                    return items;
                }
                // Without databases to list, the collections of the database of the source
            }
            if (parent == null || parent.getKind() == SourceItem.Kind.DATABASE) {
                String database = parent != null ? parent.getName() : null;
                boolean partitioned = type.getPartitionName() != null;
                try (VdbClient client = type.createClient(profileOf(database).toSource())) {
                    for (String collection : client.getCollections()) {
                        SourceItem item = parent != null ? parent.child(SourceItem.Kind.COLLECTION, collection,
                                Messages.get("vdb.collection"), !partitioned) : new SourceItem(
                                SourceItem.Kind.COLLECTION, List.of(collection), Messages.get("vdb.collection"),
                                !partitioned);
                        items.add(item);
                    }
                }
                return items;
            }
            if (parent.getKind() == SourceItem.Kind.COLLECTION) {
                try (VdbClient client = type.createClient(profileOf(databaseOf(parent)).toSource())) {
                    for (String partition : listPartitions(client, parent.getName())) {
                        items.add(parent.child(SourceItem.Kind.PARTITION, partition, type.getPartitionName(), true));
                    }
                }
            }
            return items;
        }

        /**
         * Databases of the server. A user who may not list them (Milvus) or a server without the route (older
         * Chroma) sees the database of the source, or the collections of the default one when it names none.
         */
        List<String> listDatabases() {
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                return client.getDatabases();
            } catch (RuntimeException e) {
                if (log.isInfoEnabled()) {
                    log.info("Databases of {} are not listed: {}", profile.getName(), e.getMessage());
                }
                String database = StringUtils.trimToNull(profile.getDatabase());
                return database != null ? List.of(database) : List.of();
            }
        }

        /**
         * Partitions of the collection, none when the server does not list them, the collection is read whole
         */
        List<String> listPartitions(VdbClient client, String collection) {
            try {
                return client.listPartitions(collection);
            } catch (RuntimeException e) {
                if (log.isInfoEnabled()) {
                    log.info("Partitions of {} are not listed: {}", collection, e.getMessage());
                }
                return List.of();
            }
        }

        /**
         * A key or a password which the database refused (HTTP 401 or 403, Milvus 1800), e.g. one which is not
         * remembered, is asked again. The key is asked of a database which has keys, the user and password of the
         * others.
         */
        @Override
        public boolean authenticate(Window owner, Throwable error) {
            if (!isAccessDenied(error)) {
                return false;
            }
            VdbType type = profile.getType();
            boolean key = type.isApiKeySupported() && (!type.isUserSupported() || StringUtils.isEmpty(profile
                    .getUsername()));
            Optional<CredentialsDialog.Credentials> credentials = CredentialsDialog.ask(owner, profile.getName(),
                    Messages.get(key ? "credentials.apiKey" : "connection.password"), key ? null : StringUtils
                            .defaultString(profile.getUsername()), StringUtils.abbreviate(error.getMessage(), 300));
            credentials.ifPresent(entered -> {
                if (key) {
                    profile.setApiKey(entered.getSecret());
                } else {
                    profile.setUsername(entered.getUsername());
                    profile.setPassword(entered.getSecret());
                }
                try {
                    store.saveProfile(profile, entered.isSaved());
                } catch (IOException e) {
                    Dialogs.showError(owner, Messages.get("connection.saveError"), e);
                }
            });
            return credentials.isPresent();
        }

        static boolean isAccessDenied(Throwable error) {
            for (Throwable cause = error; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
                if (cause instanceof RestException && (((RestException) cause).getStatus() == 401
                        || ((RestException) cause).getStatus() == 403)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Map<String, Map<String, String>> loadOverview() {
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                return client.getOverview();
            }
        }

        @Override
        public Map<String, Map<String, String>> loadHealth() {
            try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                return client.getHealth();
            }
        }

        /**
         * The source of a database of the server, the source itself for null
         */
        VdbProfile profileOf(String database) {
            if (database == null) {
                return profile;
            }
            VdbProfile copy = profile.copy();
            copy.setDatabase(database);
            return copy;
        }

        String databaseOf(SourceItem item) {
            return profile.getType().isDatabaseSupported() ? item.getPath().get(0) : null;
        }

        String collectionOf(SourceItem item) {
            return item.getPath().get(profile.getType().isDatabaseSupported() ? 1 : 0);
        }

        @Override
        public Tab openItem(SourceItem item, Window owner, AppContext context) {
            String database = databaseOf(item);
            VdbCollectionTab tab = new VdbCollectionTab(profileOf(database), collectionOf(item),
                    item.getKind() == SourceItem.Kind.PARTITION ? List.of(item.getName()) : List.of());
            tab.setOnImport((collection, query) -> new VdbImportDialog(owner, context, store, profile, database,
                    List.of(collection), query).showAndImport());
            return tab;
        }

        /**
         * Import of the collections of the items, of their partitions when the items are partitions
         */
        @Override
        public void open(List<SourceItem> items, Window owner, AppContext context) {
            String database = items.isEmpty() ? null : databaseOf(items.get(0));
            List<String> collections = new ArrayList<>();
            VdbQuery query = new VdbQuery();
            for (SourceItem item : items) {
                if (item.getKind() == SourceItem.Kind.DATABASE) {
                    continue;
                }
                String collection = collectionOf(item);
                if (!collections.contains(collection)) {
                    collections.add(collection);
                }
                if (item.getKind() == SourceItem.Kind.PARTITION) {
                    query.getPartitions().add(item.getName());
                }
            }
            new VdbImportDialog(owner, context, store, profile, database, collections, query).showAndImport();
        }

        @Override
        public boolean edit(Window owner, AppContext context) {
            return VdbDataSourceExtension.edit(owner, store, profile) != null;
        }

        @Override
        public boolean delete(Window owner, AppContext context) {
            if (!Dialogs.confirm(owner, Messages.get("vdb.connection.delete"), Messages.format(
                    "connection.delete.message", profile.getName()))) {
                return false;
            }
            try {
                store.removeProfile(profile.getId());
                return true;
            } catch (IOException e) {
                Dialogs.showError(owner, Messages.get("connection.save.error"), e);
                return false;
            }
        }
    }

    /**
     * Fields of a vector database of the data source form
     */
    final class VdbSourceForm implements SourceForm {

        private final VdbConnectionForm form;
        private VdbProfile profile;

        VdbSourceForm(double labelWidth) {
            form = new VdbConnectionForm(labelWidth);
        }

        @Override
        public Node getNode() {
            return form;
        }

        @Override
        public List<String> getTypeNames() {
            List<String> names = new ArrayList<>();
            for (VdbType type : VdbType.values()) {
                names.add(type.getDisplayName());
            }
            return names;
        }

        @Override
        public void setType(String typeName) {
            for (VdbType type : VdbType.values()) {
                if (type.getDisplayName().equals(typeName)) {
                    form.setType(type);
                }
            }
        }

        @Override
        public String getType() {
            return form.getType() != null ? form.getType().getDisplayName() : null;
        }

        @Override
        public void setSource(Source source) {
            profile = source instanceof VdbSourceItem ? ((VdbSourceItem) source).profile : null;
            form.setProfile(profile, profile == null || getStore().isSecretRemembered(profile.getId()));
        }

        @Override
        public String validate() {
            return form.validate();
        }

        @Override
        public Source save(String name) throws IOException {
            VdbProfile saved = form.read(name);
            getStore().saveProfile(saved, form.isSecretRemembered());
            profile = saved;
            return new VdbSourceItem(saved, getStore());
        }

        @Override
        public void test(String name, Consumer<String> onSuccess, Consumer<String> onError) {
            form.test(name, onSuccess, onError);
        }

        @Override
        public void focusFirstField() {
            form.focusFirstField();
        }
    }

    private final VdbProfileStore store;

    public VdbDataSourceExtension() {
        this(null);
    }

    /**
     * @param store store of the vector databases, null for the store of the application
     */
    VdbDataSourceExtension(VdbProfileStore store) {
        this.store = store;
    }

    private VdbProfileStore getStore() {
        return store != null ? store : VdbProfileStore.getInstance();
    }

    @Override
    public SyncSupport getSyncSupport() {
        return new SyncSupport() {
            @Override
            public SyncTask.Kind getKind() {
                return SyncTask.Kind.VECTOR;
            }

            @Override
            public SyncEngine createEngine(Function<String, Optional<ConnectionProfile>> profiles) {
                return new VdbSyncEngine(getStore(), profiles);
            }

            private VdbProfile profile(String sourceId, String database) {
                VdbProfile profile = getStore().getProfiles().stream().filter(item -> item.getId().equals(sourceId))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("No vector database " + sourceId));
                if (StringUtils.isNotBlank(database)) {
                    profile = profile.copy();
                    profile.setDatabase(database);
                }
                return profile;
            }

            @Override
            public List<String> getDatabases(String sourceId) {
                VdbProfile profile = profile(sourceId, null);
                try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                    return client.getDatabases();
                }
            }

            @Override
            public List<String> getCollections(String sourceId, String database) {
                VdbProfile profile = profile(sourceId, database);
                try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                    return client.getCollections();
                }
            }

            @Override
            public List<String> getWatermarkFields(String sourceId, String database, String collection) {
                VdbProfile profile = profile(sourceId, database);
                try (VdbClient client = profile.getType().createClient(profile.toSource())) {
                    return client.describe(collection).getFields().stream().filter(field -> !field.isPrimaryKey()
                            && WATERMARK_KINDS.contains(field.getKind())).map(VdbField::getName).collect(Collectors
                            .toList());
                }
            }
        };
    }

    /**
     * Kinds of the fields which a sync task compares: numbers and times
     */
    static final Set<VdbField.Kind> WATERMARK_KINDS = EnumSet.of(VdbField.Kind.SMALLINT, VdbField.Kind.INTEGER,
            VdbField.Kind.BIGINT, VdbField.Kind.REAL, VdbField.Kind.DOUBLE, VdbField.Kind.TIMESTAMP);

    @Override
    public SourceForm createForm(double labelWidth) {
        return new VdbSourceForm(labelWidth);
    }

    @Override
    public String getGroupName() {
        return Messages.get("vdb.group");
    }

    @Override
    public String getOpenItemText() {
        return Messages.get("vdb.openItem");
    }

    @Override
    public String getOpenText() {
        return Messages.get("vdb.open");
    }

    @Override
    public boolean isEnabled(Config config) {
        return config.getBoolean(VdbMenuExtension.ENABLED_KEY, true);
    }

    @Override
    public List<Source> getSources() {
        List<Source> sources = new ArrayList<>();
        getStore().getProfiles().forEach(profile -> sources.add(new VdbSourceItem(profile, getStore())));
        return sources;
    }

    /**
     * Creates or edits a vector database, the saved one or null if it is cancelled
     */
    static VdbProfile edit(Window owner, VdbProfileStore store, VdbProfile profile) {
        VdbConnectionDialog dialog = new VdbConnectionDialog(owner, profile, profile == null
                || store.isSecretRemembered(profile.getId()));
        VdbProfile saved = dialog.showAndWait().orElse(null);
        if (saved == null) {
            return null;
        }
        try {
            store.saveProfile(saved, dialog.isSecretRemembered());
            return saved;
        } catch (IOException e) {
            Dialogs.showError(owner, Messages.get("connection.save.error"), e);
            return null;
        }
    }
}
