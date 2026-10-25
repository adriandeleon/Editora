package com.editora.editor.undo;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A budget for the text that the undo queues of <em>all</em> documents retain together — the counterpart of
 * {@link UndoHistoryBudget} for the per-document {@link BudgetedChangeQueue}s.
 *
 * <p>Each queue is capped on its own (300 entries, 64 M chars), but a cap per document bounds nothing across
 * documents: a Replace All or a reformat on each of several multi-megabyte tabs can pin a large share of the
 * heap in removed-plus-inserted text. When the sum exceeds this budget the oldest entries of the <em>least
 * recently edited</em> queues go first — a tab last typed in an hour ago gives up its oldest steps before the
 * one being edited loses any — and no queue is ever taken below one undo step: its newest entry is what the
 * user just did, and the undo manager over it may already have answered "undo available".
 *
 * <p>Queues are held weakly: a document dropped without clearing its history must not be kept alive, or
 * counted, by this registry. All calls happen on the FX thread, where every edit is made.
 */
final class UndoQueueBudget {

    /** Shared by every document of every window: the same 64 M chars one queue alone may hold. */
    static final UndoQueueBudget APP = new UndoQueueBudget(CompletionUndoFactory.RETAINED_CHARS);

    private final long maxChars;
    private final Set<BudgetedChangeQueue<?>> queues = Collections.newSetFromMap(new WeakHashMap<>());
    private long clock;

    UndoQueueBudget(long maxChars) {
        this.maxChars = maxChars;
    }

    /** A new activity stamp; larger is more recent. */
    synchronized long tick() {
        return ++clock;
    }

    /**
     * Called after {@code active} took a push and applied its own caps: evicts the oldest undo entries of the
     * least recently active queues until the total fits, or every queue is down to one undo step.
     */
    synchronized void pushed(BudgetedChangeQueue<?> active) {
        queues.add(active);
        long total = 0;
        for (BudgetedChangeQueue<?> q : queues) {
            total += q.retained();
        }
        while (total > maxChars) {
            BudgetedChangeQueue<?> victim = null;
            for (BudgetedChangeQueue<?> q : queues) {
                if (q.canGiveUpAnUndoEntry() && (victim == null || q.lastActive() < victim.lastActive())) {
                    victim = q;
                }
            }
            if (victim == null) {
                return; // every queue is down to the one step its user just made
            }
            total -= victim.evictOldest();
        }
    }

    /** Text retained across every live queue, in chars (tests/diagnostics). */
    synchronized long retainedChars() {
        long total = 0;
        for (BudgetedChangeQueue<?> q : queues) {
            total += q.retained();
        }
        return total;
    }
}
