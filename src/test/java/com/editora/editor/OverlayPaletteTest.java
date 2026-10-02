package com.editora.editor;

import java.util.List;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Theme-aware colours of the Canvas editor overlays (pure: {@code Color} needs no toolkit). */
class OverlayPaletteTest {

    /** Editor backgrounds of bundled themes: light, dark and the awkward mid-tones. */
    private static final List<String> GROUNDS = List.of(
            "#ffffff", "#fdf8f0", "#eceff4", "#e8eccc", "#f2f0ef", "#171a24", "#0d1117", "#2e3440", "#304c64",
            "#332524", "#000000");

    @Test
    void whitespaceMarkersAreSubtleOnBothGrounds() {
        // The old fixed #b8bdc4 was 1.89:1 on white and 9.19:1 on the flagship dark editor.
        for (String hex : GROUNDS) {
            Color ground = Color.web(hex);
            double ratio = OverlayPalette.contrast(OverlayPalette.of(ground).whitespace(), ground);
            assertTrue(ratio >= 2.95, "invisible on " + hex + ": " + ratio);
            assertTrue(ratio <= 3.6, "shouting on " + hex + ": " + ratio);
        }
    }

    @Test
    void squigglesAndTheMatchBorderReachThreeToOneEverywhere() {
        for (String hex : GROUNDS) {
            Color ground = Color.web(hex);
            OverlayPalette.Colors c = OverlayPalette.of(ground);
            for (Color indicator : List.of(c.error(), c.warning(), c.info(), c.searchActiveBorder())) {
                double ratio = OverlayPalette.contrast(indicator, ground);
                assertTrue(ratio >= 2.99, indicator + " on " + hex + " is " + ratio);
            }
        }
        // The reported case: the warning squiggle on a white editor was 2.25:1.
        assertTrue(OverlayPalette.contrast(Color.web("#e2a03f"), Color.WHITE) < 3);
        assertTrue(OverlayPalette.contrast(OverlayPalette.of(Color.WHITE).warning(), Color.WHITE) >= 3);
    }

    @Test
    void inlineDebugValuesAreReadableText() {
        for (String hex : GROUNDS) {
            Color ground = Color.web(hex);
            double ratio = OverlayPalette.contrast(OverlayPalette.of(ground).inlineValue(), ground);
            assertTrue(ratio >= 4.45, "inline values on " + hex + " are " + ratio);
        }
    }

    @Test
    void lightAndDarkGroundsGetDifferentPalettes() {
        OverlayPalette.Colors light = OverlayPalette.of(Color.WHITE);
        OverlayPalette.Colors dark = OverlayPalette.of(Color.web("#171a24"));
        assertNotEquals(light.whitespace(), dark.whitespace());
        assertNotEquals(light.warning(), dark.warning());
        assertTrue(OverlayPalette.isDark(Color.web("#171a24")));
        assertTrue(OverlayPalette.isDark(Color.web("#304c64")));
        assertFalse(OverlayPalette.isDark(Color.web("#e8eccc")));
        // Text sits under the search wash, so it stays a translucent tint on both grounds.
        assertTrue(light.searchMatch().getOpacity() < 0.6 && dark.searchMatch().getOpacity() < 0.6);
        assertTrue(dark.searchMatch().getOpacity() < light.searchMatch().getOpacity());
    }

    @Test
    void reachLeavesAGoodColourAloneAndFixesAWeakOne() {
        Color ok = Color.web("#c03434");
        assertEquals(ok, OverlayPalette.reach(ok, Color.WHITE, 3.0));
        Color fixed = OverlayPalette.reach(Color.web("#ffe08a"), Color.WHITE, 3.0);
        assertTrue(OverlayPalette.contrast(fixed, Color.WHITE) >= 3.0);
        assertTrue(OverlayPalette.contrast(fixed, Color.WHITE) < 3.2, "moved no further than needed");
        // A translucent or missing background is treated as the stylesheet default (white).
        assertEquals(OverlayPalette.of(Color.WHITE), OverlayPalette.of(null));
    }
}
