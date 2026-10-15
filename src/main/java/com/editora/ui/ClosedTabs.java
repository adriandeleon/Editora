package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The files whose tabs the user closed in one window, newest first, for Buffer: Reopen Closed Tab. Bounded;
 * closing a file again moves it to the top rather than listing it twice. Kept in memory only — a window that
 * closes forgets them. Pure.
 */
final class ClosedTabs {

    /** A closed file and where its caret was. */
    record Entry(Path path, int caret) {}

    static final int DEFAULT_CAPACITY = 20;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final int capacity;

    ClosedTabs() {
        this(DEFAULT_CAPACITY);
    }

    ClosedTabs(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Records that {@code path}'s tab was closed with the caret at {@code caret}. */
    void closed(Path path, int caret) {
        if (path == null) {
            return;
        }
        forget(path);
        entries.addFirst(new Entry(path, Math.max(0, caret)));
        while (entries.size() > capacity) {
            entries.removeLast();
        }
    }

    /** The most recently closed file, removed from the list; null when there is none. */
    Entry pop() {
        return entries.pollFirst();
    }

    /** Drops {@code path} from the list (it was opened again some other way). */
    void forget(Path path) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().path().equals(path)) {
                it.remove();
            }
        }
    }

    int size() {
        return entries.size();
    }
}
