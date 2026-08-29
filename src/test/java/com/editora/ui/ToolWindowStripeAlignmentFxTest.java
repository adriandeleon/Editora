package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
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

    /**
     * An open side panel's header must start where the stripe's first button does.
     *
     * <p>The stripes and the split holding the panels share a top edge, but only the stripes were inset to
     * meet the editor's first line of code — so the panel's header sat a tab-strip's height above the very
     * button that opened it. Two competing top edges, and nothing but the eye to catch it.
     */
    @Test
    void anOpenSidePanelsHeaderStartsAtTheFirstStripeButton() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button stripeButton = firstRightStripeButton();
            stripeButton.fire();
            try {
                Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();

                ToolWindowManager twm = FxTestSupport.field(fx.controller, "toolWindows");
                java.util.Map<ToolWindow, Region> panels = FxTestSupport.field(twm, "panels");
                assertEquals(1, panels.size(), "firing the stripe button should have opened its tool window");
                Region panel = panels.values().iterator().next();
                Node header = panel.lookup(".tool-window-header");
                assertNotNull(header, "an open tool window should carry a header");

                assertEquals(
                        inScene(stripeButton).getMinY(),
                        inScene(header).getMinY(),
                        TOLERANCE,
                        "the panel header should start level with the stripe button that opened it");
            } finally {
                stripeButton.fire(); // leave the layout as the other tests here expect to find it
            }
        });
    }

    /**
     * That same inset must also be the same COLOUR as the chrome it sits in.
     *
     * <p>The inset is padding <em>inside</em> the panel, so the panel's own fill paints it — and with the
     * content's default ground that put a white notch in the band the toolbar, the tab strip and the
     * stripes' matching insets otherwise make across the top of the window. Aligning the header without
     * matching the fill fixes half the problem, and the half that is left is only visible to the eye.
     *
     * <p>The corner radii are asserted with it: rounding the panel's background would punch the ground
     * behind it back through the band at both ends, which is the same defect a few pixels smaller.
     */
    @Test
    void anOpenSidePanelsTopInsetContinuesTheChromeBandAbove() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button stripeButton = firstRightStripeButton();
            stripeButton.fire();
            try {
                Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();

                ToolWindowManager twm = FxTestSupport.field(fx.controller, "toolWindows");
                java.util.Map<ToolWindow, Region> panels = FxTestSupport.field(twm, "panels");
                Region panel = panels.values().iterator().next();
                assertTrue(
                        panel.getInsets().getTop() > 0,
                        "precondition: a side panel is inset at the top, so its own fill is what paints there");

                Region toolBar = (Region) scene.lookup(".tool-bar");
                assertNotNull(toolBar, "the toolbar should be in the scene");
                BackgroundFill panelFill = panel.getBackground().getFills().get(0);
                assertEquals(
                        toolBar.getBackground().getFills().get(0).getFill(),
                        panelFill.getFill(),
                        "a side panel's top inset should carry the same ground as the chrome above it");

                CornerRadii radii = panelFill.getRadii();
                assertEquals(
                        0.0,
                        radii.getTopLeftHorizontalRadius() + radii.getTopRightHorizontalRadius(),
                        "the panel's background should be square at the top, where it meets that band");
            } finally {
                stripeButton.fire(); // leave the layout as the other tests here expect to find it
            }
        });
    }

    /**
     * The right-pinned end of the toolbar and the right tool stripe form one icon column, so the last
     * toolbar glyph must sit directly above the stripe's.
     *
     * <p>Found by walking the toolbar rather than by naming a button: which control ends the fixed tail is
     * a layout decision that changes (it was Quit until About and Quit moved to the menus), and the
     * invariant is about the column, not about any particular icon.
     */
    @Test
    void theLastToolbarGlyphSharesAVerticalCentreLineWithTheStripeIcons() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // The whole row, not just the ToolBar: the right-pinned controls live in the tail beside it.
            javafx.scene.layout.HBox row = FxTestSupport.field(fx.controller, "toolbarRow");
            Node lastGlyph = lastGlyphIn(row);
            Node stripeGlyph = firstRightStripeButton().getGraphic();
            assertNotNull(lastGlyph, "the toolbar should end in a glyph button");
            assertNotNull(stripeGlyph, "a stripe button should carry a glyph");

            assertEquals(
                    inScene(stripeGlyph).getCenterX(),
                    inScene(lastGlyph).getCenterX(),
                    TOLERANCE,
                    "the last toolbar icon should sit directly above the right stripe's icons");
        });
    }

    /** Depth-first from the right: the last button carrying a glyph anywhere under {@code node}. */
    private static Node lastGlyphIn(Node node) {
        if (node instanceof javafx.scene.control.ToolBar bar) {
            return lastGlyphOf(bar.getItems());
        }
        if (node instanceof javafx.scene.Parent parent) {
            return lastGlyphOf(parent.getChildrenUnmodifiable());
        }
        return null;
    }

    private static Node lastGlyphOf(java.util.List<Node> children) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Node child = children.get(i);
            if (child instanceof Button b && b.getGraphic() != null && b.isVisible()) {
                return b.getGraphic();
            }
            Node nested = lastGlyphIn(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
