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
import java.util.List;

import com.github.sqljam.impexp.DbCategory;
import com.github.sqljam.impexp.DbType;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.CubicCurve;

/**
 * @Description: FeatureMap shows what SqlJam does as a map of its features: SqlJam in the middle and a branch for
 *               each group of features on both sides, joined by curves. The texts are messages
 *               (feature.&lt;group&gt;.title and feature.&lt;group&gt;.items), the data sources show their logos.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class FeatureMap extends Pane {

    /**
     * Groups of features, the first half on the left and the second half on the right
     */
    static final String[] GROUPS = {"sources", "structure", "rows", "transfer", "reliability", "vectors"};
    static final String[] ICONS = {Icons.CATALOG, Icons.TABLE, Icons.DATA, Icons.EXPORT, Icons.CONNECT,
            "fth-share-2"};
    static final double CARD_WIDTH = 300;
    static final double GAP = 18;
    static final double CENTER_WIDTH = 170;

    private final VBox center;
    private final List<VBox> cards = new ArrayList<>();
    private final List<CubicCurve> curves = new ArrayList<>();

    public FeatureMap() {
        setId("featureMap");
        getStyleClass().add("feature-map");
        ImageView logo = Branding.logoView(84);
        Label name = new Label("SqlJam");
        name.getStyleClass().add("feature-map-name");
        name.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label tagline = new Label(Messages.get("feature.tagline"));
        tagline.getStyleClass().add("muted");
        tagline.setWrapText(true);
        tagline.setMaxWidth(CENTER_WIDTH);
        center = new VBox(6, logo, name, tagline);
        center.setAlignment(Pos.CENTER);
        center.getStyleClass().add("feature-map-center");
        center.setPrefWidth(CENTER_WIDTH);
        for (int i = 0; i < GROUPS.length; i++) {
            CubicCurve curve = new CubicCurve();
            curve.getStyleClass().add("feature-map-curve");
            // Styles of the theme, the dialog has no stylesheet of the application
            curve.setStyle("-fx-fill: transparent; -fx-stroke: -color-accent-emphasis; -fx-stroke-width: 1.6;");
            curves.add(curve);
            cards.add(card(GROUPS[i], ICONS[i]));
        }
        getChildren().addAll(curves);
        getChildren().add(center);
        getChildren().addAll(cards);
    }

    /**
     * A group of features: its icon and title, its features one a line, the logos of the data sources
     */
    private static VBox card(String group, String icon) {
        Label title = new Label(Messages.get("feature." + group + ".title"), Icons.of(icon));
        title.getStyleClass().add("feature-map-title");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        VBox card = new VBox(6, title);
        card.setId("feature-" + group);
        card.getStyleClass().add("feature-map-card");
        card.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 10; -fx-border-color:"
                + " -color-border-default; -fx-border-radius: 10;");
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        for (String item : Messages.get("feature." + group + ".items").split("\\|")) {
            Label line = new Label("• " + item.trim());
            line.setWrapText(true);
            line.setMaxWidth(CARD_WIDTH - 24);
            card.getChildren().add(line);
        }
        if ("sources".equals(group)) {
            card.getChildren().add(logos());
        }
        return card;
    }

    /**
     * Logos of the databases by category, vector databases after them
     */
    private static Node logos() {
        FlowPane logos = new FlowPane(4, 4);
        logos.setId("featureLogos");
        logos.setPrefWrapLength(CARD_WIDTH - 24);
        for (DbCategory category : DbCategory.values()) {
            for (DbType type : DbType.values()) {
                if (type.getCategory() == category && DbIcons.has(type.getDisplayName())) {
                    Node icon = DbIcons.of(type.getDisplayName());
                    Label label = new Label(null, icon);
                    label.setTooltip(new Tooltip(type.getDisplayName()));
                    logos.getChildren().add(label);
                }
            }
        }
        for (String vector : new String[]{"Milvus", "Qdrant", "Weaviate", "Pinecone", "Elasticsearch", "OpenSearch",
                "Chroma"}) {
            if (DbIcons.has(vector)) {
                Label label = new Label(null, DbIcons.of(vector));
                label.setTooltip(new Tooltip(vector));
                logos.getChildren().add(label);
            }
        }
        return logos;
    }

    List<VBox> getCards() {
        return cards;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 2 * CARD_WIDTH + CENTER_WIDTH + 4 * GAP + 2 * 40;
    }

    /**
     * The map is never narrower than its columns, a narrow window scrolls it instead of putting cards on each other
     */
    @Override
    protected double computeMinWidth(double height) {
        return computePrefWidth(height);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        return Math.max(columnHeight(0), columnHeight(GROUPS.length / 2)) + 2 * GAP;
    }

    private double columnHeight(int from) {
        double height = 0;
        for (int i = from; i < from + GROUPS.length / 2; i++) {
            height += cards.get(i).prefHeight(CARD_WIDTH) + GAP;
        }
        return height - GAP;
    }

    /**
     * SqlJam in the middle, the groups in two columns, each joined to the middle by a curve
     */
    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        double centerHeight = center.prefHeight(CENTER_WIDTH);
        double centerX = (width - CENTER_WIDTH) / 2;
        double centerY = (height - centerHeight) / 2;
        center.resizeRelocate(centerX, centerY, CENTER_WIDTH, centerHeight);
        int half = GROUPS.length / 2;
        double leftX = Math.max(GAP, centerX - 40 - CARD_WIDTH);
        double rightX = Math.min(width - CARD_WIDTH - GAP, centerX + CENTER_WIDTH + 40);
        for (int column = 0; column < 2; column++) {
            int from = column * half;
            double y = Math.max(GAP, (height - columnHeight(from)) / 2);
            for (int i = from; i < from + half; i++) {
                VBox card = cards.get(i);
                double cardHeight = card.prefHeight(CARD_WIDTH);
                double x = column == 0 ? leftX : rightX;
                card.resizeRelocate(x, y, CARD_WIDTH, cardHeight);
                CubicCurve curve = curves.get(i);
                double startX = column == 0 ? centerX : centerX + CENTER_WIDTH;
                double startY = centerY + centerHeight / 2;
                double endX = column == 0 ? x + CARD_WIDTH : x;
                double endY = y + Math.min(cardHeight / 2, 24);
                double middle = (startX + endX) / 2;
                curve.setStartX(startX);
                curve.setStartY(startY);
                curve.setControlX1(middle);
                curve.setControlY1(startY);
                curve.setControlX2(middle);
                curve.setControlY2(endY);
                curve.setEndX(endX);
                curve.setEndY(endY);
                y += cardHeight + GAP;
            }
        }
    }

    /**
     * Bounds of a card in the map, e.g. for tests
     */
    Bounds getCardBounds(int index) {
        return cards.get(index).getBoundsInParent();
    }
}
