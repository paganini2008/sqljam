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
package com.github.sqljam.face;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.view.FxTestSupport;
import com.github.sqljam.face.view.SplashView;
import javafx.application.Preloader;
import javafx.scene.control.Label;
import javafx.stage.Stage;

/**
 * @Description: SplashPreloaderTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class SplashPreloaderTest {

    @TempDir
    File dir;

    @Start
    void start(Stage stage) {
        FxTestSupport.createContext(dir);
    }

    @AfterEach
    void reset() {
        SplashPreloader.setActive(false);
    }

    private SplashPreloader show(AtomicReference<Stage> stage) {
        return FxTestSupport.call(() -> {
            SplashPreloader preloader = new SplashPreloader();
            Stage splashStage = new Stage();
            stage.set(splashStage);
            preloader.start(splashStage);
            preloader.setMinDuration(0);
            return preloader;
        });
    }

    @Test
    void showsProgressAndClosesWhenReady() {
        AtomicReference<Stage> stage = new AtomicReference<>();
        SplashPreloader preloader = show(stage);
        assertTrue(SplashPreloader.isActive());
        assertTrue(FxTestSupport.call(() -> stage.get().isShowing()));
        SplashView view = preloader.getSplashView();
        assertNotNull(FxTestSupport.call(() -> view.lookup("#splashLogo")));
        assertEquals("v" + Banner.getVersion(),
                FxTestSupport.call(() -> ((Label) view.lookup("#splashVersion")).getText()));

        FxTestSupport.run(() -> {
            preloader.handleProgressNotification(new Preloader.ProgressNotification(0.5));
            assertEquals(0.05, view.getProgress(), 0.001);
            // Complete progress of the jar is ignored, the steps follow
            preloader.handleProgressNotification(new Preloader.ProgressNotification(1));
            assertEquals(0.05, view.getProgress(), 0.001);
            preloader.handleApplicationNotification(new SplashPreloader.Status("Loading MySQL driver...", 0.4));
            preloader.handleStateChangeNotification(
                    new Preloader.StateChangeNotification(Preloader.StateChangeNotification.Type.BEFORE_START));
        });
        assertEquals("Loading MySQL driver...", FxTestSupport.call(view::getStatus));
        assertEquals(0.4, FxTestSupport.call(view::getProgress), 0.001);

        AtomicBoolean shown = new AtomicBoolean();
        FxTestSupport.run(() -> {
            preloader.handleApplicationNotification(new SplashPreloader.Ready(() -> shown.set(true)));
            // A second request is ignored
            preloader.handleApplicationNotification(new SplashPreloader.Ready(() -> shown.set(false)));
        });
        FxTestSupport.waitUntil(() -> !stage.get().isShowing());
        assertTrue(shown.get(), "Main window is shown after the splash screen");
        assertFalse(SplashPreloader.isActive());
        assertEquals(1, FxTestSupport.call(view::getProgress), 0.001);
    }

    @Test
    void closesOnError() {
        AtomicReference<Stage> stage = new AtomicReference<>();
        SplashPreloader preloader = show(stage);
        assertFalse(FxTestSupport.call(() -> preloader.handleErrorNotification(
                new Preloader.ErrorNotification("location", "details", new IllegalStateException("boom")))));
        FxTestSupport.waitUntil(() -> !stage.get().isShowing());
        assertFalse(SplashPreloader.isActive());
    }

    @Test
    void indeterminateAndOpaque() {
        SplashView view = FxTestSupport.call(SplashView::new);
        FxTestSupport.run(() -> {
            view.setProgress(-1);
            view.setOpaque();
            view.setOpaque();
        });
        assertTrue(FxTestSupport.call(view::getProgress) < 0);
        assertEquals(1, FxTestSupport.call(() -> view.getStyleClass().stream()
                .filter("opaque"::equals).count()).intValue());
    }
}
