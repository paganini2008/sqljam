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

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.application.Application;
import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;

/**
 * @Description: ThemeManager switches AtlantaFX themes of the application
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class ThemeManager {

    public static final String DEFAULT_THEME = "Primer Dark";
    /**
     * Text of dark themes is never gray: white, and near white for the secondary text and the prompts. Text of
     * light themes is near black with clear contrast. Selected rows of tables, lists and trees are clearly blue
     * in both, the themes select with a gray close to the background (user 2026-10-25).
     */
    static final Map<String, String> DARK_COLORS = colors("#ffffff", "#f0f6fc", "#e6edf3",
            "rgba(56, 139, 253, 0.45)", "#ffffff");
    static final Map<String, String> LIGHT_COLORS = colors("#1f2328", "#32383f", "#424a53",
            "rgba(9, 105, 218, 0.22)", "#1f2328");

    private static Map<String, String> colors(String text, String muted, String subtle, String selection,
                                              String selectedText) {
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("-color-fg-default", text);
        colors.put("-color-fg-muted", muted);
        colors.put("-color-fg-subtle", subtle);
        colors.put("-color-cell-bg-selected", selection);
        colors.put("-color-cell-bg-selected-focused", selection);
        colors.put("-color-cell-fg-selected", selectedText);
        colors.put("-color-cell-fg-selected-focused", selectedText);
        return colors;
    }

    private static final Map<String, Supplier<Theme>> THEMES = new LinkedHashMap<>();

    static {
        THEMES.put("Primer Light", PrimerLight::new);
        THEMES.put("Primer Dark", PrimerDark::new);
        THEMES.put("Nord Light", NordLight::new);
        THEMES.put("Nord Dark", NordDark::new);
        THEMES.put("Cupertino Light", CupertinoLight::new);
        THEMES.put("Cupertino Dark", CupertinoDark::new);
        THEMES.put("Dracula", Dracula::new);
    }

    private ThemeManager() {
    }

    public static List<String> getThemeNames() {
        return new ArrayList<>(THEMES.keySet());
    }

    /**
     * Applies the theme by name, unknown names fall back to the default dark theme
     *
     * @return the applied theme name
     */
    public static String apply(String themeName) {
        String name = THEMES.containsKey(themeName) ? themeName : DEFAULT_THEME;
        Theme theme = THEMES.get(name).get();
        Application.setUserAgentStylesheet(getStylesheet(theme));
        return name;
    }

    /**
     * Stylesheet of the theme with the colors of the text and of selected rows of SqlJam, dialogs included: the
     * colors are set on the stylesheet of the theme, after it.
     */
    static String getStylesheet(Theme theme) {
        URL url = Theme.class.getResource(theme.getUserAgentStylesheet());
        String base = url != null ? url.toExternalForm() : theme.getUserAgentStylesheet();
        // The themes set the colors of selected cells again on the controls with cells, they are set there too
        StringBuilder css = new StringBuilder("@import url(\"").append(base).append("\");\n.root, .list-view,"
                + " .table-view, .tree-view, .tree-table-view {\n");
        (theme.isDarkMode() ? DARK_COLORS : LIGHT_COLORS).forEach((name, value) -> css.append("    ").append(name)
                .append(": ").append(value).append(";\n"));
        css.append("}\n");
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.toString().getBytes(
                StandardCharsets.UTF_8));
    }
}
