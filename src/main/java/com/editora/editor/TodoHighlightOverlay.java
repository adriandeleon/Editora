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
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * Highlights configured TODO/FIXME-style pattern matches behind the editor text, each in its pattern's
 * color, without touching the document's style spans (so it never fights the syntax highlighter). A
 * mouse-transparent {@link Canvas} modeled on {@link SearchHighlightOverlay}: sized to the viewport,
 * redrawn coalesced (one per pulse) on scroll / edit / resize / fold, only for the visible paragraphs,
 * and dropped to 1x1 (no GPU texture) when there are no matches. Driven by {@link #setMarks}.
 */
final class TodoHighlightOverlay extends Region {

    /** Opacity of the highlight wash drawn over each match (the pattern color provides the hue). */
    private static final double WASH_ALPHA = 0.30;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    /** Parsed spans (offsets + already-washed color), parallel to the supplied marks. */
    private List<int[]> ranges = List.of();

    private List<Color> colors = List.of();
    private boolean active;
    private boolean redrawPending;

    TodoHighlightOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("todo-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** Sets the highlight marks (null/empty clears and releases the canvas). */
    void setMarks(List<TodoMark> marks) {
        List<int[]> r = new ArrayList<>();
        List<Color> c = new ArrayList<>();
        if (marks != null) {
            for (TodoMark m : marks) {
                if (m == null || m.end() <= m.start()) {
                    continue;
                }
                r.add(new int[] {m.start(), m.end()});
                c.add(washColor(m.colorWeb()));
            }
        }
        this.ranges = r;
        this.colors = c;
        boolean show = !r.isEmpty();
        if (active != show) {
            active = show;
            setVisible(show);
        }
        if (show) {
            requestLayout();
            scheduleRedraw();
        } else {
            clear();
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
    }

    private static Color washColor(String web) {
        try {
            return Color.web(web, WASH_ALPHA);
        } catch (RuntimeException ignored) {
            return Color.web("#E5C07B", WASH_ALPHA); // fall back to the default amber on a bad hex
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
            int firstOffset = area.getAbsolutePosition(first, 0);
            int lastOffset = area.getAbsolutePosition(
                    last, area.getParagraph(last).getText().length());
            for (int i = 0; i < ranges.size(); i++) {
                int[] m = ranges.get(i);
                if (m[1] < firstOffset || m[0] > lastOffset) {
                    continue; // off-screen — cheap reject before the costly offset→position conversion
                }
                paintMark(g, m, colors.get(i), first, last, w, h);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void paintMark(GraphicsContext g, int[] match, Color color, int first, int last, double w, double h) {
        var startPos = area.offsetToPosition(match[0], Bias.Forward);
        var endPos = area.offsetToPosition(Math.max(match[1], match[0]), Bias.Backward);
        int startLine = startPos.getMajor();
        int endLine = endPos.getMajor();
        if (endLine < first || startLine > last) {
            return;
        }
        g.setFill(color);
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
            g.fillRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
        }
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
