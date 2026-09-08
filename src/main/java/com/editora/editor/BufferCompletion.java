package com.editora.editor;

import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.text.Font;

import com.editora.completion.Completion;
import com.editora.completion.CompletionEngine;
import com.editora.completion.CompletionProvider;
import com.editora.editor.EditorBuffer.AiCompletionProvider;
import com.editora.snippet.Snippet;
import org.fxmisc.richtext.CodeArea;

/** Owns a buffer's completion, code-action and documentation popups and ghost-text state. */
final class BufferCompletion {
    interface Host {
        CodeArea area();

        AnchorPane root();

        CodeArea area2();

        AnchorPane root2();

        boolean largeFile();

        long docVersion();

        boolean lspActive();

        java.util.Set<Character> lspTriggerChars();

        java.util.Set<Character> lspSignatureTriggerChars();

        java.util.function.Consumer<Character> signatureHelpRequester();

        String language();

        String fontFamily();

        int fontSize();

        CodeArea getFocusedArea();

        boolean isDiagram();

        void sendLspChange();

        boolean multiCaretActiveOn(CodeArea a);

        boolean isProse();

        String getSpellLanguage();

        boolean isEditable();

        boolean hasActiveSnippet();

        void startSnippet(CodeArea a, Snippet snippet, int from, int to);

        int lspOffset(CodeArea a, int line, int col);

        int[] lspPosition(CodeArea a, int offset);
    }

    private CompletionProvider completionProvider = (s, d, p, sp, prose) -> List.of();
    private final Host host;

    BufferCompletion(Host host) {
        this.host = host;
    }

    /** When false the whole autocomplete feature is inert (master Settings toggle). */
    boolean autocompleteEnabled = true;

    /** Per-source autocomplete toggles (gated by {@link #autocompleteEnabled}). */
    boolean autocompleteProse = true;

    boolean autocompleteSnippets = true;

    boolean autocompleteMermaid = true;

    /** The caret-anchored completion dropdown (lazily created). */
    CompletionPopup completionPopup;

    /** Injected async LSP completion source (code buffers); generation guard for stale async results. */
    java.util.function.BiConsumer<int[], java.util.function.Consumer<java.util.List<Completion>>> lspCompletionProvider;

    long completionGen;

    /** The view the completion popup is currently driven by (for click-accept routing). */
    CodeArea completionArea;

    /** The quick-fix list, anchored at the caret (#767). Lazily built, like the completion popup. */
    CodeActionPopup codeActionPopup;

    /** The area the quick-fix list belongs to, so its key ownership can be released on hide. */
    CodeArea codeActionArea;

    /** The IntelliJ-style documentation side-popup (lazily created) + its lazy resolver/state. */
    CompletionDocPopup docPopup;

    java.util.function.BiConsumer<Object, java.util.function.Consumer<String>> completionDocResolver;

    boolean completionDocEnabled = true;

    boolean docPopupActive;

    long docGen;

    javafx.animation.PauseTransition docDebounce;

    /**
     * The {@code docVersion} right after we programmatically accepted a completion, so the auto-trigger
     * that the accept's own edit schedules is suppressed — but a real edit after it (which bumps the
     * version) is not. A plain boolean cleared on the next pulse would be useless here: the debounced
     * trigger it must gate is ~280 ms away.
     */
    long suppressCompletionAtVersion = -1;

    /**
     * The edit the last completion accept made, measured around it, so the {@code additionalTextEdits} a later
     * {@code completionItem/resolve} returns — positions the server computed against the document as it stood
     * <em>before</em> that accept — can be translated into the document as it stands now. See
     * {@link LspEditShift}.
     */
    LspEditShift.Change pendingCompletionShift;

    /** Inline "ghost text" suggestion (prose buffers): a single muted suffix drawn after the caret. */
    Label ghostLabel;

    /** AI inline completion (any buffer): provider + gate + stale-result guard (see ui.AiCoordinator). */
    AiCompletionProvider aiCompletionProvider;

    boolean aiCompletionEnabled;

    long aiCompletionGen;

    String ghostSuffix;

    CodeArea ghostArea;

    /** The two document offsets currently carrying the {@code brace-match} style, or null. */
    int[] braceMatch;

    /** Longest snippet prefix we'll look up ({@code [SuppressMessageAttribute]} is the longest bundled one). */
    static final int MAX_SNIPPET_PREFIX = 40;

    /** Start of the non-whitespace token ending at {@code caret}, bounded to {@link #MAX_SNIPPET_PREFIX}. */
    static int snippetTokenStart(String text, int caret) {
        int start = caret;
        int limit = Math.max(0, caret - MAX_SNIPPET_PREFIX);
        while (start > limit && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    /** Leading whitespace (spaces/tabs) of a line, used to indent a snippet's continuation lines. */
    static String leadingIndent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    static boolean isPrefixChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Injects the completion lookup (set by the controller), mirroring {@link #setSnippetProvider}. */
    public void setCompletionProvider(CompletionProvider provider) {
        if (provider != null) {
            completionProvider = provider;
        }
    }

    /** Applies the autocomplete settings: the master toggle plus per-source toggles (prose / snippets /
     *  mermaid). The {@code mermaid} flag is the effective value (already gated on the feature + tools). */
    public void setAutocomplete(boolean enabled, boolean prose, boolean snippets, boolean mermaid) {
        this.autocompleteEnabled = enabled;
        this.autocompleteProse = prose;
        this.autocompleteSnippets = snippets;
        this.autocompleteMermaid = mermaid;
        if (!enabled) {
            hideCompletion();
        }
    }

    /** Injects the AI completion lookup (set by the controller), mirroring {@link #setCompletionProvider}. */
    public void setAiCompletionProvider(AiCompletionProvider provider) {
        this.aiCompletionProvider = provider;
    }

    /** Applies the effective AI-inline-completion gate (feature on + key present, pushed by the controller). */
    public void setAiCompletionEnabled(boolean enabled) {
        this.aiCompletionEnabled = enabled;
        if (!enabled) {
            aiCompletionGen++; // drop any in-flight request's result
            hideGhost();
        }
    }

    CompletionPopup completionPopup() {
        if (completionPopup == null) {
            completionPopup = new CompletionPopup();
            completionPopup.setOnAccept(c -> {
                if (completionArea != null) {
                    acceptCompletion(completionArea, c);
                }
            });
            completionPopup.setOnSelect(this::onCompletionSelect); // drive the documentation side-popup
            completionPopup.setOnHidden(this::hideDocPopup); // also tear the doc popup down on auto-hide
        }
        return completionPopup;
    }

    /** Injects the lazy doc resolver (opaque token → markdown), set by the controller (LSP-backed). */
    public void setCompletionDocResolver(
            java.util.function.BiConsumer<Object, java.util.function.Consumer<String>> resolver) {
        this.completionDocResolver = resolver;
    }

    /** Whether the documentation popup auto-shows beside the completion list (the Settings toggle). */
    public void setCompletionDocEnabled(boolean enabled) {
        this.completionDocEnabled = enabled;
        if (!enabled) {
            hideDocPopup();
        }
    }

    CompletionDocPopup docPopup() {
        if (docPopup == null) {
            docPopup = new CompletionDocPopup();
        }
        return docPopup;
    }

    /** Ctrl+Q: toggle the doc popup for the open completion session (show/hide for the current selection). */
    public void toggleCompletionDoc() {
        if (completionPopup == null || !completionPopup.isShowing()) {
            return;
        }
        docPopupActive = !docPopupActive;
        if (docPopupActive) {
            scheduleDoc(completionPopup.selected());
        } else {
            hideDocPopup();
        }
    }

    void onCompletionSelect(Completion c) {
        if (docPopupActive) {
            scheduleDoc(c);
        }
    }

    /** Debounced per-selection doc fetch (so arrowing quickly doesn't hammer the resolver). */
    void scheduleDoc(Completion c) {
        docGen++;
        if (c == null || completionDocResolver == null) {
            hideDocPopup();
            return;
        }
        if (docDebounce == null) {
            docDebounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));
        }
        docDebounce.stop();
        long gen = docGen;
        docDebounce.setOnFinished(e -> requestDoc(c, gen));
        docDebounce.playFromStart();
    }

    void requestDoc(Completion c, long gen) {
        if (gen != docGen || !docPopupActive || completionPopup == null || !completionPopup.isShowing()) {
            return;
        }
        completionDocResolver.accept(c.resolveToken(), doc -> {
            if (gen != docGen || !docPopupActive || completionPopup == null || !completionPopup.isShowing()) {
                return;
            }
            showDoc(c, doc);
        });
    }

    void showDoc(Completion c, String doc) {
        // The declaration (signature/type) header is shown for every item; the rendered documentation is
        // added only when the server provides any. Nothing to show ⇒ hide (e.g. a plain word/snippet).
        String signature = c.detail() == null ? "" : c.detail().strip();
        javafx.scene.Node rendered = null;
        if (doc != null && !doc.isBlank()) {
            try {
                rendered = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(doc.strip()), null);
            } catch (RuntimeException ex) {
                rendered = null;
            }
        }
        if (signature.isBlank() && rendered == null) {
            hideDocPopup();
            return;
        }
        Bounds anchor = completionPopup.screenBounds();
        if (anchor == null || completionArea == null || completionArea.getScene() == null) {
            return;
        }
        docPopup().show(completionArea.getScene().getWindow(), anchor, signature, rendered);
    }

    void hideDocPopup() {
        docGen++;
        if (docDebounce != null) {
            docDebounce.stop();
        }
        if (docPopup != null) {
            docPopup.hide();
        }
    }

    /** True while the caret-anchored quick-fix list is up. */
    public boolean codeActionsShowing() {
        return codeActionPopup != null && codeActionPopup.isShowing();
    }

    /**
     * Screen bounds to anchor a caret-following popup to, or {@code null} if the area isn't on screen.
     *
     * <p><b>Never the zero-width {@code getCharacterBoundsOnScreen(caret, caret)} form</b>, which is what
     * all three callers used to do: for an empty range {@code GenericStyledArea} allocates a throwaway
     * {@link org.fxmisc.richtext.CaretNode} to measure with, and a {@code CaretNode} starts a 500 ms blink
     * timer that nothing ever stops — so each call permanently registers a running {@code Timeline} as a
     * JavaFX pulse receiver. Measured at <b>exactly one leaked timer per popup open</b>, which for the
     * completion list means one per debounced trigger, all session. Same fault, same fix as
     * {@link #caretBounds} — measure a <em>one-character</em> range, which takes a different path and
     * allocates nothing.
     *
     * <p><b>The fallbacks are not defensive padding; each covers a case the leaking form silently handled.</b>
     * That form measured through a caret it had just created, so it answered even when nothing was rendered.
     * A character can only be measured once its paragraph has been laid out, so it comes back empty whenever
     * the flow has no cell for that line — the caret scrolled out of view, or the area not yet laid out at
     * all. Returning {@code null} there means the popup <em>silently does not open</em>, which is how this
     * first shipped and what {@code CodeActionPopupFxTest} caught in the full suite (it passes alone, where
     * the area happens to be laid out). So: the character, then the rendered caret, then the area's own
     * top-left — anchoring a popup at the corner of the editor is far better than declining to show it.
     *
     * <p>{@code CodeArea.getCaretBounds()} is second rather than first because it reports the
     * <em>rendered</em> caret and is empty whenever the area isn't focused.
     */
    static Bounds caretAnchorBounds(CodeArea a) {
        int caret = a.getCaretPosition();
        var pos = a.offsetToPosition(caret, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
        int len = a.getParagraphLength(pos.getMajor());
        Bounds measured = null;
        if (pos.getMinor() < len) {
            measured = a.getCharacterBoundsOnScreen(caret, caret + 1).orElse(null);
        } else if (caret > 0 && len > 0) {
            // End of a non-empty line: no glyph at the caret, so take the last character's right edge —
            // the same x the caret sits at.
            Bounds last = a.getCharacterBoundsOnScreen(caret - 1, caret).orElse(null);
            if (last != null) {
                measured = new javafx.geometry.BoundingBox(last.getMaxX(), last.getMinY(), 0, last.getHeight());
            }
        }
        if (measured != null) {
            return measured;
        }
        Bounds rendered = a.getCaretBounds().orElse(null);
        return rendered != null ? rendered : a.localToScreen(a.getBoundsInLocal());
    }

    public void showCodeActions(List<CodeAction> actions, java.util.function.Consumer<CodeAction> onAccept) {
        CodeArea a = host.getFocusedArea();
        if (a == null || actions == null || actions.isEmpty()) {
            return;
        }
        Bounds caretScreen = caretAnchorBounds(a);
        if (caretScreen == null) {
            return;
        }
        hideCompletion(); // the two lists are both caret-anchored; never stack them
        if (codeActionPopup == null) {
            codeActionPopup = new CodeActionPopup();
            // However the popup ends up hidden, the key ownership goes back with it.
            codeActionPopup.setOnHidden(this::releaseCodeActionKeys);
        }
        codeActionPopup.setOnAccept(action -> {
            hideCodeActions();
            if (action != null && onAccept != null) {
                onAccept.accept(action);
            }
        });
        codeActionArea = a;
        // Own the editor-context chords so C-n/C-p reach the list rather than moving the caret.
        a.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        codeActionPopup.show(a.getScene().getWindow(), caretScreen, actions);
    }

    /** Dismisses the quick-fix list and returns the editor chords to the dispatcher. */
    public void hideCodeActions() {
        if (codeActionPopup != null) {
            codeActionPopup.hide();
        }
        releaseCodeActionKeys();
    }

    /** Hands the editor-context chords back. Idempotent, since it runs from both hide paths. */
    void releaseCodeActionKeys() {
        if (codeActionArea != null) {
            codeActionArea.getProperties().remove("editora.ownsKeys");
            codeActionArea = null;
        }
    }

    void addCompletionKeys(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (host.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            // Inline ghost text (prose): Tab accepts, Esc dismisses; anything else lets the caret move
            // (the caret listener then clears it and the debounce recomputes).
            if (ghostVisible()) {
                switch (e.getCode()) {
                    case TAB -> {
                        if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                            hideGhost();
                        } else {
                            acceptGhost();
                            e.consume();
                        }
                    }
                    case ESCAPE -> {
                        hideGhost();
                        e.consume();
                    }
                    default -> {} // typing/Backspace/arrows fall through
                }
                return;
            }
            // Quick-fix list first: it and the completion popup are both caret-anchored and never coexist
            // (showCodeActions hides completion), so whichever is up owns the keys.
            if (codeActionsShowing()) {
                boolean ctrl = e.isControlDown() && !e.isAltDown() && !e.isMetaDown();
                switch (e.getCode()) {
                    case DOWN -> {
                        codeActionPopup.moveDown();
                        e.consume();
                    }
                    case UP -> {
                        codeActionPopup.moveUp();
                        e.consume();
                    }
                    case ENTER -> {
                        codeActionPopup.accept();
                        e.consume();
                    }
                    case ESCAPE -> {
                        hideCodeActions();
                        e.consume();
                    }
                    case N -> {
                        if (ctrl) {
                            codeActionPopup.moveDown();
                            e.consume();
                        }
                    }
                    case P -> {
                        if (ctrl) {
                            codeActionPopup.moveUp();
                            e.consume();
                        }
                    }
                    case G -> {
                        if (ctrl) { // C-g cancels, as everywhere else in the editor
                            hideCodeActions();
                            e.consume();
                        }
                    }
                    default -> {
                        // Anything else dismisses: the list is about the caret's current position, and a
                        // keystroke that moves or edits invalidates it.
                        hideCodeActions();
                    }
                }
                return;
            }
            if (completionPopup == null || !completionPopup.isShowing()) {
                return;
            }
            // Emacs-style C-n / C-p move the selection too (the area owns these keys while the popup is
            // open — see setOwnsKeys — so the global dispatcher leaves them for us).
            if (e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                if (e.getCode() == KeyCode.N) {
                    completionPopup.moveDown();
                    e.consume();
                    return;
                }
                if (e.getCode() == KeyCode.P) {
                    completionPopup.moveUp();
                    e.consume();
                    return;
                }
                if (e.getCode() == KeyCode.G) { // C-g cancels (Emacs); Esc is handled below
                    hideCompletion();
                    e.consume();
                    return;
                }
                if (e.getCode() == KeyCode.Q) { // C-q toggles the documentation popup (IntelliJ quick-doc)
                    toggleCompletionDoc();
                    e.consume();
                    return;
                }
            }
            switch (e.getCode()) {
                case DOWN -> {
                    completionPopup.moveDown();
                    e.consume();
                }
                case UP -> {
                    completionPopup.moveUp();
                    e.consume();
                }
                case PAGE_DOWN -> {
                    completionPopup.pageDown();
                    e.consume();
                }
                case PAGE_UP -> {
                    completionPopup.pageUp();
                    e.consume();
                }
                case ENTER, TAB -> {
                    if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                        hideCompletion();
                        return;
                    }
                    Completion sel = completionPopup.selected();
                    if (sel != null) {
                        acceptCompletion(a, sel);
                        e.consume();
                    } else {
                        hideCompletion();
                    }
                }
                case ESCAPE -> {
                    hideCompletion();
                    e.consume();
                }
                case LEFT, RIGHT, HOME, END -> hideCompletion(); // let the caret move
                default -> {} // letters/Backspace fall through; the debounced trigger refreshes the list
            }
        });
        a.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                hideCompletion();
            }
        });
    }

    /** Installs the immediate completion/signature lifecycle hooks for one view. Settled completion and
     *  AI requests are shared by the document-level dispatcher, including when this is the split view. */
    void installCompletionTrigger(CodeArea a) {
        // Signature help fires IMMEDIATELY on a typed trigger char ('(' / ','), not on the 280 ms pause —
        // the overload popup must appear as the call is opened. Guarded to three cheap checks per edit
        // when off; the request itself is deferred a pulse so the triggering insertion's caret settles
        // first (the maybeAutoFill lesson) — reading the caret inside the change emission sees a stale
        // position (#674).
        a.multiPlainChanges().subscribe(changes -> maybeTriggerSignatureHelp(a, changes));
        // Any caret move or scroll invalidates the inline ghost's position; clear it (the debounce
        // re-shows it after the next pause in typing). The popup manages its own key/caret handling.
        a.caretPositionProperty().addListener((o, ov, nv) -> {
            hideGhost();
            dismissCompletionIfPrefixEmpty(a); // close at once when Backspace deletes the whole typed word
        });
        a.estimatedScrollYProperty().addListener((o, ov, nv) -> hideGhost());
    }

    /** See {@code installCompletionTrigger}: fires the signature-help requester on a typed trigger char. */
    void maybeTriggerSignatureHelp(CodeArea a, java.util.List<org.fxmisc.richtext.model.PlainTextChange> changes) {
        if (host.signatureHelpRequester() == null
                || !host.lspActive()
                || host.lspSignatureTriggerChars().isEmpty()) {
            return; // the per-keystroke hot path for every non-LSP buffer: three field checks, nothing more
        }
        if (!a.isFocused() || changes.size() != 1) {
            return;
        }
        org.fxmisc.richtext.model.PlainTextChange c = changes.get(0);
        if (signatureTriggerTyped(c.getInserted(), c.getRemoved())) {
            char trigger = c.getInserted().charAt(0); // the char, so the server sees TriggerCharacter (#725)
            Platform.runLater(() -> host.signatureHelpRequester().accept(trigger));
        }
    }

    /**
     * Whether {@code inserted}/{@code removed} is a single typed signature-help trigger character. The
     * subtlety: with bracket auto-close on (the default), typing {@code (} inserts {@code ()} as one 2-char
     * change — so a bare {@code length()==1} check silently never fired for {@code (}, the primary trigger
     * (#674, caught in device-testing). We also accept a trigger char that auto-close expanded to a pair:
     * length 1 (auto-close off, or {@code ,}), or length 2 whose second char closes the first. A paste
     * (longer, or a non-empty removal) never triggers.
     */
    boolean signatureTriggerTyped(String inserted, String removed) {
        if (inserted.isEmpty() || (removed != null && !removed.isEmpty())) {
            return false;
        }
        if (!host.lspSignatureTriggerChars().contains(inserted.charAt(0))) {
            return false;
        }
        return inserted.length() == 1 || (inserted.length() == 2 && isCloserChar(inserted.charAt(1)));
    }

    static boolean isCloserChar(char ch) {
        return ch == ')' || ch == ']' || ch == '}' || ch == '>';
    }

    /**
     * Closes the popup the instant the caret lands where there is no word prefix — e.g. Backspace deleted
     * the whole typed word — so the list doesn't linger over an empty prefix (and isn't left to the 280 ms
     * debounce). Kept open only when the caret is right after an LSP trigger char (member completion).
     */
    void dismissCompletionIfPrefixEmpty(CodeArea a) {
        if (completionPopup == null || !completionPopup.isShowing()) {
            return;
        }
        int caret = a.getCaretPosition();
        // Only the chars just before the caret matter — read a bounded window instead of materializing the
        // whole document (this fires per caret-move while the popup is open; getText() on a big file is O(n)).
        int from = Math.max(0, caret - PREFIX_LOOKBACK);
        String before = a.getText(from, caret);
        int kept = before.length();
        while (kept > 0 && isPrefixChar(before.charAt(kept - 1))) {
            kept--;
        }
        boolean afterTrigger = host.lspActive() && !host.isProse() && endsWithLspTrigger(before, before.length());
        if (kept == before.length() && !afterTrigger) {
            hideCompletion();
        }
    }

    /** Lookback window (chars) when checking the prefix before the caret — far longer than any real prefix. */
    static final int PREFIX_LOOKBACK = 256;

    /** True while the completion popup or the inline ghost is visible. */
    public boolean completionShowing() {
        return ghostVisible() || (completionPopup != null && completionPopup.isShowing());
    }

    /** Dismisses any active completion (popup or ghost) — the {@code edit.cancel} / Escape path. */
    public void cancelCompletion() {
        hideCompletion();
    }

    /** Manual trigger (the {@code edit.completion} command), on the focused view. */
    public void triggerCompletion() {
        CodeArea a = (host.area2() != null && host.area2().isFocused()) ? host.area2() : host.area();
        updateCompletion(a, true);
    }

    /** Recomputes the word at the caret and shows/refreshes/hides the popup. {@code manual} lowers the
     *  minimum prefix length so an explicit invoke works on a single character. */
    void updateCompletion(CodeArea a, boolean manual) {
        if (!autocompleteEnabled
                || host.largeFile() // off in large-file mode: a.getText() below would allocate the whole document
                // per trigger (the same per-keystroke cost brace matching is gated to avoid), and there is
                // no LSP/grammar to complete against on a large file anyway. largeFile implies hugeFile.
                || !host.isEditable()
                || host.hasActiveSnippet()
                || (!manual && host.docVersion() == suppressCompletionAtVersion) // don't re-offer right after accept
                || a.getSelection().getLength() > 0) {
            hideCompletion();
            return;
        }
        int caret = a.getCaretPosition();
        String text = a.getText();
        int start = caret;
        while (start > 0 && isPrefixChar(text.charAt(start - 1))) {
            start--;
        }
        String prefix = text.substring(start, caret);
        int min = manual ? 1 : CompletionEngine.MIN_PREFIX;
        // LSP "trigger character" (e.g. Java's '.'): fire member completion with no prefix, and keep an
        // open LSP popup updating as the member name is typed (IntelliJ-style) — bypassing the min-prefix.
        // Keeping it open with an empty prefix is only right immediately after a trigger char; once the
        // user backspaces past the typed word (prefix empty, not after a trigger) the popup must close.
        boolean lspTrigger = host.lspActive()
                && !host.isProse()
                && (endsWithLspTrigger(text, caret) || (completionPopupShowing() && !prefix.isEmpty()));
        if (prefix.length() < min && !lspTrigger) {
            hideCompletion();
            return;
        }
        // Per-source toggle: prose → word/dictionary, mermaid → keywords+snippets, other code → snippets.
        boolean sourceOn =
                host.isProse() ? autocompleteProse : (host.isDiagram() ? autocompleteMermaid : autocompleteSnippets);
        if (!sourceOn) {
            hideCompletion();
            return;
        }
        // Snippets match the whole non-whitespace token (so a non-identifier trigger — `#inc`→`#include`,
        // `?xml`, `[PSCustomObject]` — surfaces in the popup, not only via Tab, #446); words/keywords use the
        // identifier run. When the token is wider than the identifier, stamp a replace range on the
        // snippet-kind items so accepting one replaces `[wideStart, caret]` (the whole `#inc`, never
        // `##include`) — start only, since the caret is the right end for a locally derived range.
        int wideStart = snippetTokenStart(text, caret);
        String wideToken = text.substring(wideStart, caret);
        List<Completion> items = completionProvider.complete(
                host.language(), host.getSpellLanguage(), prefix, wideToken, host.isProse());
        if (a.getScene() == null) {
            return;
        }
        if (wideStart < start && !items.isEmpty()) {
            var pos = a.offsetToPosition(wideStart, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
            Completion.ReplaceRange rs = Completion.ReplaceRange.startingAt(pos.getMajor(), pos.getMinor());
            items = items.stream()
                    .map(c -> c.snippet() != null && c.replaceRange() == null ? c.withReplaceRange(rs) : c)
                    .toList();
        }
        // Prose: a single inline "ghost text" suffix after the caret (only at end-of-line content, so it
        // never overlaps following text). Code: the multi-choice popup. Handle prose BEFORE any
        // empty-items return, so the dictionary-load retry still gets registered on the first keystrokes.
        if (host.isProse()) {
            int lineEnd = caret;
            while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            String suffix = text.substring(caret, lineEnd).isBlank() ? bestGhostSuffix(items, prefix) : null;
            if (suffix != null && !suffix.isEmpty()) {
                hidePopup();
                showGhost(a, suffix);
                return;
            }
            hideCompletion();
            // The word list loads off-thread; if it isn't ready yet, re-run once it lands so the first
            // suggestion appears without needing another keystroke.
            String dl = host.getSpellLanguage();
            if (com.editora.completion.DictionaryWords.isAvailable(dl)
                    && !com.editora.completion.DictionaryWords.isReady(dl)) {
                com.editora.completion.DictionaryWords.ensureLoaded(dl, () -> {
                    if (a.isFocused()) {
                        updateCompletion(a, manual);
                    }
                });
            }
            return;
        }
        hideGhost();
        // Code buffer with a language server: fetch LSP completions async and merge with local snippets.
        if (host.lspActive() && lspCompletionProvider != null) {
            requestLspCompletion(a, caret, prefix, items);
            return;
        }
        if (items.isEmpty()) {
            hidePopup();
            return;
        }
        Bounds caretScreen = caretAnchorBounds(a);
        if (caretScreen == null) {
            return;
        }
        completionArea = a;
        // Take ownership of editor-context chords so C-n/C-p reach the popup instead of moving the caret.
        a.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        docPopupActive = completionDocEnabled; // arm the doc popup for this session (Ctrl+Q toggles it)
        completionPopup().setQuery(prefix);
        completionPopup().show(a.getScene().getWindow(), caretScreen, items, 0);
    }

    /** Prompt-window sizes for AI inline completion (chars before/after the caret). */
    static final int AI_COMPLETION_PREFIX_CHARS = 4000;

    static final int AI_COMPLETION_SUFFIX_CHARS = 1000;

    /**
     * Fires one AI inline-completion request after a typing pause: only in an editable, non-large buffer
     * with the caret at end-of-line content (the prose-ghost convention — the suggestion never overlaps
     * following text) and no completion popup open. The async result shows as ghost text (Tab accepts)
     * only if the caret hasn't moved and no newer request superseded it.
     */
    void maybeRequestAiCompletion(CodeArea a) {
        if (!aiCompletionEnabled
                || aiCompletionProvider == null
                || host.largeFile()
                || !host.isEditable()
                || host.hasActiveSnippet()
                || a.getSelection().getLength() > 0
                || completionPopupShowing()) {
            return;
        }
        int caret = a.getCaretPosition();
        if (caret == 0) {
            return; // nothing to continue yet
        }
        String text = a.getText();
        int lineEnd = caret;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        if (!text.substring(caret, lineEnd).isBlank()) {
            return;
        }
        String prefix = text.substring(Math.max(0, caret - AI_COMPLETION_PREFIX_CHARS), caret);
        String suffix = text.substring(caret, Math.min(text.length(), caret + AI_COMPLETION_SUFFIX_CHARS));
        long gen = ++aiCompletionGen;
        aiCompletionProvider.complete(host.language(), prefix, suffix, result -> {
            if (gen != aiCompletionGen
                    || result == null
                    || result.isBlank()
                    || !a.isFocused()
                    || a.getCaretPosition() != caret
                    || completionPopupShowing()) {
                return;
            }
            String oneLine = result.lines().findFirst().orElse("").stripTrailing();
            if (!oneLine.isEmpty()) {
                showGhost(a, oneLine);
            }
        });
    }

    /** The suffix of the best word completion that continues {@code prefix}, or null if none qualifies. */
    static String bestGhostSuffix(List<Completion> items, String prefix) {
        for (Completion c : items) {
            if (c.kind() == Completion.Kind.WORD) {
                // The accept path keeps the typed prefix and inserts only this suffix, so the word must be
                // cased compatibly with what was typed (see CompletionEngine.ghostSuffix).
                String suffix = CompletionEngine.ghostSuffix(c.insert(), prefix);
                if (suffix != null) {
                    return suffix;
                }
            }
        }
        return null;
    }

    Label ghostLabel() {
        if (ghostLabel == null) {
            ghostLabel = new Label();
            ghostLabel.getStyleClass().add("completion-ghost");
            ghostLabel.setMouseTransparent(true);
            ghostLabel.setManaged(false); // free-positioned via layoutX/Y, ignored by AnchorPane layout
            ghostLabel.setFocusTraversable(false);
            ghostLabel.setAlignment(Pos.CENTER_LEFT); // center text in the line-box height (vertical align)
            ghostLabel.setVisible(false);
        }
        return ghostLabel;
    }

    boolean ghostVisible() {
        return ghostLabel != null && ghostLabel.isVisible();
    }

    /** Draws the ghost suffix as a muted overlay label starting exactly at the caret. */
    void showGhost(CodeArea a, String suffix) {
        int caret = a.getCaretPosition();
        // At end-of-line there's no glyph *at* the caret, so measure the char *before* it and use its
        // right edge as the start x (getCharacterBoundsOnScreen(caret, caret) would be empty there).
        boolean usePrev = caret > 0;
        Bounds screen = (usePrev
                        ? a.getCharacterBoundsOnScreen(caret - 1, caret)
                        : a.getCharacterBoundsOnScreen(caret, caret + 1))
                .orElse(null);
        if (screen == null) {
            return;
        }
        AnchorPane target = (a == host.area2() && host.root2() != null) ? host.root2() : host.root();
        Bounds local = target.screenToLocal(screen);
        if (local == null) {
            return;
        }
        Label g = ghostLabel();
        if (g.getParent() != target) {
            if (g.getParent() instanceof AnchorPane ap) {
                ap.getChildren().remove(g);
            }
            target.getChildren().add(g);
        }
        g.setFont(Font.font(host.fontFamily(), host.fontSize()));
        g.setText(suffix);
        // Unmanaged node: AnchorPane won't lay it out, so size + place it ourselves. applyCss() first so
        // the Label's skin exists and prefWidth reflects the text (otherwise it stays 0×0). Use the
        // measured line-box height and let the Label center the text in it, so it aligns with the line.
        g.applyCss();
        double w = Math.ceil(g.prefWidth(-1));
        double h = local.getHeight() > 0 ? local.getHeight() : Math.ceil(g.prefHeight(w));
        g.resizeRelocate(usePrev ? local.getMaxX() : local.getMinX(), local.getMinY(), w, h);
        g.setVisible(true);
        g.toFront();
        ghostSuffix = suffix;
        ghostArea = a;
    }

    /** Inserts the pending ghost suffix at the caret (the Tab-accept path for inline completion). */
    void acceptGhost() {
        if (ghostSuffix == null || ghostArea == null) {
            return;
        }
        CodeArea a = ghostArea;
        String s = ghostSuffix;
        hideGhost();
        a.insertText(a.getCaretPosition(), s);
        // Stamped AFTER the edit (which bumps docVersion), so the auto-trigger that edit schedules is the
        // one suppressed — the user typing on afterwards bumps the version again and re-enables it.
        suppressCompletionAtVersion = host.docVersion();
        a.requestFocus();
    }

    void hideGhost() {
        if (ghostLabel != null) {
            ghostLabel.setVisible(false);
        }
        ghostSuffix = null;
        ghostArea = null;
    }

    void hidePopup() {
        completionGen++; // invalidate any in-flight async (LSP) completion so a late result won't re-show
        if (completionArea != null) {
            completionArea.getProperties().remove("editora.ownsKeys"); // release the C-n/C-p ownership
        }
        if (completionPopup != null) {
            completionPopup.hide();
        }
        hideDocPopup(); // the doc popup never outlives the completion list
    }

    /** Injected async LSP completion source: {@code accept({line,char}, items->…)}; null = none. */
    public void setLspCompletionProvider(
            java.util.function.BiConsumer<int[], java.util.function.Consumer<java.util.List<Completion>>> provider) {
        this.lspCompletionProvider = provider;
    }

    /** Requests LSP completions async, filters them by the typed {@code prefix} (the server returns the
     *  whole scope and leaves filtering to the client), then shows them merged with the local snippets. */
    void requestLspCompletion(CodeArea a, int caret, String prefix, java.util.List<Completion> localItems) {
        long gen = ++completionGen;
        long version = host.docVersion();
        // Flush the current text to the server FIRST: the completion auto-trigger (≈120ms) fires before
        // the debounced didChange (≈300ms), so without this the server still has stale text and member
        // completion after '.' resolves against the old document. JSON-RPC preserves order, so this
        // didChange is applied before the completion request below. (Skipped when the server already has this
        // version — e.g. the debounced pulse just sent it — so it doesn't re-materialize the whole document.)
        host.sendLspChange();
        lspCompletionProvider.accept(new int[] {a.getCurrentParagraph(), a.getCaretColumn()}, lspItems -> {
            // Drop a response the document has moved past. The caret check alone isn't enough: an edit can
            // put the caret back on the same offset (select the word, retype it), which would show items
            // computed for the old text.
            if (gen != completionGen
                    || host.docVersion() != version
                    || a.getScene() == null
                    || a.getCaretPosition() != caret
                    || host.hasActiveSnippet()
                    // A completion request already in flight when the quick-fix list opened must not land on
                    // top of it: both are caret-anchored and both claim the editor's chords, so the late
                    // arrival would steal the keys from a list the user is looking at (#767).
                    || codeActionsShowing()) {
                return;
            }
            // Order LSP items by the server's relevance (preselect, then sortText) — IntelliJ-style —
            // before merging the local snippets in after them.
            java.util.List<Completion> ordered = CompletionEngine.sortLspByRelevance(filterByPrefix(lspItems, prefix));
            java.util.List<Completion> merged = mergeCompletions(ordered, localItems);
            if (merged.isEmpty()) {
                hidePopup();
                return;
            }
            Bounds cs = caretAnchorBounds(a);
            if (cs == null) {
                return;
            }
            completionArea = a;
            a.getProperties().put("editora.ownsKeys", Boolean.TRUE);
            docPopupActive = completionDocEnabled; // arm the doc popup for this session (Ctrl+Q toggles it)
            completionPopup().setQuery(prefix);
            completionPopup().show(a.getScene().getWindow(), cs, merged, preselectIndexOf(merged));
        });
    }

    /** Whether the completion popup is currently open (an in-progress LSP/local completion session). */
    boolean completionPopupShowing() {
        return completionPopup != null && completionPopup.isShowing();
    }

    /** True if the char just before {@code caret} is one of the server's advertised completion trigger
     *  characters (e.g. {@code .} for Java, {@code <} for HTML) — so completion fires there, not just on a
     *  word prefix. The set comes from the server's capabilities via {@link #setLspTriggerChars}.
     *  Whitespace is never treated as a trigger: no language completes on a space, and doing so would keep
     *  the popup open on a blank/indented line after the typed word is deleted. */
    boolean endsWithLspTrigger(String text, int caret) {
        if (caret <= 0 || caret > text.length()) {
            return false;
        }
        char c = text.charAt(caret - 1);
        return !Character.isWhitespace(c) && host.lspTriggerChars().contains(c);
    }

    /** Keeps LSP items whose label or insert text starts with {@code prefix} (case-insensitive). The
     *  server returns the full scope; this is the client-side prefix filtering LSP expects. Blank prefix
     *  ⇒ unfiltered. */
    static java.util.List<Completion> filterByPrefix(java.util.List<Completion> items, String prefix) {
        if (items == null || items.isEmpty() || prefix == null || prefix.isBlank()) {
            return items == null ? java.util.List.of() : items;
        }
        String p = prefix.toLowerCase(java.util.Locale.ROOT);
        java.util.List<Completion> strict = new java.util.ArrayList<>();
        java.util.List<Completion> fuzzy = new java.util.ArrayList<>();
        for (Completion c : items) {
            String label = c.label() == null ? "" : c.label().toLowerCase(java.util.Locale.ROOT);
            String insert = c.insert() == null ? "" : c.insert().toLowerCase(java.util.Locale.ROOT);
            if (label.startsWith(p) || insert.startsWith(p)) {
                strict.add(c);
            } else if (isSubsequence(p, label) || isSubsequence(p, insert)) {
                fuzzy.add(c);
            }
        }
        // Prefer literal-prefix matches (Java/TS return the whole scope, so this narrows it). When there
        // are none, fall back to the server's fuzzy matches — some servers (Pyright) already narrow
        // server-side and return subsequence matches, so strict filtering would empty the popup.
        return strict.isEmpty() ? fuzzy : strict;
    }

    /** True if {@code p} is a subsequence of {@code s} (chars in order, not necessarily contiguous). */
    static boolean isSubsequence(String p, String s) {
        int i = 0;
        for (int j = 0; j < s.length() && i < p.length(); j++) {
            if (s.charAt(j) == p.charAt(i)) {
                i++;
            }
        }
        return i == p.length();
    }

    /** Index of the first preselected item, or 0 (the popup pre-highlights the server's preselect). */
    static int preselectIndexOf(java.util.List<Completion> items) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).preselect()) {
                return i;
            }
        }
        return 0;
    }

    /** Merges LSP completions (first) with local snippet items, de-duped by insert text, capped. */
    static java.util.List<Completion> mergeCompletions(
            java.util.List<Completion> lsp, java.util.List<Completion> local) {
        java.util.LinkedHashMap<String, Completion> byInsert = new java.util.LinkedHashMap<>();
        if (lsp != null) {
            for (Completion c : lsp) {
                byInsert.putIfAbsent(c.insert(), c);
            }
        }
        for (Completion c : local) {
            byInsert.putIfAbsent(c.insert(), c);
        }
        java.util.List<Completion> out = new java.util.ArrayList<>(byInsert.values());
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    /** Replaces the typed prefix with the accepted completion (a snippet starts a tab-stop session). */
    void acceptCompletion(CodeArea a, Completion c) {
        int caret = a.getCaretPosition();
        String text = a.getText();
        int start = caret;
        while (start > 0 && isPrefixChar(text.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        Completion.ReplaceRange rs = c.replaceRange();
        if (rs != null) {
            // The server told us exactly what to replace (LSP textEdit.range) — honor it over the identifier
            // walk, both ends. The start never passes the caret (chars typed since the request are absorbed);
            // the end only ever extends past it, which is how a server says it is rewriting something already
            // there — jdtls covers the `;` an import already has, and stopping at the caret would leave it
            // behind beside the one its insert ends with (`import java.util.*;;`).
            try {
                int lineEnd = caret;
                while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
                    lineEnd++;
                }
                int serverEnd = rs.hasEnd() ? host.lspOffset(a, rs.endLine(), rs.endCharacter()) : -1;
                int[] range = CompletionEngine.replacedRange(
                        caret, host.lspOffset(a, rs.line(), rs.character()), serverEnd, lineEnd);
                start = range[0];
                end = range[1];
            } catch (RuntimeException ignored) {
                // Range no longer valid (document moved) — fall back to the identifier walk above.
            }
        } else if (start == caret && caret > 0 && c.snippet() == null) {
            // The identifier walk captured nothing: the char before the caret is a non-identifier trigger
            // (e.g. phpactor's `$`, a bash variable sigil). If the insert begins with that overlap, extend the
            // replaced range back over it so accepting `$user` after typing `$` yields `$user`, not `$$user`.
            int overlap = CompletionEngine.prefixOverlap(text.substring(0, caret), c.insert());
            start = caret - overlap;
        }
        hideCompletion();
        // Measured around our own edit so a later completionItem/resolve's additionalTextEdits — positions the
        // server computed against the document as it is right here, before the accept — can be translated onto
        // the document the accept leaves behind (#410). Taken before the edit; completed just after it.
        var preStart = host.lspPosition(a, start);
        var preEnd = host.lspPosition(a, end);
        int preLength = text.length();
        if (c.snippet() != null) {
            host.startSnippet(a, c.snippet(), start, end);
        } else {
            a.replaceText(start, end, c.insert());
        }
        // Where the replaced range's end landed. Derived from the net length change rather than the accepted
        // text, so it holds for the snippet path too (its expansion re-indents and adds tab stops).
        var postEnd = host.lspPosition(a, end + (a.getLength() - preLength));
        pendingCompletionShift =
                new LspEditShift.Change(preStart[0], preStart[1], preEnd[0], preEnd[1], postEnd[0], postEnd[1]);
        // Stamped AFTER the edit (which bumps docVersion), so the auto-trigger that edit schedules is the
        // one suppressed — the user typing on afterwards bumps the version again and re-enables it.
        suppressCompletionAtVersion = host.docVersion();
        if (c.onAccept() != null) {
            c.onAccept().run(); // e.g. resolve + apply a TypeScript auto-import's additionalTextEdits
        }
        a.requestFocus();
    }

    void hideCompletion() {
        hidePopup();
        hideGhost();
    }
}
