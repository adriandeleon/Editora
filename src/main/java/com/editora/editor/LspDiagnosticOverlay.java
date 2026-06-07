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
final class LspDiagnosticOverlay extends Region {

    private static final Color ERROR = Color.web("#e5484d");
    private static final Color WARNING = Color.web("#e2a03f");
    private static final Color INFO = Color.web("#4c8eda");
    private static final double AMP = 1.6;
    private static final double STEP = 2.0;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<LspDiagnostic> diagnostics = List.of();
    private boolean active;
    private boolean redrawPending;

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

    void setDiagnostics(List<LspDiagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
        scheduleRedraw();
    }

    List<LspDiagnostic> diagnostics() {
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
            g.setLineWidth(1.0);
            for (LspDiagnostic d : diagnostics) {
                drawDiagnostic(g, d, first, last, total, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void drawDiagnostic(GraphicsContext g, LspDiagnostic d, int first, int last, int total,
            double w, double h) {
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
                    area.getAbsolutePosition(p, startCol),
                    area.getAbsolutePosition(p, endCol)).orElse(null));
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

    private static Color color(LspDiagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> ERROR;
            case WARNING -> WARNING;
            default -> INFO;
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
