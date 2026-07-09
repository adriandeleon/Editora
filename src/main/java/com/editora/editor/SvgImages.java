package com.editora.editor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import com.editora.i18n.Messages;

/**
 * Renders standalone {@code .svg} files to JavaFX nodes for the 3-mode preview, asynchronously — the
 * in-process analogue of {@link MermaidImages}/{@code DiagramImages}, but rasterizing via JSVG
 * ({@link PreviewImageLoader#rasterizeSvg}) rather than an external CLI, so it needs no tool and no theme.
 * A daemon executor + an LRU cache keyed by a hash of the source, so the debounced whole-document
 * re-render doesn't re-rasterize an unchanged SVG (and a zoom is a cheap re-fit of the cached image).
 * The {@code editor} package already owns the JSVG rasterizer (for Markdown badge images), so no new
 * dependency.
 */
public final class SvgImages {

    private record Cached(PreviewImageLoader.Loaded loaded, String error) {}

    /** Cap on cached rendered SVGs (each successful entry pins a GPU texture — bounded LRU). */
    private static final int MAX_CACHED = 48;

    private static final Map<String, Cached> CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Cached>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Cached> eldest) {
                    return size() > MAX_CACHED;
                }
            });
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "svg-render");
        t.setDaemon(true);
        return t;
    });

    private SvgImages() {}

    /**
     * A node for the SVG {@code source}, filled asynchronously: a centered {@link ImageView} on success, or
     * a {@code .svg-error} label on a parse/render failure. Cache hits apply immediately. {@code sizer} maps
     * the SVG's logical width to the displayed fit width (a standalone preview multiplies by the zoom).
     */
    public static Node node(String source, java.util.function.DoubleUnaryOperator sizer) {
        StackPane host = new StackPane();
        host.getStyleClass().add("md-svg");
        fill(host, source, sizer);
        return host;
    }

    private static void fill(StackPane host, String source, java.util.function.DoubleUnaryOperator sizer) {
        String key = key(source);
        Cached hit = CACHE.get(key);
        if (hit != null) {
            applyCached(host, hit, sizer);
            return;
        }
        host.getChildren().setAll(placeholder(Messages.tr("svg.rendering")));
        EXEC.submit(() -> {
            PreviewImageLoader.Loaded loaded = PreviewImageLoader.rasterizeSvg(source.getBytes(StandardCharsets.UTF_8));
            Cached result =
                    loaded != null ? new Cached(loaded, null) : new Cached(null, Messages.tr("svg.renderFailed"));
            CACHE.put(key, result);
            Platform.runLater(() -> applyCached(host, result, sizer));
        });
    }

    private static void applyCached(StackPane host, Cached c, java.util.function.DoubleUnaryOperator sizer) {
        if (c.loaded() != null) {
            ImageView view = new ImageView(c.loaded().image());
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setFitWidth(Math.max(1, sizer.applyAsDouble(c.loaded().logicalWidth())));
            host.getChildren().setAll(view);
        } else {
            host.getChildren().setAll(errorNode(c.error()));
        }
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("svg-placeholder");
        return label;
    }

    private static Label errorNode(String message) {
        Label label = new Label(message == null || message.isBlank() ? Messages.tr("svg.renderFailed") : message);
        label.getStyleClass().add("svg-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /** Cache key = sha-256 of the SVG source, so editing invalidates but a re-render of the same text hits. */
    private static String key(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return source.length() + ":" + source.hashCode();
        }
    }
}
