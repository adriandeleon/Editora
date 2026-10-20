package com.editora.editops;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Where multi-caret commands put their carets. Ranges are {@code {anchor, caret}} pairs in document
 * offsets; a caret with nothing selected has both equal. Pure.
 */
public final class CaretPlans {

    private CaretPlans() {}

    /**
     * Sublime Text's Split into Lines: one selection per line that {@code [start, end)} covers, each holding
     * the part of that line inside the selection, with the caret at its end. A selection that stops at the
     * very start of a line does not reach into it. Empty when the selection is empty.
     */
    public static List<int[]> splitIntoLines(String text, int start, int end) {
        List<int[]> out = new ArrayList<>();
        int from = Math.clamp(Math.min(start, end), 0, text.length());
        int to = Math.clamp(Math.max(start, end), 0, text.length());
        if (from == to) {
            return out;
        }
        int lineStart = from;
        while (true) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 || newline >= to ? to : newline;
            out.add(new int[] {lineStart, lineEnd});
            if (newline < 0 || newline + 1 >= to) {
                return out;
            }
            lineStart = newline + 1;
        }
    }

    /**
     * VS Code's Add Cursors to Line Ends: a bare caret at the end of every line that {@code [start, end)}
     * covers. A selection that stops at the very start of a line does not reach into it. Empty when the
     * selection is empty.
     */
    public static List<int[]> caretsAtLineEnds(String text, int start, int end) {
        List<int[]> out = new ArrayList<>();
        for (int[] part : splitIntoLines(text, start, end)) {
            int newline = text.indexOf('\n', part[1]);
            int lineEnd = newline < 0 ? text.length() : newline;
            out.add(new int[] {lineEnd, lineEnd});
        }
        return out;
    }

    /**
     * The next occurrence of {@code term} at or after {@code from}, wrapping round to the start, that is not
     * one of the {@code taken} ranges (compared as {@code {start, end}}). Case-sensitive and literal, as
     * Add Caret at Next Occurrence is. Null when every occurrence is taken or there is none.
     */
    public static int @Nullable [] nextOccurrence(String text, String term, int from, List<int[]> taken) {
        if (term == null || term.isEmpty()) {
            return null;
        }
        int start = Math.clamp(from, 0, text.length());
        int idx = text.indexOf(term, start);
        boolean wrapped = false;
        while (true) {
            if (idx < 0) {
                if (wrapped) {
                    return null;
                }
                wrapped = true;
                idx = text.indexOf(term);
                continue;
            }
            if (wrapped && idx >= start) {
                return null; // back where the search began: every occurrence was seen
            }
            int end = idx + term.length();
            if (!isTaken(idx, end, taken)) {
                return new int[] {idx, end};
            }
            idx = text.indexOf(term, idx + 1);
        }
    }

    private static boolean isTaken(int start, int end, List<int[]> taken) {
        for (int[] t : taken) {
            if (Math.min(t[0], t[1]) == start && Math.max(t[0], t[1]) == end) {
                return true;
            }
        }
        return false;
    }
}
