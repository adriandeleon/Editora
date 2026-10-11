package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/**
 * Decides which restored tabs get their editor when, once the window is on screen.
 *
 * <p>A session restore creates only the selected tab's editor before the first frame; every other saved file
 * is a placeholder tab until this schedule reaches it. Entries are built in {@code first}-then-tab order, one
 * batch per painted frame, and a batch keeps building until the pulse's time budget is spent (at least one
 * entry per batch, so a slow machine still makes progress). An entry can also be <em>claimed</em> out of
 * order — the user clicked its tab, or a workspace edit needs its buffer — after which the schedule skips it.
 *
 * <p>Pure: it holds indices and a clock, never a tab, so the policy is unit-tested without a toolkit.
 */
final class RestoreSchedule {

    /** How much of a pulse a batch may spend building editors: about half a 60 Hz frame. */
    static final long DEFAULT_BUDGET_NANOS = 8_000_000L;

    private final int[] order;
    private final boolean[] claimed;
    private int cursor;

    /**
     * @param count the number of restored entries
     * @param first the entry built first (the selected tab), or a negative value for plain tab order
     */
    RestoreSchedule(int count, int first) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        order = new int[count];
        claimed = new boolean[count];
        int n = 0;
        if (first >= 0 && first < count) {
            order[n++] = first;
        }
        for (int i = 0; i < count; i++) {
            if (i != first) {
                order[n++] = i;
            }
        }
    }

    /** The entry the schedule builds first, or {@code -1} when there is none. */
    int first() {
        return order.length == 0 ? -1 : order[0];
    }

    /** Number of entries. */
    int size() {
        return order.length;
    }

    /** Marks {@code index} as built (in order or not). Returns false when it already was. */
    boolean claim(int index) {
        if (index < 0 || index >= claimed.length || claimed[index]) {
            return false;
        }
        claimed[index] = true;
        return true;
    }

    boolean isClaimed(int index) {
        return index >= 0 && index < claimed.length && claimed[index];
    }

    /** True once every entry has been claimed. */
    boolean isExhausted() {
        return nextUnclaimed() < 0;
    }

    /** How many entries are still waiting. */
    int remaining() {
        int n = 0;
        for (boolean c : claimed) {
            if (!c) {
                n++;
            }
        }
        return n;
    }

    /** The next entry in fill order that nobody has claimed, or {@code -1}. */
    int nextUnclaimed() {
        while (cursor < order.length && claimed[order[cursor]]) {
            cursor++;
        }
        return cursor < order.length ? order[cursor] : -1;
    }

    /**
     * Claims and builds one pulse's worth of entries: {@code build} is called for each, in fill order, until
     * {@code clock} (nanoseconds) says {@code budgetNanos} have passed since the batch started. At least one
     * entry is built per call while any remains, whatever the budget. Returns the entries for which
     * {@code build} answered true — the ones that started a load the caller has to wait for.
     */
    List<Integer> batch(LongSupplier clock, long budgetNanos, IntPredicate build) {
        List<Integer> started = new ArrayList<>();
        long start = clock.getAsLong();
        while (true) {
            int index = nextUnclaimed();
            if (index < 0) {
                break;
            }
            claimed[index] = true;
            if (build.test(index)) {
                started.add(index);
            }
            if (clock.getAsLong() - start >= budgetNanos) {
                break;
            }
        }
        return started;
    }
}
