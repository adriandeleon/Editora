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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

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
public final class PreviewImageLoader {

    private static final int TIMEOUT_MS = 6000;
    /** Don't re-attempt a failed URL for this long (lets an offline → online retry eventually succeed). */
    private static final long FAILURE_TTL_MS = 60_000;
    /** SVGs render at this device scale for crispness, then display at their logical size. */
    private static final double RASTER_SCALE = 2.0;
    /** Cap on the bytes of one image, from any source — a preview image is content of any size, and an
     *  endless response (or {@code /dev/zero} behind a link) must not be read into the heap. */
    static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    /** Cap on an SVG's raster, in pixels (16 MP ≈ 64 MB of ARGB): {@code width="100000" height="100000"} in a
     *  200-byte file would otherwise ask for a 160 GB bitmap. Larger documents are scaled down to fit. */
    static final long MAX_RASTER_PIXELS = 16L * 1024 * 1024;
    /** Cap on either side of that raster, so a sliver ({@code width="1e9" height="0.001"}) is bounded too. */
    static final int MAX_RASTER_SIDE = 16_384;

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
     *  of growing unbounded as more Markdown files with images/badges are previewed. Entry count is only
     *  half the bound — see {@link ImageCacheBudget} for the byte cap that backs it up, since a preview
     *  image is user content of any size. */
    private static final int MAX_CACHED_IMAGES = 64;

    private static final Map<String, Loaded> CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Loaded>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Loaded> eldest) {
                    return size() > MAX_CACHED_IMAGES;
                }
            });
    /** Most failed URLs remembered; the map only spares a retry within the failure TTL, so old ones may go. */
    static final int MAX_FAILED = 256;

    /** When each URL last failed. Bounded (LRU): it grew by one entry per broken link ever previewed. */
    private static final Map<String, Long> FAILED = failureMemory(MAX_FAILED);

    /** A synchronized, access-ordered map that forgets its least recently used entry beyond {@code max}. */
    static Map<String, Long> failureMemory(int max) {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Long>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                return size() > max;
            }
        });
    }

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
            Loaded loaded = loadAndCache(url);
            if (loaded != null) {
                Platform.runLater(() -> apply(view, loaded, maxWidth));
            }
        });
    }

    /** Loads {@code url} on the calling thread and records the outcome in the cache or the failure memory. */
    private static Loaded loadAndCache(String url) {
        Loaded loaded = load(url);
        if (loaded == null) {
            FAILED.put(url, System.currentTimeMillis());
            return null;
        }
        FAILED.remove(url);
        CACHE.put(url, loaded);
        synchronized (CACHE) { // byte cap behind the count cap: one huge image is not 1/64th of a budget
            ImageCacheBudget.trim(
                    CACHE, l -> ImageCacheBudget.footprint(l.image()), ImageCacheBudget.PREVIEW_BUDGET_BYTES);
        }
        return loaded;
    }

    /**
     * Loads every one of {@code urls} and waits for them, for at most {@code timeoutMs} in total — for print,
     * which has to know each image's size before it paginates and cannot wait on a host that never answers.
     * A URL that failed, or was still loading at the deadline, is simply absent from the result. Blocks; call
     * off the FX thread.
     */
    static Map<String, Loaded> loadAll(java.util.Collection<String> urls, long timeoutMs) {
        Map<String, Loaded> out = new java.util.HashMap<>();
        java.util.List<String> pending = new java.util.ArrayList<>();
        for (String url : urls) {
            Loaded hit = CACHE.get(url);
            if (hit != null) {
                out.put(url, hit);
            } else {
                pending.add(url);
            }
        }
        if (pending.isEmpty()) {
            return out;
        }
        java.util.List<java.util.concurrent.Callable<Loaded>> tasks = new java.util.ArrayList<>();
        for (String url : pending) {
            tasks.add(() -> loadAndCache(url));
        }
        try {
            java.util.List<java.util.concurrent.Future<Loaded>> done =
                    EXEC.invokeAll(tasks, Math.max(1, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            for (int i = 0; i < done.size(); i++) {
                Loaded loaded = finished(done.get(i));
                if (loaded != null) {
                    out.put(pending.get(i), loaded);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static Loaded finished(java.util.concurrent.Future<Loaded> f) throws InterruptedException {
        try {
            return f.isCancelled() ? null : f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            return null;
        }
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
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            // unreachable host, malformed SVG, decode failure, or a decompression bomb whose bitmap does not
            // fit — leave the image blank (an OOM here is one failed allocation, not a wrecked heap)
            return null;
        }
    }

    /** Sniffs whether {@code bytes} is SVG (XML prolog/comment then an {@code <svg} tag). Pure; unit-tested. */
    public static boolean looksLikeSvg(byte[] bytes) {
        int n = Math.min(bytes.length, 1024);
        String head = new String(bytes, 0, n, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return head.contains("<svg");
    }

    /**
     * Fetches image bytes for a URL — an {@code http(s)} download (with a timeout + User-Agent) or a
     * {@code data:} URI decoded inline. For a browser-image drop; call off the FX thread.
     */
    public static byte[] fetchBytes(String url) throws IOException {
        return fetch(url);
    }

    /**
     * Image bytes for an <b>export</b> (PDF / DOCX / ODT), or {@code null}: a {@code data:}, {@code http(s)} or
     * {@code file:} URL goes through the same guarded {@link #fetch} the preview uses (internal-address block
     * re-checked on every redirect hop, UNC refusal, byte cap); anything else is a path, resolved against
     * {@code baseDir} and read only when it is a regular file within the cap. One fetcher for every surface,
     * so an exported document cannot reach what the preview refuses. Call off the FX thread.
     */
    public static byte[] fetchForExport(String src, Path baseDir) {
        if (src == null || src.isBlank()) {
            return null;
        }
        String s = src.strip();
        try {
            if (s.matches("(?i)^(https?|file|data):.*")) {
                return fetch(s);
            }
            if (isUncLike(s)) {
                return null; // \\host\share\x.png — an SMB connection (and a NetNTLM hash) on Windows
            }
            Path p = Path.of(s);
            if (!p.isAbsolute()) {
                if (baseDir == null) {
                    return null; // nothing to anchor a relative path to; never the process cwd
                }
                p = baseDir.resolve(p);
            }
            return readLocal(p);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** True when {@code path} starts with two (or more) slashes or backslashes in any mix — a UNC path on
     *  Windows, however it was spelled ({@code //host/share}, {@code \\host\share}, {@code /\host}). Pure. */
    static boolean isUncLike(String path) {
        return path != null
                && path.length() >= 2
                && (path.charAt(0) == '/' || path.charAt(0) == '\\')
                && (path.charAt(1) == '/' || path.charAt(1) == '\\');
    }

    /** The bytes of a local regular file within {@link #MAX_IMAGE_BYTES}, else {@code null} (a directory, a
     *  device or FIFO, a missing or oversized file). */
    private static byte[] readLocal(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_IMAGE_BYTES) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return readCapped(in);
        }
    }

    /** Reads {@code in} to EOF, or returns {@code null} once it exceeds {@link #MAX_IMAGE_BYTES}. */
    static byte[] readCapped(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_IMAGE_BYTES + 1);
        return bytes.length > MAX_IMAGE_BYTES ? null : bytes;
    }

    /**
     * Encodes a JavaFX {@code Image} to PNG bytes via headless Java2D (no {@code javafx.swing}): reads the
     * pixels through a {@code PixelReader} into an ARGB {@link BufferedImage}, then {@code ImageIO}. Returns
     * {@code null} when the image has no reader (still loading / broken). Read the pixels on the FX thread.
     */
    public static byte[] imageToPng(javafx.scene.image.Image img) {
        if (img == null || img.getPixelReader() == null) {
            return null;
        }
        int w = (int) Math.round(img.getWidth());
        int h = (int) Math.round(img.getHeight());
        if (w <= 0 || h <= 0) {
            return null;
        }
        try {
            javafx.scene.image.PixelReader reader = img.getPixelReader();
            BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    row[x] = reader.getArgb(x, y);
                }
                buf.setRGB(0, y, w, 1, row, 0, w);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            return javax.imageio.ImageIO.write(buf, "png", out) ? out.toByteArray() : null;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /** Cap on redirect hops we'll follow (each re-checked against the SSRF guard). */
    private static final int MAX_REDIRECTS = 5;

    private static byte[] fetch(String url) throws IOException {
        URI uri = URI.create(url);
        if (isBlockedTarget(uri)) {
            return null; // SSRF / internal-network / UNC-credential-leak guard — see isBlockedTarget
        }
        if ("data".equalsIgnoreCase(uri.getScheme())) {
            return decodeDataUri(url);
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return readLocal(Path.of(uri)); // regular files only: never a directory listing or a device
        }
        // Follow redirects manually so EACH hop is re-checked: a public URL must not be able to 30x-pivot to a
        // loopback/internal target past the guard (the JDK's automatic redirect following wouldn't re-check).
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            URLConnection con = uri.toURL().openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestProperty("User-Agent", "Editora"); // some CDNs (shields.io) reject a missing UA
            if (con instanceof java.net.HttpURLConnection http) {
                http.setInstanceFollowRedirects(false);
                int code = http.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = http.getHeaderField("Location");
                    http.disconnect();
                    if (location == null) {
                        return null;
                    }
                    uri = uri.resolve(location);
                    if (isBlockedTarget(uri)) {
                        return null; // the redirect points somewhere we won't reach
                    }
                    continue;
                }
            }
            try (InputStream in = con.getInputStream()) {
                return readCapped(in);
            }
        }
        return null; // too many redirects
    }

    /**
     * True when fetching {@code uri} could hit the local host / internal network (blind SSRF) or leak
     * credentials via a UNC path — so the preview image loader must refuse it.
     *
     * <p>Reachable automatically: the live Markdown/CSV preview loads every {@code ![](…)} the moment it
     * renders, and the source can be an untrusted file. Without this, {@code ![](http://169.254.169.254/…)}
     * (cloud metadata), {@code http://10.0.0.1/…} (internal hosts), or {@code file://attacker/share} (an SMB
     * NetNTLM-hash leak on Windows) fire with no consent. Only {@code data:}, a <em>local</em> {@code file:}
     * (relative markdown images resolve to {@code file:///…}), and {@code http(s)} to a public host are
     * allowed; the host classification is the pure, unit-tested {@link #isInternalAddress}.
     */
    static boolean isBlockedTarget(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return true;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "data" -> {
                return false;
            }
            case "file" -> {
                // A local file is fine (that's how relative markdown images load). A non-empty authority means
                // a UNC/remote path (\\host\share) — the credential-leak vector — so refuse it. So does a path
                // that itself starts with two slashes (file:////host/share, file:///%5C%5Chost/share): there is
                // no authority to catch, but Windows resolves it to the same \\host\share. An opaque
                // file:name.png has no path at all and would resolve against the process cwd.
                String path = uri.getPath();
                return uri.getRawAuthority() != null && !uri.getRawAuthority().isBlank()
                        || path == null
                        || path.isEmpty()
                        || isUncLike(path)
                        || isUncLike(path.substring(1));
            }
            case "http", "https" -> {
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    return true;
                }
                try {
                    for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                        if (isInternalAddress(addr)) {
                            return true; // literal internal IP, or a hostname that resolves to one
                        }
                    }
                    return false;
                } catch (java.net.UnknownHostException e) {
                    return true; // can't resolve → don't reach out
                }
            }
            default -> {
                return true; // ftp/jar/… — not an image source we serve
            }
        }
    }

    /** Pure: an address the preview must not fetch from — loopback, link-local (incl. {@code 169.254.169.254}
     *  cloud metadata), any-local, multicast, IPv4 site-local (RFC-1918), or IPv6 ULA ({@code fc00::/7}). */
    static boolean isInternalAddress(java.net.InetAddress a) {
        if (a.isLoopbackAddress()
                || a.isLinkLocalAddress()
                || a.isAnyLocalAddress()
                || a.isMulticastAddress()
                || a.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = a.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC; // IPv6 unique-local (fc00::/7)
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

    /**
     * Rasterizes SVG source bytes to a JavaFX {@link Loaded} (image + logical width) via JSVG, for the
     * standalone {@code .svg} file preview ({@code editor/SvgImages}). Returns {@code null} on a
     * parse/render failure so the caller can show an error. Call off the FX thread (touches Java2D).
     */
    public static Loaded rasterizeSvg(byte[] bytes) {
        try {
            return rasterizeSvg(bytes, null);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Parses SVG bytes into a JSVG document, or {@code null} when it cannot be read.
     *
     * <p>Split out so the standalone {@code .svg} preview can render the document natively through
     * {@code FXSVGCanvas} instead of rasterizing it — the parse is the expensive half and is toolkit-free,
     * so it stays off the FX thread either way. {@link LoaderContext#createDefault()} is what refuses the
     * external references an SVG can carry; keep it, or a previewed document can fetch a URL of its
     * choosing (its policy is embedded-data-only, verified against jsvg 2.1.0).
     */
    public static SVGDocument parseSvg(byte[] bytes) {
        try {
            return new SVGLoader().load(new ByteArrayInputStream(bytes), null, LoaderContext.createDefault());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Loaded rasterizeSvg(byte[] bytes, String url) {
        SVGDocument doc =
                new SVGLoader().load(new ByteArrayInputStream(bytes), uriOrNull(url), LoaderContext.createDefault());
        if (doc == null) {
            return null;
        }
        Raster r = rasterFor(doc.size().width, doc.size().height);
        // backing bitmap is 2× (less when clamped); ImageView displays it at logical width w
        return new Loaded(toFxImage(paint(doc, r)), r.logicalWidth());
    }

    /** The bitmap an SVG of a given logical size is painted into: pixel dimensions and the scale to paint at. */
    record Raster(double logicalWidth, double logicalHeight, int pixelWidth, int pixelHeight, double scale) {}

    /**
     * Sizes the raster for an SVG's declared {@code width}/{@code height}: {@link #RASTER_SCALE}× for crispness,
     * scaled down uniformly when that would exceed {@link #MAX_RASTER_PIXELS}. The dimensions come straight
     * from the document, so they are clamped here rather than trusted — an absent, non-finite or negative size
     * falls back to a badge-sized default. Pure; unit-tested.
     */
    static Raster rasterFor(double width, double height) {
        double w = width > 0 && Double.isFinite(width) ? width : 100;
        double h = height > 0 && Double.isFinite(height) ? height : 20;
        double scale = Math.min(RASTER_SCALE, Math.min(MAX_RASTER_SIDE / w, MAX_RASTER_SIDE / h));
        double pixels = (w * scale) * (h * scale);
        if (pixels > MAX_RASTER_PIXELS) {
            scale *= Math.sqrt(MAX_RASTER_PIXELS / pixels);
        }
        int pw = (int) Math.max(1, Math.min(MAX_RASTER_SIDE, Math.ceil(w * scale)));
        int ph = (int) Math.max(1, Math.min(MAX_RASTER_SIDE, Math.ceil(h * scale)));
        while ((long) pw * ph > MAX_RASTER_PIXELS) { // rounding up can overshoot the cap by a row or column
            if (pw >= ph) {
                pw--;
            } else {
                ph--;
            }
        }
        return new Raster(w, h, pw, ph, scale);
    }

    private static BufferedImage paint(SVGDocument doc, Raster r) {
        BufferedImage buf = new BufferedImage(r.pixelWidth(), r.pixelHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buf.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(r.scale(), r.scale());
        doc.render(
                (java.awt.Component) null, g, new ViewBox(0, 0, (float) r.logicalWidth(), (float) r.logicalHeight()));
        g.dispose();
        return buf;
    }

    /**
     * Rasterizes SVG bytes to PNG bytes via JSVG, for consumers that need a bitmap rather than a JavaFX
     * {@code Image} (e.g. the PDF/print writers, which embed via PDFBox and can't decode SVG). Returns
     * {@code null} when the SVG can't be parsed/rendered, so callers degrade gracefully.
     */
    public static byte[] svgToPng(byte[] svg) {
        try {
            SVGDocument doc = new SVGLoader().load(new ByteArrayInputStream(svg), null, LoaderContext.createDefault());
            if (doc == null) {
                return null;
            }
            BufferedImage buf = paint(doc, rasterFor(doc.size().width, doc.size().height));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            return javax.imageio.ImageIO.write(buf, "png", out) ? out.toByteArray() : null;
        } catch (RuntimeException | java.io.IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private static URI uriOrNull(String url) {
        try {
            return URI.create(url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Copies an ARGB {@link BufferedImage} into a JavaFX {@link WritableImage} (no javafx.swing needed).
     *  Public so the PDF viewer can convert PDFBox-rasterized pages the same way. Call on the FX thread. */
    public static Image toFxImage(BufferedImage buf) {
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
