package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import com.editora.diff.DiffModels.UnifiedRow;
import com.editora.diff.DiffModels.UnifiedType;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

/**
 * Computes an aligned diff between two texts using java-diff-utils' Myers line diff. Pure and
 * unit-tested. {@link #compute} walks the {@link Patch} deltas, emitting an {@link DiffModels.Row} per
 * screen line — equal stretches first, then each delta as ADD/REMOVE rows, and a CHANGE delta paired
 * line-by-line into MODIFIED rows (with {@link InlineDiff} word ranges) plus filler for the longer side.
 */
public final class DiffEngine {

    private DiffEngine() {}

    /**
     * Splits {@code text} into lines for diffing: CRLF/CR are normalized to LF, and a single trailing
     * newline is dropped so {@code "a\nb\n"} → {@code [a, b]} (not a spurious trailing empty line).
     */
    public static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.endsWith("\n")) {
            t = t.substring(0, t.length() - 1);
        }
        return List.of(t.split("\n", -1));
    }

    /** Computes the diff of two already-split line lists. */
    public static DiffModel compute(List<String> left, List<String> right) {
        Patch<String> patch = DiffUtils.diff(left, right);
        List<Row> rows = new ArrayList<>();
        int li = 0; // 0-based pointer into left
        int ri = 0; // 0-based pointer into right
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int srcPos = delta.getSource().getPosition();
            while (li < srcPos) { // equal stretch before this delta
                rows.add(Row.equal(left.get(li), li + 1, ri + 1));
                li++;
                ri++;
            }
            List<String> src = delta.getSource().getLines();
            List<String> tgt = delta.getTarget().getLines();
            switch (delta.getType()) {
                case INSERT -> {
                    for (String s : tgt) {
                        rows.add(Row.added(s, ri + 1));
                        ri++;
                    }
                }
                case DELETE -> {
                    for (String s : src) {
                        rows.add(Row.removed(s, li + 1));
                        li++;
                    }
                }
                case CHANGE -> {
                    int n = Math.max(src.size(), tgt.size());
                    for (int k = 0; k < n; k++) {
                        boolean hasL = k < src.size();
                        boolean hasR = k < tgt.size();
                        if (hasL && hasR) {
                            InlineDiff.Spans sp = InlineDiff.compute(src.get(k), tgt.get(k));
                            rows.add(Row.modified(src.get(k), li + 1, tgt.get(k), ri + 1, sp.left(), sp.right()));
                            li++;
                            ri++;
                        } else if (hasL) {
                            rows.add(Row.removed(src.get(k), li + 1));
                            li++;
                        } else {
                            rows.add(Row.added(tgt.get(k), ri + 1));
                            ri++;
                        }
                    }
                }
                default -> {
                    // java-diff-utils never emits EQUAL deltas; equal lines are filled above.
                }
            }
        }
        while (li < left.size()) { // trailing equal stretch
            rows.add(Row.equal(left.get(li), li + 1, ri + 1));
            li++;
            ri++;
        }
        return finish(rows);
    }

    private static DiffModel finish(List<Row> rows) {
        int added = 0;
        int removed = 0;
        List<Integer> changeStarts = new ArrayList<>();
        List<UnifiedRow> unified = new ArrayList<>();
        boolean prevChanged = false;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean changed = r.type() != RowType.EQUAL;
            if (changed && !prevChanged) {
                changeStarts.add(i);
            }
            prevChanged = changed;
            switch (r.type()) {
                case EQUAL -> unified.add(new UnifiedRow(UnifiedType.CONTEXT, r.left(), r.leftLine(), r.rightLine()));
                case ADDED -> {
                    added++;
                    unified.add(new UnifiedRow(UnifiedType.ADD, r.right(), -1, r.rightLine()));
                }
                case REMOVED -> {
                    removed++;
                    unified.add(new UnifiedRow(UnifiedType.REMOVE, r.left(), r.leftLine(), -1));
                }
                case MODIFIED -> {
                    added++;
                    removed++;
                    unified.add(new UnifiedRow(UnifiedType.REMOVE, r.left(), r.leftLine(), -1));
                    unified.add(new UnifiedRow(UnifiedType.ADD, r.right(), -1, r.rightLine()));
                }
                default -> {}
            }
        }
        return new DiffModel(rows, unified, added, removed, changeStarts);
    }
}
