package com.editora.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.snippet.SnippetSession;
import com.editora.snippet.SnippetSessions;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * Shows a running snippet session: the active field washed and outlined, the fields still to visit
 * outlined, the mirrors that follow the active field underlined, and a small mark where {@code $0} will
 * leave the caret. Without it nothing on screen says why Tab jumps instead of indenting.
 *
 * <p>The usual overlay discipline: a mouse-transparent {@link Canvas} over the text, one coalesced redraw
 * per event turn, only ranges on visible lines measured, colours from {@link OverlayPalette}, and a 1×1
 * canvas whenever no session runs in this view — which is nearly always, so the overlay is attached only
 * when the first session starts. The ranges are read from the session at paint time; an edit costs this
 * class one flag check.
 */
final class SnippetFieldOverlay extends Region implements SecondaryPane.Followed, TabSurface {

    private final CodeArea area;
    private final SnippetSessions sessions;
    private final Canvas canvas = new Canvas(1, 1);
    private OverlayPalette.Colors colors;
    private boolean active;
    private boolean rendering = true;
    private boolean redrawPending;
    private SnippetFieldOverlay follower;

    SnippetFieldOverlay(CodeArea area, SnippetSessions sessions) {
        this.area = area;
        this.sessions = sessions;
        getStyleClass().add("snippet-field-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
        OverlayPalette.track(area, palette -> {
            colors = palette;
            scheduleRedraw();
        });
    }

    /** The same marks for a split's second {@code view}: a session runs in one view and is drawn there. */
    @Override
    public SnippetFieldOverlay follower(CodeArea view) {
        follower = new SnippetFieldOverlay(view, sessions);
        follower.refresh();
        return follower;
    }

    @Override
    public void setRenderingActive(boolean on) {
        rendering = on;
        refresh();
    }

    /** Called when the session's ranges, active field or life may have changed. */
    void refresh() {
        if (follower != null) {
            follower.refresh();
        }
        boolean show = rendering && sessions.isActive() && sessions.area() == area;
        if (active != show) {
            active = show;
            setVisible(show);
            if (!show) {
                canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
                canvas.setWidth(1); // no session here: hold no viewport-sized texture
                canvas.setHeight(1);
                return;
            }
            requestLayout();
        }
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!active) {
            return;
        }
        double w = CanvasGuards.clampWidth(this, getWidth());
        double h = CanvasGuards.clampHeight(this, getHeight());
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
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (!active || colors == null || !CanvasGuards.paintable(getWidth(), getHeight())) {
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
            sessions.marks((start, end, kind) -> {
                if (end >= firstOffset && start <= lastOffset && end <= area.getLength()) {
                    paint(g, start, end, kind);
                }
            });
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    private void paint(GraphicsContext g, int start, int end, int kind) {
        Color accent = colors.info();
        if (end <= start) {
            paintEmpty(g, start, kind == SnippetSession.MARK_FINAL ? colors.whitespace() : accent, kind);
            return;
        }
        var from = area.offsetToPosition(start, Bias.Forward);
        var to = area.offsetToPosition(end, Bias.Backward);
        for (int line = from.getMajor(); line <= to.getMajor(); line++) {
            if (area.isFolded(line)) {
                continue;
            }
            int c0 = line == from.getMajor() ? from.getMinor() : 0;
            int c1 = line == to.getMajor()
                    ? to.getMinor()
                    : area.getParagraph(line).getText().length();
            if (c1 <= c0) {
                continue;
            }
            Bounds b = local(area.getCharacterBoundsOnScreen(
                            area.getAbsolutePosition(line, c0), area.getAbsolutePosition(line, c1))
                    .orElse(null));
            if (b == null) {
                continue;
            }
            double x = Math.round(b.getMinX()) + 0.5;
            double y = Math.round(b.getMinY()) + 0.5;
            double w = Math.max(2, Math.round(b.getWidth()) - 1);
            double h = Math.round(b.getHeight()) - 1;
            g.setLineWidth(1);
            g.setLineDashes();
            switch (kind) {
                case SnippetSession.MARK_ACTIVE -> {
                    g.setFill(accent.deriveColor(0, 1, 1, 0.16));
                    g.fillRect(x, y, w, h);
                    g.setStroke(accent);
                    g.strokeRect(x, y, w, h);
                }
                case SnippetSession.MARK_MIRROR -> {
                    g.setFill(accent.deriveColor(0, 1, 1, 0.10));
                    g.fillRect(x, y, w, h);
                    g.setStroke(accent.deriveColor(0, 1, 1, 0.8));
                    g.setLineDashes(2, 2);
                    g.strokeLine(x, y + h, x + w, y + h);
                }
                default -> {
                    g.setStroke(accent.deriveColor(0, 1, 1, 0.85));
                    g.setLineDashes(3, 2);
                    g.strokeRect(x, y, w, h);
                }
            }
        }
        g.setLineDashes();
    }

    /**
     * An empty stop: a bar where the caret will go. Measured from a neighbouring character — never from an
     * empty range, which makes RichTextFX allocate a caret node whose blink timer is never stopped.
     */
    private void paintEmpty(GraphicsContext g, int offset, Color color, int kind) {
        int length = area.getLength();
        Bounds b = null;
        boolean leftEdge = true;
        if (offset < length && !area.getText(offset, offset + 1).equals("\n")) {
            b = local(area.getCharacterBoundsOnScreen(offset, offset + 1).orElse(null));
        } else if (offset > 0 && !area.getText(offset - 1, offset).equals("\n")) {
            b = local(area.getCharacterBoundsOnScreen(offset - 1, offset).orElse(null));
            leftEdge = false;
        }
        if (b == null) {
            return; // an empty line: nothing to measure against, and the caret itself will show it
        }
        double x = Math.max(1.5, Math.round(leftEdge ? b.getMinX() : b.getMaxX()) + 0.5); // not under the edge
        double top = Math.round(b.getMinY()) + 1.5;
        double bottom = Math.round(b.getMaxY()) - 1.5;
        g.setLineDashes();
        g.setStroke(color);
        g.setLineWidth(kind == SnippetSession.MARK_ACTIVE ? 2 : 1);
        g.strokeLine(x, top, x, bottom);
        if (kind == SnippetSession.MARK_FINAL) { // $0: a small foot, so it reads as "the caret ends up here"
            g.strokeLine(x - 2, bottom, x + 2, bottom);
        }
    }

    private Bounds local(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
