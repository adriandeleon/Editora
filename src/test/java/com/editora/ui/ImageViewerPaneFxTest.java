package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots the FX toolkit and checks the image viewer decodes a real PNG (and fails gracefully on junk). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageViewerPaneFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Builds the pane on the FX thread and waits for its background load to be applied. */
    private static ImageViewerPane open(Path file, long maxPixels) throws Exception {
        ImageViewerPane pane = FxTestSupport.callOnFx(() -> new ImageViewerPane(file, maxPixels));
        pane.loadedForTest().get(30, TimeUnit.SECONDS);
        return pane;
    }

    private static Path png(Path dir, String name, int w, int h) throws Exception {
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, 0xFFFF0000);
        bi.setRGB(w - 1, h - 1, 0xFF00FF00);
        Path file = dir.resolve(name);
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
        return file;
    }

    @Test
    void decodesARealPng(@TempDir Path dir) throws Exception {
        // A real 2x2 PNG written via ImageIO (works under java.awt.headless=true), decoded by JavaFX.
        Path file = png(dir, "dot.png", 2, 2);
        ImageViewerPane pane = open(file, ViewerLoads.MAX_PIXELS);
        assertNotNull(pane.node());
        assertEquals("dot.png", pane.title());
        assertEquals(file, pane.getPath());
        assertTrue(pane.hasImage(), "a valid PNG decodes");
        assertEquals(2.0, FxTestSupport.callOnFx(() -> pane.imageForTest().getWidth()));
    }

    @Test
    void failsGracefullyOnCorruptImage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.png");
        Files.writeString(file, "this is not a PNG");
        ImageViewerPane pane = open(file, ViewerLoads.MAX_PIXELS);
        assertNotNull(pane.node(), "still renders (an error message), never throws");
        assertFalse(pane.hasImage(), "junk bytes don't decode");
        assertEquals(
                com.editora.i18n.Messages.tr("imageviewer.loadFailed"),
                FxTestSupport.callOnFx(() -> centerMessage(pane)),
                "the failure is shown in the tab");
    }

    @Test
    void theConstructorDoesNotDecodeOnTheCallingThread(@TempDir Path dir) throws Exception {
        Path file = png(dir, "later.png", 4, 4);
        // Hold the FX thread from construction until the check: the load can only have been applied if it
        // ran inside the constructor. It must not have — the tab shows a "loading" note instead.
        boolean[] decodedInConstructor = new boolean[1];
        String[] note = new String[1];
        ImageViewerPane pane = FxTestSupport.callOnFx(() -> {
            ImageViewerPane p = new ImageViewerPane(file);
            decodedInConstructor[0] = p.hasImage();
            note[0] = centerMessage(p);
            return p;
        });
        assertFalse(decodedInConstructor[0], "decoding happens on a worker, not in the constructor");
        assertEquals(com.editora.i18n.Messages.tr("imageviewer.loading"), note[0]);
        pane.loadedForTest().get(30, TimeUnit.SECONDS);
        assertTrue(pane.hasImage());
    }

    @Test
    void anImageOverThePixelCapIsDecodedReducedAndSaysSo(@TempDir Path dir) throws Exception {
        Path file = png(dir, "big.png", 400, 200); // 80 000 pixels
        ImageViewerPane pane = open(file, 5_000);
        assertTrue(pane.hasImage(), "it still opens");
        double[] size = FxTestSupport.callOnFx(() -> new double[] {
            pane.imageForTest().getWidth(), pane.imageForTest().getHeight()
        });
        assertTrue(size[0] * size[1] <= 5_000, "the bitmap respects the cap: " + size[0] + "x" + size[1]);
        assertEquals(2.0, size[0] / size[1], 0.05, "aspect ratio kept");
        String note = FxTestSupport.callOnFx(
                () -> ((javafx.scene.control.Label) FxTestSupport.field(pane, "noteLabel")).getText());
        assertEquals(com.editora.i18n.Messages.tr("imageviewer.reduced", 400, 200), note);

        ImageViewerPane whole = open(file, ViewerLoads.MAX_PIXELS);
        assertEquals(400.0, FxTestSupport.callOnFx(() -> whole.imageForTest().getWidth()));
        assertEquals(
                "",
                FxTestSupport.callOnFx(
                        () -> ((javafx.scene.control.Label) FxTestSupport.field(whole, "noteLabel")).getText()));
    }

    @Test
    void aDisposedPaneDropsItsLateResult(@TempDir Path dir) throws Exception {
        Path file = png(dir, "gone.png", 4, 4);
        ImageViewerPane pane = FxTestSupport.callOnFx(() -> {
            ImageViewerPane p = new ImageViewerPane(file);
            p.dispose(); // the tab closed before the worker finished
            return p;
        });
        pane.loadedForTest().get(30, TimeUnit.SECONDS);
        assertFalse(pane.hasImage(), "no image is attached to a closed tab");
    }

    @Test
    void headerDimensionsAndTheReducedSizeAreComputedWithoutDecoding() throws Exception {
        // A 20000×20000 one-bit PNG is tiny on disk but 1.6 GB as a 32-bit bitmap.
        java.awt.image.BufferedImage huge =
                new java.awt.image.BufferedImage(20_000, 20_000, java.awt.image.BufferedImage.TYPE_BYTE_BINARY);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(huge, "png", out);
        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length < 1_000_000, "small on disk: " + bytes.length);

        int[] size = ViewerLoads.dimensions(bytes);
        assertEquals(20_000, size[0]);
        assertEquals(20_000, size[1]);
        int[] reduced = ViewerLoads.reducedSize(size[0], size[1], ViewerLoads.MAX_PIXELS);
        assertEquals(8_000, reduced[0]);
        assertEquals(8_000, reduced[1]);
        assertTrue((long) reduced[0] * reduced[1] <= ViewerLoads.MAX_PIXELS);

        assertNull(ViewerLoads.reducedSize(4000, 3000, ViewerLoads.MAX_PIXELS), "a normal photo is decoded as is");
        assertNull(ViewerLoads.dimensions("not an image".getBytes()));
        int[] wide = ViewerLoads.reducedSize(100_000, 10, 1_000);
        assertTrue((long) wide[0] * wide[1] <= 1_000 && wide[1] >= 1, wide[0] + "x" + wide[1]);
    }

    /** The text of the message label shown in place of the image, or null when the image is showing. */
    private static String centerMessage(ImageViewerPane pane) {
        javafx.scene.Node center = ((javafx.scene.layout.BorderPane) pane.node()).getCenter();
        if (center instanceof javafx.scene.layout.StackPane stack
                && !stack.getChildren().isEmpty()
                && stack.getChildren().get(0) instanceof javafx.scene.control.Label label) {
            return label.getText();
        }
        return null;
    }
}
