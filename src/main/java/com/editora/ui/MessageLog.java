package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * An in-memory, session-only history of the status-bar echo-area messages. Not persisted — it exists
 * only for the running session so the user can click the status message and review what scrolled past.
 *
 * <p>Bounded to {@link #MAX_ENTRIES} (oldest dropped) so a long session can't grow memory without
 * bound. Blank/null messages (used to <em>clear</em> the echo) are not recorded. {@link #entries()}
 * returns a snapshot ordered newest-first, which is how the popup lists them.
 *
 * <p>Pure model (no JavaFX), unit-tested for the cap, blank-skip, and ordering.
 */
public final class MessageLog {

    /** Maximum retained messages; older ones are evicted. */
    public static final int MAX_ENTRIES = 200;

    /** How much the message matters. Drives its styling and whether it counts as unread. */
    public enum Severity {
        INFO,
        WARN,
        ERROR
    }

    /** One logged message: its wall-clock time (epoch millis), text and severity. */
    public record Entry(long epochMillis, String text, Severity severity) {
        public Entry {
            severity = severity == null ? Severity.INFO : severity;
        }
    }

    // Insertion order (oldest first); we evict from the head and append at the tail.
    private final Deque<Entry> entries = new ArrayDeque<>();

    /**
     * Errors recorded since the log was last read.
     *
     * <p>The status bar shows one message at a time and never clears it — it is <em>replaced</em> by the next
     * one. So a failure that lands while the user is typing is overwritten a moment later by something
     * routine and is gone with no trace they would notice. Counting unread errors is what lets the status bar
     * keep a marker up until someone has actually looked, without hijacking the echo line to do it.
     */
    private int unreadErrors;

    /** Records {@code message} at {@code epochMillis}; no-ops for null/blank. Evicts the oldest past the cap. */
    public void add(String message, long epochMillis) {
        add(message, Severity.INFO, epochMillis);
    }

    /** Records {@code message} with an explicit severity. */
    public void add(String message, Severity severity, long epochMillis) {
        if (message == null || message.isBlank()) {
            return;
        }
        entries.addLast(new Entry(epochMillis, message, severity));
        if (severity == Severity.ERROR) {
            unreadErrors++;
        }
        while (entries.size() > MAX_ENTRIES) {
            // Evicting an unread error would lose the marker for a failure nobody has seen, so the count
            // deliberately survives eviction: it tracks "something went wrong that you have not looked at",
            // not "this specific entry is still retained".
            entries.removeFirst();
        }
    }

    /** Records {@code message} at the current wall-clock time. */
    public void add(String message) {
        add(message, System.currentTimeMillis());
    }

    /** Errors recorded since {@link #markRead()}. */
    public int unreadErrors() {
        return unreadErrors;
    }

    /** Clears the unread-error count — called when the user opens the log, i.e. has had a chance to see. */
    public void markRead() {
        unreadErrors = 0;
    }

    /** A snapshot of the messages, newest first. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries);
        Collections.reverse(out);
        return out;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        unreadErrors = 0; // clearing the log is an explicit "I have seen these"
    }
}
