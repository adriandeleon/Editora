package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;

/**
 * Draws severity-colored wavy underlines beneath LSP diagnostics on top of the editor, without touching
 * the document or its style spans. Mirrors {@link MermaidLintOverlay} (mouse-transparent {@link Canvas}
 * sized to the viewport, coalesced redraw on scroll/edit/resize, visible paragraphs only) but handles
 * multi-line ranges and per-severity colors. Diagnostics use 0-based line/character (LSP convention) and
 * are pushed in by {@link EditorBuffer}; this class only renders them.
 */
final class LspDiagnosticOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    private OverlayPalette.Colors colors = OverlayPalette.of(Color.WHITE);
    private static final double AMP = 1.6;
    private static final double STEP = 2.0;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<LspDiagnostic> diagnostics = List.of();
    private boolean active;
    private boolean redrawPending;
    /** False while this overlay's tab is in the background — see {@link #setRenderingActive}. */
    private boolean rendering = true;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private LspDiagnosticOverlay follower;

    LspDiagnosticOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("lsp-diagnostic-overlay");
        setMouseTransparent(true);
        // Inactive until a buffer turns LSP on: stay hidden so the renderer composites nothing and the
        // 1x1 canvas holds no full-viewport GPU texture (most buffers are non-Java / LSP off).
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
        OverlayPalette.track(area, palette -> {
            colors = palette; // resolved on a theme change, not per paint
            scheduleRedraw();
        });
    }

    /** The same squiggles for a split's second {@code view}, kept in step with this one. */
    @Override
    public LspDiagnosticOverlay follower(CodeArea view) {
        LspDiagnosticOverlay second = new LspDiagnosticOverlay(view);
        second.setRenderingActive(rendering);
        second.setActive(active);
        second.setDiagnostics(diagnostics);
        follower = second;
        return second;
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
            requestLayout(); // size the canvas up to the viewport, then draw
            scheduleRedraw();
        } else {
            diagnostics = List.of();
            CanvasGuards.release(canvas); // drop the full-viewport texture while hidden
        }
    }

    void setDiagnostics(List<LspDiagnostic> diagnostics) {
        if (follower != null) {
            follower.setDiagnostics(diagnostics);
        }
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
        if (this.diagnostics.isEmpty()) {
            // Nothing to underline: let the texture go now, and stay out of the scroll/edit repaint path
            // entirely until there is (the usual state of a clean file).
            CanvasGuards.release(canvas);
            return;
        }
        scheduleRedraw();
    }

    List<LspDiagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!drawable()) {
            return; // stay 1x1 / no texture while off, backgrounded, or with nothing to draw
        }
        CanvasGuards.fit(this, canvas);
        scheduleRedraw();
    }

    /**
     * Releases the canvas while this overlay's tab is in the background and repaints when it is shown again
     * (driven by {@code EditorBuffer.setRenderingActive}). A hidden tab would otherwise keep a
     * viewport-sized texture alive for as long as it stays open.
     */
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
            requestLayout();
            scheduleRedraw();
        } else {
            CanvasGuards.release(canvas);
        }
    }

    /** Whether there is anything to paint; the canvas is 1x1 (no viewport texture) whenever there is not. */
    private boolean drawable() {
        return active && rendering && !diagnostics.isEmpty();
    }

    private void scheduleRedraw() {
        if (!drawable() || redrawPending) {
            return;
        }
        redrawPending = true;
        Platform.runLater(() -> {
            redrawPending = false;
            redraw();
        });
    }

    private void redraw() {
        if (!drawable() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            CanvasGuards.release(canvas);
            return;
        }
        CanvasGuards.fit(this, canvas); // grown here as well: content can arrive without a layout pass
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        try {
            int total = area.getParagraphs().size();
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            g.setLineWidth(1.0);
            for (LspDiagnostic d : diagnostics) {
                drawDiagnostic(g, d, first, last, total, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void drawDiagnostic(
            GraphicsContext g, LspDiagnostic d, int first, int last, int total, double w, double h) {
        if (d.endLine() < first || d.startLine() > last) {
            return; // entirely off-screen — skip before touching the GraphicsContext state
        }
        g.setStroke(color(d.severity()));
        int from = Math.max(d.startLine(), first);
        int to = Math.min(d.endLine(), last);
        for (int p = from; p <= to; p++) {
            if (p < 0 || p >= total || area.isFolded(p)) {
                continue;
            }
            int lineLen = area.getParagraph(p).getText().length();
            if (lineLen == 0) {
                continue;
            }
            int startCol = p == d.startLine() ? d.startCol() : 0;
            int endCol = p == d.endLine() ? d.endCol() : lineLen;
            startCol = Math.max(0, Math.min(startCol, lineLen - 1));
            endCol = Math.max(startCol + 1, Math.min(endCol, lineLen));
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(
                            area.getAbsolutePosition(p, startCol), area.getAbsolutePosition(p, endCol))
                    .orElse(null));
            if (b == null || b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
                continue;
            }
            squiggle(g, b.getMinX(), b.getMaxX(), b.getMaxY() - 1);
        }
    }

    /** Diagnostics whose range covers (paragraph, column) — for hover tooltips. */
    List<LspDiagnostic> at(int paragraph, int column) {
        List<LspDiagnostic> hits = new ArrayList<>();
        for (LspDiagnostic d : diagnostics) {
            if (paragraph < d.startLine() || paragraph > d.endLine()) {
                continue;
            }
            int startCol = paragraph == d.startLine() ? d.startCol() : 0;
            int endCol = paragraph == d.endLine() ? d.endCol() : Integer.MAX_VALUE;
            if (column >= startCol && column <= Math.max(startCol + 1, endCol)) {
                hits.add(d);
            }
        }
        return hits;
    }

    private Color color(LspDiagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> colors.error();
            case WARNING -> colors.warning();
            default -> colors.info();
        };
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
