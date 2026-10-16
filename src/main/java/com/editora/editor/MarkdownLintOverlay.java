package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;

/**
 * Draws wavy underlines beneath {@link MarkdownLint} diagnostics (red for errors, amber for
 * warnings), on top of the editor without touching the document or its style spans. Mirrors
 * {@link MermaidLintOverlay}: a mouse-transparent {@link Canvas} sized to the viewport, redrawn
 * coalesced (one per pulse) on scroll / edit / resize, only for the currently visible paragraphs.
 */
final class MarkdownLintOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    private OverlayPalette.Colors colors = OverlayPalette.of(Color.WHITE);
    private static final double AMP = 1.6;
    private static final double STEP = 2.0;
    private static final double STROKE = 1.5;
    /** Width of the squiggle drawn on an empty line (about two characters). */
    private static final double EMPTY_LINE_WIDTH = 16;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<MarkdownLint.Diagnostic> diagnostics = List.of();
    private boolean active;
    private boolean redrawPending;
    /** Runs the repaint after the layout pass, and looks again after it (see {@link AfterLayoutPaint}). */
    private final AfterLayoutPaint afterLayout;
    /** False while this overlay's tab is in the background — see {@link #setRenderingActive}. */
    private boolean rendering = true;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private MarkdownLintOverlay follower;

    MarkdownLintOverlay(CodeArea area) {
        this.area = area;
        this.afterLayout = new AfterLayoutPaint(
                this,
                area,
                () -> {
                    redrawPending = false;
                    redraw();
                },
                this::drawable);
        getStyleClass().add("markdown-lint-overlay");
        setMouseTransparent(true);
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
    public MarkdownLintOverlay follower(CodeArea view) {
        MarkdownLintOverlay second = new MarkdownLintOverlay(view);
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
            requestLayout();
            scheduleRedraw();
        } else {
            diagnostics = List.of();
            CanvasGuards.release(canvas); // drop the full-viewport texture while hidden
        }
    }

    void setDiagnostics(List<MarkdownLint.Diagnostic> diagnostics) {
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

    List<MarkdownLint.Diagnostic> diagnostics() {
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

    /** Asks for a repaint after the next layout pass (see {@link AfterLayoutPaint}). */
    private void scheduleRedraw() {
        if (!drawable() || redrawPending) {
            return;
        }
        redrawPending = true;
        afterLayout.request();
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
            Bounds origin = firstCharacter(first, last);
            if (origin != null) {}
            g.setLineWidth(STROKE);
            double textLeft = Double.NaN; // where text starts, found on the first empty line that needs it
            for (MarkdownLint.Diagnostic d : diagnostics) {
                int p = d.line() - 1;
                if (p < first || p > last || p < 0 || p >= total || area.isFolded(p)) {
                    continue;
                }
                g.setStroke(d.isError() ? colors.error() : d.isHint() ? colors.info() : colors.warning());
                int lineLen = area.getParagraph(p).getText().length();
                if (lineLen == 0) {
                    if (Double.isNaN(textLeft)) {
                        textLeft = textLeft(first, last);
                    }
                    drawOnEmptyLine(g, p, textLeft);
                    continue;
                }
                int start = Math.max(0, Math.min(d.column() - 1, lineLen - 1));
                int end = Math.max(start + 1, Math.min(d.column() - 1 + Math.max(1, d.length()), lineLen));
                drawRange(g, p, start, end, w, h, 0);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    /**
     * Underlines {@code [start, end)} of {@code paragraph}, one squiggle per visual row. The bounds of a range
     * that wraps are the box around all of its rows, so underlining that box put one line across the whole
     * editor under the last row; a range taller than its first character is halved until each piece is one row.
     */
    private void drawRange(GraphicsContext g, int paragraph, int start, int end, double w, double h, int depth) {
        Bounds b = bounds(paragraph, start, end);
        if (b == null || b.getMaxY() < 0 || b.getMinY() > h) {
            return;
        }
        if (end - start > 1 && depth < 32) {
            Bounds one = bounds(paragraph, start, start + 1);
            if (one != null && b.getHeight() > one.getHeight() * 1.5) {
                int mid = (start + end) >>> 1;
                drawRange(g, paragraph, start, mid, w, h, depth + 1);
                drawRange(g, paragraph, mid, end, w, h, depth + 1);
                return;
            }
        }
        if (b.getMaxX() < 0 || b.getMinX() > w) {
            return;
        }
        squiggle(g, b.getMinX(), b.getMaxX(), b.getMaxY() - 1);
    }

    /**
     * A short squiggle at the start of an empty line — "too many blank lines" and "one newline too many" sit on
     * lines with no character to underline, and used to be invisible in the editor.
     */
    private void drawOnEmptyLine(GraphicsContext g, int paragraph, double textLeft) {
        Bounds box = toLocal(area.getParagraphBoundsOnScreen(paragraph).orElse(null));
        if (box == null || Double.isNaN(textLeft)) {
            return;
        }
        squiggle(g, textLeft, textLeft + EMPTY_LINE_WIDTH, box.getMaxY() - 1);
    }

    /** The x where text starts (right of the gutter), read off the first visible line that has any. */
    private double textLeft(int first, int last) {
        Bounds b = firstCharacter(first, last);
        return b == null ? Double.NaN : b.getMinX();
    }

    /** The bounds of the first character of the first visible line that has one, or null. */
    private Bounds firstCharacter(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (area.getParagraph(p).getText().isEmpty() || area.isFolded(p)) {
                continue;
            }
            Bounds b = bounds(p, 0, 1);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    private Bounds bounds(int paragraph, int start, int end) {
        return toLocal(area.getCharacterBoundsOnScreen(
                        area.getAbsolutePosition(paragraph, start), area.getAbsolutePosition(paragraph, end))
                .orElse(null));
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
