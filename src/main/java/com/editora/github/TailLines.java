package com.editora.github;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Keeps the last {@code limit} lines of a stream of any length. A failed CI log's failure is at its
 * <em>end</em>; capturing the first megabytes and tailing those loses exactly that. Thread-safe: stdout and
 * stderr are read on separate threads. Unit-tested.
 */
public final class TailLines {

    private final int limit;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private long seen;

    public TailLines(int limit) {
        this.limit = Math.max(1, limit);
    }

    public synchronized void add(String line) {
        seen++;
        if (lines.size() == limit) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    /** The kept lines, oldest first. */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    /** Whether earlier lines were dropped to stay within the limit. */
    public synchronized boolean truncated() {
        return seen > limit;
    }
}
