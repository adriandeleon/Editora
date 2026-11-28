package com.editora.editor;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;

import com.editora.editor.debug.InlineValuesOverlay;
import com.editora.editor.logviewer.LogHighlightOverlay;
import com.editora.editor.lsp.LspDiagnosticOverlay;
import com.editora.editor.lsp.LspEditPlacement;
import com.editora.editor.lsp.OccurrenceHighlightOverlay;
import com.editora.editor.markdown.MarkdownLintOverlay;
import com.editora.editor.markdown.MarkdownLintStripe;
import com.editora.editor.markdown.MermaidLintOverlay;
import com.editora.editor.minimap.Minimap;
import com.editora.editor.search.SearchHighlightOverlay;
import com.editora.editor.todo.TodoHighlightOverlay;
import com.editora.editor.todo.TodoStripe;
import com.editora.logviewer.LogLevel;
import com.editora.text.OccurrenceSpan;
import com.editora.text.SearchMatches;
import org.jspecify.annotations.Nullable;

/**
 * The overlays and scrollbar stripes drawn over a buffer's text that are built on first use (find matches,
 * occurrences, TODOs, indent guides, whitespace, lint, inline debug values, log levels, AceJump, the LSP
 * diagnostics), the anchoring and layering every overlay goes through, and the find and occurrence highlights fed
 * into them. Most buffers never turn these features on, so each overlay costs nothing until its feature does.
 *
 * <p>Moved out of {@code EditorBuffer} for size. The overlay fields stay on the buffer, where tests read them.
 */
final class BufferOverlays {

    private final EditorBuffer b;

    BufferOverlays(EditorBuffer b) {
        this.b = b;
    }

    /** A log's WARN/ERROR overview over the scrollbar, attached with the log overlay — see logStripe(). */
    com.editora.editor.logviewer.@Nullable LogStripe logStripe;

    DefinitionLinkOverlay definitionLinkOverlay() {
        if (b.definitionLinkOverlay == null) {
            b.definitionLinkOverlay =
                    attachLazyOverlay(new DefinitionLinkOverlay(b.area), OverlayLayer.DEFINITION_LINK);
        }
        return b.definitionLinkOverlay;
    }
    /** TEST SEAM — the Ctrl/Cmd-hover underline's overlay, null until a link has shown. */
    @Nullable
    DefinitionLinkOverlay definitionLinkOverlayForTest() {
        return b.definitionLinkOverlay;
    }
    /** Marks a node anchored by {@link #anchorOverText}, so {@link EditorBuffer#setMinimapVisible} can find them all. */
    static final String OVER_TEXT = SecondaryPane.OVER_TEXT;
    /**
     * Anchors {@code overlay} over the text rectangle — exactly the scroll pane's, whatever the minimap is
     * doing right now. Every overlay drawn on the text goes through here: one that kept the minimap's inset
     * while the minimap was hidden stopped 90 px short of the edge, clipping highlights and squiggles there.
     */
    void anchorOverText(Node overlay) {
        AnchorPane.setTopAnchor(overlay, 0d);
        AnchorPane.setBottomAnchor(overlay, 0d);
        AnchorPane.setLeftAnchor(overlay, 0d);
        AnchorPane.setRightAnchor(overlay, AnchorPane.getRightAnchor(b.scrollPane));
        overlay.getProperties().put(OVER_TEXT, Boolean.TRUE);
    }
    /**
     * Attaches {@code overlay} to the editor pane at {@code layer} (see {@link Layer}), with the standard
     * text-rect anchors. The split's second view, if there is one, gets its twin at once; a split opened
     * later finds it with the rest (see {@link SecondaryPane#follow}).
     */
    <T extends javafx.scene.layout.Region> T attachLazyOverlay(T overlay, int layer) {
        anchorOverText(overlay);
        return attachLazy(overlay, layer);
    }
    /** Attaches {@code stripe} docked at the right edge, top to bottom; the caller sets its right inset. */
    private <T extends javafx.scene.layout.Region> T attachLazyStripe(T stripe, int layer) {
        AnchorPane.setTopAnchor(stripe, 0d);
        AnchorPane.setBottomAnchor(stripe, 0d);
        AnchorPane.setRightAnchor(stripe, 0d);
        return attachLazy(stripe, layer);
    }

    private <T extends Node> T attachLazy(T node, int layer) {
        if (node instanceof TabSurface surface) {
            surface.setRenderingActive(b.renderingActive); // first activated while its tab is in the background
        }
        OverlayLayer.layered(node, layer);
        List<Node> children = b.root.getChildren();
        int idx = children.size();
        for (int i = 0; i < children.size(); i++) {
            if (OverlayLayer.of(children.get(i)) > layer) {
                idx = i;
                break;
            }
        }
        children.add(idx, node);
        if (b.pane2 != null) {
            b.pane2.follow(children); // the split's second view gets its twin
        }
        return node;
    }

    SearchHighlightOverlay searchOverlay() {
        if (b.searchOverlay == null) {
            b.searchOverlay = attachLazyOverlay(new SearchHighlightOverlay(b.area), OverlayLayer.SEARCH);
        }
        return b.searchOverlay;
    }

    OccurrenceHighlightOverlay occurrenceOverlay() {
        if (b.occurrenceOverlay == null) {
            b.occurrenceOverlay = attachLazyOverlay(new OccurrenceHighlightOverlay(b.area), OverlayLayer.OCCURRENCE);
        }
        return b.occurrenceOverlay;
    }

    TodoHighlightOverlay todoOverlay() {
        if (b.todoOverlay == null) {
            b.todoOverlay = attachLazyOverlay(new TodoHighlightOverlay(b.area), OverlayLayer.TODO);
        }
        return b.todoOverlay;
    }

    IndentGuideOverlay indentGuides() {
        if (b.indentGuides == null) {
            IndentGuideOverlay made = new IndentGuideOverlay(
                    b.area,
                    () -> "\t".equals(b.indentUnit())
                            ? b.tabSize
                            : b.indentUnit().length(),
                    () -> b.tabSize);
            if (b.fontApplied) {
                made.setFont(b.fontFamily, b.fontSize);
            }
            made.setSuppressed(b.largeFile);
            b.indentGuides = attachLazyOverlay(made, OverlayLayer.INDENT_GUIDE);
        }
        return b.indentGuides;
    }
    /** Shows or hides the indent guides (VS Code's vertical lines at each indentation level). */
    void setIndentGuidesVisible(boolean visible) {
        if (visible || b.indentGuides != null) { // turning them off on a buffer that never had them builds nothing
            indentGuides().setActive(visible);
        }
    }

    WhitespaceOverlay whitespace() {
        if (b.whitespace == null) {
            WhitespaceOverlay made = new WhitespaceOverlay(b.area);
            if (b.fontApplied) {
                made.setFont(b.fontFamily, b.fontSize);
            }
            made.setSuppressed(b.largeFile);
            b.whitespace = attachLazyOverlay(made, OverlayLayer.WHITESPACE);
        }
        return b.whitespace;
    }

    MarkdownLintOverlay mdLintOverlay() {
        if (b.mdLintOverlay == null) {
            b.mdLintOverlay = attachLazyOverlay(new MarkdownLintOverlay(b.area), OverlayLayer.MARKDOWN_LINT);
        }
        return b.mdLintOverlay;
    }

    InlineValuesOverlay inlineValues() {
        if (b.inlineValues == null) {
            InlineValuesOverlay made = new InlineValuesOverlay(b.area);
            if (b.fontApplied) {
                made.setFont(b.fontFamily, b.fontSize);
            }
            b.inlineValues = attachLazyOverlay(made, OverlayLayer.INLINE_VALUES);
        }
        return b.inlineValues;
    }

    DiagnosticStripe diagnosticStripe() {
        if (b.diagnosticStripe == null) {
            b.diagnosticStripe = attachLazyStripe(new DiagnosticStripe(b.area), OverlayLayer.DIAGNOSTIC_STRIPE);
            b.diagnosticStripe.setOnActivateIn(b.jumps::revealLine);
            // Its inset and on/off state, as if it had been there all along, and again when the UI zoom changes.
            UiZoom.follow(b.diagnosticStripe, b::updateDiagnosticStripe);
        }
        return b.diagnosticStripe;
    }

    MarkdownLintStripe mdLintStripe() {
        if (b.mdLintStripe == null) {
            b.mdLintStripe = attachLazyStripe(new MarkdownLintStripe(b.area), OverlayLayer.MARKDOWN_LINT_STRIPE);
            b.mdLintStripe.setOnActivateIn(b.jumps::revealLine);
            UiZoom.follow(b.mdLintStripe, b::updateMarkdownLintStripe); // its inset and on/off state, as above
        }
        return b.mdLintStripe;
    }

    TodoStripe todoStripe() {
        if (b.todoStripe == null) {
            b.todoStripe = attachLazyStripe(new TodoStripe(b.area), OverlayLayer.TODO_STRIPE);
            b.todoStripe.setOnActivateIn(b.jumps::revealLine);
            UiZoom.follow(b.todoStripe, b::updateTodoStripe); // its inset and on/off state, as above
        }
        return b.todoStripe;
    }

    LogHighlightOverlay logOverlay() {
        if (b.logOverlay == null) {
            b.logOverlay = attachLazyOverlay(
                    new LogHighlightOverlay(b.area, new LogHighlightOverlay.LevelSource() {
                        @Override
                        public boolean filtered() {
                            return b.logView.filtered();
                        }

                        @Override
                        public @Nullable LogLevel levelAt(int paragraph) {
                            return b.logView.levelAt(paragraph);
                        }

                        @Override
                        public java.util.regex.@Nullable Pattern highlight() {
                            return b.logView.patterns().include();
                        }

                        @Override
                        public int lineAt(int paragraph) {
                            return b.logView.filtered() ? b.logView.lineNumberAt(paragraph) - 1 : paragraph;
                        }
                    }),
                    OverlayLayer.LOG);
        }
        return b.logOverlay;
    }

    com.editora.editor.logviewer.LogStripe logStripe() {
        if (logStripe == null) {
            logStripe = attachLazyStripe(new com.editora.editor.logviewer.LogStripe(b.area), OverlayLayer.LOG_STRIPE);
            logStripe.setOnActivateIn(b.jumps::revealLine);
            UiZoom.follow(logStripe, b::updateMarkdownLintStripe); // its inset, and the TODO stripe's beside it
        }
        return logStripe;
    }
    /** The log overview stripe, or null while this buffer has never been shown as a log (tests). */
    com.editora.editor.logviewer.@Nullable LogStripe logStripeIfAttached() {
        return logStripe;
    }

    MermaidLintOverlay lintOverlay() {
        if (b.lintOverlay == null) {
            b.lintOverlay = attachLazyOverlay(new MermaidLintOverlay(b.area), OverlayLayer.MERMAID_LINT);
        }
        return b.lintOverlay;
    }

    LspDiagnosticOverlay lspOverlay() {
        if (b.lspOverlay == null) {
            b.lspOverlay = attachLazyOverlay(new LspDiagnosticOverlay(b.area), OverlayLayer.LSP);
        }
        return b.lspOverlay;
    }

    AceJumpOverlay aceJump() {
        if (b.aceJump == null) {
            b.aceJump = attachLazyOverlay(new AceJumpOverlay(b.area), OverlayLayer.ACE_JUMP);
        }
        return b.aceJump;
    }
    /**
     * Highlights all find matches ({@code [start,end)} offset pairs) behind the text, emphasizing the
     * match at {@code activeIndex}. No-op in large-file mode (the find bar still selects the current
     * match). Pass an empty result (or call {@link #clearSearchMatches}) to remove the highlight.
     */
    void setSearchMatches(SearchMatches matches, int activeIndex) {
        if (b.largeFile) {
            return;
        }
        searchOverlay().setMatches(matches, activeIndex);
    }
    /** Clears the find-match highlight overlay. */
    void clearSearchMatches() {
        if (b.searchOverlay != null) {
            b.searchOverlay.setMatches(SearchMatches.EMPTY, -1);
        }
    }
    /**
     * Washes every occurrence of the symbol under the caret (LSP document highlight, #675) — Read vs Write
     * shaded differently. Positions are converted to offsets against the document as it is NOW, so the
     * caller drops a response whose {@code docVersion} moved. Empty clears + releases the overlay texture.
     */
    void setOccurrenceSpans(java.util.List<OccurrenceSpan> spans) {
        if (b.largeFile || spans == null || spans.isEmpty()) {
            clearOccurrenceSpans();
            return;
        }
        java.util.List<int[]> triples = new java.util.ArrayList<>(spans.size());
        for (OccurrenceSpan s : spans) {
            int from = LspEditPlacement.offset(b.area, s.startLine(), s.startCol());
            int to = LspEditPlacement.offset(b.area, s.endLine(), s.endCol());
            if (to > from) {
                triples.add(new int[] {from, to, s.write() ? 1 : 0});
            }
        }
        occurrenceOverlay().setSpans(triples);
    }
    /** Clears the occurrence wash (no symbol under the caret / LSP off / buffer deactivated). */
    void clearOccurrenceSpans() {
        if (b.occurrenceOverlay != null) {
            b.occurrenceOverlay.setSpans(java.util.List.of());
        }
    }
    /** Starts AceJump: the next typed character labels its visible occurrences to jump the caret. */
    void startAceJump() {
        aceJump().start();
    }
    /** Starts AceJump line-mode: every visible line is labeled at once; type a label to jump to that line
     *  (its first non-whitespace character). */
    void startAceJumpLine() {
        aceJump().startLine();
    }

    /** The scrollbar stripe is shown whenever LSP is active for this buffer (minimap on or off). It sits
     *  over the editor's vertical scrollbar: at the far-right edge when the minimap is hidden, else just
     *  inside the minimap (over the editor scrollbar, which ends at the minimap's left edge). */
    void updateDiagnosticStripe() {
        boolean minimapShown = b.minimapVisible && !b.largeFile && !b.heavyFile;
        if (b.lspActive || b.diagnosticStripe != null) {
            DiagnosticStripe stripe = b.diagnosticStripe();
            AnchorPane.setRightAnchor(stripe, minimapShown ? Minimap.WIDTH : 0d);
            stripe.setActive(b.lspActive);
        }
        updateMarkdownLintStripe();
    }
    /** Positions the Markdown-lint overview stripe beside the diagnostic stripe (shifted left by its width
     *  when LSP is also active — never the case for a Markdown buffer, but kept general). */
    void updateMarkdownLintStripe() {
        boolean minimapShown = b.minimapVisible && !b.largeFile && !b.heavyFile;
        double base = minimapShown ? Minimap.WIDTH : 0d;
        boolean on = b.markdownLintEnabled && !b.largeFile && !minimapShown; // the minimap carries the same marks
        if (on || b.mdLintStripe != null) {
            MarkdownLintStripe stripe = b.mdLintStripe();
            AnchorPane.setRightAnchor(stripe, base + (b.lspActive ? UiZoom.px(DiagnosticStripe.WIDTH) : 0d));
            stripe.setActive(on);
        }
        if (b.overlays.logStripe != null) { // a log is never Markdown: the two share a slot
            AnchorPane.setRightAnchor(
                    b.overlays.logStripe, base + (b.lspActive ? UiZoom.px(DiagnosticStripe.WIDTH) : 0d));
        }
        updateTodoStripe();
    }
    /** Positions the TODO overview stripe over the scrollbar: at the edge (inside the minimap when shown),
     *  shifted left by the diagnostic + lint stripes' widths when those are active, so they sit side by side. */
    void updateTodoStripe() {
        boolean minimapShown = b.minimapVisible && !b.largeFile && !b.heavyFile;
        double base = minimapShown ? Minimap.WIDTH : 0d;
        double offset = UiZoom.px((b.lspActive ? DiagnosticStripe.WIDTH : 0d)
                + (b.markdownLintEnabled && !b.largeFile && !minimapShown ? MarkdownLintStripe.WIDTH : 0d)
                + (b.overlays.logStripe != null && b.overlays.logStripe.isActive()
                        ? com.editora.editor.logviewer.LogStripe.WIDTH
                        : 0d));
        if (b.todoStripe != null) { // attached with the first marks (todoStripe()), positioned from then on
            AnchorPane.setRightAnchor(b.todoStripe, base + offset);
            b.todoStripe.setActive(b.todoMarker.todoEnabled && !b.largeFile);
        }
    }
    /** While suspended: the frame's variable name → value map painted as grey end-of-line annotations on the
     *  visible lines of the function stopped at {@code frameLine}; null/empty clears (resume/terminate). */
    void setInlineValues(java.util.@Nullable Map<String, String> values, int frameLine) {
        java.util.Map<String, String> shown = b.hugeFile ? null : values;
        if ((shown != null && !shown.isEmpty()) || b.inlineValues != null) {
            // In a .http file only its script lines are the script's; a request body's "user" is not that variable.
            com.editora.http.HttpScriptLines.Map script = b.isHttpFile() ? b.httpScriptMap() : null;
            if (b.isHttpFile() && script == null) {
                b.whenHttpScriptMap(() -> setInlineValues(values, frameLine)); // its script lines, parsed off FX
                return;
            }
            b.inlineValues().setValues(shown, frameLine, script == null ? null : script::isScriptLine);
        }
    }
    /**
     * While suspended: the pinned values to paint, by line ({@code expr = value} text), after the line's own inline
     * values; null or empty clears them.
     */
    void setPinnedValues(java.util.@Nullable Map<Integer, String> shown) {
        java.util.Map<Integer, String> pinned = b.hugeFile ? null : shown;
        if ((pinned != null && !pinned.isEmpty()) || b.inlineValues != null) {
            b.inlineValues().setPinned(pinned);
        }
    }
    /**
     * How long the last step or Continue ran (Visual Studio's PerfTip), painted faintly after 0-based {@code line}, the
     * one the program stopped on; null clears it.
     */
    void setStepTime(int line, @Nullable String text) {
        String shown = b.hugeFile ? null : text;
        if (shown != null || b.inlineValues != null) {
            b.inlineValues().setElapsed(line, shown);
        }
    }
}
