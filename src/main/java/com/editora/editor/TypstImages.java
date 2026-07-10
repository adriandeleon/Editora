package com.editora.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleUnaryOperator;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.typst.TypstRenderer;

/**
 * Renders Typst documents to JavaFX nodes for the 3-mode preview, asynchronously and <b>multi-page</b>. The
 * document-oriented analogue of {@link MermaidImages}/{@link DiagramImages}: a static façade with a daemon
 * executor and an LRU result cache, so the synchronous {@code EditorBuffer} preview build can drop a node
 * and have it filled later off the FX thread. Each cache entry is a <em>list</em> of page images (Typst
 * documents paginate), stacked in a {@link VBox}.
 *
 * <p><b>Retain-last-image.</b> On every edit the source hash changes, so a naive re-render would blank to a
 * "rendering…" placeholder and flash. Instead, the last successfully-rendered pages are kept per preview
 * ({@code retainKey}) and shown immediately while the new render runs — a placeholder appears only on the
 * <em>first</em> render, and a compile failure keeps the last good pages visible under a non-destructive
 * error banner. So editing a multi-page document updates in place without flicker.
 *
 * <p>Configured (enabled flag, command) from {@code TypstCoordinator.applySupport} via {@link #configure} —
 * the editor package can't depend on {@code ui}/{@code typst-service}, like the Mermaid/diagram injection.
 */
public final class TypstImages {

    private record Loaded(Image image, double logicalWidth) {}

    /** {@code more} = pages beyond the display cap that were not rendered into the preview (0 = all shown). */
    private record Cached(List<Loaded> pages, String error, int more) {
        boolean ok() {
            return error == null && pages != null && !pages.isEmpty();
        }
    }

    /** Cap on cached rendered documents (each page pins a GPU texture — bounded LRU). */
    private static final int MAX_CACHED = 8;

    /** Cap on pages stacked in the preview — a many-page document would otherwise pin one texture per page.
     *  Beyond this the preview shows the first N pages + a "… more pages" note; export/print use all pages. */
    private static final int MAX_PREVIEW_PAGES = 40;

    private static final Map<String, Cached> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    /** The last good render per preview surface, so a re-render shows it instead of a placeholder. */
    private static final Map<String, List<Loaded>> LAST_GOOD =
            Collections.synchronizedMap(new LinkedHashMap<String, List<Loaded>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Loaded>> eldest) {
                    return size() > MAX_CACHED * 2;
                }
            });

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "typst-render");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean enabled;
    private static volatile List<String> command = List.of("typst");

    private TypstImages() {}

    /** Pushes the live Typst config (called at startup + every settings apply). */
    public static void configure(boolean enabled, List<String> command) {
        TypstImages.enabled = enabled;
        if (command != null && !command.isEmpty()) {
            TypstImages.command = List.copyOf(command);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns a node for the Typst {@code source}, filled asynchronously with one {@link ImageView} per
     * page. {@code sizer} maps a page's logical width to the displayed fit width (the standalone preview
     * multiplies by the zoom factor). {@code retainKey} identifies the preview surface for retain-last-image;
     * {@code fileDir} is the saved file's own folder (where relative refs resolve) and {@code root} the
     * {@code --root} sandbox (possibly a higher project root) — both {@code null} for an untitled/remote buffer.
     */
    public static Node node(String source, DoubleUnaryOperator sizer, String retainKey, Path fileDir, Path root) {
        StackPane host = new StackPane();
        host.getStyleClass().add("md-diagram");
        fill(host, source, sizer, retainKey, fileDir, root);
        return host;
    }

    private static void fill(
            StackPane host, String source, DoubleUnaryOperator sizer, String retainKey, Path fileDir, Path root) {
        String key = key(source);
        Cached hit = CACHE.get(key);
        if (hit != null) {
            applyCached(host, hit, sizer, retainKey);
            return;
        }
        List<Loaded> retained = LAST_GOOD.get(retainKey);
        if (retained != null && !retained.isEmpty()) {
            host.getChildren().setAll(pagesColumn(retained, sizer, null, 0)); // keep last pages while re-rendering
        } else {
            host.getChildren().setAll(placeholder(Messages.tr("typst.rendering")));
        }
        List<String> cmd = command;
        EXEC.submit(() -> {
            TypstRenderer.Pages r = TypstRenderer.renderPages(cmd, source, fileDir, root);
            Cached result;
            if (r.ok()) {
                int total = r.pages().size();
                int show = Math.min(total, MAX_PREVIEW_PAGES);
                List<Loaded> pages = new ArrayList<>();
                for (int i = 0; i < show; i++) {
                    Image img = new Image(new ByteArrayInputStream(r.pages().get(i)));
                    if (img.isError() || img.getWidth() <= 0) {
                        pages = null;
                        break;
                    }
                    pages.add(new Loaded(img, img.getWidth() / TypstRenderer.RENDER_SCALE));
                }
                result = pages == null
                        ? new Cached(null, Messages.tr("typst.renderFailed"), 0)
                        : new Cached(pages, null, total - show);
            } else {
                result = new Cached(null, r.error(), 0);
            }
            CACHE.put(key, result);
            if (result.ok()) {
                LAST_GOOD.put(retainKey, result.pages());
            }
            Platform.runLater(() -> applyCached(host, result, sizer, retainKey));
        });
    }

    private static void applyCached(StackPane host, Cached c, DoubleUnaryOperator sizer, String retainKey) {
        if (c.ok()) {
            host.getChildren().setAll(pagesColumn(c.pages(), sizer, null, c.more()));
            return;
        }
        List<Loaded> retained = LAST_GOOD.get(retainKey);
        if (retained != null && !retained.isEmpty()) {
            // A compile error mid-edit: keep the last good pages, surface the error non-destructively.
            host.getChildren().setAll(pagesColumn(retained, sizer, c.error(), 0));
        } else {
            host.getChildren().setAll(errorNode(c.error()));
        }
    }

    private static VBox pagesColumn(List<Loaded> pages, DoubleUnaryOperator sizer, String error, int more) {
        VBox column = new VBox(12);
        column.getStyleClass().add("typst-pages");
        if (error != null) {
            column.getChildren().add(errorBanner(error));
        }
        for (Loaded p : pages) {
            ImageView view = new ImageView(p.image());
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setFitWidth(Math.max(1, sizer.applyAsDouble(p.logicalWidth())));
            view.getStyleClass().add("typst-page");
            column.getChildren().add(view);
        }
        if (more > 0) {
            Label note = new Label(Messages.tr("typst.morePages", more));
            note.getStyleClass().add("diagram-placeholder");
            column.getChildren().add(note);
        }
        return column;
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("diagram-placeholder");
        return label;
    }

    private static Label errorBanner(String message) {
        Label label = new Label(banner(message));
        label.getStyleClass().add("typst-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label errorNode(String message) {
        Label label = new Label(banner(message));
        label.getStyleClass().add("diagram-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static String banner(String message) {
        return message == null || message.isBlank()
                ? Messages.tr("typst.renderFailed")
                : Messages.tr("typst.renderFailed") + "\n" + message.strip();
    }

    /** Cache key = sha-256 of the source, so editing invalidates but a re-render of unchanged text hits. */
    private static String key(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return source.length() + ":" + source.hashCode();
        }
    }
}
