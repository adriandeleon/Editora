package com.editora.editor;

import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * A transparent overlay washing every occurrence of the symbol under the caret (LSP document highlight,
 * #675) — Read occurrences in a neutral tint, Write occurrences (assignments) warmer with a thin border —
 * behind the text, never touching the style spans.
 *
 * <p>A structural clone of {@link SearchHighlightOverlay} (itself modeled on {@link SpellCheckOverlay}):
 * mouse-transparent {@link Canvas} sized to the viewport, redraws coalesced to one per pulse on
 * scroll/edit/resize, visible paragraphs only, released to a 1×1 texture while empty (the common case —
 * the caret is rarely resting on a highlightable symbol).
 */
final class OccurrenceHighlightOverlay extends Region {

    private static final Color READ = Color.web("#90a4ae", 0.28); // neutral blue-gray wash
    private static final Color WRITE = Color.web("#ffb74d", 0.32); // warmer — the symbol is assigned here
    private static final Color WRITE_BORDER = Color.web("#ffb74d", 0.9);

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    /** {startOffset, endOffset, write(0/1)} triples, precomputed to offsets by the buffer. */
    private List<int[]> spans = List.of();

    private boolean active;
    private boolean redrawPending;

    OccurrenceHighlightOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("occurrence-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** Sets the occurrence spans (offset triples) to wash; empty hides + releases the texture. */
    void setSpans(List<int[]> spans) {
        this.spans = spans == null ? List.of() : spans;
        boolean show = !this.spans.isEmpty();
        if (active != show) {
            active = show;
            setVisible(show);
        }
        if (show) {
            requestLayout();
            scheduleRedraw();
        } else {
            canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.setWidth(1); // release the full-viewport texture while hidden
            canvas.setHeight(1);
        }
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
            int firstOffset = area.getAbsolutePosition(first, 0);
            int lastOffset = area.getAbsolutePosition(
                    last, area.getParagraph(last).getText().length());
            for (int[] s : spans) {
                if (s[1] < firstOffset || s[0] > lastOffset) {
                    continue; // cheap off-screen rejection before the costlier bounds lookups
                }
                paintSpan(g, s, first, last, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void paintSpan(GraphicsContext g, int[] span, int first, int last, double w, double h) {
        var startPos = area.offsetToPosition(span[0], Bias.Forward);
        var endPos = area.offsetToPosition(Math.max(span[1], span[0]), Bias.Backward);
        boolean write = span[2] != 0;
        int startLine = startPos.getMajor();
        int endLine = endPos.getMajor();
        if (endLine < first || startLine > last) {
            return;
        }
        for (int line = Math.max(startLine, first); line <= Math.min(endLine, last); line++) {
            if (area.isFolded(line)) {
                continue;
            }
            int lineLen = area.getParagraph(line).getText().length();
            int c0 = line == startLine ? startPos.getMinor() : 0;
            int c1 = line == endLine ? endPos.getMinor() : lineLen;
            if (c1 <= c0) {
                continue;
            }
            int absStart = area.getAbsolutePosition(line, c0);
            int absEnd = area.getAbsolutePosition(line, c1);
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(absStart, absEnd).orElse(null));
            if (b == null || b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
                continue;
            }
            g.setFill(write ? WRITE : READ);
            g.fillRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
            if (write) {
                g.setStroke(WRITE_BORDER);
                g.setLineWidth(1);
                g.strokeRect(b.getMinX() + 0.5, b.getMinY() + 0.5, b.getWidth() - 1, b.getHeight() - 1);
            }
        }
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
