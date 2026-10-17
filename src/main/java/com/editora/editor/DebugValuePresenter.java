package com.editora.editor;

import org.fxmisc.richtext.CodeArea;

/**
 * What shows a value the debugger evaluated for the editor — the hover and Show Value at Caret (follow-up 6): the
 * window supplies it, so an object's fields can unfold in a tree while this package knows nothing of the
 * debugger. Without one, {@link DebugHover} shows a one-line tooltip.
 */
public interface DebugValuePresenter {

    /**
     * Evaluates {@code expression} and shows its value at the screen point {@code (x, y)}, owned by {@code area};
     * shows nothing when it does not evaluate. {@code keyboard}: opened from the keyboard, so the arrow keys,
     * Enter and Esc work the popup until it closes.
     */
    void show(CodeArea area, String expression, double x, double y, boolean keyboard);

    /** Closes the popup now (resumed, another word, Esc). */
    void hide();

    /** The mouse left the word it was opened for: close soon, unless the mouse goes into the popup. */
    void mouseLeft();

    /** Whether the mouse is over the popup (a word under it is not hovered meanwhile). */
    boolean mouseInside();

    boolean isShowing();
}
