package com.editora.diff;

import java.util.List;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.patch.Patch;

/**
 * A Myers diff that gives up once the edit distance passes a budget. The search costs O(N + D²) in time and
 * memory, so two inputs that share little — a re-indented 40,000-line file, a minified bundle whose
 * identifiers were renamed — used to hold the window's single diff worker for tens of seconds. Callers fall
 * back to a linear comparison when this returns {@code null}.
 */
final class BoundedDiff {

    private BoundedDiff() {}

    /** Thrown from the progress callback to leave the search; stackless because it is control flow. */
    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** The patch from {@code left} to {@code right}, or {@code null} when it needs more than {@code maxEdits} edits. */
    static <T> Patch<T> diff(List<T> left, List<T> right, int maxEdits) {
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
}
