package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots the FX toolkit and checks the image viewer decodes a real PNG (and fails gracefully on junk). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageViewerPaneFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void decodesARealPng(@TempDir Path dir) throws Exception {
        // A real 2x2 PNG written via ImageIO (works under java.awt.headless=true), decoded by JavaFX.
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, 0xFFFF0000);
        bi.setRGB(1, 1, 0xFF00FF00);
        Path file = dir.resolve("dot.png");
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
        ImageViewerPane pane = FxTestSupport.callOnFx(() -> new ImageViewerPane(file));
        assertNotNull(pane.node());
        assertEquals("dot.png", pane.title());
        assertEquals(file, pane.getPath());
        assertTrue(pane.hasImage(), "a valid PNG decodes");
    }

    @Test
    void failsGracefullyOnCorruptImage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.png");
        Files.writeString(file, "this is not a PNG");
        ImageViewerPane pane = FxTestSupport.callOnFx(() -> new ImageViewerPane(file));
        assertNotNull(pane.node(), "still renders (an error message), never throws");
        assertFalse(pane.hasImage(), "junk bytes don't decode");
    }
}
