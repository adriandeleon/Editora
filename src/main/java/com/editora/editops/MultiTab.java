package com.editora.editops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tab / Shift-Tab with several carets: the buffer's own Tab at every caret, as one edit.
 *
 * <p>Each caret gets exactly the edit a lone caret would get there ({@link Edits} — the smart Tab of a code
 * buffer, the plain one of prose): indent the selected lines, indent or dedent the caret's line, or insert
 * one indent unit. The multi-caret add-on typed a literal tab character at every caret instead, whatever
 * the indent style and whatever was selected.
 *
 * <p>Two carets can ask for the same text to be rewritten (both on one line, both wanting it indented). The
 * first in document order wins and the other caret simply rides along, so a line is never indented twice.
 *
 * <p>Pure: no toolkit dependency.
 */
public final class MultiTab {

    private MultiTab() {}

    /** The single-caret Tab edit for a selection of {@code text}; null when Tab does not apply at all. */
    @FunctionalInterface
    public interface Edits {
        Indenter.TabEdit edit(String text, int selStart, int selEnd, boolean shift);
    }

    /** One replacement, in the coordinates of the text before any of them. */
    public record Change(int from, int to, String replacement) {}

    /** One caret's selection afterwards, in the coordinates of the text after all of them. */
    public record Range(int anchor, int caret) {}

    /**
     * @param changes disjoint, ascending — ready for a relative multi-change
     * @param ranges one per caret, in the order the carets were given
     */
    public record Plan(List<Change> changes, List<Range> ranges) {}

    /**
     * Plans Tab ({@code shift == false}) or Shift-Tab for {@code carets} (each {@code {anchor, caret}}).
     * Returns null when {@code edits} declines any caret (a read-only or huge buffer), so the caller leaves
     * the key alone.
     */
    public static Plan plan(String text, List<int[]> carets, boolean shift, Edits edits) {
        int n = carets.size();
        Indenter.TabEdit[] wanted = new Indenter.TabEdit[n];
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int anchor = carets.get(i)[0];
            int caret = carets.get(i)[1];
            wanted[i] = edits.edit(text, Math.min(anchor, caret), Math.max(anchor, caret), shift);
            if (wanted[i] == null) {
                return null;
            }
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> wanted[i].from()).thenComparingInt(i -> wanted[i].to()));

        // Accept each edit that does not reach into one already accepted.
        int[] changeIndex = new int[n];
        java.util.Arrays.fill(changeIndex, -1);
        List<Change> changes = new ArrayList<>(n);
        int lastTo = Integer.MIN_VALUE;
        int lastFrom = Integer.MIN_VALUE;
        for (int i : order) {
            Indenter.TabEdit e = wanted[i];
            boolean real = e.from() != e.to() || !e.replacement().isEmpty();
            boolean sameSpot = e.from() == lastFrom && e.to() == e.from(); // two insertions at one offset
            if (real && e.from() >= lastTo && !sameSpot) {
                changeIndex[i] = changes.size();
                changes.add(new Change(e.from(), e.to(), e.replacement()));
                lastFrom = e.from();
                lastTo = e.to();
            }
        }

        List<Range> ranges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int anchor = carets.get(i)[0];
            int caret = carets.get(i)[1];
            Indenter.TabEdit e = wanted[i];
            int start;
            int end;
            if (changeIndex[i] >= 0) { // its own edit's selection, moved by the edits above it
                int moved = 0;
                for (int k = 0; k < changeIndex[i]; k++) {
                    moved += growth(changes.get(k));
                }
                start = e.selStart() + moved;
                end = e.selEnd() + moved;
            } else if (e.from() == e.to() && e.replacement().isEmpty()) { // nothing to change, a caret to place
                start = carry(changes, e.selStart());
                end = carry(changes, e.selEnd());
            } else { // another caret's edit covers this one's: ride along
                start = carry(changes, Math.min(anchor, caret));
                end = carry(changes, Math.max(anchor, caret));
            }
            ranges.add(caret < anchor ? new Range(end, start) : new Range(start, end));
        }
        return new Plan(changes, ranges);
    }

    private static int growth(Change c) {
        return c.replacement().length() - (c.to() - c.from());
    }

    /**
     * Where an offset of the old text lands: moved by the changes before it; inside a rewritten stretch it
     * keeps its distance from that stretch's end (an indent grows at the front, the text after it does not).
     */
    private static int carry(List<Change> changes, int position) {
        int shift = 0;
        for (Change c : changes) {
            if (c.to() <= position) {
                shift += growth(c);
            } else if (c.from() < position) {
                int newStart = c.from() + shift;
                return Math.max(newStart, newStart + c.replacement().length() - (c.to() - position));
            }
        }
        return position + shift;
    }
}
