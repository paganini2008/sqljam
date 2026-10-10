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

import java.util.ArrayList;
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
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
        return name;
    }
}
