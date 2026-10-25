package com.editora.editor.todo;

import java.util.List;

import com.editora.editor.EditorBuffer;

/**
 * Injected by {@code MainController} to find the highlight spans in a buffer's text, without the
 * {@code editor} package depending on {@code com.editora.todo}/{@code config}. The implementation
 * compiles the user's patterns and scans the text; {@link EditorBuffer} runs it on its debounced edit
 * pulse and feeds the result to {@link TodoHighlightOverlay}.
 */
@FunctionalInterface
public interface TodoMatcher {

    /** The highlight spans in {@code text} (empty when there are no matches / no enabled patterns). */
    List<TodoMark> match(String text);
}
