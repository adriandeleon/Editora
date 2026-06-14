package com.editora.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny embedded HTTP server (JDK {@link HttpServer}) that serves one HTML file's folder so a real browser
 * can render it with its relative CSS/JS/image assets, bound to <strong>loopback only</strong> (never the
 * LAN). The previewed HTML file itself is served from the editor buffer's <em>live in-memory text</em> (via
 * an injected {@link Supplier}) with a small <strong>live-reload script</strong> spliced in, so edits show
 * without saving. The script long-polls {@code /__editora_livereload}; {@link #bumpVersion()} (called on the
 * debounced edit pulse) releases the held request and the page reloads.
 *
 * <p>Lifecycle is owned by {@code HtmlPreviewService} (one server per window). The pure helpers
 * ({@link #injectReloadScript}, {@link #contentType}, {@link #safeResolve}) are unit-tested.
 */
public final class LivePreviewServer {

    /** The long-poll endpoint the injected script hits; held until the version advances or the timeout. */
    static final String LR_PATH = "/__editora_livereload";

    private static final long POLL_TIMEOUT_MS = 25_000;

    private HttpServer server;
    private ExecutorService pool;

    private volatile Path docRoot; // absolute, normalized; the previewed file's parent
    private volatile String previewRelPath; // the file served from live text, relative to docRoot
    private volatile Supplier<String> liveText = () -> "";

    private final AtomicLong version = new AtomicLong(1);
    private final Object versionLock = new Object();

    /** Starts the server on an ephemeral loopback port (idempotent); returns the bound port. */
    public synchronized int start() throws IOException {
        if (server != null) {
            return server.getAddress().getPort();
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "html-preview-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /** Points the server at {@code file}: doc root becomes its parent and the file is served from {@code text}. */
    public synchronized void setPreview(Path file, Supplier<String> text) {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        this.docRoot = parent;
        this.previewRelPath = parent == null
                ? abs.getFileName().toString()
                : parent.relativize(abs).toString();
        this.liveText = text == null ? () -> "" : text;
        bumpVersion(); // a new preview target ⇒ reload any open browser
    }

    /** The URL that renders the current preview file (loopback + the previewed relative path). */
    public synchronized String previewUrl() {
        String rel = previewRelPath == null ? "" : encodePath(previewRelPath);
        return "http://127.0.0.1:" + port() + "/" + rel;
    }

    /** Advances the version and wakes every held long-poll so open browsers reload. */
    public void bumpVersion() {
        synchronized (versionLock) {
            version.incrementAndGet();
            versionLock.notifyAll();
        }
    }

    /** Stops the server + its thread pool (idempotent). Releases held polls so their threads exit. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        bumpVersion();
    }

    // --- request handling -------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (LR_PATH.equals(ex.getRequestURI().getPath())) {
                handleLiveReload(ex);
            } else {
                serveFile(ex, ex.getRequestURI().getPath());
            }
        } catch (RuntimeException e) {
            sendStatus(ex, 500);
        } finally {
            ex.close();
        }
    }

    private void handleLiveReload(HttpExchange ex) throws IOException {
        long since = parseSince(ex.getRequestURI().getRawQuery());
        long current = waitForNewVersion(since);
        if (current > since) {
            byte[] body = Long.toString(current).getBytes(StandardCharsets.UTF_8);
            writeBody(ex, 200, "text/plain; charset=utf-8", body);
        } else {
            ex.sendResponseHeaders(204, -1); // no change within the timeout; the client re-polls
        }
    }

    /** Blocks until the version exceeds {@code since} or the poll timeout elapses; returns the current version. */
    private long waitForNewVersion(long since) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        synchronized (versionLock) {
            while (version.get() <= since) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    versionLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return version.get();
        }
    }

    private void serveFile(HttpExchange ex, String urlPath) throws IOException {
        Path root = docRoot;
        if (root == null) {
            sendStatus(ex, 404);
            return;
        }
        String rel = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
        rel = URLDecoder.decode(rel, StandardCharsets.UTF_8);
        if (rel.isEmpty()) {
            rel = previewRelPath == null ? "" : previewRelPath; // "/" ⇒ the previewed file
        }
        Path resolved = safeResolve(root, rel);
        if (resolved == null) {
            sendStatus(ex, 403); // path traversal outside the doc root
            return;
        }
        // The previewed file is served from the live buffer text (so unsaved edits show).
        if (previewRelPath != null && resolved.equals(safeResolve(root, previewRelPath))) {
            byte[] body = injectReloadScript(liveText.get(), version.get()).getBytes(StandardCharsets.UTF_8);
            writeBody(ex, 200, "text/html; charset=utf-8", body);
            return;
        }
        if (!Files.isRegularFile(resolved)) {
            sendStatus(ex, 404);
            return;
        }
        byte[] bytes = Files.readAllBytes(resolved);
        String ct = contentType(resolved.getFileName().toString());
        if (ct.startsWith("text/html")) {
            // Other HTML pages under the root also get the script, so navigating to them live-reloads too.
            bytes = injectReloadScript(new String(bytes, StandardCharsets.UTF_8), version.get())
                    .getBytes(StandardCharsets.UTF_8);
        }
        writeBody(ex, 200, ct, bytes);
    }

    private static void writeBody(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendStatus(HttpExchange ex, int status) {
        try {
            ex.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // client already gone; nothing to do
        }
    }

    private static long parseSince(String rawQuery) {
        if (rawQuery == null) {
            return 0;
        }
        for (String part : rawQuery.split("&")) {
            if (part.startsWith("v=")) {
                try {
                    return Long.parseLong(part.substring(2));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    // --- pure helpers (unit-tested) ---------------------------------------------------------------

    /**
     * Resolves {@code rel} against {@code root} and rejects anything that escapes the doc root (path
     * traversal), returning {@code null} when unsafe. {@code root} must be absolute + normalized.
     */
    static Path safeResolve(Path root, String rel) {
        Path resolved = root.resolve(rel).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    /** Splices the live-reload {@code <script>} before {@code </body>} (appends when there is no body tag). */
    static String injectReloadScript(String html, long version) {
        String script = reloadScript(version);
        int idx = indexOfIgnoreCase(html, "</body>");
        return idx >= 0 ? html.substring(0, idx) + script + html.substring(idx) : html + script;
    }

    private static String reloadScript(long version) {
        // Long-poll: 200 ⇒ a newer version is up, reload (the new page embeds the new version); 204 ⇒ no
        // change within the server's timeout, re-poll immediately; network error ⇒ back off 1s then retry.
        return "\n<script>(function(){var v=" + version + ";function poll(){"
                + "fetch('" + LR_PATH + "?v='+v).then(function(r){return r.status===200?r.text():null;})"
                + ".then(function(t){if(t){location.reload();}else{poll();}})"
                + ".catch(function(){setTimeout(poll,1000);});}poll();})();</script>\n";
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    /** Maps a filename to a Content-Type by extension (octet-stream fallback). */
    static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "html", "htm", "xhtml" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "xml" -> "application/xml; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "wasm" -> "application/wasm";
            case "map" -> "application/json; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    /** URL-encodes each path segment (so spaces and unicode in filenames produce a valid URL). */
    private static String encodePath(String relPath) {
        String[] segments = relPath.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }
}
