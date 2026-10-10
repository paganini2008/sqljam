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
package com.github.sqljam.face.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.SqlScriptRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: ExampleDatabase creates an H2 database of a small online shop at the first start (no data sources are
 *               saved yet), so that a new user can browse, query, export and import tables right away. It is
 *               configured by sqljam.example.enabled and sqljam.example.directory.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class ExampleDatabase {

    /**
     * Name of the data source of the example database
     */
    public static final String PROFILE_NAME = "Example Shop (H2)";
    /**
     * Id of the data source of the example database, a restored example replaces it
     */
    public static final String PROFILE_ID = "example-shop";
    public static final String DATABASE_NAME = "shop";
    public static final String USERNAME = "sa";

    private static final String SCRIPT = "/com/github/sqljam/face/example/shop.sql";

    private final File directory;
    private final boolean enabled;

    public ExampleDatabase() {
        this(Config.getInstance().getBoolean("sqljam.example.enabled", true), Config.getInstance().getFile(
                "sqljam.example.directory", new File(JsonStore.DEFAULT_DIRECTORY, "example")));
    }

    public ExampleDatabase(boolean enabled, File directory) {
        this.enabled = enabled;
        this.directory = directory;
    }

    /**
     * The example database at the first start: when the file of data sources does not exist yet. A data source
     * removed by the user is not created again.
     *
     * @param connectionsFile file of the saved data sources
     * @return the data source of the example database, empty if it is not created
     */
    public Optional<ConnectionProfile> createAtFirstStart(File connectionsFile) {
        if (!enabled || connectionsFile.exists()) {
            return Optional.empty();
        }
        try {
            return Optional.of(create());
        } catch (IOException | SQLException e) {
            // The example is a convenience, the application starts without it
            if (log.isWarnEnabled()) {
                log.warn("Unable to create the example database in {}", directory, e);
            }
            return Optional.empty();
        }
    }

    /**
     * The example database of a saved example data source whose database file is missing (deleted by the user),
     * it is created again at the start
     *
     * @return the data source if its database is created again
     */
    public Optional<ConnectionProfile> repairAtStart(List<ConnectionProfile> profiles) {
        Optional<ConnectionProfile> example = profiles.stream().filter(ExampleDatabase::isExample).findFirst();
        if (!enabled || example.isEmpty() || !isDatabaseMissing(example.get())) {
            return Optional.empty();
        }
        try {
            return Optional.of(create());
        } catch (IOException | SQLException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to create the example database in {} again", directory, e);
            }
            return Optional.empty();
        }
    }

    public static boolean isExample(ConnectionProfile profile) {
        return profile != null && PROFILE_ID.equals(profile.getId());
    }

    /**
     * Whether the database file of the data source does not exist, H2 would create an empty database
     */
    public static boolean isDatabaseMissing(ConnectionProfile profile) {
        return profile.getDatabase() == null || !new File(profile.getDatabase() + ".mv.db").isFile();
    }

    /**
     * Creates the database and its tables, an existing database of the directory is replaced
     */
    public ConnectionProfile create() throws IOException, SQLException {
        FileUtils.forceMkdir(directory);
        File file = new File(directory, DATABASE_NAME);
        FileUtils.deleteQuietly(new File(file.getPath() + ".mv.db"));
        FileUtils.deleteQuietly(new File(file.getPath() + ".trace.db"));
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(PROFILE_ID);
        profile.setName(PROFILE_NAME);
        profile.setDbType(DbType.H2);
        profile.setDatabase(file.getAbsolutePath());
        profile.setUsername(USERNAME);
        profile.setPassword("");
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), USERNAME, "");
             InputStream in = ExampleDatabase.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IOException("Script of the example database not found: " + SCRIPT);
            }
            new SqlScriptRunner(connection).runScript(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        if (log.isInfoEnabled()) {
            log.info("Example database created: {}", file);
        }
        return profile;
    }

    public File getDirectory() {
        return directory;
    }
}
