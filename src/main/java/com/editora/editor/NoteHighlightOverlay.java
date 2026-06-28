package com.editora.editor;

import java.util.List;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * A transparent overlay that paints a soft highlight behind each Personal Note's anchored span, on top of
 * the editor without touching the document or its style spans. Modeled on {@link SpellCheckOverlay}: a
 * mouse-transparent {@link Canvas} sized to the viewport, redrawn coalesced (one per pulse) on
 * scroll/edit/resize/fold, only for visible spans. Off in large-file mode / when indicators are hidden.
 */
final class NoteHighlightOverlay extends Region {

    // A soft amber wash painted like a text selection: a flat, contiguous fill (no per-line outline or
    // rounded corners) so a multi-line note traces the same shape as the editor's selection.
    private static final Color FILL = Color.web("#fbc02d", 0.22);
    // A small solid corner triangle painted at each note's start character — the inline "there's a note
    // here" marker (replaces the old gutter glyph). Deeper amber than the wash so it reads on top of it.
    private static final Color GLYPH = Color.web("#f57f17", 0.95);
    private static final double GLYPH_SIZE = 8.75;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private Supplier<List<int[]>> spans = List::of;
    private boolean active;
    private boolean redrawPending;

    NoteHighlightOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("note-highlight-overlay");
        setMouseTransparent(true);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    void setSpans(Supplier<List<int[]>> spans) {
        this.spans = spans == null ? List::of : spans;
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

    void refresh() {
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
        if (!active || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        g.setLineWidth(1);
        try {
            int firstVisible = area.firstVisibleParToAllParIndex();
            int lastVisible = area.lastVisibleParToAllParIndex();
            int total = area.getParagraphs().size();
            // Reject off-screen spans by a cheap offset comparison before the offsetToPosition
            // conversions in paintSpan (which would otherwise run for every note on each redraw).
            int firstOffset = area.getAbsolutePosition(Math.max(0, firstVisible), 0);
            int lastVis = Math.min(total - 1, lastVisible);
            int lastOffset = area.getAbsolutePosition(
                    lastVis, area.getParagraph(lastVis).getText().length());
            for (int[] span : spans.get()) {
                if (span[1] < firstOffset || span[0] > lastOffset) {
                    continue;
                }
                paintSpan(g, span[0], span[1], firstVisible, lastVisible, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — a later event will redraw.
        }
    }

    /**
     * Paints a highlight that follows the document's text shape: a multi-line span is drawn as one
     * rounded box <b>per line</b> (clamped to the visible paragraphs), mirroring how the editor renders a
     * multi-line selection — rather than a single bounding rectangle over the whole range.
     */
    private void paintSpan(
            GraphicsContext g, int start, int end, int firstVisible, int lastVisible, double w, double h) {
        int total = area.getLength();
        start = clamp(start, 0, total);
        end = clamp(end, 0, total);
        var startPos = area.offsetToPosition(start, Bias.Forward);
        var endPos = area.offsetToPosition(Math.max(end, start), Bias.Backward);
        int startLine = startPos.getMajor();
        int endLine = endPos.getMajor();
        boolean single = startLine == endLine;
        int from = Math.max(startLine, firstVisible);
        int to = Math.min(endLine, lastVisible);
        for (int line = from; line <= to; line++) {
            int paraLen = area.getParagraph(line).getText().length();
            boolean lastLine = line == endLine;
            int c0 = (line == startLine) ? startPos.getMinor() : 0;
            int c1 = lastLine ? endPos.getMinor() : paraLen;
            int o0 = area.getAbsolutePosition(line, clamp(c0, 0, paraLen));
            int o1 = area.getAbsolutePosition(line, clamp(c1, 0, paraLen));
            // Query a non-empty range so the bounds (left edge, baseline, height) are well-defined.
            Bounds b = toLocal(
                    area.getCharacterBoundsOnScreen(o0, Math.max(o1, o0 + 1)).orElse(null));
            if (b == null) {
                continue;
            }
            double x = b.getMinX();
            // Intermediate lines of a multi-line note fill to the right edge — matching how a text
            // selection paints the included line break; the last (and single) line stops at the caret.
            double right = (!single && !lastLine) ? w : b.getMaxX();
            if (right < 0 || x > w || b.getMaxY() < 0 || b.getMinY() > h) {
                continue;
            }
            // Flat, square fill so adjacent lines join seamlessly (like a selection), not separate pills.
            g.setFill(FILL);
            g.fillRect(x, b.getMinY(), Math.max(1, right - x), b.getHeight());
            // The inline note marker: a small corner triangle at the very start of the note's span (drawn
            // once, on the note's first line, when that line is visible).
            if (line == startLine) {
                double gx = Math.max(0, x);
                double gy = b.getMinY();
                g.setFill(GLYPH);
                g.fillPolygon(new double[] {gx, gx + GLYPH_SIZE, gx}, new double[] {gy, gy, gy + GLYPH_SIZE}, 3);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
