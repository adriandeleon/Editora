package com.editora.ui;

/**
 * Pure pacing decision for work that is requested far more often than it is worth doing: run the first
 * request of a burst at once, then at most one run per interval for as long as requests keep coming.
 *
 * <p>The two usual alternatives each fail one way. Running on every request does the work hundreds of times
 * for one visible result. A trailing-edge debounce (restart a timer on every request) shows nothing at all
 * while requests keep arriving faster than the delay — a steady stream starves it. Here the leading edge
 * keeps the first result prompt and the fixed interval is the longest anything waits.
 *
 * <p>Not thread-safe; times are {@link System#nanoTime()} values supplied by the caller.
 */
final class ThrottlePolicy {

    /** {@link #request} answer: a run is already due, nothing more to schedule. */
    static final long ALREADY_SCHEDULED = -1;

    private final long intervalNanos;
    private long lastRun;
    private boolean hasRun;
    private boolean scheduled;

    ThrottlePolicy(long intervalNanos) {
        this.intervalNanos = Math.max(0, intervalNanos);
    }

    /**
     * Records a request made at {@code now}.
     *
     * @return {@link #ALREADY_SCHEDULED}, or how long from {@code now} the run it schedules is due — zero
     *     when the last run is at least an interval ago (or there was none)
     */
    long request(long now) {
        if (scheduled) {
            return ALREADY_SCHEDULED;
        }
        scheduled = true;
        if (!hasRun) {
            return 0;
        }
        long since = now - lastRun;
        return since >= intervalNanos ? 0 : intervalNanos - since;
    }

    /** Whether a requested run has not happened yet. */
    boolean scheduled() {
        return scheduled;
    }

    /** Records that the scheduled run happened at {@code now}. */
    void ran(long now) {
        scheduled = false;
        hasRun = true;
        lastRun = now;
    }

    /** Drops a scheduled run without running it. */
    void cancel() {
        scheduled = false;
    }
}
