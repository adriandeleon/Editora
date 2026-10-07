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
