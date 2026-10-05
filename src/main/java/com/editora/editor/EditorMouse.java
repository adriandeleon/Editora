package com.editora.editor;

import javafx.scene.input.MouseEvent;

import org.fxmisc.richtext.CodeArea;

/**
 * Pointer conventions the editor component leaves to its host, for any view of a buffer.
 *
 * <p>All static and per-area, so a split's second pane gets exactly what the first has.
 */
final class EditorMouse {

    private EditorMouse() {}

    /**
     * A dragged selection is moved only when it is released <em>over the text it was dragged in</em>.
     *
     * <p>The component's default drops at {@code hit(x, y)} wherever the button comes up, and a hit-test
     * clamps a point outside the area to the nearest character. So changing your mind and letting go over
     * the tab bar, a tool window or the other pane of a split still moved the text — to the edge of the
     * viewport nearest the pointer, often a line that only the gap it left behind gives away. Releasing
     * outside now cancels the drag: the text stays, still selected.
     */
    static void installSelectionDrop(CodeArea area) {
        area.setOnSelectionDropped(e -> dropSelection(area, e));
    }

    private static void dropSelection(CodeArea area, MouseEvent e) {
        if (area.getLayoutBounds().contains(e.getX(), e.getY())) {
            area.moveSelectedText(area.hit(e.getX(), e.getY()).getInsertionIndex());
            return;
        }
        // The drag displaced the caret to show where the text would land. Put it back on the selection's
        // own end, or the next keystroke would act somewhere the user never clicked.
        int anchor = area.getAnchor();
        var selection = area.getSelection();
        if (selection.getLength() > 0) {
            area.selectRange(anchor, anchor == selection.getStart() ? selection.getEnd() : selection.getStart());
        }
    }

    /**
     * Puts {@code area}'s caret under a drop at {@code (x, y)} in its coordinates, so what is dropped lands
     * where the pointer is rather than wherever the caret was left. A point with nothing laid out under it
     * leaves the caret alone.
     */
    static void moveCaretToDrop(CodeArea area, double x, double y) {
        try {
            area.moveTo(area.hit(x, y).getInsertionIndex());
        } catch (RuntimeException ignored) {
            // nothing to hit: keep the caret
        }
        area.requestFocus();
    }
}
