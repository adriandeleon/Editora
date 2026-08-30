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
import javafx.scene.layout.StackPane;

import com.editora.i18n.Messages;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas;
import com.github.weisj.jsvg.view.ViewBox;

/**
 * Renders standalone {@code .svg} files to JavaFX nodes for the 3-mode preview, asynchronously — the
 * in-process analogue of {@link MermaidImages}/{@code DiagramImages}, but drawn by JSVG rather than an
 * external CLI, so it needs no tool and no theme. A daemon executor + an LRU cache keyed by a hash of the
 * source, so the debounced whole-document re-render doesn't re-parse an unchanged SVG.
 *
 * <p><b>Rendered natively through {@code FXSVGCanvas}, not rasterized.</b> The preview used to draw the SVG
 * once into a 2x bitmap and scale that, which is blurry the moment the zoom passes 2x — on the one preview
 * whose entire content is vector art. The canvas redraws from the document at whatever size it is given, so
 * zoom stays crisp at any level, and a transparent SVG gets a checkerboard behind it instead of sitting
 * invisibly on the pane's background.
 *
 * <p><b>Only this preview moved.</b> Markdown badge images stay on {@link PreviewImageLoader#rasterizeSvg}
 * because that cache hands the same {@code Image} to every occurrence and a {@code Node} cannot be shared
 * between two parents; PDF, print and the office writers stay on
 * {@link PreviewImageLoader#svgToPng} because they need bytes, off the FX thread, which a scene-graph
 * control cannot give them.
 *
 * <p>The cache holds the parsed {@link SVGDocument} rather than a rendered image — cheaper, and it pins no
 * GPU texture, so the bound here is now about parse work rather than video memory. A document is treated as
 * immutable render input and may back more than one canvas (the same file previewed in two windows);
 * animation is deliberately left off, which is what keeps that true.
 */
public final class SvgImages {

    private record Cached(SVGDocument document, double width, double height, String error) {}

    /** Cap on cached parsed SVGs (bounded LRU; a document is plain heap, unlike the images this replaced). */
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
     * A node for the SVG {@code source}, filled asynchronously: a centered {@code FXSVGCanvas} on success,
     * or a {@code .svg-error} label on a parse failure. Cache hits apply immediately. {@code sizer} maps the
     * SVG's logical width to the displayed width (a standalone preview multiplies by the zoom).
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
            SVGDocument doc = PreviewImageLoader.parseSvg(source.getBytes(StandardCharsets.UTF_8));
            Cached result = cachedFor(doc);
            CACHE.put(key, result);
            Platform.runLater(() -> applyCached(host, result, sizer));
        });
    }

    /** A parsed document plus the size to draw it at, or the error to show instead. */
    private static Cached cachedFor(SVGDocument doc) {
        if (doc == null) {
            return new Cached(null, 0, 0, Messages.tr("svg.renderFailed"));
        }
        // An SVG need not declare a size; fall back to the same defaults the rasterizer used so a
        // dimensionless document still gets a sane box rather than collapsing to nothing.
        var size = doc.size();
        double w = size.width > 0 ? size.width : DEFAULT_WIDTH;
        double h = size.height > 0 ? size.height : DEFAULT_HEIGHT;
        return new Cached(doc, w, h, null);
    }

    /** Fallbacks for an SVG that declares no intrinsic size (mirrors the rasterizer's). */
    private static final double DEFAULT_WIDTH = 100;

    private static final double DEFAULT_HEIGHT = 20;

    private static void applyCached(StackPane host, Cached c, java.util.function.DoubleUnaryOperator sizer) {
        if (c.document() == null) {
            host.getChildren().setAll(errorNode(c.error()));
            return;
        }
        host.getChildren().setAll(canvasFor(c, sizer));
    }

    /**
     * The canvas for a cached document at the caller's width, aspect preserved.
     *
     * <p>Two things here are load-bearing and neither is obvious from the API.
     *
     * <p><b>The view box is the TARGET rectangle, not the source.</b> Setting it to the document's own size
     * makes the canvas draw at natural size in the corner of whatever box it is given — the control grows
     * with the zoom and the artwork does not, which is the exact opposite of the point. It is therefore set
     * to the display size. Measured while getting this wrong: a 200x50 SVG in a 400x100 canvas painted 25%
     * of its box, the artwork sitting at 1:1.
     *
     * <p><b>The size must be pinned, max included.</b> {@code FXSVGCanvas} is a {@code Control} whose skin
     * computes no preferred size, so unsized it lays out at zero; and pinning only pref lets the centering
     * {@code StackPane} stretch it to fill the pane, distorting the drawing (measured: 800x600 for a box
     * that should be 800x200).
     */
    private static FXSVGCanvas canvasFor(Cached c, java.util.function.DoubleUnaryOperator sizer) {
        double w = Math.max(1, sizer.applyAsDouble(c.width()));
        double h = Math.max(1, w * (c.height() / c.width()));
        FXSVGCanvas canvas = new FXSVGCanvas();
        canvas.setDocument(c.document());
        canvas.setViewBox(new ViewBox(0, 0, (float) w, (float) h));
        // A checkerboard behind transparency, so a white-on-transparent icon is visible rather than
        // appearing blank against a light preview background.
        canvas.setShowTransparentPattern(true);
        // Left off deliberately: an animated document drives a per-frame tick, and nothing here stops it
        // when the tab goes to the background or closes. Turning it on needs that lifecycle first.
        canvas.setAnimated(false);
        canvas.setPrefSize(w, h);
        canvas.setMinSize(w, h);
        canvas.setMaxSize(w, h);
        return canvas;
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
