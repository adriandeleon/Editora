package com.editora.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * What a Local History index publication that was <em>superseded</em> is told. A newer snapshot of the index
 * replaced it in the write queue before its turn, so it never reached the disk by itself — but the snapshot that
 * replaced it holds every row it held (rows leave the index only by a deliberate removal), so its caller's
 * question "is my revision on disk?" is answered by the publication that replaced it: true once that one (or a
 * later one) is written, false when it fails. Answering "no" on the spot refused an agent write, a Replace in
 * Files or a delete whenever any other history save landed in between.
 *
 * <p>Publications are numbered in the order they are taken ({@link HistoryHashLedger#publish}); the writer
 * finishes them in that order. Thread-safe; the answers are handed back to be run outside the lock.
 */
final class HistoryPublicationAnswers {

    private long latestFinished;
    private boolean latestDurable;
    private final TreeMap<Long, List<Consumer<Boolean>>> waiting = new TreeMap<>();

    /**
     * Publication {@code publication} was superseded. Answers at once ({@code non-null}) when a later
     * publication has already finished; otherwise {@code answer} waits for the next one that does.
     */
    synchronized @Nullable Boolean superseded(long publication, Consumer<Boolean> answer) {
        if (latestFinished > publication) {
            return latestDurable;
        }
        waiting.computeIfAbsent(publication, k -> new ArrayList<>()).add(answer);
        return null;
    }

    /**
     * Publication {@code publication} was written ({@code durable}) or failed: returns the answers of the
     * superseded publications taken before it, which its outcome is the outcome of.
     */
    synchronized List<Consumer<Boolean>> finished(long publication, boolean durable) {
        if (publication > latestFinished) {
            latestFinished = publication;
            latestDurable = durable;
        }
        List<Consumer<Boolean>> due = new ArrayList<>();
        Map<Long, List<Consumer<Boolean>>> earlier = waiting.headMap(publication, false);
        earlier.values().forEach(due::addAll);
        earlier.clear();
        return due;
    }
}
