package com.editora.ui;

import java.util.Collections;
import java.util.List;
import java.util.Set;
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

    /**
     * An animation that moves, fades or flashes part of the window: a timer that also knows the state it
     * leaves behind, which is what is shown in its place under reduced motion.
     */
    abstract static class Animation extends AnimationTimer {
        /** Shows the state the animation ends in, as if it had run. */
        protected abstract void atEnd();
    }

    /**
     * The animations running now. Held weakly, and nothing but the animation itself: a registry that kept an
     * end action for each would keep the animation's window alive through it for as long as the editor runs.
     */
    private static final Set<Animation> RUNNING = Collections.newSetFromMap(new WeakHashMap<>());

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
     * Starts {@code animation}, or with reduced motion shows its end state in its place. The end state is also
     * what is shown if the setting is turned on while it runs. FX thread.
     */
    static void start(Animation animation) {
        if (reduced()) {
            animation.stop();
            animation.atEnd();
            return;
        }
        listen();
        RUNNING.add(animation);
        animation.start();
    }

    /** {@code animation} ended on its own, or its owner stopped it: there is nothing left to stop. */
    static void ended(Animation animation) {
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
        List<Animation> running = List.copyOf(RUNNING);
        RUNNING.clear();
        for (Animation animation : running) {
            animation.stop();
            animation.atEnd();
        }
    }
}
