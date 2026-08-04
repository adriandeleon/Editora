package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two toolbar/stripe alignments, both of which are pixel relationships between nodes that live
 * in different parts of the scene graph and are therefore invisible to any pure test:
 *
 * <ul>
 *   <li>the right stripe's first icon sits beside the editor's first line of text, not beside the tab
 *       strip above it (see {@code ToolWindowManager.alignVerticalStripesWithEditorText});
 *   <li>the right-pinned Quit glyph shares a vertical centre line with the stripe icons below it (the
 *       {@code .tool-bar} right inset in {@code app.css}).
 * </ul>
 *
 * <p>Both are set from a measured value, so either can drift the moment the tab strip's or the toolbar
 * button's own metrics change — which is exactly what a build should catch.
 */
@Tag("fx")
class ToolWindowStripeAlignmentFxTest {

    /** Layout rounds to integers, and the two glyphs differ in intrinsic width, so allow a hair. */
    private static final double TOLERANCE = 1.5;

    /** A glyph is not the same height as a text line, so "on the line" is a few px, not exact. */
    private static final double GLYPH_TOLERANCE = 4;

    private static FxWindowFixture fx;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        Path dir = Files.createTempDirectory("editora-stripe-align");
        Path file = dir.resolve("sample.md");
        Files.writeString(file, "# Hello\n\nsecond line\nthird line\n");
        fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, 1, 1)), true, c -> {});
        FxTestSupport.runOnFx(() -> {
            Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
            scene.getRoot().resize(1500, 800);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** The stripe button's top padding, read from the live control rather than restated here. */
    private static long stripeButtonTopInset() {
        return Math.round(firstRightStripeButton().getInsets().getTop());
    }

    private static Bounds inScene(Node n) {
        return n.localToScene(n.getBoundsInLocal());
    }

    private static Button firstRightStripeButton() {
        Pane right = FxTestSupport.field(FxTestSupport.field(fx.controller, "toolWindows"), "rightStripe");
        assertFalse(right.getChildren().isEmpty(), "the right stripe should carry buttons by default");
        return (Button) right.getChildren().get(0);
    }

    /**
     * Asserts the GLYPH, not the button box. The button pads above its icon to space the rail out, so a
     * box-top assertion would keep passing while the visible icon walked away from the line it is meant to
     * sit beside — which is exactly what happened when that padding was introduced.
     */
    @Test
    void firstRightStripeIconSitsOnTheEditorsFirstTextLine() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node tabPane = FxTestSupport.field(fx.controller, "tabPane");
            Node editorArea = tabPane.lookup(".editor-area");
            assertNotNull(editorArea, "the sample file should have opened into a CodeArea");
            Node glyph = firstRightStripeButton().getGraphic();
            assertNotNull(glyph, "a stripe button should carry a glyph");

            // The first character's own bounds — no line-height arithmetic to get wrong.
            org.fxmisc.richtext.CodeArea area = (org.fxmisc.richtext.CodeArea) editorArea;
            Bounds firstChar = area.getCharacterBoundsOnScreen(0, 1).orElseThrow();
            double sceneOriginY =
                    area.getScene().getWindow().getY() + area.getScene().getY();
            double firstLineCentre = firstChar.getCenterY() - sceneOriginY;

            assertEquals(
                    firstLineCentre,
                    inScene(glyph).getCenterY(),
                    GLYPH_TOLERANCE,
                    "the first stripe glyph should sit on the first line of code, not above or below it");
        });
    }

    @Test
    void stripeTopInsetTracksTheTabStripSoHidingItKeepsTheAlignment() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node tabPane = FxTestSupport.field(fx.controller, "tabPane");
            Region header = (Region) tabPane.lookup(".tab-header-area");
            assertNotNull(header, "the tab header should exist once the skin is built");
            assertTrue(header.getHeight() > 0, "precondition: the tab strip is showing");

            Pane right = FxTestSupport.field(FxTestSupport.field(fx.controller, "toolWindows"), "rightStripe");
            // The inset is applied inline (an author stylesheet would otherwise win it back), so the
            // measured tab-strip height — less the button's own top padding, which offsets the glyph
            // inside it — has to be what reaches the stripe.
            long expected = Math.max(0, Math.round(header.getHeight()) - stripeButtonTopInset());
            assertTrue(
                    right.getStyle().contains("-fx-padding: " + expected + "px"),
                    "the stripe's top inset should track the live tab-header height, not be a constant; was: "
                            + right.getStyle());
        });
    }

    @Test
    void quitGlyphSharesAVerticalCentreLineWithTheStripeIcons() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button quit = FxTestSupport.field(fx.controller, "quitButton");
            Node quitGlyph = quit.getGraphic();
            Node stripeGlyph = firstRightStripeButton().getGraphic();
            assertNotNull(quitGlyph, "the Quit button should carry a glyph");
            assertNotNull(stripeGlyph, "a stripe button should carry a glyph");

            assertEquals(
                    inScene(stripeGlyph).getCenterX(),
                    inScene(quitGlyph).getCenterX(),
                    TOLERANCE,
                    "Quit should sit directly above the right stripe's icons");
        });
    }
}
