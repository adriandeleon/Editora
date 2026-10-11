package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of {@link Fonts}: which bundled families the first painted frame needs. */
class FontsTest {

    @Test
    void firstPaintNeedsTheChromeFontAndTheCssDefaultEvenWithNoEditorFamily() {
        assertEquals(List.of(Fonts.UI, Fonts.DEFAULT), Fonts.firstPaintFamilies(null));
    }

    @Test
    void aSystemEditorFamilyAddsNothingToLoad() {
        assertEquals(List.of(Fonts.UI, Fonts.DEFAULT), Fonts.firstPaintFamilies("Consolas"));
    }

    @Test
    void theConfiguredBundledEditorFamilyIsLoadedBeforeFirstPaint() {
        assertEquals(List.of(Fonts.UI, Fonts.DEFAULT, "Fira Code"), Fonts.firstPaintFamilies("Fira Code"));
    }

    @Test
    void theDefaultFamilyIsNotListedTwice() {
        List<String> families = Fonts.firstPaintFamilies(Fonts.DEFAULT);
        assertEquals(List.of(Fonts.UI, Fonts.DEFAULT), families);
    }

    @Test
    void theBundledListIsTheEditorFamiliesOnly() {
        assertFalse(Fonts.BUNDLED.contains(Fonts.UI), "Inter is the chrome font, not an editor choice");
        assertTrue(Fonts.BUNDLED.contains(Fonts.DEFAULT));
        for (String family : Fonts.BUNDLED) {
            assertTrue(Fonts.firstPaintFamilies(family).contains(family), family + " must be loadable for first paint");
        }
    }
}
