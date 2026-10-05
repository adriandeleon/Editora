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
}
