package com.editora.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

import com.editora.editor.PreviewImageLoader;
import com.editora.editor.TabContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import static com.editora.i18n.Messages.tr;

/**
 * A read-only PDF viewer that lives in an editor tab ({@link TabContent}) — so opening a {@code .pdf}
 * renders its pages via Apache PDFBox (already a dependency, for PDF export) instead of dumping the binary
 * bytes into the hex viewer. A top bar navigates pages ({@code ◀ Page N of M ▶}) and zooms (out / in /
 * fit-to-window / actual-size); {@code Ctrl}+wheel zooms too. Pages are rasterized <b>one at a time, off the
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

    private final Path path;
    private final String title;
    private final BorderPane root = new BorderPane();
    private final ImageView imageView = new ImageView();
    private final ScrollPane scroll = new ScrollPane();
    private final Label zoomLabel = new Label();
    private final Label pageLabel = new Label();
    private Button prevButton;
    private Button nextButton;

    /** Single daemon thread: loads the document + rasterizes pages (PDDocument is not thread-safe). */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-render");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong renderGen = new AtomicLong();
    private volatile boolean disposed;

    // Touched only on the exec thread once loaded.
    private PDDocument document;
    private PDFRenderer renderer;

    private int pageCount;
    private int currentPage; // 0-based; FX thread
    private Image image;
    private double zoom = 1.0;
    private boolean fitMode = true;

    /** Viewport size the last fit was computed against; lets {@link #relayout()} ignore self-induced jitter. */
    private double lastFitViewW = -1;

    private double lastFitViewH = -1;

    public PdfViewerPane(Path path) {
        this.path = path;
        this.title = path.getFileName() == null
                ? path.toString()
                : path.getFileName().toString();
        root.getStyleClass().add("pdf-viewer");
        build();
        load();
    }

    private void build() {
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        StackPane holder = new StackPane(imageView);
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
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() || e.isControlDown()) {
                setZoom(e.getDeltaY() >= 0 ? zoom * ZOOM_STEP : zoom / ZOOM_STEP);
                e.consume();
            }
        });
        root.setCenter(scroll);
        root.setTop(buildToolbar());
    }

    private Node buildToolbar() {
        prevButton = button("◀", tr("pdfviewer.prevPage"), () -> showPage(currentPage - 1));
        nextButton = button("▶", tr("pdfviewer.nextPage"), () -> showPage(currentPage + 1));
        pageLabel.getStyleClass().add("pdf-viewer-page");
        Button out = button("−", tr("imageviewer.zoomOut"), () -> setZoom(zoom / ZOOM_STEP));
        Button in = button("+", tr("imageviewer.zoomIn"), () -> setZoom(zoom * ZOOM_STEP));
        Button fit = button(tr("imageviewer.fit"), tr("imageviewer.fitTip"), this::fitToWindow);
        Button actual = button(tr("imageviewer.actualSize"), tr("imageviewer.actualSizeTip"), () -> {
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
        HBox bar = new HBox(
                6, prevButton, pageLabel, nextButton, sep(), out, in, fit, actual, zoomLabel, spacer, readOnly);
        bar.getStyleClass().add("pdf-viewer-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        return bar;
    }

    private static Label sep() {
        Label l = new Label("|");
        l.getStyleClass().add("pdf-viewer-sep");
        return l;
    }

    private Button button(String text, String tip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("pdf-viewer-btn");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private void load() {
        long g = renderGen.incrementAndGet();
        exec.submit(() -> {
            try {
                long size = Files.size(path);
                if (size > MAX_BYTES) {
                    postError(tr("pdfviewer.tooLarge"));
                    return;
                }
                byte[] bytes = Files.readAllBytes(path); // provider-agnostic (local + SFTP)
                document = Loader.loadPDF(bytes);
                renderer = new PDFRenderer(document);
                int count = document.getNumberOfPages();
                if (count <= 0) {
                    postError(tr("pdfviewer.loadFailed"));
                    return;
                }
                Image first = renderToImage(0);
                Platform.runLater(() -> {
                    if (disposed || g != renderGen.get()) {
                        return;
                    }
                    pageCount = count;
                    currentPage = 0;
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
        BufferedImage bi = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
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
        center.setPadding(new Insets(24));
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

    public Path getPath() {
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
