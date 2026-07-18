package com.editora.logviewer;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;

/**
 * Drives {@code tail -f} for the log viewer: polls a file off the FX thread, reads only the bytes
 * written since the last offset (via {@link LogTail#readAppended}), and posts the appended text — or a
 * rotation/truncation signal — back to a {@link Listener} on the FX thread. Mirrors the {@code GitService}
 * idiom (a single daemon executor; results marshaled with {@link Platform#runLater}).
 *
 * <p>One {@link Handle} per followed buffer; {@link Handle#stop()} cancels just that follow, and
 * {@link #shutdown()} tears the whole service down when the window closes. The poll only ever does a
 * {@code stat} + a small delta read, so an idle (not-yet-grown) file costs almost nothing per tick.
 */
public final class LogTailService {

    /** Poll cadence — fast enough to feel live, slow enough to stay off the hot path. */
    private static final long POLL_MS = 500;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "log-tail");
        t.setDaemon(true);
        return t;
    });

    /** Receives follow events on the FX thread. */
    public interface Listener {
        /** New bytes appended since the last offset (never empty). */
        void appended(String text);

        /** The file shrank (log rotation/truncation); {@code fullText} is the new (small) file content. */
        void rotated(String fullText);

        /** A read error occurred; the follow has stopped. */
        void error(String message);
    }

    /** A live follow; call {@link #stop()} to cancel it. */
    public final class Handle {
        private final ScheduledFuture<?> future;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private Handle(ScheduledFuture<?> future) {
            this.future = future;
        }

        public void stop() {
            if (stopped.compareAndSet(false, true)) {
                future.cancel(false);
            }
        }

        public boolean isStopped() {
            return stopped.get();
        }
    }

    /**
     * Starts following {@code file} from {@code startOffset} (typically EOF at the moment the user turned
     * Follow on). Returns a {@link Handle}; events are delivered to {@code listener} on the FX thread.
     */
    public Handle follow(Path file, long startOffset, Listener listener) {
        AtomicLong offset = new AtomicLong(startOffset);
        AtomicBoolean done = new AtomicBoolean();
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> {
                    if (done.get()) {
                        return;
                    }
                    try {
                        LogTail.Append a = LogTail.readAppended(file, offset.get());
                        if (a.reset()) {
                            offset.set(a.offset());
                            String text = a.text();
                            Platform.runLater(() -> listener.rotated(text));
                        } else if (!a.text().isEmpty()) {
                            offset.set(a.offset());
                            String text = a.text();
                            Platform.runLater(() -> listener.appended(text));
                        }
                    } catch (Throwable e) {
                        // Catch Throwable, not Exception: an Error escaping a scheduleWithFixedDelay body cancels
                        // the recurring task, silently stopping the tail. Report it and mark done instead.
                        done.set(true);
                        String message = e.getMessage() == null ? e.toString() : e.getMessage();
                        Platform.runLater(() -> listener.error(message));
                    }
                },
                POLL_MS,
                POLL_MS,
                TimeUnit.MILLISECONDS);
        return new Handle(future);
    }

    /** Shuts the poll thread down (window close). */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
