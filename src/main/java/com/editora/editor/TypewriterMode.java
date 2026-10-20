package com.editora.editor;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Typewriter mode: the line being written stays in the middle of the editor instead of working its way down
 * to the bottom edge.
 *
 * <p>The view is re-centred when the caret moves to another line — by typing, Enter or the keyboard, not
 * while a mouse button is down (a click should put the caret where it was aimed, not move the text under
 * the pointer). Typing within a line scrolls nothing, so there is no work per keystroke; a burst of line
 * changes in one pulse is one scroll.
 */
public final class TypewriterMode {

    private static final Object KEY = TypewriterMode.class;

    private TypewriterMode() {}

    /** Switches the mode for {@code buffer}'s editor (and its split view, when it has one). */
    public static void apply(EditorBuffer buffer, boolean on) {
        if (buffer == null) {
            return;
        }
        apply(buffer.getArea(), on);
        apply(buffer.getSplitView(), on);
    }

    public static boolean isOn(CodeArea area) {
        return area != null && area.getProperties().containsKey(KEY);
    }

    @SuppressWarnings("unchecked")
    static void apply(@Nullable CodeArea area, boolean on) {
        if (area == null || on == isOn(area)) {
            return;
        }
        if (!on) {
            area.currentParagraphProperty().removeListener((ChangeListener<Integer>)
                    area.getProperties().remove(KEY));
            return;
        }
        boolean[] pending = {false};
        ChangeListener<Integer> listener = (obs, was, now) -> {
            if (pending[0] || area.isPressed()) {
                return;
            }
            pending[0] = true;
            Platform.runLater(() -> {
                pending[0] = false;
                if (isOn(area) && area.getScene() != null) {
                    center(area);
                }
            });
        };
        area.getProperties().put(KEY, listener);
        area.currentParagraphProperty().addListener(listener);
    }

    /** Puts the caret's line in the middle of the viewport. */
    static void center(CodeArea area) {
        int paragraph = area.getCurrentParagraph();
        if (paragraph >= 0 && paragraph < area.getParagraphs().size()) {
            area.showParagraphAtCenter(paragraph);
        }
    }
}
