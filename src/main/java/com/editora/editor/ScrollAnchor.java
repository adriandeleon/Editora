package com.editora.editor;

import org.fxmisc.richtext.CodeArea;

/**
 * A viewport position that survives the document being re-laid-out: the first visible line, with the pixel
 * offset only as a fallback. The virtual flow's {@code estimatedScrollY} is an estimate over the cells it
 * has measured so far, so the same number names different lines before and after a layout pass.
 */
final class ScrollAnchor {

    private ScrollAnchor() {}

    /** The first visible paragraph of {@code area}, or -1 when it has no laid-out viewport. */
    static int firstVisibleLine(CodeArea area) {
        try {
            return area.firstVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            return -1;
        }
    }

    /** Scrolls {@code area} back to {@code line} at the top; to {@code pixelY} when the line is unknown. */
    static void restore(CodeArea area, int line, double pixelY) {
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
    static void adopt(CodeArea area, CodeArea other, int otherTop) {
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
}
