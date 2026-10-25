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
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import com.github.sqljam.face.model.AppSettings;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.utils.SshSettings;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: StartupTest verifies the start in the main window like DBeaver and DataGrip: the password of a data
 *               source which is not remembered is asked when it is connected, a refused password is asked again,
 *               the data sources which were connected are connected again, a window is placed on a screen which
 *               exists, and the welcome page creates a data source
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StartupTest {

    @TempDir
    File dir;

    private Stage stage;
    private AppContext context;
    private ConnectionProfile profile;
    private MainView mainView;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        profile = UiDatabase.create(dir, "Embedded H2");
        stage.setScene(new Scene(new StackPane(), 400, 300));
        stage.show();
    }

    @AfterEach
    void close() {
        FxTestSupport.closeAllDialogs();
        context.getSessionManager().closeAll();
    }

    /**
     * A new start: the main window of the saved data sources, as SqlJam shows it
     */
    private MainView restart() {
        context.getSessionManager().closeAll();
        context = FxTestSupport.createContext(dir);
        mainView = FxTestSupport.call(() -> {
            MainView view = new MainView(context);
            stage.setScene(new Scene(view, 1200, 800));
            view.applyCss();
            view.layout();
            return view;
        });
        return mainView;
    }

    private ConnectionTree tree() {
        return (ConnectionTree) mainView.lookup("#connectionTree");
    }

    private TreeItem<DbNode> connection() {
        return FxTestSupport.call(() -> tree().getConnectionItems().stream().filter(item -> "Embedded H2".equals(
                item.getValue().getLabel())).findFirst().orElseThrow());
    }

    private static boolean connected(TreeItem<DbNode> item) {
        return FxTestSupport.call(() -> !item.getChildren().isEmpty() && item.getChildren().stream().noneMatch(
                child -> child.getValue().getKind() == DbNode.Kind.LOADING || child.getValue().getKind()
                        == DbNode.Kind.MESSAGE));
    }

    private static DialogPane credentials() {
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> pane.lookup(
                "#credentialsSecretField") != null));
        return FxTestSupport.getDialogPanes().stream().filter(pane -> pane.lookup("#credentialsSecretField")
                != null).findFirst().orElseThrow();
    }

    private static void enter(DialogPane pane, String password, boolean save) {
        FxTestSupport.run(() -> {
            ((PasswordField) pane.lookup("#credentialsSecretField")).setText(password);
            ((CheckBox) pane.lookup("#credentialsSaveCheck")).setSelected(save);
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
        });
    }

    @Test
    void asksPasswordWhichIsNotRemembered() throws Exception {
        context.getProfileRegistry().saveProfile(profile, false);
        restart();
        TreeItem<DbNode> item = connection();
        // Cancelled, nothing is connected
        FxTestSupport.run(() -> item.setExpanded(true));
        DialogPane pane = credentials();
        assertEquals("sa", FxTestSupport.call(() -> ((TextField) pane.lookup("#credentialsUsernameField"))
                .getText()));
        assertTrue(pane.getHeaderText().contains("Embedded H2"));
        assertNull(pane.lookup("#credentialsMessageLabel"));
        FxTestSupport.run(() -> ((Button) pane.lookupButton(ButtonType.CANCEL)).fire());
        assertFalse(FxTestSupport.call(item::isExpanded));
        assertFalse(context.getSettings().getConnectedSources().contains(profile.getId()));

        // Entered and saved, it is connected and remembered
        FxTestSupport.run(() -> item.setExpanded(true));
        enter(credentials(), "", true);
        FxTestSupport.waitUntil(() -> connected(item));
        assertTrue(context.getProfileRegistry().isPasswordRemembered(profile.getId()));
        assertTrue(context.getSettings().getConnectedSources().contains(profile.getId()));
    }

    @Test
    void asksRefusedPasswordAgain() throws Exception {
        profile.setPassword("wrong");
        context.getProfileRegistry().saveProfile(profile, true);
        restart();
        TreeItem<DbNode> item = connection();
        FxTestSupport.run(() -> item.setExpanded(true));
        // The error of the database is shown, the password is kept for the session only
        DialogPane pane = credentials();
        assertNotNull(pane.lookup("#credentialsMessageLabel"));
        assertFalse(FxTestSupport.call(() -> ((Label) pane.lookup("#credentialsMessageLabel")).getText()).isEmpty());
        enter(pane, "", false);
        FxTestSupport.waitUntil(() -> connected(item));
        assertFalse(context.getProfileRegistry().isPasswordRemembered(profile.getId()));
        assertEquals("", context.getProfileRegistry().findProfile(profile.getId()).orElseThrow().getPassword());
        // Cancelled after a refusal, the error is shown in the tree
        profile.setPassword("wrong again");
        context.getProfileRegistry().saveProfile(profile, true);
        restart();
        TreeItem<DbNode> again = connection();
        FxTestSupport.run(() -> again.setExpanded(true));
        DialogPane refused = credentials();
        FxTestSupport.run(() -> ((Button) refused.lookupButton(ButtonType.CANCEL)).fire());
        FxTestSupport.closeAllDialogs();
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> again.getChildren().stream().anyMatch(child -> child
                .getValue().getKind() == DbNode.Kind.MESSAGE)));
    }

    @Test
    void connectsAgainWhatWasConnected() throws Exception {
        context.getProfileRegistry().saveProfile(profile, true);
        restart();
        TreeItem<DbNode> item = connection();
        FxTestSupport.run(() -> item.setExpanded(true));
        FxTestSupport.waitUntil(() -> connected(item));
        assertEquals(List.of(profile.getId()), context.getSettings().getConnectedSources());
        context.getSettings().getConnectedSources().add("deleted meanwhile");
        context.saveSettings();

        // The next start connects it again, a data source which was deleted is forgotten
        restart();
        assertTrue(context.getSettings().getConnectedSources().contains(profile.getId()));
        FxTestSupport.run(mainView::restoreConnected);
        TreeItem<DbNode> restored = connection();
        FxTestSupport.waitUntil(() -> connected(restored));
        assertFalse(context.getSettings().getConnectedSources().contains("deleted meanwhile"));

        // Disconnected, the next start leaves it
        FxTestSupport.run(() -> tree().disconnect(restored));
        assertFalse(context.getSettings().getConnectedSources().contains(profile.getId()));
        restart();
        assertTrue(context.getSettings().getConnectedSources().isEmpty());
    }

    @Test
    void createsDataSourceFromWelcome() throws Exception {
        restart();
        Button button = FxTestSupport.call(() -> (Button) mainView.lookup("#welcomeNewDataSourceButton"));
        assertTrue(FxTestSupport.call(button::isVisible));
        FxTestSupport.fireLater(button);
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> pane.lookup(
                "#categoryCombo") != null));
    }

    /**
     * A first layout before the view has a width keeps the tree narrow otherwise, its share is set again
     */
    @Test
    void keepsWidthOfTree() {
        // A window of its own, the window of the tests has the size which other tests left
        Stage window = FxTestSupport.call(() -> {
            Stage created = new Stage();
            created.setScene(new Scene(new StackPane(), 1280, 800));
            created.show();
            return created;
        });
        try {
            StackPane root = FxTestSupport.call(() -> (StackPane) window.getScene().getRoot());
            MainView view = FxTestSupport.call(() -> {
                MainView created = new MainView(context);
                root.getChildren().setAll(created);
                // Laid out before the root gives it its width
                created.applyCss();
                created.layout();
                return created;
            });
            SplitPane split = FxTestSupport.call(() -> (SplitPane) view.getCenter());
            // The tree has its share of the width, not the narrow width of the first layout
            long deadline = System.currentTimeMillis() + 30_000;
            String state = "";
            while (System.currentTimeMillis() < deadline) {
                state = FxTestSupport.call(() -> split.getWidth() + " " + split.getDividerPositions()[0] + " " + split
                        .getItems().get(0).getLayoutBounds().getWidth() + " window " + window.getWidth() + " scene "
                        + window.getScene().getWidth());
                if (FxTestSupport.call(() -> split.getWidth() > MainView.MIN_SPLIT_WIDTH && Math.abs(split
                        .getDividerPositions()[0] - MainView.TREE_WIDTH) < 0.01 && Math.abs(split.getItems().get(0)
                        .getLayoutBounds().getWidth() / split.getWidth() - MainView.TREE_WIDTH) < 0.02)) {
                    state = null;
                    break;
                }
                WaitForAsyncUtils.sleep(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            assertNull(state, "Width, divider, tree, window and scene: " + state);
            // Dragged by the user: the tree keeps its width when the window is resized (narrower, the headless
            // screen of the tests is 1280 wide)
            FxTestSupport.run(() -> {
                Node divider = split.lookup(".split-pane-divider");
                divider.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1, false,
                        false, false, false, true, false, false, false, false, false, null));
                split.setDividerPositions(0.3);
            });
            FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> Math.abs(split.getDividerPositions()[0] - 0.3)
                    < 0.01));
            double tree = FxTestSupport.call(() -> split.getItems().get(0).getLayoutBounds().getWidth());
            FxTestSupport.run(() -> window.setWidth(1100));
            FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> split.getWidth() < 1150));
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(tree, FxTestSupport.call(() -> split.getItems().get(0).getLayoutBounds().getWidth()), 2);
        } finally {
            FxTestSupport.run(window::close);
        }
    }

    @Test
    void placesWindowOnScreens() {
        Rectangle2D laptop = new Rectangle2D(0, 25, 1440, 875);
        Rectangle2D external = new Rectangle2D(1440, 0, 2560, 1440);
        // On the screen it was closed on
        assertEquals(new Rectangle2D(1600, 100, 1280, 800), WindowPlacement.fit(1600, 100, 1280, 800, List.of(
                laptop, external), laptop));
        // The external display was unplugged: centered on the laptop
        assertEquals(new Rectangle2D(80, 62.5, 1280, 800), WindowPlacement.fit(1600, 100, 1280, 800, List.of(
                laptop), laptop));
        // Never placed before
        assertEquals(new Rectangle2D(80, 62.5, 1280, 800), WindowPlacement.fit(Double.NaN, Double.NaN, 1280, 800,
                List.of(laptop), laptop));
        // Larger than the screen: reduced and moved into it
        assertEquals(new Rectangle2D(0, 25, 1440, 875), WindowPlacement.fit(100, 30, 2400, 1300, List.of(laptop),
                laptop));
        // The title bar above the screen can not be dragged
        assertEquals(new Rectangle2D(80, 62.5, 1280, 800), WindowPlacement.fit(100, -200, 1280, 800, List.of(
                laptop), laptop));
        // Partly out at the right, the title bar is still on the screen: moved in
        assertEquals(new Rectangle2D(160, 100, 1280, 800), WindowPlacement.fit(700, 100, 1280, 800, List.of(
                laptop), laptop));
    }

    @Test
    void savesAndRestoresPlacement() {
        AppSettings settings = new AppSettings();
        FxTestSupport.run(() -> {
            stage.setX(120);
            stage.setY(90);
            stage.setWidth(900);
            stage.setHeight(600);
            WindowPlacement.save(stage, settings);
        });
        assertEquals(120, settings.getWindowX());
        assertEquals(600, settings.getWindowHeight());
        assertFalse(settings.isWindowMaximized());
        FxTestSupport.run(() -> {
            stage.setX(400);
            WindowPlacement.restore(stage, settings);
        });
        assertEquals(900, FxTestSupport.call(stage::getWidth));
    }

    @Test
    void tellsRefusedLogins() {
        assertTrue(AuthFailures.isDatabaseFailure(new SQLException("Access denied", "28000", 1045)));
        assertTrue(AuthFailures.isDatabaseFailure(new SQLException("ORA-01017", "72000", 1017)));
        assertTrue(AuthFailures.isDatabaseFailure(new SQLException("Login failed", "S0001", 18456)));
        assertTrue(AuthFailures.isDatabaseFailure(new SQLException("x", "28P01", 0)));
        assertTrue(AuthFailures.isDatabaseFailure(new RuntimeException(new SQLException(
                "FATAL: password authentication failed for user"))));
        assertTrue(AuthFailures.isDatabaseFailure(new IllegalStateException("Code: 516. AUTHENTICATION_FAILED")));
        assertFalse(AuthFailures.isDatabaseFailure(new SQLException("Connection refused", "08001", 0)));
        assertFalse(AuthFailures.isDatabaseFailure(new IOException((String) null)));

        IOException ssh = new IOException("SSH tunnel through deploy@bastion:22 failed: No more authentication "
                + "methods available");
        assertTrue(AuthFailures.isSshFailure(new RuntimeException(ssh)));
        assertFalse(AuthFailures.isDatabaseFailure(new RuntimeException("SSH tunnel through deploy@bastion:22 "
                + "failed: Authentication failed")));
        assertTrue(AuthFailures.isSshFailure(new IOException("SSH tunnel through a@b:22 failed: unable to read "
                + "the private key ~/.ssh/id")));
        assertFalse(AuthFailures.isSshFailure(new IOException("SSH tunnel through a@b:22 failed: the SSH server "
                + "does not reach db:5432")));
        assertFalse(AuthFailures.isSshFailure(new IOException("No more authentication methods available")));
    }

    /**
     * A tunnel which the SSH server refused: the password of the tunnel is asked, not the one of the database
     */
    @Test
    void asksPasswordOfRefusedTunnel() throws Exception {
        SshSettings ssh = new SshSettings();
        ssh.setEnabled(true);
        ssh.setHost("bastion");
        ssh.setUsername("deploy");
        profile.setDbType(DbType.MYSQL);
        profile.setHostname("localhost");
        profile.setPort(3306);
        profile.setSsh(ssh);
        context.getProfileRegistry().saveProfile(profile, true);
        restart();
        DbNode node = connection().getValue();
        RuntimeException refused = new RuntimeException(new IOException("SSH tunnel through deploy@bastion:22 "
                + "failed: No more authentication methods available"));
        boolean[] asked = new boolean[1];
        Platform.runLater(() -> asked[0] = tree().askAfterFailure(node, refused));
        DialogPane pane = credentials();
        assertNull(pane.lookup("#credentialsUsernameField"));
        enter(pane, "ssh-secret", false);
        FxTestSupport.waitUntil(() -> asked[0]);
        assertEquals("ssh-secret", node.getProfile().getSsh().getPassword());
        // Kept for the session, not in the file
        assertEquals("ssh-secret", context.getProfileRegistry().findProfile(profile.getId()).orElseThrow().getSsh()
                .getPassword());
        // Another error asks nothing
        assertFalse(FxTestSupport.call(() -> tree().askAfterFailure(node, new SQLException("refused", "08001",
                0))));
    }
}
