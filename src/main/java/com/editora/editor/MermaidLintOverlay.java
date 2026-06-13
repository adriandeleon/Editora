package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.mermaid.MaidOutput;
import org.fxmisc.richtext.CodeArea;

/**
 * Draws red wavy underlines beneath Mermaid (maid) diagnostics, on top of the editor without touching the
 * document or its style spans. Mirrors {@link SpellCheckOverlay}: a mouse-transparent {@link Canvas} sized
 * to the viewport, redrawn coalesced (one per pulse) on scroll / edit / resize, only for the currently
 * visible paragraphs. The diagnostics come from maid (1-based line/column + char length) and are pushed in
 * by {@link EditorBuffer}; this class only renders them.
 */
final class MermaidLintOverlay extends Region {

    private static final Color SQUIGGLE = Color.web("#e5484d");
    private static final double AMP = 1.6;
    private static final double STEP = 2.0;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<MaidOutput.Diagnostic> diagnostics = List.of();
    private boolean active;
    private boolean redrawPending;

    MermaidLintOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("mermaid-lint-overlay");
        setMouseTransparent(true);
        // Inactive until a .mmd buffer enables linting: stay hidden so the renderer composites nothing
        // and the 1x1 canvas holds no full-viewport GPU texture (almost every buffer is not a diagram).
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

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
            diagnostics = List.of();
            clear();
            canvas.setWidth(1); // release the full-viewport texture while hidden
            canvas.setHeight(1);
        }
    }

    void setDiagnostics(List<MaidOutput.Diagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
        scheduleRedraw();
    }

    List<MaidOutput.Diagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!active) {
            return; // stay 1x1 / no texture while inactive (the common case)
        }
        double w = CanvasGuards.clampDim(getWidth());
        double h = CanvasGuards.clampDim(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
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

    private void clear() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (!active || diagnostics.isEmpty() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            g.setStroke(SQUIGGLE);
            g.setLineWidth(1.0);
            OverlayMetrics metrics = new OverlayMetrics(area, canvas);
            for (MaidOutput.Diagnostic d : diagnostics) {
                int p = d.line() - 1;
                if (p < first || p > last || p < 0 || p >= total || area.isFolded(p)) {
                    continue;
                }
                drawDiagnostic(g, p, d, w, h, metrics);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void drawDiagnostic(GraphicsContext g, int paragraph, MaidOutput.Diagnostic d, double w, double h,
                                OverlayMetrics metrics) {
        int lineLen = area.getParagraph(paragraph).getText().length();
        if (lineLen == 0) {
            return;
        }
        int start = Math.max(0, Math.min(d.column() - 1, lineLen - 1));
        int end = Math.max(start + 1, Math.min(d.column() - 1 + Math.max(1, d.length()), lineLen));
        Bounds b = metrics.rangeLocal(paragraph, start, end);
        if (b == null || b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
            return;
        }
        squiggle(g, b.getMinX(), b.getMaxX(), b.getMaxY() - 1);
    }

    /** The diagnostics whose span covers the character at absolute {@code offset} (for hover tooltips). */
    List<MaidOutput.Diagnostic> at(int paragraph, int column) {
        List<MaidOutput.Diagnostic> hits = new ArrayList<>();
        for (MaidOutput.Diagnostic d : diagnostics) {
            if (d.line() - 1 == paragraph) {
                int start = d.column() - 1;
                int end = start + Math.max(1, d.length());
                if (column >= start && column <= end) {
                    hits.add(d);
                }
            }
        }
        return hits;
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
