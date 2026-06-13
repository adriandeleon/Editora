package com.editora.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import org.fxmisc.richtext.CodeArea;

/**
 * A transparent overlay that draws "hidden character" markers — {@code ·} for spaces, {@code →} for
 * tabs, {@code ¶} for line ends — on top of the editor without touching the document text.
 *
 * <p>RichTextFX has no native whitespace rendering, so markers are painted on a {@link Canvas} sized
 * to the text viewport. Each marker is positioned from {@link CodeArea#getCharacterBoundsOnScreen} so
 * it stays aligned across tabs and any font. Only the currently visible paragraphs are drawn, and
 * redraws (on scroll / edit / resize / fold) are coalesced to one per pulse — when inactive the
 * overlay does nothing, so it is free unless the user turns it on.
 */
final class WhitespaceOverlay extends Region {

    private static final Color MARKER = Color.web("#b8bdc4");
    private static final String SPACE = "·";
    private static final String TAB = "→";
    private static final String EOL = "¶";

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private boolean active;
    private boolean redrawPending;
    private Font font = Font.font("monospace", 14);

    WhitespaceOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("whitespace-overlay");
        setMouseTransparent(true);
        // Inactive until "show whitespace" is on: stay hidden so the renderer composites nothing and the
        // 1x1 canvas holds no full-viewport GPU texture (whitespace markers are off by default).
        setVisible(false);
        getChildren().add(canvas);
        // Anything that changes what's on screen (scroll, resize, layout, fold) or the text itself.
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** Turns the markers on or off. */
    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        setVisible(active);
        if (active) {
            requestLayout(); // size the canvas up to the viewport, then draw
            scheduleRedraw();
        } else {
            clear();
            canvas.setWidth(1); // release the full-viewport texture while hidden
            canvas.setHeight(1);
        }
    }

    /** Keeps the marker glyphs in step with the editor font. */
    void setFont(String family, int size) {
        this.font = Font.font(family, size);
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!active) {
            return; // stay 1x1 / no texture while inactive (off by default)
        }
        double w = CanvasGuards.clampDim(getWidth());
        double h = CanvasGuards.clampDim(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        scheduleRedraw();
    }

    /** Coalesces a burst of viewport events into a single redraw on the next pulse. */
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
        if (!active || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            g.setFill(MARKER);
            g.setFont(font);
            g.setTextBaseline(VPos.CENTER);
            // The text area's left edge (past the line-number gutter). Empty lines have no character
            // to anchor their ¶ to, so we reuse this x derived from a real character on any line.
            double contentLeftX = contentLeftX(first, last);
            for (int p = first; p <= last; p++) {
                // Skip collapsed (folded) paragraphs: they aren't rendered, and their character
                // bounds collapse onto the fold-header row, piling stray markers there.
                if (area.isFolded(p)) {
                    continue;
                }
                drawParagraph(g, p, total, w, contentLeftX);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    /**
     * The local x of the text area's left edge, taken from the first character of the first
     * non-empty visible paragraph. Returns {@code -1} if no visible line has any text.
     */
    private double contentLeftX(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (area.isFolded(p) || area.getParagraph(p).getText().isEmpty()) {
                continue;
            }
            int abs = area.getAbsolutePosition(p, 0);
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null));
            if (b != null) {
                return b.getMinX();
            }
        }
        return -1;
    }

    private void drawParagraph(GraphicsContext g, int paragraph, int total, double w, double contentLeftX) {
        String line = area.getParagraph(paragraph).getText();
        int len = line.length();
        g.setTextAlign(TextAlignment.CENTER);
        // getCharacterBoundsOnScreen is a synchronous layout query; a space-indented line would issue one
        // per leading space. In a monospace font adjacent non-tab glyphs have equal-width, perfectly
        // abutting boxes, so within a contiguous run of spaces we advance arithmetically from a single
        // real query. Tabs (variable width → tab stops) are always queried, and any non-whitespace gap
        // forces the next marker to re-query — so positions stay exact.
        Bounds prev = null; // bounds of the previous whitespace marker, for arithmetic advance
        int prevIndex = -2; // its index (contiguity check); prevTab guards advancing past a tab box
        boolean prevTab = false;
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                continue; // a non-whitespace gap; the next marker re-queries (contiguity broken)
            }
            Bounds local;
            if (c == ' ' && prev != null && i == prevIndex + 1 && !prevTab) {
                double cw = prev.getWidth(); // a single-cell advance from the last real/derived box
                local = new javafx.geometry.BoundingBox(prev.getMaxX(), prev.getMinY(), cw, prev.getHeight());
            } else {
                int abs = area.getAbsolutePosition(paragraph, i);
                local = toLocal(area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null));
            }
            if (local == null) {
                prev = null;
                prevIndex = -2;
                continue;
            }
            prev = local; // keep tracking even when off-screen so contiguity (and advance) holds
            prevIndex = i;
            prevTab = c == '\t';
            double cx = local.getMinX() + local.getWidth() / 2;
            if (cx < 0 || cx > w) {
                continue; // off-screen horizontally
            }
            g.fillText(c == ' ' ? SPACE : TAB, cx, local.getMinY() + local.getHeight() / 2);
        }
        // End-of-line marker, except after the document's final paragraph (which has no line break).
        if (paragraph < total - 1) {
            Bounds eol = len > 0
                    ? toLocal(area.getCharacterBoundsOnScreen(
                                    area.getAbsolutePosition(paragraph, len - 1),
                                    area.getAbsolutePosition(paragraph, len - 1) + 1)
                            .orElse(null))
                    : toLocal(area.getParagraphBoundsOnScreen(paragraph).orElse(null));
            // For an empty line, anchor the ¶ at the text-area left edge, not the line's
            // on-screen minX (which is the gutter); skip if no reference x is available.
            if (eol != null && (len > 0 || contentLeftX >= 0)) {
                double x = len > 0 ? eol.getMaxX() + 2 : contentLeftX;
                if (x >= 0 && x <= w) {
                    g.setTextAlign(TextAlignment.LEFT);
                    g.fillText(EOL, x, eol.getMinY() + eol.getHeight() / 2);
                }
            }
        }
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
