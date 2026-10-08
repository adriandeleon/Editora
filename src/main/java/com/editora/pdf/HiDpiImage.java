package com.editora.pdf;

import javafx.scene.image.WritableImage;

/**
 * A snapshot that knows its density: {@link #pixelScale()} image pixels per logical pixel. The Project Map
 * renders its output at up to 2×; print and PDF lay the image out at its logical size and keep the extra
 * pixels as resolution. Travels wherever a plain {@code Image} does, so the callbacks in between need not
 * know about it.
 */
public final class HiDpiImage extends WritableImage {
    private final double pixelScale;

    public HiDpiImage(int width, int height, double pixelScale) {
        super(width, height);
        this.pixelScale = pixelScale > 0 && Double.isFinite(pixelScale) ? pixelScale : 1;
    }

    public double pixelScale() {
        return pixelScale;
    }

    /** The density of {@code image}: its own when it is a {@link HiDpiImage}, else 1. */
    public static double scaleOf(javafx.scene.image.Image image) {
        return image instanceof HiDpiImage h ? h.pixelScale() : 1;
    }
}
