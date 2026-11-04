package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.dap.DapModels;

/**
 * Pure: the call stack as the Debug window lists it (review D105). js-debug sends every frame of Node.js's own
 * modules — 6 to 20 rows of {@code <anonymous>:1871, loader} under the user's two — marked de-emphasized
 * ({@link DapModels#HINT_SUBTLE}), with {@code await} label rows between async parts. A run of two or more such
 * rows becomes one {@link DapModels#HINT_FOLD} row ("… 9 Node.js frames"), as VS Code folds them; clicking it
 * shows them all. The selected frame is never folded away.
 */
final class StackFolding {

    private StackFolding() {}

    /** Id of a fold row; never an adapter's frame id. */
    static final int FOLD_ID = Integer.MIN_VALUE;

    /** The rows shown for {@code frames} and which of them is {@code selected} (an index into {@code frames}). */
    record Shown(List<DapModels.StackFrameInfo> rows, int selectedRow) {}

    /**
     * {@code frames} with runs of de-emphasized/label rows folded, unless {@code expanded}; the row index that shows
     * {@code frames[selected]}.
     */
    static Shown fold(List<DapModels.StackFrameInfo> frames, int selected, boolean expanded) {
        if (expanded || frames.isEmpty()) {
            return new Shown(List.copyOf(frames), selected);
        }
        List<DapModels.StackFrameInfo> rows = new ArrayList<>();
        int selectedRow = 0;
        int i = 0;
        while (i < frames.size()) {
            int end = i;
            while (end < frames.size() && foldable(frames.get(end)) && end != selected) {
                end++;
            }
            int run = end - i;
            boolean subtle = false;
            for (int k = i; k < end; k++) {
                subtle |= DapModels.HINT_SUBTLE.equals(frames.get(k).hint());
            }
            if (run >= 2 && subtle) {
                rows.add(foldRow(countSubtle(frames, i, end)));
                i = end;
                continue;
            }
            if (run == 0) {
                end = i + 1; // a normal (or the selected) frame
            }
            for (int k = i; k < end; k++) {
                if (k == selected) {
                    selectedRow = rows.size();
                }
                rows.add(frames.get(k));
            }
            i = end;
        }
        return new Shown(List.copyOf(rows), selectedRow);
    }

    /** Whether {@code f} is a fold row. */
    static boolean isFold(DapModels.StackFrameInfo f) {
        return f != null && DapModels.HINT_FOLD.equals(f.hint());
    }

    private static boolean foldable(DapModels.StackFrameInfo f) {
        return DapModels.HINT_SUBTLE.equals(f.hint()) || DapModels.HINT_LABEL.equals(f.hint());
    }

    private static int countSubtle(List<DapModels.StackFrameInfo> frames, int from, int to) {
        int n = 0;
        for (int k = from; k < to; k++) {
            if (!DapModels.HINT_LABEL.equals(frames.get(k).hint())) {
                n++;
            }
        }
        return n;
    }

    private static DapModels.StackFrameInfo foldRow(int count) {
        return new DapModels.StackFrameInfo(FOLD_ID, "", null, count, 0, null, DapModels.HINT_FOLD);
    }
}
