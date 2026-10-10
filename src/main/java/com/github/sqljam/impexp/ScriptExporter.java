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
package com.github.sqljam.impexp;

import java.io.File;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description: ScriptExporter exports tables of a database as an export package of sql scripts
 * @Author: Fred Feng
 * @Date: 31/03/2023
 * @Version 1.0.0
 */
public final class ScriptExporter {

    public ScriptExporter(File root, boolean separate) {
        this(root, separate ? DataFileStrategy.FILE_PER_TABLE : DataFileStrategy.SINGLE_FILE,
                ScriptExportHandler.DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * @param maxFileSize max bytes of a data file, 0 means unlimited
     */
    public ScriptExporter(File root, DataFileStrategy dataFileStrategy, long maxFileSize) {
        this.scriptExportHandler = new ScriptExportHandler(root, dataFileStrategy, maxFileSize);
        this.exporter = new Exporter(scriptExportHandler);
        // Only table level objects (tables, indexes and constraints) are exported by default
        getConfiguration().setShowCreateUserSql(false);
        getConfiguration().setShowCreateCatalogSql(false);
        getConfiguration().setShowCreateSchemaSql(false);
    }

    private final ScriptExportHandler scriptExportHandler;
    private final Exporter exporter;
    private DbType targetDbType;
    private IdentifierCase identifierCase;
    private String targetCatalogName;
    private String targetSchemaName;
    private int targetMajorVersion = -1;
    private int targetMinorVersion = -1;

    public Exporter.ExportConfiguration getConfiguration() {
        return exporter.getConfiguration();
    }

    public void setMetaDataOperations(MetaDataOperations metaDataOperations) {
        exporter.setMetaDataOperations(metaDataOperations);
    }

    public void setExportListener(ExportListener exportListener) {
        exporter.setExportListener(exportListener);
    }

    /**
     * Generates script for another database type. Default is the same as source database.
     */
    public void setTargetDbType(DbType targetDbType) {
        this.targetDbType = targetDbType;
    }

    public void setIdentifierCase(IdentifierCase identifierCase) {
        this.identifierCase = identifierCase;
    }

    /**
     * Qualifies table names of the script with the target catalog/schema
     */
    public void setTargetCatalogName(String targetCatalogName) {
        this.targetCatalogName = targetCatalogName;
    }

    public void setTargetSchemaName(String targetSchemaName) {
        this.targetSchemaName = targetSchemaName;
    }

    /**
     * Generates script for the given version of target database, e.g. 11.2 for Oracle 11g. Default is the version
     * of source database when the target is the same database type, otherwise the latest version.
     */
    public void setTargetVersion(int majorVersion, int minorVersion) {
        this.targetMajorVersion = majorVersion;
        this.targetMinorVersion = minorVersion;
    }

    /**
     * Writes LOB values into lob/ directory and lob-manifest.json (default), or inline in insert statements
     */
    public void setLobSeparated(boolean lobSeparated) {
        scriptExportHandler.setLobSeparated(lobSeparated);
    }

    /**
     * Writes foreign keys into constraints.sql even if rows are not exported (default: into schema.sql)
     */
    public void setConstraintsSeparated(boolean constraintsSeparated) {
        scriptExportHandler.setConstraintsSeparated(constraintsSeparated);
    }

    public List<File> getWrittenFiles() {
        return scriptExportHandler.getWrittenFiles();
    }

    private void prepare() {
        Exporter.ExportConfiguration configuration = getConfiguration();
        if (configuration.getDbType() == null) {
            configuration.setDbType(DbType.forUrl(configuration.getUrl()));
        }
        if (targetDbType != null && configuration.getDialect().getDbType() != targetDbType) {
            Dialect dialect = targetDbType.createDialect();
            dialect.setSourceDbType(configuration.getDbType());
            configuration.setDialect(dialect);
        }
        Dialect dialect = configuration.getDialect();
        if (identifierCase != null) {
            dialect.setIdentifierCase(identifierCase);
        }
        if (StringUtils.isNotBlank(targetSchemaName)) {
            dialect.setTargetCatalogName(targetCatalogName);
            dialect.setTargetSchemaName(targetSchemaName);
        } else if (StringUtils.isNotBlank(targetCatalogName) && !dialect.getDbType().isSchemaSupported()) {
            dialect.setTargetCatalogName(targetCatalogName);
        }
        if (targetMajorVersion >= 0) {
            configuration.setDialect(dialect.forVersion(targetMajorVersion, Math.max(targetMinorVersion, 0)));
        }
    }

    public void export(ExportMode exportMode) throws Exception {
        prepare();
        exporter.export(exportMode);
    }

    public void exportDdlAndData() throws Exception {
        export(ExportMode.DDL_DATA);
    }

    public void exportDdl() throws Exception {
        export(ExportMode.DDL);
    }

    public void exportData() throws Exception {
        export(ExportMode.DATA);
    }
}
