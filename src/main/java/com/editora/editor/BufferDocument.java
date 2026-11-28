package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import com.editora.editops.LineEndings;
import com.editora.editops.WholeDocumentEdit;
import com.editora.editor.EditorBuffer.PreparedEdit;
import com.editora.editor.fold.NarrowCarry;
import com.editora.perf.OffFx;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.jspecify.annotations.Nullable;

/**
 * Putting text into a buffer: the first load (prepared off the FX thread when it is large), a reload that keeps the
 * caret and undo where it can, whole-document rewrites from a command, typing a string as keystrokes, and the
 * vertical line moves with their goal column.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferDocument {

    private final EditorBuffer b;

    BufferDocument(EditorBuffer b) {
        this.b = b;
    }

    /**
     * Emacs-style goal column for line-up/line-down ({@code C-p}/{@code C-n}): the column to aim for
     * when moving vertically, preserved across short lines until any other caret move resets it.
     * {@code -1} means "recompute from the caret on the next vertical move".
     */
    private int goalColumn = -1;
    /** True only while {@link #moveLine} is updating the caret, so its own move doesn't reset the goal. */
    private boolean movingByLine;
    /**
     * Replays a run of literally-typed text from a recorded macro, routing each character through the same
     * typing assists the live key filters use (auto-close, Enter auto-indent, closer dedent, smart Tab), so
     * a replayed {@code (} pairs and a replayed newline re-indents exactly as when typed by hand.
     */
    void typeString(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            b.typeChar(text.charAt(i));
        }
    }
    /** Replaces the document content (e.g. after loading a file) and resets the dirty flag. */
    void setContent(String content) {
        setInitialContent(content);
    }
    /**
     * Installs freshly-loaded content without immediately copying the whole RichTextFX document back out
     * merely to establish the clean baseline. Size-based modes should be applied before this call, so the
     * initial replacement never enters an undo manager or feature that the load profile will disable.
     */
    void setInitialContent(String content) {
        setInitialContent(content, false);
    }
    /** As above, optionally splitting giant paragraphs into style segments ({@link InitialDocument#segmented}). */
    void setInitialContent(String content, boolean segmentLongLines) {
        setInitialContent(
                segmentLongLines ? InitialDocument.prepare(b.area, content, true) : InitialDocument.ofText(content));
    }
    /**
     * {@link #setContent} for a caller on the FX thread (PERF-01, Pauta 6.2.0): the text's line endings are
     * resolved and its fingerprint worked out off the FX thread, and it is installed when that lands, as a load
     * is. {@code then} runs once it is in; it does not run, and nothing is installed, when the buffer was closed or
     * edited meanwhile (its text is the user's then).
     */
    void setContentLater(String content, Runnable then) {
        setInitialContentLater(content, false, then);
    }
    /** As {@link #setContentLater}, optionally splitting giant paragraphs ({@link #setInitialContent(String, boolean)}). */
    void setInitialContentLater(String content, boolean segmentLongLines, Runnable then) {
        long version = b.docVersion;
        installsPending++;
        OffFx.then(EditorBuffer.PREPARE_TEXT_SITE, () -> prepareInitialContent(content, segmentLongLines), answer -> {
            InitialDocument prepared = answer.value();
            if (prepared != null && !b.disposed && b.docVersion == version) {
                setInitialContent(prepared);
                then.run();
            }
            if (--installsPending == 0 && !b.disposed) {
                List<Runnable> waiting = new ArrayList<>(afterInstalls);
                afterInstalls.clear();
                waiting.forEach(Runnable::run);
            }
        });
    }
    /** Texts given by {@link #setInitialContentLater} not installed yet. FX thread. */
    private int installsPending;
    /** What waits for {@link #installsPending} to reach none. */
    private final List<Runnable> afterInstalls = new ArrayList<>();
    /**
     * Runs {@code action} once the text given by {@link #setContentLater} is in (now when none is pending): a jump
     * to a line, say, of a tab opened with its text. Dropped when the buffer is closed first.
     */
    void whenTextInstalled(Runnable action) {
        if (installsPending == 0) {
            action.run();
        } else {
            afterInstalls.add(action);
        }
    }
    /**
     * Builds a load's paragraphs for {@link #setInitialContent(InitialDocument)}; safe off the FX thread. Meant
     * for the read worker, so the text's fingerprint — what the saved baseline keeps once the buffer is edited —
     * is computed here too, and never on the FX thread.
     */
    InitialDocument prepareInitialContent(String content, boolean segmentLongLines) {
        return InitialDocument.prepare(b.area, content, segmentLongLines).fingerprinted();
    }
    /** Installs a prepared load: one document swap, with the loaded String shared as baseline and snapshot. */
    void setInitialContent(InitialDocument loaded) {
        // The area never holds a '\r', so remember the file's line ending here and keep the baseline in the
        // same normalised form — a CRLF baseline could never equal the document again (edit + undo stayed dirty).
        b.lineEnding = loaded.lineEnding();
        b.dirtyState.cleanLineEnding = b.lineEnding;
        String initial = loaded.text();
        b.widen(); // a fresh document supersedes any narrowing of the old one
        Runnable refilter = b.logView.suspendFilter(true);
        // Establish the baseline before the change event. Otherwise an async loading shell briefly becomes
        // dirty during replace(), which promotes a disposable preview tab before the method can clear it.
        // A truncated load has no baseline (null): it is never compared, and pins no copy once a log tail moves on.
        b.baseline = b.truncatedLoad ? null : SavedBaseline.of(initial, loaded.textFingerprint());
        b.dirtyState.forcedDirty = false;
        b.documentSnapshots.expect(initial); // the dirty check inside the change event must not copy it back out
        if (loaded.document() != null) {
            b.area.replace(0, b.area.getLength(), loaded.document());
        } else {
            b.area.replaceText(initial);
        }
        if (b.area.getLength() == initial.length()) {
            b.documentSnapshots.seed(b.docVersion, initial);
        } else {
            b.documentSnapshots.expect(null);
            b.documentSnapshots.invalidate();
        }
        b.forgetHistoryAtNarrowBoundary(
                false); // the load is the baseline, not an undo step: undoing it emptied the file
        b.captureUndoCheckpoint(); // ...and the Undo History baseline is the loaded text, not the loading shell
        refilter.run();
        b.dirty.set(false);
        b.gitLines.reset(); // freshly loaded: buffer lines are the disk's lines again
        b.runGutter.recomputeRun(
                true); // detect a runnable file on load (drives the Run glyph), scanning off the FX thread
    }
    /**
     * Installs a re-read of the file into a buffer that already shows it. Unlike {@link #setInitialContent},
     * which swaps the whole document, only the span that differs is rewritten ({@link WholeDocumentEdit}):
     * the paragraphs, styles and folds outside it survive and nothing is split again on the FX thread. Like a
     * load, it is not an undo step — the history is forgotten, so undo cannot resurrect text the disk no
     * longer has — and the loaded text becomes the snapshot, the Undo History baseline and, through the
     * caller's {@link EditorBuffer#markClean}, the saved baseline. Not for a load that needs the segmented or capped
     * profile ({@code longLine}, truncated): those install through {@link #setInitialContent}.
     */
    void reloadContent(InitialDocument loaded) {
        b.widen();
        reloadContent(loaded, PreparedEdit.between(b.docVersion, b.documentTextSnapshot(), loaded.text()));
    }
    /**
     * As {@link #reloadContent(InitialDocument)} with the differing span {@code prepared} worked out off the FX thread
     * (PERF-01, Pauta 6.2.0) against the accessible text it names. When the document is no longer at that version,
     * or is narrowed, the load is installed whole instead ({@link #setInitialContent(InitialDocument)}), which
     * searches nothing either.
     */
    void reloadContent(InitialDocument loaded, @Nullable PreparedEdit prepared) {
        if (prepared == null || prepared.version() != b.docVersion || b.isNarrowed()) {
            setInitialContent(loaded);
            return;
        }
        b.lineEnding = loaded.lineEnding();
        String text = loaded.text();
        Runnable refilter = b.logView.suspendFilter(true);
        applyWholeEdit(b.area, prepared.edit());
        if (b.area.getLength() == text.length()) {
            b.documentSnapshots.seed(b.docVersion, text); // the rewrite left the document equal to the loaded String
        }
        b.forgetHistoryAtNarrowBoundary(false);
        b.captureUndoCheckpoint();
        refilter.run();
        b.gitLines.reset(); // the disk's lines again
        b.runGutter.recomputeRun(true);
    }
    /**
     * Places the caret at the start of the document and scrolls to the top. Called after a file is
     * loaded so opening a file always lands on the first line ({@code replaceText} leaves the caret
     * at the end, and restoring saved folds moves it to a fold header).
     */
    void goToStart() {
        b.area.moveTo(0);
        try {
            b.area.showParagraphAtTop(0);
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet; it defaults to the top, so there is nothing more to do.
        }
    }
    /**
     * Moves the caret one line down ({@code delta == 1}) or up ({@code delta == -1}) in the focused
     * view, Emacs-style: the target column is the "goal column" — the column the caret was at when the
     * vertical run began — clamped to the target line's length, so passing through short lines does not
     * lose the original column. Any non-vertical caret move resets the goal (see the caret listeners).
     */
    void moveLine(int delta, SelectionPolicy policy) {
        CodeArea a = b.focusedArea;
        if (goalColumn < 0) {
            goalColumn = a.getCaretColumn();
        }
        int target = a.getCurrentParagraph() + delta;
        if (target < 0 || target >= a.getParagraphs().size()) {
            return; // already at the first/last line
        }
        int col = Math.min(goalColumn, a.getParagraphLength(target));
        movingByLine = true;
        try {
            a.moveTo(target, col, policy);
        } finally {
            movingByLine = false;
        }
        a.requestFollowCaret();
    }
    /** Clears the goal column unless the caret change came from {@link #moveLine} itself. */
    void resetGoalColumn() {
        if (!movingByLine) {
            goalColumn = -1;
        }
    }
    /**
     * Replaces the entire document, widening first. Every caller that computes a replacement from
     * {@link EditorBuffer#getContent()} — a find-and-replace across files, a local-history restore, a diff apply, a
     * reload from disk — must come through here or {@link #setContent}: writing whole-document text into a
     * narrowed area would leave the held-aside text alongside it and duplicate the file.
     */
    void replaceWholeDocument(String text) {
        b.widen();
        Runnable refilter = b.logView.suspendFilter(false);
        replaceVisibleText(b.area, text);
        refilter.run();
    }
    /**
     * Makes the accessible text {@code text} as ONE undo step that records only the span that differs (see
     * {@link WholeDocumentEdit}) — nothing at all when it is already equal. The caret ends at the document
     * end, where replacing everything left it; callers that want it elsewhere move it afterwards.
     */
    void replaceVisibleText(CodeArea view, String text) {
        applyWholeEdit(view, WholeDocumentEdit.between(b.documentTextSnapshot(), LineEndings.toLf(text)));
    }

    private void applyWholeEdit(CodeArea view, @Nullable WholeDocumentEdit edit) {
        if (edit != null) {
            b.preventUndoMerge();
            view.replaceText(edit.start(), edit.end(), edit.replacement());
            view.moveTo(view.getLength());
            b.preventUndoMerge();
        }
    }
    /**
     * The accessible text and its version, for a worker to {@linkplain PreparedEdit#between prepare} a whole-document
     * rewrite on; widens a narrowed buffer first, as {@link #replaceWholeDocument} does. FX thread; no copy beyond the
     * shared snapshot.
     */
    PreparedEdit.Base baseForWholeEdit() {
        b.widen();
        return new PreparedEdit.Base(b.docVersion, b.documentTextSnapshot());
    }
    /**
     * Applies a rewrite {@linkplain PreparedEdit#between prepared} off the FX thread, as {@link #replaceWholeDocument}
     * would, only when the document is still at the version it was worked out for (and not narrowed since). False,
     * with nothing done, otherwise: the caller works it out again on the text as it is now.
     */
    boolean applyPreparedWholeEdit(PreparedEdit prepared) {
        if (b.disposed || b.docVersion != prepared.version() || b.isNarrowed()) {
            return false;
        }
        Runnable refilter = b.logView.suspendFilter(false);
        applyWholeEdit(b.area, prepared.edit());
        refilter.run();
        return true;
    }
    /**
     * Applies to the shown text (a narrowed buffer's region) a rewrite {@linkplain PreparedEdit#between prepared} off
     * the FX thread against it, as {@link #replaceVisibleText} would, only when the document is still at its version.
     */
    boolean applyPreparedVisibleEdit(CodeArea view, PreparedEdit prepared) {
        if (b.disposed || b.docVersion != prepared.version()) {
            return false;
        }
        applyWholeEdit(view, prepared.edit());
        return true;
    }
    /**
     * Makes the whole document {@code wholeText}, with the span that differs {@code prepared} off the FX thread
     * against {@link EditorBuffer#getContent()} at its version. False, with nothing done, when the document is no longer at that
     * version. When it is, but the shown text is not the whole document (narrowed, or a filtered log), the span is
     * worked out again off the FX thread against what is shown ({@link #replaceWholeDocumentLater}).
     */
    boolean applyPreparedWholeText(PreparedEdit prepared, String wholeText) {
        if (b.disposed || b.docVersion != prepared.version()) {
            return false;
        }
        if (b.isNarrowed() || b.logView.filtered()) {
            replaceWholeDocumentLater(wholeText, applied -> {});
            return true;
        }
        applyWholeEdit(b.area, prepared.edit());
        return true;
    }
    /**
     * {@link #replaceWholeDocument} without searching the text on the FX thread (PERF-01, Pauta 6.2.0): widens now,
     * works out the span that differs off the FX thread and applies it as one undo step when it lands, if the
     * document is unchanged. Text typed meanwhile is never overwritten: {@code text} was decided on the document as
     * it was, so the rewrite is then dropped. {@code done} hears true once applied (or nothing differed), false when
     * it was dropped or the buffer was closed.
     */
    void replaceWholeDocumentLater(String text, java.util.function.Consumer<Boolean> done) {
        rewriteLater(b.area, text, true, done);
    }
    /** {@link #replaceVisibleText} worked out off the FX thread, as {@link #replaceWholeDocumentLater}. */
    void replaceVisibleTextLater(CodeArea view, String text, java.util.function.Consumer<Boolean> done) {
        rewriteLater(view, text, false, done);
    }

    private void rewriteLater(CodeArea view, String text, boolean whole, java.util.function.Consumer<Boolean> done) {
        if (b.disposed) {
            done.accept(false);
            return;
        }
        if (whole) {
            b.widen();
        }
        boolean narrowed = b.isNarrowed();
        long version = b.docVersion;
        String current = b.documentTextSnapshot();
        OffFx.then(EditorBuffer.WHOLE_EDIT_SITE, () -> PreparedEdit.between(version, current, text), answer -> {
            PreparedEdit prepared = answer.value();
            if (b.disposed || prepared == null) {
                done.accept(false);
                return;
            }
            if (b.docVersion == version && b.isNarrowed() == narrowed) {
                Runnable refilter = whole ? b.logView.suspendFilter(false) : () -> {};
                applyWholeEdit(view, prepared.edit());
                refilter.run();
                done.accept(true);
            } else {
                done.accept(false);
            }
        });
    }

    /**
     * The length of the document's last line, in characters. Together with {@link EditorBuffer#lineCount} this describes
     * the document <em>end</em>, which an LSP range request must be clamped to: a range naming a line past
     * the end makes a server answer with an empty result rather than an error, which is indistinguishable
     * from "nothing to report" (#715).
     */
    int lastLineLength() {
        var paragraphs = b.area.getParagraphs();
        return paragraphs.isEmpty() ? 0 : paragraphs.get(paragraphs.size() - 1).length();
    }
    /**
     * A 0-based line's text, or {@code ""} when the index is out of range — so a caller reading lines a
     * server reported against a document that has since moved gets an empty string rather than an
     * exception. Used by inlay-hint filtering to classify the argument at a hint's column (#823).
     */
    String lineText(int line) {
        var paragraphs = b.area.getParagraphs();
        return (line < 0 || line >= paragraphs.size())
                ? ""
                : paragraphs.get(line).getText();
    }
    /**
     * True when {@code target} is (or sits inside) one of this buffer's editor areas — i.e. a key event
     * aimed at the document itself rather than at an overlay, the find bar, or a tool-window field.
     *
     * <p>The macro recorder needs this because the key hooks live on a <b>scene</b> filter, which sees every
     * key in the window: without it, the text typed into the command palette or the find bar was recorded
     * and then replayed straight into the document.
     */
    boolean ownsKeyTarget(javafx.event.EventTarget target) {
        for (javafx.scene.Node n = target instanceof javafx.scene.Node node ? node : null;
                n != null;
                n = n.getParent()) {
            if (n == b.area || (b.area2 != null && n == b.area2)) {
                return true;
            }
        }
        return false;
    }
    /**
     * The <b>whole document</b>, including any part hidden by narrowing. This is what every caller that
     * means "the file" wants — save, autosave, LSP sync, diff, local history, find-in-files, the MCP
     * bridge, the plugin API — and returning the full text here is what makes narrowing safe by
     * construction rather than by each of them remembering to ask. Use {@link EditorBuffer#getVisibleContent()} for
     * the accessible portion.
     */
    String getContent() {
        if (b.logView.filtered()) {
            return b.logView.fullText(); // the area shows only the matching lines
        }
        return NarrowCarry.wholeText(b.narrowing.narrowHeld, b.documentTextSnapshot());
    }
}
