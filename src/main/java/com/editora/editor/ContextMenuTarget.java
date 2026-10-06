package com.editora.editor;

import javafx.geometry.Bounds;
import javafx.scene.input.ContextMenuEvent;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.Selection;
import org.fxmisc.richtext.model.TwoDimensional;

/**
 * The one place in the text an editor context menu is about, and where on screen the menu opens.
 *
 * <p>A menu mixes items that act on the caret or selection (Paste, Cut, Toggle Comment, Run Test) with items
 * that act on a position (Go to Definition, spelling suggestions, Add Bookmark). Resolving both from the same
 * request, before any item is built, is what keeps them from disagreeing:
 *
 * <ul>
 *   <li><b>Right-click outside every selection</b> moves the caret to the click, dropping the selection and
 *       any extra carets — the convention in every editor, and what makes Paste land where you clicked.
 *   <li><b>Right-click inside a selection</b> (the primary one or an extra caret's) keeps carets and
 *       selections as they are, so Cut/Copy act on what was clicked; position items use the clicked spot.
 *   <li><b>Menu key / Shift+F10</b>: JavaFX reports a point a quarter of the way into the focused node, which
 *       is not a click. The target is the caret, and the menu opens just below it.
 * </ul>
 *
 * <p>Works on whichever view the request arrived at, so a split's second pane resolves against its own caret
 * and hit-testing: pass the area the menu is being shown on.
 *
 * @param offset  the document offset position-dependent items act on
 * @param line    the 0-based paragraph of {@code offset}
 * @param screenX where to show the menu
 * @param screenY where to show the menu
 */
record ContextMenuTarget(int offset, int line, double screenX, double screenY) {

    /**
     * Resolves {@code e}, a request delivered to {@code area}, moving the caret as described above.
     *
     * @param collapseExtraCarets drops extra carets / a box selection; run only when the click lands outside
     *                            every selection
     */
    static ContextMenuTarget resolve(CodeArea area, ContextMenuEvent e, Runnable collapseExtraCarets) {
        area.requestFocus();
        if (e.isKeyboardTrigger()) {
            Bounds caret = area.getCaretBounds().orElse(null); // empty while the caret is scrolled out of view
            return new ContextMenuTarget(
                    area.getCaretPosition(),
                    area.getCurrentParagraph(),
                    caret == null ? e.getScreenX() : caret.getMinX(),
                    caret == null ? e.getScreenY() : caret.getMaxY());
        }
        int offset;
        try {
            offset = area.hit(e.getX(), e.getY()).getInsertionIndex();
        } catch (RuntimeException ex) { // nothing laid out under the pointer: leave the caret where it is
            return new ContextMenuTarget(
                    area.getCaretPosition(), area.getCurrentParagraph(), e.getScreenX(), e.getScreenY());
        }
        if (!insideASelection(area, offset)) {
            collapseExtraCarets.run();
            area.moveTo(offset);
        }
        int line = area.offsetToPosition(offset, TwoDimensional.Bias.Forward).getMajor();
        return new ContextMenuTarget(offset, line, e.getScreenX(), e.getScreenY());
    }

    private static boolean insideASelection(CodeArea area, int offset) {
        for (Selection<?, ?, ?> selection : area.getSelectionSet()) {
            if (selection.getLength() > 0
                    && offset >= selection.getStartPosition()
                    && offset <= selection.getEndPosition()) {
                return true;
            }
        }
        return false;
    }
}
