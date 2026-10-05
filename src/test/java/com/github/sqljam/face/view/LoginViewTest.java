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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
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
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: LoginViewTest verifies the data source login page
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class LoginViewTest {

    @TempDir
    File dir;

    private AppContext context;
    private StackPane root;
    private final AtomicReference<ConnectionProfile> loggedIn = new AtomicReference<>();
    private final AtomicBoolean called = new AtomicBoolean();
    private ConnectionProfile embedded;

    @Start
    void start(Stage stage) {
        context = FxTestSupport.createContext(dir);
        root = new StackPane();
        stage.setScene(new Scene(root, 900, 650));
        stage.show();
    }

    private LoginView show() {
        return FxTestSupport.call(() -> {
            LoginView view = new LoginView(context, profile -> {
                called.set(true);
                loggedIn.set(profile);
            });
            root.getChildren().setAll(view);
            // Content of the scroll pane is looked up after the skin is created
            view.applyCss();
            view.layout();
            return view;
        });
    }

    private void saveProfiles() throws Exception {
        embedded = UiDatabase.create(dir, "Embedded H2");
        context.getProfileRegistry().saveProfile(embedded, true);
        ConnectionProfile unreachable = new ConnectionProfile();
        unreachable.setName("Unreachable MySQL");
        unreachable.setDbType(DbType.MYSQL);
        unreachable.setHostname("127.0.0.1");
        unreachable.setPort(1);
        unreachable.setDatabase("test");
        context.getProfileRegistry().saveProfile(unreachable, true);
        ConnectionProfile sqlite = new ConnectionProfile();
        sqlite.setName("Local file");
        sqlite.setDbType(DbType.SQLITE);
        sqlite.setDatabase(new File(dir, "local.sqlite").getAbsolutePath());
        context.getProfileRegistry().saveProfile(sqlite, true);
    }

    @SuppressWarnings("unchecked")
    private static ListView<ConnectionProfile> list(LoginView view) {
        return (ListView<ConnectionProfile>) view.lookup("#profileList");
    }

    private static void select(LoginView view, String name) {
        FxTestSupport.run(() -> list(view).getItems().stream().filter(profile -> profile.getName().equals(name))
                .findFirst().ifPresent(list(view).getSelectionModel()::select));
    }

    @Test
    void showsEmptyList() {
        LoginView view = show();
        assertEquals(0, FxTestSupport.call(() -> list(view).getItems().size()));
        assertEquals("No data sources", FxTestSupport.call(() -> ((Label) list(view).getPlaceholder()).getText()));
        assertTrue(FxTestSupport.call(() -> view.lookup("#deleteButton").isDisabled()));
    }

    @Test
    void listsAndSelectsFirstDataSource() throws Exception {
        saveProfiles();
        LoginView view = show();
        assertEquals(3, FxTestSupport.call(() -> list(view).getItems().size()));
        // Sorted by name, the first one fills the form
        assertEquals("Embedded H2", FxTestSupport.call(() -> list(view).getSelectionModel().getSelectedItem()
                .getName()));
        assertEquals("Embedded H2", FxTestSupport.call(() -> ((TextField) view.lookup("#nameField")).getText()));
        select(view, "Unreachable MySQL");
        assertEquals("1", FxTestSupport.call(() -> ((TextField) view.lookup("#portField")).getText()));
    }

    @Test
    void filtersByNameAndType(FxRobot robot) throws Exception {
        saveProfiles();
        LoginView view = show();
        robot.clickOn(view.lookup("#searchField")).write("mysql");
        assertEquals(1, FxTestSupport.call(() -> list(view).getItems().size()));
        FxTestSupport.run(() -> ((TextField) view.lookup("#searchField")).setText("SQLite"));
        // Matched by database type
        assertEquals("Local file", FxTestSupport.call(() -> list(view).getItems().get(0).getName()));
        FxTestSupport.run(() -> ((TextField) view.lookup("#searchField")).setText("nothing"));
        assertEquals(0, FxTestSupport.call(() -> list(view).getItems().size()));
        FxTestSupport.run(() -> ((TextField) view.lookup("#searchField")).setText(""));
        assertEquals(3, FxTestSupport.call(() -> list(view).getItems().size()));
    }

    @Test
    void newClearsForm() throws Exception {
        saveProfiles();
        LoginView view = show();
        FxTestSupport.run(() -> ((Button) view.lookup("#newButton")).fire());
        assertEquals("", FxTestSupport.call(() -> ((TextField) view.lookup("#nameField")).getText()));
        assertNull(FxTestSupport.call(() -> list(view).getSelectionModel().getSelectedItem()));
    }

    @Test
    void deletesDataSource() throws Exception {
        saveProfiles();
        LoginView view = show();
        select(view, "Local file");
        FxTestSupport.fireLater((Button) view.lookup("#deleteButton"));
        FxTestSupport.closeDialog(ButtonType.OK);
        FxTestSupport.waitUntil(() -> list(view).getItems().size() == 2);
        assertFalse(Files.readString(new File(dir, "connections.json").toPath()).contains("Local file"));
    }

    @Test
    void cancelsDeletingDataSource() throws Exception {
        saveProfiles();
        LoginView view = show();
        select(view, "Local file");
        FxTestSupport.fireLater((Button) view.lookup("#deleteButton"));
        FxTestSupport.closeDialog(ButtonType.CANCEL);
        assertEquals(3, FxTestSupport.call(() -> list(view).getItems().size()));
    }

    @Test
    void skipsLogin() {
        LoginView view = show();
        FxTestSupport.run(() -> ((Button) view.lookup("#skipButton")).fire());
        assertTrue(called.get());
        assertNull(loggedIn.get());
    }

    @Test
    void enterKeyLogsIn(FxRobot robot) throws Exception {
        saveProfiles();
        LoginView view = show();
        select(view, "Embedded H2");
        robot.clickOn(view.lookup("#usernameField")).type(KeyCode.ENTER);
        FxTestSupport.waitUntil(called::get);
        assertEquals(embedded.getId(), loggedIn.get().getId());
    }

    @Test
    void failedLoginStaysOnPage() throws Exception {
        saveProfiles();
        LoginView view = show();
        select(view, "Unreachable MySQL");
        FxTestSupport.run(() -> ((Button) view.lookup("#loginButton")).fire());
        FxTestSupport.waitUntil(() -> !view.lookup("#loginButton").isDisabled());
        assertFalse(called.get());
        assertTrue(FxTestSupport.call(() -> ((Label) view.lookup("#formStatusLabel")).getText())
                .startsWith("Connection failed:"));
        assertTrue(FxTestSupport.call(() -> root.getChildren().contains(view)));
    }

    @Test
    void invalidFormIsNotLoggedIn() {
        LoginView view = show();
        FxTestSupport.run(() -> {
            ((TextField) view.lookup("#hostField")).setText("");
            ((Button) view.lookup("#loginButton")).fire();
        });
        assertFalse(called.get());
        assertEquals("Host is required", FxTestSupport.call(() -> ((Label) view.lookup("#formStatusLabel"))
                .getText()));
    }

    @Test
    void loginSavesNewDataSource() throws Exception {
        LoginView view = show();
        ConnectionProfile profile = UiDatabase.create(dir, "New H2");
        FxTestSupport.run(() -> {
            ConnectionForm form = (ConnectionForm) view.lookup(".connection-form");
            form.setProfile(profile, false);
            ((Button) view.lookup("#loginButton")).fire();
        });
        FxTestSupport.waitUntil(called::get);
        assertEquals("New H2", loggedIn.get().getName());
        assertEquals(1, context.getProfileRegistry().getProfiles().size());
        // Password is not remembered
        assertFalse(context.getProfileRegistry().isPasswordRemembered(profile.getId()));
    }
}
