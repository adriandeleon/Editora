package com.editora.editor;

import java.time.Duration;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;

/**
 * A transparent overlay that draws red wavy underlines beneath misspelled words, on top of the editor
 * without touching the document or its style spans (so it never fights the syntax highlighter).
 *
 * <p>Modeled on {@link WhitespaceOverlay}: a mouse-transparent {@link Canvas} sized to the viewport,
 * redrawn coalesced (one per pulse) on scroll / edit / resize / fold, and only for the currently visible
 * paragraphs. Word eligibility: in prose buffers every word is checked (except Markdown inline/fenced
 * {@code code}); in code buffers only words styled {@code comment} or {@code string}.
 */
final class SpellCheckOverlay extends Region {

    private static final Color SQUIGGLE = Color.web("#e5484d");
    private static final double AMP = 1.6; // squiggle peak-to-baseline amplitude (px)
    private static final double STEP = 2.0; // half-wavelength (px)

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private SpellChecker checker;
    private boolean proseMode;
    private boolean markdown;
    private boolean active;
    private boolean redrawPending;
    // Whether the last redraw actually had visible misspellings to paint. When false the canvas is shrunk
    // to 1x1 to release its (viewport-sized) RTTexture — a spell-checked buffer with no visible squiggles
    // shouldn't pin a full-viewport texture (the convention every other overlay follows). layoutChildren
    // only re-grows the canvas while this is set, so a clean viewport never flaps between sizes on scroll.
    private boolean hasContent;

    /** Lines (0-based) inside a Markdown fenced ``` code block — never spell-checked. Their content is
     *  the embedded language (or unstyled), so the per-char "code" style class can't catch them; this
     *  whole-line skip does. Recomputed off the debounced edit pulse, not per scroll. */
    private BitSet codeLines = new BitSet();

    // Both wordSpans(line) and isMisspelled(word) are otherwise recomputed for every visible word on
    // every scroll/resize pulse, even though the text hasn't changed — and isMisspelled is a Hunspell
    // FST lookup. Memoize both (bounded LRU, FX-thread-only). wordSpans is a pure function of the line
    // text, so its cache never needs invalidation; the misspelled cache depends on the dictionary +
    // ignore set, so it is cleared whenever those change (setChecker / refresh). Eligibility (syntax
    // style) is still evaluated fresh per draw, so a re-highlight is reflected immediately.
    private final java.util.Map<String, List<int[]>> spanCache = lru(2000);
    private final java.util.Map<String, Boolean> spellCache = lru(20_000);

    private static <K, V> java.util.Map<K, V> lru(int max) {
        return new java.util.LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    SpellCheckOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("spellcheck-overlay");
        setMouseTransparent(true);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        // Recompute fenced-code line ranges off the debounced edit pulse (O(lines), not per keystroke);
        // only while active, so an inactive overlay (e.g. a large file with spell check off) does no work.
        area.multiPlainChanges().successionEnds(Duration.ofMillis(300)).subscribe(ignore -> {
            if (active) {
                recomputeCodeLines();
            }
        });
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** Whether this is a Markdown buffer (enables fenced-code-block skipping). */
    void setMarkdown(boolean markdown) {
        this.markdown = markdown;
        recomputeCodeLines();
        scheduleRedraw();
    }

    private void recomputeCodeLines() {
        codeLines = markdown ? fencedCodeLines(area.getText()) : new BitSet();
    }

    /** 0-based line indices inside Markdown fenced code blocks (the ``` delimiter lines included). Pure. */
    static BitSet fencedCodeLines(String text) {
        BitSet code = new BitSet();
        if (text == null || text.isEmpty()) {
            return code;
        }
        String[] lines = text.split("\n", -1);
        int fenceStart = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (fenceStart < 0) {
                    fenceStart = i;
                } else {
                    code.set(fenceStart, i + 1); // mark the whole fence, delimiters included
                    fenceStart = -1;
                }
            }
        }
        if (fenceStart >= 0) {
            code.set(fenceStart, lines.length); // an unterminated fence runs to EOF (as editors render it)
        }
        return code;
    }

    void setChecker(SpellChecker checker) {
        this.checker = checker;
        spellCache.clear(); // a different dictionary → re-evaluate misspellings
        scheduleRedraw();
    }

    /** Whether this buffer is prose (check all words) vs code (only comments/strings). */
    void setProseMode(boolean prose) {
        this.proseMode = prose;
        scheduleRedraw();
    }

    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        setVisible(active);
        if (active) {
            recomputeCodeLines(); // pick up the current text (it may have changed while inactive)
            scheduleRedraw();
        } else {
            releaseCanvas(); // off → drop the texture, not just clear it
        }
    }

    boolean isActive() {
        return active;
    }

    /** Re-runs the check + redraw (e.g. after a dictionary finishes loading or the language changes). */
    void refresh() {
        spellCache.clear(); // dictionary loaded / language / user-word / ignore-set changed
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        // Only track the viewport size while there's something to paint; an idle overlay stays 1x1 (see
        // hasContent). redraw() grows the canvas on demand when it finds visible misspellings.
        if (hasContent) {
            double w = CanvasGuards.clampDim(getWidth());
            double h = CanvasGuards.clampDim(getHeight());
            if (canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas.setWidth(w);
                canvas.setHeight(h);
            }
        }
        scheduleRedraw();
    }

    private void scheduleRedraw() {
        if (!active || redrawPending) {
            return;
        }
        redrawPending = true;
        Platform.runLater(() -> {
            redrawPending = false;
            redraw();
        });
    }

    /** Grows the canvas to the viewport (only when there's content to paint) and clears it. */
    private void ensureCanvasSized() {
        double w = CanvasGuards.clampDim(getWidth());
        double h = CanvasGuards.clampDim(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w); // resizing a Canvas also clears it
            canvas.setHeight(h);
        }
        canvas.relocate(0, 0);
        hasContent = true;
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    /** Shrinks the canvas to 1x1, releasing its RTTexture (nothing visible to draw / overlay off). */
    private void releaseCanvas() {
        hasContent = false;
        if (canvas.getWidth() != 1 || canvas.getHeight() != 1) {
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
    }

    private void redraw() {
        if (!active || checker == null || !checker.ready() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            releaseCanvas();
            return;
        }
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                releaseCanvas();
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            // Phase 1 — collect the visible misspelled spans (the cheap, cached scan; for a clean viewport
            // this makes zero getCharacterBoundsOnScreen calls, exactly as before).
            List<int[]> hits = new java.util.ArrayList<>();
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p) || (markdown && codeLines.get(p))) {
                    continue; // skip folded lines and Markdown fenced code blocks
                }
                collectParagraph(p, hits);
            }
            if (hits.isEmpty()) {
                releaseCanvas(); // no visible squiggles → drop the viewport texture
                return;
            }
            // Phase 2 — size the canvas to the viewport and paint the collected spans.
            ensureCanvasSized();
            GraphicsContext g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            g.setStroke(SQUIGGLE);
            g.setLineWidth(1.0);
            for (int[] hit : hits) {
                Bounds b =
                        toLocal(area.getCharacterBoundsOnScreen(hit[0], hit[1]).orElse(null));
                if (b == null) {
                    continue;
                }
                if (b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
                    continue; // off-screen (horizontal scroll)
                }
                squiggle(g, b.getMinX(), b.getMaxX(), b.getMaxY() - 1);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    /** Adds each misspelled, eligible word in {@code paragraph} to {@code hits} as {@code {absStart, absEnd}}. */
    private void collectParagraph(int paragraph, List<int[]> hits) {
        String line = area.getParagraph(paragraph).getText();
        if (line.isEmpty()) {
            return;
        }
        // Absolute offset is the paragraph's base offset plus the column — hoist the base query out of the
        // per-word loop (it was called up to twice per word). getParagraph(p,0) is the paragraph's first char.
        int parBase = area.getAbsolutePosition(paragraph, 0);
        for (int[] span : spanCache.computeIfAbsent(line, SpellChecker::wordSpans)) {
            int start = span[0];
            int end = span[1];
            // Spell verdict first (cached per word) — so the per-word style lookup (eligible(), an area
            // query) runs only for the few misspelled words on screen, not every visible word. Reordering
            // is safe: eligibility still gates drawing, and a misspelled code token is still skipped; the
            // only difference is its (harmless) cache entry. This is the per-scroll-pulse hot path on prose.
            if (!spellCache.computeIfAbsent(line.substring(start, end), checker::isMisspelled)) {
                continue;
            }
            // Don't flag a "misspelled" run that is actually part of a URL, path, command, or
            // dotted/underscored identifier (checked only for the few flagged words, off the hot path).
            if (SpellChecker.partOfStructuredToken(line, start, end)) {
                continue;
            }
            int abs = parBase + start;
            if (!eligible(abs)) {
                continue; // eligibility is style-dependent (inline/fenced code) → evaluated fresh
            }
            hits.add(new int[] {abs, parBase + end});
        }
    }

    /** Whether the word at absolute offset {@code abs} should be checked, by its applied syntax style. */
    private boolean eligible(int abs) {
        Collection<String> style = area.getStyleOfChar(abs);
        if (proseMode) {
            // Skip Markdown inline `code` and links/URLs; check the rest (prose, headings, bold/italic).
            return !style.contains("code") && !style.contains("link");
        }
        return style.contains("comment") || style.contains("string"); // code: only comments/strings
    }

    private void squiggle(GraphicsContext g, double x0, double x1, double y) {
        g.beginPath();
        g.moveTo(x0, y);
        boolean up = true;
        for (double x = x0; x <= x1; x += STEP) {
            g.lineTo(Math.min(x + STEP, x1), up ? y - AMP : y);
            up = !up;
        }
        g.stroke();
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
