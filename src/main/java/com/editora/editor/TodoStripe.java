package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;

/**
 * A thin TODO/highlight stripe docked over the editor's vertical scrollbar (beside the LSP
 * {@link DiagnosticStripe}), mapping the <em>whole document</em> to its height so a glance shows where
 * the TODO/FIXME matches are — IntelliJ-style. Each mark is drawn in its pattern's color; hovering shows
 * the line's text and clicking jumps the caret there (via an injected {@code onActivate}). Coalesced
 * redraw + a 1×1 backing texture while inactive — the same {@link CanvasGuards}/overlay discipline as
 * {@link DiagnosticStripe}, but generic (per-mark color, no severities). 0-based lines.
 */
final class TodoStripe extends Region {

    static final double WIDTH = 6;
    private static final double HIT_PAD = 3;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<TodoMark> marks = List.of();
    private List<Color> colors = List.of();
    private boolean active;
    private boolean redrawPending;
    private Tooltip tooltip;
    private String shownText;
    private IntConsumer onActivate = line -> {};

    TodoStripe(CodeArea area) {
        this.area = area;
        getStyleClass().add("todo-stripe");
        setMouseTransparent(true);
        setVisible(false);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        getChildren().add(canvas);
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        addEventHandler(MouseEvent.MOUSE_MOVED, this::onHover);
        addEventHandler(MouseEvent.MOUSE_EXITED, e -> hideTooltip());
        addEventHandler(MouseEvent.MOUSE_CLICKED, this::onClick);
    }

    /** Shows/hides the stripe (driven by TODO-highlight-on for this buffer). */
    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        setVisible(active);
        setMouseTransparent(!active || marks.isEmpty());
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            hideTooltip();
            clear();
            releaseTexture();
        }
    }

    void setMarks(List<TodoMark> marks) {
        List<TodoMark> m = new ArrayList<>();
        List<Color> c = new ArrayList<>();
        if (marks != null) {
            for (TodoMark mk : marks) {
                if (mk == null) {
                    continue;
                }
                m.add(mk);
                c.add(parse(mk.colorWeb()));
            }
        }
        this.marks = m;
        this.colors = c;
        setMouseTransparent(!active || m.isEmpty());
        requestLayout();
        scheduleRedraw();
    }

    void setOnActivate(IntConsumer onActivate) {
        this.onActivate = onActivate == null ? line -> {} : onActivate;
    }

    private static Color parse(String web) {
        try {
            return Color.web(web);
        } catch (RuntimeException e) {
            return Color.web("#E5C07B");
        }
    }

    private boolean shouldPaint() {
        return active && !marks.isEmpty();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!shouldPaint()) {
            releaseTexture();
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

    private void releaseTexture() {
        if (canvas.getWidth() != 1 || canvas.getHeight() != 1) {
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
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
        if (!shouldPaint() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            releaseTexture();
            return;
        }
        int total = area.getParagraphs().size();
        if (total == 0) {
            return;
        }
        double markH = markHeight(h, total);
        for (int i = 0; i < marks.size(); i++) {
            g.setFill(colors.get(i));
            double y = yForLine(marks.get(i).line(), h, total);
            g.fillRect(0, Math.min(y, h - markH), w, markH);
        }
    }

    private double yForLine(int line, double h, int total) {
        int clamped = Math.max(0, Math.min(line, total - 1));
        Double estimate = area.totalHeightEstimateProperty().getValue();
        double contentH = estimate == null ? 0 : estimate;
        if (contentH > 0) {
            double lineH = contentH / total;
            double scale = h / Math.max(contentH, h);
            return clamped * lineH * scale;
        }
        return (clamped / (double) total) * h;
    }

    private double markHeight(double h, int total) {
        Double estimate = area.totalHeightEstimateProperty().getValue();
        double contentH = estimate == null ? 0 : estimate;
        if (contentH > 0) {
            double lineH = contentH / total;
            double scale = h / Math.max(contentH, h);
            return Math.max(2.0, lineH * scale);
        }
        return Math.max(2.0, h / total);
    }

    private TodoMark hitAt(double localY) {
        int total = area.getParagraphs().size();
        if (total == 0) {
            return null;
        }
        double h = canvas.getHeight();
        double markH = markHeight(h, total);
        for (TodoMark mk : marks) {
            double y = Math.min(yForLine(mk.line(), h, total), h - markH);
            if (localY >= y - HIT_PAD && localY <= y + markH + HIT_PAD) {
                return mk;
            }
        }
        return null;
    }

    private void onHover(MouseEvent e) {
        if (!active || marks.isEmpty()) {
            hideTooltip();
            return;
        }
        TodoMark hit = hitAt(e.getY());
        if (hit == null) {
            setCursor(Cursor.DEFAULT);
            hideTooltip();
            return;
        }
        setCursor(Cursor.HAND);
        String text = hit.lineText() == null ? "" : hit.lineText().strip();
        if (text.isEmpty()) {
            return;
        }
        if (tooltip != null && tooltip.isShowing() && text.equals(shownText)) {
            return;
        }
        if (tooltip == null) {
            tooltip = new Tooltip();
            tooltip.getStyleClass().add("todo-stripe-tooltip");
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(480);
        }
        tooltip.setText(text);
        shownText = text;
        tooltip.show(this, e.getScreenX() - 12 - tooltip.getWidth(), e.getScreenY() + 14);
    }

    private void onClick(MouseEvent e) {
        if (!active) {
            return;
        }
        TodoMark hit = hitAt(e.getY());
        if (hit != null) {
            onActivate.accept(hit.line());
        }
    }

    private void hideTooltip() {
        if (tooltip != null) {
            tooltip.hide();
        }
        shownText = null;
    }
}
