package com.editora.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which revision bodies the Local History index refers to, kept up to date as the index changes instead of
 * being recomputed from the whole index on every save.
 *
 * <p>Blob collection may delete a body only when no index that exists anywhere still lists it: not the one in
 * memory, not one queued for writing, not the one on disk. Walking every revision of every project to build
 * those sets on each save was a cost that grew with the history; here the index reports each row as it is
 * added or removed ({@link HistoryStore}), and the three sets collapse into two maps:
 *
 * <ul>
 *   <li>the hashes <b>listed</b> now, with how many rows list each;
 *   <li>the hashes <b>dropped</b> since the index on disk was written — still listed by that file, and by any
 *       snapshot queued before the row went. Each remembers the number of the first publication that does not
 *       contain it; once a publication with that number or a later one is on disk, the hash is free.
 * </ul>
 *
 * Thread-safe: the index changes on the FX thread, the protected set is read on the writer thread.
 */
final class HistoryHashLedger {

    private final Map<String, Integer> listed = new HashMap<>();
    private final Map<String, Long> dropped = new HashMap<>();
    /** The number the next {@link #publish()} hands out; publications are numbered from 1. */
    private long nextPublication = 1;

    synchronized void added(String hash) {
        if (hash != null && !hash.isEmpty()) {
            listed.merge(hash, 1, Integer::sum);
        }
    }

    synchronized void removed(String hash) {
        if (hash == null || hash.isEmpty()) {
            return;
        }
        Integer rows = listed.get(hash);
        if (rows == null) {
            return;
        }
        if (rows > 1) {
            listed.put(hash, rows - 1);
        } else {
            listed.remove(hash);
            dropped.put(hash, nextPublication); // the next snapshot is the first one without it
        }
    }

    /** Numbers the snapshot being taken now. Call with the snapshot, before the index changes again. */
    synchronized long publish() {
        return nextPublication++;
    }

    /** Publication {@code publication} is on disk: what was dropped before it was taken is listed nowhere. */
    synchronized void durable(long publication) {
        dropped.values().removeIf(first -> first <= publication);
    }

    /** Every hash a collection must keep: listed now, or dropped but possibly still listed on disk. */
    synchronized Set<String> protectedHashes() {
        Set<String> out = new HashSet<>(listed.keySet());
        out.addAll(dropped.keySet());
        return out;
    }

    /** The hashes listed now. */
    synchronized Set<String> listedHashes() {
        return new HashSet<>(listed.keySet());
    }

    synchronized boolean listsNothing() {
        return listed.isEmpty();
    }
}
