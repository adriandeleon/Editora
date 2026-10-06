package com.editora.editops;

/**
 * Decides, from the text changes one command made, whether it edited <em>at the caret</em> — the case in
 * which the view should follow the caret afterwards, as it does for typed text.
 *
 * <p>A command edits at the caret when one of its changes
 * <ul>
 *   <li>touches the lines the caret or selection was on when the command started (paste, kill, toggle
 *       comment, wrap the selection in {@code **bold**}, duplicate or move the line), or</li>
 *   <li>is where the command left the caret (undo and redo take the caret to the change they replay).</li>
 * </ul>
 * A change that replaces the <b>whole document</b> is not an edit at the caret unless the whole document was
 * what the user had selected: reformatting, sorting or converting the file leaves a deliberately scrolled
 * view where it is. A command that changes nothing, or only text elsewhere, never qualifies — whatever its
 * name.
 *
 * <p>Feed {@link #change} every change in the order it happened (offsets in the text as it was just before
 * that change), then ask {@link #editedAtCaret}. Pure and allocation-light: a few ints per change.
 */
public final class CaretEditTracker {

    /** The lines of the caret/selection at the start, kept in step with the changes seen so far. */
    private int from;

    private int to;
    private int length;
    private final boolean wholeDocumentSelected;
    private boolean touched;
    private boolean changed;

    // The changes that count, newest last: position, removed length, inserted length.
    private int[] log = new int[12];
    private int logged;

    /**
     * @param lineStart the start of the first line the selection (or bare caret) is on
     * @param lineEnd the end of the last line it is on, before the line break
     * @param selectionStart the selection's own start (the caret when nothing is selected)
     * @param selectionEnd the selection's own end
     * @param length the length of the text
     */
    public CaretEditTracker(int lineStart, int lineEnd, int selectionStart, int selectionEnd, int length) {
        this.from = Math.max(0, lineStart);
        this.to = Math.max(this.from, lineEnd);
        this.length = Math.max(0, length);
        this.wholeDocumentSelected = length > 0 && selectionStart <= 0 && selectionEnd >= length;
    }

    /** One change: {@code removed} characters at {@code position} replaced by {@code inserted} characters. */
    public void change(int position, int removed, int inserted) {
        int delta = inserted - removed;
        boolean wholeDocument = position == 0 && removed == length && length > 0 && !wholeDocumentSelected;
        length += delta;
        changed = true;
        if (wholeDocument) {
            // Nothing of the old text is left to be "at": the caret's lines are wherever the command puts it.
            from = -1;
            to = -1;
            touched = false;
            logged = 0;
            return;
        }
        int end = position + removed;
        if (end < from) {
            from += delta; // entirely above the caret's lines: they only shift
            to += delta;
        } else if (position <= to) {
            touched = true;
            from = Math.min(from, position);
            to = Math.max(to, end) + delta;
        }
        if (logged + 3 > log.length) {
            log = java.util.Arrays.copyOf(log, log.length * 2);
        }
        log[logged++] = position;
        log[logged++] = removed;
        log[logged++] = inserted;
    }

    /** Whether anything at all was changed (whole-document replacements included). */
    public boolean changed() {
        return changed;
    }

    /**
     * Whether the command edited at the caret; {@code caretAfter} is where it left the caret, in the text as
     * it is now.
     */
    public boolean editedAtCaret(int caretAfter) {
        if (touched) {
            return true;
        }
        // Walk back through the changes: is the caret inside (or at an edge of) what one of them inserted?
        int caret = caretAfter;
        for (int i = logged - 3; i >= 0; i -= 3) {
            int position = log[i];
            int removed = log[i + 1];
            int inserted = log[i + 2];
            if (caret >= position && caret <= position + inserted) {
                return true;
            }
            if (caret > position + inserted) {
                caret -= inserted - removed; // where that caret was before this change
            }
        }
        return false;
    }
}
