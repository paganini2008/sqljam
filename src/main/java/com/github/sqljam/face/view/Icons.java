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

import org.kordamp.ikonli.javafx.FontIcon;

/**
 * @Description: Icons of Feather icon pack
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class Icons {

    public static final String CONNECTION = "fth-server";
    public static final String CATALOG = "fth-database";
    public static final String SCHEMA = "fth-folder";
    public static final String TABLE = "fth-grid";
    public static final String PARTITION = "fth-layers";
    public static final String LOADING = "fth-loader";
    public static final String ADD = "fth-plus";
    public static final String EDIT = "fth-edit";
    public static final String DELETE = "fth-trash-2";
    public static final String REFRESH = "fth-refresh-cw";
    public static final String EXPORT = "fth-download";
    public static final String IMPORT = "fth-upload";
    public static final String COPY = "fth-copy";
    public static final String CANCEL = "fth-x";
    public static final String START = "fth-play";
    public static final String FOLDER = "fth-folder";
    public static final String SETTINGS = "fth-settings";
    public static final String THEME = "fth-sun";
    public static final String INFO = "fth-info";
    public static final String ALERT = "fth-alert-circle";
    public static final String FIRST = "fth-chevrons-left";
    public static final String PREVIOUS = "fth-chevron-left";
    public static final String NEXT = "fth-chevron-right";
    public static final String LAST = "fth-chevrons-right";
    public static final String CONNECT = "fth-link";
    public static final String OPEN = "fth-external-link";
    public static final String GITHUB = "fth-github";
    public static final String DISCONNECT = "fth-power";
    public static final String EXIT = "fth-log-out";

    private Icons() {
    }

    public static FontIcon of(String literal) {
        return new FontIcon(literal);
    }
}
