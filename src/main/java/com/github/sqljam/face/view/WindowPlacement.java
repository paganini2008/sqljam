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

import java.util.List;
import java.util.stream.Collectors;

import com.github.sqljam.face.model.AppSettings;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * @Description: WindowPlacement keeps the size, the position and the maximized state of the main window between
 *               starts. A saved position is used only when the title bar of the window is on a screen, e.g. not
 *               after the external display it was on is unplugged, otherwise the window is centered on the main
 *               screen. A size larger than the screen is reduced to it.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class WindowPlacement {

    /**
     * Part of the title bar which must be on a screen, so that the window can be dragged
     */
    static final double TITLE_HEIGHT = 30;
    static final double TITLE_WIDTH = 120;

    private WindowPlacement() {
    }

    /**
     * Places the stage as the settings saved it
     */
    public static void restore(Stage stage, AppSettings settings) {
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).collect(Collectors
                .toList());
        Rectangle2D bounds = fit(settings.getWindowX(), settings.getWindowY(), settings.getWindowWidth(), settings
                .getWindowHeight(), screens, Screen.getPrimary().getVisualBounds());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setMaximized(settings.isWindowMaximized());
    }

    /**
     * Saves the placement of the stage into the settings, the size and position before it was maximized are kept
     */
    public static void save(Stage stage, AppSettings settings) {
        settings.setWindowMaximized(stage.isMaximized());
        if (!stage.isMaximized() && !stage.isIconified()) {
            settings.setWindowX(stage.getX());
            settings.setWindowY(stage.getY());
            settings.setWindowWidth(stage.getWidth());
            settings.setWindowHeight(stage.getHeight());
        }
    }

    /**
     * Bounds of the window: the saved ones when its title bar is on one of the screens, centered on the primary
     * screen otherwise, never larger than the screen it is on
     */
    static Rectangle2D fit(double x, double y, double width, double height, List<Rectangle2D> screens,
                           Rectangle2D primary) {
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            Rectangle2D title = new Rectangle2D(x + Math.max(0, (width - TITLE_WIDTH) / 2), y, Math.min(width,
                    TITLE_WIDTH), TITLE_HEIGHT);
            for (Rectangle2D screen : screens) {
                if (screen.contains(title)) {
                    double fittedWidth = Math.min(width, screen.getWidth());
                    double fittedHeight = Math.min(height, screen.getHeight());
                    // The window is moved into the screen when it is reduced
                    double fittedX = Math.max(screen.getMinX(), Math.min(x, screen.getMaxX() - fittedWidth));
                    double fittedY = Math.max(screen.getMinY(), Math.min(y, screen.getMaxY() - fittedHeight));
                    return new Rectangle2D(fittedX, fittedY, fittedWidth, fittedHeight);
                }
            }
        }
        double fittedWidth = Math.min(width, primary.getWidth());
        double fittedHeight = Math.min(height, primary.getHeight());
        return new Rectangle2D(primary.getMinX() + (primary.getWidth() - fittedWidth) / 2, primary.getMinY()
                + (primary.getHeight() - fittedHeight) / 2, fittedWidth, fittedHeight);
    }
}
