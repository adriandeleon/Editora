package com.editora.editor;

/**
 * What an overview stripe over the scrollbar (TODO, diagnostics, Markdown lint) last drew, so an edit that moves
 * none of its marks draws nothing.
 *
 * <p>A stripe maps lines to heights over the whole document: what it draws depends on its marks, their colours,
 * the canvas size, the line count and the content height, and on nothing else. It is asked to redraw on every edit
 * because an edit can change the line count, but most keystrokes change none of those. Redrawing anyway clears and
 * fills the canvas again, which marks it dirty; the redraw runs from {@code runLater}, after the keystroke's pulse,
 * so the same pixels cost a pulse and a frame of their own on most keys.
 *
 * <p>The stripe calls {@link #unchanged} before drawing, and {@link #forget} whenever its marks, colours or shown
 * state change (what the key does not hold). FX thread only.
 */
public final class StripeFrame {

    private boolean known;
    private boolean painted;
    private double width;
    private double height;
    private int lines;
    private double contentHeight;
    /** How many redraws drew (tests). */
    private int draws;

    /**
     * Whether a redraw with these inputs would leave the canvas as it is; when not, remembers them as what is drawn.
     *
     * @param painted whether the stripe draws marks (false: it is blank)
     * @param width the canvas width
     * @param height the canvas height
     * @param lines the document's line count (ignored when blank)
     * @param contentHeight the content height the marks are scaled by (ignored when blank)
     */
    public boolean unchanged(boolean painted, double width, double height, int lines, double contentHeight) {
        int l = painted ? lines : 0;
        double c = painted ? contentHeight : 0;
        if (known
                && this.painted == painted
                && Double.compare(this.width, width) == 0
                && Double.compare(this.height, height) == 0
                && this.lines == l
                && Double.compare(this.contentHeight, c) == 0) {
            return true;
        }
        known = true;
        this.painted = painted;
        this.width = width;
        this.height = height;
        this.lines = l;
        this.contentHeight = c;
        draws++;
        return false;
    }

    /** How many redraws drew rather than kept the canvas (tests). */
    int drawsForTest() {
        return draws;
    }

    /** The marks, their colours or the canvas changed some other way: the next redraw draws. */
    public void forget() {
        known = false;
    }
}
