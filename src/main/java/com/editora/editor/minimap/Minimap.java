package com.editora.editor.minimap;

import java.time.Duration;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import com.editora.editor.CanvasGuards;
import com.editora.editor.OverlayPalette;
import com.editora.editor.git.GitGutterLines;
import com.editora.editor.git.GitHunk;
import com.editora.editor.lsp.LspDiagnostic;
import com.editora.editor.todo.TodoColors;
import com.editora.editor.todo.TodoMark;
import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * A lightweight document overview ("minimap") drawn beside the editor. Each paragraph is rendered
 * as scaled-down blocks representing its non-whitespace runs, with a translucent rectangle marking
 * the currently visible viewport. Clicking jumps there, dragging the viewport box slides it, and the
 * mouse wheel over the column scrolls the editor exactly as it does over the text.
 *
 * <p>The (relatively expensive) content rendering is cached as an image and only regenerated when
 * the text or size changes; scrolling just re-blits the cached image plus the viewport rectangle. A
 * render draws one row per line, or one per pixel row once lines are less than a pixel tall
 * ({@link MinimapRows}), and skips the snapshot when the runs it would draw are the ones already cached.
 * The runs are drawn on a canvas that is never in the scene graph and that canvas is snapshotted: a
 * snapshot of a node in the window's scene first runs CSS and layout over the whole window, between
 * pulses, which is costly and can leave the window wrongly styled (see {@link #offscreen}).
 * A background tab keeps its content pixels on the CPU ({@link MinimapRetention}) so that switching back
 * re-blits instead of re-rendering; only the textures go.
 */
public final class Minimap extends Region {

    /** Fixed width of the minimap column, in pixels. */
    public static final double WIDTH = 90;

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
    /**
     * Where a content render draws its runs before they are cached as {@link #contentImage}. It is never
     * added to the scene graph, so {@code snapshot()} processes this canvas alone.
     *
     * <p>Snapshotting {@link #canvas} instead made JavaFX run a CSS and layout pass over the entire window
     * first, outside the normal pulse. Coming right after a tab was opened or its state changed, that pass
     * could fail to resolve the theme's {@code .root} colour variables for the nodes it restyled ("Could not
     * resolve '-color-fg-default'", a {@code ClassCastException} for {@code -fx-background-color}), and a
     * node it left with the wrong value kept it until something restyled it again: the selected tab's title
     * stayed black instead of the theme's colour for a modified file.
     */
    private final Canvas offscreen = new Canvas(WIDTH, 1);

    private @Nullable WritableImage contentImage;
    /** Canvas logical dims at the last successful snapshot, so the {@link #contentImage} buffer can be
     *  reused (rather than reallocated each render) when the size is unchanged — see {@link #renderContent}. */
    private double lastSnapW = -1;

    private double lastSnapH = -1;
    /** False while this minimap's buffer is a background (non-selected) tab: rendering is skipped and
     *  the cached snapshot is dropped so its GPU texture can be reclaimed — keeps retained VRAM from
     *  scaling with the number of open files. */
    private boolean renderingActive = true;
    /** A render was asked for while the tab was hidden (an edit, new colours, a tab size, {@link #refresh}):
     *  the pixels kept by {@link MinimapRetention} may no longer match, so showing the tab renders. */
    private boolean staleWhileHidden;
    /** This minimap's entry in {@link MinimapRetention}. */
    private final Object retentionKey = new Object();
    /** The runs the cached {@link #contentImage} was drawn from, with the colour, line count and size it was
     *  drawn at: a render that would draw the same thing again skips the snapshot. */
    private final MinimapRows.RunList lastRuns = new MinimapRows.RunList();

    private final MinimapRows.RunList nextRuns = new MinimapRows.RunList();
    private @Nullable Color lastTextColor;
    private int lastTotal = -1;
    /** When the last content render ran, for {@link MinimapPacing}; zero before the first. */
    private long lastRenderNanos;
    /** Holds an edit-driven render back until {@link MinimapPacing}'s interval has passed. */
    private final javafx.animation.PauseTransition pacedRender = new javafx.animation.PauseTransition();
    /** Re-entrancy guard. A render ends in {@link #drawViewport}, whose {@code firstVisibleParToAllParIndex()}
     *  can force a {@code VirtualFlow} layout that settles the editor's viewport and fires the
     *  {@code estimatedScrollYProperty} listener — re-entering {@link #redraw}/{@link #renderContent}
     *  mid-render, which would paint a second, stale viewport box over the in-progress frame. (The snapshot
     *  used to force a whole-window layout too; it no longer does, see {@link #offscreen}.) While a render
     *  is in flight, suppress nested paints; the in-progress render draws the single, up-to-date viewport
     *  when it completes. */
    private boolean painting;

    private boolean redrawPending;
    /** Holds the content render back while the column is still being resized; see {@link #layoutChildren}. */
    private final javafx.animation.PauseTransition resizeRender =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(RESIZE_SETTLE_MILLIS));

    private static final double RESIZE_SETTLE_MILLIS = 120;

    /** Content renders run by all minimaps — the test seam for "one render per tab switch / resize drag". */
    static final java.util.concurrent.atomic.AtomicInteger RENDERS_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Renders that found the cached image already right and skipped the draw and snapshot. */
    static final java.util.concurrent.atomic.AtomicInteger UNCHANGED_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger();
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

    /**
     * The buffer's Git changes as {@code {line, count, kind}} triples (see {@link GitGutterLines#marks()}),
     * drawn as a thin stripe at the left edge. Asked for again only after {@link #gitMarksChanged()}: a
     * scroll repaint reads the array it already has.
     */
    private java.util.function.Supplier<int[]> gitMarkSource = () -> NO_GIT_MARKS;

    private int[] gitMarks = NO_GIT_MARKS;
    private boolean gitMarksStale;
    private static final int[] NO_GIT_MARKS = new int[0];
    /** Added / modified / deleted, indexed by {@link GitHunk.Kind#ordinal()}; resolved per editor theme. */
    private final Color[] gitColors = new Color[3];

    private static final double GIT_STRIPE_WIDTH = 3;

    private static final Color ERROR_STRIPE = Color.web("#e5484d");
    private static final Color WARNING_STRIPE = Color.web("#e2a03f");
    private static final Color INFO_STRIPE = Color.web("#4c8eda");
    private static final double STRIPE_WIDTH = 5;

    /** The minimap of the split's second view, given every setting and mark this one is given. */
    private @Nullable Minimap follower;

    /** A minimap for a split's second {@code view}, kept in step with this one. */
    public Minimap follower(CodeArea view) {
        Minimap second = new Minimap(view);
        second.tabSize = tabSize;
        second.textColor = textColor;
        second.viewportColor = viewportColor;
        second.diagnostics = diagnostics;
        second.diagnosticsEnabled = diagnosticsEnabled;
        second.todoMarks = todoMarks;
        second.todoEnabled = todoEnabled;
        second.lintMarks = lintMarks;
        second.lintEnabled = lintEnabled;
        second.gitMarkSource = gitMarkSource;
        second.gitMarksStale = true;
        second.setRenderingActive(renderingActive);
        follower = second;
        return second;
    }

    public Minimap(CodeArea area) {
        this.area = area;
        getStyleClass().add("minimap");
        getChildren().add(canvas);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);

        area.multiPlainChanges().successionEnds(Duration.ofMillis(200)).subscribe(ignore -> renderAfterEdit());
        area.estimatedScrollYProperty().addListener((o, a, b) -> scheduleRedraw());
        pacedRender.setOnFinished(e -> renderContent());

        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::beginDrag);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::continueDrag);
        // On the Region rather than the canvas, so the wheel works over the whole column even in the
        // moments the canvas is smaller than it (see CanvasGuards / layoutChildren).
        addEventHandler(ScrollEvent.SCROLL, this::wheelScroll);
        resizeRender.setOnFinished(e -> renderContent());
        OverlayPalette.track(area, palette -> {
            Color ground = OverlayPalette.backgroundOf(area);
            boolean dark = OverlayPalette.isDark(ground);
            gitColors[GitHunk.Kind.ADDED.ordinal()] =
                    OverlayPalette.reach(Color.web(dark ? "#3fb950" : "#1a7f37"), ground, OverlayPalette.INDICATOR);
            gitColors[GitHunk.Kind.MODIFIED.ordinal()] = palette.info();
            gitColors[GitHunk.Kind.DELETED.ordinal()] = palette.error();
            repaintStripes();
        });
    }

    /** Releases what this minimap holds outside itself: its retained pixels and its timers. */
    public void dispose() {
        if (follower != null) {
            follower.dispose();
        }
        pacedRender.stop();
        resizeRender.stop();
        MinimapRetention.SHARED.remove(retentionKey);
        contentImage = null;
    }

    /** Sets where the Git change marks come from; they are read on the next repaint. */
    public void setGitMarks(java.util.function.Supplier<int[]> source) {
        gitMarkSource = source == null ? () -> NO_GIT_MARKS : source;
        gitMarksChanged();
    }

    /** The marks (or the lines they sit on) changed: re-read them on the next repaint. */
    public void gitMarksChanged() {
        if (follower != null) {
            follower.gitMarksChanged();
        }
        if (gitMarksStale) {
            return; // already waiting for a repaint
        }
        gitMarksStale = true;
        repaintStripes();
    }

    /** The marks as last drawn — the test seam for "they follow the buffer". */
    int[] gitMarksForTest() {
        refreshGitMarks();
        return gitMarks;
    }

    private void refreshGitMarks() {
        if (gitMarksStale) {
            gitMarksStale = false;
            int[] now = gitMarkSource.get();
            gitMarks = now == null ? NO_GIT_MARKS : now;
        }
    }

    /**
     * Draws the Git changes as a thin stripe down the left edge: one run per change, at least two pixels
     * tall so a single changed line of a long file is still there. A deletion is drawn last and one row
     * high, on the boundary it happened at.
     */
    private void drawGitStripes(GraphicsContext g, double h, int total, double rowHeight) {
        refreshGitMarks();
        int[] marks = gitMarks;
        if (marks.length == 0 || total == 0 || gitColors[0] == null) {
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i + 2 < marks.length; i += 3) {
                boolean deletion = marks[i + 2] == GitHunk.Kind.DELETED.ordinal();
                if (deletion != (pass == 1)) {
                    continue;
                }
                double markH = Math.max(2.0, deletion ? rowHeight : marks[i + 1] * rowHeight);
                // Whole pixels: a 2px mark on a half pixel is smeared over three rows at half strength.
                double y = Math.rint(clamp(marks[i], total) * rowHeight - (deletion ? markH / 2 : 0));
                g.setFill(gitColors[marks[i + 2]]);
                g.fillRect(0, Math.max(0, Math.min(y, h - markH)), GIT_STRIPE_WIDTH, Math.ceil(markH));
            }
        }
    }

    /** Sets the visual tab width (columns) and re-renders if it changed. */
    public void setTabSize(int tabSize) {
        if (follower != null) {
            follower.setTabSize(tabSize);
        }
        if (tabSize > 0 && tabSize != this.tabSize) {
            this.tabSize = tabSize;
            renderContent();
        }
    }

    /** Sets the document-block and viewport-overlay colors (theme-aware) and re-renders. */
    public void setColors(Color text, Color viewport) {
        if (follower != null) {
            follower.setColors(text, viewport);
        }
        this.textColor = text;
        this.viewportColor = viewport;
        renderContent();
    }

    /** Forces a re-render (e.g. after layout/theme settle at startup, when the first render may have
     *  run before the canvas was sized). */
    public void refresh() {
        if (follower != null) {
            follower.refresh();
        }
        renderContent();
    }

    /** Sets the LSP diagnostics drawn as right-edge severity stripes; a cheap stripe-only repaint. */
    public void setDiagnostics(java.util.List<LspDiagnostic> diagnostics) {
        if (follower != null) {
            follower.setDiagnostics(diagnostics);
        }
        java.util.List<LspDiagnostic> now = diagnostics == null ? java.util.List.of() : diagnostics;
        if (now.isEmpty() && this.diagnostics.isEmpty()) {
            return; // an LSP buffer clears its (already empty) list on every keystroke
        }
        this.diagnostics = now;
        repaintStripes();
    }

    /** Enables/disables the diagnostic stripes (driven by LSP-active for this buffer); a cheap repaint. */
    public void setDiagnosticsEnabled(boolean enabled) {
        if (follower != null) {
            follower.setDiagnosticsEnabled(enabled);
        }
        if (this.diagnosticsEnabled == enabled) {
            return;
        }
        this.diagnosticsEnabled = enabled;
        repaintStripes();
    }

    /** Sets the TODO/highlight matches drawn as left-edge stripes; a cheap stripe-only repaint. */
    public void setTodoMarks(java.util.List<TodoMark> marks) {
        if (follower != null) {
            follower.setTodoMarks(marks);
        }
        java.util.List<TodoMark> now = marks == null ? java.util.List.of() : marks;
        if (now.isEmpty() && this.todoMarks.isEmpty()) {
            return;
        }
        this.todoMarks = now;
        repaintStripes();
    }

    /** Enables/disables the TODO stripes (driven by TODO-highlight-on for this buffer); a cheap repaint. */
    public void setTodoEnabled(boolean enabled) {
        if (follower != null) {
            follower.setTodoEnabled(enabled);
        }
        if (this.todoEnabled == enabled) {
            return;
        }
        this.todoEnabled = enabled;
        repaintStripes();
    }

    /** Sets the Markdown-lint warnings drawn as right-edge stripes; a cheap stripe-only repaint. */
    public void setLintMarks(java.util.List<MarkdownLint.Diagnostic> marks) {
        if (follower != null) {
            follower.setLintMarks(marks);
        }
        java.util.List<MarkdownLint.Diagnostic> now = marks == null ? java.util.List.of() : marks;
        if (now.isEmpty() && this.lintMarks.isEmpty()) {
            return;
        }
        this.lintMarks = now;
        repaintStripes();
    }

    /** Enables/disables the lint stripes (driven by Markdown-lint-on for this buffer); a cheap repaint. */
    public void setLintEnabled(boolean enabled) {
        if (follower != null) {
            follower.setLintEnabled(enabled);
        }
        if (this.lintEnabled == enabled) {
            return;
        }
        this.lintEnabled = enabled;
        repaintStripes();
    }

    /**
     * Repaints just the stripes over the already-cached content image. Crucially, this never forces a
     * {@link #renderContent()}: when there is no cached image yet (early startup, before the editor's first
     * paint), it does nothing and lets the minimap's normal layout-driven first render draw the stripes.
     *
     * <p>Coalesced like the scroll repaint: diagnostics, lint marks and TODO marks routinely change in the
     * same turn (and again on the next keystroke), and each used to repaint the whole column by itself.
     */
    private void repaintStripes() {
        if (contentImage != null) {
            scheduleRedraw();
        }
    }

    /**
     * Marks whether this minimap's buffer is the active (visible) tab. A background tab drops the
     * cached snapshot (a pinned GPU texture) and its canvas texture and stops rendering. This is what
     * keeps retained VRAM from growing with the number of open files.
     *
     * <p>The snapshot's <em>pixels</em> stay, on the CPU, in {@link MinimapRetention}: switching back to
     * the tab writes them into a fresh image and re-blits — a texture upload — unless something that
     * changes the picture happened while it was hidden ({@link #staleWhileHidden}) or the column is now
     * another size, in which case it renders as before. A full render was the largest app frame of a tab
     * switch; the pixels are a few hundred KB.
     */
    public void setRenderingActive(boolean active) {
        if (follower != null) {
            follower.setRenderingActive(active);
        }
        if (this.renderingActive == active) {
            return;
        }
        this.renderingActive = active;
        if (active) {
            // Fit the canvas here rather than waiting for layoutChildren: a render queued now must draw at
            // the column's size, not into the released 1×1 canvas (and then again once layout re-fits it).
            CanvasGuards.fit(this, canvas);
            boolean restored = restoreRetained(canvas.getWidth(), canvas.getHeight());
            if (restored) {
                scheduleRedraw(); // the re-fitted canvas is blank: blit the restored image onto it
            }
            if (!restored || staleWhileHidden) {
                // With the pixels restored, the render still skips its snapshot when the edit made while
                // hidden did not move a run (see renderContent0); the redraw above has shown them by then.
                renderContent();
            }
            staleWhileHidden = false;
        } else {
            retain();
            contentImage = null;
            CanvasGuards.release(canvas);
            CanvasGuards.release(offscreen);
        }
    }

    /** Copies the cached content pixels into {@link MinimapRetention} before the image is dropped. */
    private void retain() {
        WritableImage image = contentImage;
        if (image == null) {
            return;
        }
        int pw = (int) image.getWidth();
        int ph = (int) image.getHeight();
        if (pw <= 0 || ph <= 0) {
            return;
        }
        int[] pixels = new int[pw * ph];
        image.getPixelReader().getPixels(0, 0, pw, ph, PixelFormat.getIntArgbPreInstance(), pixels, 0, pw);
        MinimapRetention.SHARED.put(retentionKey, new MinimapRetention.Entry(pixels, pw, ph, lastSnapW, lastSnapH));
    }

    /**
     * Rebuilds {@link #contentImage} from the retained pixels when they were snapshotted at the current
     * column size; the entry is consumed either way.
     *
     * @return whether the cached image is back
     */
    private boolean restoreRetained(double w, double h) {
        MinimapRetention.Entry entry = MinimapRetention.SHARED.get(retentionKey);
        if (entry == null) {
            return false;
        }
        MinimapRetention.SHARED.remove(retentionKey);
        if (entry.canvasWidth() != w || entry.canvasHeight() != h) {
            return false;
        }
        WritableImage image = new WritableImage(entry.pixelWidth(), entry.pixelHeight());
        image.getPixelWriter()
                .setPixels(
                        0,
                        0,
                        entry.pixelWidth(),
                        entry.pixelHeight(),
                        PixelFormat.getIntArgbPreInstance(),
                        entry.pixels(),
                        0,
                        entry.pixelWidth());
        contentImage = image;
        lastSnapW = entry.canvasWidth();
        lastSnapH = entry.canvasHeight();
        return true;
    }

    @Override
    protected void layoutChildren() {
        if (!renderingActive) {
            return; // the canvas is released while the tab is hidden; setRenderingActive(true) re-fits it
        }
        double w = CanvasGuards.clampWidth(this, getWidth());
        double h = CanvasGuards.clampHeight(this, getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            if (contentImage == null) {
                renderContent();
            } else if (w == lastSnapW && h == lastSnapH) {
                scheduleRedraw(); // the cached image is this size already: nothing to re-render
            } else {
                // Dragging the window edge changes the height on every pulse, and a content render walks
                // the document and then draws and snapshots it. Until the size settles
                // the cached image is re-blitted with the live viewport box and stripes over it; the
                // render that matches the final size follows once.
                scheduleRedraw();
                resizeRender.playFromStart();
            }
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
     * The document line drawn at column pixel {@code minimapY}. Pure.
     *
     * <p>This is the mapping that stays true whatever the editor's rows look like: the overview always draws
     * line {@code i} at {@code i * rowHeight}. {@link #documentScrollY} is only its pixel-smooth equivalent
     * while every editor row is one line high — a fold (rows of zero height) or word wrap (rows several
     * lines high) breaks the proportion, and a click then landed on a different line than the one under the
     * cursor.
     *
     * @return the 0-based line, clamped to the document, or -1 when the geometry isn't measurable yet
     */
    static int lineAt(double minimapY, double rowHeight, int totalLines) {
        if (rowHeight <= 0 || totalLines <= 0) {
            return -1;
        }
        return (int) Math.max(0, Math.min(totalLines - 1, Math.floor(Math.max(0, minimapY) / rowHeight)));
    }

    /**
     * Whether the editor's rows are <em>not</em> all one line high, so column pixels have to be mapped by
     * line ({@link #lineAt}) rather than by proportion. Decided once per press: the fold scan is a plain
     * style read per paragraph (no layout), but it is still O(lines) and a drag fires per pixel.
     */
    private boolean unevenRows;

    private boolean hasUnevenRows() {
        if (area.isWrapText()) {
            return true;
        }
        for (int p = 0, n = area.getParagraphs().size(); p < n; p++) {
            if (area.isFolded(p)) {
                return true;
            }
        }
        return false;
    }

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
    double @Nullable [] viewportBox() {
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
        unevenRows = hasUnevenRows();
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
     * <p>That proportional mapping is only right while every editor row is one line high. With a fold or
     * word wrap in the document ({@link #unevenRows}) the press is mapped to the <em>line</em> drawn under
     * it instead and that line is shown at the top — whole-line steps, but on the right line.
     *
     * <p>Falls back to the paragraph jump only when the height estimate isn't available yet (before the
     * first layout), where an approximate landing beats not scrolling at all.
     */
    private void scrollToBoxTop(double boxTop) {
        int total = area.getParagraphs().size();
        if (total == 0 || getHeight() <= 0) {
            return;
        }
        if (unevenRows) {
            int line = lineAt(boxTop, rowHeight(getHeight(), total), total);
            while (line > 0 && area.isFolded(line)) {
                line--; // a hidden line is represented by the fold header above it
            }
            if (line >= 0) {
                area.showParagraphAtTop(line);
            }
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
     * <p>Both halves exist because a content render is far from free: it iterates every paragraph, draws and
     * snapshots the runs, and finishes in {@link #drawViewport} whose {@code firstVisibleParToAllParIndex()}
     * forces a {@code VirtualFlow} layout. (The snapshot also forced a whole-window layout until it moved to
     * {@link #offscreen}; the measurement below predates that.) At startup the triggers arrive in a burst —
     * theme colors, tab size, and the content settling all fire one each — so the minimap used to run that
     * whole sequence three times over <em>before</em> the editor's first frame. Measured on a packaged build,
     * it was the single largest piece of app code on the path to first paint (~135–230 ms of a ~1.6 s
     * startup).
     *
     * <p>The minimap is a secondary navigation aid: nothing about it needs to precede the text the user is
     * waiting for. Deferring the first render by two animation frames guarantees the editor has painted
     * first; later renders only coalesce (the 200 ms edit debounce already paces those), so typing is
     * unaffected. Correctness is unchanged either way — every trigger still results in a render, and a
     * render always draws from the current document.
     */
    private void renderContent() {
        if (!renderingActive) {
            // Nothing is drawn for a hidden tab; remember that its retained pixels are no longer current.
            staleWhileHidden = true;
            return;
        }
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

    /**
     * A typing pause ended: re-render now, or — on a long document — once {@link MinimapPacing}'s interval
     * since the last render has passed. Edits that land while the wait runs are picked up by that one
     * render; the timer is not restarted.
     */
    private void renderAfterEdit() {
        if (!renderingActive) {
            staleWhileHidden = true;
            return;
        }
        long wait = MinimapPacing.delayMillis(area.getParagraphs().size(), System.nanoTime() - lastRenderNanos);
        if (wait <= 0) {
            renderContent();
        } else if (pacedRender.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            pacedRender.setDuration(javafx.util.Duration.millis(wait));
            pacedRender.playFromStart();
        }
    }

    /** Runs a queued render, guarding against the nested paint a forced layout during it can cause. */
    private void renderNow() {
        renderPending = false;
        firstRenderDone = true;
        if (painting) {
            return; // nested paint triggered by a forced layout mid-render — let the outer render finish
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
        int total = area.getParagraphs().size();
        if (!renderingActive || !isVisible() || !CanvasGuards.paintable(getWidth(), getHeight()) || total == 0) {
            g.clearRect(0, 0, w, h);
            contentImage = null;
            return;
        }
        double rowHeight = rowHeight(h, total);
        // What this render would draw, decided before anything is cleared: a typing pause that changed no
        // run (a character inside a word, a style-only edit that still ended a succession) costs the scan
        // and nothing else. The scan is O(lines) in length reads and O(rows) in text.
        MinimapRows.plan(
                total,
                rowHeight,
                tabSize,
                maxColumns(w),
                area::getParagraphLength,
                i -> area.getParagraph(i).getText(),
                nextRuns);
        if (contentImage != null
                && w == lastSnapW
                && h == lastSnapH
                && total == lastTotal
                && textColor.equals(lastTextColor)
                && nextRuns.sameAs(lastRuns)) {
            UNCHANGED_FOR_TEST.incrementAndGet();
            return;
        }
        RENDERS_FOR_TEST.incrementAndGet();
        lastRenderNanos = System.nanoTime();
        g.clearRect(0, 0, w, h);
        offscreen.setWidth(w);
        offscreen.setHeight(h);
        GraphicsContext og = offscreen.getGraphicsContext2D();
        og.clearRect(0, 0, w, h);
        // Hold the prior buffer so a same-size re-render can reuse it (see the snapshot call below)
        // instead of allocating a fresh WritableImage.
        WritableImage prior = contentImage;
        contentImage = null;
        boolean aggregated = MinimapRows.aggregated(rowHeight);
        // One whole pixel row per aggregated row; otherwise a block a little shorter than the line's row.
        double blockH = aggregated ? 1.0 : Math.max(0.75, Math.min(rowHeight * 0.8, 2.0));
        og.setFill(textColor);
        for (int k = 0, n = nextRuns.size(); k < n; k++) {
            double y = aggregated ? nextRuns.row(k) : nextRuns.row(k) * rowHeight;
            double x = nextRuns.startColumn(k) * CHAR_SCALE;
            double width = Math.min(w - x, (nextRuns.endColumn(k) - nextRuns.startColumn(k)) * CHAR_SCALE);
            if (x < w && width > 0) {
                og.fillRect(x, y, width, blockH);
            }
        }
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        // Reuse the previous frame's image buffer when the canvas size is unchanged (the common case:
        // a content re-render on edit), instead of allocating a fresh WritableImage every render.
        WritableImage reuse = (prior != null && w == lastSnapW && h == lastSnapH) ? prior : null;
        try {
            contentImage = offscreen.snapshot(sp, reuse);
            lastSnapW = w;
            lastSnapH = h;
            lastTotal = total;
            lastTextColor = textColor;
            lastRuns.copyFrom(nextRuns);
            g.drawImage(contentImage, 0, 0); // the same blit every later redraw paints
        } catch (RuntimeException ignored) {
            // Leave the cache empty; a later render (layout settle, refresh(), or the next edit) re-caches.
            // (Snapshotting the on-screen canvas used to fail this way at startup, when the window-wide
            // layout it forced found Flowless with no visible cell yet: "Cell 0 is not visible".)
            contentImage = null;
        }
        drawViewport(g, w);
        drawDiagnosticStripes(g, w, h, total, rowHeight);
        drawLintStripes(g, w, h, total, rowHeight);
        drawGitStripes(g, h, total, rowHeight);
        drawTodoStripes(g, h, total, rowHeight);
    }

    /** How many text columns fit a {@code w}-pixel column before the right edge. */
    static int maxColumns(double w) {
        return (int) Math.ceil(w / CHAR_SCALE);
    }

    /**
     * Cheap redraw on scroll: re-blit the cached content image and draw the viewport rectangle. Coalesces
     * scroll-driven repaints to one per pulse (the overlay {@code pending}-flag idiom).
     */
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
            // No cached content yet — the first render has not run, or its snapshot failed. Regenerate
            // (draws the runs and retries the cache) instead of leaving the minimap blank with only the
            // viewport box.
            renderContent();
            return;
        }
        g.drawImage(contentImage, 0, 0);
        double rowHeight = rowHeight(h, total);
        drawViewport(g, w);
        drawDiagnosticStripes(g, w, h, total, rowHeight);
        drawLintStripes(g, w, h, total, rowHeight);
        drawGitStripes(g, h, total, rowHeight);
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
            g.setFill(TodoColors.solid(m.colorWeb()));
            double y = clamp(m.line(), total) * rowHeight;
            // Beside the Git stripe when there is one, so neither hides the other.
            g.fillRect(gitMarks.length == 0 ? 0 : GIT_STRIPE_WIDTH + 1, Math.min(y, h - markH), STRIPE_WIDTH, markH);
        }
    }

    private static int clamp(int idx, int total) {
        if (idx < 0) {
            return 0;
        }
        return Math.min(idx, total - 1);
    }
}
