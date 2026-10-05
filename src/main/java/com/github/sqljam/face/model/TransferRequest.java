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
package com.github.sqljam.face.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.ScriptExportHandler;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: TransferRequest describes an export to sql scripts or an import into another database
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Getter
@Setter
public class TransferRequest {

    public enum Target {

        SCRIPT,

        DATABASE
    }

    private ConnectionProfile source;
    private String sourceCatalog;
    private String sourceSchema;
    /**
     * Selected tables, empty means all tables of the schema
     */
    private List<String> tables = new ArrayList<>();
    private ExportMode exportMode = ExportMode.DDL_DATA;
    private Target target = Target.SCRIPT;

    // Options
    private boolean tableRecreated = true;
    private boolean idReused = true;
    private boolean indexIncluded = true;
    private boolean foreignKeyIncluded = true;
    private boolean commentIncluded = true;
    private boolean sequenceIncluded = true;
    private boolean failFast = true;
    private int pageSize = Exporter.DEFAULT_PAGE_SIZE;
    private int lobPageSize = Exporter.DEFAULT_LOB_PAGE_SIZE;
    private IdentifierCase identifierCase = IdentifierCase.AUTO;

    // Script target
    private File outputDirectory;
    private DbType scriptDbType;
    /**
     * Target database version of script, e.g. "11.2", blank means same as source (or latest)
     */
    private String scriptDbVersion;
    private String scriptSchema;
    private DataFileStrategy dataFileStrategy = DataFileStrategy.SINGLE_FILE;
    /**
     * Max bytes of a data file, 0 means unlimited
     */
    private long maxFileSize = ScriptExportHandler.DEFAULT_MAX_FILE_SIZE;
    private boolean lobSeparated = true;

    // Database target
    private ConnectionProfile targetProfile;
    private String targetCatalog;
    private String targetSchema;
    private boolean targetSchemaCreated = true;
}
