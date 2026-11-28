package com.editora.editor;

import java.util.List;

import com.editora.editor.notes.NoteDraft;
import org.fxmisc.richtext.CodeArea;

/**
 * Personal Notes in one buffer: applying the saved notes, capturing the anchor and context of a new note from the
 * selection or a line, and the note markers' visibility and click.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferNotes {

    private final EditorBuffer eb;

    BufferNotes(EditorBuffer eb) {
        this.eb = eb;
    }

    /** When false, the note inline marker + highlight are hidden (the {@code showNoteIndicators} setting). */
    boolean noteIndicators = true;
    /** Invoked when the user clicks a note's inline start marker — the controller opens the note editor. */
    java.util.function.BiConsumer<EditorBuffer, com.editora.config.PersonalNote> noteMarkerClick = (b, n) -> {};
    /** Chars of context captured before/after a note's selection (for re-anchoring). */
    private static final int CONTEXT_CHARS = 40;
    /** Replaces this buffer's notes from persisted state and repaints their highlight (see {@link #applyNotes(List, java.util.function.Consumer)}). */
    void applyNotes(List<com.editora.config.PersonalNote> saved) {
        eb.notes.restore(saved); // repaints the note overlay; the gutter shows no notes, so it is left alone
    }
    /**
     * As above; {@code movedThen} hears whether any note was re-anchored or (un)orphaned, so the caller can persist
     * the self-healed state. The notes are found in the text off the FX thread (PERF-01, Pauta 6.2.0).
     */
    void applyNotes(List<com.editora.config.PersonalNote> saved, java.util.function.Consumer<Boolean> movedThen) {
        eb.notes.restore(saved, movedThen);
    }
    /**
     * Captures a note draft (scope + anchor) from the current selection/caret: a multi-line selection is a
     * {@link com.editora.config.NoteScope#RANGE}, a single-line selection a {@code WORD}, and no selection a
     * {@code LINE} anchored to the caret's line. Used by the controller's "Add Personal Note" flow.
     */
    NoteDraft captureNoteDraft() {
        org.fxmisc.richtext.model.TwoDimensional.Bias fwd = org.fxmisc.richtext.model.TwoDimensional.Bias.Forward;
        CodeArea a = eb.focusedArea; // the view the user is in
        String doc = a.getText();
        var sel = a.getSelection();
        if (sel.getLength() > 0) {
            int start = sel.getStart();
            int end = sel.getEnd();
            var sp = a.offsetToPosition(start, fwd);
            var ep = a.offsetToPosition(end, fwd);
            com.editora.config.NoteScope scope = sp.getMajor() == ep.getMajor()
                    ? com.editora.config.NoteScope.WORD
                    : com.editora.config.NoteScope.RANGE;
            String prefix = doc.substring(Math.max(0, start - CONTEXT_CHARS), start);
            String suffix = doc.substring(end, Math.min(doc.length(), end + CONTEXT_CHARS));
            var anchor = new com.editora.config.TextAnchor(
                    sp.getMajor(), sp.getMinor(), ep.getMajor(), ep.getMinor(), a.getSelectedText(), prefix, suffix);
            return new NoteDraft(scope, anchor);
        }
        return captureLineNoteDraft(a.getCurrentParagraph());
    }
    /** Captures a LINE note anchor for a specific line, independent of the current selection/caret. */
    NoteDraft captureLineNoteDraft(int requestedLine) {
        int line = Math.max(0, Math.min(requestedLine, eb.area.getParagraphs().size() - 1));
        String doc =
                eb.documentTextSnapshot(); // the area's text (the narrowed region when narrowed), one copy per version
        String lineText = eb.area.getParagraph(line).getText();
        int lineLen = lineText.length();
        // Capture surrounding context for a LINE note too (like WORD/RANGE): the lines a LINE note lands on
        // (`    }`, `});`, `end`, a repeated import) are the most-duplicated in a file, so with empty context
        // relocation collapsed to pure proximity and the note re-anchored to the nearest identical line (#453).
        int lineStart = eb.area.getAbsolutePosition(line, 0);
        int lineEnd = lineStart + lineLen;
        String linePrefix = doc.substring(Math.max(0, lineStart - CONTEXT_CHARS), lineStart);
        String lineSuffix = doc.substring(lineEnd, Math.min(doc.length(), lineEnd + CONTEXT_CHARS));
        var anchor = new com.editora.config.TextAnchor(line, 0, line, lineLen, lineText, linePrefix, lineSuffix);
        return new NoteDraft(com.editora.config.NoteScope.LINE, anchor);
    }
    /** Shows/hides the notes' inline markers + highlight (the {@code showNoteIndicators} setting). */
    void setNoteIndicatorsVisible(boolean on) {
        if (noteIndicators == on) {
            return;
        }
        noteIndicators = on;
        eb.noteOverlay.setActive(on);
        if (!on) {
            eb.noteHover.hide();
        }
    }
    /** Sets the handler invoked when the user clicks a note's inline start marker (controller edits it). */
    void setNoteMarkerClick(java.util.function.BiConsumer<EditorBuffer, com.editora.config.PersonalNote> handler) {
        if (handler != null) {
            this.noteMarkerClick = handler;
        }
    }
}
