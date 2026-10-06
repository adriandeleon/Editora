package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.CodeArea;

/**
 * The debugger's execution-point highlight of one {@link CodeArea}: a paragraph style class on the line
 * the debuggee is stopped at.
 *
 * <p>Two things about paragraph styles shape this. The style <em>travels with its paragraph</em> when lines
 * are inserted or removed above it, so the line is found again by its style rather than by a remembered
 * index — clearing by index left the highlight on the statement for good once the user typed a line above
 * it while paused. And the style list is <em>shared</em>: a collapsed fold is the {@code collapse} entry of
 * each hidden paragraph's style, so the class is added to and removed from the list, never written over it.
 */
final class ExecutionLine {

    static final String STYLE = "exec-line";

    /** The line last marked, or -1; only a hint (see above). */
    private int line = -1;

    /** The paragraph count when {@link #line} was marked — unchanged means the hint can be trusted. */
    private int paragraphsThen;

    /**
     * Marks {@code target} as the execution point, revealing it if it is hidden inside a collapsed fold.
     * Returns false (and marks nothing) when the line does not exist.
     */
    boolean set(CodeArea area, FoldManager folds, int target) {
        clear(area);
        if (target < 0 || target >= area.getParagraphs().size()) {
            return false;
        }
        folds.unfoldContaining(target);
        List<String> style = new ArrayList<>(styleOf(area, target));
        style.add(STYLE);
        area.setParagraphStyle(target, style);
        line = target;
        paragraphsThen = area.getParagraphs().size();
        return true;
    }

    /** Removes the highlight from wherever it now is. */
    void clear(CodeArea area) {
        if (line < 0) {
            return;
        }
        int count = area.getParagraphs().size();
        if (line < count && count == paragraphsThen && styleOf(area, line).contains(STYLE)) {
            unmark(area, line);
        } else {
            // The document was edited while paused: the marked paragraph moved (or was split in two).
            for (int i = 0; i < count; i++) {
                if (styleOf(area, i).contains(STYLE)) {
                    unmark(area, i);
                }
            }
        }
        line = -1;
    }

    private static void unmark(CodeArea area, int index) {
        List<String> style = new ArrayList<>(styleOf(area, index));
        style.remove(STYLE);
        area.setParagraphStyle(index, style);
    }

    private static Collection<String> styleOf(CodeArea area, int index) {
        Collection<String> style = area.getParagraph(index).getParagraphStyle();
        return style == null ? List.of() : style;
    }
}
