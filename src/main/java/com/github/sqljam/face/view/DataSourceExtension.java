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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Function;

import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.service.SyncEngine;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.stage.Window;

/**
 * @Description: DataSourceExtension is a kind of data source other than the relational and the OLAP databases,
 *               found by ServiceLoader, e.g. the vector databases of the vdb line. The data source form offers it as a
 *               category whose types follow it in the type list and whose fields replace the JDBC fields, the login
 *               page and the connection tree list its sources in a group of their own. Its own settings switch it
 *               on or off, and the classes of its line are loaded only when one of its sources is used.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public interface DataSourceExtension {

    /**
     * A data source of the kind, shown in the connection tree
     */
    interface Source {

        String getId();

        String getName();

        /**
         * Type of the source, e.g. Qdrant
         */
        String getTypeName();

        /**
         * The main action of the source, e.g. the import of its collections
         */
        void open(Window owner, AppContext context);

        /**
         * Metrics of the dashboard of the info of the source, null for sources without
         */
        default java.util.function.Supplier<com.github.sqljam.face.service.MetricSource> getDashboard() {
            return null;
        }

        /**
         * Children of an item of the source in the connection tree, the top items for null: databases,
         * collections and their partitions as the source has them, read in the background
         */
        List<SourceItem> loadItems(SourceItem parent) throws Exception;

        /**
         * Tab of a collection or of a partition in the tabs of the main window: its fields and its records
         */
        Tab openItem(SourceItem item, Window owner, AppContext context);

        /**
         * The main action of some items of the source, e.g. the import of some collections or partitions
         */
        void open(List<SourceItem> items, Window owner, AppContext context);

        /**
         * Overview of the source by sections, read in the background
         */
        Map<String, Map<String, String>> loadOverview() throws Exception;

        /**
         * Health of the source by sections, read in the background
         */
        Map<String, Map<String, String>> loadHealth() throws Exception;

        /**
         * Edits the source, true if it is saved
         */
        boolean edit(Window owner, AppContext context);

        /**
         * Deletes the source after a confirmation, true if it is deleted
         */
        boolean delete(Window owner, AppContext context);

        /**
         * Asks for the secret which the source refused (e.g. an API key which is not remembered), true when a new
         * one is entered and the source is read again
         */
        default boolean authenticate(Window owner, Throwable error) {
            return false;
        }
    }

    /**
     * Fields of a source of the kind below its name and type, shown by the data source form
     */
    interface SourceForm {

        Node getNode();

        /**
         * Type names of the kind, e.g. Qdrant, in the order of the type list
         */
        List<String> getTypeNames();

        void setType(String typeName);

        String getType();

        /**
         * Shows a source, a new one if it is null
         */
        void setSource(Source source);

        /**
         * Error of the fields, null if they are valid
         */
        String validate();

        /**
         * Saves the source of the fields, a new source or the shown one
         */
        Source save(String name) throws IOException;

        /**
         * Connects in the background
         */
        void test(String name, Consumer<String> onSuccess, Consumer<String> onError);

        void focusFirstField();
    }

    /**
     * Fields of the sources of the kind
     *
     * @param labelWidth width of the labels of the form around them
     */
    SourceForm createForm(double labelWidth);

    /**
     * Name of the group of the sources in the connection tree, e.g. Vector databases
     */
    String getGroupName();

    /**
     * Text of the main action of a source in its menu, e.g. Import into Database...
     */
    String getOpenText();

    /**
     * Text of opening an item of a source, e.g. Open Collection
     */
    String getOpenItemText();

    boolean isEnabled(Config config);

    List<Source> getSources();

    /**
     * Sync tasks of the sources of the kind: the engine which runs them, and the parts which the task dialog lists
     */
    interface SyncSupport {

        /**
         * Kind of the tasks of the sources
         */
        SyncTask.Kind getKind();

        SyncEngine createEngine(Function<String, Optional<ConnectionProfile>> profiles);

        /**
         * Databases of a source, empty for sources without databases
         */
        List<String> getDatabases(String sourceId) throws Exception;

        List<String> getCollections(String sourceId, String database) throws Exception;

        /**
         * Fields of a collection which can be watermarks: numbers and times
         */
        List<String> getWatermarkFields(String sourceId, String database, String collection) throws Exception;
    }

    /**
     * Sync tasks of the sources, null for kinds without
     */
    default SyncSupport getSyncSupport() {
        return null;
    }

    /**
     * Extensions which their settings switch on
     */
    static List<DataSourceExtension> load(Config config) {
        List<DataSourceExtension> extensions = new ArrayList<>();
        for (DataSourceExtension extension : ServiceLoader.load(DataSourceExtension.class)) {
            if (extension.isEnabled(config)) {
                extensions.add(extension);
            }
        }
        return extensions;
    }
}
