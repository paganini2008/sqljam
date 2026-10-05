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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: Branding provides the logo of SqlJam: the full logo (emblem and name) for the splash screen, the
 *               login page, the welcome page and the about dialog, the emblem for the toolbar, and window icons in
 *               several sizes
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public final class Branding {

    public static final String IMAGE_PATH = "/com/github/sqljam/face/images/";
    public static final String LOGO = "logo.png";
    public static final String LOGO_MARK = "logo-mark.png";
    public static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private static final Map<String, Image> images = new ConcurrentHashMap<>();
    private static volatile List<Image> icons;

    private Branding() {
    }

    /**
     * Returns the image in the image directory, or null if it does not exist
     */
    public static Image getImage(String name) {
        Image image = images.get(name);
        if (image == null) {
            try (InputStream in = Branding.class.getResourceAsStream(IMAGE_PATH + name)) {
                if (in == null) {
                    return null;
                }
                image = new Image(in);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("Unable to load image: {}", name, e);
                }
                return null;
            }
            images.put(name, image);
        }
        return image;
    }

    public static Image getLogo() {
        return getImage(LOGO);
    }

    public static Image getLogoMark() {
        return getImage(LOGO_MARK);
    }

    /**
     * Window icons from small to large, the platform picks the best size
     */
    public static List<Image> getIcons() {
        if (icons == null) {
            List<Image> list = new ArrayList<>();
            for (int size : ICON_SIZES) {
                Image icon = getImage("icon-" + size + ".png");
                if (icon != null) {
                    list.add(icon);
                }
            }
            icons = Collections.unmodifiableList(list);
        }
        return icons;
    }

    /**
     * The full logo scaled to the height, keeping its ratio
     */
    public static ImageView logoView(double height) {
        return imageView(getLogo(), height);
    }

    /**
     * The emblem scaled to the height, keeping its ratio
     */
    public static ImageView logoMarkView(double height) {
        return imageView(getLogoMark(), height);
    }

    private static ImageView imageView(Image image, double height) {
        ImageView view = new ImageView(image);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.getStyleClass().add("logo");
        return view;
    }

    public static void applyIcons(Stage stage) {
        if (stage != null && stage.getIcons().isEmpty()) {
            stage.getIcons().setAll(getIcons());
        }
    }

    public static void applyIcons(Dialog<?> dialog) {
        Window window = dialog.getDialogPane().getScene() != null ? dialog.getDialogPane().getScene().getWindow()
                : null;
        if (window instanceof Stage) {
            applyIcons((Stage) window);
        }
    }
}
