package com.editora.editor;

import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editops.AutoClose;
import com.editora.editops.CommentContinuation;
import com.editora.editops.IndentWindow;
import com.editora.editops.Indenter;
import com.editora.editops.LineIndent;
import com.editora.editor.highlight.JsxContext;
import com.editora.text.LspTextEdit;
import com.editora.typst.TypstMarkup;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.jspecify.annotations.Nullable;

/**
 * The typing assists of a buffer's views, installed as key filters on each {@code CodeArea} in a fixed order
 * (auto-close before auto-indent, so it sees a keystroke first): Enter auto-indent and list continuation,
 * smart Backspace, closer de-indent, bracket/quote/tag auto-close, smart Tab and its language-server re-indent,
 * on-type formatting, smart semicolon, auto-fill, read-only paging, and the replay of typed text and keys from
 * a macro through the same paths. {@link EditorBuffer} keeps the public API and decides the indent unit.
 */
final class BufferTyping {

    private final EditorBuffer b;

    BufferTyping(EditorBuffer b) {
        this.b = b;
    }

    /** Whether this buffer's server advertises range formatting — enables Tab to re-indent the line. */
    private boolean lspRangeFormatAvailable;
    /** Injected range-formatter (the controller wires it to the LSP manager); null = none. */
    private EditorBuffer.@Nullable LspRangeFormatter lspRangeFormatter;
    /** Injected on-type formatter (#740); null = none. */
    private EditorBuffer.@Nullable LspOnTypeFormatter lspOnTypeFormatter;
    /** The characters this buffer's server formats on ({@code ;}, {@code }}, {@code \n} for jdtls) — #740. */
    private java.util.Set<Character> lspOnTypeTriggers = java.util.Set.of();
    /** {@code Settings.lspOnTypeFormatting} — the master gate for on-type formatting (#740). */
    private boolean onTypeFormattingEnabled;
    /** Generation guard so a stale async re-indent result can't clobber a later edit. */
    private long reindentGen;
    /** Injects the on-type formatting request (#740); null disables it. */
    void setLspOnTypeFormatter(EditorBuffer.LspOnTypeFormatter formatter) {
        this.lspOnTypeFormatter = formatter;
    }

    /** The characters the server formats on (empty = none / not yet known), refreshed when it reports ready. */
    void setLspOnTypeTriggers(java.util.Set<Character> chars) {
        this.lspOnTypeTriggers = chars == null ? java.util.Set.of() : chars;
    }

    /** The {@code Settings.lspOnTypeFormatting} master gate (#740), pushed by {@code applyViewSettings}. */
    void setOnTypeFormattingEnabled(boolean enabled) {
        this.onTypeFormattingEnabled = enabled;
    }

    /** Injects the range-formatter used by Tab to re-indent the current line (controller-wired; null off). */
    void setLspRangeFormatter(EditorBuffer.LspRangeFormatter formatter) {
        this.lspRangeFormatter = formatter;
    }

    /** Whether the server advertises range formatting — gates Tab's LSP line re-indent (else plain Tab). */
    void setLspRangeFormatAvailable(boolean available) {
        this.lspRangeFormatAvailable = available;
    }

    /** Auto-close tags: typing the {@code >} of an HTML/XML open tag inserts the matching closer. */
    private boolean autoCloseTags;

    private final TagTyping tagTyping = new TagTyping(); // the closer typed after '>', a hand-typed one joined

    /** Enables/disables tag auto-closing (pushed from the controller's view settings). */
    void setAutoCloseTags(boolean on) {
        autoCloseTags = on;
    }

    /**
     * After each document change, mirrors a tag-name edit onto the paired open/close tag (the VS
     * Code Auto Rename Tag behavior) via the pure {@link com.editora.editops.TagRename}. Runs only
     * for editable html/xml buffers below the large-file tier, never during undo/redo (undoing the
     * user's edit and the mirror separately must not re-mirror), and never re-entrantly. Cost when
     * it doesn't apply is one boolean/language check; the document lex runs only when the change
     * sits inside a tag name.
     */
    private boolean autoFill;

    private int fillColumn = com.editora.editops.Filler.DEFAULT_FILL_COLUMN;
    private boolean applyingAutoFill;

    /** Enables/disables auto-fill (break-as-you-type) for this buffer; pushed from Settings by the controller. */
    void setAutoFillEnabled(boolean on) {
        this.autoFill = on;
    }

    void setFillColumn(int column) {
        this.fillColumn = column > 0 ? column : com.editora.editops.Filler.DEFAULT_FILL_COLUMN;
    }

    /**
     * {@code a}'s caret once it is past the just-typed {@code c}. The split's second view moves its caret
     * only after this buffer's change listeners have run, so there it is still in front of the character.
     */
    static int caretPast(CodeArea a, org.fxmisc.richtext.model.PlainTextChange c) {
        return a.getCaretPosition() == c.getPosition() ? c.getInsertionEnd() : a.getCaretPosition();
    }

    void maybeAutoFill(org.fxmisc.richtext.model.PlainTextChange c) {
        if (!autoFill || applyingAutoFill || b.hugeFile || !b.isEditable() || !b.isProse()) {
            return;
        }
        // Only a single typed character (Emacs self-insert): no deletion, exactly one char, not a newline
        // (which starts a fresh line). This excludes pastes, file loads/reloads and programmatic reflows —
        // auto-fill breaks as you *type*, it never rewraps a block that arrived some other way.
        if (!c.getRemoved().isEmpty()
                || c.getInserted().length() != 1
                || c.getInserted().charAt(0) == '\n') {
            return;
        }
        if (b.hasActiveSnippet() || b.area.getUndoManager().isPerformingAction()) {
            return;
        }
        CodeArea a = b.getFocusedArea();
        if (b.caretOps.multiCaretActiveOn(a)) {
            return;
        }
        int par = a.getCurrentParagraph();
        String line = a.getParagraph(par).getText();
        if (line.length() <= fillColumn) {
            return;
        }
        com.editora.editops.AutoFill.Break brk = com.editora.editops.AutoFill.computeProse(
                line,
                fillColumn,
                com.editora.editops.Commenter.styleFor(b.getLanguage()).line());
        if (brk == null) {
            return;
        }
        int lineStart = a.getAbsolutePosition(par, 0);
        int caret = caretPast(a, c);
        int delta = brk.insert().length() - brk.removeLen();
        int end = lineStart + brk.at() + brk.removeLen();
        int restored = caret >= end ? caret + delta : caret; // the user's caret, shifted past the inserted prefix
        applyingAutoFill = true;
        try {
            a.replaceText(lineStart + brk.at(), end, brk.insert());
        } finally {
            applyingAutoFill = false;
        }
        // Restore the caret AFTER the change settles: we ran inside the triggering insertion's
        // plainTextChanges, and RichTextFX re-applies that insertion's own caret position once our
        // subscriber returns — which would strand the caret inside the inserted prefix (delta > 0) and send
        // the next characters to the wrong place. Deferring makes our position the last one to win.
        b.caretFixes.defer(() -> a.moveTo(Math.min(restored, a.getLength())));
    }

    /**
     * Pager-style navigation while in read-only View mode: an unmodified Space pages down and Backspace
     * pages up (like {@code less}/man). Installed as a key <em>filter</em> so it runs before RichTextFX's
     * own (no-op, since non-editable) handling, and only acts while {@link EditorBuffer#viewMode} is on — normal
     * editing keeps Space/Backspace untouched. Modifier combos (Ctrl/Alt/Meta) are left for the keymap.
     */
    void addViewModePaging(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (!b.viewMode || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                return;
            }
            if (e.getCode() == KeyCode.SPACE) {
                a.nextPage(SelectionPolicy.CLEAR);
                e.consume();
            } else if (e.getCode() == KeyCode.BACK_SPACE) {
                a.prevPage(SelectionPolicy.CLEAR);
                e.consume();
            }
        });
    }

    /**
     * Smart Tab / Shift-Tab via the pure {@link Indenter#smartTab}: block-indent a selection, indent the
     * current line in leading whitespace, insert one indent unit mid-line, and Shift-Tab dedents — using
     * the file's indent unit. Prose and unstyled languages get the same minus the context re-indent
     * ({@link com.editora.editops.PlainTab}). Returns false (leaving the default Tab) only in
     * read-only/large-file mode.
     */
    boolean applySmartTab(CodeArea a, boolean shift) {
        Indenter.TabEdit edit =
                tabEdit(a.getSelection().getStart(), a.getSelection().getEnd(), shift);
        if (edit == null) {
            return false;
        }
        if (edit.from() != edit.to() || !edit.replacement().isEmpty()) {
            a.replaceText(edit.from(), edit.to(), edit.replacement());
        }
        a.selectRange(edit.selStart(), edit.selEnd());
        return true;
    }

    /** The Tab edit for one selection (every caret's, with several); null = leave the key. Smart Tab, or
     *  for PLAIN (prose/plaintext) no context re-indent but still the file's indent unit — both computed
     *  from the lines around the selection, not from the whole document. */
    Indenter.@Nullable TabEdit tabEdit(int selStart, int selEnd, boolean shift) {
        if (!b.isEditable() || b.hugeFile) {
            return null;
        }
        return IndentWindow.tabEdit(
                IndentKeys.doc(b.area), selStart, selEnd, b.language, b.tabSize, shift, b.indentUnit());
    }

    /**
     * Plain Tab re-indents the current line to the language server's convention (the chosen behavior:
     * indentation only, not a full line reformat). Returns {@code true} when it takes over the keystroke
     * (LSP active + the server supports range formatting + a single caret on a non-blank line) and kicks
     * off an async request; the result adjusts only the line's leading whitespace. Returns {@code false}
     * to fall back to the normal smart-Tab indent (no server, unsupported, blank line, or a selection).
     */
    boolean tryLspReindentLine(CodeArea a) {
        if (!b.lspActive || !lspRangeFormatAvailable || lspRangeFormatter == null) {
            return false;
        }
        if (!b.isEditable() || b.hugeFile || b.largeFile || a.getSelection().getLength() > 0) {
            return false;
        }
        int par = a.getCurrentParagraph();
        String line = a.getParagraph(par).getText();
        // Formatters strip a blank line's whitespace, so they can't indent a fresh line you're about to type on, and
        // an HTML page's <pre> or <script> line is not theirs to indent (B102): leave those to the local smart Tab.
        if (line.isBlank() || !IndentWindow.serverReindents(IndentKeys.doc(a), a.getCaretPosition(), b.language)) {
            return false;
        }
        int caret = a.getCaretPosition();
        long gen = ++reindentGen;
        int from = Math.max(0, par - 1); // from the line break: lemminx indents by editing it (XML review B4)
        lspRangeFormatter.format(from, par == 0 ? 0 : a.getParagraphLength(from), par, line.length(), edits -> {
            if (gen != reindentGen
                    || a.getScene() == null
                    || a.getCurrentParagraph() != par
                    || !a.getParagraph(par).getText().equals(line)) {
                return; // stale, detached, or the line changed under us
            }
            if (!applyLspLineIndent(a, par, line, caret, edits, true) && a.getCaretPosition() == caret) {
                applySmartTab(a, false); // no usable indent: Tab still does what it does without a server
            }
        });
        return true;
    }

    /**
     * Adopts the formatter's leading whitespace for the current line, leaving the rest of the line as-is.
     *
     * <p>{@code fromTab} distinguishes the two callers, and only matters when the indent is already right:
     * a Tab pressed from inside the leading whitespace must still put the caret where typing starts (the
     * same rule the re-indent below applies), whereas on-type formatting fires while the user is typing and
     * must never move the caret out from under them. False when the answer gives no usable indent.
     */
    private boolean applyLspLineIndent(
            CodeArea a, int par, String line, int caret, java.util.List<LspTextEdit> edits, boolean fromTab) {
        String newIndent = LineIndent.formattedIndent(line, edits, par);
        if (newIndent == null) {
            return false; // unusable (multi-line) result → leave the line untouched
        }
        String oldIndent = LineIndent.leadingWhitespace(line);
        if (newIndent.equals(oldIndent)) {
            // Already correctly indented: no edit, but Tab from within the indent still moves the caret to
            // its end — otherwise Tab looks like it did nothing at all.
            int indentEnd = a.getAbsolutePosition(par, 0) + oldIndent.length();
            if (fromTab && caret < indentEnd && indentEnd <= a.getLength()) {
                a.moveTo(indentEnd);
            }
            return true;
        }
        int lineStart = a.getAbsolutePosition(par, 0);
        int oldEnd = lineStart + oldIndent.length();
        a.replaceText(lineStart, oldEnd, newIndent);
        int delta = newIndent.length() - oldIndent.length();
        int newCaret = caret <= oldEnd ? lineStart + newIndent.length() : caret + delta;
        newCaret = Math.max(lineStart, Math.min(newCaret, a.getLength()));
        a.moveTo(newCaret);
        // Re-indenting isn't the user typing a word — don't let the edit we just made pop the completion.
        b.completionActions.suppressCompletionAt(b.docVersion);
        return true;
    }

    /**
     * Auto/smart indentation (installed on {@code area}/{@code area2}). On <b>Enter</b>, inserts a
     * newline indented per {@link Indenter} (inherit + block-opener +1 + matching-pair split). When a
     * <b>closing token</b> is typed — a {@code )]}} bracket alone on the line, or a closer keyword like
     * {@code end}/{@code fi} completed — the line is re-aligned to its opener's indent. Inert in
     * read-only mode. Inside a snippet field these assists work as anywhere else: the session's range
     * tracking absorbs their edits.
     */
    void addAutoIndent(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() != KeyCode.ENTER
                    || e.isShiftDown()
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()) {
                return;
            }
            if (!b.isEditable()) {
                return;
            }
            applyEnter(a);
            e.consume(); // we inserted the newline+indent ourselves
        });
        // Smart backspace: when the caret is in a line's leading whitespace, one Backspace clears the
        // whole indent — and on an otherwise-blank (auto-indented) line it also removes the newline, so
        // a single press jumps back to the end of the previous line ("back to where you hit Enter").
        // Only consumes when it removes more than one char, so a normal single-char Backspace still runs
        // everywhere else. The auto-close empty-pair handler is registered earlier and gets first dibs: it
        // consumes when it deletes a pair, but JavaFX runs *every* filter on a node regardless of consume()
        // (consume only stops propagation to other nodes), so we must re-check isConsumed() here ourselves —
        // otherwise Backspace on an empty pair inside leading whitespace deletes the pair AND the indent.
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) { // the empty-pair handler already consumed this Backspace
                return;
            }
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() != KeyCode.BACK_SPACE
                    || b.viewMode
                    || !b.isEditable()
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()
                    || e.isShiftDown()
                    || a.getSelection().getLength() > 0) {
                return;
            }
            int par = a.getCurrentParagraph();
            int lineStart = a.getAbsolutePosition(par, 0);
            int caret = a.getCaretPosition();
            int col = caret - lineStart;
            if (col <= 0) {
                return; // at column 0 — let normal Backspace join the previous line
            }
            String line = a.getParagraph(par).getText();
            // Markdown: Backspace at the end of an empty list/quote item ("- ", "1. ", "> ", "- [ ] ")
            // steps out one level (a nested item moves to its parent's level, a top-level one is cleared).
            if (b.isMarkdown() && b.markdownEditing.backspace(a)) {
                e.consume();
                return;
            }
            int marker = b.isTypst() ? TypstMarkup.emptyMarkerDeleteLength(line, col) : 0;
            if (marker > 0) {
                a.deleteText(caret - marker, caret);
                e.consume();
                return;
            }
            int del = Indenter.smartBackspaceCount(
                    line.substring(0, col), line.substring(col), par > 0, b.language, b.indentUnit(), b.tabSize);
            if (del > 1) {
                a.deleteText(caret - del, caret);
                e.consume();
            }
        });
        a.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (e.isConsumed()) { // the auto-close KEY_TYPED handler (registered earlier) already acted
                return;
            }
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (!b.isEditable()
                    || e.getCharacter().length() != 1
                    || !TypedText.isText(e)
                    || a.getSelection().getLength() > 0) {
                return;
            }
            char typed = e.getCharacter().charAt(0);
            if (typed == '*' && closeJavadocOpening(a)) {
                e.consume();
                return;
            }
            // Fires while the caret is still where the user typed and the document has no ';' yet — the
            // state the server's answer is expressed in. Never consumes, so the char inserts as normal.
            maybeSmartSemicolon(a, typed); // #746
            applyCloserDedent(a, typed);
            // #740. Deferred: this filter runs BEFORE the character is inserted, and both the request's
            // position and its staleness baseline must describe the line with the character in it.
            Platform.runLater(() -> maybeOnTypeFormat(a, typed));
        });
    }

    /**
     * In Java with the language server on, the {@code *} that makes a line {@code /**} also closes it —
     * {@code /**|*}{@code /} — and asks for completion there, where jdtls offers the declaration's Javadoc
     * template ({@link CommentContinuation#closesDocOpening}). Enter instead still splits the comment.
     */
    private boolean closeJavadocOpening(CodeArea a) {
        if (!b.lspActive || !"java".equals(b.language) || b.hugeFile) {
            return false;
        }
        int caret = a.getCaretPosition();
        String line = a.getParagraph(a.getCurrentParagraph()).getText();
        int col = a.getCaretColumn();
        String before = line.substring(0, col);
        if (!before.strip().equals("/*") // cheap first: the whole text only on a "/*" line
                || !CommentContinuation.closesDocOpening(a.getText(), caret, before, line.substring(col))) {
            return false;
        }
        a.insertText(caret, "* */");
        a.moveTo(caret + 1);
        b.triggerCompletion();
        return true;
    }

    private EditorBuffer.@Nullable LspSmartSemicolonRequester smartSemicolonRequester;
    /** {@code Settings.lspSmartSemicolon} — OFF by default, as in VS Code. */
    private boolean smartSemicolonEnabled;

    private long smartSemicolonGen;

    void setLspSmartSemicolonRequester(EditorBuffer.LspSmartSemicolonRequester requester) {
        this.smartSemicolonRequester = requester;
    }

    void setSmartSemicolonEnabled(boolean enabled) {
        this.smartSemicolonEnabled = enabled;
    }

    /**
     * Smart-semicolon detection (#746): a {@code ;} typed part-way through an expression belongs at the end
     * of the statement — {@code compute(1, 2|)} should become {@code compute(1, 2);|}.
     *
     * <p><b>Type first, correct after</b> — the semicolon is never held back waiting for the server. Making
     * a keystroke wait on a round trip is exactly the latency the performance rules forbid, and a slow or
     * cold server would let the character land after whatever was typed next. So the {@code ;} inserts
     * normally and the answer, if it disagrees, moves it — as a single ranged {@code replaceText}, hence one
     * undo step rather than a delete plus an insert.
     *
     * <p>The consequence, stated rather than hidden: if the user types straight through (a very common
     * {@code ;}-then-Enter), {@link EditorBuffer#docVersion} has moved by more than the semicolon and the correction is
     * <b>dropped</b>. That is deliberate — the alternative is rewriting text the user has already moved past
     * — and it leaves the semicolon exactly where it would have been with the feature off, never worse.
     */
    private void maybeSmartSemicolon(CodeArea a, char typed) {
        if (typed != ';' || !smartSemicolonEnabled || !b.lspActive || smartSemicolonRequester == null) {
            return;
        }
        if (!b.isEditable() || b.hugeFile || b.largeFile || b.isNarrowed()) {
            return;
        }
        int typedAt = a.getCaretPosition();
        int line = a.getCurrentParagraph();
        int column = a.getCaretColumn();
        long version = b.docVersion;
        long gen = ++smartSemicolonGen;
        b.sendLspChange(); // the position refers to the pre-';' text, so the server must have exactly that
        smartSemicolonRequester.request(line, column, target -> {
            // docVersion bumps once per change, so "+1" is precisely "only the semicolon landed since".
            if (gen != smartSemicolonGen || target == null || a.getScene() == null || b.docVersion != version + 1) {
                return;
            }
            moveTypedSemicolon(a, typedAt, line, column, target);
        });
    }

    /**
     * Moves the just-typed semicolon from {@code typedAt} to the server's target, which is expressed in
     * <b>pre-insert</b> coordinates — so a target on the same line has to be shifted past the semicolon
     * that now sits before it, while a target on a later line needs no adjustment (its columns are
     * untouched and its absolute offset already includes the inserted character).
     */
    private void moveTypedSemicolon(CodeArea a, int typedAt, int typedLine, int typedColumn, int[] target) {
        int targetPost;
        try {
            targetPost = target[0] == typedLine && target[1] >= typedColumn
                    ? a.getAbsolutePosition(target[0], target[1] + 1)
                    : a.getAbsolutePosition(target[0], target[1]);
        } catch (RuntimeException outOfRange) {
            return; // a target the document can no longer address
        }
        if (targetPost <= typedAt + 1 || targetPost > a.getLength()) {
            return; // already where it was typed (the ordinary answer), or past the end
        }
        // Rewrite [typedAt, targetPost) as "the text after the semicolon, then the semicolon" — one edit,
        // so one Ctrl-Z puts it back where it was typed.
        a.replaceText(typedAt, targetPost, a.getText(typedAt + 1, targetPost) + ";");
    }

    /**
     * On-type formatting (#740): after a character the server named as a trigger ({@code ;}, {@code }},
     * {@code \n} for jdtls), ask it how the line should be indented and adopt only that.
     *
     * <p><b>The local assist runs first and the server refines it.</b> That is the resolution of the
     * conflict between this and {@link #applyCloserDedent}/{@link #applyEnter}, which already act on
     * {@code }} and Enter: making a very common keystroke wait on a round-trip would be felt, and the local
     * result is usually already right — in which case {@link #applyLspLineIndent} finds the indent unchanged
     * and does nothing at all. So the two never fight; the server only corrects a disagreement.
     *
     * <p>Indentation only, never a full reformat — the user is mid-edit, and rewriting the line under them
     * is the behaviour that makes on-type formatting infuriating in other editors.
     *
     * <p>Inert unless the setting is on, the server advertises a trigger set containing this character, and
     * the buffer is an editable, normal-sized, single-caret LSP buffer. A trigger
     * consumed by auto-close (typing {@code }} to skip over an inserted one) doesn't reach here — that path
     * leaves the line already correct.
     */
    private void maybeOnTypeFormat(CodeArea a, char typed) {
        if (b.disposed || !onTypeFormattingEnabled || !b.lspActive || lspOnTypeFormatter == null) {
            return;
        }
        if (!lspOnTypeTriggers.contains(typed)) {
            return;
        }
        if (!b.isEditable() || b.hugeFile || b.largeFile || a.getSelection().getLength() > 0) {
            return;
        }
        int par = a.getCurrentParagraph();
        String line = a.getParagraph(par).getText();
        int caret = a.getCaretPosition();
        if (a.getCaretColumn() == 0 || line.charAt(a.getCaretColumn() - 1) != typed) {
            return; // the keystroke did not insert its character after all
        }
        long gen = ++reindentGen; // shares the Tab re-indent's generation: both adjust the same line's indent
        lspOnTypeFormatter.format(par, a.getCaretColumn(), typed, edits -> {
            if (gen != reindentGen
                    || a.getScene() == null
                    || a.getCurrentParagraph() != par
                    || !a.getParagraph(par).getText().equals(line)) {
                return; // stale, detached, or the user kept typing and the line moved on
            }
            applyLspLineIndent(a, par, line, caret, edits, false);
        });
    }

    /**
     * The body of the Enter auto-indent (Markdown list/blockquote continuation, else {@link Indenter}):
     * clears any selection, inserts the indented newline, and moves the caret. Shared by the {@code ENTER}
     * key filter and macro replay ({@link #typeChar} for {@code '\n'}).
     */
    private void applyEnter(CodeArea a) {
        if (a.getSelection().getLength() > 0) {
            a.replaceSelection("");
        }
        int caret = a.getCaretPosition();
        // Markdown: Enter inside a table or a list/quote item (continue it, or step out of an empty one).
        if (b.isMarkdown() && b.markdownEditing.enter(a)) {
            return;
        }
        // Typst list continuation, with its own markers (-, +, N. — never *, which is bold).
        if (b.isTypst()) {
            int par = a.getCurrentParagraph();
            int lineStart = a.getAbsolutePosition(par, 0);
            String line = a.getParagraph(par).getText();
            int markerLen = TypstMarkup.markerLength(line);
            if (markerLen > 0 && caret - lineStart >= markerLen) {
                if (TypstMarkup.isEmptyItem(line)) {
                    a.replaceText(lineStart, lineStart + line.length(), ""); // exit list (clear marker)
                    a.requestFollowCaret();
                    return;
                }
                String cont = TypstMarkup.continuation(line);
                if (cont != null) {
                    a.replaceText(caret, caret, "\n" + cont);
                    a.moveTo(caret + 1 + cont.length());
                    a.requestFollowCaret();
                    return;
                }
            }
        }
        applyCloserDedent(a, '\n'); // Enter finishes a closer keyword (fi, end, done): align it first
        caret = a.getCaretPosition();
        int par = a.getCurrentParagraph(); // Enter between JSX tags goes a level in (A104), else the usual rules
        Indenter.EnterEdit jsx = JsxContext.enter(
                b.grammar,
                b.highlighter.highlightLines,
                par,
                a.getParagraph(par).getText(),
                a.getCaretColumn(),
                b.indentUnit());
        Indenter.EnterEdit edit =
                jsx != null ? jsx : IndentWindow.enterEdit(IndentKeys.doc(a), caret, b.language, b.indentUnit());
        a.replaceText(caret, caret, edit.insert());
        a.moveTo(caret + edit.caretOffset());
        a.requestFollowCaret();
    }

    /**
     * Auto-close decision for a typed character (see {@link AutoClose}): insert a pair / type over a
     * closer / wrap the selection. Returns {@code true} when it acted (so the caller consumes the event and
     * skips normal insertion), {@code false} for ordinary typing. Shared by the auto-close key filter and
     * macro replay ({@link #typeChar}).
     */
    boolean applyAutoCloseTyped(CodeArea a, char c) {
        if (autoCloseTags && tagTyping.typed(a, c, b.language)) {
            return true; // html/xml: the > completed an open tag, or a hand-typed closer joined the one there
        }
        if (b.isMarkdown() && b.markdownEditing.typed(a, c)) {
            return true; // emphasis wrap / pair, smart typography — before the generic bracket and quote pairs
        }
        if (AutoClose.closerFor(c) == 0 && !AutoClose.isCloser(c)) {
            return false; // not a bracket or quote
        }
        int caret = a.getCaretPosition();
        char next = caret < a.getLength() ? a.getText(caret, caret + 1).charAt(0) : 0;
        boolean hasSel = a.getSelection().getLength() > 0;
        int lineStart = a.getAbsolutePosition(a.getCurrentParagraph(), 0);
        AutoClose.Decision d = AutoClose.decide(
                c,
                a.getText(lineStart, caret),
                next,
                hasSel,
                b.language,
                TypingContext.inLiteral(a, caret, b.language));
        switch (d.action()) {
            case INSERT_PAIR -> {
                a.replaceText(caret, caret, "" + c + d.closer());
                a.moveTo(caret + 1);
                return true;
            }
            case INSERT_TRIPLE -> { // the third quote of a Python triple quote: close it too
                a.replaceText(caret, caret, String.valueOf(c).repeat(4));
                a.moveTo(caret + 1);
                return true;
            }
            case SKIP_OVER -> {
                a.moveTo(caret + 1);
                return true;
            }
            case WRAP_SELECTION -> {
                int s = a.getSelection().getStart();
                String sel = a.getSelectedText();
                a.replaceText(s, a.getSelection().getEnd(), "" + c + sel + d.closer());
                a.selectRange(s + 1, s + 1 + sel.length());
                return true;
            }
            case NONE -> {
                return false; // normal typing (and the auto-indent closer-dedent may run)
            }
        }
        return false;
    }

    /**
     * When a closing token is typed alone on a line (a {@code )]}} bracket, or the character/Enter that
     * finishes a closer keyword like {@code end}/{@code fi}), re-aligns the line's indent to its opener. Does
     * <em>not</em> insert {@code c} (the caller does). Shared by the key filters and macro replay.
     */
    private void applyCloserDedent(CodeArea a, char c) {
        Indenter.Style style = IndentWindow.styleAt(IndentKeys.doc(a), a.getCaretPosition(), b.language, c);
        int caret = a.getCaretPosition();
        int lineStart = a.getAbsolutePosition(a.getCurrentParagraph(), 0);
        String beforeCaret = a.getText(lineStart, caret);
        boolean bracket = Indenter.isCloserChar(style, c) && !beforeCaret.isEmpty() && beforeCaret.isBlank();
        boolean keyword = Indenter.completesCloserKeyword(style, beforeCaret + c, b.language);
        if (!bracket && !keyword) {
            return;
        }
        // Re-align this line's indent to its opener; the typed char then inserts normally (not consumed).
        String currentIndent = b.completionActions.leadingIndent(beforeCaret);
        String aligned = IndentWindow.closerAlignIndent(
                style, IndentKeys.doc(a), caret, b.tabSize, currentIndent, c, b.indentUnit(), b.language);
        if (!aligned.equals(currentIndent)) {
            a.replaceText(lineStart, lineStart + currentIndent.length(), aligned);
            a.moveTo(caret + aligned.length() - currentIndent.length()); // back after the closer, not the indent
        }
    }

    /**
     * Replays a bare editing/navigation key press from a recorded macro — Backspace, Delete, an arrow,
     * Home/End, Page Up/Down — by {@code KeyCode} name.
     *
     * <p>The token carries the modifiers ({@code BACK_SPACE}, {@code S-DOWN}, {@code C-LEFT}), because the
     * area acts on modified variants too — Shift-Down extends the selection, Ctrl-Left goes a word left —
     * and replaying those as a bare arrow would move the caret instead.
     *
     * <p>Unlike {@link EditorBuffer#typeString}, there is no "apply" method to reuse here: the behavior of these keys
     * lives in the area's own input map (and in the Backspace filters — smart-backspace, the auto-close
     * empty-pair delete, the Markdown empty-marker delete). So this fires a real {@code KEY_PRESSED} at the
     * focused area, which runs exactly the handlers a hand-pressed key runs. Recording only ever captures
     * keys bound to no command, so the re-dispatch can't also fire a chord; the macro recorder is inert
     * during replay in any case.
     */
    void pressKey(String macroKeyToken) {
        com.editora.macro.MacroKey.Decoded k = com.editora.macro.MacroKey.decode(macroKeyToken);
        if (k == null || !b.isEditable() || b.hugeFile) {
            return;
        }
        KeyCode code;
        try {
            code = KeyCode.valueOf(k.keyCodeName());
        } catch (IllegalArgumentException e) {
            return; // a hand-edited macros.json / a step typed into the Settings editor
        }
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        a.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, k.shift(), k.ctrl(), k.alt(), k.meta()));
    }

    /** Types a single character through the editor's typing assists. See {@link EditorBuffer#typeString}. */
    void typeChar(char c) {
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        if (!b.isEditable() || b.hugeFile) {
            return;
        }
        if (c == '\n' || c == '\r') {
            applyEnter(a);
            return;
        }
        if (c == '\t') {
            if (!applySmartTab(a, false)) {
                a.replaceSelection("\t");
            }
            a.requestFollowCaret();
            return;
        }
        // Mirror the live KEY_TYPED order: auto-close first; if it didn't act, closer-dedent then insert.
        if (applyAutoCloseTyped(a, c)) {
            a.requestFollowCaret();
            return;
        }
        if (a.getSelection().getLength() == 0) {
            applyCloserDedent(a, c);
        }
        a.replaceSelection(String.valueOf(c));
        a.requestFollowCaret();
    }

    /**
     * Auto-closes brackets/quotes (installed on {@code area}/{@code area2}). A {@code KEY_TYPED} filter
     * inserts the matching closer / types over an existing one / wraps a selection (see {@link AutoClose});
     * a {@code KEY_PRESSED} filter removes both halves of an empty pair on Backspace. Added before
     * {@link #addAutoIndent} so it sees the keystroke first; when it does nothing it leaves the event
     * for normal typing (and the indent closer-dedent).
     */
    void addAutoClose(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (e.isConsumed()) return;
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (!b.isEditable() || e.getCharacter().length() != 1 || !TypedText.isText(e)) {
                return;
            }
            char c = e.getCharacter().charAt(0);
            if (applyAutoCloseTyped(a, c)) {
                e.consume();
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() != KeyCode.BACK_SPACE
                    || b.viewMode
                    || !b.isEditable()
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()
                    || e.isShiftDown()
                    || a.getSelection().getLength() > 0) {
                return;
            }
            int caret = a.getCaretPosition();
            if (caret <= 0 || caret >= a.getLength()) {
                return;
            }
            if (autoCloseTags && tagTyping.backspace(a, b.language)) {
                e.consume(); // a pending close-tag join takes back the last typed character (C22)
                return;
            }
            char prev = a.getText(caret - 1, caret).charAt(0);
            char next = a.getText(caret, caret + 1).charAt(0);
            if (AutoClose.isEmptyPair(prev, next)) {
                a.deleteText(caret - 1, caret + 1); // remove both halves of the empty pair
                e.consume();
            }
        });
    }
}
