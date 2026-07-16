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
 * Highlights configured TODO/FIXME-style pattern matches behind the editor text, without touching the
 * document's style spans (so it never fights the syntax highlighter). Each match is colored per structural
 * part of the {@code KEYWORD [tag] (priority) description} comment: the keyword in its pattern color, the
 * {@code [tag]} in the tag color (with an underline), and the {@code (priority)} in its level color; the
 * description keeps the normal text color. A mouse-transparent {@link Canvas} modeled on
 * {@link SearchHighlightOverlay}: sized to the viewport, redrawn coalesced (one per pulse) on scroll / edit /
 * resize / fold, only for the visible paragraphs, and dropped to 1x1 (no GPU texture) when there are no
 * matches. Driven by {@link #setMarks}.
 */
final class TodoHighlightOverlay extends Region {

    /** Opacity of the highlight wash drawn over each part (the part color provides the hue). */
    private static final double WASH_ALPHA = 0.30;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<TodoMark> marks = List.of();
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
    void setMarks(List<TodoMark> newMarks) {
        List<TodoMark> valid = new ArrayList<>();
        if (newMarks != null) {
            for (TodoMark m : newMarks) {
                if (m != null && m.end() > m.start()) {
                    valid.add(m);
                }
            }
        }
        this.marks = valid;
        boolean show = !valid.isEmpty();
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

    private static Color solidColor(String web) {
        try {
            return Color.web(web);
        } catch (RuntimeException ignored) {
            return Color.web("#E5C07B");
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
            int len = area.getLength();
            for (TodoMark m : marks) {
                int spanEnd = Math.max(m.end(), Math.max(m.tagEnd(), m.priEnd()));
                if (spanEnd < firstOffset || m.start() > lastOffset) {
                    continue; // off-screen — cheap reject before the costly offset→position conversion
                }
                if (spanEnd > len) {
                    continue; // stale: an edit shrank the document since the marks were computed
                }
                // Guard per mark, not per frame. Marks refresh on the 300 ms pulse but we repaint on every
                // edit, so one mark left past the end used to throw out of the whole loop — blanking every
                // remaining highlight until the next pulse.
                try {
                    paintSpan(g, m.start(), m.end(), washColor(m.colorWeb()), null, first, last, w, h);
                    if (m.hasTag()) {
                        paintSpan(
                                g,
                                m.tagStart(),
                                m.tagEnd(),
                                washColor(m.tagColorWeb()),
                                solidColor(m.tagColorWeb()),
                                first,
                                last,
                                w,
                                h);
                    }
                    if (m.hasPriority() && m.priColorWeb() != null) {
                        paintSpan(g, m.priStart(), m.priEnd(), washColor(m.priColorWeb()), null, first, last, w, h);
                    }
                } catch (RuntimeException ignored) {
                    // This mark no longer maps to a position — skip it, keep painting the rest.
                }
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event will redraw.
        }
    }

    /** Paints a wash over {@code [start,end)}; when {@code underline} is non-null, also strokes a 1px line
     *  along the bottom of each covered line in that (opaque) color (used to underline the tag). */
    private void paintSpan(
            GraphicsContext g,
            int start,
            int end,
            Color color,
            Color underline,
            int first,
            int last,
            double w,
            double h) {
        if (end <= start) {
            return;
        }
        var startPos = area.offsetToPosition(start, Bias.Forward);
        var endPos = area.offsetToPosition(Math.max(end, start), Bias.Backward);
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
            g.setFill(color);
            g.fillRect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
            if (underline != null) {
                g.setStroke(underline);
                g.setLineWidth(1);
                double y = Math.floor(b.getMaxY()) - 0.5;
                g.strokeLine(b.getMinX(), y, b.getMaxX(), y);
            }
        }
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
