package com.editora.editor;

import java.util.HashMap;
import java.util.Map;

/**
 * Moves code lenses with the text they annotate when an edit adds or removes lines. Pure.
 *
 * <p>A lens is anchored to a line number the server reported for the previous text, and the next answer is
 * a debounce and a round trip away. Without this a new line above a method leaves its "3 references" on
 * whatever line now has the old number.
 */
final class CodeLensShift {

    private CodeLensShift() {}

    /** Groups {@code lenses} (null = none) by the line each annotates, in their given order. */
    static <L> Map<Integer, java.util.List<L>> byLine(
            java.util.List<L> lenses, java.util.function.ToIntFunction<L> lineOf) {
        Map<Integer, java.util.List<L>> byLine = new HashMap<>();
        if (lenses != null) {
            for (L lens : lenses) {
                byLine.computeIfAbsent(lineOf.applyAsInt(lens), k -> new java.util.ArrayList<>(1))
                        .add(lens);
            }
        }
        return byLine;
    }

    /**
     * The lenses after {@code changes} to {@code area}'s text, or {@code null} when nothing moves: a change
     * that adds or removes lines moves the lenses below it and drops those inside the removed text.
     */
    static <V> Map<Integer, V> afterChanges(
            Map<Integer, V> lenses,
            java.util.List<org.fxmisc.richtext.model.PlainTextChange> changes,
            org.fxmisc.richtext.CodeArea area) {
        if (lenses.isEmpty()) {
            return null;
        }
        Map<Integer, V> moved;
        if (changes.size() == 1) {
            var change = changes.get(0);
            int removedLines = newlines(change.getRemoved());
            int delta = newlines(change.getInserted()) - removedLines;
            if (delta == 0 && removedLines == 0) {
                return null;
            }
            var at = area.offsetToPosition(
                    Math.min(change.getPosition(), area.getLength()),
                    org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
            moved = shift(
                    lenses,
                    at.getMajor(),
                    at.getMinor() == 0,
                    removedLines,
                    change.getRemoved().endsWith("\n"),
                    delta);
        } else if (changes.stream().anyMatch(c -> newlines(c.getRemoved()) + newlines(c.getInserted()) > 0)) {
            // Each change of a batch is in the coordinates of the step it ran in; rather than replay
            // them, drop the lenses until the next answer.
            moved = Map.of();
        } else {
            return null;
        }
        return moved.isEmpty() ? Map.of() : moved;
    }

    private static int newlines(String text) {
        int n = 0;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            n++;
        }
        return n;
    }

    /**
     * @param changeLine the line the edit starts on
     * @param atLineStart whether it starts at that line's first column
     * @param removedLines line breaks in the removed text
     * @param endsAtLineStart whether the removed text ends with a line break, so the line after it is whole
     * @param delta line breaks added minus line breaks removed
     */
    static <V> Map<Integer, V> shift(
            Map<Integer, V> lenses,
            int changeLine,
            boolean atLineStart,
            int removedLines,
            boolean endsAtLineStart,
            int delta) {
        Map<Integer, V> out = new HashMap<>();
        for (Map.Entry<Integer, V> e : lenses.entrySet()) {
            int line = e.getKey();
            if (line < changeLine) {
                out.put(line, e.getValue());
            } else if (line == changeLine) {
                if (!atLineStart) {
                    out.put(line, e.getValue()); // the declaration's own start stays where it was
                } else if (removedLines == 0) {
                    out.put(line + delta, e.getValue()); // lines inserted in front of it push it down
                }
                // else: removed from its first column on — the declaration is gone
            } else if (line > changeLine + removedLines || (line == changeLine + removedLines && endsAtLineStart)) {
                out.put(line + delta, e.getValue());
            }
            // else: inside the removed lines, or on the line whose start was removed
        }
        return out;
    }
}
