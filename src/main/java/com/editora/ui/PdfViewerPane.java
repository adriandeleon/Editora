package com.editora.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

import com.editora.editor.TabContent;
import com.editora.editor.UiZoom;
import com.editora.editor.markdown.PreviewImageLoader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;
import static com.editora.ui.ProjectMapView.wheelAction;

/**
 * A read-only PDF viewer that lives in an editor tab ({@link TabContent}) — so opening a {@code .pdf}
 * renders its pages via Apache PDFBox (already a dependency, for PDF export) instead of dumping the binary
 * bytes into the hex viewer. A top bar navigates pages ({@code ◀ Page N of M ▶}) and zooms (out / in /
 * fit-to-window / actual-size). The mouse wheel and a touchpad pinch zoom around the pointer, as on the Project
 * Map; Shift+wheel pans across, Alt+wheel pans down, and dragging pans. Pages are rasterized <b>one at a time, off the
 * FX thread</b>, on a single daemon thread (PDFBox's {@code PDDocument} is not thread-safe, so all document
 * access is serialized there) — so only the current page's image is held, bounding the Prism texture pool.
 * {@link #dispose()} closes the document + drops the image when the tab closes.
 */
public final class PdfViewerPane implements TabContent {

    /** Read at most this many bytes; a larger PDF is refused (protects heap). */
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    /** Rasterization resolution — crisp on HiDPI while one page stays a bounded texture; zoom scales from here. */
    private static final float RENDER_DPI = 150f;

    private static final double MIN_ZOOM = 0.05;
    private static final double MAX_ZOOM = 20.0;
    private static final double ZOOM_STEP = 1.25;

    /**
     * Space reserved around the page when fitting: the holder's 12px padding on each side (24) plus a small
     * slack so the fitted page stays <b>strictly</b> inside the viewport. Without the slack the fitted page's
     * preferred size lands exactly at the scrollbar threshold, so the {@link ScrollPane} toggles a scrollbar
     * every layout pass; each toggle nudges {@code viewportBounds}, re-firing the listener → {@link #relayout()}
     * → re-fit → … forever (the 73%↔74% oscillation seen on a portrait PDF, burning the FX thread).
     */
    private static final double FIT_INSET = 28;

    private final @Nullable Path path;
    private final String title;
    private final BorderPane root = new BorderPane();
    private final ImageView imageView = new ImageView();
    private final ScrollPane scroll = new ScrollPane();
    private final StackPane holder = new StackPane(imageView);
    private final Label zoomLabel = new Label();
    private final Label pageLabel = new Label();
    private final Button prevButton = symbolButton("◀", tr("pdfviewer.prevPage"), () -> showPage(this.currentPage - 1));
    private final Button nextButton = symbolButton("▶", tr("pdfviewer.nextPage"), () -> showPage(this.currentPage + 1));
    private Node toolbar;

    /** Single daemon thread: loads the document + rasterizes pages (PDDocument is not thread-safe). */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-render");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong renderGen = new AtomicLong();
    private volatile boolean disposed;

    // Touched only on the exec thread once loaded.
    private @Nullable PDDocument document;
    private @Nullable PDFRenderer renderer;

    private int pageCount;
    private int currentPage; // 0-based; FX thread
    private @Nullable Image image;
    private double zoom = 1.0;
    private boolean fitMode = true;

    /** Viewport size the last fit was computed against; lets {@link #relayout()} ignore self-induced jitter. */
    private double lastFitViewW = -1;

    private double lastFitViewH = -1;

    public PdfViewerPane(@Nullable Path path) {
        this.path = path;
        Path name = path == null ? null : path.getFileName();
        this.title = name == null ? String.valueOf(path) : name.toString();
        root.getStyleClass().add("pdf-viewer");
        build();
        load();
    }

    private void build() {
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        holder.getStyleClass().add("pdf-viewer-holder");
        holder.setPadding(new Insets(12));
        holder.minWidthProperty()
                .bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> scroll.getViewportBounds().getWidth(), scroll.viewportBoundsProperty()));
        holder.minHeightProperty()
                .bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> scroll.getViewportBounds().getHeight(), scroll.viewportBoundsProperty()));
        scroll.setContent(holder);
        scroll.setPannable(true);
        scroll.viewportBoundsProperty().addListener((o, w, n) -> relayout());
        scroll.addEventFilter(ScrollEvent.SCROLL, this::onWheel);
        scroll.addEventFilter(ZoomEvent.ZOOM, this::onPinch);
        root.setCenter(scroll);
        toolbar = buildToolbar();
        root.setTop(toolbar);
    }

    private Node buildToolbar() {
        pageLabel.getStyleClass().add("pdf-viewer-page");
        Button out = symbolButton("−", tr("imageviewer.zoomOut"), () -> setZoom(zoom / ZOOM_STEP));
        Button in = symbolButton("+", tr("imageviewer.zoomIn"), () -> setZoom(zoom * ZOOM_STEP));
        Button fit = button(tr("imageviewer.fit"), tr("imageviewer.fitTip"), this::fitToWindow);
        Button actual = symbolButton(tr("imageviewer.actualSize"), tr("imageviewer.actualSizeTip"), () -> {
            fitMode = false;
            setZoom(1.0);
        });
        zoomLabel.getStyleClass().add("pdf-viewer-zoom");
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label readOnly = new Label(tr("imageviewer.readOnly"));
        readOnly.getStyleClass().add("pdf-viewer-readonly");
        prevButton.setDisable(true);
        nextButton.setDisable(true);
        HBox bar = UiZoom.spacing(
                new HBox(prevButton, pageLabel, nextButton, sep(), out, in, fit, actual, zoomLabel, spacer, readOnly),
                6);
        bar.getStyleClass().add("pdf-viewer-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        UiZoom.padding(bar, 6, 10, 6, 10);
        return bar;
    }

    private static Label sep() {
        Label l = new Label("|");
        l.getStyleClass().add("pdf-viewer-sep");
        return l;
    }

    /** A button that shows a word ("Fit"), which is its name; {@code tip} says more. */
    private Button button(String text, String tip, Runnable action) {
        Button b = barButton(text, action);
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    /** A button that shows a symbol and no word ("◀", "−", "1:1"): {@code name} is its tooltip and what is read out. */
    private Button symbolButton(String symbol, String name, Runnable action) {
        Button b = barButton(symbol, action);
        Icons.name(b, name);
        return b;
    }

    private Button barButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("pdf-viewer-btn");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    /**
     * Reads the file again: it was replaced on disk — an export wrote over the PDF this tab shows — and the
     * viewer holds the old bytes in memory. Stays on the page it was on when the new document has it.
     */
    void reload() {
        if (!disposed) {
            load(currentPage);
        }
    }

    private void load() {
        load(0);
    }

    /** Loads the document and shows the page at {@code wanted}, or the last page of a shorter document. */
    private void load(int wanted) {
        long g = renderGen.incrementAndGet();
        exec.submit(() -> {
            try {
                long size = Files.size(path);
                if (size > MAX_BYTES) {
                    postError(tr("pdfviewer.tooLarge"));
                    return;
                }
                byte[] bytes = Files.readAllBytes(path); // provider-agnostic (local + SFTP)
                PDDocument previous = document;
                document = Loader.loadPDF(bytes);
                renderer = new PDFRenderer(document);
                if (previous != null) { // a reload: the document it replaces is done with
                    try {
                        previous.close();
                    } catch (java.io.IOException ignored) {
                        // best-effort close
                    }
                }
                int count = document.getNumberOfPages();
                if (count <= 0) {
                    postError(tr("pdfviewer.loadFailed"));
                    return;
                }
                int show = Math.max(0, Math.min(wanted, count - 1));
                Image first = renderToImage(show);
                Platform.runLater(() -> {
                    if (disposed || g != renderGen.get()) {
                        return;
                    }
                    if (root.getCenter() != scroll) { // an earlier load had failed: bring the viewer back
                        root.setCenter(scroll);
                        root.setTop(toolbar);
                    }
                    pageCount = count;
                    currentPage = show;
                    applyImage(first);
                    updateControls();
                });
            } catch (Throwable e) { // PDFBox throws Errors too (e.g. malformed streams)
                postError(tr("pdfviewer.loadFailed"));
            }
        });
    }

    /** Rasterizes {@code index} to a JavaFX image (on the exec thread; RGB → white page background). */
    private Image renderToImage(int index) throws java.io.IOException {
        PDFRenderer r = renderer;
        if (r == null) {
            throw new IllegalStateException("no PDF document loaded");
        }
        BufferedImage bi = r.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
        return PreviewImageLoader.toFxImage(bi);
    }

    private void showPage(int index) {
        if (disposed || renderer == null || index < 0 || index >= pageCount || index == currentPage) {
            return;
        }
        currentPage = index;
        updateControls();
        long g = renderGen.incrementAndGet();
        exec.submit(() -> {
            try {
                Image img = renderToImage(index);
                Platform.runLater(() -> {
                    if (disposed || g != renderGen.get()) {
                        return;
                    }
                    applyImage(img);
                });
            } catch (Throwable e) {
                postError(tr("pdfviewer.loadFailed"));
            }
        });
    }

    private void applyImage(Image img) {
        image = img;
        imageView.setImage(img);
        fitToWindow();
    }

    private void updateControls() {
        pageLabel.setText(tr("pdfviewer.pageOf", currentPage + 1, pageCount));
        prevButton.setDisable(currentPage <= 0);
        nextButton.setDisable(currentPage >= pageCount - 1);
    }

    private void postError(String message) {
        Platform.runLater(() -> {
            if (!disposed) {
                showError(message);
            }
        });
    }

    private void showError(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("pdf-viewer-error");
        StackPane center = new StackPane(label);
        UiZoom.padding(center, 24);
        root.setCenter(center);
        root.setTop(null);
    }

    private void fitToWindow() {
        fitMode = true;
        apply();
    }

    private void setZoom(double z) {
        fitMode = false;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        apply();
    }

    /**
     * The wheel zooms around the pointer (faster with the shortcut key held), like the Project Map: Shift pans
     * across, Alt pans down, a mostly-horizontal touchpad swipe pans across, and scroll inertia is ignored.
     */
    private void onWheel(ScrollEvent e) {
        double deltaX = e.getDeltaX();
        double deltaY = e.getDeltaY();
        // Some platforms report Shift+wheel as a horizontal delta already; take whichever axis moved.
        double wheel = deltaY != 0 ? deltaY : deltaX;
        switch (wheelAction(deltaX, deltaY, e.isShiftDown(), e.isAltDown(), e.isInertia())) {
            case PAN_X -> panBy(e.isShiftDown() ? wheel : deltaX, 0);
            case PAN_Y -> panBy(0, wheel);
            case ZOOM -> {
                if (image != null) {
                    zoomAround(
                            zoom * Math.exp(wheelZoomExponent(deltaY, e.isShortcutDown())),
                            e.getSceneX(),
                            e.getSceneY());
                }
            }
            case NONE -> {}
        }
        e.consume();
    }

    /** A touchpad or touch-screen pinch zooms around the gesture's centre. */
    private void onPinch(ZoomEvent e) {
        double factor = e.getZoomFactor();
        if (image != null && Double.isFinite(factor) && factor > 0) {
            zoomAround(zoom * factor, e.getSceneX(), e.getSceneY());
        }
        e.consume();
    }

    /**
     * How far one wheel event zooms, as an exponent of {@code e}: proportional to the delta so a touchpad's
     * small steps zoom smoothly, capped so one coarse wheel notch can't jump. Same speeds as the Project Map.
     * Pure — tested.
     */
    static double wheelZoomExponent(double deltaY, boolean fast) {
        double speed = fast ? 0.004 : 0.0025;
        return Math.max(-0.45, Math.min(0.45, deltaY * speed));
    }

    /** Scrolls the page by a wheel delta in pixels (a positive delta moves toward the top / left). */
    private void panBy(double dx, double dy) {
        Bounds view = scroll.getViewportBounds();
        double extraW = holder.getWidth() - view.getWidth();
        double extraH = holder.getHeight() - view.getHeight();
        if (extraW > 0 && dx != 0) {
            scroll.setHvalue(clamp01(scroll.getHvalue() - dx / extraW));
        }
        if (extraH > 0 && dy != 0) {
            scroll.setVvalue(clamp01(scroll.getVvalue() - dy / extraH));
        }
    }

    /** Zooms to {@code requested}, keeping the point of the page under the scene point {@code (sx, sy)} still. */
    private void zoomAround(double requested, double sx, double sy) {
        double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, requested));
        if (next == zoom && !fitMode) {
            return;
        }
        Bounds view = scroll.getViewportBounds();
        Point2D inHolder = holder.sceneToLocal(sx, sy);
        Point2D inImage = imageView.sceneToLocal(sx, sy);
        Bounds img = imageView.getLayoutBounds();
        // Where the pointer sits in the viewport, and which fraction of the page it is over (clamped, so a
        // pointer in the margin pivots on the page's nearest edge).
        double viewX = inHolder.getX() - scrollOffset(scroll.getHvalue(), holder.getWidth(), view.getWidth());
        double viewY = inHolder.getY() - scrollOffset(scroll.getVvalue(), holder.getHeight(), view.getHeight());
        double fx = img.getWidth() > 0 ? clamp01(inImage.getX() / img.getWidth()) : 0.5;
        double fy = img.getHeight() > 0 ? clamp01(inImage.getY() / img.getHeight()) : 0.5;

        setZoom(next);
        scroll.applyCss();
        scroll.layout();

        Bounds after = imageView.getBoundsInParent();
        Bounds newView = scroll.getViewportBounds();
        double extraW = holder.getWidth() - newView.getWidth();
        double extraH = holder.getHeight() - newView.getHeight();
        if (extraW > 0) {
            scroll.setHvalue(clamp01((after.getMinX() + fx * after.getWidth() - viewX) / extraW));
        }
        if (extraH > 0) {
            scroll.setVvalue(clamp01((after.getMinY() + fy * after.getHeight() - viewY) / extraH));
        }
    }

    /** The pixels a {@link ScrollPane} with value {@code value} (0–1) has scrolled its content by. */
    private static double scrollOffset(double value, double content, double view) {
        return Math.max(0, content - view) * value;
    }

    private static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }

    private void apply() {
        if (image == null) {
            return;
        }
        double natW = image.getWidth();
        double natH = image.getHeight();
        if (fitMode) {
            lastFitViewW = scroll.getViewportBounds().getWidth();
            lastFitViewH = scroll.getViewportBounds().getHeight();
            double viewW = lastFitViewW - FIT_INSET;
            double viewH = lastFitViewH - FIT_INSET;
            if (viewW > 1 && viewH > 1) {
                zoom = Math.min(1.0, Math.min(viewW / natW, viewH / natH));
            } else {
                zoom = 1.0;
            }
            if (!Double.isFinite(zoom) || zoom <= 0) {
                zoom = 1.0;
            }
        }
        imageView.setFitWidth(natW * zoom);
        zoomLabel.setText(Math.round(zoom * 100) + "%");
    }

    /** Re-fits when the tab is first shown / the window resizes (call after the node is in a laid-out scene). */
    void relayout() {
        if (!fitMode) {
            return;
        }
        // Only re-fit on a genuine resize: a sub-pixel viewport change is the feedback from our own re-fit
        // (a scrollbar flickering on/off), and reacting to it re-opens the oscillation loop.
        double vw = scroll.getViewportBounds().getWidth();
        double vh = scroll.getViewportBounds().getHeight();
        if (Math.abs(vw - lastFitViewW) < 1.0 && Math.abs(vh - lastFitViewH) < 1.0) {
            return;
        }
        apply();
    }

    /** The page count once loaded (0 while loading / on failure). Test accessor. */
    int pageCount() {
        return pageCount;
    }

    /** Whether {@code fileName} is a PDF (opens in this viewer instead of the hex viewer). Pure. */
    public static boolean isPdf(String fileName) {
        if (fileName == null) {
            return false;
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String name = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** Closes the document + releases the page image when the tab closes. */
    public void dispose() {
        disposed = true;
        imageView.setImage(null);
        image = null;
        exec.submit(() -> {
            try {
                if (document != null) {
                    document.close();
                }
            } catch (java.io.IOException ignored) {
                // best-effort close
            }
        });
        exec.shutdown();
    }

    public @Nullable Path getPath() {
        return path;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Node icon() {
        return FileIcons.forFileName(title);
    }
}
