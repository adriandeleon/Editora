package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

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

import com.editora.editor.TabContent;

import static com.editora.i18n.Messages.tr;

/**
 * A read-only image viewer that lives in an editor tab ({@link TabContent}) — so opening a {@code .png} /
 * {@code .jpg} / {@code .gif} / {@code .bmp} renders the picture instead of dumping its binary bytes into a
 * text buffer (see {@link ImageFormats}). A small top bar shows the dimensions + a "read-only" hint and offers
 * zoom out / in / fit-to-window / actual-size; the image sits in a scroll pane, and {@code Ctrl}+wheel zooms.
 * Nothing here is editable. {@link #dispose()} drops the decoded {@link Image} so its GPU texture is released
 * when the tab closes (bounding the Prism texture pool — see the perf notes in CLAUDE.md).
 *
 * <p>The file is read and decoded on a {@link ViewerLoads} worker, never on the FX thread; the tab shows a
 * "loading" note until the image lands (a result for a tab closed meanwhile is dropped). The decode is
 * bounded by pixel count, not just file size: an image over {@link ViewerLoads#MAX_PIXELS} is decoded at a
 * reduced size and the bar says so, and a failure — unreadable file, unknown format, out of memory — is
 * shown as a message in the tab.
 */
public final class ImageViewerPane implements TabContent {

    /** Read at most this many bytes; a larger file is refused (protects heap + the VRAM texture pool). */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final double MIN_ZOOM = 0.05;
    private static final double MAX_ZOOM = 20.0;
    private static final double ZOOM_STEP = 1.25;

    private final Path path;
    private final String title;
    private final BorderPane root = new BorderPane();
    private final ImageView imageView = new ImageView();
    private final ScrollPane scroll = new ScrollPane();
    private final Label zoomLabel = new Label();
    private final Label noteLabel = new Label();
    private final long maxPixels;
    private final CompletableFuture<Void> loaded = new CompletableFuture<>();
    private volatile boolean disposed;
    private Node toolbar;
    private Image image;
    /** The picture's own pixel size — the decoded {@link #image} is smaller when it was reduced. */
    private double naturalWidth;

    private double naturalHeight;
    private double zoom = 1.0;
    private boolean fitMode = true; // start fit-to-window so a large image is visible at once

    public ImageViewerPane(Path path) {
        this(path, ViewerLoads.MAX_PIXELS);
    }

    /** Test seam: a small pixel cap, so the reduced-decode path runs on a small file. */
    ImageViewerPane(Path path, long maxPixels) {
        this.path = path;
        this.maxPixels = maxPixels;
        this.title = path.getFileName() == null
                ? path.toString()
                : path.getFileName().toString();
        root.getStyleClass().add("image-viewer");
        build();
        load();
    }

    private void build() {
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        // The holder is at least viewport-sized so a small image is centered; it grows to the (scaled) image
        // when larger, which is what lets the ScrollPane scroll a zoomed-in image.
        StackPane holder = new StackPane(imageView);
        holder.getStyleClass().add("image-viewer-holder");
        holder.setPadding(new Insets(12));
        holder.minWidthProperty()
                .bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> scroll.getViewportBounds().getWidth(), scroll.viewportBoundsProperty()));
        holder.minHeightProperty()
                .bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> scroll.getViewportBounds().getHeight(), scroll.viewportBoundsProperty()));
        scroll.setContent(holder);
        scroll.setPannable(true);
        // Re-fit when the viewport resizes (window resize / tab first shown) while in fit mode.
        scroll.viewportBoundsProperty().addListener((o, w, n) -> relayout());
        // Ctrl+wheel zooms (like the editor); a plain wheel scrolls the pane as usual.
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() || e.isControlDown()) {
                setZoom(e.getDeltaY() >= 0 ? zoom * ZOOM_STEP : zoom / ZOOM_STEP);
                e.consume();
            }
        });
        toolbar = buildToolbar();
        showMessage(tr("imageviewer.loading"), "image-viewer-loading"); // until the worker delivers the image
    }

    private Node buildToolbar() {
        Button out = zoomButton("−", tr("imageviewer.zoomOut"), () -> setZoom(zoom / ZOOM_STEP));
        Button in = zoomButton("+", tr("imageviewer.zoomIn"), () -> setZoom(zoom * ZOOM_STEP));
        Button fit = zoomButton(tr("imageviewer.fit"), tr("imageviewer.fitTip"), this::fitToWindow);
        Button actual = zoomButton(tr("imageviewer.actualSize"), tr("imageviewer.actualSizeTip"), () -> {
            fitMode = false;
            setZoom(1.0);
        });
        zoomLabel.getStyleClass().add("image-viewer-zoom");
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label readOnly = new Label(tr("imageviewer.readOnly"));
        readOnly.getStyleClass().add("image-viewer-readonly");
        noteLabel.getStyleClass().add("image-viewer-note");
        HBox bar = new HBox(6, out, in, fit, actual, zoomLabel, noteLabel, spacer, readOnly);
        bar.getStyleClass().add("image-viewer-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        return bar;
    }

    private Button zoomButton(String text, String tip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("image-viewer-btn");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    /** A decode result handed from the worker to the FX thread. */
    private record Decoded(Image image, int width, int height, boolean reduced) {}

    private void load() {
        ViewerLoads.submit(() -> {
            Decoded decoded = null;
            String error = null;
            try {
                decoded = decode();
                if (decoded == null) {
                    error = tr("imageviewer.loadFailed");
                }
            } catch (TooLargeException | OutOfMemoryError e) {
                error = tr("imageviewer.tooLarge");
            } catch (Throwable e) { // unreadable file, a decoder bug: say so in the tab, never fail silently
                error = tr("imageviewer.loadFailed");
            }
            Decoded result = decoded;
            String message = error;
            Platform.runLater(() -> {
                try {
                    if (!disposed) {
                        if (result == null) {
                            showMessage(message, "image-viewer-error");
                        } else {
                            show(result);
                        }
                    }
                } finally {
                    loaded.complete(null);
                }
            });
        });
    }

    /** Worker thread: reads the file and decodes it within the pixel cap; null when it is not a picture. */
    private Decoded decode() throws Exception {
        if (Files.size(path) > MAX_BYTES) {
            throw new TooLargeException();
        }
        byte[] bytes = Files.readAllBytes(path); // provider-agnostic (works for local + SFTP)
        if (disposed) {
            return null;
        }
        // The header says how big the bitmap will be; decide the decode size before allocating it.
        int[] size = ViewerLoads.dimensions(bytes);
        int[] reduced = size == null ? null : ViewerLoads.reducedSize(size[0], size[1], maxPixels);
        Image img = reduced == null
                ? new Image(new ByteArrayInputStream(bytes))
                : new Image(new ByteArrayInputStream(bytes), reduced[0], reduced[1], true, true);
        if (img.isError() || img.getWidth() <= 0 || img.getHeight() <= 0) {
            return null;
        }
        int w = size == null ? (int) Math.round(img.getWidth()) : size[0];
        int h = size == null ? (int) Math.round(img.getHeight()) : size[1];
        return new Decoded(img, w, h, reduced != null);
    }

    /** The file (or the bitmap it would decode to) is over a hard limit. */
    private static final class TooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private void show(Decoded decoded) {
        image = decoded.image();
        naturalWidth = decoded.width();
        naturalHeight = decoded.height();
        noteLabel.setText(decoded.reduced() ? tr("imageviewer.reduced", decoded.width(), decoded.height()) : "");
        imageView.setImage(image);
        root.setCenter(scroll);
        root.setTop(toolbar);
        fitToWindow();
    }

    private void showMessage(String message, String styleClass) {
        Label label = new Label(message);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        StackPane center = new StackPane(label);
        center.setPadding(new Insets(24));
        root.setCenter(center);
        root.setTop(null); // no zoom controls when there's nothing to show
    }

    /** Fits the image to the current viewport (or natural size if that's smaller); leaves fit mode on. */
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
        double natW = naturalWidth; // zoom is relative to the picture's real size, even when decoded reduced
        double natH = naturalHeight;
        if (fitMode) {
            double viewW = scroll.getViewportBounds().getWidth() - 24; // minus the holder padding
            double viewH = scroll.getViewportBounds().getHeight() - 24;
            if (viewW > 1 && viewH > 1) {
                zoom = Math.min(1.0, Math.min(viewW / natW, viewH / natH)); // fit, but never upscale past 100%
            } else {
                zoom = 1.0; // viewport not laid out yet — natural size, re-fit on the next layout pulse
            }
            if (!Double.isFinite(zoom) || zoom <= 0) {
                zoom = 1.0;
            }
        }
        imageView.setFitWidth(natW * zoom); // preserveRatio scales height to match
        zoomLabel.setText(Math.round(zoom * 100) + "%");
    }

    /** Re-fits when the tab is first shown / the window resizes (call after the node is in a laid-out scene). */
    void relayout() {
        if (fitMode) {
            apply();
        }
    }

    /** True once the image decoded successfully (false while loading / failed / refused). Test accessor. */
    boolean hasImage() {
        return image != null && !image.isError();
    }

    /** Completes once the background load has been applied (or dropped, for a disposed pane). Test accessor. */
    CompletableFuture<Void> loadedForTest() {
        return loaded;
    }

    /** The decoded picture for Print and Export to PDF, or null while loading and when it failed to load. */
    Image printableImage() {
        return hasImage() ? image : null;
    }

    /** The decoded bitmap (smaller than the picture when it was reduced). Test accessor. */
    Image imageForTest() {
        return image;
    }

    /** Releases the decoded image (and its GPU texture) when the tab closes; a load still running is dropped. */
    public void dispose() {
        disposed = true;
        imageView.setImage(null);
        image = null;
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
