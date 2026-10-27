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
 * Shows a jump's target line — where the caret was just moved — in one of a buffer's views: at the top, below the
 * sticky-scroll rows, in the frame that moves the caret.
 *
 * <p>Jumps used to move the caret, follow it, and put the line at the top a pulse later, with the pinned rows a
 * pulse after the scroll: every Structure click, Go to Line, definition and search hit painted two or three
 * frames, and a line followed in from above landed under the pinned rows. One per buffer; FX thread.
 */
public final class JumpReveal {

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
     * Puts {@code line} at the top of {@code area}'s usable viewport, below any pinned rows. Call after the area
     * has been laid out.
     *
     * <p>The pinned rows are filled in here, for the line about to be on top, rather than by the scroll listener:
     * that one runs a pulse after the jump, so the jump would paint once with the old rows (or none) over the new
     * text and again with the right ones.
     */
    public void showAtTop(CodeArea area, int line) {
        boolean pinning = stickyScroll.getAsBoolean();
        int firstVisible = pinning ? StickyScroll.navigationFirstVisible(regions.get(), line) : Math.max(0, line);
        area.showParagraphAtTop(firstVisible);
        // Across, the target's own column decides, not where the view happened to be: a column near the line's
        // start shows from column 1, and one past the viewport's width is scrolled to. A jump used to keep the old
        // horizontal scroll, landing with the line start cut off or the caret off to the right (XML review C19).
        // The line is on screen now, so following the caret moves the view only sideways.
        area.scrollXToPixel(0);
        area.requestFollowCaret();
        if (pinning && pinsOver.test(area)) {
            pinFor.accept(firstVisible);
        }
    }

    /**
     * Shows a jump's target {@code line} with {@link #showAtTop}: now when {@code area} is on screen, so the frame
     * that moves the caret shows the line too. An area not laid out yet (a file just opened, a widen that just
     * swapped its text in, which the caller passes as {@code layoutPending}) follows the caret now and anchors the
     * line once it is laid out.
     */
    public void reveal(CodeArea area, int line, boolean layoutPending) {
        if (!layoutPending && area.getScene() != null && area.getHeight() > 0) {
            try {
                showAtTop(area, line);
                return;
            } catch (RuntimeException notReady) {
                // The viewport refused the target; anchor it after the next layout instead.
            }
        }
        area.requestFollowCaret();
        Platform.runLater(() -> {
            try {
                showAtTop(area, line);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
    }

    /**
     * As {@link #reveal} for a line that is not in view yet; a line already on screen, clear of the pinned rows,
     * is only followed (a partly shown last line slides in). For Go to Line and the fold motions, which used to
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
        if (line >= first + pinned && line <= last) {
            area.requestFollowCaret();
        } else {
            reveal(area, line, false);
        }
    }
}
