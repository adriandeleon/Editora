package com.editora.dap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Carries debug-adapter {@code output} events to the FX thread in bounded batches.
 *
 * <p>Each event used to be its own {@code Platform.runLater} plus its own console append. A debuggee
 * printing in a loop sends thousands of events a second, so the FX queue filled with appends — each one a
 * rich-text edit, a restyle and a scroll — and the editor stopped responding until the program paused. This
 * mirrors the Run console's pump ({@code run/RunService}): events wait in a bounded queue, one drain is
 * scheduled at a time, a drain takes a bounded slice, and neighbouring events of the same kind are joined so
 * a slice costs one append rather than hundreds. When the debuggee outruns the UI the oldest waiting output
 * is dropped and a single notice says so.
 *
 * <p>Pure of JavaFX: the FX hand-off is the {@code schedule} function, so the batching is unit-tested with a
 * plain queue. {@link #offer} may be called from any thread; the sink only ever runs inside a scheduled
 * drain or {@link #flush}.
 */
final class DapOutputPump {

    static final int MAX_PENDING_CHARS = 256 * 1024;
    static final int MAX_PENDING_EVENTS = 2_048;
    static final int MAX_EVENT_CHARS = 64 * 1024;
    static final int MAX_EVENTS_PER_PULSE = 256;
    static final int MAX_CHARS_PER_PULSE = 64 * 1024;
    static final String DROPPED = "[output truncated while the UI was busy]\n";
    static final String EVENT_TRUNCATED = " … [output truncated]\n";

    /** Receives a batch of output for one session and category. */
    interface Sink {
        void accept(long epoch, String text, String category);
    }

    private record Chunk(long epoch, String text, String category) {}

    private final Consumer<Runnable> schedule;
    private final Sink sink;

    private final Object lock = new Object();
    private final ArrayDeque<Chunk> pending = new ArrayDeque<>();
    private int pendingChars;
    private boolean drainScheduled;
    private long droppedEpoch = -1;
    private boolean dropped;

    DapOutputPump(Consumer<Runnable> schedule, Sink sink) {
        this.schedule = schedule;
        this.sink = sink;
    }

    /** Queues one output event of session {@code epoch}; never blocks and never runs the sink. */
    void offer(long epoch, String text, String category) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String bounded = text.length() > MAX_EVENT_CHARS ? text.substring(0, MAX_EVENT_CHARS) + EVENT_TRUNCATED : text;
        boolean scheduleDrain = false;
        synchronized (lock) {
            pending.addLast(new Chunk(epoch, bounded, category));
            pendingChars += bounded.length();
            while (pending.size() > 1 && (pendingChars > MAX_PENDING_CHARS || pending.size() > MAX_PENDING_EVENTS)) {
                Chunk oldest = pending.removeFirst();
                pendingChars -= oldest.text().length();
                dropped = true;
                droppedEpoch = oldest.epoch();
            }
            if (!drainScheduled) {
                drainScheduled = true;
                scheduleDrain = true;
            }
        }
        if (scheduleDrain) {
            schedule.accept(this::drain);
        }
    }

    /** FX thread: delivers one bounded slice and reschedules itself while output is still waiting. */
    private void drain() {
        boolean more = deliver(MAX_EVENTS_PER_PULSE, MAX_CHARS_PER_PULSE);
        if (more) {
            schedule.accept(this::drain);
        }
    }

    /**
     * FX thread: delivers everything waiting, now. Called before a session ends so the program's last lines
     * are shown rather than discarded with the session that produced them.
     */
    void flush() {
        deliver(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** Takes up to the given slice off the queue and hands it to the sink; true when output remains. */
    private boolean deliver(int maxEvents, int maxChars) {
        List<Chunk> batch = new ArrayList<>();
        boolean notice;
        long noticeEpoch;
        boolean more;
        synchronized (lock) {
            notice = dropped;
            noticeEpoch = droppedEpoch;
            dropped = false;
            int chars = 0;
            while (!pending.isEmpty() && batch.size() < maxEvents) {
                Chunk next = pending.peekFirst();
                if (!batch.isEmpty() && (long) chars + next.text().length() > maxChars) {
                    break;
                }
                pending.removeFirst();
                pendingChars -= next.text().length();
                chars += next.text().length();
                batch.add(next);
            }
            more = !pending.isEmpty();
            drainScheduled = more && maxEvents != Integer.MAX_VALUE;
        }
        if (notice) {
            sink.accept(noticeEpoch, DROPPED, "console");
        }
        // Join neighbours that belong to the same session and category: one append for the whole run.
        int i = 0;
        while (i < batch.size()) {
            Chunk first = batch.get(i);
            int j = i + 1;
            while (j < batch.size() && sameRun(first, batch.get(j))) {
                j++;
            }
            if (j == i + 1) {
                sink.accept(first.epoch(), first.text(), first.category());
            } else {
                StringBuilder joined = new StringBuilder();
                for (int k = i; k < j; k++) {
                    joined.append(batch.get(k).text());
                }
                sink.accept(first.epoch(), joined.toString(), first.category());
            }
            i = j;
        }
        return more;
    }

    private static boolean sameRun(Chunk a, Chunk b) {
        return a.epoch() == b.epoch() && java.util.Objects.equals(a.category(), b.category());
    }
}
