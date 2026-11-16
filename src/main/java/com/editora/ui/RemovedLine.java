package com.editora.ui;

import java.lang.ref.WeakReference;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The line of a tab that something was removed from (a breakpoint, its pinned values, a bookmark), kept by the
 * removal's entry in {@link RemovalUndo} to find the line again: the tab, the line in whole-file numbering (the
 * same whether or not the buffer is narrowed) and the line's text. The tab is held weakly: the newest removals
 * outlive a closed tab and must not keep its buffer.
 */
record RemovedLine(WeakReference<EditorBuffer> tab, int documentLine, String text) {

    /** Line {@code viewLine} of {@code buffer}, as the buffer shows it now. */
    static RemovedLine of(EditorBuffer buffer, int viewLine) {
        return new RemovedLine(
                new WeakReference<>(buffer), viewLine + buffer.narrowFirstLine(), buffer.lineText(viewLine));
    }

    /** The tab's buffer while the window still has the tab; null once it was closed. */
    @Nullable
    EditorBuffer buffer(CoordinatorHost host) {
        EditorBuffer buffer = tab.get();
        if (buffer == null) {
            return null;
        }
        boolean[] open = {false};
        host.forEachBuffer(b -> open[0] |= b == buffer);
        return open[0] ? buffer : null;
    }

    /**
     * The line as {@code buffer} shows it now, or -1 when it cannot take the removed thing back: the line is outside
     * the text shown (the buffer was narrowed, or the text got shorter) or no longer reads what it read (the text
     * moved under it), so what was removed would land on other code.
     */
    int viewLine(EditorBuffer buffer) {
        int line = documentLine - buffer.narrowFirstLine();
        return line >= 0 && line < buffer.lineCount() && buffer.lineText(line).equals(text) ? line : -1;
    }
}
