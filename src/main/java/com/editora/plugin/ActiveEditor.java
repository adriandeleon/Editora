package com.editora.plugin;

import java.nio.file.Path;

/**
 * A narrow facade over the active editor buffer handed to plugins, so a plugin can read/modify the current
 * file without touching the app's internals ({@code MainController}/{@code EditorBuffer} stay private). All
 * methods are no-ops / empty when there is no editable buffer (e.g. the Welcome tab).
 */
public interface ActiveEditor {

    /** The active file's path, or {@code null} when there is no file-backed buffer (untitled/none). */
    Path filePath();

    /** The active buffer's full text, or {@code ""} when there is none. */
    String text();

    /** The active buffer's selected text, or {@code ""} when nothing is selected. */
    String selectedText();

    /** The caret's 1-based line number, or {@code -1} when there is no buffer. */
    int caretLine();

    /** Replaces the current selection (or inserts at the caret when empty) with {@code replacement}. */
    void replaceSelection(String replacement);

    /** Inserts {@code text} at the caret. */
    void insertAtCaret(String text);

    /**
     * Replaces the <em>entire</em> buffer content (undoable; marks the buffer dirty) — for whole-file
     * transforms like formatting. A no-op when there is no editable buffer.
     */
    void setText(String text);

    /** Opens {@code path} in a tab (a no-op for a directory / unreadable file). */
    void openPath(Path path);
}
