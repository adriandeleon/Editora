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
 * A thin severity stripe docked over the editor's vertical scrollbar, mapping the <em>whole document</em>
 * to its height so a glance shows where the errors and warnings are — IntelliJ-style. Shown whenever LSP
 * is active for the buffer (independent of the minimap); {@link EditorBuffer} positions it over the
 * scrollbar (far-right edge when the minimap is hidden, just inside the minimap when it is shown).
 *
 * <p>Interactive: hovering a mark shows the diagnostic message in a tooltip and clicking jumps the caret
 * to that line (via an injected {@code onActivate}). Coalesced redraw, and a 1×1 backing texture while
 * inactive — the same {@link CanvasGuards}/overlay discipline as {@link LspDiagnosticOverlay}. Diagnostics
 * use 0-based lines (LSP convention) and are pushed in by {@link EditorBuffer}.
 */
final class DiagnosticStripe extends Region {

    /** Width of the stripe column, in pixels (sits over the right edge of the scrollbar). */
    static final double WIDTH = 6;
    /** Vertical tolerance (px) added around a mark so thin ticks are still easy to hover/click. */
    private static final double HIT_PAD = 3;

    private static final Color ERROR = Color.web("#e5484d");
    private static final Color WARNING = Color.web("#e2a03f");
    private static final Color INFO = Color.web("#4c8eda");

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private List<LspDiagnostic> diagnostics = List.of();
    private boolean active;
    private boolean redrawPending;
    private Tooltip tooltip;
    /** The message currently shown by {@link #tooltip}; lets a repeated hover over the same mark skip a
     *  re-{@code show()} that would otherwise re-position the popup on every mouse-move (flicker). */
    private String shownText;
    /** Called with a 0-based line when a mark is clicked; injected by {@link EditorBuffer}. */
    private IntConsumer onActivate = line -> {};

    DiagnosticStripe(CodeArea area) {
        this.area = area;
        getStyleClass().add("diagnostic-stripe");
        // Mouse-transparent until there are marks, so the scrollbar underneath stays fully usable when
        // the stripe is empty; only when it carries marks does it capture hover/click.
        setMouseTransparent(true);
        setVisible(false);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        getChildren().add(canvas);
        // Redraw on content edits (paragraph count shifts the line→y mapping). We deliberately do NOT
        // listen to totalHeightEstimateProperty: adding a listener makes RichTextFX compute that estimate
        // eagerly on every viewport/layout change, churning the FX thread and starving repaints. We read
        // it lazily at draw time instead — by the time diagnostics arrive the estimate has settled, and a
        // resize re-draws via layoutChildren.
        area.multiPlainChanges().subscribe(ignore -> scheduleRedraw());
        addEventHandler(MouseEvent.MOUSE_MOVED, this::onHover);
        addEventHandler(MouseEvent.MOUSE_EXITED, e -> hideTooltip());
        addEventHandler(MouseEvent.MOUSE_CLICKED, this::onClick);
    }

    /** Shows/hides the stripe and draws, or releases its texture. */
    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        setVisible(active);
        setMouseTransparent(!active || diagnostics.isEmpty());
        if (active) {
            requestLayout();
            scheduleRedraw();
        } else {
            hideTooltip();
            clear();
            releaseTexture();
        }
    }

    void setDiagnostics(List<LspDiagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
        // Only intercept mouse events (over the scrollbar) when there are marks to hover/click.
        setMouseTransparent(!active || this.diagnostics.isEmpty());
        requestLayout(); // grow the canvas when marks appear / shrink it to 1x1 when they're gone
        scheduleRedraw();
    }

    /** True only when the stripe should hold a real backing texture (active + has marks to draw). */
    private boolean shouldPaint() {
        return active && !diagnostics.isEmpty();
    }

    /** Injects the click-to-jump action (0-based line); null disables jumping. */
    void setOnActivate(IntConsumer onActivate) {
        this.onActivate = onActivate == null ? line -> {} : onActivate;
    }

    @Override
    protected void layoutChildren() {
        canvas.relocate(0, 0);
        if (!shouldPaint()) {
            releaseTexture(); // no marks → hold no full-height texture (GPU pool hygiene)
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
        // Info/warning first, errors last so the most severe marks sit on top.
        for (LspDiagnostic.Severity sev : new LspDiagnostic.Severity[] {
            LspDiagnostic.Severity.HINT, LspDiagnostic.Severity.INFO,
            LspDiagnostic.Severity.WARNING, LspDiagnostic.Severity.ERROR
        }) {
            g.setFill(color(sev));
            for (LspDiagnostic d : diagnostics) {
                if (d.severity() != sev) {
                    continue;
                }
                double y = yForLine(d.startLine(), h, total);
                g.fillRect(0, Math.min(y, h - markH), w, markH);
            }
        }
    }

    /**
     * Maps a 0-based document line to the stripe's vertical position. When the document fits the viewport
     * the marks land at their real on-screen line position (1:1, blank below); when it's taller, the whole
     * document compresses into the stripe (true scrollbar overview). Mirrors the editor's content metrics
     * (total pixel height ÷ paragraph count = line height; scale = h / max(content, viewport)).
     */
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

    /** Diagnostics whose mark covers the given local y (with a small tolerance), most-severe first. */
    private List<LspDiagnostic> hitsAt(double localY) {
        List<LspDiagnostic> hits = new ArrayList<>();
        int total = area.getParagraphs().size();
        if (total == 0) {
            return hits;
        }
        double h = canvas.getHeight();
        double markH = markHeight(h, total);
        for (LspDiagnostic.Severity sev : new LspDiagnostic.Severity[] {
            LspDiagnostic.Severity.ERROR, LspDiagnostic.Severity.WARNING,
            LspDiagnostic.Severity.INFO, LspDiagnostic.Severity.HINT
        }) {
            for (LspDiagnostic d : diagnostics) {
                if (d.severity() != sev) {
                    continue;
                }
                double y = Math.min(yForLine(d.startLine(), h, total), h - markH);
                if (localY >= y - HIT_PAD && localY <= y + markH + HIT_PAD) {
                    hits.add(d);
                }
            }
        }
        return hits;
    }

    private void onHover(MouseEvent e) {
        if (!active || diagnostics.isEmpty()) {
            hideTooltip();
            return;
        }
        List<LspDiagnostic> hits = hitsAt(e.getY());
        if (hits.isEmpty()) {
            setCursor(Cursor.DEFAULT);
            hideTooltip();
            return;
        }
        setCursor(Cursor.HAND);
        StringBuilder sb = new StringBuilder();
        for (LspDiagnostic d : hits) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(d.message());
            String origin = d.origin();
            if (!origin.isEmpty()) {
                sb.append("  (").append(origin).append(')');
            }
        }
        String text = sb.toString();
        // Already showing this exact message → leave it where it is. Re-showing on every MOUSE_MOVED
        // re-positions the popup to the cursor each pixel, which reads as flicker/jank.
        if (tooltip != null && tooltip.isShowing() && text.equals(shownText)) {
            return;
        }
        if (tooltip == null) {
            tooltip = new Tooltip();
            tooltip.getStyleClass().add("lsp-diagnostic-tooltip");
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
        List<LspDiagnostic> hits = hitsAt(e.getY());
        if (!hits.isEmpty()) {
            onActivate.accept(hits.get(0).startLine());
        }
    }

    private void hideTooltip() {
        if (tooltip != null) {
            tooltip.hide();
        }
        shownText = null;
    }

    private static Color color(LspDiagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> ERROR;
            case WARNING -> WARNING;
            default -> INFO;
        };
    }
}
