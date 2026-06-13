package com.editora.editor;

import java.util.List;
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
 * IntelliJ-style inline debugger values: while execution is suspended, grey italic
 * {@code name: value} annotations are painted after the text of each visible line that mentions a
 * variable of the suspended frame. A mouse-transparent {@link Canvas} mirroring
 * {@link WhitespaceOverlay}: only visible paragraphs are scanned (via the pure
 * {@link DebugIdentifiers#matchesIn}), redraws coalesce to one per pulse, and when no values are set
 * the canvas is 1×1 and invisible — zero cost outside a paused debug session.
 */
final class InlineValuesOverlay extends Region {

    private static final Color VALUE_COLOR = Color.web("#808a93");
    private static final int MAX_PER_LINE = 3;
    private static final int MAX_VALUE_CHARS = 60;

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    /** Variable name → rendered value for the suspended frame; null = inactive. */
    private Map<String, String> values;

    private boolean redrawPending;
    private Font font = Font.font("monospace", FontPosture.ITALIC, 14);

    InlineValuesOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("inline-values-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRedraw());
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        area.estimatedScrollXProperty().addListener((o, a, b) -> scheduleRedraw());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
    }

    /** Sets the suspended frame's variables (null or empty clears and releases the canvas). */
    void setValues(Map<String, String> v) {
        this.values = (v == null || v.isEmpty()) ? null : Map.copyOf(v);
        boolean active = values != null;
        setVisible(active);
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            clear();
            canvas.setWidth(1); // release the full-viewport texture while inactive
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
        if (values == null) {
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
        if (values == null || redrawPending) {
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
        Map<String, String> vals = values;
        if (vals == null || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            g.setFill(VALUE_COLOR);
            g.setFont(font);
            g.setTextBaseline(VPos.CENTER);
            g.setTextAlign(TextAlignment.LEFT);
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                if (line.isEmpty()) {
                    continue;
                }
                List<String> names = DebugIdentifiers.matchesIn(line, vals.keySet());
                if (names.isEmpty()) {
                    continue;
                }
                int lastChar = line.length() - 1;
                int abs = area.getAbsolutePosition(p, lastChar);
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
                g.fillText(annotation(names, vals), x, b.getMinY() + b.getHeight() / 2);
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout — skip this frame; a later event redraws.
        }
    }

    /** {@code name: value  name2: value2} for up to {@link #MAX_PER_LINE} names, values capped. */
    private static String annotation(List<String> names, Map<String, String> vals) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown == MAX_PER_LINE) {
                sb.append("  …");
                break;
            }
            String v = vals.get(name);
            if (v == null) {
                continue;
            }
            v = v.replace('\n', ' ');
            if (v.length() > MAX_VALUE_CHARS) {
                v = v.substring(0, MAX_VALUE_CHARS) + "…";
            }
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(name).append(": ").append(v);
            shown++;
        }
        return sb.toString();
    }
}
