package com.editora.editor;

import java.util.List;

import com.editora.editops.IndentWindow;
import com.editora.editops.MultiTab;
import com.editora.text.SearchMatcher;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Multiple cursors and column selection in a buffer's views (the RichTextFX fork's {@link MultiCarets}, installed on
 * each view while enabled): adding carets, selecting occurrences, splitting a selection into lines, line commands
 * and edits applied at every caret as one undo step, multi-caret movement, clipboard and undo. With one caret each
 * operation takes the single-caret path.
 */
final class BufferCarets {

    private final EditorBuffer b;

    BufferCarets(EditorBuffer b) {
        this.b = b;
    }

    /** Multiple cursors + Alt+drag column/box selection (RichTextFX fork add-on). Installed on {@link EditorBuffer#area}
     *  (and {@link EditorBuffer#area2} when split) while {@link #multiCaretEnabled}; transparent with one caret. */
    boolean multiCaretEnabled;

    @Nullable
    MultiCarets multiCaret;

    @Nullable
    MultiCarets multiCaret2;

    /**
     * Enables/disables the multi-caret add-on. When on, installs {@link MultiCarets} on the
     * primary area (and the secondary split view if present); when off, removes it (collapsing to one
     * caret). Skipped for huge files (editing is already inert there). Idempotent.
     */
    void setMultiCaretEnabled(boolean enabled) {
        this.multiCaretEnabled = enabled;
        if (enabled && !b.hugeFile) {
            if (multiCaret == null) {
                multiCaret = MultiCarets.install(b.area, b.typingAids::tabEdit);
            }
            if (b.area2 != null && multiCaret2 == null) {
                multiCaret2 = MultiCarets.install(b.area2, b.typingAids::tabEdit);
            }
        } else {
            disposeMultiCaret();
        }
    }

    void disposeMultiCaret() {
        if (multiCaret != null) {
            multiCaret.dispose();
            multiCaret = null;
        }
        if (multiCaret2 != null) {
            multiCaret2.dispose();
            multiCaret2 = null;
        }
    }

    /** The multi-caret add-on of the area that has focus (secondary split view, else primary), or null. */
    private @Nullable MultiCarets activeCarets() {
        return multiCaret2 != null && b.area2 != null && b.area2.isFocused() ? multiCaret2 : multiCaret;
    }

    /** Runs {@code op} on {@link #activeCarets()} when it has extra carets; false → the single-caret path. */
    private boolean withExtras(java.util.function.Consumer<MultiCarets> op) {
        MultiCarets m = activeCarets();
        if (m == null || !m.hasExtras()) {
            return false;
        }
        op.accept(m);
        return true;
    }

    /** True while any area of this buffer has more than the primary caret (extra carets / box selection). */
    boolean hasMultipleCarets() {
        return (multiCaret != null && multiCaret.hasExtras()) || (multiCaret2 != null && multiCaret2.hasExtras());
    }

    /** Whether any caret of the focused area, primary or extra, has text selected. */
    boolean anyCaretSelection() {
        MultiCarets m = activeCarets();
        return m != null ? m.anySelection() : b.getFocusedArea().getSelection().getLength() > 0;
    }

    /** Undo ({@code redo == false}) or Redo on {@code a}; extra carets keep their places and stay usable. */
    void undoOrRedo(CodeArea a, boolean redo) {
        MultiCarets m = a == b.area2 ? multiCaret2 : multiCaret;
        Runnable op = redo ? a::redo : a::undo;
        if (m == null) {
            op.run();
        } else {
            m.keepingPrimary(op);
        }
    }

    /** Selects the next occurrence of the current selection as an additional caret (VS Code Cmd/Ctrl+D). */
    void addCaretNextOccurrence() {
        MultiCarets m = activeCarets();
        if (m != null) {
            m.addNextOccurrence();
        }
    }

    /**
     * Places a caret with selection at each of {@code ranges} (the one containing {@code anchorStart} stays
     * primary), for "select all occurrences" / "select all matches". Capped at
     * {@link EditorBuffer#MAX_OCCURRENCE_CARETS}. Returns the number of carets placed; 0 when {@code ranges} is empty.
     */
    int placeOccurrenceCarets(List<int[]> ranges, int anchorStart) {
        if (ranges == null || ranges.isEmpty()) {
            return 0;
        }
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        int primaryIdx = com.editora.editops.SelectOccurrences.primaryIndex(ranges, anchorStart);
        int[] primary = ranges.get(primaryIdx);
        MultiCarets carets = activeCarets();
        if (carets == null) { // multi-caret unavailable → just select the primary occurrence
            a.selectRange(primary[0], primary[1]);
            a.requestFollowCaret();
            return 1;
        }
        carets.collapse();
        a.selectRange(primary[0], primary[1]);
        int placed = 1;
        for (int i = 0; i < ranges.size() && placed < EditorBuffer.MAX_OCCURRENCE_CARETS; i++) {
            if (i == primaryIdx) {
                continue;
            }
            int[] r = ranges.get(i);
            carets.getManager().addCaretWithSelection(r[0], r[1]);
            placed++;
        }
        a.requestFollowCaret();
        return placed;
    }

    /**
     * Selects every occurrence of the current selection (or, with none, the word under the caret) as a
     * multi-caret selection — VS Code's {@code selectHighlights} (Ctrl+Shift+L). Case-sensitive literal
     * matching (whole-word when seeded from the caret's word). Returns the number of carets placed (0 when there's nothing to match).
     */
    int selectAllOccurrences() {
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        String text = a.getText();
        String query;
        int anchor;
        String sel = a.getSelectedText();
        if (!sel.isEmpty()) {
            query = sel;
            anchor = a.getSelection().getStart();
        } else {
            int[] w = com.editora.editops.SelectOccurrences.wordAt(text, a.getCaretPosition(), b.language);
            if (w == null) {
                return 0;
            }
            query = text.substring(w[0], w[1]);
            anchor = w[0];
        }
        // A query seeded from the word under a bare caret matches whole words only (id, not valid / width).
        List<int[]> matches = SearchMatcher.matches(text, query, true, false, sel.isEmpty());
        return placeOccurrenceCarets(matches, anchor);
    }

    /**
     * Sublime's Split into Lines ({@code atLineEnds} false: each line's selected part becomes a selection) or
     * VS Code's Add Cursors to Line Ends (true: a bare caret at the end of each selected line), for every
     * selection there is. Returns the number of carets placed; 0 when nothing is selected.
     */
    int splitSelectionIntoLines(boolean atLineEnds) {
        MultiCarets carets = activeCarets();
        CodeArea a = b.getFocusedArea();
        if (carets == null) {
            return 0;
        }
        String text = a.getText();
        java.util.TreeMap<Long, int[]> ranges = new java.util.TreeMap<>(); // in document order, none twice
        for (int[] sel : carets.selections()) {
            List<int[]> parts = atLineEnds
                    ? com.editora.editops.CaretPlans.caretsAtLineEnds(text, sel[0], sel[1])
                    : com.editora.editops.CaretPlans.splitIntoLines(text, sel[0], sel[1]);
            for (int[] r : parts) {
                ranges.put(((long) r[0] << 32) | r[1], r);
            }
        }
        return ranges.isEmpty()
                ? 0
                : placeOccurrenceCarets(
                        new java.util.ArrayList<>(ranges.values()),
                        a.getSelection().getStart());
    }

    /** Moves the newest selection to the next occurrence (VS Code Ctrl+K Ctrl+D); false when it cannot. */
    boolean skipOccurrence() {
        MultiCarets carets = activeCarets();
        return carets != null && carets.skipOccurrence();
    }

    /** Removes the extra caret added last (VS Code Ctrl+U); false when there is only one caret. */
    boolean undoLastCaret() {
        MultiCarets carets = activeCarets();
        return carets != null && carets.undoLastCaret();
    }

    /**
     * Runs a line command at every caret of the focused area — the primary and the extras — as one undoable
     * edit. Collapsed folds on the lines it changes are opened first, so no hidden line is edited unseen.
     * False when the area cannot take it (read-only, a huge file for the indent commands).
     */
    boolean lineCommand(EditorBuffer.LineCommand command) {
        CodeArea a = b.getFocusedArea();
        if (command != EditorBuffer.LineCommand.SELECT && !a.isEditable()) {
            return false;
        }
        MultiCarets carets = activeCarets();
        if (command != EditorBuffer.LineCommand.SELECT && a == b.area) {
            for (int[] sel :
                    carets != null ? carets.selections() : List.of(new int[] {a.getAnchor(), a.getCaretPosition()})) {
                b.folds.expandHeaderAt(Math.min(sel[0], sel[1]));
                b.folds.expandHeaderAt(Math.max(sel[0], sel[1]));
            }
        }
        return atEachCaret((text, s, e) -> switch (command) {
            case INSERT_BELOW ->
                com.editora.editops.LineCommands.insertBelow(
                        text, s, e, at -> IndentWindow.enterEdit(IndentKeys.doc(a), at, b.language, b.indentUnit()));
            case INSERT_ABOVE -> com.editora.editops.LineCommands.insertAbove(text, s, e);
            case DELETE -> com.editora.editops.LineCommands.deleteLines(text, s, e);
            case SELECT -> com.editora.editops.LineCommands.selectLine(text, s, e);
            case INDENT, OUTDENT ->
                com.editora.editops.LineCommands.indentLines(
                        text,
                        s,
                        e,
                        command == EditorBuffer.LineCommand.OUTDENT,
                        (from, to, back) -> b.typingAids.tabEdit(from, to, back));
        });
    }

    /**
     * Complete Current Statement ({@link com.editora.editops.CompleteStatement}) at every caret, as one undoable
     * edit; in a language it does not know, Insert Line Below. False when the area is read-only.
     */
    boolean completeStatement() {
        CodeArea a = b.getFocusedArea();
        if (!a.isEditable()) {
            return false;
        }
        com.editora.editops.CompleteStatement.Style style = com.editora.editops.CompleteStatement.styleFor(b.language);
        if (style == null) {
            return lineCommand(EditorBuffer.LineCommand.INSERT_BELOW);
        }
        String unit = b.indentUnit();
        return atEachCaret((text, s, e) -> com.editora.editops.CompleteStatement.edit(
                text, s, e, style, unit, at -> IndentWindow.enterEdit(IndentKeys.doc(a), at, b.language, unit)));
    }

    /**
     * Runs {@code edit} at every caret of the focused area — the primary and the extras, or the one caret when
     * multiple cursors are off — as one undoable edit. False when {@code edit} declines a caret (returns null);
     * an exception it throws propagates before anything changes.
     */
    boolean atEachCaret(EditorBuffer.CaretEdit edit) {
        CodeArea a = b.getFocusedArea();
        MultiCarets carets = activeCarets();
        String text = a.getText();
        MultiTab.CaretEdits edits = (s, e, ignored) -> edit.edit(text, s, e);
        boolean done;
        if (carets != null) {
            done = carets.applyAtEachCaret(edits, false);
        } else { // multiple cursors off: the same plan for the one caret
            MultiTab.Plan plan = MultiTab.plan(List.of(new int[] {a.getAnchor(), a.getCaretPosition()}), false, edits);
            done = plan != null;
            if (plan != null) {
                for (MultiTab.Change c : plan.changes()) {
                    a.replaceText(c.from(), c.to(), c.replacement()); // at most one: a single caret
                }
                a.selectRange(
                        plan.ranges().get(0).anchor(), plan.ranges().get(0).caret());
            }
        }
        if (done) {
            a.requestFollowCaret();
        }
        return done;
    }

    /** Adds a caret on the line above the topmost caret. */
    void addCaretAbove() {
        addCaretOnNextLine(false);
    }

    /** Adds a caret on the line below the bottommost caret. */
    void addCaretBelow() {
        addCaretOnNextLine(true);
    }

    private void addCaretOnNextLine(boolean down) {
        MultiCarets m = activeCarets();
        if (m != null) {
            m.addCaretOnNextLine(down);
        }
    }

    // --- Multi-caret movement fan-out (#635) ------------------------------------------------------
    // The fork's multi-caret movement lives in its node-level InputMap, which the scene-level
    // KeyDispatcher preempts for the Emacs chords. These expose that movement so MainController's
    // nav.* commands can fan out to every caret when extras exist; each returns whether it handled the
    // move (false → no extra carets, so the caller runs the normal single-caret motion). {@code select}
    // extends the selection (= Emacs mark active), mirroring {@code selPolicy()}.

    /** Moves every caret horizontally by {@code amount} chars (or words); see {@link #addCaretBelow}. */
    boolean multiMoveHorizontal(int amount, boolean byWord, boolean select) {
        return withExtras(m -> m.moveHorizontal(amount, byWord, select));
    }

    /** Moves every caret one line up ({@code down=false}) or down. */
    boolean multiMoveVertical(boolean down, boolean select) {
        return withExtras(m -> m.moveVertical(down, select));
    }

    /** Moves every caret to its line start ({@code toEnd=false}) or line end. */
    boolean multiMoveLineBoundary(boolean toEnd, boolean select) {
        return withExtras(m -> m.moveLineBoundary(toEnd, select));
    }

    /** True when {@code a} currently has extra carets, so this buffer's single-caret KEY filters
     *  (auto-indent/close, snippets, completion, view paging) should stand down and let the fork's
     *  multi-caret input map handle the key for every caret. */
    boolean multiCaretActiveOn(@Nullable CodeArea a) {
        if (a == b.area) {
            return multiCaret != null && multiCaret.hasExtras();
        }
        if (a == b.area2) {
            return multiCaret2 != null && multiCaret2.hasExtras();
        }
        return false;
    }

    /** Copies every caret's selection to the clipboard (VS Code one-line-per-caret) when extra carets
     *  exist; returns whether it handled it (so the caller can fall back to the single-caret copy). */
    boolean multiCaretCopy() {
        return withExtras(m -> m.copy());
    }

    /** Cuts every caret's selection (multi-caret aware); returns whether it handled it. */
    boolean multiCaretCut() {
        return withExtras(m -> m.cut());
    }

    /** Pastes at every caret (distributing clipboard lines one per caret); returns whether it handled it. */
    boolean multiCaretPaste() {
        return withExtras(m -> m.paste());
    }

    /** Puts the whole current line (including a trailing newline) on the clipboard — the empty-selection
     *  Copy of VS Code's {@code editor.emptySelectionClipboard}. Leaves the document untouched. */
    void copyCurrentLine() {
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        int p = a.getCurrentParagraph();
        LineClipboard.copy(a, p, b.folds.hiddenRunEnd(p)); // a collapsed fold's header takes its body along
    }

    /** Cuts the whole current line — copies it then deletes the line as one undoable edit. */
    void cutCurrentLine() {
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        int p = a.getCurrentParagraph();
        LineClipboard.cut(a, p, b.folds.hiddenRunEnd(p));
    }

    /** Collapses any extra carets / box selection back to a single caret. */
    void collapseCarets() {
        if (multiCaret != null) {
            multiCaret.collapse();
        }
        if (multiCaret2 != null) {
            multiCaret2.collapse();
        }
    }
}
