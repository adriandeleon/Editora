package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An image response says what it is, and Fit (the default) scales a picture down to the width, never up (U14). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpImageViewFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static HttpImageView inScene(double width) {
        HttpImageView view = new HttpImageView();
        new Scene(new StackPane(view), width, 300);
        settle(view);
        return view;
    }

    /** Two passes: the first sizes the viewport, the second lays out the picture fitted to it. */
    private static void settle(HttpImageView view) {
        for (int i = 0; i < 2; i++) {
            view.getScene().getRoot().applyCss();
            view.getScene().getRoot().layout();
        }
    }

    @Test
    void theCaptionNamesFormatPixelSizeAndBytes() throws Exception {
        assertEquals("PNG · 160 × 160 · 858 B", HttpImageView.caption("image/png", 160, 160, 858));
        assertEquals("JPEG · 4000 × 3000 · 1.5 MB", HttpImageView.caption("image/jpeg", 4000, 3000, 1_572_864));
        assertEquals("PNG", HttpImageView.format("IMAGE/PNG; charset=binary"));
        assertEquals("JPEG", HttpImageView.format("image/jpg"));
        assertEquals("ICO", HttpImageView.format("image/x-icon"));
        assertEquals("ICO", HttpImageView.format("image/vnd.microsoft.icon"));
        assertEquals("SVG", HttpImageView.format("image/svg+xml"));
        assertEquals("BMP", HttpImageView.format("image/x-ms-bmp"));
        assertEquals("IMAGE", HttpImageView.format(null));
        String shown = FxTestSupport.callOnFx(() -> {
            HttpImageView view = inScene(500);
            view.show(new WritableImage(160, 160), "image/png", 858);
            return view.captionForTest();
        });
        assertEquals("PNG · 160 × 160 · 858 B", shown);
    }

    @Test
    void fitScalesALargePictureDownAndOneHundredPercentShowsItsOwnSize() throws Exception {
        FxTestSupport.runOnFx(() -> {
            HttpImageView view = inScene(300);
            view.show(new WritableImage(800, 200), "image/png", 1000);
            settle(view);
            assertTrue(view.fitSelectedForTest(), "Fit is the default");
            double fitted = view.shownWidthForTest();
            assertTrue(fitted > 100 && fitted < 300, "scaled down to the width: " + fitted);

            view.actualSizeButtonForTest().fire();
            settle(view);
            assertFalse(view.fitSelectedForTest());
            assertEquals(800, view.shownWidthForTest(), 0.5, "100% is one image pixel per pixel");

            view.actualSizeButtonForTest().fire(); // clicking the active toggle does not leave both off
            settle(view);
            assertTrue(view.actualSizeButtonForTest().isSelected());

            view.fitButtonForTest().fire();
            settle(view);
            assertEquals(fitted, view.shownWidthForTest(), 0.5);
        });
    }

    @Test
    void fitNeverEnlargesASmallPicture() throws Exception {
        FxTestSupport.runOnFx(() -> {
            HttpImageView view = inScene(600);
            view.show(new WritableImage(40, 30), "image/gif", 90);
            settle(view);
            assertEquals(40, view.shownWidthForTest(), 0.5);
            view.clear();
            assertFalse(view.hasImage());
            assertEquals("", view.captionForTest());
        });
    }
}
