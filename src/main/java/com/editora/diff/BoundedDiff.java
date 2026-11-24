package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.patch.ChangeDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeleteDelta;
import com.github.difflib.patch.InsertDelta;
import com.github.difflib.patch.Patch;
import org.jspecify.annotations.Nullable;

/**
 * A Myers diff that gives up once the edit distance passes a budget. The search costs O(N + D²) in time and
 * memory, so two inputs that share little — a re-indented 40,000-line file, a minified bundle whose
 * identifiers were renamed — used to hold the window's single diff worker for tens of seconds. Callers fall
 * back to a linear comparison when this returns {@code null}, or take {@link #orChangedMiddle}'s coarse patch.
 * Every Myers line diff in the app goes through here.
 */
public final class BoundedDiff {

    private BoundedDiff() {}

    /**
     * The line-edit budget of a whole-file diff. The search does about D²/2 steps for D edits, each a path
     * node to allocate: 4,000 edits take about 80 ms, 20,000 (the old budget) took 1 to 3 seconds and
     * 80,000 took 25 (2026-11-24, two 60,000-line texts with no line in common, warm JIT).
     */
    public static final int MAX_LINE_EDITS = 4_000;

    /** Thrown from the progress callback to leave the search; stackless because it is control flow. */
    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** The patch from {@code left} to {@code right}, or {@code null} when it needs more than {@code maxEdits} edits. */
    public static <T> @Nullable Patch<T> diff(List<T> left, List<T> right, int maxEdits) {
        if (Math.abs(left.size() - right.size()) > maxEdits) {
            return null; // the length difference alone is a lower bound on the edit distance
        }
        try {
            return DiffUtils.diff(left, right, new DiffAlgorithmListener() {
                @Override
                public void diffStart() {}

                @Override
                public void diffStep(int value, int max) {
                    if (value > maxEdits) {
                        throw new BudgetExceeded();
                    }
                }

                @Override
                public void diffEnd() {}
            });
        } catch (BudgetExceeded tooFarApart) {
            return null;
        }
    }

    /**
     * The patch from {@code left} to {@code right} within {@link #MAX_LINE_EDITS}, else {@link #changedMiddle}.
     * Never null; a valid patch either way, only coarser past the budget.
     */
    public static <T> Patch<T> orChangedMiddle(List<T> left, List<T> right) {
        Patch<T> patch = diff(left, right, MAX_LINE_EDITS);
        return patch != null ? patch : changedMiddle(left, right);
    }

    /**
     * One delta replacing everything between the two sides' common prefix and common suffix: a valid, if
     * coarse, patch for texts too far apart to search. Linear.
     */
    public static <T> Patch<T> changedMiddle(List<T> left, List<T> right) {
        int max = Math.min(left.size(), right.size());
        int prefix = 0;
        while (prefix < max && left.get(prefix).equals(right.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && left.get(left.size() - 1 - suffix).equals(right.get(right.size() - 1 - suffix))) {
            suffix++;
        }
        Chunk<T> source = new Chunk<>(prefix, new ArrayList<>(left.subList(prefix, left.size() - suffix)));
        Chunk<T> target = new Chunk<>(prefix, new ArrayList<>(right.subList(prefix, right.size() - suffix)));
        Patch<T> patch = new Patch<>();
        if (source.size() > 0 && target.size() > 0) {
            patch.addDelta(new ChangeDelta<>(source, target));
        } else if (source.size() > 0) {
            patch.addDelta(new DeleteDelta<>(source, target));
        } else if (target.size() > 0) {
            patch.addDelta(new InsertDelta<>(source, target));
        }
        return patch;
    }
}
