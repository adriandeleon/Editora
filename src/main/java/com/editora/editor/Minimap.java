package com.editora.editor;

import java.time.Duration;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;

/**
 * A lightweight document overview ("minimap") drawn beside the editor. Each paragraph is rendered
 * as scaled-down blocks representing its non-whitespace runs, with a translucent rectangle marking
 * the currently visible viewport. Clicking jumps there, dragging the viewport box slides it, and the
 * mouse wheel over the column scrolls the editor exactly as it does over the text.
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
    /** Re-entrancy guard. {@link #renderContent}'s {@code canvas.snapshot()} forces a synchronous
     *  full-scene layout pass that can settle the editor's viewport and fire the
     *  {@code estimatedScrollYProperty} listener — re-entering {@link #redraw}/{@link #renderContent}
     *  mid-render (with {@link #contentImage} momentarily null), which would paint a second, stale
     *  viewport box over the in-progress frame. While a render is in flight, suppress nested paints;
     *  the in-progress render draws the single, up-to-date viewport when it completes. */
    private boolean painting;

    private boolean redrawPending;
    /** A content render is queued for this pulse; see {@link #renderContent}. */
    private boolean renderPending;
    /** False until the first content render has run, which is the one held back until after first paint. */
    private boolean firstRenderDone;
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
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());

        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::beginDrag);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::continueDrag);
        // On the Region rather than the canvas, so the wheel works over the whole column even in the
        // moments the canvas is smaller than it (see CanvasGuards / layoutChildren).
        addEventHandler(ScrollEvent.SCROLL, this::wheelScroll);
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

    /**
     * Maps a minimap column pixel back to a document scroll offset, in pixels. Pure.
     *
     * <p>The overview draws document line {@code i} at {@code i * rowHeight}, so a column pixel converts to
     * a document pixel by the ratio of the two line heights ({@code totalHeight / totalLines} in the editor,
     * {@code rowHeight} here). Returns the offset that puts the grabbed line at the <b>top</b> of the
     * viewport — the same anchoring {@code showParagraphAtTop} gave — but continuously.
     *
     * <p>Deliberately in document pixels rather than a paragraph index: a paragraph index quantises the
     * result to a whole line, which is what made a drag stair-step (see {@link #scrollToEvent}).
     *
     * @return the unclamped scroll offset, or -1 when the geometry isn't measurable yet
     */
    static double documentScrollY(double minimapY, double rowHeight, int totalLines, double totalHeight) {
        if (rowHeight <= 0 || totalLines <= 0 || totalHeight <= 0) {
            return -1;
        }
        return Math.max(0, minimapY) * (totalHeight / totalLines) / rowHeight;
    }

    /**
     * Scrolls the editor to the grabbed position — <b>continuously</b>, by setting the estimated scroll
     * offset in pixels rather than by jumping to a paragraph index.
     *
     * <p>{@code showParagraphAtTop} can only land on a line boundary, so a drag moved the document in whole
     * lines with stalls in between. Measured on this repo's {@code CLAUDE.md} (592 lines, 16 px per line, a
     * 900 px column): consecutive pixels of mouse travel produced deltas of {@code 16, 0, 16, 16, 0, …} —
     * roughly two thirds of a line per pixel, delivered as a full-line jump or nothing at all. That is the
     * choppiness; it is worst on a long file, where each column pixel covers more lines. Setting
     * {@code estimatedScrollY} instead gives {@code 10, 9, 10, 9, …} over the same travel.
     *
     * <p>Falls back to the paragraph jump only when the height estimate isn't available yet (before the
     * first layout), where an approximate landing beats not scrolling at all.
     */
    /**
     * Minimum height at which the viewport box can be grabbed, in column pixels. The box is as tall as the
     * viewport is <i>as a share of the document</i>, so on a long file it is a pixel or two — not something
     * a mouse can reliably land on, which is exactly the file where dragging it matters most.
     */
    private static final double MIN_GRAB_HEIGHT = 10;

    /** Whether a press at column pixel {@code y} grabbed the viewport box, allowing for {@link
     *  #MIN_GRAB_HEIGHT}. Pure. */
    static boolean withinBox(double y, double boxTop, double boxHeight) {
        double pad = Math.max(0, MIN_GRAB_HEIGHT - boxHeight) / 2;
        return y >= boxTop - pad && y <= boxTop + boxHeight + pad;
    }

    /**
     * Column-pixel offset from the viewport box's top to the point a drag grabbed it, so the box slides
     * under the cursor rather than teleporting its top there. Zero for a press <em>outside</em> the box,
     * which does jump — that press is a "go here", and the line clicked becomes the top of the viewport.
     */
    private double grabOffset;

    /** The viewport box as {@link #drawViewport} draws it: {@code {top, height}} in column pixels, or null
     *  when the viewport isn't laid out yet. Package-visible so the FX test can grab the box it draws. */
    double[] viewportBox() {
        int total = area.getParagraphs().size();
        if (total == 0) {
            return null;
        }
        double rowHeight = rowHeight(getHeight(), total);
        try {
            int first = clamp(area.firstVisibleParToAllParIndex(), total);
            int last = clamp(area.lastVisibleParToAllParIndex(), total);
            return new double[] {first * rowHeight, Math.max(rowHeight, (last - first + 1) * rowHeight)};
        } catch (RuntimeException ignored) {
            return null; // viewport not laid out yet (e.g. before first render)
        }
    }

    /** Grabs the viewport box if the press landed on it, else jumps to the pressed position. */
    private void beginDrag(MouseEvent e) {
        double y = Math.max(0, Math.min(getHeight(), e.getY()));
        double[] box = viewportBox();
        if (box != null && withinBox(y, box[0], box[1])) {
            // Grabbing the slider must not move the document: remember where it was taken hold of and
            // let the drag carry it from there. Jumping the box's top to the cursor (what a press used to
            // do unconditionally) threw the document half a screen the moment you touched the middle of it.
            grabOffset = y - box[0];
            return;
        }
        grabOffset = 0;
        scrollToBoxTop(y);
    }

    private void continueDrag(MouseEvent e) {
        scrollToBoxTop(Math.max(0, Math.min(getHeight(), e.getY())) - grabOffset);
    }

    /**
     * Scrolls so the viewport box's top sits at {@code boxTop} — <b>continuously</b>, by setting the
     * estimated scroll offset in pixels rather than by jumping to a paragraph index.
     *
     * <p>{@code showParagraphAtTop} can only land on a line boundary, so a drag moved the document in whole
     * lines with stalls in between. Measured on this repo's {@code CLAUDE.md} (592 lines, 16 px per line, a
     * 900 px column): consecutive pixels of mouse travel produced deltas of {@code 16, 0, 16, 16, 0, …} —
     * roughly two thirds of a line per pixel, delivered as a full-line jump or nothing at all. That is the
     * choppiness; it is worst on a long file, where each column pixel covers more lines. Setting
     * {@code estimatedScrollY} instead gives {@code 10, 9, 10, 9, …} over the same travel.
     *
     * <p>Falls back to the paragraph jump only when the height estimate isn't available yet (before the
     * first layout), where an approximate landing beats not scrolling at all.
     */
    private void scrollToBoxTop(double boxTop) {
        int total = area.getParagraphs().size();
        if (total == 0 || getHeight() <= 0) {
            return;
        }
        Double totalHeight = area.totalHeightEstimateProperty().getValue();
        double scrollY =
                totalHeight == null ? -1 : documentScrollY(boxTop, rowHeight(getHeight(), total), total, totalHeight);
        if (scrollY < 0) {
            double fraction = Math.max(0, Math.min(1, boxTop / getHeight()));
            area.showParagraphAtTop((int) Math.round(fraction * (total - 1)));
            return;
        }
        double scrollable = totalHeight - area.getHeight();
        if (scrollable <= 0) {
            return; // whole document already fits: nothing to scroll
        }
        area.estimatedScrollYProperty().setValue(Math.min(scrollable, scrollY));
    }

    /**
     * Scrolls the editor from a wheel over the column. The minimap is a sibling of the scroll pane, not a
     * child of it, so a wheel event here reaches no scrollable ancestor and used to do nothing at all —
     * the one part of the editor surface the wheel was dead over.
     *
     * <p>Deliberately the same two lines {@code VirtualFlow}'s own SCROLL handler runs, so the gesture is
     * indistinguishable from a wheel over the text (including a horizontal/shift wheel) rather than a
     * second, subtly different scroll speed. Ctrl+wheel never arrives: the scene-level text-zoom filter
     * consumes it first.
     */
    private void wheelScroll(ScrollEvent e) {
        area.scrollXBy(-e.getDeltaX());
        area.scrollYBy(-e.getDeltaY());
        e.consume();
    }

    /**
     * Requests a content render, coalesced to at most one per pulse — and, for the <b>first</b> one, held
     * until the editor has actually painted.
     *
     * <p>Both halves exist because a content render is far from free: it iterates every paragraph and then
     * calls {@code canvas.snapshot()}, which forces a synchronous full-scene layout, and finishes in
     * {@link #drawViewport} whose {@code firstVisibleParToAllParIndex()} forces a {@code VirtualFlow} layout
     * on top of that. At startup the triggers arrive in a burst — theme colors, tab size, and the content
     * settling all fire one each — so the minimap used to run that whole sequence three times over
     * <em>before</em> the editor's first frame. Measured on a packaged build, it was the single largest
     * piece of app code on the path to first paint (~135–230 ms of a ~1.6 s startup).
     *
     * <p>The minimap is a secondary navigation aid: nothing about it needs to precede the text the user is
     * waiting for. Deferring the first render by two animation frames guarantees the editor has painted
     * first; later renders only coalesce (the 200 ms edit debounce already paces those), so typing is
     * unaffected. Correctness is unchanged either way — every trigger still results in a render, and a
     * render always draws from the current document.
     */
    private void renderContent() {
        if (renderPending) {
            return; // a render is already queued for this pulse; it will pick up whatever changed
        }
        renderPending = true;
        if (firstRenderDone) {
            Platform.runLater(this::renderNow);
            return;
        }
        // A pulse's handle() runs at the start of a pulse, before that pulse renders, so two ticks is what
        // proves a frame carrying the editor's content actually completed. Same reasoning as the startup
        // instrumentation's first-paint mark.
        new AnimationTimer() {
            private int ticks;

            @Override
            public void handle(long now) {
                if (++ticks >= 2) {
                    stop();
                    renderNow();
                }
            }
        }.start();
    }

    /** Runs a queued render, guarding against the nested paint {@code snapshot()}'s forced layout can cause. */
    private void renderNow() {
        renderPending = false;
        firstRenderDone = true;
        if (painting) {
            return; // nested paint triggered by snapshot()'s forced layout — let the outer render finish
        }
        painting = true;
        try {
            renderContent0();
        } finally {
            painting = false;
        }
    }

    private void renderContent0() {
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
        drawViewport(g, w);
        drawDiagnosticStripes(g, w, h, total, rowHeight);
        drawLintStripes(g, w, h, total, rowHeight);
        drawTodoStripes(g, h, total, rowHeight);
    }

    /** Cheap redraw on scroll: re-blit the cached content image and draw the viewport rectangle. */
    /** Coalesces scroll-driven repaints to one per pulse (the overlay {@code pending}-flag idiom). */
    private void scheduleRedraw() {
        if (redrawPending) {
            return;
        }
        redrawPending = true;
        Platform.runLater(() -> {
            redrawPending = false;
            redraw();
        });
    }

    private void redraw() {
        if (painting) {
            return; // nested paint during a render in flight (see the `painting` guard); suppress it
        }
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
        drawViewport(g, w);
        drawDiagnosticStripes(g, w, h, total, rowHeight);
        drawLintStripes(g, w, h, total, rowHeight);
        drawTodoStripes(g, h, total, rowHeight);
    }

    /** Vertical pixels per line: a fixed size, but compressed to fit when the document is long. */
    private static double rowHeight(double h, int total) {
        return Math.min(MAX_ROW_HEIGHT, h / total);
    }

    private void drawViewport(GraphicsContext g, double w) {
        // One source of truth with the drag's hit test: a box you can see but not grab (or vice versa) is
        // worse than either alone.
        double[] box = viewportBox();
        if (box != null) {
            double vy = box[0];
            double vh = box[1];
            g.setFill(viewportColor);
            g.fillRect(0, vy, w, vh);
            // The wash alone is ~14% alpha on a light theme, which disappears over a dense minimap — the
            // whole overview then reads as noise with no "you are here". The EDGES are what make a block
            // read as a slider (VS Code and IntelliJ both outline theirs), and deriving them from the same
            // colour keeps one per-theme entry rather than thirty more to hold in step.
            g.setStroke(viewportEdge(viewportColor));
            g.setLineWidth(1);
            // Half-pixel offsets: a 1px stroke on an integer coordinate straddles two device pixels and
            // renders as a 2px blur.
            g.strokeRect(0.5, vy + 0.5, Math.max(0, w - 1), Math.max(0, vh - 1));
        }
    }

    /** How much more opaque the viewport outline is than its fill. */
    private static final double EDGE_ALPHA_FACTOR = 3.0;

    /** Floor for the outline's alpha, so a theme with a very faint fill still gets a visible edge. */
    private static final double MIN_EDGE_ALPHA = 0.35;

    /**
     * The outline colour for a viewport fill: the same hue, opaque enough to read as an edge.
     *
     * <p>Pure so the derivation is testable — a wrong factor here is invisible in a screenshot until
     * someone notices the overview has no slider on one theme.
     */
    static Color viewportEdge(Color fill) {
        double alpha = Math.max(MIN_EDGE_ALPHA, Math.min(1.0, fill.getOpacity() * EDGE_ALPHA_FACTOR));
        return new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), alpha);
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
