package com.editora.ui;

import java.util.Locale;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

/**
 * Whether JavaFX is drawing on the CPU because its GPU pipeline could not start.
 *
 * <p>Editora asks for a GPU pipeline first ({@code -Dprism.order=es2,sw} on Linux, {@code d3d,es2,sw} on Windows,
 * {@code mtl,es2,sw} on macOS). When that pipeline fails to start, JavaFX quietly falls back to its software
 * renderer, and the whole window draws on the CPU: menus, scrolling and typing lag, with nothing anywhere saying
 * why. On Linux a usual cause is a graphics driver upgraded without a reboot, so its libraries no longer match the
 * loaded kernel module and OpenGL cannot create a context; others are a remote X display, a virtual machine, or a
 * driver that is missing.
 *
 * <p>Detected through public API: only the software pipeline lacks {@link ConditionalFeature#SCENE3D}. The pipeline
 * is chosen once, when the toolkit starts, so the answer is taken once and kept.
 */
final class RenderPipeline {

    /** The scene-root style class app.css keys the cheaper software-mode styling on (no popup shadow). */
    static final String SOFTWARE_STYLE = "software-rendering";

    private static volatile @Nullable Boolean softwareFallback;

    private RenderPipeline() {}

    /**
     * True when JavaFX fell back to software rendering although a GPU pipeline was asked for. The first call must
     * come after the toolkit started; any thread after that.
     */
    static boolean softwareFallback() {
        Boolean known = softwareFallback;
        if (known == null) {
            known = fellBack(
                    System.getProperty("prism.order"),
                    System.getProperty("glass.platform"),
                    Platform.isSupported(ConditionalFeature.SCENE3D));
            softwareFallback = known;
        }
        return known;
    }

    /**
     * Whether a toolkit without 3D ({@code scene3d} false) means a fallback: not when software was asked for first
     * ({@code prism.order} starting with {@code sw}, as the tests and the screenshot harness ask), and not on the
     * headless or Monocle glass, which have no GPU to fall back from. An unset order is JavaFX's default, which
     * starts with a GPU pipeline. Pure.
     */
    static boolean fellBack(@Nullable String prismOrder, @Nullable String glassPlatform, boolean scene3d) {
        if (scene3d) {
            return false;
        }
        if (glassPlatform != null) {
            String glass = glassPlatform.trim().toLowerCase(Locale.ROOT);
            if (glass.equals("headless") || glass.equals("monocle")) {
                return false;
            }
        }
        if (prismOrder == null || prismOrder.isBlank()) {
            return true;
        }
        String first = prismOrder.split(",", -1)[0].trim().toLowerCase(Locale.ROOT);
        return !first.equals("sw") && !first.equals("j2d");
    }
}
