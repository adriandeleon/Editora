package com.editora.editor.debug;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import com.editora.editor.FoldManager;
import com.editora.editor.StickyScroll;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * The debugger's execution-point highlight of one {@link CodeArea}: a paragraph style class on the line
 * the debuggee is stopped at, plus the arrow the gutter draws beside it ({@link #isMarked}).
 *
 * <p>Two things about paragraph styles shape this. The style <em>travels with its paragraph</em> when lines
 * are inserted or removed above it, so the line is found again by its style rather than by a remembered
 * index — clearing by index left the highlight on the statement for good once the user typed a line above
 * it while paused. And the style list is <em>shared</em>: a collapsed fold is the {@code collapse} entry of
 * each hidden paragraph's style, so the class is added to and removed from the list, never written over it.
 *
 * <p>A stop puts the caret on the stopped line, and the editor's current-line highlight is painted over the
 * paragraph background — so the execution colour vanished under it on every stop and step, and the stopped
 * line looked like any caret line (review C1). While the caret is on the marked line the current-line
 * highlight is therefore made transparent, and restored as soon as the caret leaves it.
 */
public final class ExecutionLine {

    static final String STYLE = "exec-line";

    /** Added beside {@link #STYLE} while the stop is on an exception: the line turns red (review C13). */
    static final String EXCEPTION_STYLE = "exec-exception";

    /** Lines of context kept above a revealed execution line that was out of view, at the least. */
    static final int MIN_CONTEXT = 3;

    /** The line last marked, or -1; only a hint (see above). */
    private int line = -1;

    /** The paragraph count when {@link #line} was marked — unchanged means the hint can be trusted. */
    private int paragraphsThen;

    /** The area whose caret is watched, once {@link #set} has run on it (the listener is installed once). */
    private @Nullable CodeArea watched;

    /** The current-line highlight colour to give back when the caret leaves the marked line. */
    private Supplier<? extends Paint> normalFill = () -> Color.TRANSPARENT;

    /**
     * Marks {@code target} as the execution point, revealing it if it is hidden inside a collapsed fold.
     * Returns false (and marks nothing) when the line does not exist. {@code repaint} rebuilds a line's gutter
     * (the arrow), for the line marked before and the one marked now; {@code lineFill} is the editor's own
     * current-line colour.
     */
    public boolean set(
            CodeArea area, FoldManager folds, int target, Supplier<? extends Paint> lineFill, IntConsumer repaint) {
        int before = markedLine(area);
        clear(area);
        if (target < 0 || target >= area.getParagraphs().size()) {
            if (before >= 0) {
                repaint.accept(before);
            }
            return false;
        }
        folds.unfoldContaining(target);
        List<String> style = new ArrayList<>(styleOf(area, target));
        style.add(STYLE);
        area.setParagraphStyle(target, style);
        line = target;
        paragraphsThen = area.getParagraphs().size();
        normalFill = lineFill;
        if (watched != area) {
            watched = area;
            area.currentParagraphProperty().addListener((o, a, b) -> applyLineFill(area));
        }
        applyLineFill(area);
        if (before >= 0 && before != target) {
            repaint.accept(before);
        }
        repaint.accept(target);
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
        applyLineFill(area);
    }

    /** As {@link #clear(CodeArea)}, rebuilding the gutter of the line that carried the arrow. */
    public void clear(CodeArea area, IntConsumer repaint) {
        int before = markedLine(area);
        clear(area);
        if (before >= 0) {
            repaint.accept(before);
        }
    }

    /** Whether paragraph {@code index} is the execution point now (the gutter draws its arrow from this). */
    public boolean isMarked(CodeArea area, int index) {
        return line >= 0
                && index >= 0
                && index < area.getParagraphs().size()
                && styleOf(area, index).contains(STYLE);
    }

    /** The marked paragraph now (it moves with edits), or -1. */
    int markedLine(CodeArea area) {
        if (line < 0) {
            return -1;
        }
        int count = area.getParagraphs().size();
        if (line < count && styleOf(area, line).contains(STYLE)) {
            return line;
        }
        for (int i = 0; i < count; i++) {
            if (styleOf(area, i).contains(STYLE)) {
                return i;
            }
        }
        return -1;
    }

    /** The current-line highlight is see-through on the execution line, so the line's own colour shows. */
    private void applyLineFill(CodeArea area) {
        if (watched != area || !area.isLineHighlighterOn()) {
            return;
        }
        boolean onIt = line >= 0 && isMarked(area, area.getCurrentParagraph());
        area.setLineHighlighterFill(onIt ? Color.TRANSPARENT : normalFill.get());
    }

    /**
     * Pure: the first line to scroll to so that {@code target} is in view and clear of the sticky-scroll headers,
     * or -1 when it already is. {@code first}/{@code last} are the visible lines ({@code first < 0}: not laid
     * out, so it is not known to be visible), {@code pinned} how many header rows cover the top of the viewport
     * now, and {@code clearTop} turns a wanted first line into one whose pinned headers leave it uncovered
     * ({@link StickyScroll#navigationFirstVisible}). A line already in plain view is left where it is — stepping
     * through a loop must not scroll on every step — and one out of view (or under a pinned header, review B6)
     * gets a third of the screen of context above it, as IntelliJ centres it.
     */
    public static int revealTop(int target, int first, int last, int pinned, IntUnaryOperator clearTop) {
        if (first >= 0 && last > first && target >= first + pinned && target < last) {
            return -1;
        }
        int rows = first >= 0 && last > first ? last - first : 0;
        int context = Math.max(MIN_CONTEXT, rows / 3);
        int top = Math.max(0, target - context);
        return Math.min(target, clearTop.applyAsInt(top));
    }

    /**
     * Scrolls so that {@code line} is in view clear of {@code pinned} sticky-scroll headers, as {@link #revealTop}
     * decides from the lines {@code area} shows now (none when it is not laid out yet); a line already in view
     * stays where it is.
     */
    public void reveal(CodeArea area, int line, int pinned, IntUnaryOperator clearTop) {
        int first;
        int last;
        try {
            first = area.firstVisibleParToAllParIndex();
            last = area.lastVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            first = last = -1;
        }
        int top = revealTop(line, first, last, pinned, clearTop);
        if (top >= 0) {
            area.showParagraphAtTop(top);
        }
    }

    /** Marks the execution line as an exception stop (red) or a plain one; nothing when no line is marked. */
    public void setException(CodeArea area, boolean exception) {
        int at = markedLine(area);
        if (at < 0 || styleOf(area, at).contains(EXCEPTION_STYLE) == exception) {
            return;
        }
        List<String> style = new ArrayList<>(styleOf(area, at));
        if (exception) {
            style.add(EXCEPTION_STYLE);
        } else {
            style.remove(EXCEPTION_STYLE);
        }
        area.setParagraphStyle(at, style);
    }

    private static void unmark(CodeArea area, int index) {
        List<String> style = new ArrayList<>(styleOf(area, index));
        style.remove(STYLE);
        style.remove(EXCEPTION_STYLE);
        area.setParagraphStyle(index, style);
    }

    private static Collection<String> styleOf(CodeArea area, int index) {
        Collection<String> style = area.getParagraph(index).getParagraphStyle();
        return style == null ? List.of() : style;
    }
}
