package com.editora.editor;

import javafx.application.Platform;

import com.editora.editor.todo.TodoMark;
import com.editora.editor.todo.TodoMatcher;
import org.jspecify.annotations.Nullable;

/**
 * A buffer's TODO/FIXME-style highlights: the configured patterns scanned off the FX thread on the settled-edit
 * pulse (a generation counter drops a superseded scan), the marks pushed to the overlay, the overview stripe and
 * the minimap, and jumping between them.
 */
final class BufferTodos {

    private final EditorBuffer b;

    BufferTodos(EditorBuffer b) {
        this.b = b;
    }

    /** Injected matcher (compiled patterns) + on/off gate; null/false = no highlight. */
    private @Nullable TodoMatcher todoMatcher;

    boolean todoEnabled;
    /** Enables/disables the TODO-pattern highlight for this buffer and re-scans (the controller pushes the
     *  effective {@code Settings.todoHighlight}). */
    void setTodoHighlightEnabled(boolean enabled) {
        this.todoEnabled = enabled;
        b.minimap.setTodoEnabled(enabled && !b.largeFile);
        b.updateTodoStripe(); // (de)activate + position the scrollbar overview stripe
        refreshTodoMarks();
    }

    /** Injects the compiled-pattern matcher (decoupled from the {@code todo}/{@code config} packages) and
     *  re-scans. */
    void setTodoMatcher(TodoMatcher matcher) {
        this.todoMatcher = matcher;
        refreshTodoMarks();
    }

    /** Discards an in-flight background TODO scan whose result would be stale (mirrors {@link BufferHighlighter#highlightGen}). */
    long todoGen;

    /** Re-scans the buffer text and updates the highlight overlay + the scrollbar/minimap overview stripes;
     *  inert when off / no matcher / huge file. The multi-pattern scan runs off the FX thread (a generation
     *  counter discards a superseded result), so a large-but-under-cap file doesn't spend tens of ms scanning
     *  on the FX thread per debounced edit pulse — mirroring how {@link BufferHighlighter#applyHighlighting} tokenizes. */
    void refreshTodoMarks() {
        if (b.disposed) {
            return; // see the disposed field
        }
        long gen = ++todoGen; // any newer pulse (or dispose) supersedes an in-flight scan
        if (!todoEnabled || todoMatcher == null || b.largeFile) {
            applyTodoMarks(java.util.List.of());
            return;
        }
        TodoMatcher matcher = todoMatcher; // may be reassigned on the FX thread; capture the current one
        String text = b.documentTextSnapshot();
        EditorBuffer.HIGHLIGHT_POOL.execute(() -> {
            java.util.List<TodoMark> marks;
            try {
                marks = matcher.match(text);
            } catch (RuntimeException e) {
                return; // never let a pathological user pattern kill the scan thread
            }
            Platform.runLater(() -> {
                if (gen != todoGen) {
                    return; // a newer edit/scan superseded this pass
                }
                applyTodoMarks(marks);
            });
        });
    }

    /** Pushes a scan result to the overlay, the stripe and the minimap; the first non-empty one attaches the
     *  overlay and the stripe (an empty result for a buffer that never had them leaves them unbuilt). */
    private void applyTodoMarks(java.util.List<TodoMark> marks) {
        if (!marks.isEmpty() || b.todoOverlay != null) {
            b.todoOverlay().setMarks(marks);
        }
        if (!marks.isEmpty() || b.todoStripe != null) {
            b.todoStripe().setMarks(marks);
        }
        b.minimap.setTodoMarks(marks);
        todoMarks = java.util.Collections.unmodifiableList(marks);
        if (onTodoMarksChanged != null) {
            onTodoMarksChanged.run();
        }
    }

    /** The last applied TODO/highlight marks (empty when off, unscanned, or a huge file). */
    java.util.List<TodoMark> todoMarks = java.util.List.of();

    private @Nullable Runnable onTodoMarksChanged;

    /** The TODO/highlight marks currently shown in this buffer (read-only; empty when the highlight is off). */
    java.util.List<TodoMark> todoMarks() {
        return todoMarks;
    }

    /** Called on the FX thread after every TODO re-scan of this buffer is applied (on load, after debounced
     *  edits, and when the patterns or the on/off setting change), whether or not the marks changed. */
    void setOnTodoMarksChanged(Runnable callback) {
        this.onTodoMarksChanged = callback;
    }

    /** Moves the caret to the next TODO/highlight match after the caret (wrapping); false if none exist. */
    boolean jumpToNextTodo() {
        return jumpTodoMark(true);
    }

    /** Moves the caret to the previous TODO/highlight match before the caret (wrapping); false if none. */
    boolean jumpToPreviousTodo() {
        return jumpTodoMark(false);
    }

    private boolean jumpTodoMark(boolean forward) {
        // Same gates as refreshTodoMarks: without them a huge file (where the highlight is deliberately off
        // and no marks are drawn) still ran the full multi-pattern scan over the whole document on the FX
        // thread, and jumped to a match the user cannot see.
        if (todoMatcher == null || !todoEnabled || b.largeFile) {
            return false;
        }
        java.util.List<TodoMark> marks = todoMatcher.match(b.documentTextSnapshot());
        if (marks.isEmpty()) {
            return false;
        }
        int caret = b.area.getCaretPosition();
        TodoMark target = null;
        if (forward) {
            for (TodoMark m : marks) {
                if (m.start() > caret) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                target = marks.get(0);
            }
        } else {
            for (int i = marks.size() - 1; i >= 0; i--) {
                if (marks.get(i).start() < caret) {
                    target = marks.get(i);
                    break;
                }
            }
            if (target == null) {
                target = marks.get(marks.size() - 1);
            }
        }
        b.area.moveTo(Math.min(target.start(), b.area.getLength()));
        b.area.requestFollowCaret();
        return true;
    }
}
