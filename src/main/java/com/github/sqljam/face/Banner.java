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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.config.Config;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: Banner prints banner.txt when the application starts, like the banner of Spring Boot.
 *               sqljam.banner.mode is console (default), log or off. sqljam.banner.location is a custom banner
 *               file, otherwise banner.txt in the classpath is used. Placeholders ${sqljam.version}, system
 *               properties like ${java.version} and keys of sqljam.properties are resolved.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public final class Banner {

    public static final String BANNER_FILE_NAME = "banner.txt";
    public static final String VERSION_FILE_NAME = "sqljam-version.properties";
    public static final String VERSION_KEY = "sqljam.version";
    public static final String MODE_KEY = "sqljam.banner.mode";
    public static final String LOCATION_KEY = "sqljam.banner.location";
    public static final String UNKNOWN_VERSION = "unknown";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Where the banner goes
     */
    public enum Mode {

        CONSOLE, LOG, OFF;

        static Mode of(String value) {
            try {
                return StringUtils.isBlank(value) ? CONSOLE : valueOf(value.trim().toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                return CONSOLE;
            }
        }
    }

    private Banner() {
    }

    /**
     * Prints the banner by the mode of the configuration
     */
    public static void print(Config config, PrintStream out) {
        Mode mode = Mode.of(config.getString(MODE_KEY, null));
        if (mode == Mode.OFF) {
            return;
        }
        String text = getBanner(config);
        if (StringUtils.isBlank(text)) {
            return;
        }
        if (mode == Mode.LOG) {
            if (log.isInfoEnabled()) {
                log.info("\n{}", text);
            }
        } else {
            out.println(text);
            out.flush();
        }
    }

    /**
     * Returns the resolved banner text, or null when no banner is found
     */
    public static String getBanner(Config config) {
        String template = readTemplate(config.getString(LOCATION_KEY, null));
        return template != null ? resolve(StringUtils.stripEnd(template, null), config) : null;
    }

    static String readTemplate(String location) {
        try {
            if (StringUtils.isNotBlank(location)) {
                File file = new File(location.trim());
                if (file.isFile()) {
                    return Files.readString(file.toPath(), StandardCharsets.UTF_8);
                }
                if (log.isWarnEnabled()) {
                    log.warn("Banner not found: {}, the default banner is used", file.getAbsolutePath());
                }
            }
            try (InputStream in = Banner.class.getResourceAsStream("/" + BANNER_FILE_NAME)) {
                return in != null ? IOUtils.toString(in, StandardCharsets.UTF_8) : null;
            }
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("Unable to read banner: {}", e.getMessage());
            }
            return null;
        }
    }

    static String resolve(String template, Config config) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value;
            if (VERSION_KEY.equals(name)) {
                value = getVersion();
            } else {
                value = System.getProperty(name);
                if (value == null) {
                    value = config.getString(name, matcher.group());
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Version of SqlJam, written into sqljam-version.properties by Maven resource filtering
     */
    public static String getVersion() {
        try (InputStream in = Banner.class.getResourceAsStream("/" + VERSION_FILE_NAME)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                String version = properties.getProperty(VERSION_KEY);
                if (StringUtils.isNotBlank(version) && !version.startsWith("${")) {
                    return version.trim();
                }
            }
        } catch (IOException e) {
            // Unknown version
        }
        String version = Banner.class.getPackage().getImplementationVersion();
        return StringUtils.isNotBlank(version) ? version : UNKNOWN_VERSION;
    }
}
