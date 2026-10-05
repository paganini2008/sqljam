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

import com.github.sqljam.face.Banner;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * @Description: SplashView is the content of the startup splash screen: the logo, the slogan, a progress bar with
 *               the loading status and the version. It has its own stylesheet since it is shown before the theme is
 *               applied.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class SplashView extends StackPane {

    public static final String STYLESHEET = "/com/github/sqljam/face/splash.css";
    public static final double WIDTH = 560;
    public static final double HEIGHT = 400;

    private final ProgressBar progressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final Label statusLabel = new Label(Messages.get("splash.starting"));

    public SplashView() {
        getStyleClass().add("splash");
        getStylesheets().add(SplashView.class.getResource(STYLESHEET).toExternalForm());
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);

        ImageView logo = Branding.logoView(210);
        logo.setId("splashLogo");
        Label slogan = new Label(Messages.get("splash.slogan"));
        slogan.getStyleClass().add("splash-slogan");
        VBox center = new VBox(14, logo, slogan);
        center.setAlignment(Pos.CENTER);

        progressBar.setId("splashProgress");
        progressBar.getStyleClass().add("splash-progress");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setId("splashStatus");
        statusLabel.getStyleClass().add("splash-status");
        Label versionLabel = new Label("v" + Banner.getVersion());
        versionLabel.setId("splashVersion");
        versionLabel.getStyleClass().add("splash-version");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusRow = new HBox(statusLabel, spacer, versionLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        VBox bottom = new VBox(8, progressBar, statusRow);

        BorderPane card = new BorderPane(center);
        card.setBottom(bottom);
        card.getStyleClass().add("splash-card");
        getChildren().add(card);
    }

    /**
     * Square card without shadow, for windows which can not be transparent
     */
    public void setOpaque() {
        if (!getStyleClass().contains("opaque")) {
            getStyleClass().add("opaque");
        }
    }

    /**
     * Progress from 0 to 1, a negative value shows an indeterminate progress
     */
    public void setProgress(double progress) {
        progressBar.setProgress(progress < 0 ? ProgressBar.INDETERMINATE_PROGRESS : Math.min(1, progress));
    }

    public double getProgress() {
        return progressBar.getProgress();
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public String getStatus() {
        return statusLabel.getText();
    }
}
