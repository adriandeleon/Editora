package com.editora.editor;

import java.time.Duration;

/**
 * Undo-group boundary policy (pure, unit-tested). RichTextFX's undo manager merges every contiguous
 * edit into one step — so a whole burst of typing (even across spaces and newlines) collapses into a
 * single {@code C-z}. To get VS Code/IntelliJ-style word/line-level undo, Editora forces a new undo
 * group at two kinds of boundary:
 *
 * <ul>
 *   <li><b>idle</b> — pause longer than {@link #PAUSE} and the next edit starts a fresh group (handled
 *       natively by the undo manager's {@code preventMergeDelay});</li>
 *   <li><b>token</b> — {@link #breakAfter} returns true after an edit that ends a word/line, so the
 *       caller calls {@code UndoManager.preventMerge()} and the next edit starts a fresh group.</li>
 * </ul>
 */
public final class UndoMerge {

    private UndoMerge() {}

    /** Pause after which a subsequent edit begins a new undo group (the manager's preventMergeDelay). */
    public static final Duration PAUSE = Duration.ofMillis(400);

    /**
     * Whether the undo group should end <em>after</em> this change, so the next edit is a separate undo
     * step. An insertion breaks once it ends in whitespace/newline (so the next word/line is its own
     * step); a deletion breaks when it removes whitespace (so deleting across a word boundary splits into
     * per-word steps). Larger replacements (paste, snippet, format) are already their own change.
     */
    public static boolean breakAfter(String inserted, String removed) {
        if (inserted != null && !inserted.isEmpty()) {
            return Character.isWhitespace(inserted.charAt(inserted.length() - 1));
        }
        if (removed != null && !removed.isEmpty()) {
            return Character.isWhitespace(removed.charAt(0))
                    || Character.isWhitespace(removed.charAt(removed.length() - 1));
        }
        return false;
    }
}
