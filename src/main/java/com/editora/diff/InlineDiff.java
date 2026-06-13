package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;

/**
 * Intra-line word-level diff: given two lines that changed, returns the {@code [start,end)} character
 * ranges that differ on each side, so the viewer can highlight just the changed words (like GitHub's
 * within-line emphasis) rather than the whole line. Pure and unit-tested.
 *
 * <p>Implemented on top of java-diff-utils by tokenizing each line into word/non-word runs and diffing
 * the token lists — word granularity, no second diff library needed. Cheap (one short diff per changed
 * line) and only ever called for paired CHANGE rows.
 */
public final class InlineDiff {

    private InlineDiff() {}

    /** Differing char ranges on the {@code left} and {@code right} line (each {@code [start,end)}). */
    public record Spans(int[][] left, int[][] right) {}

    public static Spans compute(String left, String right) {
        if (left == null) {
            left = "";
        }
        if (right == null) {
            right = "";
        }
        List<String> a = tokenize(left);
        List<String> b = tokenize(right);
        int[] aStarts = offsets(a);
        int[] bStarts = offsets(b);
        Patch<String> patch = DiffUtils.diff(a, b);
        List<int[]> leftRanges = new ArrayList<>();
        List<int[]> rightRanges = new ArrayList<>();
        for (AbstractDelta<String> d : patch.getDeltas()) {
            DeltaType t = d.getType();
            if (t == DeltaType.DELETE || t == DeltaType.CHANGE) {
                addRange(
                        leftRanges,
                        aStarts,
                        d.getSource().getPosition(),
                        d.getSource().getLines().size());
            }
            if (t == DeltaType.INSERT || t == DeltaType.CHANGE) {
                addRange(
                        rightRanges,
                        bStarts,
                        d.getTarget().getPosition(),
                        d.getTarget().getLines().size());
            }
        }
        return new Spans(merge(leftRanges), merge(rightRanges));
    }

    /** Tokens are maximal runs of word characters, or a single non-word character each (so whitespace
     *  and punctuation split words apart for word-level granularity). */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (isWordChar(c)) {
                int j = i + 1;
                while (j < n && isWordChar(s.charAt(j))) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Cumulative character start of each token; {@code offsets[i]} = start of token i, plus a final
     *  entry = total length (so a token run [pos, pos+count) spans chars [offsets[pos], offsets[pos+count])). */
    private static int[] offsets(List<String> tokens) {
        int[] starts = new int[tokens.size() + 1];
        int acc = 0;
        for (int i = 0; i < tokens.size(); i++) {
            starts[i] = acc;
            acc += tokens.get(i).length();
        }
        starts[tokens.size()] = acc;
        return starts;
    }

    private static void addRange(List<int[]> ranges, int[] starts, int pos, int count) {
        if (count <= 0) {
            return;
        }
        int start = starts[pos];
        int end = starts[pos + count];
        if (end > start) {
            ranges.add(new int[] {start, end});
        }
    }

    /** Coalesces adjacent/overlapping ranges (they arrive in order). */
    static int[][] merge(List<int[]> ranges) {
        if (ranges.isEmpty()) {
            return new int[0][];
        }
        List<int[]> out = new ArrayList<>();
        int[] cur = ranges.get(0).clone();
        for (int i = 1; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            if (r[0] <= cur[1]) {
                cur[1] = Math.max(cur[1], r[1]);
            } else {
                out.add(cur);
                cur = r.clone();
            }
        }
        out.add(cur);
        return out.toArray(new int[0][]);
    }
}
