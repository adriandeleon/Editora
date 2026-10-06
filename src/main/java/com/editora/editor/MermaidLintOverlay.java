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
final class MermaidLintOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    private static final Color SQUIGGLE = Color.web("#e5484d");
    private static final double AMP = 1.6;
    private static final double STEP = 2.0;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<MaidOutput.Diagnostic> diagnostics = List.of();
    private boolean active;
    private boolean redrawPending;
    /** False while this overlay's tab is in the background — see {@link #setRenderingActive}. */
    private boolean rendering = true;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private MermaidLintOverlay follower;

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

    /** The same squiggles for a split's second {@code view}, kept in step with this one. */
    @Override
    public MermaidLintOverlay follower(CodeArea view) {
        MermaidLintOverlay second = new MermaidLintOverlay(view);
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

    void setDiagnostics(List<MaidOutput.Diagnostic> diagnostics) {
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

    List<MaidOutput.Diagnostic> diagnostics() {
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
            g.setStroke(SQUIGGLE);
            g.setLineWidth(1.0);
            for (MaidOutput.Diagnostic d : diagnostics) {
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

    private void drawDiagnostic(GraphicsContext g, int paragraph, MaidOutput.Diagnostic d, double w, double h) {
        int lineLen = area.getParagraph(paragraph).getText().length();
        if (lineLen == 0) {
            return;
        }
        int start = Math.max(0, Math.min(d.column() - 1, lineLen - 1));
        int end = Math.max(start + 1, Math.min(d.column() - 1 + Math.max(1, d.length()), lineLen));
        Bounds b = toLocal(area.getCharacterBoundsOnScreen(
                        area.getAbsolutePosition(paragraph, start), area.getAbsolutePosition(paragraph, end))
                .orElse(null));
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

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
