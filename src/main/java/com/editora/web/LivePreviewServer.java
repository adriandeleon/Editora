package com.editora.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
 * <p>Loopback binding is not a boundary on its own: any local process can reach the port, a web page can
 * reach it through DNS rebinding (its own hostname re-pointed at 127.0.0.1), and everything under the doc
 * root is readable. So every request must (1) carry a {@code Host} header naming this loopback listener
 * ({@link #isAllowedHost}) and (2) present the per-server random {@link #token} as its first path segment
 * ({@link #route}); and a doc root that would expose the home directory or a filesystem root is refused
 * outright ({@link #isUnsafeDocRoot}).
 *
 * <p>Lifecycle is owned by {@code HtmlPreviewService} (one server per window). The pure helpers
 * ({@link #injectReloadScript}, {@link #contentType}, {@link #safeResolve}, {@link #isAllowedHost},
 * {@link #route}, {@link #isUnsafeDocRoot}) are unit-tested.
 */
public final class LivePreviewServer {

    /** The long-poll endpoint the injected script hits (under the token prefix); held until the version
     *  advances or the timeout. */
    static final String LR_PATH = "/__editora_livereload";

    /** The unguessable first path segment every request must carry (128 random bits, per server instance). */
    private final String token = newToken();

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
        // Bounded (not newCachedThreadPool): each /__editora_livereload request blocks a worker for up to
        // POLL_TIMEOUT_MS, so an unbounded pool lets any localhost process exhaust threads/memory by holding
        // many concurrent long-polls. A small fixed pool caps that; normal use holds one poll per open page.
        pool = Executors.newFixedThreadPool(16, r -> {
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
    public synchronized void setPreview(Path file, Supplier<String> text) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        if (isUnsafeDocRoot(parent, userHome())) {
            this.docRoot = null; // serve nothing rather than the previous (or an over-broad) folder
            throw new IOException("refusing to serve " + parent + " (home directory or filesystem root)");
        }
        this.docRoot = parent;
        this.previewRelPath = parent == null
                ? abs.getFileName().toString()
                : parent.relativize(abs).toString();
        this.liveText = text == null ? () -> "" : text;
        bumpVersion(); // a new preview target ⇒ reload any open browser
    }

    /** The URL that renders the current preview file (loopback + the token prefix + the relative path). */
    public synchronized String previewUrl() {
        String rel = previewRelPath == null ? "" : encodePath(previewRelPath);
        return "http://127.0.0.1:" + port() + "/" + token + "/" + rel;
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
            int port = ex.getLocalAddress().getPort();
            if (!isAllowedHost(ex.getRequestHeaders().getFirst("Host"), port)) {
                sendStatus(ex, 403); // DNS rebinding / a foreign origin: not addressed to this loopback listener
                return;
            }
            String path = ex.getRequestURI().getPath();
            String inner = route(path, token);
            if (inner == null) {
                // A root-absolute asset reference (<link href="/app.css">) from a page we served: the browser's
                // Referer proves it already holds the token, so bounce it under the prefix. Anything else 404s.
                if (refererHasToken(ex.getRequestHeaders().getFirst("Referer"), port, token)) {
                    String query = ex.getRequestURI().getRawQuery();
                    String to = "/" + token + ex.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
                    ex.getResponseHeaders().set("Location", to);
                    sendStatus(ex, 307);
                } else {
                    sendStatus(ex, 404);
                }
            } else if (LR_PATH.equals(inner)) {
                handleLiveReload(ex);
            } else {
                serveFile(ex, inner);
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
        // urlPath is URI.getPath(), which is already %-decoded — do NOT URLDecoder.decode again (that is
        // form decoding: it turns "+" into a space and double-decodes "%25", breaking asset filenames that
        // contain "+", "%", or a space).
        String rel = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
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
            byte[] body =
                    injectReloadScript(liveText.get(), version.get(), token).getBytes(StandardCharsets.UTF_8);
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
            bytes = injectReloadScript(new String(bytes, StandardCharsets.UTF_8), version.get(), token)
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

    private static String newToken() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** The preview token, for tests in this package. */
    String token() {
        return token;
    }

    private static Path userHome() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }

    /**
     * Whether a request's {@code Host} header names this listener: exactly {@code 127.0.0.1:<port>},
     * {@code localhost:<port>} or {@code [::1]:<port>}. A page on {@code evil.example} whose DNS was re-pointed
     * at 127.0.0.1 still sends {@code Host: evil.example:<port>}, which is how rebinding is told apart from the
     * browser tab we opened. A missing header (HTTP/1.0, a raw socket) is refused too.
     */
    static boolean isAllowedHost(String hostHeader, int port) {
        if (hostHeader == null) {
            return false;
        }
        String h = hostHeader.strip().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port) || h.equals("[::1]:" + port);
    }

    /**
     * Strips the required {@code /<token>} prefix from a request path, returning the rest (always starting
     * with {@code /}), or {@code null} when the prefix is absent or wrong — the caller answers 404 without
     * touching the doc root. Compared in constant time so the token cannot be recovered byte-by-byte.
     */
    static String route(String path, String token) {
        if (path == null || token == null || token.isEmpty() || !path.startsWith("/")) {
            return null;
        }
        int end = path.indexOf('/', 1);
        String first = end < 0 ? path.substring(1) : path.substring(1, end);
        boolean same = java.security.MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!same) {
            return null;
        }
        return end < 0 ? "/" : path.substring(end);
    }

    /** Whether {@code referer} is a page this server itself served under the token (same listener + prefix). */
    static boolean refererHasToken(String referer, int port, String token) {
        if (referer == null) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(referer.strip());
            String host = uri.getRawAuthority();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && isAllowedHost(host, port)
                    && route(uri.getPath(), token) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether serving {@code docRoot} would expose far more than a web page's folder: a filesystem root, the
     * user's home directory, or any ancestor of it ({@code /home}, {@code /Users}). An HTML file saved straight
     * into the home folder would otherwise publish {@code ~/.ssh}, browser profiles and every dotfile to
     * whatever can reach the port. {@code home} may be null (unknown) — then only a filesystem root is refused.
     */
    public static boolean isUnsafeDocRoot(Path docRoot, Path home) {
        if (docRoot == null) {
            return true;
        }
        Path root = real(docRoot);
        if (root.getParent() == null) {
            return true;
        }
        return home != null && real(home).startsWith(root);
    }

    /** {@link #isUnsafeDocRoot(Path, Path)} for the folder of {@code file}, against the current user's home. */
    public static boolean isUnsafeDocRootFor(Path file) {
        return isUnsafeDocRoot(file.toAbsolutePath().normalize().getParent(), userHome());
    }

    private static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException e) {
            return abs;
        }
    }

    /**
     * Resolves {@code rel} against {@code root} and rejects anything that escapes the doc root (path
     * traversal), returning {@code null} when unsafe. {@code root} must be absolute + normalized.
     *
     * <p>Beyond the lexical {@code normalize()}+{@code startsWith} check, it canonicalizes an <em>existing</em>
     * target with {@code toRealPath()} and re-checks containment — otherwise a symlink that lives inside the
     * doc root but points outside it (common in real project trees) would pass the lexical check and let the
     * preview server read an arbitrary file (same-origin with the previewed page). A target that doesn't exist
     * yet (or can't be canonicalized) falls back to the lexical result — the subsequent read just 404s.
     */
    static Path safeResolve(Path root, String rel) {
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            return null; // lexical escape (…/../…, absolute path)
        }
        try {
            return resolved.toRealPath().startsWith(root.toRealPath()) ? resolved : null;
        } catch (IOException notYetExisting) {
            return resolved; // no such file — safe to return; the read will 404
        }
    }

    /** Splices the live-reload {@code <script>} before {@code </body>} (appends when there is no body tag). */
    static String injectReloadScript(String html, long version, String token) {
        String script = reloadScript(version, token);
        int idx = indexOfIgnoreCase(html, "</body>");
        return idx >= 0 ? html.substring(0, idx) + script + html.substring(idx) : html + script;
    }

    private static String reloadScript(long version, String token) {
        // Long-poll: 200 ⇒ a newer version is up, reload (the new page embeds the new version); 204 ⇒ no
        // change within the server's timeout, re-poll immediately; network error ⇒ back off 1s then retry.
        return "\n<script>(function(){var v=" + version + ";function poll(){"
                + "fetch('/" + token + LR_PATH + "?v='+v).then(function(r){return r.status===200?r.text():null;})"
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
