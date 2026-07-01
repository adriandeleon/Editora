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

    /**
     * Diff options. {@code ignoreWhitespace} compares lines with leading/trailing/collapsed whitespace
     * removed (the rows still render the original text). {@code wordLevel} enables the intra-line
     * {@link InlineDiff} word-range emphasis on MODIFIED rows (turn off for whole-line highlighting).
     */
    public record DiffOptions(boolean ignoreWhitespace, boolean wordLevel) {
        public static final DiffOptions DEFAULT = new DiffOptions(false, true);
    }

    private static final InlineDiff.Spans NO_SPANS = new InlineDiff.Spans(new int[0][], new int[0][]);

    /** Computes the diff of two already-split line lists (default options). */
    public static DiffModel compute(List<String> left, List<String> right) {
        return compute(left, right, DiffOptions.DEFAULT);
    }

    /**
     * Computes the diff with {@code opts}. When {@code ignoreWhitespace} is set, the Myers diff runs over
     * whitespace-normalized lines — but because normalization is strictly one-to-one per line, the delta
     * positions still index the original lists, so every emitted {@link Row} carries the <em>original</em>
     * text (only the equality test changes). {@code wordLevel} gates the per-line {@link InlineDiff}.
     */
    public static DiffModel compute(List<String> left, List<String> right, DiffOptions opts) {
        DiffOptions o = opts == null ? DiffOptions.DEFAULT : opts;
        List<String> dl = o.ignoreWhitespace() ? normalizeAll(left) : left;
        List<String> dr = o.ignoreWhitespace() ? normalizeAll(right) : right;
        Patch<String> patch = DiffUtils.diff(dl, dr);
        List<Row> rows = new ArrayList<>();
        int li = 0; // 0-based pointer into left (original)
        int ri = 0; // 0-based pointer into right (original)
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int srcPos = delta.getSource().getPosition();
            while (li < srcPos) { // equal stretch before this delta
                rows.add(Row.equal(left.get(li), li + 1, ri + 1));
                li++;
                ri++;
            }
            // Slice text from the ORIGINAL lists by the delta's positions/sizes (not delta.getLines(),
            // which would be the normalized text when ignoring whitespace).
            int srcSize = delta.getSource().size();
            int tgtSize = delta.getTarget().size();
            switch (delta.getType()) {
                case INSERT -> {
                    for (int k = 0; k < tgtSize; k++) {
                        rows.add(Row.added(right.get(ri), ri + 1));
                        ri++;
                    }
                }
                case DELETE -> {
                    for (int k = 0; k < srcSize; k++) {
                        rows.add(Row.removed(left.get(li), li + 1));
                        li++;
                    }
                }
                case CHANGE -> {
                    int n = Math.max(srcSize, tgtSize);
                    for (int k = 0; k < n; k++) {
                        boolean hasL = k < srcSize;
                        boolean hasR = k < tgtSize;
                        if (hasL && hasR) {
                            InlineDiff.Spans sp =
                                    o.wordLevel() ? InlineDiff.compute(left.get(li), right.get(ri)) : NO_SPANS;
                            rows.add(Row.modified(left.get(li), li + 1, right.get(ri), ri + 1, sp.left(), sp.right()));
                            li++;
                            ri++;
                        } else if (hasL) {
                            rows.add(Row.removed(left.get(li), li + 1));
                            li++;
                        } else {
                            rows.add(Row.added(right.get(ri), ri + 1));
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

    private static List<String> normalizeAll(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String s : lines) {
            out.add(normalize(s));
        }
        return out;
    }

    /** Leading/trailing whitespace stripped and internal whitespace runs collapsed to one space. */
    static String normalize(String line) {
        return line.strip().replaceAll("\\s+", " ");
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
