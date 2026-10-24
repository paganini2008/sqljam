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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.kordamp.ikonli.javafx.FontIcon;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * @Description: DbIcons are the colored icons of the databases: the logo of a database on a rounded tile of its
 *               brand color (SVG paths of Simple Icons in db-icons.properties), or the image of its logo (the avatar
 *               of the GitHub organization of the project), a badge of its short name for an unknown one. Categories
 *               have a glyph on a tile of their color. Types are named by their display names, e.g. PostgreSQL or Qdrant,
 *               so that the icons of other lines (vector databases) need no class of them. Categories have badges.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class DbIcons {

    public static final double SIZE = 18;

    /**
     * Color of the badge, color of the text and the short name of a database
     */
    private static final class Badge {
        final Color background;
        final Color foreground;
        final String text;

        Badge(String background, String foreground, String text) {
            this.background = Color.web(background);
            this.foreground = Color.web(foreground);
            this.text = text;
        }
    }

    private static final Map<String, Badge> BADGES = new HashMap<>();
    /**
     * Logos by the names of the databases: tile color, logo color and SVG path of 24x24
     */
    private static final Map<String, String[]> LOGOS = new HashMap<>();

    static {
        Properties logos = new Properties();
        try (InputStream in = DbIcons.class.getResourceAsStream("/com/github/sqljam/face/images/db-icons.properties")) {
            if (in != null) {
                logos.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // Badges are drawn instead
        }
        logos.stringPropertyNames().forEach(name -> LOGOS.put(name.toLowerCase(Locale.ENGLISH), logos.getProperty(
                name).split("\\|", 3)));
    }

    static {
        // Relational databases
        put("MySQL", "#00758F", "#FFFFFF", "My");
        put("MariaDB", "#C0765A", "#FFFFFF", "Ma");
        put("PostgreSQL", "#336791", "#FFFFFF", "Pg");
        put("Oracle", "#C74634", "#FFFFFF", "Or");
        put("SQL Server", "#CC2927", "#FFFFFF", "SS");
        put("Db2", "#054ADA", "#FFFFFF", "D2");
        put("H2", "#0D47A1", "#FFFFFF", "H2");
        put("SQLite", "#0F80CC", "#FFFFFF", "SL");
        put("Access", "#A4373A", "#FFFFFF", "Ac");
        // OLAP databases
        put("DuckDB", "#FFC600", "#1A1A1A", "Du");
        put("ClickHouse", "#FAFF69", "#1A1A1A", "CH");
        // Vector databases
        put("Milvus", "#00A1EA", "#FFFFFF", "Mv");
        put("Qdrant", "#DC244C", "#FFFFFF", "Qd");
        put("Weaviate", "#61BD73", "#FFFFFF", "Wv");
        put("Pinecone", "#2B2B2B", "#FFFFFF", "Pc");
        put("Elasticsearch", "#00BFB3", "#FFFFFF", "ES");
        put("OpenSearch", "#005EB8", "#FFFFFF", "OS");
        put("Chroma", "#FF6446", "#FFFFFF", "Ch");
        // Categories
        put("Relational databases", "#3B82F6", "#FFFFFF", "R");
        put("OLAP databases", "#F59E0B", "#1A1A1A", "O");
        put("Vector databases", "#8B5CF6", "#FFFFFF", "V");
    }

    private DbIcons() {
    }

    /**
     * Whether the database has a logo, not a badge
     */
    public static boolean hasLogo(String name) {
        return name != null && LOGOS.containsKey(name.toLowerCase(Locale.ENGLISH));
    }

    static final String IMAGE = "png:";
    private static final Map<String, Image> IMAGES = new HashMap<>();
    /**
     * Icons of the categories by their names
     */
    private static final Map<String, String> CATEGORIES = Map.of(
            "relational databases", "fth-database",
            "olap databases", "fth-bar-chart-2",
            "vector databases", "fth-share-2");

    /**
     * An image of a logo (png:file|full or png:file|tile): full images fill the tile, others are inset on a white
     * tile, the tile is rounded
     */
    private static Node image(String[] logo) {
        boolean full = logo.length > 1 && "full".equals(logo[1]);
        // Images are read once, the cells of lists and trees share them
        Image image = IMAGES.computeIfAbsent(logo[0], key -> new Image(DbIcons.class.getResourceAsStream(
                "/com/github/sqljam/face/images/db/" + key.substring(IMAGE.length())), 48, 48, true, true));
        ImageView view = new ImageView(image);
        double size = full ? SIZE : SIZE - 3;
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(full ? 6 : 4);
        clip.setArcHeight(full ? 6 : 4);
        view.setClip(clip);
        Rectangle tile = new Rectangle(SIZE, SIZE);
        tile.setArcWidth(6);
        tile.setArcHeight(6);
        tile.setFill(Color.WHITE);
        StackPane icon = full ? new StackPane(view) : new StackPane(tile, view);
        icon.setAlignment(Pos.CENTER);
        icon.setMinSize(SIZE, SIZE);
        icon.setMaxSize(SIZE, SIZE);
        icon.getStyleClass().addAll("db-icon", "db-logo");
        return icon;
    }

    /**
     * Icon of a category: its glyph on a tile of its color
     */
    private static Node category(String name, String literal) {
        Badge badge = BADGES.get(name.toLowerCase(Locale.ENGLISH));
        Rectangle tile = new Rectangle(SIZE, SIZE);
        tile.setArcWidth(6);
        tile.setArcHeight(6);
        tile.setFill(badge != null ? badge.background : Color.GRAY);
        FontIcon glyph = new FontIcon(literal);
        glyph.setIconSize(12);
        glyph.setIconColor(badge != null ? badge.foreground : Color.WHITE);
        StackPane icon = new StackPane(tile, glyph);
        icon.setAlignment(Pos.CENTER);
        icon.setMinSize(SIZE, SIZE);
        icon.setMaxSize(SIZE, SIZE);
        icon.getStyleClass().addAll("db-icon", "db-category");
        return icon;
    }

    /**
     * A logo of 24x24 scaled into a tile of the brand color
     */
    private static Node logo(String[] logo) {
        Rectangle tile = new Rectangle(SIZE, SIZE);
        tile.setArcWidth(6);
        tile.setArcHeight(6);
        tile.setFill(Color.web(logo[0]));
        SVGPath path = new SVGPath();
        path.setContent(logo[2]);
        path.setFill(Color.web(logo[1]));
        double scale = (SIZE - 6) / 24;
        path.setScaleX(scale);
        path.setScaleY(scale);
        StackPane icon = new StackPane(tile, new Group(path));
        icon.setAlignment(Pos.CENTER);
        icon.setMinSize(SIZE, SIZE);
        icon.setMaxSize(SIZE, SIZE);
        icon.getStyleClass().addAll("db-icon", "db-logo");
        return icon;
    }

    private static void put(String name, String background, String foreground, String text) {
        BADGES.put(name.toLowerCase(Locale.ENGLISH), new Badge(background, foreground, text));
    }

    /**
     * Whether a database or a category of the name has an icon
     */
    public static boolean has(String name) {
        return name != null && (BADGES.containsKey(name.toLowerCase(Locale.ENGLISH)) || hasLogo(name));
    }

    /**
     * Icon of a database or a category by its display name, a gray badge of its first letters if it is unknown
     */
    public static Node of(String name) {
        String[] logo = name != null ? LOGOS.get(name.toLowerCase(Locale.ENGLISH)) : null;
        if (logo != null) {
            return logo[0].startsWith(IMAGE) ? image(logo) : logo(logo);
        }
        String category = name != null ? CATEGORIES.get(name.toLowerCase(Locale.ENGLISH)) : null;
        if (category != null) {
            return category(name, category);
        }
        Badge badge = name != null ? BADGES.get(name.toLowerCase(Locale.ENGLISH)) : null;
        if (badge == null) {
            String text = name == null || name.isBlank() ? "?" : name.trim().substring(0, Math.min(2,
                    name.trim().length()));
            badge = new Badge("#6B7280", "#FFFFFF", text);
        }
        Rectangle shape = new Rectangle(SIZE, SIZE);
        shape.setArcWidth(6);
        shape.setArcHeight(6);
        shape.setFill(badge.background);
        Text text = new Text(badge.text);
        text.setFill(badge.foreground);
        text.setFont(Font.font("System", FontWeight.BOLD, badge.text.length() > 1 ? 8.5 : 10));
        StackPane icon = new StackPane(shape, text);
        icon.setAlignment(Pos.CENTER);
        icon.setMinSize(SIZE, SIZE);
        icon.setMaxSize(SIZE, SIZE);
        icon.getStyleClass().add("db-icon");
        return icon;
    }
}
