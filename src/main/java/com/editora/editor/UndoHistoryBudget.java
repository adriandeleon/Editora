package com.editora.editor;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A budget for the document text that the {@link UndoHistory} of <em>all</em> buffers retains together.
 *
 * <p>Each history is capped on its own (50 checkpoints, 16 M chars), but checkpoints are whole documents and
 * nothing bounded their sum: ten edited tabs of a few hundred KB each held 160–320 MB of snapshots. When the
 * sum exceeds this budget the oldest checkpoints of the <em>least recently edited</em> buffers go first — a
 * tab last touched an hour ago gives up its timeline before the one being typed in loses a step — and the
 * newest checkpoint of the history being added to is never evicted.
 *
 * <p>Histories are held weakly: a buffer dropped without {@code dispose()} must not be kept alive, or
 * counted, by this registry.
 */
final class UndoHistoryBudget {

    /** Shared by every buffer of every window. 64 M chars is 64–128 MB of snapshot text at most. */
    static final UndoHistoryBudget APP = new UndoHistoryBudget(64_000_000L);

    private final long maxChars;
    private final Set<UndoHistory> histories = Collections.newSetFromMap(new WeakHashMap<>());
    private long clock;

    UndoHistoryBudget(long maxChars) {
        this.maxChars = maxChars;
    }

    /** A new activity stamp; larger is more recent. */
    synchronized long tick() {
        return ++clock;
    }

    /**
     * Called after {@code active} gained a checkpoint: evicts oldest checkpoints from the least recently
     * active histories until the total fits (or only {@code active}'s newest checkpoint is left to take).
     */
    synchronized void added(UndoHistory active) {
        histories.add(active);
        long total = 0;
        for (UndoHistory h : histories) {
            total += h.retainedChars();
        }
        while (total > maxChars) {
            UndoHistory victim = null;
            for (UndoHistory h : histories) {
                if (h.size() > (h == active ? 1 : 0) && (victim == null || h.lastActive() < victim.lastActive())) {
                    victim = h;
                }
            }
            if (victim == null) {
                return; // nothing left but the checkpoint just added
            }
            total -= victim.evictOldest();
        }
    }

    /** A cleared history (its buffer was closed, reloaded or narrowed) no longer counts. */
    synchronized void cleared(UndoHistory history) {
        histories.remove(history);
    }

    /** Text retained across every live history, in chars (tests/diagnostics). */
    synchronized long retainedChars() {
        long total = 0;
        for (UndoHistory h : histories) {
            total += h.retainedChars();
        }
        return total;
    }
}
