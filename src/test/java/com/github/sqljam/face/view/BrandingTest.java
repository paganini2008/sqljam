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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.Banner;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: BrandingTest
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class BrandingTest {

    @TempDir
    File dir;

    private Stage stage;
    private final List<String> opened = new ArrayList<>();

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new Scene(new StackPane(), 200, 100));
        stage.show();
        FxTestSupport.createContext(dir);
    }

    @Test
    void images() {
        Image logo = Branding.getLogo();
        assertNotNull(logo);
        assertSame(logo, Branding.getLogo(), "Images are cached");
        assertTrue(logo.getWidth() >= 512, "Logo is large enough for HiDPI screens");
        assertNotNull(Branding.getLogoMark());
        assertNull(Branding.getImage("missing.png"));

        List<Image> icons = Branding.getIcons();
        assertEquals(Branding.ICON_SIZES.length, icons.size());
        for (int i = 0; i < icons.size(); i++) {
            assertEquals(Branding.ICON_SIZES[i], icons.get(i).getWidth(), 0.1);
            assertEquals(Branding.ICON_SIZES[i], icons.get(i).getHeight(), 0.1);
        }
        // The logo is transparent around the artwork
        assertEquals(0, logo.getPixelReader().getColor(0, 0).getOpacity(), 0.01);
    }

    @Test
    void views() {
        ImageView logo = FxTestSupport.call(() -> Branding.logoView(80));
        assertEquals(80, logo.getFitHeight(), 0.1);
        assertTrue(logo.isPreserveRatio());
        assertEquals(22, FxTestSupport.call(() -> Branding.logoMarkView(22)).getFitHeight(), 0.1);
    }

    @Test
    void windowIcons() {
        FxTestSupport.run(() -> {
            Stage window = new Stage();
            Branding.applyIcons(window);
            assertEquals(Branding.ICON_SIZES.length, window.getIcons().size());
            // Icons already set are kept
            Stage custom = new Stage();
            custom.getIcons().add(Branding.getLogoMark());
            Branding.applyIcons(custom);
            assertEquals(1, custom.getIcons().size());
            Branding.applyIcons((Stage) null);
        });
    }

    @Test
    void about() {
        Alert about = FxTestSupport.call(() -> Dialogs.createAbout(stage, opened::add));
        assertEquals("SqlJam " + Banner.getVersion(), about.getHeaderText());
        assertNotNull(about.getGraphic());
        assertEquals("aboutLogo", about.getGraphic().getId());
        FxTestSupport.run(about::show);
        Stage window = (Stage) about.getDialogPane().getScene().getWindow();
        assertEquals(Branding.ICON_SIZES.length, FxTestSupport.call(() -> window.getIcons().size()).intValue());
        FxTestSupport.run(about::close);
        assertSame(stage, about.getOwner());

        // An owner without a scene is not set, JavaFX would fail with a NullPointerException
        Alert withoutOwner = FxTestSupport.call(() -> Dialogs.createAbout(new Stage(), null));
        assertNull(withoutOwner.getOwner());
        Alert noOwner = FxTestSupport.call(() -> Dialogs.createAbout(null, null));
        assertNull(noOwner.getOwner());
        // Version, home page, repository, author, email and license, the links are opened in the browser
        String details = FxTestSupport.call(() -> ((GridPane) about.getDialogPane().lookup("#aboutDetails"))
                .getChildren().stream().map(node -> ((Labeled) node).getText()).collect(Collectors.joining("|")));
        for (String text : List.of("Version", Banner.getVersion(), "Home page", Branding.HOMEPAGE_URL,
                "Repository", Branding.REPOSITORY_URL, "Author", "Fred Feng", "Email", Branding.EMAIL, "License",
                "Apache License 2.0")) {
            assertTrue(details.contains(text), details);
        }
        for (String id : List.of("#aboutHomepage", "#aboutRepository", "#aboutEmail")) {
            FxTestSupport.run(() -> ((Hyperlink) about.getDialogPane().lookup(id)).fire());
        }
        assertEquals(List.of(Branding.HOMEPAGE_URL, Branding.REPOSITORY_URL, "mailto:" + Branding.EMAIL), opened);
        // Links without a browser do nothing
        FxTestSupport.run(() -> ((Hyperlink) noOwner.getDialogPane().lookup("#aboutHomepage")).fire());
        assertEquals(3, opened.size());
    }

    /**
     * The map of the features: SqlJam in the middle, six groups on both sides joined by curves, the logos of the
     * data sources, the dialog wider while it is shown
     */
    @Test
    void featureMap() throws Exception {
        Alert about = FxTestSupport.call(() -> Dialogs.createAbout(stage, null));
        FxTestSupport.run(about::show);
        javafx.scene.control.TabPane tabs = FxTestSupport.call(() -> (javafx.scene.control.TabPane) about
                .getDialogPane().lookup("#aboutTabs"));
        assertEquals(List.of("About", "Features"), FxTestSupport.call(() -> tabs.getTabs().stream().map(
                javafx.scene.control.Tab::getText).collect(Collectors.toList())));
        FxTestSupport.run(() -> tabs.getSelectionModel().select(1));
        assertEquals(1000, FxTestSupport.call(() -> about.getDialogPane().getPrefWidth()));
        FeatureMap map = FxTestSupport.call(() -> (FeatureMap) about.getDialogPane().lookup("#featureMap"));
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> about.getDialogPane().getScene().getWindow()
                .getWidth() >= 990));
        // The layout of the dialog, not a size of the test
        FxTestSupport.run(() -> {
            about.getDialogPane().applyCss();
            about.getDialogPane().layout();
        });
        assertTrue(FxTestSupport.call(map::getWidth) >= FxTestSupport.call(() -> map.prefWidth(-1)) - 1);
        List<javafx.scene.layout.VBox> cards = map.getCards();
        assertEquals(6, cards.size());
        List<javafx.geometry.Bounds> all = FxTestSupport.call(() -> java.util.stream.IntStream.range(0, cards.size())
                .mapToObj(map::getCardBounds).collect(Collectors.toList()));
        for (int i = 0; i < cards.size(); i++) {
            javafx.geometry.Bounds bounds = all.get(i);
            for (int j = i + 1; j < cards.size(); j++) {
                javafx.geometry.Bounds other = all.get(j);
                assertFalse(bounds.intersects(other), FeatureMap.GROUPS[i] + " and " + FeatureMap.GROUPS[j]);
            }
        }
        // Left and right of the middle
        assertTrue(all.get(0).getMaxX() < all.get(3).getMinX());
        // Every group has items, the data sources show their logos
        assertTrue(FxTestSupport.call(() -> cards.stream().allMatch(card -> card.getChildren().size() > 2)));
        assertTrue(FxTestSupport.call(() -> ((javafx.scene.layout.FlowPane) map.lookup("#featureLogos"))
                .getChildren().size() > 5));
        File image = new File("target/screenshots/feature-map.png");
        image.getParentFile().mkdirs();
        DocImages.write(FxTestSupport.call(() -> about.getDialogPane().getScene().snapshot(null)), image);
        // Back to About, the dialog is narrow again
        FxTestSupport.run(() -> tabs.getSelectionModel().select(0));
        assertEquals(500, FxTestSupport.call(() -> about.getDialogPane().getPrefWidth()));
        FxTestSupport.run(about::close);
    }

    @Test
    void repositoryMark() {
        Label mark = FxTestSupport.call(() -> Branding.repositoryMark(opened::add));
        // An icon without text, the url is shown by the tooltip
        assertNull(mark.getText());
        assertNotNull(mark.getGraphic());
        assertEquals("https://github.com/paganini2008/sqljam", FxTestSupport.call(() -> mark.getTooltip()
                .getText()));
        // A click opens the repository
        assertEquals(Cursor.HAND, mark.getCursor());
        FxTestSupport.run(() -> mark.getOnMouseClicked().handle(null));
        assertEquals(List.of(Branding.REPOSITORY_URL), opened);
        // Without a browser it is not clickable
        Label plain = FxTestSupport.call(() -> Branding.repositoryMark(null));
        assertNull(plain.getOnMouseClicked());
    }
}
