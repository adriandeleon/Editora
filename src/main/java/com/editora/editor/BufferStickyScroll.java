package com.editora.editor;

import javafx.application.Platform;

import org.fxmisc.richtext.CodeArea;

/**
 * Sticky scroll for one buffer: whether the pinned enclosing-scope headers are on, and their update, coalesced to
 * one per pulse as the view scrolls. The bar itself is {@link com.editora.editor.fold.StickyScrollBar}.
 *
 * <p>Moved out of {@code EditorBuffer} for size. The pinned lines stay on the buffer, where tests read them.
 */
final class BufferStickyScroll {

    private final EditorBuffer b;

    BufferStickyScroll(EditorBuffer b) {
        this.b = b;
    }

    boolean stickyScrollEnabled;
    private boolean stickyScrollPending;

    void scheduleStickyScroll() {
        if (stickyScrollPending || !stickyScrollEnabled) {
            return;
        }
        stickyScrollPending = true;
        Platform.runLater(this::updateStickyScroll);
    }
    /**
     * Turns the pinned scope headers on or off for this buffer. Off in large-file mode along with the
     * other per-viewport work, and off for a buffer with no fold regions to speak of — there is nothing
     * to pin in prose.
     */
    void setStickyScrollEnabled(boolean enabled) {
        boolean effective = enabled && !b.largeFile;
        if (effective == stickyScrollEnabled) {
            return;
        }
        stickyScrollEnabled = effective;
        if (effective) {
            scheduleStickyScroll();
        } else {
            b.stickyScroll.hide();
            b.stickyLines = java.util.List.of(); // clear the state too, or the bar and the model disagree
        }
    }

    boolean isStickyScrollEnabled() {
        return stickyScrollEnabled;
    }
    /** Package-visible for the FX test: what is pinned right now, as 0-based lines. */
    java.util.List<Integer> stickyScrollLines() {
        return b.stickyLines;
    }
    /** Package-visible for the FX test: the bar node itself, so a test can assert it is actually laid out. */
    javafx.scene.Node stickyScrollNode() {
        return b.stickyScroll.node();
    }

    private void updateStickyScroll() {
        stickyScrollPending = false;
        if (!stickyScrollEnabled) {
            b.stickyScroll.hide();
            b.stickyLines = java.util.List.of();
            return;
        }
        CodeArea a = b.focusedArea;
        if (a == null) {
            return;
        }
        int first;
        try {
            first = Math.max(0, a.firstVisibleParToAllParIndex());
        } catch (RuntimeException ex) {
            return; // not laid out yet; the next scroll or edit will bring us back
        }
        stickyLinesFor(first);
    }
    /**
     * Recomputes and renders the pinned headers for a viewport starting at {@code firstVisible}.
     *
     * <p>Split from {@link #updateStickyScroll()} so the first visible line is a parameter rather than a
     * reading of a live viewport: under the headless test platform nothing is laid out, so the real
     * reading is meaningless there and the wiring between the buffer's fold regions and the pinned
     * result would otherwise be untestable.
     */
    void stickyLinesFor(int firstVisible) {
        // Attach here rather than trusting attachControlToCodePane(). That runs from rebuildViewHost(),
        // which only fires when the view mode or split CHANGES — and `viewHost` is built in a field
        // initializer, so a plain buffer that never changes mode (i.e. every ordinary code file) never
        // called it and the bar was never in the scene graph at all. This is the one place that is
        // guaranteed to run right before the bar is needed, and placeStickyScroll is idempotent.
        b.viewLayout.placeStickyScroll();
        b.stickyLines = b.stickyScroll.pinnedLines(b.folds.regions(), firstVisible);
        b.stickyScroll.update(b.focusedArea, b.stickyLines, b.getTabSize(), b::jumpToLine);
    }
}
