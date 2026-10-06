package com.editora.process;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

/**
 * Streams a child process's stdout/stderr to the JavaFX thread without letting a chatty process flood it.
 * The one pump behind both streaming services — {@code run.RunService} (a program's console) and
 * {@code build.BuildService} (a build tool's) — so the two cannot drift apart again: the build side once
 * posted one {@code Platform.runLater} per line with no bound at all, and {@code mvn -X} stalled the window
 * and its own Stop button.
 *
 * <p>What it guarantees:
 *
 * <ul>
 *   <li><b>A bounded queue.</b> At most {@value #MAX_PENDING_OUTPUT_EVENTS} lines /
 *       {@value #MAX_PENDING_OUTPUT_CHARS} characters wait for the FX thread. What happens at the bound is
 *       the owner's {@link Overflow} choice.
 *   <li><b>Batched, paced delivery.</b> One drain delivers at most {@value #MAX_EVENTS_PER_PULSE} lines /
 *       {@value #MAX_CHARS_PER_PULSE} characters and then yields the FX thread, and drains start at least
 *       {@value #MIN_DRAIN_INTERVAL_MILLIS} ms apart — about one a frame — so input and repaints interleave
 *       with a flood. The lines of a drain are delivered inside an {@link OutputBatch}, which lets a console
 *       show them with a single edit.
 *   <li><b>A bounded line.</b> No line longer than {@value #MAX_OUTPUT_LINE_CHARS} characters is ever
 *       materialized; the excess is dropped and the line is marked.
 *   <li><b>Ordered exit.</b> {@link #finish} joins the reader threads, and {@link #post} queues behind every
 *       line already read, so an exit event can never overtake the output that preceded it.
 *   <li><b>No late delivery.</b> Everything carries the generation of the run it belongs to; {@link #begin}
 *       and {@link #cancel} invalidate whatever is still queued.
 * </ul>
 */
public final class OutputPump {

    /** What a full queue does to the reader thread. */
    public enum Overflow {
        /**
         * Discard the oldest queued line and tell the listener once. The child is never slowed down — right
         * for a program's console, where the newest output is what matters and nothing parses the stream.
         */
        DROP_OLDEST,
        /**
         * Make the reader wait for the FX thread, which stops it reading the pipe and so applies ordinary
         * pipe backpressure to the child. Nothing is lost — required where the stream is <em>parsed</em>
         * (Go/Cargo/TAP test results), since a dropped line there is a test that silently vanishes.
         */
        BLOCK
    }

    /** Receives the streamed output, always on the FX thread. */
    @FunctionalInterface
    public interface Sink {
        /** One complete line ({@code stderr} true for the error stream). */
        void line(String text, boolean stderr);

        /** Output flushed before a newline, such as a prompt waiting for console input. */
        default void partial(String text, boolean stderr) {
            line(text, stderr);
        }
    }

    /** The line delivered in place of output dropped under {@link Overflow#DROP_OLDEST}. */
    public static final String OUTPUT_DROPPED = "[output truncated while the UI was busy]";

    /** Appended to a line cut at {@link #MAX_OUTPUT_LINE_CHARS}. */
    public static final String LINE_TRUNCATED = " … [line truncated]";

    private static final int MAX_PENDING_OUTPUT_CHARS = 256 * 1024;
    private static final int MAX_PENDING_OUTPUT_EVENTS = 2_048;
    /** Counted in bytes as read, so a line is never more characters than this either. */
    private static final int MAX_OUTPUT_LINE_CHARS = 64 * 1024;

    private static final int MAX_EVENTS_PER_PULSE = 256;
    private static final int MAX_CHARS_PER_PULSE = 64 * 1024;

    /**
     * The least time between the starts of two drains: one frame at 60 Hz. Drains used to be chained back to
     * back for as long as anything was queued, so a program that printed steadily owned the FX thread; a
     * console cannot show more than a frame's worth at a time anyway. The first line after a quiet spell is
     * still delivered at once.
     */
    public static final int MIN_DRAIN_INTERVAL_MILLIS = 16;

    private static final long MIN_DRAIN_INTERVAL_NANOS = MIN_DRAIN_INTERVAL_MILLIS * 1_000_000L;

    /** Wakes the FX thread for a drain that has to wait out {@link #MIN_DRAIN_INTERVAL_MILLIS}. */
    private static final class Pacer {
        static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "output-pump-pacer");
            t.setDaemon(true);
            return t;
        });
    }

    /** How long {@link #finish} waits for a reader that is not making progress before closing its stream. */
    private static final long FINISH_GRACE_NANOS = 1_000_000_000L;

    private record PendingFx(int generation, int chars, boolean output, Runnable action) {}

    /** One reader thread and the stream it drains. */
    public static final class Feed {
        private final Thread thread;
        private final InputStream stream;
        /** True while the reader is parked on a full queue — waiting for the UI, not for the pipe. */
        private volatile boolean waitingForUi;

        private Feed(Thread thread, InputStream stream) {
            this.thread = thread;
            this.stream = stream;
        }
    }

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(OutputPump.class.getName());

    private final String threadPrefix;
    private final Overflow overflow;
    private final boolean flushPartialLines;

    private volatile int generation;
    private final Object lock = new Object();
    private final ArrayDeque<PendingFx> pending = new ArrayDeque<>();
    private int pendingOutputChars;
    private int pendingOutputEvents;
    private boolean drainScheduled;
    /** When the last drain started ({@link System#nanoTime}); guarded by {@link #lock}. */
    private long lastDrainNanos = System.nanoTime() - MIN_DRAIN_INTERVAL_NANOS;

    private Runnable droppedNotice;

    /**
     * @param threadPrefix names the reader threads ({@code <prefix>-stdout} / {@code <prefix>-stderr})
     * @param overflow what a full queue does
     * @param flushPartialLines deliver output that stops short of a newline (an interactive prompt) as
     *     {@link Sink#partial}. Off where every delivered string must be a whole line; a bare carriage
     *     return then ends a line as well (a console keeps it, to show progress the way it was written).
     */
    public OutputPump(String threadPrefix, Overflow overflow, boolean flushPartialLines) {
        this.threadPrefix = threadPrefix;
        this.overflow = overflow;
        this.flushPartialLines = flushPartialLines;
    }

    /** Starts a new run: whatever is still queued for the previous one is discarded. Returns its generation. */
    public int begin() {
        return invalidate();
    }

    /** Final owner shutdown: discards callbacks queued for a window that is closing and releases the readers. */
    public void cancel() {
        invalidate();
    }

    private int invalidate() {
        synchronized (lock) {
            int next = ++generation;
            pending.clear();
            pendingOutputChars = 0;
            pendingOutputEvents = 0;
            droppedNotice = null;
            lock.notifyAll(); // a reader parked on the old run's full queue must not wait forever
            return next;
        }
    }

    /** Drains {@code in} on a daemon thread, delivering to {@code sink} on the FX thread. */
    public Feed start(InputStream in, boolean stderr, int gen, Sink sink) {
        Feed[] self = new Feed[1];
        Thread t = new Thread(() -> read(self[0], stderr, gen, sink), threadPrefix + (stderr ? "-stderr" : "-stdout"));
        t.setDaemon(true);
        self[0] = new Feed(t, in);
        t.start();
        return self[0];
    }

    /**
     * Reads to end of stream without ever materializing more than one bounded line.
     *
     * <p>The stream is read as <em>bytes</em> and each line decoded on its own ({@link ChildText}): a child in
     * the user's locale may write UTF-8 or the native encoding, and a line is the unit that can be told apart.
     * A newline byte is the same in every encoding a console uses.
     */
    private void read(Feed self, boolean stderr, int gen, Sink sink) {
        try (InputStream in = self.stream) {
            byte[] chunk = new byte[8_192];
            byte[] line = new byte[1_024];
            int length = 0;
            boolean truncated = false;
            boolean afterCr = false; // whole-line mode: the last line ended at a CR, so a following LF is its pair
            int read;
            while ((read = in.read(chunk)) != -1) {
                for (int i = 0; i < read; i++) {
                    byte b = chunk[i];
                    if (b == '\n') {
                        if (afterCr) {
                            afterCr = false; // CRLF: the line was already delivered at the CR
                            continue;
                        }
                        emitLine(self, line, length, truncated, stderr, gen, sink);
                        length = 0;
                        truncated = false;
                        continue;
                    }
                    afterCr = false;
                    if (b == '\r' && !flushPartialLines) {
                        // A bare CR ends a line too, as BufferedReader.readLine() has it: Maven's download
                        // progress is CR-terminated, and held until the next LF it showed nothing during a
                        // long download and then arrived as one line past the cap.
                        emitLine(self, line, length, truncated, stderr, gen, sink);
                        length = 0;
                        truncated = false;
                        afterCr = true;
                    } else if (length < MAX_OUTPUT_LINE_CHARS) {
                        if (length == line.length) {
                            line = java.util.Arrays.copyOf(line, Math.min(MAX_OUTPUT_LINE_CHARS, length * 2));
                        }
                        line[length++] = b;
                    } else {
                        truncated = true;
                    }
                }
                // A prompt often ends without a newline and then waits for stdin. Wait briefly
                // before flushing so a long line arriving in several reads stays one bounded
                // event, while an interactive prompt becomes visible before input is sent.
                if (flushPartialLines && length > 0 && in.available() == 0) {
                    try {
                        Thread.sleep(75);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (in.available() == 0) {
                        int held = ChildText.incompleteUtf8Tail(line, length); // never split a character
                        if (length > held) {
                            String text = ChildText.decode(line, 0, length - held) + (truncated ? LINE_TRUNCATED : "");
                            enqueue(self, gen, text.length(), true, () -> sink.partial(text, stderr), sink, stderr);
                            System.arraycopy(line, length - held, line, 0, held);
                            length = held;
                            truncated = false;
                        }
                    }
                }
            }
            if (length > 0 || truncated) {
                emitLine(self, line, length, truncated, stderr, gen, sink);
            }
        } catch (IOException ignored) {
            // Stream closed as the process ended — nothing to report.
        }
    }

    private void emitLine(Feed self, byte[] line, int length, boolean truncated, boolean stderr, int gen, Sink sink) {
        if (length > 0 && line[length - 1] == '\r') {
            length--; // match BufferedReader.readLine() for CRLF
        }
        if (truncated) {
            length -= ChildText.incompleteUtf8Tail(line, length); // the cap may have cut a character in two
        }
        String text = ChildText.decode(line, 0, length) + (truncated ? LINE_TRUNCATED : "");
        enqueue(self, gen, text.length(), true, () -> sink.line(text, stderr), sink, stderr);
    }

    /**
     * Waits for {@code feeds} to reach end of stream, so nothing the process wrote is still in a pipe when
     * its exit is reported. Call from the thread that waited for the process, never the FX thread.
     *
     * <p>Bounded: a descendant that inherited the pipe can hold it open long after the root exited, so a
     * reader that makes no progress for a second has its stream closed. A reader parked on a full queue is
     * making progress at the UI's pace and is waited for.
     */
    public void finish(Feed... feeds) {
        for (Feed feed : feeds) {
            finish(feed);
        }
    }

    private static void finish(Feed reader) {
        long deadline = System.nanoTime() + FINISH_GRACE_NANOS;
        while (reader.thread.isAlive()) {
            if (!join(reader.thread, 50)) {
                return; // interrupted: the caller is being torn down
            }
            if (reader.waitingForUi) {
                deadline = System.nanoTime() + FINISH_GRACE_NANOS;
            } else if (System.nanoTime() >= deadline) {
                break;
            }
        }
        if (!reader.thread.isAlive()) {
            return;
        }
        try {
            reader.stream.close(); // a descendant may still hold the pipe open after the root exits
        } catch (IOException ignored) {
            // best effort
        }
        join(reader.thread, 1_000); // bounded again; no output is intentionally accepted after the exit event
    }

    /** False when interrupted. */
    private static boolean join(Thread thread, long millis) {
        try {
            thread.join(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Queues a non-output event (the exit notification) behind every line already queued for {@code gen}. */
    public void post(int gen, Runnable action) {
        enqueue(null, gen, 0, false, action, null, false);
    }

    private void enqueue(Feed reader, int gen, int chars, boolean output, Runnable action, Sink sink, boolean stderr) {
        boolean schedule = false;
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            if (output && overflow == Overflow.BLOCK && !awaitRoom(reader, gen, chars)) {
                return;
            }
            pending.addLast(new PendingFx(gen, chars, output, action));
            pendingOutputChars += chars;
            if (output) {
                pendingOutputEvents++;
            }
            while (sink != null
                    && (pendingOutputChars > MAX_PENDING_OUTPUT_CHARS
                            || pendingOutputEvents > MAX_PENDING_OUTPUT_EVENTS)) {
                PendingFx removed = removeOldestOutput(); // DROP_OLDEST only: BLOCK made room above
                if (removed == null) {
                    break;
                }
                pendingOutputChars -= removed.chars();
                pendingOutputEvents--;
                droppedNotice = () -> sink.line(OUTPUT_DROPPED, stderr);
            }
            if (!drainScheduled) {
                drainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) {
            scheduleDrain();
        }
    }

    /** Hands the next drain to the FX thread, no sooner than a frame after the last one started. */
    private void scheduleDrain() {
        long wait;
        synchronized (lock) {
            wait = lastDrainNanos + MIN_DRAIN_INTERVAL_NANOS - System.nanoTime();
        }
        if (wait <= 0) {
            Platform.runLater(this::drain);
        } else {
            Pacer.TIMER.schedule(() -> Platform.runLater(this::drain), wait, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Parks the reader until its line fits. Called with {@link #lock} held. False when the run was
     * superseded or the thread interrupted while waiting. A line is always admitted into an empty queue, so
     * one line longer than the character bound cannot park forever.
     */
    private boolean awaitRoom(Feed reader, int gen, int chars) {
        while (gen == generation
                && pendingOutputEvents > 0
                && (pendingOutputEvents >= MAX_PENDING_OUTPUT_EVENTS
                        || pendingOutputChars + chars > MAX_PENDING_OUTPUT_CHARS)) {
            reader.waitingForUi = true;
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                reader.waitingForUi = false;
            }
        }
        return gen == generation;
    }

    private PendingFx removeOldestOutput() {
        Iterator<PendingFx> it = pending.iterator();
        while (it.hasNext()) {
            PendingFx event = it.next();
            if (event.output()) {
                it.remove();
                return event;
            }
        }
        return null;
    }

    /** FX thread: delivers one bounded batch, then yields. */
    private void drain() {
        List<PendingFx> batch = new ArrayList<>();
        Runnable notice;
        boolean more;
        synchronized (lock) {
            lastDrainNanos = System.nanoTime();
            notice = droppedNotice;
            droppedNotice = null;
            int chars = 0;
            while (!pending.isEmpty() && batch.size() < MAX_EVENTS_PER_PULSE) {
                PendingFx next = pending.peekFirst();
                if (!batch.isEmpty() && chars + next.chars() > MAX_CHARS_PER_PULSE) {
                    break;
                }
                pending.removeFirst();
                pendingOutputChars -= next.chars();
                if (next.output()) {
                    pendingOutputEvents--;
                }
                chars += next.chars();
                batch.add(next);
            }
            more = !pending.isEmpty();
            drainScheduled = more;
            lock.notifyAll(); // room for a reader parked under Overflow.BLOCK
        }
        // A callback that throws must not end the pump: `drainScheduled` is already set for the batch after
        // this one, so skipping the reschedule below would leave it set forever — no further output for
        // this run or any later one, and under BLOCK a reader (and so the child) parked on a full queue.
        // Output is delivered inside an OutputBatch so a console applies the drain's lines as one edit. An
        // exit event is not: it runs with every line before it already on screen, as it always has (it may
        // start the next run, or open a dialog and so a nested event loop).
        boolean batching = false;
        try {
            if (notice != null) {
                OutputBatch.begin();
                batching = true;
                deliver(notice);
            }
            for (PendingFx event : batch) {
                if (event.generation() != generation) {
                    continue;
                }
                if (event.output() != batching) {
                    if (batching) {
                        OutputBatch.end();
                    } else {
                        OutputBatch.begin();
                    }
                    batching = event.output();
                }
                deliver(event.action());
            }
        } finally {
            if (batching) {
                OutputBatch.end();
            }
            if (more) {
                scheduleDrain();
            }
        }
    }

    private static void deliver(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, "A process-output callback failed; continuing", e);
        }
    }
}
