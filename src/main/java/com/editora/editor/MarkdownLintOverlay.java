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
 * Draws wavy underlines beneath {@link MarkdownLint} diagnostics (red for errors, amber for
 * warnings), on top of the editor without touching the document or its style spans. Mirrors
 * {@link MermaidLintOverlay}: a mouse-transparent {@link Canvas} sized to the viewport, redrawn
 * coalesced (one per pulse) on scroll / edit / resize, only for the currently visible paragraphs.
 */
final class MarkdownLintOverlay extends Region {

    private static final Color ERROR = Color.web("#e5484d");
    private static final Color WARNING = Color.web("#f5a623");
    private static final double AMP = 1.6;
    private static final double STEP = 2.0;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<MarkdownLint.Diagnostic> diagnostics = List.of();
    private boolean active;
    private boolean redrawPending;

    MarkdownLintOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("markdown-lint-overlay");
        setMouseTransparent(true);
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
            requestLayout();
            scheduleRedraw();
        } else {
            diagnostics = List.of();
            clear();
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
    }

    void setDiagnostics(List<MarkdownLint.Diagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
        scheduleRedraw();
    }

    List<MarkdownLint.Diagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!active) {
            return;
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
            g.setLineWidth(1.0);
            for (MarkdownLint.Diagnostic d : diagnostics) {
                int p = d.line() - 1;
                if (p < first || p > last || p < 0 || p >= total || area.isFolded(p)) {
                    continue;
                }
                drawDiagnostic(g, p, d, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void drawDiagnostic(GraphicsContext g, int paragraph, MarkdownLint.Diagnostic d, double w, double h) {
        int lineLen = area.getParagraph(paragraph).getText().length();
        if (lineLen == 0) {
            return;
        }
        int start = Math.max(0, Math.min(d.column() - 1, lineLen - 1));
        int dEnd = Math.max(start + 1, Math.min(d.column() - 1 + Math.max(1, d.length()), lineLen));
        Bounds b = toLocal(area.getCharacterBoundsOnScreen(
                        area.getAbsolutePosition(paragraph, start), area.getAbsolutePosition(paragraph, dEnd))
                .orElse(null));
        if (b == null || b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
            return;
        }
        g.setStroke(d.isError() ? ERROR : WARNING);
        squiggle(g, b.getMinX(), b.getMaxX(), b.getMaxY() - 1);
    }

    /** The diagnostics whose span covers the character at {@code (paragraph, column)} (for hover tooltips). */
    List<MarkdownLint.Diagnostic> at(int paragraph, int column) {
        List<MarkdownLint.Diagnostic> hits = new ArrayList<>();
        for (MarkdownLint.Diagnostic d : diagnostics) {
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

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
