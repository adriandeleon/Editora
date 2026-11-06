package com.editora.editor;

import java.nio.file.Path;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.completion.SnippetFieldOverlay;
import com.editora.editor.completion.SnippetTyping;
import com.editora.snippet.ParsedSnippet;
import com.editora.snippet.Snippet;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Snippet and abbreviation expansion in a buffer: Tab-expanding a trigger, the keys that drive a running
 * snippet session (Tab/Shift-Tab between fields, Escape, a typed character into a mirrored field as one undo
 * step), the field overlay, inserting a picked snippet or a file template, and Emacs abbrevs (expand at the caret,
 * and abbrev-mode's expansion on a typed word terminator). The session itself is {@link EditorBuffer}'s.
 */
final class BufferSnippets {

    private final EditorBuffer b;

    BufferSnippets(EditorBuffer b) {
        this.b = b;
    }

    /** Resolves (language, prefix) → snippet for Tab-expand; injected by the controller (default: none). */
    java.util.function.BiFunction<String, String, Snippet> snippetProvider = (lang, prefix) -> null;

    /** As {@link #snippetProvider}, including the triggers a completion offers but Tab alone leaves be. */
    java.util.function.BiFunction<String, String, Snippet> snippetOfferProvider = (lang, prefix) -> null;

    boolean snippetTabExpansion = true;
    private java.util.function.UnaryOperator<@Nullable Path> snippetWorkspaceRoot = file -> null;
    private java.util.function.Consumer<int[]> snippetProgress = progress -> {};
    private @Nullable SnippetFieldOverlay snippetOverlay; // lazily attached when the first session starts

    // Settings: auto-show docs beside the list
    // per-open session flag (reset on each popup open; Ctrl+Q toggles)
    // generation guard for the debounced/async doc fetch

    /**
     * Emacs {@code auto-fill-mode}: after an insertion, break the current line at a word boundary if it has
     * grown past the fill column. Prose only ({@link EditorBuffer#isProse()}), so it never wraps code. Runs on the
     * post-edit {@code plainTextChanges} (the tag-rename pattern) — the first field check short-circuits when
     * off, keeping the per-keystroke cost at one boolean for every buffer that hasn't enabled it.
     */
    java.util.Map<String, String> abbrevTable = java.util.Map.of();

    boolean abbrevMode;
    boolean applyingAbbrev;

    /** Pushes the abbreviation dictionary (lower-cased keys) and the auto-expand mode from Settings. */
    void setAbbrevs(java.util.Map<String, String> table, boolean autoExpand) {
        this.abbrevTable = table == null ? java.util.Map.of() : table;
        this.abbrevMode = autoExpand;
    }

    /**
     * Emacs {@code expand-abbrev} ({@code C-x a e}): expand the abbreviation immediately before the caret,
     * regardless of abbrev-mode. Returns whether anything expanded.
     */
    boolean expandAbbrevAtCaret() {
        if (!b.isEditable() || abbrevTable.isEmpty()) {
            return false;
        }
        CodeArea a = b.getFocusedArea();
        com.editora.editops.Abbrev.Edit edit =
                com.editora.editops.Abbrev.expand(a.getText(), a.getCaretPosition(), abbrevTable);
        if (edit == null) {
            return false;
        }
        a.replaceText(edit.from(), edit.to(), edit.replacement());
        return true;
    }

    /** Longest word an abbreviation lookup will scan back over — bounds the per-keystroke text slice below. */
    private static final int MAX_ABBREV_LOOKBACK = 256;

    /**
     * Abbrev-mode auto-expand: when the just-typed single character is a word terminator (anything but a
     * letter/digit), expand the word that ended just before it. The terminator stays. Same guarded
     * {@code plainTextChanges} shape as {@link BufferTyping#maybeAutoFill}; the first field check short-circuits when off.
     */
    void maybeExpandAbbrev(org.fxmisc.richtext.model.PlainTextChange c) {
        if (!abbrevMode || applyingAbbrev || b.hugeFile || !b.isEditable() || abbrevTable.isEmpty()) {
            return;
        }
        if (!c.getRemoved().isEmpty() || !com.editora.editops.Abbrev.terminates(c.getInserted())) {
            return; // not a typed terminator (still inside a word, or a paste)
        }
        if (b.hasActiveSnippet() || b.area.getUndoManager().isPerformingAction()) {
            return;
        }
        CodeArea a = b.getFocusedArea();
        if (b.caretOps.multiCaretActiveOn(a)) {
            return;
        }
        // The terminator sits at c.getPosition(), so the word ends there.
        com.editora.editops.Abbrev.Edit edit = abbrevBefore(a, Math.min(c.getPosition(), a.getLength()));
        if (edit == null) {
            return;
        }
        int from = edit.from();
        int to = edit.to();
        int caret = BufferTyping.caretPast(a, c);
        int delta = edit.replacement().length() - (to - from);
        applyingAbbrev = true;
        try {
            a.replaceText(from, to, edit.replacement());
        } finally {
            applyingAbbrev = false;
        }
        // The edit lies before the caret (the terminator we just typed), so shift the caret by the length
        // change. Deferred for the same reason as auto-fill: RichTextFX re-applies the triggering
        // insertion's caret after this subscriber returns, which would otherwise mis-place it.
        // Enter-with-indent and an auto-closed pair place their own caret once this returns (after the
        // indent, between the pair), so for those it is read back then rather than predicted now.
        boolean oneChar = c.getInserted().length() == 1;
        b.caretFixes.defer(
                () -> a.moveTo(Math.clamp((oneChar ? caret : a.getCaretPosition()) + delta, 0, a.getLength())));
    }

    /**
     * The abbreviation ending at {@code point} of {@code a}, in document offsets, or null. The word fits in a
     * bounded window before it, so just that window is sliced instead of the whole document — this runs per
     * word-terminator keystroke and per typing pause.
     */
    com.editora.editops.Abbrev.@Nullable Edit abbrevBefore(CodeArea a, int point) {
        int windowStart = Math.max(0, point - MAX_ABBREV_LOOKBACK);
        String window = a.getText(windowStart, point);
        com.editora.editops.Abbrev.Edit edit = com.editora.editops.Abbrev.expand(window, window.length(), abbrevTable);
        return edit == null
                ? null
                : new com.editora.editops.Abbrev.Edit(
                        windowStart + edit.from(), windowStart + edit.to(), edit.replacement());
    }

    /** Injects the snippet lookup used by Tab-expand (set by the controller). */
    void setSnippetProvider(java.util.function.BiFunction<String, String, Snippet> provider) {
        if (provider != null) {
            this.snippetProvider = provider;
        }
    }

    /** Injects the lookup for every snippet a completion may offer for a typed trigger, list-only ones too. */
    void setSnippetOfferProvider(java.util.function.BiFunction<String, String, Snippet> provider) {
        if (provider != null) {
            this.snippetOfferProvider = provider;
        }
    }

    /** {@code Settings.snippetTabExpansion}: whether Tab expands a trigger (the popup and picker always do). */
    void setSnippetTabExpansion(boolean on) {
        this.snippetTabExpansion = on;
    }

    /** A file's project root ({@code WORKSPACE_*}), and who hears the active field's {position, count} (null = ended). */
    void setSnippetHooks(
            java.util.function.UnaryOperator<@Nullable Path> root, java.util.function.Consumer<int[]> progress) {
        this.snippetWorkspaceRoot = root == null ? file -> null : root;
        this.snippetProgress = progress == null ? p -> {} : progress;
    }

    void snippetSessionChanged() {
        if (snippetOverlay == null && b.snippetSession.isActive()) {
            snippetOverlay =
                    b.attachLazyOverlay(new SnippetFieldOverlay(b.area, b.snippetSession), OverlayLayer.SNIPPET);
        }
        if (snippetOverlay != null) snippetOverlay.refresh();
        snippetProgress.accept(b.snippetSession.progress());
    }

    /**
     * Tab/Shift-Tab/Escape handling for snippets, as a key filter (runs before RichTextFX's own Tab
     * indent). With an active snippet, Tab/Shift-Tab cycle fields and Escape cancels; otherwise Tab
     * tries to expand the identifier before the caret and only consumes the event when one matched —
     * so a plain Tab still indents.
     */
    void addSnippetKeys(CodeArea a) {
        // Typing a printable char into a MIRRORED snippet field is applied atomically (the char + its mirror
        // update as one undo unit) so a single Ctrl-Z reverts them together instead of leaving the document
        // half-reverted (#415). Only mirrored fields are intercepted; everything else (single-occurrence fields,
        // newlines, control keys, paste) falls through to the normal insert + reactive mirror, which is already
        // one undo unit when there's nothing to mirror.
        a.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (e.isConsumed()) return;
            if (!b.hasActiveSnippet() || b.caretOps.multiCaretActiveOn(a) || !b.isEditable()) {
                return;
            }
            String ch = e.getCharacter();
            if (ch == null || ch.length() != 1 || e.isControlDown() || e.isMetaDown()) {
                return;
            }
            char c = ch.charAt(0);
            if (c < 0x20 || c == 0x7F) {
                return; // control / non-printable (Enter, Tab, Backspace handled elsewhere)
            }
            // A bracket or quote is paired first (its mirrors follow reactively, as one undo step); the
            // atomic path below would swallow the key before the auto-close filter saw it.
            if (TypedText.isText(e) && b.typingAids.applyAutoCloseTyped(a, c)
                    || b.snippetSession.replaceInActiveField(
                            a.getSelection().getStart(), a.getSelection().getEnd(), ch)) {
                e.consume(); // handled atomically; don't let the area also insert the char
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            // ownsKeys applies the "caret has left the fields" rule first, so a session the caret walked
            // away from never takes this Tab — it indents like any other.
            if ((e.getCode() == KeyCode.TAB || e.getCode() == KeyCode.ESCAPE) && b.snippetSession.ownsKeys(a)) {
                if (e.getCode() == KeyCode.TAB) {
                    if (e.isShiftDown()) {
                        b.snippetSession.previous();
                    } else {
                        b.snippetSession.next();
                    }
                    e.consume();
                } else if (e.getCode() == KeyCode.ESCAPE) {
                    b.snippetSession.cancel();
                    e.consume();
                }
                return;
            }
            if (e.getCode() == KeyCode.TAB
                    && !e.isControlDown()
                    && !e.isAltDown()
                    && !e.isMetaDown()
                    && !b.completionActions.completionShowing()) {
                // Plain Tab first tries to expand a snippet prefix; otherwise smart-indent (Shift-Tab
                // always dedents). Skipped while a completion popup/ghost is showing, so Tab accepts
                // the completion instead.
                if (b.isMarkdown() && b.isEditable() && !b.hugeFile && b.markdownEditing.tab(a, !e.isShiftDown())) {
                    e.consume(); // Tab/Shift-Tab: between table cells, or a list item in/out one level
                } else if (!e.isShiftDown() && expandPrefixAtCaret(a)) {
                    e.consume();
                } else if (!e.isShiftDown() && b.typingAids.tryLspReindentLine(a)) {
                    e.consume(); // LSP re-indents the current line (async); see tryLspReindentLine
                } else if (b.typingAids.applySmartTab(a, e.isShiftDown())) {
                    e.consume();
                }
            }
        });
    }

    /** Inserts a snippet from the picker, replacing the selection (if any), and starts its session. */
    void insertSnippet(Snippet snippet) {
        if (snippet == null || !b.isEditable()) {
            return;
        }
        CodeArea a = b.focusedArea;
        int from = a.getSelection().getLength() > 0 ? a.getSelection().getStart() : a.getCaretPosition();
        int to = a.getSelection().getLength() > 0 ? a.getSelection().getEnd() : from;
        int[] range = to > from ? SnippetTyping.wrappedRange(a.getText(), from, to) : new int[] {from, to};
        startSnippet(a, snippet, range[0], range[1]);
        a.requestFocus();
    }

    /** Expands the trigger before the caret if Tab may ({@link SnippetTyping#triggerAtCaret}); returns whether it did. */
    private boolean expandPrefixAtCaret(CodeArea a) {
        if (!b.isEditable() || !snippetTabExpansion || a.getSelection().getLength() > 0) {
            return false;
        }
        com.editora.snippet.TabExpansion.Match m =
                SnippetTyping.triggerAtCaret(a, b.language, b.isProse(), snippetProvider);
        if (m != null) {
            startSnippet(a, m.snippet(), m.start(), a.getCaretPosition());
        }
        return m != null;
    }

    /** Parses {@code snippet}, replaces {@code [from,to)} with the expansion, and begins a session. */
    void startSnippet(CodeArea a, Snippet snippet, int from, int to) {
        startSnippet(a, snippet, from, to, true);
    }

    void startSnippet(CodeArea a, Snippet snippet, int from, int to, boolean reindent) {
        Path root = b.path == null ? null : snippetWorkspaceRoot.apply(b.path);
        ParsedSnippet parsed = SnippetTyping.parse(snippet, a, from, to, b.path, b.language, root);
        String currentLine = a.getParagraph(SnippetTyping.lineOf(a, from)).getText();
        String indent = reindent ? b.completionActions.leadingIndent(currentLine) : "";
        // asIs (no reindent) keeps the text untouched; otherwise the body's indentation becomes the buffer's unit.
        String unit = reindent ? b.indentUnit() : null;
        b.snippetSession.start(a, parsed, from, to, indent, unit);
    }

    /**
     * Inserts an already-parsed file template into this (typically empty, untitled) buffer over its full
     * content, honoring the template's {@code ${cursor}} ({@code $0}) final caret and any {@code $1…} tab
     * stops via a snippet session. No-op when not editable.
     */
    void applyTemplate(ParsedSnippet parsed) {
        if (parsed == null || !b.isEditable()) {
            return;
        }
        b.snippetSession.cancel();
        b.snippetSession.start(b.area, parsed, 0, b.area.getLength(), "");
        b.area.requestFocus();
    }

    // ---- Autocomplete (snippet + dictionary completions; see com.editora.completion) ----

    // ---- AI inline completion (ghost text from an AI provider; see ui.AiCoordinator) ----
}
