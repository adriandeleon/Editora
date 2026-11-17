package com.editora.perf;

import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Disk work a command needs before it can go on, done off the JavaFX thread (PERF-01, Pauta 6.0.1): a stat, a
 * listing, a read, a move, a delete. The work runs on a virtual thread named for it, and its answer, or the
 * exception it threw, comes back on the FX thread, where the command goes on. A command waiting for its answer
 * shows it is busy the way it already did and refuses a second press itself.
 *
 * <p>{@link BlockingSites} hears the name on the thread the work runs on, so a test can tell where it ran.
 */
public final class OffFx {

    private OffFx() {}

    /**
     * Where the work runs: a virtual thread named for it. TEST SEAM — a test may run it in place ({@code (name,
     * task) -> task.run()}): called on the FX thread, the answer then lands before the call returns.
     */
    private static volatile BiConsumer<String, Runnable> runner =
            (name, task) -> Thread.ofVirtual().name(name).start(task);

    /** TEST SEAM — replaces {@link #runner}; {@code null} restores the virtual thread. */
    public static void runnerForTest(@Nullable BiConsumer<String, Runnable> newRunner) {
        runner = newRunner != null
                ? newRunner
                : (name, task) -> Thread.ofVirtual().name(name).start(task);
    }

    /** Runs {@code work} on a thread called {@code name}, then {@code then} with its answer on the FX thread. */
    public static <T> void then(String name, Callable<T> work, Consumer<Answer<T>> then) {
        runner.accept(name, () -> {
            BlockingSites.enter(name);
            Answer<T> answer;
            try {
                answer = new Answer<>(work.call(), null);
            } catch (Exception | LinkageError e) {
                answer = new Answer<>(null, e);
            }
            Answer<T> done = answer;
            onFx(() -> then.accept(done));
        });
    }

    /** As {@link #then} for work with no answer: {@code then} gets the failure, or null when it went through. */
    public static void run(String name, Work work, Consumer<@Nullable Throwable> then) {
        then(
                name,
                () -> {
                    work.run();
                    return Boolean.TRUE;
                },
                a -> then.accept(a.failure()));
    }

    /** Work that may throw and answers nothing. */
    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    /** Runs {@code r} on the FX thread: now when already there, else later. */
    public static void onFx(Runnable r) {
        boolean fx;
        try {
            fx = javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            fx = false;
        }
        if (fx) {
            r.run();
        } else {
            javafx.application.Platform.runLater(r);
        }
    }

    /** What the work answered: its value, or why it could not. */
    public record Answer<T>(@Nullable T value, @Nullable Throwable failure) {

        public boolean ok() {
            return failure == null;
        }

        /** The value, or {@code fallback} when the work failed or answered null. */
        public T or(T fallback) {
            T v = value;
            return failure == null && v != null ? v : fallback;
        }

        /** The failure's message, for the status line ("" when it went through). */
        public String why() {
            Throwable f = failure;
            return f == null ? "" : String.valueOf(f.getMessage());
        }
    }
}
