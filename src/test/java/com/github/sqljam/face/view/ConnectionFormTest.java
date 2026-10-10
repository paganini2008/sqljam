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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/**
 * @Description: ConnectionFormTest verifies the data source form: required fields of each database type, port
 *               bounds, type switching, custom jdbc url, connection test and remembered passwords
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ConnectionFormTest {

    @TempDir
    File dir;

    private ConnectionForm form;
    private AppContext context;
    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        form = new ConnectionForm();
        stage.setScene(new Scene(form, 600, 500));
        stage.show();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<DbType> dbTypeCombo() {
        return (ComboBox<DbType>) form.lookup("#dbTypeCombo");
    }

    private TextField field(String id) {
        return (TextField) form.lookup("#" + id);
    }

    private void fill(DbType dbType, String host, String port, String database) {
        FxTestSupport.run(() -> {
            dbTypeCombo().setValue(dbType);
            field("hostField").setText(host);
            field("portField").setText(port);
            field("databaseField").setText(database);
        });
    }

    private String validate() {
        return FxTestSupport.call(form::validate);
    }

    @Test
    void listsOnlySupportedDatabaseTypes() {
        assertEquals(DbType.values().length, FxTestSupport.call(() -> dbTypeCombo().getItems().size()));
    }

    @Test
    void requiresDatabaseOfPostgreSQL() {
        fill(DbType.POSTGRESQL, "localhost", "5432", "");
        assertEquals("Database is required", validate());
        fill(DbType.POSTGRESQL, "localhost", "5432", "demo");
        assertNull(validate());
    }

    @Test
    void requiresServiceNameOfOracle() {
        fill(DbType.ORACLE, "localhost", "1521", "");
        assertEquals("Service name is required", validate());
        assertEquals("Service name", FxTestSupport.call(() -> ((Label) form.lookup("#databaseLabel")).getText()));
    }

    @Test
    void requiresFileOfFileDatabases() {
        fill(DbType.H2, "", "", "");
        assertEquals("File is required", validate());
        fill(DbType.SQLITE, "", "", " ");
        assertEquals("File is required", validate());
        fill(DbType.SQLITE, "", "", new File(dir, "a.sqlite").getAbsolutePath());
        assertNull(validate());
        fill(DbType.DUCKDB, "", "", "");
        assertEquals("File is required", validate());
        fill(DbType.DUCKDB, "", "", new File(dir, "a.duckdb").getAbsolutePath());
        assertNull(validate());
    }

    @Test
    void databaseIsOptionalForMySQLAndSQLServer() {
        fill(DbType.MYSQL, "localhost", "3306", "");
        assertNull(validate());
        fill(DbType.SQLSERVER, "localhost", "1433", "");
        assertNull(validate());
    }

    @Test
    void connectsClickHouseServer() {
        fill(DbType.MYSQL, "localhost", "3306", "");
        FxTestSupport.run(() -> dbTypeCombo().setValue(DbType.CLICKHOUSE));
        // The default http port of ClickHouse, the database is optional
        assertEquals("8123", FxTestSupport.call(() -> field("portField").getText()));
        assertEquals("Database", FxTestSupport.call(() -> ((Label) form.lookup("#databaseLabel")).getText()));
        fill(DbType.CLICKHOUSE, "localhost", "8123", "");
        assertNull(validate());
        fill(DbType.CLICKHOUSE, "localhost", "18123", "app");
        assertNull(validate());
        ConnectionProfile profile = FxTestSupport.call(form::getProfile);
        assertEquals("jdbc:clickhouse:http://localhost:18123/app?compress=0", profile.getJdbcUrl());
        // A url entered by the user gets the options of SqlJam, configured options are kept
        profile.setUrl("jdbc:clickhouse:http://localhost:18123/app?ssl=false");
        assertEquals("jdbc:clickhouse:http://localhost:18123/app?ssl=false&compress=0", profile.getJdbcUrl());
        profile.setUrl("jdbc:clickhouse:http://localhost:18123/app?compress=1");
        assertEquals("jdbc:clickhouse:http://localhost:18123/app?compress=1", profile.getJdbcUrl());
        assertEquals(DbType.CLICKHOUSE, DbType.forUrl("jdbc:clickhouse:http://localhost:18123/app"));
        assertEquals(DbType.CLICKHOUSE, DbType.forUrl("jdbc:ch://localhost:8123"));
    }

    @Test
    void requiresHost() {
        fill(DbType.MYSQL, " ", "3306", "test");
        assertEquals("Host is required", validate());
    }

    @Test
    void validatesPortRange() {
        String error = "Port must be a number between 1 and 65535";
        fill(DbType.MYSQL, "localhost", "", "test");
        assertEquals(error, validate());
        fill(DbType.MYSQL, "localhost", "0", "test");
        assertEquals(error, validate());
        fill(DbType.MYSQL, "localhost", "65536", "test");
        assertEquals(error, validate());
        fill(DbType.MYSQL, "localhost", "65535", "test");
        assertNull(validate());
        fill(DbType.MYSQL, "localhost", "1", "test");
        assertNull(validate());
    }

    @Test
    void portAcceptsDigitsOnly(FxRobot robot) {
        fill(DbType.MYSQL, "localhost", "", "test");
        robot.clickOn(field("portField")).write("-1a2b").write("345678");
        // Negative sign and letters are rejected, at most 5 digits are accepted
        assertEquals("12345", FxTestSupport.call(() -> field("portField").getText()));
    }

    @Test
    void switchingTypeResetsDefaultPort() {
        fill(DbType.MYSQL, "localhost", "3306", "test");
        FxTestSupport.run(() -> dbTypeCombo().setValue(DbType.POSTGRESQL));
        assertEquals("5432", FxTestSupport.call(() -> field("portField").getText()));
        // A custom port is kept
        FxTestSupport.run(() -> {
            field("portField").setText("15432");
            dbTypeCombo().setValue(DbType.ORACLE);
        });
        assertEquals("15432", FxTestSupport.call(() -> field("portField").getText()));
    }

    @Test
    void switchingTypeTogglesFileChooser() {
        fill(DbType.SQLITE, "", "", "");
        assertFalse(FxTestSupport.call(() -> field("hostField").isVisible() && field("hostField").getParent()
                .isVisible()));
        assertTrue(FxTestSupport.call(() -> form.lookup("#browseButton").isVisible()));
        assertEquals("File", FxTestSupport.call(() -> ((Label) form.lookup("#databaseLabel")).getText()));
        FxTestSupport.run(() -> dbTypeCombo().setValue(DbType.MYSQL));
        assertTrue(FxTestSupport.call(() -> field("hostField").getParent().isVisible()));
        assertEquals("3306", FxTestSupport.call(() -> field("portField").getText()));
        // Browsing databases of the server
        assertTrue(FxTestSupport.call(() -> form.lookup("#browseButton").isVisible()));
        FxTestSupport.run(() -> dbTypeCombo().setValue(DbType.ORACLE));
        assertFalse(FxTestSupport.call(() -> form.lookup("#browseButton").isVisible()));        // DuckDB is a file like SQLite
        FxTestSupport.run(() -> dbTypeCombo().setValue(DbType.DUCKDB));
        assertFalse(FxTestSupport.call(() -> field("hostField").getParent().isVisible()));
        assertTrue(FxTestSupport.call(() -> form.lookup("#browseButton").isVisible()));
        assertEquals("File", FxTestSupport.call(() -> ((Label) form.lookup("#databaseLabel")).getText()));
    }

    @Test
    void customUrlOverridesHostAndPort() {
        fill(DbType.MYSQL, "", "", "");
        FxTestSupport.run(() -> field("urlField").setText("jdbc:mysql://db.example.com:3307/app"));
        assertNull(validate());
        ConnectionProfile profile = FxTestSupport.call(form::getProfile);
        assertEquals("jdbc:mysql://db.example.com:3307/app", profile.getJdbcUrl());
    }

    @Test
    void generatesDefaultName() {
        fill(DbType.MYSQL, "localhost", "3306", "test");
        assertEquals("MySQL - localhost/test", FxTestSupport.call(form::getProfile).getName());
        fill(DbType.SQLITE, "", "", "/tmp/app.sqlite");
        assertEquals("SQLite - app.sqlite", FxTestSupport.call(form::getProfile).getName());
        FxTestSupport.run(() -> field("nameField").setText("  Prod  "));
        assertEquals("Prod", FxTestSupport.call(form::getProfile).getName());
    }

    @Test
    void keepsIdOfEditedProfile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setDbType(DbType.POSTGRESQL);
        profile.setHostname("pg");
        profile.setPort(6543);
        profile.setDatabase("app");
        FxTestSupport.run(() -> form.setProfile(profile, false));
        ConnectionProfile edited = FxTestSupport.call(form::getProfile);
        assertEquals(profile.getId(), edited.getId());
        assertEquals(6543, edited.getPort());
        assertFalse(FxTestSupport.call(form::isPasswordRemembered));
    }

    @Test
    void testShowsErrorOfUnreachableHost() {
        // Nothing listens on port 1
        fill(DbType.MYSQL, "127.0.0.1", "1", "test");
        AtomicReference<String> product = new AtomicReference<>();
        FxTestSupport.run(() -> form.testConnection(product::set));
        FxTestSupport.waitUntil(() -> !form.busyProperty().get());
        Label status = (Label) form.lookup("#formStatusLabel");
        assertTrue(FxTestSupport.call(status::getText).startsWith("Connection failed:"),
                FxTestSupport.call(status::getText));
        assertTrue(FxTestSupport.call(() -> status.getStyleClass().contains("status-error")));
        assertNull(product.get());
    }

    @Test
    void testShowsValidationErrorWithoutConnecting() {
        fill(DbType.POSTGRESQL, "localhost", "5432", "");
        FxTestSupport.run(() -> form.testConnection(null));
        assertFalse(FxTestSupport.call(() -> form.busyProperty().get()));
        assertEquals("Database is required", FxTestSupport.call(() -> ((Label) form.lookup("#formStatusLabel"))
                .getText()));
    }

    @Test
    void testConnectsToEmbeddedDatabase() throws Exception {
        ConnectionProfile profile = UiDatabase.create(dir, "ui");
        FxTestSupport.run(() -> form.setProfile(profile, true));
        AtomicReference<String> product = new AtomicReference<>();
        FxTestSupport.run(() -> form.testConnection(product::set));
        FxTestSupport.waitUntil(() -> product.get() != null);
        assertTrue(product.get().startsWith("H2"));
        assertTrue(FxTestSupport.call(() -> ((Label) form.lookup("#formStatusLabel")).getText())
                .startsWith("Connected: H2"));
    }

    @Test
    void forgottenPasswordIsNotSaved() throws Exception {
        ConnectionDialog dialog = FxTestSupport.call(() -> new ConnectionDialog(stage, context, null));
        FxTestSupport.run(dialog::show);
        DialogPane pane = dialog.getDialogPane();
        FxTestSupport.run(() -> {
            ConnectionForm dialogForm = (ConnectionForm) pane.getContent();
            @SuppressWarnings("unchecked")
            ComboBox<DbType> combo = (ComboBox<DbType>) dialogForm.lookup("#dbTypeCombo");
            combo.setValue(DbType.MYSQL);
            ((TextField) dialogForm.lookup("#nameField")).setText("secret");
            ((TextField) dialogForm.lookup("#hostField")).setText("localhost");
            ((TextField) dialogForm.lookup("#databaseField")).setText("test");
            ((TextField) dialogForm.lookup("#usernameField")).setText("fengy");
            ((TextField) dialogForm.lookup("#passwordField")).setText("p@ssw0rd");
            ((CheckBox) dialogForm.lookup("#rememberPasswordCheck")).setSelected(false);
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
        });
        assertFalse(FxTestSupport.call(dialog::isShowing));
        String json = Files.readString(new File(dir, "connections.json").toPath());
        assertTrue(json.contains("secret"));
        assertFalse(json.contains("p@ssw0rd"), json);
        // The password is kept in memory for the session
        ConnectionProfile saved = context.getProfileRegistry().getProfiles().get(0);
        assertEquals("p@ssw0rd", saved.getPassword());
        assertFalse(context.getProfileRegistry().isPasswordRemembered(saved.getId()));

        // Remembering the password saves it
        context.getProfileRegistry().saveProfile(saved, true);
        assertTrue(Files.readString(new File(dir, "connections.json").toPath()).contains("p@ssw0rd"));
    }

    @Test
    void invalidFormIsNotSaved() {
        ConnectionDialog dialog = FxTestSupport.call(() -> new ConnectionDialog(stage, context, null));
        FxTestSupport.run(dialog::show);
        FxTestSupport.run(() -> {
            ConnectionForm dialogForm = (ConnectionForm) dialog.getDialogPane().getContent();
            @SuppressWarnings("unchecked")
            ComboBox<DbType> combo = (ComboBox<DbType>) dialogForm.lookup("#dbTypeCombo");
            combo.setValue(DbType.POSTGRESQL);
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).fire();
        });
        assertTrue(FxTestSupport.call(dialog::isShowing));
        assertTrue(context.getProfileRegistry().getProfiles().isEmpty());
        assertNotNull(FxTestSupport.call(() -> ((Label) dialog.getDialogPane().lookup("#formStatusLabel"))
                .getText()));
        FxTestSupport.run(dialog::close);
    }
}
