package com.editora.editor;

import org.jspecify.annotations.Nullable;

/**
 * The modes that change what a buffer may do: large-file and heavy-file mode (features off for size), huge-file
 * read-only, View mode, the partial-load flag and the loading shell, and what each makes editable.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferModes {

    private final EditorBuffer b;

    BufferModes(EditorBuffer b) {
        this.b = b;
    }

    /** Large-file mode (see {@link EditorBuffer#LARGE_FILE_BYTES}): no highlighting, minimap, spell check, whitespace marks, indent
     *  guides, current-line highlight or undo, so huge or long-lined documents stay responsive. Set before the text. */
    void setLargeFile(boolean large) {
        if (b.largeFile == large) {
            return;
        }
        b.largeFile = large;
        b.highlighter.highlightGen++; // discard any in-flight highlight result
        b.folds.setHeuristicEnabled(!large); // never schedule a whole-document fold scan for a large file
        b.setMinimapVisible(b.minimapVisible); // re-apply with the large-file guard
        b.spell.applyActive(); // spell checking is off in large-file mode (like highlighting)
        if (b.whitespace != null) {
            b.whitespace.setSuppressed(large); // so are the whitespace markers, whatever the setting says
        }
        if (b.indentGuides != null) {
            b.indentGuides.setSuppressed(large); // and the indent guides
        }
        b.setLineHighlightOn(LineHighlighter.wanted(b.area)); // and the current-line highlight
        b.undo.applyUndoMode(); // nor undo history: a large file shouldn't pay its memory
        if (!large) {
            b.highlighter.applyHighlighting(); // re-enable highlighting if we ever leave large-file mode
        }
    }
    /** Intermediate large-file tier: (with the controller's help) disables LSP, while keeping syntax
     *  highlighting, the minimap and editing. Set at load by line count, or toggled per-buffer.
     *  The controller re-runs its LSP sync after toggling so the session starts/stops accordingly. */
    void setHeavyFile(boolean heavy) {
        if (b.heavyFile == heavy) {
            return;
        }
        b.heavyFile = heavy;
        b.setMinimapVisible(b.minimapVisible); // re-apply with the heavy-file guard
        b.setLspActive(b.lspActive); // re-evaluate (forces off while heavy); controller re-syncs the session
    }
    /**
     * Enables huge-file (read-only) mode: implies large-file mode, and makes the views non-editable
     * with no undo. Used for files the loader had to truncate; see {@link EditorBuffer#HUGE_FILE_BYTES}.
     */
    void setReadOnly(boolean readOnly) {
        b.hugeFile = readOnly;
        applyEditable();
        if (readOnly) {
            setLargeFile(true); // also disables highlighting + minimap (and undo via applyUndoMode)
        } else {
            b.undo.applyUndoMode();
        }
    }
    /**
     * Marks that the loader could only read <b>part</b> of the file — the huge-file cap (first
     * {@link EditorBuffer#HUGE_FILE_BYTES}) or a log's tail (the <em>last</em> chunk). The buffer's content is then not
     * the file, and writing it back would <b>truncate the user's file on disk</b>, so the save path refuses.
     * Set by the loader on every load (cleared for a normal, complete read).
     */
    void setTruncatedLoad(boolean truncated) {
        b.truncatedLoad = truncated;
    }
    /**
     * User "View mode": makes the buffer non-editable without disabling any normal editor feature
     * (highlighting, minimap, folding, scrolling, and undo all stay on) — unlike {@link #setReadOnly},
     * which is the huge-file mechanism. Edits are blocked at the keyboard via {@code setEditable(false)};
     * the controller additionally guards its own edit commands (see {@code MainController.activeEditable}).
     */
    void setViewMode(boolean viewMode) {
        b.viewMode = viewMode;
        b.banners.viewModeReason = viewMode ? b.banners.viewModeReason : null;
        applyEditable();
    }
    /** View mode, with the banner saying why ({@code reason}) instead of the general sentence. */
    void setViewMode(boolean viewMode, @Nullable String reason) {
        b.banners.viewModeReason = reason;
        setViewMode(viewMode);
    }
    /** Keeps an empty loading shell non-editable without showing the user-facing View Mode banner. */
    void setLoading(boolean loading) {
        b.loading = loading;
        applyEditable();
        b.setLineHighlightOn(LineHighlighter.wanted(b.area)); // held back while loading (see LineHighlighter)
    }
    /** Applies editability to both views from the current flags, and tags the surface for CSS. */
    void applyEditable() {
        boolean editable = b.isEditable();
        b.area.setEditable(editable);
        if (b.area2 != null) {
            b.area2.setEditable(editable);
        }
        EditorBuffer.toggleStyleClass(b.area, "read-only", !editable);
        if (b.area2 != null) {
            EditorBuffer.toggleStyleClass(b.area2, "read-only", !editable);
        }
        b.banners.updateViewModeBar();
    }
}
