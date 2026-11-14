package com.editora.ui;

import java.util.Map;
import java.util.WeakHashMap;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

/**
 * The one place an animation that moves, fades or flashes part of the window starts from, so that it follows
 * the system's reduced-motion setting: while that is on, the animation does not run and its end state is
 * shown at once, and turning the setting on stops whatever is running the same way. Sliding and fading can
 * make a person dizzy or sick, and they turned motion off in the system for that reason.
 *
 * <p>A timer that only measures time (an elapsed-seconds label, a debounce, a repaint that coalesces work) moves
 * nothing and does not start here.
 */
final class Motion {

    /** The animations running now, each with the action that shows its end state. */
    private static final Map<AnimationTimer, Runnable> RUNNING = new WeakHashMap<>();

    private static boolean listening;

    /** A test's answer for {@link #reduced}, since the system's setting cannot be changed from a test; null: ask the system. */
    private static @Nullable Boolean reducedForTest;

    static void setReducedForTest(@Nullable Boolean reduced) {
        reducedForTest = reduced;
    }

    private Motion() {}

    /** Whether the system asks for reduced motion. False where the platform cannot say. */
    static boolean reduced() {
        Boolean forTest = reducedForTest;
        if (forTest != null) {
            return forTest;
        }
        try {
            return Platform.getPreferences().isReducedMotion();
        } catch (RuntimeException | LinkageError noToolkit) {
            return false;
        }
    }

    /**
     * Starts {@code animation}, or with reduced motion runs {@code atEnd} in its place: the state the animation
     * would leave behind. {@code atEnd} is also what stops it if the setting is turned on while it runs. FX thread.
     */
    static void start(AnimationTimer animation, Runnable atEnd) {
        if (reduced()) {
            animation.stop();
            atEnd.run();
            return;
        }
        listen();
        RUNNING.put(animation, atEnd);
        animation.start();
    }

    /** {@code animation} ended on its own: there is nothing left to stop. */
    static void ended(AnimationTimer animation) {
        RUNNING.remove(animation);
    }

    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        try {
            Platform.getPreferences().reducedMotionProperty().addListener((obs, was, now) -> {
                if (now) {
                    stopAll();
                }
            });
        } catch (RuntimeException | LinkageError noToolkit) {
            // No preferences on this platform: the setting never changes.
        }
    }

    /** Stops every running animation and shows its end state: the setting applies at once. */
    static void stopAll() {
        Map<AnimationTimer, Runnable> running = Map.copyOf(RUNNING);
        RUNNING.clear();
        running.forEach((animation, atEnd) -> {
            animation.stop();
            atEnd.run();
        });
    }
}
