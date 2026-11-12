package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.editor.UiZoom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UI zoom: one setting scales the whole interface. A change restyles an open window at once, a size the
 * code fixes follows it, the menus' 14 and 11 px scale with it whatever node they open from, and the three
 * commands move it and save it.
 */
@Tag("fx")
class UiZoomFxTest {

    private static final String APP_CSS =
            UiZoomFxTest.class.getResource("/com/editora/styles/app.css").toExternalForm();

    private static FxWindowFixture fx;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(UiZoom::install);
    }

    @AfterEach
    void backToDefault() throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.controller.config.getSettings().setUiZoom(Settings.DEFAULT_UI_ZOOM);
            Themes.applyUiZoom(Settings.DEFAULT_UI_ZOOM);
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
            fx = null;
        }
    }

    private static Scene mainScene() {
        return FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
    }

    @Test
    void atOneHundredPercentASheetIsItself() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertEquals(100, UiZoom.percent());
            assertEquals(APP_CSS, UiZoom.sheet(APP_CSS));
            assertEquals(12.0, UiZoom.px(12));
        });
    }

    @Test
    void aZoomedSheetIsACopyThatRemembersItsSource() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Themes.applyUiZoom(150);
            String zoomed = UiZoom.sheet(APP_CSS);
            assertTrue(zoomed.startsWith("data:text/css;base64,"), zoomed.substring(0, 30));
            assertEquals(APP_CSS, UiZoom.base(zoomed));
            assertEquals(zoomed, UiZoom.sheet(zoomed), "a zoomed sheet asked for again is the same copy");
            assertEquals(List.of(APP_CSS), UiZoom.bases(List.of(zoomed, APP_CSS)));
            // A sheet with no lengths in it has nothing to scale, and one built in code is not a file.
            String syntax = UiZoomFxTest.class
                    .getResource("/com/editora/styles/syntax.css")
                    .toExternalForm();
            assertEquals(syntax, UiZoom.sheet(syntax));
            assertEquals("data:text/css,.x{}", UiZoom.sheet("data:text/css,.x{}"));
        });
    }

    @Test
    void aChangeRestylesTheOpenWindowAtOnceAndBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Scene scene = mainScene();
            List<String> before = new ArrayList<>(scene.getStylesheets());
            assertTrue(before.contains(APP_CSS), "precondition: the window carries app.css");
            Label label = new Label("status");
            ((javafx.scene.layout.Pane) scene.getRoot()).getChildren().add(label);
            try {
                scene.getRoot().applyCss();
                double size = label.getFont().getSize();

                assertTrue(Themes.applyUiZoom(150));
                scene.getRoot().applyCss();
                assertFalse(scene.getStylesheets().contains(APP_CSS), "app.css is replaced by its zoomed copy");
                assertEquals(before, UiZoom.bases(scene.getStylesheets()));
                assertEquals(size * 1.5, label.getFont().getSize(), 0.01, "every font follows the root size");
                String theme = javafx.application.Application.getUserAgentStylesheet();
                assertNotEquals(UiZoom.base(theme), theme, "the theme's sheet is zoomed too");

                assertFalse(Themes.applyUiZoom(150), "no change, nothing to do");
                assertTrue(Themes.applyUiZoom(100));
                scene.getRoot().applyCss();
                assertEquals(before, new ArrayList<>(scene.getStylesheets()));
                assertEquals(size, label.getFont().getSize(), 0.01);
            } finally {
                ((javafx.scene.layout.Pane) scene.getRoot()).getChildren().remove(label);
            }
        });
    }

    /** MENU-16: Inter 14 px titles and 11 px chords from a toolbar, an editor and a tree row, times the zoom. */
    @Test
    void aMenuReadsFourteenAndElevenPixelsWhateverItOpensFromTimesTheZoom() throws Exception {
        for (int percent : new int[] {100, 150, 80}) {
            FxTestSupport.runOnFx(() -> {
                Themes.applyUiZoom(percent);
                double factor = percent / 100.0;
                for (String owner : List.of(
                        "-fx-font-size: 18px;",
                        "-fx-font-family: \"JetBrains Mono\"; -fx-font-size: 22px;",
                        "-fx-font-size: 11px; -fx-font-weight: bold;")) {
                    Label title = new Label("Rename…");
                    Label chord = new Label("F2");
                    chord.getStyleClass().add("accelerator-text");
                    HBox row = new HBox(title, chord);
                    row.getStyleClass().add("menu-item");
                    VBox menu = new VBox(row);
                    menu.getStyleClass().add("context-menu");
                    StackPane from = new StackPane(menu); // a menu's CSS parent is the node it opened from
                    from.setStyle(owner);
                    Scene scene = new Scene(new StackPane(from), 400, 300);
                    scene.getStylesheets().add(UiZoom.sheet(APP_CSS));
                    scene.getRoot().applyCss();
                    assertEquals(14 * factor, title.getFont().getSize(), 0.01, owner + " at " + percent);
                    assertEquals(11 * factor, chord.getFont().getSize(), 0.01, owner + " at " + percent);
                    assertEquals("Inter", title.getFont().getFamily(), owner);
                    assertEquals("Inter", chord.getFont().getFamily(), owner);
                }
            });
        }
    }

    @Test
    void aSizeTheCodeFixesFollowsTheZoom() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Region panel = UiZoom.prefWidth(new Region(), 280);
            VBox box = UiZoom.spacing(UiZoom.padding(new VBox(), 8, 12, 8, 12), 6);
            Region free = UiZoom.maxWidth(new Region(), Double.MAX_VALUE);
            Region computed = UiZoom.prefHeight(new Region(), Region.USE_COMPUTED_SIZE);
            Label styled = UiZoom.style(new Label(), "-fx-padding: 4 8; -fx-opacity: 0.5;");
            int[] runs = {0};
            Region counted = new Region();
            for (int i = 0; i < 3; i++) {
                UiZoom.follow(counted, () -> runs[0]++); // one place in the code: the newest follower replaces
            }
            assertEquals(3, runs[0]);

            Themes.applyUiZoom(150);
            assertEquals(420, panel.getPrefWidth(), 0.01);
            assertEquals(new javafx.geometry.Insets(12, 18, 12, 18), box.getPadding());
            assertEquals(9, box.getSpacing(), 0.01);
            assertEquals(Double.MAX_VALUE, free.getMaxWidth(), "no limit is not a length");
            assertEquals(Region.USE_COMPUTED_SIZE, computed.getPrefHeight(), "computed is not a length");
            assertEquals("-fx-padding: 6 12; -fx-opacity: 0.5;", styled.getStyle());
            assertEquals(4, runs[0], "one follower for one place, however often it registered");

            Themes.applyUiZoom(100);
            assertEquals(280, panel.getPrefWidth(), 0.01);
            assertEquals("-fx-padding: 4 8; -fx-opacity: 0.5;", styled.getStyle());
        });
    }

    @Test
    void aPinnedSceneStaysAtOneHundredPercent() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Themes.applyUiZoom(150);
            Label label = new Label("page");
            Scene page = new Scene(new Group(label));
            page.getStylesheets().add(UiZoom.sheet(APP_CSS));
            UiZoom.pin(page);
            assertEquals(List.of(APP_CSS), new ArrayList<>(page.getStylesheets()));
            String theme = javafx.application.Application.getUserAgentStylesheet();
            assertEquals(UiZoom.base(theme), page.getUserAgentStylesheet(), "the theme at 100%, not the zoomed one");

            Themes.applyUiZoom(100);
            assertNull(page.getUserAgentStylesheet(), "at 100% the app's own sheet serves");
        });
    }

    @Test
    void theCommandsZoomInOutAndBackAndSaveTheSetting() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Settings settings = fx.controller.config.getSettings();
            fx.controller.uiZoom(1);
            assertEquals(110, settings.getUiZoom());
            assertEquals(110, UiZoom.percent());
            fx.controller.uiZoom(1);
            assertEquals(120, settings.getUiZoom());
            fx.controller.uiZoom(-1);
            assertEquals(110, settings.getUiZoom());
            fx.controller.uiZoom(0);
            assertEquals(100, settings.getUiZoom());
            assertEquals(100, UiZoom.percent());

            settings.setUiZoom(Settings.MAX_UI_ZOOM);
            Themes.applyUiZoom(Settings.MAX_UI_ZOOM);
            fx.controller.uiZoom(1);
            assertEquals(Settings.MAX_UI_ZOOM, settings.getUiZoom(), "the largest zoom is a limit");

            for (String id : List.of("view.uiZoomIn", "view.uiZoomOut", "view.uiZoomReset")) {
                assertTrue(fx.controller.registry.get(id).isPresent(), id + " is a registered command");
            }
        });
    }

    @Test
    void theSettingIsClampedAndKept() throws Exception {
        Settings s = new Settings();
        assertEquals(100, s.getUiZoom());
        s.setUiZoom(500);
        assertEquals(Settings.MAX_UI_ZOOM, s.getUiZoom());
        s.setUiZoom(10);
        assertEquals(Settings.MIN_UI_ZOOM, s.getUiZoom());
        s.setUiZoom(130);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(
                130,
                mapper.readValue(mapper.writeValueAsString(s), Settings.class).getUiZoom());
    }
}
