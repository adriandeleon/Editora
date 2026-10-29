package com.editora.editor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.application.Platform;

import com.editora.editor.fold.StickyScroll;
import com.editora.text.FoldRegions.Region;
import org.fxmisc.richtext.CodeArea;

/**
 * Shows a jump's target line — where the caret was just moved — in one of a buffer's views: near the top, a couple
 * of lines below the sticky-scroll rows, in the frame that moves the caret.
 *
 * <p>Jumps used to move the caret, follow it, and put the line at the top a pulse later, with the pinned rows a
 * pulse after the scroll: every Structure click, Go to Line, definition and search hit painted two or three
 * frames, and a line followed in from above landed under the pinned rows. One per buffer; FX thread.
 */
public final class JumpReveal {

    /**
     * Lines of context shown between the pinned rows (or the viewport's top edge) and a jump's target. A target
     * right under the pinned rows read as half hidden: each row is a little taller than a code line and casts a
     * shadow, so a method picked in Structure landed with its signature tucked under the class header.
     */
    static final int CONTEXT_LINES = 2;

    private final BooleanSupplier stickyScroll;
    private final Predicate<CodeArea> pinsOver;
    private final Supplier<List<Region>> regions;
    private final IntConsumer pinFor;
    private final IntSupplier pinnedRows;

    /**
     * @param stickyScroll whether the buffer pins scope headers at all
     * @param pinsOver whether the pinned rows are drawn over that view (they follow the focused one)
     * @param regions the buffer's fold regions, which decide what is pinned
     * @param pinFor fills the pinned rows in for a viewport starting at the given line
     * @param pinnedRows how many rows are pinned now
     */
    JumpReveal(
            BooleanSupplier stickyScroll,
            Predicate<CodeArea> pinsOver,
            Supplier<List<Region>> regions,
            IntConsumer pinFor,
            IntSupplier pinnedRows) {
        this.stickyScroll = stickyScroll;
        this.pinsOver = pinsOver;
        this.regions = regions;
        this.pinFor = pinFor;
        this.pinnedRows = pinnedRows;
    }

    /**
     * Puts {@code line} near the top of {@code area}'s usable viewport, {@link #CONTEXT_LINES} below any pinned
     * rows. Call after the area has been laid out.
     *
     * <p>The pinned rows are filled in here, for the line about to be on top, rather than by the scroll listener:
     * that one runs a pulse after the jump, so the jump would paint once with the old rows (or none) over the new
     * text and again with the right ones.
     */
    public void showAtTop(CodeArea area, int line) {
        place(area, line, CONTEXT_LINES);
    }

    private void place(CodeArea area, int line, int context) {
        boolean pinning = stickyScroll.getAsBoolean();
        int top = line - context;
        int firstVisible = pinning ? StickyScroll.navigationFirstVisible(regions.get(), top) : Math.max(0, top);
        area.showParagraphAtTop(firstVisible);
        // Across, the target's own column decides, not where the view happened to be: a column near the line's
        // start shows from column 1, and one past the viewport's width is scrolled to. A jump used to keep the old
        // horizontal scroll, landing with the line start cut off or the caret off to the right (XML review C19).
        // Only the horizontal scroll moves: following the caret would also nudge the view down a row past the
        // pinned rows it was just placed under.
        area.scrollXToPixel(0);
        revealCaretColumn(area);
        if (pinning && pinsOver.test(area)) {
            pinFor.accept(firstVisible);
        }
    }

    /** Scrolls {@code area} sideways so its caret, on a line already on screen, is inside the viewport. */
    private static void revealCaretColumn(CodeArea area) {
        area.layout();
        var caret = area.getCaretBounds().orElse(null);
        if (caret == null) {
            return;
        }
        var view = area.localToScreen(area.getBoundsInLocal());
        if (view == null) {
            return;
        }
        double margin = Math.min(view.getWidth() / 4, 4 * caret.getHeight());
        if (caret.getMaxX() > view.getMaxX()) {
            area.scrollXBy(caret.getMaxX() - view.getMaxX() + margin);
        }
    }

    /**
     * Shows a jump's target {@code line} with {@link #showAtTop}: now when {@code area} is on screen, so the frame
     * that moves the caret shows the line too. An area not laid out yet (a file just opened, a widen that just
     * swapped its text in, which the caller passes as {@code layoutPending}) follows the caret now and anchors the
     * line once it is laid out.
     */
    public void reveal(CodeArea area, int line, boolean layoutPending) {
        reveal(area, line, layoutPending, CONTEXT_LINES);
    }

    /**
     * As {@link #reveal}, but with {@code line} itself the top line below the pinned rows, no context above it:
     * for a widen, which keeps the region's lines where they were on screen rather than jumping to one.
     */
    public void keepAtTop(CodeArea area, int line, boolean layoutPending) {
        reveal(area, line, layoutPending, 0);
    }

    private void reveal(CodeArea area, int line, boolean layoutPending, int context) {
        if (!layoutPending && area.getScene() != null && area.getHeight() > 0) {
            try {
                place(area, line, context);
                return;
            } catch (RuntimeException notReady) {
                // The viewport refused the target; anchor it after the next layout instead.
            }
        }
        area.requestFollowCaret();
        Platform.runLater(() -> {
            try {
                place(area, line, context);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
    }

    /**
     * Moves {@code view}'s caret to the start of {@code line} (0-based) and shows it the way a Structure click
     * does, then focuses the view. For the scrollbar's marks, where following the caret left a warning on the
     * bottom row or under the pinned rows.
     */
    public void revealLine(CodeArea view, int line) {
        int total = view.getParagraphs().size();
        if (total == 0) {
            return;
        }
        int p = Math.max(0, Math.min(line, total - 1));
        view.moveTo(p, 0);
        reveal(view, p, false);
        view.requestFocus();
    }

    /**
     * As {@link #reveal} for a line that is not in view yet; a line already on screen, clear of the pinned rows,
     * is only followed (a partly shown last line slides in). The row right under the pinned ones counts as hidden:
     * the pinned rows' shadow falls across it. For Go to Line and the fold motions, which used to
     * follow the caret alone.
     */
    public void revealIfHidden(CodeArea area, int line) {
        int first;
        int last;
        try {
            first = area.firstVisibleParToAllParIndex();
            last = area.lastVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            reveal(area, line, true);
            return;
        }
        int pinned = stickyScroll.getAsBoolean() && pinsOver.test(area) ? pinnedRows.getAsInt() : 0;
        if (line >= first + pinned + (pinned > 0 ? 1 : 0) && line <= last) {
            area.requestFollowCaret();
        } else {
            reveal(area, line, false);
        }
    }
}
