package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.image.Image;

import org.jspecify.annotations.Nullable;

/**
 * The app's bundled icons ({@code icons/icon-<size>.png}), read and decoded once. The first window reads them
 * as it is built, which PERF-01 (Pauta 6.2.0) allows of the resources the first window is built from; every
 * later window, and every Welcome tab, takes the same images from here instead of reading them again.
 */
final class AppIcons {

    /** The window icon sizes, smallest first. */
    static final int[] WINDOW_SIZES = {16, 32, 48, 128, 256, 512};

    private static final Map<Integer, Optional<Image>> IMAGES = new ConcurrentHashMap<>();

    private AppIcons() {}

    /** The icon of {@code size} pixels, or {@code null} when it is not bundled. Any thread; read once. */
    static @Nullable Image icon(int size) {
        return IMAGES.computeIfAbsent(size, AppIcons::read).orElse(null);
    }

    /**
     * Reads and decodes the window icons on a thread of their own, for the first window: {@code App.start} starts it
     * while it waits for the config prefetch, and the window then finds them read (a window asking first waits for
     * the one being read). About 30 ms of PNG decoding, the 512 and 256 pixel icons most of it, that the first
     * window's build did on the FX thread (startup R3). The toolkit must be running.
     */
    static void readAhead() {
        Thread.ofVirtual().name("app-icons").start(() -> {
            for (int size : WINDOW_SIZES) {
                icon(size);
            }
        });
    }

    /** Forgets the icons read, so a test sees them read again. */
    static void forgetForTest() {
        IMAGES.clear();
    }

    private static Optional<Image> read(int size) {
        com.editora.perf.BlockingSites.enter("AppIcons.read");
        try (InputStream in = AppIcons.class.getResourceAsStream("/com/editora/icons/icon-" + size + ".png")) {
            return in == null ? Optional.empty() : Optional.of(new Image(in));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
