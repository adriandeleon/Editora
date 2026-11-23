package com.editora.ui;

/**
 * When a light Git refresh trigger (a tab switch, a save, an autosave) may keep the status it has: within
 * {@link #WINDOW_NANOS} of the last full refresh, it diffs only the active file, and a full refresh follows at the
 * end of the window, so a burst of triggers costs one {@code git status} per window and the last one is never
 * dropped (F2, perf review 2026-11-23). Pure: the caller passes the clock.
 */
final class GitRefreshCoalescer {

    /** How long a full refresh covers the light triggers after it. */
    static final long WINDOW_NANOS = 2_000_000_000L;

    private boolean started;
    private long lastFull;

    /** A full refresh ({@code git status}) was started at {@code now}. */
    void fullStarted(long now) {
        started = true;
        lastFull = now;
    }

    /**
     * For a light trigger at {@code now}: {@code -1} when it should run a full refresh now, else how long until the
     * full refresh that ends the window is due (the caller schedules it once and diffs only the active file now).
     */
    long lightDelay(long now) {
        if (!started) {
            return -1;
        }
        long since = now - lastFull;
        return since < 0 || since >= WINDOW_NANOS ? -1 : WINDOW_NANOS - since;
    }
}
