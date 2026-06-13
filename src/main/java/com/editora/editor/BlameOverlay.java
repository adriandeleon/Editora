package com.editora.editor;

import java.util.List;

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
 * GitLens-style inline blame: a faint italic annotation ("author, 3 days ago • summary") painted after
 * the text of the <b>caret line only</b>, following the caret. A mouse-transparent {@link Canvas}
 * mirroring {@link InlineValuesOverlay}: it holds the whole-file per-line blame but renders just the
 * current paragraph, redraws coalesce to one per pulse, and the canvas drops to 1×1 (invisible) when
 * blame is off — zero cost when disabled (the default). The per-line list is supplied already formatted
 * and localized by the controller, so this class stays git-free.
 */
final class BlameOverlay extends Region {

    private static final Color COLOR = Color.web("#98a0a8");

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    /** Per 0-based line; {@code null} = inactive (blame off or not yet loaded). */
    private List<BlameInfo> blame;

    private boolean redrawPending;
    private Font font = Font.font("monospace", FontPosture.ITALIC, 14);

    BlameOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("blame-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.caretPositionProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** Sets the per-line blame (null/empty clears and releases the canvas). */
    void setBlame(List<BlameInfo> b) {
        this.blame = (b == null || b.isEmpty()) ? null : List.copyOf(b);
        boolean active = blame != null;
        setVisible(active);
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            clear();
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
    }

    /** Keeps the annotation font in step with the editor font (italic variant). */
    void setFont(String family, int size) {
        this.font = Font.font(family, FontPosture.ITALIC, size);
        scheduleRedraw();
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (blame == null) {
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
        if (blame == null || redrawPending) {
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
        List<BlameInfo> lines = blame;
        if (lines == null || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int p = area.getCurrentParagraph();
            if (p < 0 || p >= lines.size() || p >= area.getParagraphs().size()) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(area.getParagraphs().size() - 1, area.lastVisibleParToAllParIndex());
            if (p < first || p > last || area.isFolded(p)) {
                return;
            }
            BlameInfo info = lines.get(p);
            if (info == null || info.text().isBlank()) {
                return;
            }
            String line = area.getParagraph(p).getText();
            if (line.isEmpty()) {
                return; // nothing to anchor to on an empty line
            }
            int lastChar = line.length() - 1;
            int abs = area.getAbsolutePosition(p, lastChar);
            Bounds b = area.getCharacterBoundsOnScreen(abs, abs + 1)
                    .map(canvas::screenToLocal)
                    .orElse(null);
            if (b == null) {
                return;
            }
            double x = b.getMaxX() + 24;
            if (x < 0 || x > w) {
                return;
            }
            g.setFill(COLOR);
            g.setFont(font);
            g.setTextBaseline(VPos.CENTER);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(info.text(), x, b.getMinY() + b.getHeight() / 2);
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event redraws.
        }
    }
}
