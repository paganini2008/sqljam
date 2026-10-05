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

import com.github.sqljam.config.Config;
import com.github.sqljam.face.view.Branding;
import com.github.sqljam.face.view.SplashView;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.application.Preloader;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: SplashPreloader shows the splash screen with the logo while the application is loading. The
 *               application reports its loading steps by {@link Status} and asks to close the splash screen by
 *               {@link Ready}, which shows the main window when the splash screen is closed. The splash screen stays
 *               at least sqljam.splash.min-duration milliseconds so that it does not flash.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class SplashPreloader extends Preloader {

    public static final String ENABLED_KEY = "sqljam.splash.enabled";
    public static final String MIN_DURATION_KEY = "sqljam.splash.min-duration";
    public static final long DEFAULT_MIN_DURATION = 1200;
    static final Duration FADE_DURATION = Duration.millis(250);

    private static volatile boolean active;

    private Stage stage;
    private SplashView splashView;
    private long shownAt;
    private long minDuration = DEFAULT_MIN_DURATION;
    private boolean closing;

    /**
     * Whether the splash screen is showing, the application then shows its window by {@link Ready}
     */
    public static boolean isActive() {
        return active;
    }

    static void setActive(boolean value) {
        active = value;
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        minDuration = Math.max(0, Config.getInstance().getLong(MIN_DURATION_KEY, DEFAULT_MIN_DURATION));
        splashView = new SplashView();
        Scene scene = new Scene(splashView, SplashView.WIDTH, SplashView.HEIGHT);
        // Some Linux desktops have no transparent windows, a square card is shown there instead of rounded corners
        if (Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW)) {
            scene.setFill(Color.TRANSPARENT);
            stage.initStyle(StageStyle.TRANSPARENT);
        } else {
            splashView.setOpaque();
            stage.initStyle(StageStyle.UNDECORATED);
        }
        stage.setScene(scene);
        stage.setTitle("SqlJam");
        Branding.applyIcons(stage);
        stage.centerOnScreen();
        stage.show();
        shownAt = System.currentTimeMillis();
        active = true;
    }

    public SplashView getSplashView() {
        return splashView;
    }

    public void setMinDuration(long minDuration) {
        this.minDuration = minDuration;
    }

    @Override
    public void handleProgressNotification(ProgressNotification notification) {
        // Progress of loading the application jar, the loading steps are reported by Status
        if (splashView != null && notification.getProgress() < 1) {
            splashView.setProgress(notification.getProgress() * 0.1);
        }
    }

    @Override
    public void handleApplicationNotification(PreloaderNotification notification) {
        if (notification instanceof Status) {
            Status status = (Status) notification;
            splashView.setStatus(status.getMessage());
            splashView.setProgress(status.getProgress());
        } else if (notification instanceof Ready) {
            close(((Ready) notification).getOnClosed());
        }
    }

    @Override
    public boolean handleErrorNotification(ErrorNotification notification) {
        if (log.isErrorEnabled()) {
            log.error("Unable to start SqlJam: {}", notification.getDetails(), notification.getCause());
        }
        close(null);
        return false;
    }

    /**
     * Closes the splash screen after the minimum duration with a fade out, then runs the callback
     */
    void close(Runnable onClosed) {
        if (closing || stage == null) {
            return;
        }
        closing = true;
        splashView.setProgress(1);
        long remaining = Math.max(0, minDuration - (System.currentTimeMillis() - shownAt));
        FadeTransition fade = new FadeTransition(FADE_DURATION, splashView);
        fade.setToValue(0);
        SequentialTransition transition = new SequentialTransition(new PauseTransition(Duration.millis(remaining)),
                fade);
        transition.setOnFinished(event -> {
            if (onClosed != null) {
                onClosed.run();
            }
            stage.hide();
            active = false;
        });
        transition.play();
    }

    /**
     * A loading step of the application
     */
    public static class Status implements PreloaderNotification {

        private final String message;
        private final double progress;

        public Status(String message, double progress) {
            this.message = message;
            this.progress = progress;
        }

        public String getMessage() {
            return message;
        }

        public double getProgress() {
            return progress;
        }
    }

    /**
     * The application is ready, the callback shows its window when the splash screen is closed
     */
    public static class Ready implements PreloaderNotification {

        private final Runnable onClosed;

        public Ready(Runnable onClosed) {
            this.onClosed = onClosed;
        }

        public Runnable getOnClosed() {
            return onClosed;
        }
    }
}
