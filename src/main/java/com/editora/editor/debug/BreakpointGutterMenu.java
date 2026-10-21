package com.editora.editor.debug;

import javafx.scene.input.ContextMenuEvent;

import com.editora.editor.EditorBuffer;

/** What a right-click on a breakpoint glyph in the gutter does (the window shows Edit / Disable / Remove). */
@FunctionalInterface
public interface BreakpointGutterMenu {
    /** {@code line} is the 0-based view line of the breakpoint that was right-clicked. */
    void show(EditorBuffer buffer, int line, ContextMenuEvent event);
}
