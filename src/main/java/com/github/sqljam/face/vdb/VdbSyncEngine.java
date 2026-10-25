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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.SyncTask;
import com.github.sqljam.face.model.SyncTask.Watermark;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.service.SyncEngine;
import com.github.sqljam.face.service.SyncErrors;
import com.github.sqljam.face.service.SyncRunner;
import com.github.sqljam.face.service.TransferService;
import com.github.sqljam.impexp.ExportCancelledException;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbExportResult;
import com.github.sqljam.vdb.VdbExporter;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.dialect.VdbDialect;

/**
 * @Description: VdbSyncEngine runs sync tasks of collections of a vector database: the records whose watermark
 *               field (a number or a time of the payload) is at least the last watermark are updated or inserted by
 *               id into the kept tables of the target, all records the first time. Vector databases can not tell
 *               the greatest value of a field, so the watermark is read from the target table after the rows were
 *               written; records of that value are read again by the next run and updated.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbSyncEngine implements SyncEngine {

    private final VdbProfileStore store;
    private final Function<String, Optional<ConnectionProfile>> profiles;

    public VdbSyncEngine(VdbProfileStore store, Function<String, Optional<ConnectionProfile>> profiles) {
        this.store = store;
        this.profiles = profiles;
    }

    private Optional<VdbProfile> findSource(String id) {
        return store.getProfiles().stream().filter(profile -> profile.getId().equals(id)).findFirst();
    }

    /**
     * The vector database of the task in its database of the task
     */
    VdbProfile source(SyncTask task) {
        VdbProfile profile = findSource(task.getSourceId()).orElseThrow(() -> new SyncErrors.PermanentException(
                "The vector database of the task was removed."));
        if (StringUtils.isNotBlank(task.getSourceCatalog())) {
            profile = profile.copy();
            profile.setDatabase(task.getSourceCatalog());
        }
        return profile;
    }

    @Override
    public void describe(SyncTask task, TransferReport report) {
        report.setKind(TransferReport.Kind.VECTOR_SYNC);
        report.setTitle("Sync " + task.getName());
        report.setSource(findSource(task.getSourceId()).map(profile -> profile.getName() + " (" + profile.getType()
                + ")").orElse(task.getSourceId()) + (StringUtils.isNotBlank(task.getSourceCatalog()) ? " / " + task
                .getSourceCatalog() : ""));
        report.setTarget(profiles.apply(task.getTargetId()).map(ConnectionProfile::getName).orElse(task
                .getTargetId()) + (StringUtils.isNotBlank(task.getTargetSchema()) ? " / " + task.getTargetSchema()
                : ""));
        report.setTransaction(TransferReport.Transaction.EVERY_ROWS);
        report.getOptions().put("Task", task.getName());
        report.getOptions().put("Copy", task.isInitialized() ? "Records changed since the last run"
                : "All records, the first run");
        report.getOptions().put("Write mode", "Update or insert by id");
        report.getOptions().put("Records deleted in the source", "Kept in the target");
    }

    @Override
    public Map<String, Watermark> run(SyncTask task, TransferReport report, ExportListener listener)
            throws Exception {
        VdbProfile source = source(task);
        ConnectionProfile target = SyncRunner.requireProfile(profiles, task.getTargetId(), "target");
        ProfileDataSource dataSource = new ProfileDataSource(target);
        VdbDialect dialect;
        try (Connection connection = dataSource.getConnection()) {
            dialect = VdbExporter.getDialect(connection);
        }
        if (!dialect.isUpsertSupported()) {
            throw new SyncErrors.PermanentException(target.getName() + " can not update rows by key, a sync task "
                    + "needs it.");
        }
        Map<String, Watermark> watermarks = new LinkedHashMap<>();
        for (SyncTask.Table table : task.getTables()) {
            Watermark last = table.getWatermark();
            VdbExportConfiguration configuration = new VdbExportConfiguration();
            configuration.setDialect(dialect);
            configuration.setTargetSchema(StringUtils.trimToNull(task.getTargetSchema()));
            configuration.addCollection(table.getName(), StringUtils.trimToNull(table.getTargetName()));
            VdbQuery query = VdbQuery.all();
            if (last != null) {
                // Records of the same value are read again and updated, keys which only grow are new records
                query.where(table.getWatermarkColumn(), table.getWatermarkType() == SyncTask.WatermarkType
                        .UPDATED_AT ? VdbQuery.Operator.GE : VdbQuery.Operator.GT, queryValue(last));
            }
            configuration.setQuery(query);
            configuration.setTableRecreated(false);
            configuration.setWriteMode(VdbExportConfiguration.WriteMode.UPSERT);
            configuration.setVectorIndexCreated(true);
            configuration.setErrorSkipped(false);
            configuration.setPageSize(Math.max(1, Math.min(task.getPageSize(), 1000)));
            VdbExportResult result;
            try (VdbClient client = source.getType().createClient(source.toSource())) {
                result = new VdbExporter(client, dataSource, configuration, VdbListeners.adapt(listener, task
                        .getTargetSchema())).export();
            }
            if (result.isCancelled() || listener.isCancelled()) {
                throw new ExportCancelledException();
            }
            String tableName = result.getTables().isEmpty() ? table.getName() : result.getTables().get(0)
                    .getTableName();
            Watermark next;
            try (Connection connection = dataSource.getConnection()) {
                next = readMax(connection, task.getTargetSchema(), tableName, table.getWatermarkColumn());
            }
            watermarks.put(table.getName(), next != null ? next : last);
            report.getOptions().put("Watermark of " + table.getName(), table.getWatermarkColumn() + " from "
                    + (last != null ? last : "the first record") + " to " + (next != null ? next : "no records"));
        }
        return watermarks;
    }

    @Override
    public void verify(SyncTask task, TransferReport report) throws Exception {
        ConnectionProfile target = SyncRunner.requireProfile(profiles, task.getTargetId(), "target");
        try (Connection connection = new ProfileDataSource(target).getConnection()) {
            TransferService.countTargetRows(connection, null, task.getTargetSchema(), report, name -> name, false);
        }
    }

    /**
     * A number or a time as the vector database compares it
     */
    static Object queryValue(Watermark watermark) {
        Object value = SyncRunner.valueOf(watermark);
        if (value instanceof BigDecimal) {
            BigDecimal number = ((BigDecimal) value).stripTrailingZeros();
            return number.scale() <= 0 ? (Object) number.longValueExact() : (Object) number.doubleValue();
        }
        return watermark.getValue();
    }

    /**
     * The greatest value of the column of the field in the target table, whose names are found without case
     */
    static Watermark readMax(Connection connection, String schema, String table, String field) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        String quote = StringUtils.defaultIfBlank(StringUtils.trim(metaData.getIdentifierQuoteString()), "\"");
        String[] found = null;
        try (ResultSet rs = metaData.getColumns(null, StringUtils.trimToNull(schema), null, null)) {
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equalsIgnoreCase(table) && rs.getString("COLUMN_NAME")
                        .equalsIgnoreCase(field)) {
                    found = new String[]{rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"), rs.getString(
                            "COLUMN_NAME")};
                    break;
                }
            }
        }
        if (found == null) {
            throw new SyncErrors.PermanentException(String.format(Locale.ENGLISH, "The field %s is not a column "
                    + "of the table %s of the target.", field, table));
        }
        String name = (StringUtils.isNotBlank(found[0]) ? quote(found[0], quote) + "." : "") + quote(found[1], quote);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(" + quote(found[2], quote) + ") FROM " + name)) {
            return rs.next() ? SyncRunner.watermarkOf(rs.getObject(1)) : null;
        }
    }

    private static String quote(String name, String quote) {
        return quote + name.replace(quote, quote + quote) + quote;
    }
}
