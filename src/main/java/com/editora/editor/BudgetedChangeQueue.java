package com.editora.editor;

import java.util.ArrayList;
import java.util.function.ToLongFunction;

import org.fxmisc.undo.impl.ChangeQueue;

/**
 * An undo queue bounded by entry count <em>and</em> by the text its entries retain.
 *
 * <p>UndoFX's fixed-size queue caps only the count. An entry holds the text it removed and the text it
 * inserted, so 300 entries can be 300 keystrokes or 300 rewrites of a multi-megabyte selection; the count
 * says nothing about memory. This queue also evicts its oldest entries once the retained characters exceed a
 * budget. It keeps the fixed-size queue's contract otherwise: a push drops the redo entries, and a queue
 * position stays valid exactly as long as the change before it is still in the queue.
 *
 * <p>Unlike that queue it releases what it forgets: {@code forgetHistory} there only moves indices, leaving
 * a loaded document's whole text reachable from the ring until 300 later edits overwrite the slot.
 */
final class BudgetedChangeQueue<C> implements ChangeQueue<C> {

    private record Entry<C>(C change, long revision, long cost) {}

    private final int capacity;
    private final long budget;
    private final ToLongFunction<? super C> cost;
    /** The budget shared with every other document's queue, or null for a queue bounded on its own only. */
    private final UndoQueueBudget shared;

    private final ArrayList<Entry<C>> entries = new ArrayList<>();
    /** Number of entries before the position: undo walks down from here, redo up. */
    private int position;

    private long retained;
    private long revision;
    /** Revision of the (no longer held) change before entry 0 — what position 0 is identified by. */
    private long zeroRevision;
    /** The shared budget's stamp of the last push; larger is more recent. */
    private long lastActive;

    /**
     * @param capacity most entries kept
     * @param budget most retained cost kept, as measured by {@code cost}; the newest entry is always kept
     * @param cost the text an entry retains (removed plus inserted characters)
     */
    BudgetedChangeQueue(int capacity, long budget, ToLongFunction<? super C> cost) {
        this(capacity, budget, cost, null);
    }

    /**
     * As above, also counted against {@code shared}, which may evict this queue's oldest undo entries when the
     * queues of every document together exceed it (see {@link UndoQueueBudget}).
     */
    BudgetedChangeQueue(int capacity, long budget, ToLongFunction<? super C> cost, UndoQueueBudget shared) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.budget = budget;
        this.cost = cost;
        this.shared = shared;
    }

    @Override
    public boolean hasNext() {
        return position < entries.size();
    }

    @Override
    public boolean hasPrev() {
        return position > 0;
    }

    @Override
    public C peekNext() {
        return entries.get(position).change();
    }

    @Override
    public C next() {
        return entries.get(position++).change();
    }

    @Override
    public C peekPrev() {
        return entries.get(position - 1).change();
    }

    @Override
    public C prev() {
        return entries.get(--position).change();
    }

    @Override
    public void forgetHistory() {
        if (position == 0) {
            return;
        }
        zeroRevision = revisionBefore(position);
        for (int i = 0; i < position; i++) {
            retained -= entries.get(i).cost();
        }
        entries.subList(0, position).clear();
        position = 0;
    }

    @Override
    @SafeVarargs
    public final void push(C... changes) {
        for (int i = entries.size() - 1; i >= position; i--) { // a new change replaces the redo entries
            retained -= entries.remove(i).cost();
        }
        for (C change : changes) {
            long changeCost = Math.max(0, cost.applyAsLong(change));
            entries.add(new Entry<>(change, ++revision, changeCost));
            retained += changeCost;
        }
        position = entries.size();
        // Oldest first; the newest entry stays whatever it costs, so the last edit can always be undone.
        while (entries.size() > 1 && (entries.size() > capacity || retained > budget)) {
            evictOldest();
        }
        if (shared != null) {
            lastActive = shared.tick();
            shared.pushed(this); // then the app-wide cap, which takes from the least recently edited documents
        }
    }

    /**
     * Drops the oldest entry — an undo entry, so the position moves down with it — and returns the cost it
     * released. The caller makes sure there is one to drop ({@link #canGiveUpAnUndoEntry}).
     */
    long evictOldest() {
        Entry<C> evicted = entries.removeFirst();
        retained -= evicted.cost();
        zeroRevision = evicted.revision();
        position--;
        return evicted.cost();
    }

    /** Whether the shared budget may take this queue's oldest entry: it keeps at least one undo step. */
    boolean canGiveUpAnUndoEntry() {
        return position > 1;
    }

    /** The shared budget's stamp of the last push (larger is more recent). */
    long lastActive() {
        return lastActive;
    }

    @Override
    public QueuePosition getCurrentPosition() {
        return new Position(revisionBefore(position));
    }

    /** Entries currently held (undo and redo sides together). */
    int size() {
        return entries.size();
    }

    /** Cost currently retained across all entries. */
    long retained() {
        return retained;
    }

    private long revisionBefore(int index) {
        return index == 0 ? zeroRevision : entries.get(index - 1).revision();
    }

    /** A position is the change before it; it is valid while that change (or the queue start) is still there. */
    private final class Position implements QueuePosition {
        private final long before;

        Position(long before) {
            this.before = before;
        }

        @Override
        public boolean isValid() {
            if (before == zeroRevision) {
                return true;
            }
            for (int i = entries.size() - 1; i >= 0; i--) { // revisions ascend: stop once past it
                long r = entries.get(i).revision();
                if (r == before) {
                    return true;
                }
                if (r < before) {
                    return false;
                }
            }
            return false;
        }

        private BudgetedChangeQueue<C> queue() {
            return BudgetedChangeQueue.this;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BudgetedChangeQueue<?>.Position that
                    && that.queue() == queue()
                    && that.before == before;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(before);
        }
    }
}
