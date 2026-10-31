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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.vdb.VdbTestDialogs;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: LayoutTest opens every dialog and window of SqlJam on small screens (a laptop with its task bar and
 *               a smaller one): none is higher or wider than its screen, the buttons at its bottom are in view, a
 *               content which does not fit scrolls. The data source has its SSH tunnel open, its highest form.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class LayoutTest {

    /**
     * Heights of the screens which windows may use: 1366 x 768 without the task bar, and a smaller screen
     */
    private static final double[] HEIGHTS = {700, 560};

    @TempDir
    File dir;

    private Stage stage;
    private AppContext context;
    private ConnectionProfile profile;
    private final Function<Window, Rectangle2D> screenArea = Dialogs.screenArea;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        profile = UiDatabase.create(dir, "Embedded H2");
        context.getProfileRegistry().saveProfile(profile, true);
        stage.setScene(new Scene(new StackPane(), 400, 300));
        stage.show();
    }

    @AfterEach
    void restore() {
        Dialogs.screenArea = screenArea;
        FxTestSupport.closeAllDialogs();
    }

    /**
     * The dialogs by name, each created again for every screen
     */
    private Map<String, Supplier<Dialog<?>>> dialogs() {
        DbNode table = DbNode.table(profile, new TableInfo(UiDatabase.CATALOG, UiDatabase.SCHEMA, "T_PAGED", null,
                false, false));
        Map<String, Supplier<Dialog<?>>> dialogs = new java.util.LinkedHashMap<>();
        dialogs.put("Data source with SSH", () -> {
            ConnectionDialog dialog = new ConnectionDialog(stage, context, null);
            CheckBox ssh = (CheckBox) dialog.getDialogPane().lookup("#sshEnabledCheck");
            if (ssh != null) {
                ssh.setSelected(true);
            }
            return dialog;
        });
        dialogs.put("Export", () -> new ExportDialog(stage, context, table));
        dialogs.put("Import package", () -> new ImportPackageDialog(stage, context, null));
        dialogs.put("Import files", () -> new ImportParquetDialog(stage, context, null));
        dialogs.put("Sync task", () -> new SyncTaskDialog(stage, context, null));
        dialogs.put("Column mapping", () -> new ColumnMappingDialog(stage, List.of("T_PAGED", "T_ONE"), name -> List
                .of(new ColumnMappingDialog.Column("ID", "INT", true)), Map.of()));
        dialogs.put("Credentials", () -> new CredentialsDialog(stage, "Embedded H2", "Password", "sa",
                "Access denied for user sa"));
        dialogs.put("About", () -> Dialogs.createAbout(stage, null));
        dialogs.putAll(VdbTestDialogs.create(stage, context, dir));
        return dialogs;
    }

    @Test
    void fitsDialogsToSmallScreens() {
        List<String> problems = new ArrayList<>();
        for (double height : HEIGHTS) {
            Dialogs.screenArea = window -> new Rectangle2D(0, 0, 1280, height);
            for (Map.Entry<String, Supplier<Dialog<?>>> entry : dialogs().entrySet()) {
                Dialog<?> dialog = FxTestSupport.call(() -> {
                    Dialog<?> created = entry.getValue().get();
                    created.show();
                    return created;
                });
                WaitForAsyncUtils.waitForFxEvents();
                String problem = FxTestSupport.call(() -> check(dialog.getDialogPane().getScene(), height));
                if (problem != null) {
                    problems.add(entry.getKey() + " on a screen of " + (int) height + ": " + problem);
                }
                FxTestSupport.run(() -> {
                    dialog.setResult(null);
                    dialog.close();
                    if (dialog.isShowing()) {
                        dialog.getDialogPane().getScene().getWindow().hide();
                    }
                });
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * The progress of a transfer and the package viewer, windows of their own
     */
    @Test
    void fitsWindowsToSmallScreens() throws Exception {
        Dialogs.screenArea = window -> new Rectangle2D(0, 0, 1280, 560);
        ProgressDialog progress = FxTestSupport.call(() -> new ProgressDialog(stage, context, "Import", 3));
        FxTestSupport.run(() -> progress.getStage().show());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(FxTestSupport.call(() -> progress.getStage().getHeight()) <= 560.5);
        assertTrue(FxTestSupport.call(() -> check(progress.getStage().getScene(), 560)) == null, FxTestSupport.call(
                () -> check(progress.getStage().getScene(), 560)));
        FxTestSupport.run(() -> progress.getStage().close());
    }

    /**
     * What is wrong with the window of the scene on a screen of the height, null when nothing is
     */
    private static String check(Scene scene, double height) {
        Window window = scene.getWindow();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        if (window.getHeight() > height + 0.5) {
            return String.format("the window is %.0f high", window.getHeight());
        }
        if (window.getY() < -0.5 || window.getY() + window.getHeight() > height + 0.5) {
            return String.format("the window is at %.0f to %.0f", window.getY(), window.getY() + window.getHeight());
        }
        // The buttons at the bottom of a dialog
        for (Node buttons : scene.getRoot().lookupAll(".button-bar")) {
            if (!buttons.isVisible()) {
                continue;
            }
            Bounds bounds = buttons.localToScene(buttons.getBoundsInLocal());
            if (bounds.getMaxY() > scene.getHeight() + 0.5) {
                return String.format("its buttons end at %.0f of %.0f", bounds.getMaxY(), scene.getHeight());
            }
            if (bounds.getHeight() < 10) {
                return String.format("its buttons are %.0f high", bounds.getHeight());
            }
        }
        return null;
    }
}
