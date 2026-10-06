package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Shared background work for the read-only viewer tabs ({@link ImageViewerPane}, {@link HexViewerPane}):
 * the worker their file reads + decodes run on, and the pure sizing decisions for a large image.
 *
 * <p>Both viewers used to read and decode in their constructor, on the FX thread: a 40 MB JPEG, or anything
 * on a slow SFTP mount, froze the window, and session restore paid that for every image tab before the
 * window appeared. Two workers bound how many decodes run (and hold memory) at once; they are daemon threads
 * that time out when idle.
 */
final class ViewerLoads {

    /**
     * The most pixels an image is decoded to. Dimensions come from the file header, so the check costs
     * nothing — while a 60 KB, highly-compressed 20000×20000 PNG would otherwise decode to a 1.6 GB bitmap
     * (4 bytes per pixel) and take the whole editor down with an {@code OutOfMemoryError}. 64 MP is 256 MB,
     * and still more than any display shows.
     */
    static final long MAX_PIXELS = 64_000_000L;

    private static final ExecutorService EXEC = executor();

    private ViewerLoads() {}

    private static ExecutorService executor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "viewer-load");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Runs {@code work} on a viewer worker thread. */
    static void submit(Runnable work) {
        EXEC.execute(work);
    }

    /**
     * The pixel dimensions {@code [width, height]} of the image in {@code bytes}, read from its header without
     * decoding any pixel data; {@code null} when no installed reader recognizes the format.
     */
    static int[] dimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The size to decode a {@code width}×{@code height} image at so it holds at most {@code maxPixels}:
     * {@code null} when it already fits (decode as is), else the proportionally reduced {@code [w, h]}.
     */
    static int[] reducedSize(int width, int height, long maxPixels) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || pixels <= maxPixels) {
            return null;
        }
        double scale = Math.sqrt((double) maxPixels / pixels);
        int w = Math.max(1, (int) Math.floor(width * scale));
        int h = Math.max(1, (int) Math.floor(height * scale));
        // A sliver (100000×10) bottoms out at one pixel on its short side; trim the long side to still fit.
        if ((long) w * h > maxPixels) {
            if (h == 1) {
                w = (int) Math.max(1, Math.min(w, maxPixels));
            } else {
                h = (int) Math.max(1, Math.min(h, maxPixels / w));
            }
        }
        return new int[] {w, h};
    }
}
