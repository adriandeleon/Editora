package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import org.jspecify.annotations.Nullable;

/**
 * The files whose tabs the user closed in one window, newest first, for Buffer: Reopen Closed Tab. Bounded;
 * closing a file again moves it to the top rather than listing it twice. Kept in memory only — a window that
 * closes forgets them. Pure.
 */
final class ClosedTabs {

    /**
     * A closed file, where its caret was, and whether the tab showed its bytes ({@code hex}): a file opened as
     * hex comes back as hex. An image or a PDF needs no mark, since its name decides its viewer.
     */
    record Entry(Path path, int caret, boolean hex) {}

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
    void closed(@Nullable Path path, int caret) {
        closed(path, caret, false);
    }

    /** As {@link #closed(Path, int)}; {@code hex} when the tab was the file's hex view. */
    void closed(@Nullable Path path, int caret, boolean hex) {
        if (path == null) {
            return;
        }
        forget(path);
        entries.addFirst(new Entry(path, Math.max(0, caret), hex));
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
