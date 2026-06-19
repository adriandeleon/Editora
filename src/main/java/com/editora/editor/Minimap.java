package com.editora.editor;

import java.time.Duration;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;

/**
 * A lightweight document overview ("minimap") drawn beside the editor. Each paragraph is rendered
 * as scaled-down blocks representing its non-whitespace runs, with a translucent rectangle marking
 * the currently visible viewport. Clicking or dragging scrolls the editor to that position.
 *
 * <p>The (relatively expensive) content rendering is cached as an image and only regenerated when
 * the text or size changes; scrolling just re-blits the cached image plus the viewport rectangle.
 */
final class Minimap extends Region {

    /** Fixed width of the minimap column, in pixels. */
    static final double WIDTH = 90;

    /** Horizontal scale: assume ~110 columns map across the full width. */
    private static final double CHAR_SCALE = WIDTH / 110.0;
    /** Max vertical pixels per document line. Caps short files so they fill from the top rather than
     * stretching one line across a huge slice; long files compress below this to fit the column. */
    private static final double MAX_ROW_HEIGHT = 3.0;

    /** Block and viewport-overlay colors; theme-aware (see {@link #setColors}). */
    private Color textColor = Color.web("#9aa5b1");

    private Color viewportColor = Color.web("#0969da", 0.14);

    private final CodeArea area;
    private final Canvas canvas = new Canvas(WIDTH, 1);
    private WritableImage contentImage;
    /** Canvas logical dims at the last successful snapshot, so the {@link #contentImage} buffer can be
     *  reused (rather than reallocated each render) when the size is unchanged — see {@link #renderContent}. */
    private double lastSnapW = -1;

    private double lastSnapH = -1;
    /** False while this minimap's buffer is a background (non-selected) tab: rendering is skipped and
     *  the cached snapshot is dropped so its GPU texture can be reclaimed — keeps retained VRAM from
     *  scaling with the number of open files. */
    private boolean renderingActive = true;
    /** Visual width of a tab character, in columns. */
    private int tabSize = 4;
    /** LSP diagnostics drawn as colored stripes on the right edge (IntelliJ-style); never cached. */
    private java.util.List<LspDiagnostic> diagnostics = java.util.List.of();
    /** Gate for the diagnostic stripes: only drawn when LSP is active for this buffer. */
    private boolean diagnosticsEnabled;
    /** TODO/highlight matches drawn as colored stripes on the LEFT edge (so they never clash with the
     *  right-edge diagnostics); never cached. */
    private java.util.List<TodoMark> todoMarks = java.util.List.of();

    private boolean todoEnabled;

    /** Markdown-lint warnings drawn as right-edge stripes (Markdown buffers have no LSP diagnostics, so
     *  the right edge is free); never cached. */
    private java.util.List<MarkdownLint.Diagnostic> lintMarks = java.util.List.of();

    private boolean lintEnabled;

    private static final Color ERROR_STRIPE = Color.web("#e5484d");
    private static final Color WARNING_STRIPE = Color.web("#e2a03f");
    private static final Color INFO_STRIPE = Color.web("#4c8eda");
    private static final double STRIPE_WIDTH = 5;

    Minimap(CodeArea area) {
        this.area = area;
        getStyleClass().add("minimap");
        getChildren().add(canvas);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);

        area.multiPlainChanges().successionEnds(Duration.ofMillis(200)).subscribe(ignore -> renderContent());
        area.estimatedScrollYProperty().addListener((o, a, b) -> redraw());

        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::scrollToEvent);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::scrollToEvent);
    }

    /** Sets the visual tab width (columns) and re-renders if it changed. */
    void setTabSize(int tabSize) {
        if (tabSize > 0 && tabSize != this.tabSize) {
            this.tabSize = tabSize;
            renderContent();
        }
    }

    /** Sets the document-block and viewport-overlay colors (theme-aware) and re-renders. */
    void setColors(Color text, Color viewport) {
        this.textColor = text;
        this.viewportColor = viewport;
        renderContent();
    }

    /** Forces a re-render (e.g. after layout/theme settle at startup, when the first render may have
     *  run before the canvas was sized). */
    void refresh() {
        renderContent();
    }

    /** Sets the LSP diagnostics drawn as right-edge severity stripes; a cheap stripe-only repaint. */
    void setDiagnostics(java.util.List<LspDiagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? java.util.List.of() : diagnostics;
        repaintStripes();
    }

    /** Enables/disables the diagnostic stripes (driven by LSP-active for this buffer); a cheap repaint. */
    void setDiagnosticsEnabled(boolean enabled) {
        if (this.diagnosticsEnabled == enabled) {
            return;
        }
        this.diagnosticsEnabled = enabled;
        repaintStripes();
    }

    /** Sets the TODO/highlight matches drawn as left-edge stripes; a cheap stripe-only repaint. */
    void setTodoMarks(java.util.List<TodoMark> marks) {
        this.todoMarks = marks == null ? java.util.List.of() : marks;
        repaintStripes();
    }

    /** Enables/disables the TODO stripes (driven by TODO-highlight-on for this buffer); a cheap repaint. */
    void setTodoEnabled(boolean enabled) {
        if (this.todoEnabled == enabled) {
            return;
        }
        this.todoEnabled = enabled;
        repaintStripes();
    }

    /** Sets the Markdown-lint warnings drawn as right-edge stripes; a cheap stripe-only repaint. */
    void setLintMarks(java.util.List<MarkdownLint.Diagnostic> marks) {
        this.lintMarks = marks == null ? java.util.List.of() : marks;
        repaintStripes();
    }

    /** Enables/disables the lint stripes (driven by Markdown-lint-on for this buffer); a cheap repaint. */
    void setLintEnabled(boolean enabled) {
        if (this.lintEnabled == enabled) {
            return;
        }
        this.lintEnabled = enabled;
        repaintStripes();
    }

    /**
     * Repaints just the stripes over the already-cached content image. Crucially, this never forces a
     * {@link #renderContent()} (which calls {@code snapshot()} — a synchronous full-scene layout): when
     * there is no cached image yet (early startup, before the editor's first paint), it does nothing and
     * lets the minimap's normal layout-driven first render draw the stripes. Forcing a snapshot at that
     * point blanks the editor surface until the next relayout.
     */
    private void repaintStripes() {
        if (contentImage != null) {
            redraw();
        }
    }

    /**
     * Marks whether this minimap's buffer is the active (visible) tab. A background tab drops the
     * cached snapshot (a pinned GPU texture) and stops rendering; the minimap regenerates when the tab
     * is shown again. This is what keeps retained VRAM from growing with the number of open files.
     */
    void setRenderingActive(boolean active) {
        if (this.renderingActive == active) {
            return;
        }
        this.renderingActive = active;
        if (active) {
            renderContent();
        } else {
            contentImage = null;
            canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        }
    }

    @Override
    protected void layoutChildren() {
        double w = CanvasGuards.clampDim(getWidth());
        double h = CanvasGuards.clampDim(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            renderContent();
        }
        canvas.relocate(0, 0);
    }

    private void scrollToEvent(MouseEvent e) {
        int total = area.getParagraphs().size();
        if (total == 0 || getHeight() <= 0) {
            return;
        }
        double fraction = Math.max(0, Math.min(1, e.getY() / getHeight()));
        area.showParagraphAtTop((int) Math.round(fraction * (total - 1)));
    }

    /** Renders the document content into the canvas, caches it, then draws the viewport on top. */
    private void renderContent() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        // Hold the prior buffer so a same-size re-render can reuse it (see the snapshot call below)
        // instead of allocating a fresh WritableImage; cleared if we bail out before snapshotting.
        WritableImage prior = contentImage;
        contentImage = null;
        if (!renderingActive || !isVisible() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        int total = area.getParagraphs().size();
        if (total == 0) {
            return;
        }
        double rowHeight = rowHeight(h, total);
        double blockH = Math.max(0.75, Math.min(rowHeight * 0.8, 2.0));
        g.setFill(textColor);
        for (int i = 0; i < total; i++) {
            if (area.getParagraphLength(i) == 0) {
                continue; // blank line: nothing to draw, and skip building its (empty) text string
            }
            drawRuns(g, area.getParagraph(i).getText(), i * rowHeight, blockH, w);
        }
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        // Reuse the previous frame's image buffer when the canvas size is unchanged (the common case:
        // a content re-render on edit), instead of allocating a fresh WritableImage every render.
        WritableImage reuse = (prior != null && w == lastSnapW && h == lastSnapH) ? prior : null;
        try {
            contentImage = canvas.snapshot(sp, reuse);
            lastSnapW = w;
            lastSnapH = h;
        } catch (RuntimeException ignored) {
            // snapshot() forces a synchronous full-scene layout pass; during early startup the
            // CodeArea's VirtualFlow may have no visible cell yet, and Flowless throws
            // "Cell 0 is not visible". Leave the cache empty — a later render (layout settle,
            // refresh(), or the next edit) re-caches once a cell is laid out.
            contentImage = null;
        }
        drawViewport(g, w, h, total, rowHeight);
        drawDiagnosticStripes(g, w, h, total, rowHeight);
        drawLintStripes(g, w, h, total, rowHeight);
        drawTodoStripes(g, h, total, rowHeight);
    }

    /** Cheap redraw on scroll: re-blit the cached content image and draw the viewport rectangle. */
    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (!renderingActive || !isVisible() || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        int total = area.getParagraphs().size();
        if (total == 0) {
            return;
        }
        if (contentImage == null) {
            // No cached content yet — e.g. the first renderContent()'s snapshot lost the startup
            // layout race. Regenerate (draws the runs and retries the cache) instead of leaving the
            // minimap blank with only the viewport box.
            renderContent();
            return;
        }
        g.drawImage(contentImage, 0, 0);
        double rowHeight = rowHeight(h, total);
        drawViewport(g, w, h, total, rowHeight);
        drawDiagnosticStripes(g, w, h, total, rowHeight);
        drawLintStripes(g, w, h, total, rowHeight);
        drawTodoStripes(g, h, total, rowHeight);
    }

    /** Vertical pixels per line: a fixed size, but compressed to fit when the document is long. */
    private static double rowHeight(double h, int total) {
        return Math.min(MAX_ROW_HEIGHT, h / total);
    }

    private void drawViewport(GraphicsContext g, double w, double h, int total, double rowHeight) {
        try {
            int first = clamp(area.firstVisibleParToAllParIndex(), total);
            int last = clamp(area.lastVisibleParToAllParIndex(), total);
            double vy = first * rowHeight;
            double vh = Math.max(rowHeight, (last - first + 1) * rowHeight);
            g.setFill(viewportColor);
            g.fillRect(0, vy, w, vh);
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet (e.g. before first render) — skip the indicator.
        }
    }

    /**
     * Draws IntelliJ-style severity stripes on the right edge — one per diagnostic line, at the same
     * vertical position the line maps to. Info/warning first, errors last so errors sit on top.
     */
    private void drawDiagnosticStripes(GraphicsContext g, double w, double h, int total, double rowHeight) {
        if (!diagnosticsEnabled || diagnostics.isEmpty() || total == 0) {
            return;
        }
        double x = w - STRIPE_WIDTH;
        double markH = Math.max(2.0, rowHeight);
        for (LspDiagnostic.Severity sev : new LspDiagnostic.Severity[] {
            LspDiagnostic.Severity.HINT, LspDiagnostic.Severity.INFO,
            LspDiagnostic.Severity.WARNING, LspDiagnostic.Severity.ERROR
        }) {
            g.setFill(stripeColor(sev));
            for (LspDiagnostic d : diagnostics) {
                if (d.severity() != sev) {
                    continue;
                }
                double y = clamp(d.startLine(), total) * rowHeight;
                g.fillRect(x, Math.min(y, h - markH), STRIPE_WIDTH, markH);
            }
        }
    }

    private static Color stripeColor(LspDiagnostic.Severity sev) {
        return switch (sev) {
            case ERROR -> ERROR_STRIPE;
            case WARNING -> WARNING_STRIPE;
            default -> INFO_STRIPE;
        };
    }

    /** Draws Markdown-lint warnings as amber stripes on the right edge (no LSP diagnostics on Markdown). */
    private void drawLintStripes(GraphicsContext g, double w, double h, int total, double rowHeight) {
        if (!lintEnabled || lintMarks.isEmpty() || total == 0) {
            return;
        }
        double x = w - STRIPE_WIDTH;
        double markH = Math.max(2.0, rowHeight);
        g.setFill(WARNING_STRIPE);
        for (MarkdownLint.Diagnostic d : lintMarks) {
            double y = clamp(d.line() - 1, total) * rowHeight;
            g.fillRect(x, Math.min(y, h - markH), STRIPE_WIDTH, markH);
        }
    }

    /** Draws TODO/highlight matches as colored stripes on the LEFT edge (each in its pattern's color). */
    private void drawTodoStripes(GraphicsContext g, double h, int total, double rowHeight) {
        if (!todoEnabled || todoMarks.isEmpty() || total == 0) {
            return;
        }
        double markH = Math.max(2.0, rowHeight);
        for (TodoMark m : todoMarks) {
            try {
                g.setFill(Color.web(m.colorWeb()));
            } catch (RuntimeException e) {
                g.setFill(Color.web("#E5C07B"));
            }
            double y = clamp(m.line(), total) * rowHeight;
            g.fillRect(0, Math.min(y, h - markH), STRIPE_WIDTH, markH);
        }
    }

    /** Draws a block for each contiguous run of non-whitespace characters in {@code text}. */
    private void drawRuns(GraphicsContext g, String text, double y, double blockH, double w) {
        int n = text.length();
        int col = 0;
        int runStart = -1;
        for (int i = 0; i <= n; i++) {
            boolean whitespace = i == n || Character.isWhitespace(text.charAt(i));
            if (!whitespace && runStart < 0) {
                runStart = col;
            } else if (whitespace && runStart >= 0) {
                double x = runStart * CHAR_SCALE;
                double width = Math.min(w - x, (col - runStart) * CHAR_SCALE);
                if (x < w && width > 0) {
                    g.fillRect(x, y, width, blockH);
                }
                runStart = -1;
            }
            if (i < n) {
                col += text.charAt(i) == '\t' ? tabSize : 1;
            }
        }
    }

    private static int clamp(int idx, int total) {
        if (idx < 0) {
            return 0;
        }
        return Math.min(idx, total - 1);
    }
}
