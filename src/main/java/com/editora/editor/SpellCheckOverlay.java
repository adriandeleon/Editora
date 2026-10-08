package com.editora.editor;

import java.time.Duration;
import java.util.BitSet;
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
 * paragraphs. Which words are eligible is the buffer's {@link SpellMode}: every word of prose (except
 * Markdown inline/fenced {@code code}), the text content of HTML and Typst, and in code only words styled
 * {@code comment} or {@code string}.
 */
final class SpellCheckOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    private static final Color SQUIGGLE = Color.web("#e5484d");
    private static final double AMP = 1.6; // squiggle peak-to-baseline amplitude (px)
    private static final double STEP = 2.0; // half-wavelength (px)

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private SpellChecker checker;
    private SpellMode mode = SpellMode.PROSE;
    /** Whether {@link #mode} is Markdown — kept as a field because the fence-skip tests read it. */
    private boolean markdown;

    private boolean active;
    /**
     * False while this overlay's tab is in the background. A backgrounded tab keeps a full
     * viewport-sized Canvas (and its RTTexture) alive for nothing: the existing 1x1 release only fires
     * when the FEATURE is off or there is nothing to draw, never when the tab is merely not visible.
     * Measured at ~2.4 MB retained per open tab across the overlays. Driven by
     * {@code EditorBuffer.setRenderingActive}, the same hook the minimap already uses.
     */
    private boolean rendering = true;

    private boolean redrawPending;
    private double laidOutWidth = -1;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private SpellCheckOverlay follower;
    // Whether the last redraw actually had visible misspellings to paint. When false the canvas is shrunk
    // to 1x1 to release its (viewport-sized) RTTexture — a spell-checked buffer with no visible squiggles
    // shouldn't pin a full-viewport texture (the convention every other overlay follows). layoutChildren
    // only re-grows the canvas while this is set, so a clean viewport never flaps between sizes on scroll.
    private boolean hasContent;

    /** Lines (0-based) that are code as a whole — inside a Markdown fenced ``` block, an HTML script or
     *  style block, a Typst instruction — and never spell-checked. Their content is the embedded language
     *  (or unstyled), so the per-char style class can't catch them; this whole-line skip does. Recomputed
     *  off the debounced edit pulse, not per scroll. */
    private BitSet codeLines = new BitSet();

    // Both wordSpans(line) and isMisspelled(word) are otherwise recomputed for every visible word on
    // every scroll/resize pulse, even though the text hasn't changed — and isMisspelled is a Hunspell
    // FST lookup. Memoize both (bounded LRU, FX-thread-only). wordSpans is a pure function of the line
    // text, so its cache never needs invalidation; the misspelled cache depends on the dictionary +
    // ignore set, so it is cleared whenever those change (setChecker / refresh). Eligibility (syntax
    // style) is still evaluated fresh per draw, so a re-highlight is reflected immediately.
    //
    // The line cache holds the finished answer for a line's text — the spans of its words that are
    // misspelled and not part of a structured token — so a repaint of an unchanged line is one lookup
    // rather than a substring and a lookup per word. It is derived from the word verdicts, so it is cleared
    // with them.
    private final java.util.Map<String, int[][]> flaggedCache = lru(2000);
    private final java.util.Map<String, Boolean> spellCache = lru(20_000);

    private static final int[][] NONE = new int[0][];

    /**
     * A line longer than this is not checked: it is minified or generated data, not a paragraph, and each
     * squiggle on it costs a layout query that grows with the line (about 0.8 ms per word at 60 KB).
     */
    static final int MAX_LINE = 16_384;

    /**
     * The most squiggles one line gets. A line with more is in another language or is not prose, and every
     * one of them is measured again whenever that line is edited.
     */
    static final int MAX_SQUIGGLES_PER_LINE = 250;

    /**
     * Where a paragraph's squiggles go, relative to the paragraph's own box: {@code {x0, x1, y}} per
     * squiggle, in screen units. Asking RichTextFX where a word is ({@code getCharacterBoundsOnScreen}) is
     * the expensive part of a repaint, and it was asked for every squiggle on every scroll frame: a
     * viewport of prose in the wrong language cost 17–30 ms a frame against 2.5–4.5 ms for the same text
     * with nothing flagged. Scrolling moves a paragraph without changing its layout, so the answer is kept
     * and a frame costs one box lookup per paragraph.
     *
     * <p>Keyed by the paragraph object: RichTextFX replaces it on any edit or restyle of the line, so a
     * changed line can never find a stale entry. What a paragraph object does not capture — the wrap width,
     * the font, the tab size, a gutter that grew a digit — is caught three ways: a different box size
     * ({@link Geometry#width}/{@code height}); {@link #refresh} and {@link #invalidateGeometry}, which drop
     * everything; and one word re-measured per frame ({@link #stale}), since anything that moves the text
     * inside its box moves it in every paragraph alike.
     */
    private final java.util.Map<Object, Geometry> geometry = new java.util.IdentityHashMap<>();

    private static final int MAX_GEOMETRY = 600;

    /**
     * One paragraph's squiggles; {@code rects} is empty when none of its words is flagged and eligible.
     * {@code firstStart}/{@code firstEnd} are the columns of the word the first squiggle belongs to.
     */
    private record Geometry(double width, double height, double[] rects, int firstStart, int firstEnd) {}

    private static final double[] NO_RECTS = new double[0];

    /** Words positioned by asking the text layout (a geometry-cache miss) — the test seam for that cache. */
    private int wordsMeasured;

    int wordsMeasuredForTest() {
        return wordsMeasured;
    }

    /** When set (by a test), every squiggle painted is appended as {@code {x0, x1, y}} in canvas pixels. */
    List<double[]> paintLog;

    /** Lines whose flagged spans had to be computed (a cache miss) — the test seam for the line cache. */
    private int linesScanned;

    int linesScannedForTest() {
        return linesScanned;
    }

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

    /** Which words this buffer's language has checked (see {@link SpellMode}), and which lines are code. */
    void setMode(SpellMode mode) {
        if (follower != null) {
            follower.setMode(mode);
        }
        if (this.mode == mode) {
            return;
        }
        this.mode = mode;
        this.markdown = mode == SpellMode.MARKDOWN;
        flaggedCache.clear(); // the same line is cut into tokens differently in another mode
        geometry.clear();
        recomputeCodeLines();
        scheduleRedraw();
    }

    SpellMode mode() {
        return mode;
    }

    private void recomputeCodeLines() {
        BitSet before = codeLines;
        if (!active && before.isEmpty()) {
            return; // nothing is drawn or asked while inactive; setActive(true) recomputes
        }
        // Straight off the paragraph list: the whole-document String this used to build and split (two
        // O(document) copies on the FX thread per settled edit) held nothing the paragraphs do not.
        var paragraphs = area.getParagraphs();
        codeLines = mode.codeLines(paragraphs.size(), i -> paragraphs.get(i).getText());
        if (!codeLines.equals(before)) {
            geometry.clear(); // a line that became (or stopped being) code has squiggles to drop or draw
        }
    }

    /** Whether line {@code paragraph} (0-based) is code as a whole and so not checked. */
    boolean isCodeLine(int paragraph) {
        return codeLines.get(paragraph);
    }

    /** 0-based line indices inside Markdown fenced code blocks (the ``` delimiter lines included). Pure. */
    static BitSet fencedCodeLines(String text) {
        if (text == null || text.isEmpty()) {
            return new BitSet();
        }
        String[] lines = text.split("\n", -1);
        return fencedCodeLines(lines.length, i -> lines[i]);
    }

    /** {@link #fencedCodeLines(String)} over {@code count} lines supplied one at a time. Pure. */
    static BitSet fencedCodeLines(int count, java.util.function.IntFunction<String> lineAt) {
        BitSet code = new BitSet();
        int fenceStart = -1;
        for (int i = 0; i < count; i++) {
            if (isFence(lineAt.apply(i))) {
                if (fenceStart < 0) {
                    fenceStart = i;
                } else {
                    code.set(fenceStart, i + 1); // mark the whole fence, delimiters included
                    fenceStart = -1;
                }
            }
        }
        if (fenceStart >= 0) {
            code.set(fenceStart, count); // an unterminated fence runs to EOF (as editors render it)
        }
        return code;
    }

    /** Whether {@code line}, ignoring leading whitespace as {@code trim()} does, opens or closes a fence. */
    private static boolean isFence(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) <= ' ') {
            i++;
        }
        return line.startsWith("```", i) || line.startsWith("~~~", i);
    }

    /**
     * The same squiggles for a split's second {@code view}: an overlay for that view's pane, from then on
     * given every setting this one is given (checker, mode, on/off, refresh).
     */
    @Override
    public SpellCheckOverlay follower(CodeArea view) {
        SpellCheckOverlay second = new SpellCheckOverlay(view);
        second.checker = checker;
        second.setMode(mode);
        second.setRenderingActive(rendering);
        second.setActive(active);
        follower = second;
        return second;
    }

    void setChecker(SpellChecker checker) {
        if (follower != null) {
            follower.setChecker(checker);
        }
        this.checker = checker;
        spellCache.clear(); // a different dictionary → re-evaluate misspellings
        flaggedCache.clear();
        geometry.clear();
        scheduleRedraw();
    }

    void setActive(boolean active) {
        if (follower != null) {
            follower.setActive(active);
        }
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
        if (follower != null) {
            follower.refresh();
        }
        spellCache.clear(); // dictionary loaded / language / user-word / ignore-set changed
        flaggedCache.clear();
        geometry.clear();
        scheduleRedraw();
    }

    /** Forgets where the squiggles go, not what is misspelled: the font or the tab size changed. */
    void invalidateGeometry() {
        if (follower != null) {
            follower.invalidateGeometry();
        }
        geometry.clear();
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (getWidth() != laidOutWidth) {
            laidOutWidth = getWidth();
            geometry.clear(); // a different wrap width moves the words
        }
        // Only track the viewport size while there's something to paint; an idle overlay stays 1x1 (see
        // hasContent). redraw() grows the canvas on demand when it finds visible misspellings.
        if (hasContent) {
            double w = CanvasGuards.clampWidth(this, getWidth());
            double h = CanvasGuards.clampHeight(this, getHeight());
            if (canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas.setWidth(w);
                canvas.setHeight(h);
            }
        }
        scheduleRedraw();
    }

    /** Release/repaint this overlay as its tab is backgrounded/shown (see {@link #rendering}). */
    @Override
    public void setRenderingActive(boolean on) {
        if (follower != null) {
            follower.setRenderingActive(on);
        }
        if (rendering == on) {
            return;
        }
        rendering = on;
        if (on) {
            scheduleRedraw();
        } else {
            releaseCanvas();
        }
    }

    private void scheduleRedraw() {
        if (!active || !rendering || redrawPending) {
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
        double w = CanvasGuards.clampWidth(this, getWidth());
        double h = CanvasGuards.clampHeight(this, getHeight());
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
            if (geometry.size() > MAX_GEOMETRY) {
                geometry.clear(); // paragraphs long scrolled past; the visible ones are measured again below
            }
            // Phase 1 — collect the squiggles of the visible paragraphs: the cheap, cached scan first (for a
            // clean viewport this makes zero layout queries, exactly as before), then one box lookup per
            // paragraph that has any; the per-word positions inside the box are remembered.
            int count = last - first + 1;
            if (count <= 0) {
                releaseCanvas();
                return;
            }
            if (frameRects.length < count) {
                frameRects = new double[count][];
                frameBoxes = new Bounds[count];
            }
            boolean any = false;
            boolean verified = false; // one remembered word has been checked against the layout this frame
            for (int p = first; p <= last; p++) {
                int i = p - first;
                frameRects[i] = null;
                if (skipped(p) || flaggedIn(p).length == 0) {
                    continue;
                }
                Bounds box = area.getParagraphBoundsOnScreen(p).orElse(null);
                if (box == null) {
                    continue;
                }
                if (!verified) {
                    Geometry known = geometry.get(area.getParagraph(p));
                    if (known != null && known.rects().length > 0) {
                        verified = true;
                        if (stale(p, box, known)) {
                            geometry.clear(); // the text moved inside its box: every paragraph's did
                        }
                    }
                }
                double[] rects = rectsOf(p, box);
                if (rects.length > 0) {
                    frameRects[i] = rects;
                    frameBoxes[i] = box;
                    any = true;
                }
            }
            if (!any) {
                releaseCanvas(); // no visible squiggles → drop the viewport texture
                return;
            }
            // Phase 2 — size the canvas to the viewport and paint.
            ensureCanvasSized();
            GraphicsContext g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            g.setStroke(SQUIGGLE);
            g.setLineWidth(1.0);
            javafx.geometry.Point2D origin = canvas.localToScreen(0, 0);
            javafx.geometry.Point2D unit = canvas.localToScreen(1, 1);
            double sx = origin == null || unit == null ? 0 : unit.getX() - origin.getX();
            double sy = origin == null || unit == null ? 0 : unit.getY() - origin.getY();
            for (int i = 0; i < count; i++) {
                double[] rects = frameRects[i];
                Bounds box = frameBoxes[i];
                frameRects[i] = null;
                frameBoxes[i] = null;
                if (rects == null || sx <= 0 || sy <= 0) {
                    continue;
                }
                double bx = (box.getMinX() - origin.getX()) / sx;
                double by = (box.getMinY() - origin.getY()) / sy;
                for (int k = 0; k < rects.length; k += 3) {
                    double x0 = bx + rects[k] / sx;
                    double x1 = bx + rects[k + 1] / sx;
                    double y = by + rects[k + 2] / sy;
                    if (x1 < 0 || x0 > w || y < 0 || y > h + 2) {
                        continue; // off-screen (horizontal scroll, or a wrapped paragraph's hidden part)
                    }
                    squiggle(g, x0, x1, y - 1);
                    if (paintLog != null) {
                        paintLog.add(new double[] {x0, x1, y - 1});
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    /** This frame's squiggles per visible paragraph, and that paragraph's box; reused between frames. */
    private double[][] frameRects = new double[0][];

    private Bounds[] frameBoxes = new Bounds[0];

    /** Folded lines and whole-line code are never checked. */
    private boolean skipped(int paragraph) {
        return area.isFolded(paragraph) || codeLines.get(paragraph);
    }

    /** The flagged spans of line {@code paragraph} (paragraph-relative; not yet filtered by style). */
    private int[][] flaggedIn(int paragraph) {
        String line = area.getParagraph(paragraph).getText();
        if (line.isEmpty() || line.length() > MAX_LINE) {
            return NONE;
        }
        int[][] flagged = flaggedCache.get(line);
        if (flagged == null) {
            flagged = flaggedSpans(line);
            flaggedCache.put(line, flagged);
        }
        return flagged;
    }

    /**
     * The squiggle positions of {@code paragraph} relative to its on-screen {@code box}, measured once per
     * paragraph object and box size (see {@link #geometry}).
     */
    private double[] rectsOf(int paragraph, Bounds box) {
        Object key = area.getParagraph(paragraph);
        Geometry known = geometry.get(key);
        if (known != null && known.width() == box.getWidth() && known.height() == box.getHeight()) {
            return known.rects();
        }
        List<int[]> hits = new java.util.ArrayList<>();
        collectParagraph(paragraph, hits);
        double[] rects = hits.isEmpty() ? NO_RECTS : new double[hits.size() * 3];
        int n = 0;
        for (int[] hit : hits) {
            wordsMeasured++;
            Bounds b = area.getCharacterBoundsOnScreen(hit[0], hit[1]).orElse(null);
            if (b == null) {
                continue;
            }
            rects[n++] = b.getMinX() - box.getMinX();
            rects[n++] = b.getMaxX() - box.getMinX();
            rects[n++] = b.getMaxY() - box.getMinY();
        }
        if (n < rects.length) {
            return java.util.Arrays.copyOf(rects, n); // a word could not be placed yet: do not remember that
        }
        int base = hits.isEmpty() ? 0 : area.getAbsolutePosition(paragraph, 0);
        geometry.put(
                key,
                new Geometry(
                        box.getWidth(),
                        box.getHeight(),
                        rects,
                        hits.isEmpty() ? 0 : hits.get(0)[0] - base,
                        hits.isEmpty() ? 0 : hits.get(0)[1] - base));
        return rects;
    }

    /**
     * Whether the remembered position of {@code known}'s first word is no longer where the layout puts it.
     * One layout query a frame, against one per squiggle: it catches what neither the paragraph object nor
     * its box size shows, such as a line-number gutter that grew a digit and pushed the text right.
     */
    private boolean stale(int paragraph, Bounds box, Geometry known) {
        int base = area.getAbsolutePosition(paragraph, 0);
        Bounds now = area.getCharacterBoundsOnScreen(base + known.firstStart(), base + known.firstEnd())
                .orElse(null);
        return now == null
                || Math.abs(now.getMinX() - box.getMinX() - known.rects()[0]) > 0.5
                || Math.abs(now.getMaxY() - box.getMinY() - known.rects()[2]) > 0.5;
    }

    /** Adds each misspelled, eligible word in {@code paragraph} to {@code hits} as {@code {absStart, absEnd}}. */
    void collectParagraph(int paragraph, List<int[]> hits) {
        int[][] flagged = flaggedIn(paragraph);
        if (flagged.length == 0) {
            return; // the usual line: nothing misspelled, so no offset or style query at all
        }
        int parBase = area.getAbsolutePosition(paragraph, 0);
        for (int[] span : flagged) {
            int abs = parBase + span[0];
            if (!eligible(abs)) {
                continue; // eligibility is style-dependent (inline/fenced code) → evaluated fresh
            }
            hits.add(new int[] {abs, parBase + span[1]});
        }
    }

    /**
     * The misspelled, eligible words of line {@code paragraph} as absolute {@code {start, end}} offsets, in
     * order; empty for a folded line, a code line, or while the dictionary is loading. What the squiggles
     * show, for the commands that walk them (next/previous misspelling, the word at the caret).
     */
    List<int[]> misspellingsIn(int paragraph) {
        List<int[]> hits = new java.util.ArrayList<>();
        if (checker != null && checker.ready() && !skipped(paragraph)) {
            collectParagraph(paragraph, hits);
        }
        return hits;
    }

    /**
     * The spans of {@code line}'s words that are misspelled — a pure function of the text and the current
     * dictionary, which is what lets {@link #flaggedCache} keep it. A "misspelled" run that is actually part
     * of a URL, path, command, or dotted/underscored identifier is not flagged.
     */
    private int[][] flaggedSpans(String line) {
        linesScanned++;
        List<int[]> flagged = null;
        // checkableWords has already left out every word of a structured token, each token judged once.
        for (int[] span : SpellChecker.checkableWords(line, mode.syntax())) {
            int start = span[0];
            int end = span[1];
            if (spellCache.computeIfAbsent(line.substring(start, end), checker::isMisspelled)) {
                if (flagged == null) {
                    flagged = new java.util.ArrayList<>();
                }
                flagged.add(span);
                if (flagged.size() >= MAX_SQUIGGLES_PER_LINE) {
                    break;
                }
            }
        }
        return flagged == null ? NONE : flagged.toArray(new int[0][]);
    }

    /** Whether the word at absolute offset {@code abs} should be checked, by its applied syntax style. */
    private boolean eligible(int abs) {
        return mode.eligible(area.getStyleOfChar(abs));
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
}
