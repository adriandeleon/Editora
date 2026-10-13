package com.editora.editor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reader's preview choices (font size, column width, front matter) and the enlarged picture's scale. */
class PreviewPrefsTest {

    @AfterEach
    void reset() {
        PreviewPrefs.set(0, 0, false);
    }

    @Test
    void zeroIsTheBuiltInSizeAndWidth() {
        PreviewPrefs.set(0, 0, false);
        assertEquals(PreviewPrefs.BASE_FONT, PreviewPrefs.baseFont());
        assertEquals(PreviewPrefs.BASE_WIDTH, PreviewPrefs.contentWidth());
        assertFalse(PreviewPrefs.hideFrontMatter());
        assertEquals("-fx-font-size: 15.0px;", PreviewPrefs.fontStyle(1));
        assertEquals("-fx-font-size: 30.0px;", PreviewPrefs.fontStyle(2), "the preview's zoom multiplies it");
        PreviewPrefs.set(-3, -1, false);
        assertEquals(PreviewPrefs.BASE_FONT, PreviewPrefs.baseFont());
        assertEquals(PreviewPrefs.BASE_WIDTH, PreviewPrefs.contentWidth());
    }

    @Test
    void aChosenSizeAndWidthAreUsedWithinSaneBounds() {
        PreviewPrefs.set(18, 1100, true);
        assertEquals(18, PreviewPrefs.baseFont());
        assertEquals(1100, PreviewPrefs.contentWidth());
        assertTrue(PreviewPrefs.hideFrontMatter());
        assertEquals("-fx-font-size: 27.0px;", PreviewPrefs.fontStyle(1.5));
        PreviewPrefs.set(2, 10, false);
        assertEquals(PreviewPrefs.MIN_FONT, PreviewPrefs.baseFont());
        assertEquals(PreviewPrefs.MIN_WIDTH, PreviewPrefs.contentWidth());
        PreviewPrefs.set(500, 1_000_000, false);
        assertEquals(PreviewPrefs.MAX_FONT, PreviewPrefs.baseFont());
        assertEquals(PreviewPrefs.MAX_WIDTH, PreviewPrefs.contentWidth());
    }

    @Test
    void whatChangesTheBuiltNodesChangesTheSignature() {
        PreviewPrefs.set(0, 0, false);
        String base = PreviewPrefs.signature();
        PreviewPrefs.set(20, 0, false);
        assertEquals(base, PreviewPrefs.signature(), "the font is a style of the pane, not of its nodes");
        PreviewPrefs.set(0, 1000, false);
        assertNotEquals(base, PreviewPrefs.signature());
        PreviewPrefs.set(0, 0, true);
        assertNotEquals(base, PreviewPrefs.signature());
    }

    @Test
    void anEnlargedPictureFitsTheWindowAndASmallOneIsNotBlownUpWithoutLimit() {
        assertEquals(0.5, PreviewLightbox.scale(2000, 1000, 1000, 800), 1e-9);
        assertEquals(0.4, PreviewLightbox.scale(1000, 2000, 1000, 800), 1e-9);
        assertEquals(2.0, PreviewLightbox.scale(400, 300, 800, 800), 1e-9, "a small one is magnified to fit");
        assertEquals(PreviewLightbox.MAX_MAGNIFICATION, PreviewLightbox.scale(16, 16, 1000, 800), 1e-9);
        assertEquals(1, PreviewLightbox.scale(0, 0, 1000, 800), 1e-9);
    }
}
