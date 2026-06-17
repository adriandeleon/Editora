package com.editora.editor;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.geometry.size.FloatSize;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;

/**
 * Loads images for the Markdown preview off the FX thread, adding support JavaFX's own decoder lacks:
 * <b>SVG</b> (e.g. shields.io / GitHub badge images, which are served as {@code image/svg+xml}). It
 * fetches the bytes (http(s)/file/data URLs), rasterizes SVG via {@link SVGLoader JSVG} and decodes
 * everything else with JavaFX, then applies the result on the FX thread. Results are cached per URL,
 * and recent failures are remembered briefly so a re-render doesn't hammer an unreachable host.
 *
 * <p>Off the hot paths: all network/parse/raster work runs on a small daemon pool; only the final
 * {@code setImage} touches the FX thread.
 */
final class PreviewImageLoader {

    private static final int TIMEOUT_MS = 6000;
    /** Don't re-attempt a failed URL for this long (lets an offline → online retry eventually succeed). */
    private static final long FAILURE_TTL_MS = 60_000;
    /** SVGs render at this device scale for crispness, then display at their logical size. */
    private static final double RASTER_SCALE = 2.0;

    /**
     * Strong reference to JSVG's logger. Badges often embed a logo as a nested SVG {@code <image>}, which
     * JSVG can't decode-in-decode; it logs a WARNING (with a stack trace) per occurrence. The badge itself
     * still renders, so we quiet that noise to SEVERE. The field must be {@code static final} (not a bare
     * {@code Logger.getLogger(...)} call): {@code java.util.logging} holds only a <em>weak</em> reference to
     * a logger, so without a strong ref of our own the logger is eventually GC'd, the SEVERE level is lost,
     * and the warning leaks back through at the inherited level. (Mirrors {@code App.TM4E_LOG}/{@code LSP4J_LOG}.)
     */
    private static final java.util.logging.Logger JSVG_LOG =
            java.util.logging.Logger.getLogger("com.github.weisj.jsvg");

    static {
        JSVG_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    /** Cap on cached decoded images. Each holds a GPU texture, so the cache is bounded (LRU) instead
     *  of growing unbounded as more Markdown files with images/badges are previewed. */
    private static final int MAX_CACHED_IMAGES = 64;

    private static final Map<String, Loaded> CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Loaded>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Loaded> eldest) {
                    return size() > MAX_CACHED_IMAGES;
                }
            });
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "md-image-loader");
        t.setDaemon(true);
        return t;
    });

    /** A decoded image plus its logical (CSS-pixel) width, used to size the {@link ImageView}. */
    public record Loaded(Image image, double logicalWidth) {}

    private PreviewImageLoader() {}

    /** Loads {@code url} into {@code view} (cached), sizing it to its logical width capped at {@code maxWidth}. */
    static void loadInto(ImageView view, String url, double maxWidth) {
        Loaded hit = CACHE.get(url);
        if (hit != null) {
            apply(view, hit, maxWidth);
            return;
        }
        Long failedAt = FAILED.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_TTL_MS) {
            return; // recently unreachable — leave the slot blank rather than refetch on every re-render
        }
        EXEC.submit(() -> {
            Loaded loaded = load(url);
            if (loaded == null) {
                FAILED.put(url, System.currentTimeMillis());
                return;
            }
            FAILED.remove(url);
            CACHE.put(url, loaded);
            Platform.runLater(() -> apply(view, loaded, maxWidth));
        });
    }

    private static void apply(ImageView view, Loaded loaded, double maxWidth) {
        view.setImage(loaded.image());
        view.setFitWidth(Math.min(loaded.logicalWidth(), maxWidth));
    }

    private static Loaded load(String url) {
        try {
            byte[] bytes = fetch(url);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            if (looksLikeSvg(bytes)) {
                return rasterizeSvg(bytes, url);
            }
            Image img = new Image(new ByteArrayInputStream(bytes));
            if (img.isError() || img.getWidth() <= 0) {
                return null;
            }
            return new Loaded(img, img.getWidth());
        } catch (Exception | LinkageError e) {
            return null; // unreachable host, malformed SVG, decode failure — leave the image blank
        }
    }

    /** Sniffs whether {@code bytes} is SVG (XML prolog/comment then an {@code <svg} tag). Pure; unit-tested. */
    static boolean looksLikeSvg(byte[] bytes) {
        int n = Math.min(bytes.length, 1024);
        String head = new String(bytes, 0, n, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return head.contains("<svg");
    }

    private static byte[] fetch(String url) throws IOException {
        if (url.startsWith("data:")) {
            return decodeDataUri(url);
        }
        URLConnection con = URI.create(url).toURL().openConnection();
        con.setConnectTimeout(TIMEOUT_MS);
        con.setReadTimeout(TIMEOUT_MS);
        // Some CDNs (incl. shields.io) reject requests without a User-Agent.
        con.setRequestProperty("User-Agent", "Editora");
        try (InputStream in = con.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** Decodes a {@code data:} URI's payload (base64 or percent-encoded). */
    private static byte[] decodeDataUri(String url) {
        int comma = url.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String meta = url.substring(5, comma);
        String data = url.substring(comma + 1);
        if (meta.toLowerCase(Locale.ROOT).contains(";base64")) {
            return Base64.getMimeDecoder().decode(data);
        }
        return URLDecoder.decode(data, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    }

    private static Loaded rasterizeSvg(byte[] bytes, String url) {
        SVGDocument doc =
                new SVGLoader().load(new ByteArrayInputStream(bytes), uriOrNull(url), LoaderContext.createDefault());
        if (doc == null) {
            return null;
        }
        FloatSize size = doc.size();
        double w = size.width > 0 ? size.width : 100;
        double h = size.height > 0 ? size.height : 20;
        int pw = (int) Math.ceil(w * RASTER_SCALE);
        int ph = (int) Math.ceil(h * RASTER_SCALE);
        BufferedImage buf = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buf.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(RASTER_SCALE, RASTER_SCALE);
        doc.render((java.awt.Component) null, g, new ViewBox(0, 0, (float) w, (float) h));
        g.dispose();
        return new Loaded(toFxImage(buf), w); // backing bitmap is 2×; ImageView displays it at logical width w
    }

    private static URI uriOrNull(String url) {
        try {
            return URI.create(url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Copies an ARGB {@link BufferedImage} into a JavaFX {@link WritableImage} (no javafx.swing needed). */
    static Image toFxImage(BufferedImage buf) {
        int w = buf.getWidth();
        int h = buf.getHeight();
        WritableImage out = new WritableImage(w, h);
        int[] row = new int[w];
        var writer = out.getPixelWriter();
        var fmt = PixelFormat.getIntArgbInstance();
        for (int y = 0; y < h; y++) {
            buf.getRGB(0, y, w, 1, row, 0, w);
            writer.setPixels(0, y, w, 1, fmt, row, 0, w);
        }
        return out;
    }
}
