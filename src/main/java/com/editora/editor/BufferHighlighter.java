package com.editora.editor;

import java.util.Collection;
import java.util.List;

import javafx.application.Platform;

import com.editora.editor.highlight.CsvRainbow;
import com.editora.editor.highlight.HighlightDirty;
import com.editora.editor.highlight.HighlightPass;
import com.editora.editor.markdown.ScrollAnchor;
import com.editora.text.SemanticToken;
import org.fxmisc.richtext.model.StyleSpans;
import org.jspecify.annotations.Nullable;

/**
 * A buffer's syntax highlighting: the incremental TextMate pass over the range edited since the last one
 * (tokenized on {@link EditorBuffer#HIGHLIGHT_POOL}, applied on the FX thread only if no newer pass or edit
 * superseded it), the language server's semantic tokens overlaid on it, rainbow CSV columns, bracket-pair colours,
 * and holding the pass back while the tab is not in the scene.
 */
final class BufferHighlighter {

    private final EditorBuffer b;

    BufferHighlighter(EditorBuffer b) {
        this.b = b;
    }

    /**
     * While set, {@link #applyHighlighting()} records that a pass is owed instead of dispatching it (see
     * {@link #setHighlightDeferred}). The owed range stays in {@code highlightDirty}, so the pass that runs
     * when the deferral ends covers everything that changed meanwhile.
     */
    private boolean highlightDeferred;
    /** A highlight pass was requested while {@link #highlightDeferred}; run it when the deferral ends. */
    private boolean highlightOwed;
    /** LSP semantic highlighting active for this buffer (server supports it + the feature is on). */
    boolean semanticActive;
    /** Latest server semantic tokens (absolute positions), overlaid onto the TextMate highlight. */
    private java.util.List<SemanticToken> semanticTokens = java.util.List.of();
    /** True once the document changed after {@link #semanticTokens} were captured — suppresses the overlay
     *  (so we never mis-color shifted text) until a fresh response lands via {@link #setSemanticTokens}. */
    boolean semanticStale;
    /** Bumped on every edit and read when a semantic-tokens request is issued, so a response computed against
     *  an older document is dropped instead of re-anchoring stale tokens onto the current text (see
     *  {@link #semanticGen()} / {@link #setSemanticTokens(java.util.List, long)}). */
    long semanticGen;
    /** Bumped on every highlight request (FX thread only); lets background results discard if stale. Volatile:
     *  read per line by the background tokenize's cancel check. */
    volatile long highlightGen;
    /** Per-line grammar end-states and bracket depths of the last applied pass (see {@link HighlightPass});
     *  null = none, so the next pass tokenizes the whole document. Replaced, never modified. */
    HighlightPass.@Nullable Lines highlightLines;
    /** The range the next highlight pass owes: every edit since a pass last applied. */
    final HighlightDirty highlightDirty = new HighlightDirty();
    /** Where {@link #semanticTokens} are no longer painted (edits, lexical restyles) since they were anchored. */
    final HighlightDirty semanticDirty = new HighlightDirty();
    /** Line count of the text {@link #semanticTokens} were anchored to. */
    private int semanticLines;
    /** A grammar or CSV pass may have styled the document, so losing the grammar leaves styles to clear. */
    private boolean styled;
    /** Turns LSP semantic highlighting on/off for this buffer (server supports it + feature enabled). Off in
     *  large-file mode, like the diagnostics overlay. Re-applies the highlight so the overlay appears/clears. */
    void setSemanticActive(boolean on) {
        boolean next = on && !b.largeFile;
        if (next == semanticActive) {
            return;
        }
        semanticActive = next;
        if (!semanticActive) {
            semanticTokens = java.util.List.of();
            semanticStale = false;
        }
        semanticDirty.applied();
        invalidateHighlighting(); // force a full re-tokenize so the overlay is added/removed everywhere
        applyHighlighting();
    }

    boolean isSemanticActive() {
        return semanticActive;
    }

    /** The generation to capture when issuing a semantic-tokens request; pass it back to
     *  {@link #setSemanticTokens(java.util.List, long)} so a response for an older document is dropped. */
    long semanticGen() {
        return semanticGen;
    }

    /** Pushes fresh server semantic tokens (absolute positions) and re-applies so they overlay the
     *  TextMate highlight immediately. No-op when semantic highlighting is off for this buffer. */
    void setSemanticTokens(java.util.List<SemanticToken> tokens) {
        setSemanticTokens(tokens, semanticGen);
    }

    /**
     * Applies a semantic-tokens response only if the document hasn't changed since the request was issued
     * ({@code requestGen == } the current {@link #semanticGen()}). A stale response — the server computed it
     * against an older version, or an older request's reply arrives after a newer one — is <b>dropped</b>,
     * leaving {@link #semanticStale} set so the overlay stays suppressed rather than re-anchoring the old
     * tokens onto the shifted text (which mis-colored characters/lines until the next response). A response
     * identical to the tokens already shown restyles nothing; a different one restyles the lines that differ.
     */
    void setSemanticTokens(java.util.List<SemanticToken> tokens, long requestGen) {
        if (!semanticActive || requestGen != semanticGen) {
            return;
        }
        java.util.List<SemanticToken> next = tokens == null ? java.util.List.of() : tokens;
        int lines = b.area.getParagraphs().size();
        int erasedFirst = semanticStale ? lineOfOffset(semanticDirty.start()) : -1;
        int[] range = SemanticToken.changedLines(
                semanticTokens, next, erasedFirst, lineOfOffset(semanticDirty.end()), lines - semanticLines);
        if (range != null || semanticStale) {
            semanticTokens = next;
        }
        semanticStale = false; // anchored to the current (unchanged since request) text → safe to overlay
        semanticDirty.applied();
        semanticLines = lines;
        if (range != null) { // else the same tokens over the same text: nothing to restyle
            // Style-only: the pass re-tokenizes just these lines, from the stored end-state above them.
            int last = Math.min(range[1], lines - 1);
            highlightDirty.include(
                    b.area.getAbsolutePosition(Math.min(range[0], last), 0),
                    b.area.getAbsolutePosition(last, b.area.getParagraphLength(last)));
            applyHighlighting();
        }
    }

    private int lineOfOffset(int offset) {
        return b.area.offsetToPosition(
                        Math.max(0, Math.min(offset, b.area.getLength())),
                        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
    }

    /** The semantic tokens a pass dispatched now should overlay, or null (off, stale, or none). */
    private java.util.@Nullable List<SemanticToken> semanticOverlay() {
        return semanticActive && !semanticStale && !semanticTokens.isEmpty() ? semanticTokens : null;
    }

    /** Per-column "rainbow" coloring for CSV/TSV buffers (replaces the source.csv grammar highlighting). */
    private boolean csvRainbow;

    /** Enables/disables rainbow per-column CSV coloring; re-highlights the whole buffer on a change. */
    void setCsvRainbowEnabled(boolean on) {
        boolean want = on && b.isCsv();
        if (want != csvRainbow) {
            csvRainbow = want;
            invalidateHighlighting();
            applyHighlighting();
        }
    }

    /** Tint each bracket by nesting depth (see {@code BracketColors}). */
    private boolean bracketColors;

    /**
     * Enables/disables bracket-pair colorization. Re-highlights the whole buffer on a change: the colours
     * ride the token spans, so an incremental pass would leave the untouched prefix coloured as it was.
     */
    void setBracketColorsEnabled(boolean on) {
        if (on != bracketColors) {
            bracketColors = on;
            invalidateHighlighting();
            applyHighlighting();
        }
    }

    /**
     * Holds the syntax highlighter back while this buffer's editor is not in the scene.
     *
     * <p>A restored background tab is filled while its editor is detached ({@code DeferredTabContent}); the
     * load marks the whole document dirty, and without this the tab would be tokenised on the highlight
     * pool and restyled on the FX thread a pause later — for every restored tab, shortly after its fill,
     * while the user is looking at a different file. Deferred, the first pass runs when the tab is first
     * shown instead, which is when its styles are first visible. Every other document consumer (folds, the
     * caret, LSP sync, the snapshot) is unaffected: only the style pass waits.
     *
     * <p>Ending the deferral runs the owed pass at once, if one was requested; a buffer that was never asked
     * to highlight while deferred dispatches nothing.
     */
    void setHighlightDeferred(boolean deferred) {
        if (highlightDeferred == deferred) {
            return;
        }
        highlightDeferred = deferred;
        if (!deferred && highlightOwed) {
            highlightOwed = false;
            applyHighlighting();
        }
    }

    /** True while {@link #setHighlightDeferred} is holding the highlighter back. */
    boolean isHighlightDeferred() {
        return highlightDeferred;
    }

    /** Forces the next {@link #applyHighlighting()} to re-tokenize the whole document (e.g. after a
     *  language/grammar change, where no text change occurred). */
    void invalidateHighlighting() {
        highlightDirty.invalidate();
        highlightLines = null;
    }

    /** Forgets the per-line tokenizer state and the structure symbols: no grammar pass owns the document. */
    private void dropHighlightState() {
        highlightLines = null;
        if (!b.symbols.isEmpty()) {
            b.symbols = List.of();
            b.onSymbolsChanged.run();
        }
    }

    /** Applies per-column rainbow CSV coloring over the whole document. The scan and span construction run
     *  off the FX thread; only the guarded style application returns to it. */
    private void applyCsvRainbow() {
        dropHighlightState();
        String text = b.documentTextSnapshot();
        int len = text.length();
        if (len == 0) {
            return;
        }
        styled = true;
        long version = b.docVersion;
        long gen = ++highlightGen;
        EditorBuffer.HIGHLIGHT_POOL.execute(() -> {
            char delim = com.editora.csv.CsvParser.detectDelimiter(text);
            StyleSpans<Collection<String>> spans = CsvRainbow.buildSpans(text, delim, CsvRainbow.COLORS);
            Platform.runLater(() -> {
                if (gen != highlightGen || version != b.docVersion || spans.length() != b.area.getLength()) {
                    return;
                }
                ScrollAnchor.setStyleSpans(b.area, 0, spans);
                b.scheduleBraceMatch();
            });
        });
    }

    void applyHighlighting() {
        if (b.disposed) {
            return; // a closed buffer must not dispatch new passes (see the disposed field)
        }
        if (b.largeFile) {
            dropHighlightState(); // large-file mode: leave the document as plain text
            return;
        }
        if (highlightDeferred) {
            highlightOwed = true; // the dirty range is kept; setHighlightDeferred(false) dispatches the pass
            return;
        }
        if (csvRainbow && b.isCsv() && !b.heavyFile) {
            applyCsvRainbow();
            return;
        }
        if (b.grammar == null) {
            // No grammar for this file type, so nothing to compute — and nothing to clear either, except
            // once when a grammar or CSV pass had styled the document, and otherwise in the text typed
            // since the last pause where it took on the style of its neighbour (a matched brace). Clearing
            // the whole document at every pause restyled it for nothing and wiped the brace match.
            dropHighlightState();
            int length = b.area.getLength();
            int from = styled ? 0 : highlightDirty.isClean() ? length : Math.min(highlightDirty.start(), length);
            int to = styled ? length : Math.min(highlightDirty.end(), length);
            int[] matched = styled ? null : b.completionActions.braceMatch(); // the live pair is not a leftover
            if (styled) {
                styled = false;
                highlightGen++; // a pass still in flight must not style the document after this
            }
            highlightDirty.applied();
            if (from < to && HighlightPass.anyStyled(b.area.getStyleSpans(from, to), from, matched)) {
                b.area.clearStyle(from, to);
                b.scheduleBraceMatch();
            }
            return;
        }
        // Tokenizing is O(lines), so a pass covers only the range edited since the last one applied and
        // stops where the grammar state re-converges (see HighlightPass). The work runs on a background
        // thread — tokenizing, the semantic and bracket overlays, the spliced per-line state — and the
        // result is applied only if no newer pass was dispatched and the text is still the snapshot's.
        styled = true;
        java.util.List<SemanticToken> overlay = semanticOverlay();
        long version = b.docVersion;
        long gen = ++highlightGen;
        HighlightPass.Request request = new HighlightPass.Request(
                        b.documentTextSnapshot(),
                        b.grammar,
                        highlightLines,
                        b.symbols,
                        highlightDirty.isClean() ? -1 : highlightDirty.start(),
                        highlightDirty.end(),
                        bracketColors,
                        overlay)
                .withPreview( // a long file's screen is coloured before the rest is tokenized (review 2 E105)
                        Math.max(ScrollAnchor.firstVisibleLine(b.area), b.area.getCurrentParagraph()) + 150,
                        spans -> Platform.runLater(() -> {
                            if (gen == highlightGen && version == b.docVersion) {
                                ScrollAnchor.setStyleSpans(b.area, 0, spans);
                            }
                        }));
        HighlightPass.submit(EditorBuffer.HIGHLIGHT_POOL, b.grammar, () -> {
            HighlightPass.Result r;
            try {
                // The cancel check lets a superseded pass stop at the next line instead of finishing a
                // doomed multi-MB tokenize while holding the shared grammar monitor (see analyzeFrom).
                r = HighlightPass.run(request, () -> gen != highlightGen);
            } catch (Exception | LinkageError e) {
                return; // never let a grammar/engine fault kill the highlighter thread
            }
            if (r == null) {
                return;
            }
            Platform.runLater(() -> {
                // Superseded, or the text changed since the snapshot: what this pass owed stays in
                // highlightDirty, so the next one covers it.
                if (gen != highlightGen || version != b.docVersion) {
                    return;
                }
                highlightDirty.applied();
                highlightLines = r.lines();
                if (r.spans() != null) {
                    ScrollAnchor.setStyleSpans(b.area, r.fromOffset(), r.spans());
                    b.scheduleBraceMatch(); // re-apply the match highlight the spans just overwrote
                }
                if (r.symbolsChanged()) {
                    b.symbols = r.symbols();
                    b.onSymbolsChanged.run();
                }
                if (semanticActive && semanticStale) {
                    semanticDirty.include(r.fromOffset(), r.fromOffset() + r.length()); // overlay gone here
                } else if (overlay != semanticOverlay() && r.spans() != null) {
                    // Tokens arrived while this pass ran without them: restyle its range with the overlay.
                    highlightDirty.include(r.fromOffset(), r.fromOffset() + r.length());
                    applyHighlighting();
                }
            });
        });
    }
}
