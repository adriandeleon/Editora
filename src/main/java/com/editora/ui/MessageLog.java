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

    /** One logged message: its wall-clock time (epoch millis) and text. */
    public record Entry(long epochMillis, String text) {}

    // Insertion order (oldest first); we evict from the head and append at the tail.
    private final Deque<Entry> entries = new ArrayDeque<>();

    /** Records {@code message} at {@code epochMillis}; no-ops for null/blank. Evicts the oldest past the cap. */
    public void add(String message, long epochMillis) {
        if (message == null || message.isBlank()) {
            return;
        }
        entries.addLast(new Entry(epochMillis, message));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    /** Records {@code message} at the current wall-clock time. */
    public void add(String message) {
        add(message, System.currentTimeMillis());
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
    }
}
