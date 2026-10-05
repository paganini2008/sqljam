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
package com.github.sqljam.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description: Config holds all configurations in sqljam.properties. Values are looked up in 3 layers, the later
 *               wins: defaults in the classpath (development only, the packaged jar does not contain the file),
 *               the external application file next to the jar (conf/sqljam.properties or sqljam.properties,
 *               or -Dsqljam.app.config), and the user file saved by the UI (${user.home}/.sqljam/sqljam.properties
 *               or -Dsqljam.config). Placeholders like ${user.home} are resolved by system properties and other
 *               keys.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Slf4j
public class Config {

    public static final String FILE_NAME = "sqljam.properties";
    public static final String CONFIG_PROPERTY = "sqljam.config";
    public static final String APP_CONFIG_PROPERTY = "sqljam.app.config";
    public static final String CONF_DIR_NAME = "conf";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static volatile Config instance;

    private final Properties defaults = new Properties();
    private final Properties appProperties = new Properties();
    private final Properties overrides = new Properties();
    private final File appFile;
    private final File userFile;

    public Config(File userFile) {
        this(null, userFile);
    }

    public Config(File appFile, File userFile) {
        this.appFile = appFile;
        this.userFile = userFile;
        try (InputStream in = Config.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                defaults.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load default configuration", e);
        }
        load(appFile, appProperties);
        load(userFile, overrides);
    }

    private static void load(File file, Properties properties) {
        if (file == null || !file.isFile()) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            properties.load(reader);
            if (log.isInfoEnabled()) {
                log.info("Configuration loaded: {}", file.getAbsolutePath());
            }
        } catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("Unable to load configuration: {}", file, e);
            }
        }
    }

    public static Config getInstance() {
        if (instance == null) {
            synchronized (Config.class) {
                if (instance == null) {
                    instance = new Config(getDefaultApplicationFile(), getDefaultUserFile());
                }
            }
        }
        return instance;
    }

    public static void setInstance(Config config) {
        instance = config;
    }

    static File getDefaultUserFile() {
        String path = System.getProperty(CONFIG_PROPERTY);
        if (StringUtils.isNotBlank(path)) {
            return new File(path);
        }
        return new File(new File(System.getProperty("user.home"), ".sqljam"), FILE_NAME);
    }

    /**
     * Returns the external sqljam.properties of the installation: -Dsqljam.app.config, otherwise conf/sqljam.properties
     * or sqljam.properties in the directory of the running jar. Returns null when not running from a jar.
     */
    static File getDefaultApplicationFile() {
        String path = System.getProperty(APP_CONFIG_PROPERTY);
        if (StringUtils.isNotBlank(path)) {
            return new File(path);
        }
        return getApplicationFile(getJarDirectory());
    }

    static File getApplicationFile(File directory) {
        if (directory == null) {
            return null;
        }
        File file = new File(new File(directory, CONF_DIR_NAME), FILE_NAME);
        if (file.isFile()) {
            return file;
        }
        file = new File(directory, FILE_NAME);
        return file.isFile() ? file : null;
    }

    /**
     * Directory of the running jar, which is not the working directory when the jar is started by double click
     */
    static File getJarDirectory() {
        try {
            File location = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.isFile() ? location.getParentFile() : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    public File getApplicationFile() {
        return appFile;
    }

    public File getUserFile() {
        return userFile;
    }

    public String getRawValue(String key) {
        String value = overrides.getProperty(key);
        if (value == null) {
            value = appProperties.getProperty(key);
        }
        return value != null ? value : defaults.getProperty(key);
    }

    public String getString(String key, String defaultValue) {
        String value = getRawValue(key);
        return value != null ? resolve(value, 0) : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        try {
            return StringUtils.isBlank(value) ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String value = getString(key, null);
        try {
            return StringUtils.isBlank(value) ? defaultValue : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = getString(key, null);
        try {
            return StringUtils.isBlank(value) ? defaultValue : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, null);
        return StringUtils.isBlank(value) ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public File getFile(String key, File defaultValue) {
        String value = getString(key, null);
        return StringUtils.isBlank(value) ? defaultValue : new File(value);
    }

    private String resolve(String value, int depth) {
        if (depth > 10 || value.indexOf("${") < 0) {
            return value;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getProperty(name);
            if (replacement == null) {
                String raw = getRawValue(name);
                replacement = raw != null ? resolve(raw, depth + 1) : matcher.group();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Sets a user value, saved by {@link #save()}
     */
    public synchronized void set(String key, Object value) {
        if (value == null) {
            overrides.remove(key);
        } else {
            overrides.setProperty(key, String.valueOf(value));
        }
    }

    /**
     * Saves user values into the user file
     */
    public synchronized void save() throws IOException {
        if (userFile == null) {
            return;
        }
        Files.createDirectories(userFile.getAbsoluteFile().getParentFile().toPath());
        try (Writer writer = Files.newBufferedWriter(userFile.toPath(), StandardCharsets.UTF_8)) {
            overrides.store(writer, "SqlJam user configuration");
        }
    }
}
