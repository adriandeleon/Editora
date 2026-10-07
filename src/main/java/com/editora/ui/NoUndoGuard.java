package com.editora.ui;

import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * The gate every <em>programmatic</em> bulk edit passes before it changes a buffer that has no undo.
 *
 * <p>A file of 5 MB or more, or one with a very long line, is opened in large-file mode, whose undo manager
 * is a no-op ({@link EditorBuffer#isLargeFile()}). Typing there is the user's own, visible, one keystroke at
 * a time. A whole-document or bulk replacement is not — Replace in Files, a line transform, an external
 * tool's output, an AI rewrite, a CSV re-align, a lint fix, an agent / MCP / plugin / language-server edit
 * — and every one of them relies on "it is one undo step" as its safety net, which does not exist here: the
 * previous text, unsaved edits included, was simply gone.
 *
 * <p>So before such an edit the writer asks {@link #allow(EditorBuffer, String)} (or {@link #check} when it
 * needs the reason, e.g. to answer a remote caller). For a buffer with undo the answer is yes, at no cost.
 * For one without, the buffer's current text is first stored in Local History as a labelled revision, on
 * disk before this returns; the user is told that undo is unavailable and where the copy is. When no copy
 * can be taken — Local History is off, the buffer is untitled or remote, the write failed — the answer is
 * <b>no</b>, with a message saying why, and the writer must not make the edit.
 *
 * <p>Call it on the FX thread, immediately before the edit and after every "nothing to do" check, so a
 * no-op does not leave a revision behind. Both the notice and the refusal are posted to the status line
 * <em>after</em> the current FX event, so they are not overwritten by the status the writer itself sets.
 *
 * <p>The window installs one guard on each of its buffers ({@link #install}); a buffer that has none
 * (a detached buffer in a test) cannot be copied and is refused in large-file mode.
 */
final class NoUndoGuard {

    /** Whether the previous text of a no-undo buffer could be stored, and if not, why. */
    enum Copy {
        STORED,
        /** Local History is switched off (or suppressed by Simple UI mode). */
        HISTORY_OFF,
        /** The buffer has no local file to file the revision under (untitled, or remote). */
        NO_LOCAL_FILE,
        /** The copy could not be written (I/O error, timeout), or no guard is installed on the buffer. */
        FAILED
    }

    /** Stores a buffer's current text durably under {@code label}; see {@code HistoryCoordinator.recordSafetyCopy}. */
    @FunctionalInterface
    interface Copier {
        Copy copy(EditorBuffer buffer, String label);
    }

    /**
     * The answer for one edit. {@code allowed} is the only thing a writer must act on. {@code safetyCopy} is
     * true when the buffer has no undo and its previous text was stored first. {@code message} is what the
     * user is told — the refusal reason, or the "no undo, copy is in Local History" notice — and
     * {@code null} for an ordinary undoable buffer.
     */
    record Verdict(boolean allowed, boolean safetyCopy, String message) {
        static final Verdict UNDOABLE = new Verdict(true, false, null);
    }

    private static final Object KEY = new Object();

    private final Copier copier;
    private final Consumer<String> status;

    NoUndoGuard(Copier copier, Consumer<String> status) {
        this.copier = copier;
        this.status = status;
    }

    /** Makes {@code guard} the one {@link #check} consults for {@code buffer}. */
    static void install(EditorBuffer buffer, NoUndoGuard guard) {
        buffer.getArea().getProperties().put(KEY, guard);
    }

    /** True when an edit of {@code buffer} cannot be undone, so a bulk edit needs a safety copy first. */
    static boolean needed(EditorBuffer buffer) {
        return buffer != null && buffer.isLargeFile();
    }

    /**
     * Whether the programmatic bulk edit {@code operation} (a user-facing name, e.g. "Replace in Files") may
     * change {@code buffer} now. False means: do not edit; the user has been told why.
     */
    static boolean allow(EditorBuffer buffer, String operation) {
        return check(buffer, operation).allowed();
    }

    /** {@link #allow} with the reason, for a caller that reports the outcome itself (an MCP or agent reply). */
    static Verdict check(EditorBuffer buffer, String operation) {
        if (!needed(buffer)) {
            return Verdict.UNDOABLE;
        }
        NoUndoGuard guard = (NoUndoGuard) buffer.getArea().getProperties().get(KEY);
        Copy copy = guard == null
                ? Copy.FAILED
                : guard.copier.copy(buffer, tr("history.label.beforeNoUndoEdit", operation));
        Verdict verdict = verdict(copy, operation);
        if (guard != null) {
            // After the current event: the writer's own status ("Replaced 12 matches…") must not hide this.
            Platform.runLater(() -> guard.status.accept(verdict.message()));
        }
        return verdict;
    }

    /** The verdict for a no-undo buffer given how the safety copy went. Pure apart from the catalog lookup. */
    static Verdict verdict(Copy copy, String operation) {
        String key = messageKey(copy);
        return new Verdict(copy == Copy.STORED, copy == Copy.STORED, tr(key, operation));
    }

    /** The catalog key of the message for {@code copy}. Pure. */
    static String messageKey(Copy copy) {
        return switch (copy) {
            case STORED -> "status.noUndo.safetyCopy";
            case HISTORY_OFF -> "status.noUndo.cannotHistoryOff";
            case NO_LOCAL_FILE -> "status.noUndo.cannotNoLocalFile";
            case FAILED -> "status.noUndo.cannotCopy";
        };
    }
}
