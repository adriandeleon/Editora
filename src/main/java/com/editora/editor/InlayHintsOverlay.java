package com.editora.editor;

import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.TextAlignment;

import org.fxmisc.richtext.CodeArea;

/**
 * LSP inlay hints (#681), rendered as grey italic annotations <b>after each line's text</b> — the
 * per-line aggregate of the server's hints (parameter names, inferred types). A structural clone of
 * {@link InlineValuesOverlay}: mouse-transparent {@link Canvas}, coalesced redraws, visible paragraphs
 * only, 1×1 texture while empty. End-of-line placement is deliberate: a mid-line overlay would overdraw
 * the following glyphs, and truly inline hints require document-model segments (deferred).
 */
final class InlayHintsOverlay extends Region {

    private static final Color HINT_COLOR = Color.web("#808a93");
    private static final int MAX_LINE_CHARS = 120;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    /** 0-based line → the aggregated hint annotation for that line; null = inactive. */
    private Map<Integer, String> byLine;

    private boolean redrawPending;
    private Font font = Font.font("monospace", FontPosture.ITALIC, 13);

    InlayHintsOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("inlay-hints-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** Sets the per-line hint annotations (null or empty clears + releases the canvas). */
    void setHints(Map<Integer, String> hints) {
        this.byLine = (hints == null || hints.isEmpty()) ? null : Map.copyOf(hints);
        boolean active = byLine != null;
        setVisible(active);
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.setWidth(1); // release the full-viewport texture while inactive
            canvas.setHeight(1);
        }
    }

    /** Keeps the hint font in step with the editor font (italic, slightly smaller). */
    void setFont(String family, int size) {
        this.font = Font.font(family, FontPosture.ITALIC, Math.max(9, size - 1));
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (byLine == null) {
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
        if (byLine == null || redrawPending) {
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
        Map<Integer, String> hints = byLine;
        if (hints == null || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            g.setFill(HINT_COLOR);
            g.setFont(font);
            g.setTextBaseline(VPos.CENTER);
            g.setTextAlign(TextAlignment.LEFT);
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                String annotation = hints.get(p);
                if (annotation == null || annotation.isBlank()) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                if (line.isEmpty()) {
                    continue; // no anchor glyph to place after
                }
                int abs = area.getAbsolutePosition(p, line.length() - 1);
                Bounds b = area.getCharacterBoundsOnScreen(abs, abs + 1)
                        .map(canvas::screenToLocal)
                        .orElse(null);
                if (b == null) {
                    continue;
                }
                double x = b.getMaxX() + 16;
                if (x < 0 || x > w) {
                    continue;
                }
                String text = annotation.length() > MAX_LINE_CHARS
                        ? annotation.substring(0, MAX_LINE_CHARS) + "…"
                        : annotation;
                g.fillText(text, x, b.getMinY() + b.getHeight() / 2);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event redraws.
        }
    }
}
