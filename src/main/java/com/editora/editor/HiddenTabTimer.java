package com.editora.editor;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

/**
 * Runs one action on the FX thread once a tab has stayed in the background for a delay — and not at all if
 * the tab is shown again first. {@code EditorBuffer} uses it to give back what a hidden tab holds only for
 * drawing and can rebuild (see {@code EditorBuffer.releaseHiddenCaches}).
 *
 * <p>The wait is a {@link CompletableFuture#delayedExecutor} timer: no thread, no pulse receiver (a
 * {@code PauseTransition} would keep the animation timer pulsing for every hidden tab) and nothing to stop
 * — a show in the meantime bumps the generation, and the stale callback finds it changed and does nothing.
 * The waiting task holds this timer, and through it the buffer, only weakly: a tab closed while a wait is
 * pending stays collectable ({@code BufferReleasedOnCloseFxTest}). FX-thread confined, like the buffer that
 * owns it.
 */
final class HiddenTabTimer {

    private final Duration delay;
    private final Runnable action;
    private final Function<Duration, Executor> timers;
    private final Consumer<Runnable> fxThread;
    private long generation;

    HiddenTabTimer(Duration delay, Runnable action) {
        this(
                delay,
                action,
                d -> CompletableFuture.delayedExecutor(d.toMillis(), TimeUnit.MILLISECONDS),
                HiddenTabTimer::onFxThread);
    }

    /** The seams: {@code timers} supplies the executor that waits out a delay, {@code fxThread} hops back. */
    HiddenTabTimer(Duration delay, Runnable action, Function<Duration, Executor> timers, Consumer<Runnable> fxThread) {
        this.delay = delay;
        this.action = action;
        this.timers = timers;
        this.fxThread = fxThread;
    }

    private static void onFxThread(Runnable r) {
        try {
            Platform.runLater(r);
        } catch (IllegalStateException toolkitGone) {
            // The application exited while a tab's wait was pending: nothing left to release.
        }
    }

    /** Starts (or restarts) the wait: the action runs after the delay unless {@link #cancel} comes first. */
    void start() {
        long mine = ++generation;
        WeakReference<HiddenTabTimer> self = new WeakReference<>(this);
        timers.apply(delay).execute(() -> {
            HiddenTabTimer timer = self.get();
            if (timer == null) {
                return; // the buffer was closed and collected while the wait was pending
            }
            timer.fxThread.accept(() -> {
                if (mine == timer.generation) {
                    timer.action.run();
                }
            });
        });
    }

    /** Forgets a pending wait; a callback already on its way finds the generation changed and does nothing. */
    void cancel() {
        generation++;
    }
}
