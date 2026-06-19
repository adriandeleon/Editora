package com.editora.editor;

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
 * A thin Markdown-lint overview stripe docked over the editor's vertical scrollbar (beside the TODO
 * {@link TodoStripe} / LSP {@link DiagnosticStripe}), mapping the <em>whole document</em> to its height
 * so a glance shows where the lint warnings are — IntelliJ-style. Each mark is a single amber warning
 * tick; hovering shows the rule code + message and clicking jumps the caret there (via an injected
 * {@code onActivate}). Coalesced redraw + a 1×1 backing texture while inactive — the same
 * {@link CanvasGuards}/overlay discipline as {@link TodoStripe}. Diagnostics carry 1-based lines.
 */
final class MarkdownLintStripe extends Region {

    static final double WIDTH = 6;
    private static final double HIT_PAD = 3;
    private static final Color WARNING = Color.web("#e2a03f");

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<MarkdownLint.Diagnostic> marks = List.of();
    private boolean active;
    private boolean redrawPending;
    private Tooltip tooltip;
    private String shownText;
    private IntConsumer onActivate = line -> {};

    MarkdownLintStripe(CodeArea area) {
        this.area = area;
        getStyleClass().add("markdown-lint-stripe");
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

    /** Shows/hides the stripe (driven by Markdown-lint-on for this buffer). */
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

    void setDiagnostics(List<MarkdownLint.Diagnostic> diagnostics) {
        this.marks = diagnostics == null ? List.of() : diagnostics;
        setMouseTransparent(!active || marks.isEmpty());
        requestLayout();
        scheduleRedraw();
    }

    void setOnActivate(IntConsumer onActivate) {
        this.onActivate = onActivate == null ? line -> {} : onActivate;
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
        g.setFill(WARNING);
        for (MarkdownLint.Diagnostic d : marks) {
            double y = yForLine(d.line() - 1, h, total);
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

    private MarkdownLint.Diagnostic hitAt(double localY) {
        int total = area.getParagraphs().size();
        if (total == 0) {
            return null;
        }
        double h = canvas.getHeight();
        double markH = markHeight(h, total);
        for (MarkdownLint.Diagnostic d : marks) {
            double y = Math.min(yForLine(d.line() - 1, h, total), h - markH);
            if (localY >= y - HIT_PAD && localY <= y + markH + HIT_PAD) {
                return d;
            }
        }
        return null;
    }

    private void onHover(MouseEvent e) {
        if (!active || marks.isEmpty()) {
            hideTooltip();
            return;
        }
        MarkdownLint.Diagnostic hit = hitAt(e.getY());
        if (hit == null) {
            setCursor(Cursor.DEFAULT);
            hideTooltip();
            return;
        }
        setCursor(Cursor.HAND);
        String text = hit.code() + ": " + hit.message();
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
        MarkdownLint.Diagnostic hit = hitAt(e.getY());
        if (hit != null) {
            onActivate.accept(hit.line() - 1);
        }
    }

    private void hideTooltip() {
        if (tooltip != null) {
            tooltip.hide();
        }
        shownText = null;
    }
}
