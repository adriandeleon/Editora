package com.editora.editor;

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
    private static final double AMP = 1.6;   // squiggle peak-to-baseline amplitude (px)
    private static final double STEP = 2.0;  // half-wavelength (px)

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private SpellChecker checker;
    private boolean proseMode;
    private boolean active;
    private boolean redrawPending;

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
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
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
            scheduleRedraw();
        } else {
            clear();
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
        double w = CanvasGuards.clampDim(getWidth());
        double h = CanvasGuards.clampDim(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        canvas.relocate(0, 0);
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

    private void clear() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (!active || checker == null || !checker.ready() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            g.setStroke(SQUIGGLE);
            g.setLineWidth(1.0);
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                drawParagraph(g, p, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void drawParagraph(GraphicsContext g, int paragraph, double w, double h) {
        String line = area.getParagraph(paragraph).getText();
        if (line.isEmpty()) {
            return;
        }
        for (int[] span : spanCache.computeIfAbsent(line, SpellChecker::wordSpans)) {
            int start = span[0];
            int end = span[1];
            int abs = area.getAbsolutePosition(paragraph, start);
            if (!eligible(abs)) {
                continue; // eligibility is style-dependent → always evaluated fresh (never cached)
            }
            // Cache the Hunspell verdict per word (cleared on dictionary/ignore changes). Checked only
            // after eligibility, so ineligible code tokens still never reach the dictionary, as before.
            if (!spellCache.computeIfAbsent(line.substring(start, end), checker::isMisspelled)) {
                continue;
            }
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(abs,
                    area.getAbsolutePosition(paragraph, end)).orElse(null));
            if (b == null) {
                continue;
            }
            if (b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
                continue; // off-screen
            }
            squiggle(g, b.getMinX(), b.getMaxX(), b.getMaxY() - 1);
        }
    }

    /** Whether the word at absolute offset {@code abs} should be checked, by its applied syntax style. */
    private boolean eligible(int abs) {
        Collection<String> style = area.getStyleOfChar(abs);
        if (proseMode) {
            return !style.contains("code"); // skip Markdown inline/fenced code; check prose
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
