package com.editora.editor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * A transparent overlay that paints a soft highlight behind each Personal Note's anchored span, on top of
 * the editor without touching the document or its style spans. Modeled on {@link SpellCheckOverlay}: a
 * mouse-transparent {@link Canvas} sized to the viewport, redrawn coalesced (one per pulse, after that pulse's
 * layout — see {@link #scheduleRedraw}) on scroll/edit/resize/fold, only for visible spans. Each visual row of a
 * span is painted on its own, folded lines are skipped, and nothing is painted over the gutter when the text is
 * scrolled sideways under it. Off when indicators are hidden (the {@code showNoteIndicators} setting) and inert
 * while the buffer has no positioned note; it stays on in large-file mode, where it costs nothing until a note
 * is on screen and the notes are the user's own.
 */
final class NoteHighlightOverlay extends Region implements SecondaryPane.Followed, TabSurface {

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
    /**
     * Whether {@link #spans} has anything to paint, asked before a redraw is even queued. The overlay is
     * active for every buffer (the feature is on by default) but almost no buffer has a note: without this
     * gate each edit, scroll and layout queued a runLater that did the visible-paragraph lookups only to
     * find the span list empty. With it the overlay is inert — no runLater, no viewport call — until the
     * note manager positions a note, the way {@link TodoHighlightOverlay} is off until it has marks.
     */
    private BooleanSupplier hasSpans = () -> true;

    private boolean active;
    /**
     * False while this overlay's tab is in the background. A backgrounded tab keeps a full
     * viewport-sized Canvas (and its RTTexture) alive for nothing: the existing 1x1 release only fires
     * when the FEATURE is off or there is nothing to draw, never when the tab is merely not visible.
     * Measured at ~2.4 MB retained per open tab across the overlays. Driven by
     * {@code EditorBuffer.setRenderingActive}, the same hook the minimap already uses.
     */
    private boolean rendering = true;

    private boolean redrawPending;
    /** How many pulses after a paint the overlay checks that the text is still where it painted for. */
    private static final int CHECKS_AFTER_PAINT = 3;

    private final Runnable afterLayout = this::afterLayoutPulse;
    /** The scene whose post-layout pulses {@link #afterLayout} is listening to, or null. */
    private Scene armed;

    private int checksLeft;
    /** Where the first visible character was when the highlights were last painted (NaN: nothing painted). */
    private double paintedX = Double.NaN;

    private double paintedY = Double.NaN;
    /** Test seam: when set, every highlight box painted by the last frame is recorded as {@code {x0, y0, x1, y1}}. */
    static List<double[]> boxesForTest;
    /** The overlay of the split's second view, kept in step with this one (see {@link #follower}). */
    private NoteHighlightOverlay follower;
    // Whether the last redraw had visible note spans to paint. When false the canvas is shrunk to 1x1 to
    // release its viewport-sized RTTexture — a buffer with no visible notes shouldn't pin a full-viewport
    // texture (the convention the other overlays follow). layoutChildren re-grows only while this is set.
    private boolean hasContent;

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

    /** The same highlights and markers for a split's second {@code view}, kept in step with this one. */
    @Override
    public NoteHighlightOverlay follower(CodeArea view) {
        NoteHighlightOverlay second = new NoteHighlightOverlay(view);
        second.spans = spans;
        second.hasSpans = hasSpans;
        second.setRenderingActive(rendering);
        second.setActive(active);
        follower = second;
        return second;
    }

    void setSpans(Supplier<List<int[]>> spans) {
        setSpans(spans, () -> true);
    }

    /** As {@link #setSpans(Supplier)}, with a cheap "is there anything" test asked before any redraw is queued. */
    void setSpans(Supplier<List<int[]>> spans, BooleanSupplier hasSpans) {
        if (follower != null) {
            follower.setSpans(spans, hasSpans);
        }
        this.spans = spans == null ? List::of : spans;
        this.hasSpans = hasSpans == null ? () -> true : hasSpans;
        scheduleRedraw();
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
            scheduleRedraw();
        } else {
            releaseCanvas(); // off → drop the texture, not just clear it
        }
    }

    void refresh() {
        if (follower != null) {
            follower.refresh();
        }
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        // Track the viewport size only while there's something to paint; an idle overlay stays 1x1 (see
        // hasContent). redraw() grows the canvas on demand when a note span becomes visible.
        if (hasContent) {
            double w = CanvasGuards.clampWidth(this, getWidth());
            double h = CanvasGuards.clampHeight(this, getHeight());
            if (canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas.setWidth(w);
                canvas.setHeight(h);
            }
        }
        scheduleRedraw();
    }

    /** Release/repaint this overlay as its tab is backgrounded/shown (see {@link #rendering}). */
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
            scheduleRedraw();
        } else {
            releaseCanvas();
        }
    }

    /**
     * Asks for a repaint <em>after the next layout pass</em>. The highlights are placed from the laid-out text,
     * so a paint run before the pulse that lays it out ({@code Platform.runLater} does) is a paint for the old
     * layout, and that frame is shown. When the text moves without anything the overlay listens to (the gutter
     * widening a pulse after an edit takes the file to 1,000 lines, line numbers toggled, the font changed),
     * nothing asked for another. So the paint is tied to the pulse, and for a few pulses afterwards the overlay
     * looks again at where the text starts (the pattern of {@link MarkdownLintOverlay}).
     */
    private void scheduleRedraw() {
        if (!active || !rendering || redrawPending) {
            return;
        }
        if (!hasSpans.getAsBoolean()) {
            releaseCanvas(); // nothing to paint (the last note went away): drop the texture, queue nothing
            return;
        }
        redrawPending = true;
        arm();
    }

    private void arm() {
        Scene scene = getScene();
        if (scene == null) {
            Platform.runLater(afterLayout); // not in a window: there is no pulse to wait for
            return;
        }
        if (armed != scene) {
            disarm();
            scene.addPostLayoutPulseListener(afterLayout);
            armed = scene;
        }
        Platform.requestNextPulse();
    }

    private void disarm() {
        if (armed != null) {
            armed.removePostLayoutPulseListener(afterLayout);
            armed = null;
        }
    }

    /** Runs once the scene has been laid out: paints if asked to, else checks the last paint still fits. */
    private void afterLayoutPulse() {
        if (redrawPending) {
            redrawPending = false;
            redraw();
            checksLeft = CHECKS_AFTER_PAINT;
        } else if (checksLeft > 0) {
            checksLeft--;
            if (drawable() && textMoved()) {
                redraw();
                checksLeft = CHECKS_AFTER_PAINT;
            }
        }
        if (checksLeft > 0 && drawable() && armed != null) {
            Platform.requestNextPulse();
        } else {
            checksLeft = 0;
            disarm();
        }
    }

    /** Whether a repaint could put anything on the canvas. */
    private boolean drawable() {
        return active && rendering && hasContent;
    }

    /** Whether the first visible character is no longer where it was when the highlights were last painted. */
    private boolean textMoved() {
        try {
            int total = area.getParagraphs().size();
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            Bounds now = firstCharacter(first, last);
            return now != null && (now.getMinX() != paintedX || now.getMinY() != paintedY);
        } catch (RuntimeException midLayout) {
            return false;
        }
    }

    /** Grows the canvas to the viewport (only when there's content to paint) and clears it. */
    private void ensureCanvasSized() {
        double w = CanvasGuards.clampWidth(this, getWidth());
        double h = CanvasGuards.clampHeight(this, getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w); // resizing a Canvas also clears it
            canvas.setHeight(h);
        }
        canvas.relocate(0, 0);
        hasContent = true;
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    /** Shrinks the canvas to 1x1, releasing its RTTexture (no visible note spans / overlay off). */
    private void releaseCanvas() {
        hasContent = false;
        if (canvas.getWidth() != 1 || canvas.getHeight() != 1) {
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
    }

    private void redraw() {
        paintedX = Double.NaN;
        paintedY = Double.NaN;
        if (boxesForTest != null) {
            boxesForTest.clear();
        }
        if (!active || !CanvasGuards.paintable(getWidth(), getHeight())) {
            releaseCanvas();
            return;
        }
        List<int[]> all = spans.get();
        if (all.isEmpty()) {
            releaseCanvas(); // decided before any viewport lookup
            return;
        }
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
            List<int[]> visible = new java.util.ArrayList<>();
            for (int[] span : all) {
                if (span[1] < firstOffset || span[0] > lastOffset) {
                    continue;
                }
                visible.add(span);
            }
            if (visible.isEmpty()) {
                releaseCanvas(); // no visible note spans → drop the viewport texture
                return;
            }
            ensureCanvasSized();
            GraphicsContext g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            Bounds origin = firstCharacter(Math.max(0, firstVisible), lastVis);
            // Scrolled sideways, the text slides under the gutter, which stays put: nothing is painted left of
            // where column 0 sits at no scroll (where the text area begins), so no highlight covers the numbers.
            double textLeft = 0;
            if (origin != null) {
                paintedX = origin.getMinX();
                paintedY = origin.getMinY();
                textLeft = Math.max(0, Math.floor(origin.getMinX() + area.getEstimatedScrollX()));
            }
            for (int[] span : visible) {
                paintSpan(g, span[0], span[1], Math.max(0, firstVisible), lastVis, w, h, textLeft);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — a later event will redraw.
        }
    }

    /**
     * Paints a highlight that follows the document's text shape, like the editor's selection: one flat box per
     * <b>visual row</b> (clamped to the visible, unfolded paragraphs). The bounds of a range that wraps are the
     * box around all of its rows, so a note that crossed a row boundary used to paint those rows in full; such a
     * range is split until each piece is one row (see {@link #rows}). A line of a multi-line note other than its
     * last fills to the right edge on its last row — the included line break, as a selection paints it.
     */
    private void paintSpan(
            GraphicsContext g,
            int start,
            int end,
            int firstVisible,
            int lastVisible,
            double w,
            double h,
            double textLeft) {
        int total = area.getLength();
        start = clamp(start, 0, total);
        end = clamp(end, 0, total);
        var startPos = area.offsetToPosition(start, Bias.Forward);
        var endPos = area.offsetToPosition(Math.max(end, start), Bias.Backward);
        int startLine = startPos.getMajor();
        int endLine = endPos.getMajor();
        int from = Math.max(startLine, firstVisible);
        int to = Math.min(endLine, lastVisible);
        for (int line = from; line <= to; line++) {
            if (area.isFolded(line)) {
                continue;
            }
            int paraLen = area.getParagraph(line).getText().length();
            boolean lastLine = line == endLine;
            int c0 = clamp((line == startLine) ? startPos.getMinor() : 0, 0, paraLen);
            int c1 = clamp(lastLine ? endPos.getMinor() : paraLen, 0, paraLen);
            int o0 = area.getAbsolutePosition(line, c0);
            if (o0 >= total) {
                continue; // the very end of the document: no character to take a box from
            }
            // A non-empty range keeps the bounds (left edge, top, height) well-defined; on an empty line (or at
            // a line's end) that is the line break.
            List<double[]> rows = new java.util.ArrayList<>(2);
            rows(o0, o0 + Math.max(c1 - c0, 1), 0, rows);
            for (int r = 0; r < rows.size(); r++) {
                double[] row = rows.get(r);
                double x = row[0];
                double right = (!lastLine && r == rows.size() - 1) ? w : row[1];
                double top = row[2];
                double bottom = row[3];
                if (right < 0 || x > w || bottom < 0 || top > h) {
                    continue;
                }
                double left = Math.max(x, textLeft);
                if (right > left) {
                    // Flat, square fill so adjacent rows join seamlessly (like a selection), not separate pills.
                    g.setFill(FILL);
                    g.fillRect(left, top, Math.max(1, right - left), bottom - top);
                    if (boxesForTest != null) {
                        boxesForTest.add(new double[] {left, top, right, bottom});
                    }
                }
                // The inline note marker: a small corner triangle at the very start of the note's span (drawn
                // once, on the note's first row, while that character is not scrolled under the gutter).
                if (line == startLine && r == 0 && x >= textLeft - 0.5) {
                    g.setFill(GLYPH);
                    g.fillPolygon(new double[] {x, x + GLYPH_SIZE, x}, new double[] {top, top, top + GLYPH_SIZE}, 3);
                }
            }
        }
    }

    /**
     * Appends the visual rows of the offsets {@code [o0, o1)} of one line to {@code out}, each as {@code {x0, x1,
     * top, bottom}}, in order. Unwrapped text has one row per line, so that is one bounds query. A wrapped range
     * taller than its first character spans rows: it is halved until each piece is one row, and pieces on the
     * same row are joined into one box (two abutting translucent fills would show a seam).
     */
    private void rows(int o0, int o1, int depth, List<double[]> out) {
        Bounds b = bounds(o0, o1);
        if (b == null) {
            return;
        }
        if (o1 - o0 > 1 && depth < 32 && area.isWrapText()) {
            Bounds one = bounds(o0, o0 + 1);
            if (one != null && b.getHeight() > one.getHeight() * 1.5) {
                int mid = (o0 + o1) >>> 1;
                rows(o0, mid, depth + 1, out);
                rows(mid, o1, depth + 1, out);
                return;
            }
        }
        if (!out.isEmpty()) {
            double[] prev = out.get(out.size() - 1);
            if (Math.abs(prev[2] - b.getMinY()) < 0.5) {
                prev[0] = Math.min(prev[0], b.getMinX());
                prev[1] = Math.max(prev[1], b.getMaxX());
                return;
            }
        }
        out.add(new double[] {b.getMinX(), b.getMaxX(), b.getMinY(), b.getMaxY()});
    }

    /** The canvas-local bounds of the first character of the first visible, unfolded line that has one. */
    private Bounds firstCharacter(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (area.getParagraph(p).getText().isEmpty() || area.isFolded(p)) {
                continue;
            }
            int abs = area.getAbsolutePosition(p, 0);
            Bounds b = bounds(abs, abs + 1);
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    private Bounds bounds(int start, int end) {
        return toLocal(area.getCharacterBoundsOnScreen(start, end).orElse(null));
    }

    /**
     * The on-screen box of the character a note starting at {@code start} hangs its marker on, or
     * {@code null} when there is none. A note whose text was deleted up to the end of the document starts
     * <em>at</em> the end: there is no character there and no marker is drawn, and asking
     * {@code getCharacterBoundsOnScreen} for that empty range would leak a caret blink timer per call.
     */
    static Bounds markerBoundsOnScreen(CodeArea area, int start) {
        return start < 0 || start >= area.getLength()
                ? null
                : area.getCharacterBoundsOnScreen(start, start + 1).orElse(null);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
