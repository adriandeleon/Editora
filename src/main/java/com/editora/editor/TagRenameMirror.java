package com.editora.editor;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.editora.editops.DoubleClickWord;
import com.editora.editops.TagRename;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.EditableStyledDocument;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.StyledDocument;
import org.fxmisc.richtext.model.TwoDimensional;

/**
 * Auto-rename-tag: editing an HTML/XML tag name mirrors the rename onto the paired open/close tag (the
 * pure decision is {@link TagRename}; this is the live glue over the editor's areas).
 *
 * <p><b>The mirror runs after the triggering edit has committed, never inside its change event.</b>
 * RichTextFX's {@code replace} changes the text, notifies subscribers, and only then puts the caret after
 * the replacement — at an offset computed <em>before</em> the subscribers ran. A mirror applied from the
 * change event that lands <em>before</em> the caret (typing in a closing tag renames the opener above it)
 * therefore left the caret one position short: {@code </div|} + {@code xy} became {@code </divyx>}, and the
 * opener was renamed to the same scrambled name. {@link Area} reports each single-range replace once both
 * text and caret have settled, which is the first moment the mirror can be applied and the caret shifted
 * with it.
 *
 * <p>Only a single-range replace is mirrored. A multi-change (several carets typing at once, a rectangle
 * edit) never reaches {@link Area#replace}, and the host's {@code eligible} check refuses while extra carets
 * exist: one tag pair per keystroke is the feature; renaming a different pair under every caret is not.
 */
final class TagRenameMirror {

    /**
     * Left/right context the local tag-name pre-check needs around an edit: at least {@code MAX_NAME + 2}
     * (the longest name plus its {@code </}) so the window reproduces {@link TagRename}'s own region decision
     * without a false negative.
     */
    private static final int LOOKBACK = TagRename.MAX_NAME + 2;

    private final Supplier<String> language;
    private final BooleanSupplier eligible;
    private boolean enabled;
    /** Re-entrancy guard: the mirrored {@code replaceText} must not itself trigger another mirror. */
    private boolean applying;
    /** The buffer's areas (the split view's second one shares the document; each records its own undo). */
    private CodeArea primary;

    /** Weak: a closed split view's area must not be kept alive by its buffer's mirror. */
    private java.lang.ref.WeakReference<CodeArea> secondary = new java.lang.ref.WeakReference<>(null);

    /**
     * @param language the buffer's current language id
     * @param eligible whether the buffer may be mirrored into right now (editable, not a large file, a
     *                 single caret)
     */
    TagRenameMirror(Supplier<String> language, BooleanSupplier eligible) {
        this.language = language;
        this.eligible = eligible;
    }

    void setEnabled(boolean on) {
        enabled = on;
    }

    /** A new editor area wired to this mirror: over {@code document}, or over a fresh one when it is null. */
    CodeArea newArea(EditableStyledDocument<Collection<String>, String, Collection<String>> document) {
        Area area = document == null ? new Area() : new Area(document);
        area.onCommitted = this::committed;
        area.language = language;
        EditorMouse.installSelectionDrop(area);
        if (document == null) {
            primary = area;
        } else {
            secondary = new java.lang.ref.WeakReference<>(area);
        }
        return area;
    }

    private void committed(CodeArea a, PlainTextChange c) {
        if (!enabled || applying || !eligible.getAsBoolean()) {
            return;
        }
        String lang = language.get();
        boolean html = "html".equals(lang);
        if (!html && !"xml".equals(lang)) {
            return;
        }
        if (a.getUndoManager().isPerformingAction()) {
            return; // undo/redo replays both the edit and its mirror; mirroring again would loop
        }
        // TagRename.mirror only has work when the edit lands inside a tag name — a fully-local test that reads
        // just a bounded neighborhood of the change. Run it on a small window first so the vast majority of
        // keystrokes (in text, attributes, anywhere but a tag name) skip materializing the whole document — an
        // O(n) String this runs per keystroke. Only a confirmed tag-name edit pays the full getText + lex.
        int changePos = c.getPosition();
        int changeEnd = changePos + c.getInserted().length();
        int winStart = Math.max(0, changePos - LOOKBACK);
        int winEnd = Math.min(a.getLength(), changeEnd + LOOKBACK);
        if (changeEnd > winEnd
                || !TagRename.changeInTagName(
                        a.getText(winStart, winEnd), changePos - winStart, changeEnd - winStart)) {
            return;
        }
        TagRename.Mirror m = TagRename.mirror(a.getText(), changePos, c.getRemoved(), c.getInserted(), html);
        if (m == null) {
            return;
        }
        // The edit has committed, so these are the user's real anchor and caret (after the typed character,
        // or where Backspace left it). Carry them across the mirror: it shifts them only when it sits above.
        int anchor = a.getAnchor();
        int caret = a.getCaretPosition();
        int delta = m.name().length() - (m.to() - m.from());
        applying = true;
        try {
            // One undo step with the keystroke: undoing only the mirror would leave the tags mismatched.
            CompletionUndoManager.joinLastEdit(
                    primary, secondary.get(), () -> a.replaceText(m.from(), m.to(), m.name()));
            a.selectRange(anchor >= m.to() ? anchor + delta : anchor, caret >= m.to() ? caret + delta : caret);
        } finally {
            applying = false;
        }
    }

    /**
     * A {@link CodeArea} that reports each single-range replace <em>after</em> it has fully committed — text
     * changed, subscribers notified, caret placed. Every single edit (typing, Backspace/Delete, paste,
     * {@code replaceText}, {@code insertText}, {@code deleteText}) funnels through
     * {@link #replace(int, int, StyledDocument)}; multi-changes do not, and are deliberately not reported.
     * A replace during which another replace ran on this area (a subscriber editing from the change event)
     * is not reported either: the recorded change's offsets may no longer describe the document.
     */
    static final class Area extends CodeArea {

        private BiConsumer<CodeArea, PlainTextChange> onCommitted = (area, change) -> {};
        private Supplier<String> language = () -> null;
        private PlainTextChange inFlight;
        private int depth;
        private boolean nested;

        Area() {
            track();
        }

        Area(EditableStyledDocument<Collection<String>, String, Collection<String>> document) {
            super(document);
            track();
        }

        private void track() {
            // The document may be shared with a second view: only the area whose replace is running records.
            plainTextChanges().subscribe(change -> {
                if (depth == 1) {
                    inFlight = change;
                }
            });
        }

        /**
         * Tolerates an offset past the end of the document. While a multi-caret change is being notified,
         * the extra carets still hold their pre-edit offsets; the current-line highlighter (subscribed
         * before them) re-creates the caret line's cell in that window, which resolves every caret's
         * paragraph — and for a caret near the end of a document that just got shorter, the stale offset
         * is out of range. RichTextFX threw from the change notification, the multi-caret commit never
         * re-asserted its carets, and every later key in the buffer failed. The carets correct themselves
         * as soon as their own change handlers run, so clamping the transient lookup is all that is needed.
         */
        /** Double-click: the word under the caret by {@link DoubleClickWord}'s rules for this language. */
        @Override
        public void selectWord() {
            String lang = language.get();
            boolean prose = LanguageRegistry.plaintext().equals(lang) || "markdown".equals(lang);
            int paragraph = getCurrentParagraph();
            int[] word = DoubleClickWord.at(getText(paragraph), getCaretColumn(), prose, "css".equals(lang));
            if (word != null) {
                selectRange(paragraph, word[0], paragraph, word[1]);
            }
        }

        @Override
        public TwoDimensional.Position offsetToPosition(int offset, TwoDimensional.Bias bias) {
            return super.offsetToPosition(Math.min(offset, getLength()), bias);
        }

        @Override
        public void replace(
                int start, int end, StyledDocument<Collection<String>, String, Collection<String>> replacement) {
            if (depth == 0) {
                inFlight = null;
                nested = false;
            } else {
                nested = true;
            }
            depth++;
            try {
                super.replace(start, end, replacement);
            } finally {
                depth--;
            }
            if (depth == 0) {
                PlainTextChange change = nested ? null : inFlight;
                inFlight = null;
                if (change != null) {
                    onCommitted.accept(this, change);
                }
            }
        }
    }
}
