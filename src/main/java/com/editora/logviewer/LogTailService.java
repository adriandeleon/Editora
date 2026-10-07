package com.editora.logviewer;

import java.nio.charset.Charset;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
 *
 * <p>Like {@code tail -F}, a follow outlives its file: while a rotation has the name pointing at nothing, the
 * poll waits, and picks the new file up from its first byte when it appears.
 */
public final class LogTailService {

    /** Poll cadence — fast enough to feel live, slow enough to stay off the hot path. */
    private static final long POLL_MS = 500;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "log-tail");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<Runnable> deliver;
    private final long pollMillis;

    public LogTailService() {
        this(Platform::runLater, POLL_MS);
    }

    /** Test seam: where events are delivered, and how often the file is polled. */
    LogTailService(Consumer<Runnable> deliver, long pollMillis) {
        this.deliver = deliver;
        this.pollMillis = pollMillis;
    }

    /** What the file was when a chunk was read — the buffer's new on-disk snapshot. */
    public record FileState(long size, long modifiedMillis) {}

    /** Receives follow events on the FX thread. Nothing is delivered after {@link Handle#stop()}. */
    public interface Listener {
        /** New text appended since the last offset (never empty), with {@code \n} line endings. */
        void appended(String text, FileState file);

        /** The file was replaced or truncated (log rotation); {@code text} is the new file from its start. */
        void rotated(String text, FileState file);

        /** The file is gone for now (mid-rotation, or deleted); the follow keeps waiting for it. */
        default void missing() {}

        /** A read error occurred; the follow has stopped. */
        void error(String message);
    }

    /** A live follow; call {@link #stop()} to cancel it. */
    public final class Handle {
        private volatile ScheduledFuture<?> future;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicLong delivered;

        private Handle(long startOffset) {
            delivered = new AtomicLong(startOffset);
        }

        public void stop() {
            if (stopped.compareAndSet(false, true) && future != null) {
                future.cancel(false);
            }
        }

        public boolean isStopped() {
            return stopped.get();
        }

        /**
         * The byte offset up to which the file's text has reached the listener — where a later follow must
         * resume so that nothing is skipped and nothing is shown twice.
         */
        public long offset() {
            return delivered.get();
        }
    }

    /**
     * Starts following {@code file} from {@code startOffset} — the end of what the buffer already holds —
     * decoding with {@code charset}. Returns a {@link Handle}; events are delivered to {@code listener} on
     * the FX thread.
     */
    public Handle follow(Path file, long startOffset, Charset charset, Listener listener) {
        Handle handle = new Handle(startOffset);
        Poll poll = new Poll(file, startOffset, charset, listener, handle);
        handle.future = scheduler.scheduleWithFixedDelay(poll, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        if (handle.isStopped()) {
            handle.future.cancel(false); // stopped between construction and scheduling
        }
        return handle;
    }

    /** One follow's poll. Its fields are touched by the poll thread only. */
    private final class Poll implements Runnable {
        private final Path file;
        private final Charset charset;
        private final Listener listener;
        private final Handle handle;

        private long offset;
        private Object fileKey;
        private boolean missing;
        /** The last chunk ended in a lone {@code \r}: it is half of a {@code \r\n} until proven otherwise. */
        private boolean pendingCr;

        Poll(Path file, long startOffset, Charset charset, Listener listener, Handle handle) {
            this.file = file;
            this.offset = startOffset;
            this.charset = charset;
            this.listener = listener;
            this.handle = handle;
        }

        @Override
        public void run() {
            if (handle.isStopped()) {
                return;
            }
            try {
                LogTail.Append a = LogTail.readAppended(file, offset, LogTail.FOLLOW_BYTES, charset, fileKey);
                boolean reset = a.reset() || missing;
                missing = false;
                fileKey = a.fileKey();
                if (!reset && a.offset() == offset) {
                    return;
                }
                offset = a.offset();
                if (reset) {
                    pendingCr = false;
                }
                String text = toLf(a.text());
                long reached = offset;
                FileState state = new FileState(a.size(), a.modifiedMillis());
                if (reset) {
                    post(() -> listener.rotated(text, state), reached);
                } else if (!text.isEmpty()) {
                    post(() -> listener.appended(text, state), reached);
                }
            } catch (NoSuchFileException gone) {
                // Mid-rotation (the old file renamed, the new one not created yet) or deleted: keep waiting.
                // Whatever appears under this name next is a new file, read from its first byte.
                if (!missing) {
                    missing = true;
                    offset = 0;
                    fileKey = null;
                    post(listener::missing, -1);
                }
            } catch (Throwable e) {
                // Catch Throwable, not Exception: an Error escaping a scheduleWithFixedDelay body cancels
                // the recurring task, silently stopping the tail. Report it and stop instead.
                String detail = e.getMessage() == null || e.getMessage().isBlank() ? "" : ": " + e.getMessage();
                String message = e.getClass().getSimpleName() + detail;
                deliver.accept(() -> {
                    if (!handle.isStopped()) {
                        handle.stop();
                        listener.error(message);
                    }
                });
            }
        }

        private void post(Runnable event, long reached) {
            deliver.accept(() -> {
                if (!handle.isStopped()) { // a read in flight when the follow stopped is not delivered
                    if (reached >= 0) {
                        handle.delivered.set(reached);
                    }
                    event.run();
                }
            });
        }

        /** {@code \r\n} and {@code \r} become {@code \n}, as the editor's own loader does — across chunks too. */
        private String toLf(String chunk) {
            String text = pendingCr ? "\r" + chunk : chunk;
            pendingCr = false;
            if (text.indexOf('\r') < 0) {
                return text;
            }
            if (text.charAt(text.length() - 1) == '\r') {
                pendingCr = true; // its \n may be the first byte of the next read
                text = text.substring(0, text.length() - 1);
            }
            return text.replace("\r\n", "\n").replace('\r', '\n');
        }
    }

    /** Shuts the poll thread down (window close). */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
