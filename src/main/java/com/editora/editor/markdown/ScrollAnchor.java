package com.editora.editor.markdown;

import java.util.Collection;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.jspecify.annotations.Nullable;

/**
 * A viewport position that survives the document being re-laid-out: the first visible line, with the pixel
 * offset only as a fallback. The virtual flow's {@code estimatedScrollY} is an estimate over the cells it
 * has measured so far, so the same number names different lines before and after a layout pass.
 */
public final class ScrollAnchor {

    private ScrollAnchor() {}

    /** The first visible paragraph of {@code area}, or -1 when it has no laid-out viewport. */
    public static int firstVisibleLine(@Nullable CodeArea area) {
        if (area == null) {
            return -1;
        }
        try {
            return area.firstVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            return -1;
        }
    }

    /** Scrolls {@code area} back to {@code line} at the top; to {@code pixelY} when the line is unknown. */
    public static void restore(CodeArea area, int line, double pixelY) {
        if (line >= 0 && line < area.getParagraphs().size()) {
            try {
                area.showParagraphAtTop(line);
                return;
            } catch (RuntimeException notLaidOut) {
                // fall through to the pixel offset
            }
        }
        area.estimatedScrollYProperty().setValue(pixelY);
    }

    /**
     * Gives {@code area} the caret, selection and scroll position of {@code other}, another view of the same
     * document whose first visible line was {@code otherTop} (-1 = unknown: just reveal the caret).
     */
    public static void adopt(CodeArea area, CodeArea other, int otherTop) {
        int length = area.getLength();
        area.selectRange(Math.min(other.getAnchor(), length), Math.min(other.getCaretPosition(), length));
        if (otherTop < 0 || otherTop >= area.getParagraphs().size()) {
            area.requestFollowCaret();
            return;
        }
        // A pulse later: the view is being put back into the scene right now, and a scroll asked for in the
        // same turn is resolved against the layout it had as half of the split.
        javafx.application.Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(Math.min(otherTop, area.getParagraphs().size() - 1));
            } catch (RuntimeException notLaidOut) {
                area.requestFollowCaret();
            }
        });
    }

    /**
     * Applies style spans without letting the viewport jump.
     *
     * <p>{@code setStyleSpans} re-runs the document update and can drop the virtual flow's scroll to the top:
     * measured as {@code estimatedScrollY} going 184 → 0 on the first highlight pass after a session restore,
     * a couple of pulses after the file had already painted at its saved caret. That is what showed as a
     * restored file jumping to line 1 on open. Restoring the scroll only when it actually collapsed to the
     * top keeps this inert for every normal re-highlight (typing, scrolling), where the flow doesn't reset.
     *
     * <p>What is put back is the first visible <em>line</em>, not the pixel offset: right after a whole-document
     * replace (a reload from disk) the offset is an estimate over unmeasured cells, and re-applying it once the
     * heights are known landed a hundred lines away from where the view had been.
     */
    public static void setStyleSpans(CodeArea area, int from, StyleSpans<Collection<String>> spans) {
        Double before = area.estimatedScrollYProperty().getValue();
        int top = before != null && before > 1 ? firstVisibleLine(area) : -1;
        area.setStyleSpans(from, spans);
        Double after = area.estimatedScrollYProperty().getValue();
        if (before != null && before > 1 && (after == null || after <= 1)) {
            restore(area, top, before);
        }
    }
}
