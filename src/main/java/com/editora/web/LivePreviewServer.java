package com.editora.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

/**
 * A tiny embedded HTTP server (JDK {@link HttpServer}) that serves HTML sites so a real browser can render a page
 * with its CSS/JS/image assets, bound to <strong>loopback only</strong> (never the LAN).
 *
 * <p>It serves any number of <em>sites</em>, each a folder under its own prefix {@code /<token>/<siteId>/…}
 * ({@link #serve}): previewing a page in another folder adds a site instead of re-rooting the server, so tabs open
 * on the first one keep working. A file that is open in the editor is served from its <em>live in-memory text</em>
 * (the injected {@link #setLiveText lookup}), so unsaved edits show. Every HTML page gets a small
 * <strong>live-reload script</strong> that short-polls {@code <site>/__editora_livereload} every
 * {@value #POLL_INTERVAL_MS} ms; the server answers each poll at once (no request is ever held), and
 * {@link #changed} advances the version of the site holding the changed file. A stylesheet-only change is
 * answered with {@code "<version> css"} and the page swaps its stylesheets instead of reloading.
 *
 * <p>Loopback binding is not a boundary on its own: any local process can reach the port, a web page can reach it
 * through DNS rebinding (its own hostname re-pointed at 127.0.0.1), and every script the page runs (CDN ones
 * included) is same-origin with everything served. So every request must (1) carry a {@code Host} header naming
 * this loopback listener ({@link #isAllowedHost}) and (2) present the random {@link #token} (new on every
 * {@link #start}) as its first path segment ({@link #route}); hidden files and folders and private keys are never
 * served ({@link #isHiddenOrSecret}); and a root that would expose the home directory, a catch-all folder such as
 * Downloads, the temp folder, a filesystem root or a remote file system is refused outright
 * ({@link #isUnsafeDocRoot}). Files are streamed (with single-range {@code Range} support), not read whole: only
 * HTML up to {@link #MAX_INJECT_BYTES} and small text files are held in memory.
 *
 * <p>Lifecycle is owned by {@code HtmlPreviewService} (one server per window). The pure helpers are unit-tested.
 */
public final class LivePreviewServer {

    /** The poll endpoint the injected script hits, under a site's prefix; always answered at once. */
    static final String LR_PATH = "/__editora_livereload";

    /** How often the injected client polls, in milliseconds. */
    static final int POLL_INTERVAL_MS = 700;

    /** HTML on disk larger than this is streamed as is (no live-reload script) rather than read into memory. */
    static final long MAX_INJECT_BYTES = 20L * 1024 * 1024;

    /** {@link #parseRange}'s answer for a range that starts past the end of the file (416). */
    static final long[] UNSATISFIABLE = new long[0];

    /** The unguessable first path segment every request must carry (128 random bits, new for every start). */
    private volatile String token = newToken();

    /** {@link #MAX_INJECT_BYTES}, lowered by tests. */
    volatile long injectCap = MAX_INJECT_BYTES;

    private @Nullable HttpServer server;
    private @Nullable ExecutorService pool;

    private final Map<String, Site> sitesById = new ConcurrentHashMap<>();
    private final Map<Path, Site> sitesByRoot = new ConcurrentHashMap<>();
    private final AtomicInteger nextSiteId = new AtomicInteger();
    private volatile Function<Path, @Nullable String> liveText = p -> null;
    private volatile String goneMessage = "The Editora preview server has stopped.";

    /** One served folder: its prefix id, the page "/" maps to, and its reload version. */
    private static final class Site {
        final String id;
        final Path root; // absolute, normalized
        volatile String entryRel;
        private long version = 1;
        private long fullVersion = 1; // the last version that needs a full reload (not just a stylesheet swap)

        Site(String id, Path root, String entryRel) {
            this.id = id;
            this.root = root;
            this.entryRel = entryRel;
        }

        synchronized void changed(boolean stylesheetOnly) {
            version++;
            if (!stylesheetOnly) {
                fullVersion = version;
            }
        }

        synchronized long version() {
            return version;
        }

        /** The poll answer for a client at {@code since}: null when nothing changed, else the version (+ " css"). */
        synchronized @Nullable String poll(long since) {
            if (version <= since) {
                return null;
            }
            return fullVersion <= since ? version + " css" : Long.toString(version);
        }
    }

    /** Starts the server on an ephemeral loopback port with a fresh token (idempotent); returns the bound port. */
    public synchronized int start() throws IOException {
        if (server != null) {
            return server.getAddress().getPort();
        }
        token = newToken(); // a URL from an earlier run (in a browser's argv or history) no longer works
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // A virtual thread per exchange: the JDK server reads request headers on the executor, so a fixed pool
        // could be exhausted by a few idle connections (any local process, no token needed). Nothing here holds a
        // request open on purpose any more: the live-reload poll is answered at once.
        ExecutorService p = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("html-preview-http-", 0).factory());
        s.setExecutor(p);
        s.createContext("/", this::handle);
        s.start();
        server = s;
        pool = p;
        return s.getAddress().getPort();
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /** The text of an open file (absolute, normalized path), or null to serve it from disk. Called off FX. */
    public void setLiveText(Function<Path, @Nullable String> lookup) {
        this.liveText = lookup;
    }

    /** The note the injected script shows once it gives up on a server that has gone (localized by the caller). */
    public void setGoneMessage(String message) {
        this.goneMessage = message;
    }

    /**
     * Serves {@code root} (adding it as a site, or reusing the site already serving it) and returns the URL that
     * renders {@code entry}, which must lie under it. Refuses an unsafe root ({@link #isUnsafeDocRoot}).
     */
    public synchronized String serve(Path root, Path entry) throws IOException {
        if (server == null) {
            throw new IOException("the preview server is not running");
        }
        Path r = root.toAbsolutePath().normalize();
        Path e = entry.toAbsolutePath().normalize();
        if (!e.startsWith(r) || e.equals(r)) {
            throw new IOException(entry + " is not inside " + root);
        }
        if (isUnsafeDocRoot(r, userHome())) {
            throw new IOException("refusing to serve " + r + " (home, catch-all or temp folder, or a filesystem root)");
        }
        String rel = r.relativize(e).toString().replace('\\', '/');
        Site site = sitesByRoot.get(r);
        if (site == null) {
            site = new Site(Integer.toString(nextSiteId.incrementAndGet()), r, rel);
            sitesByRoot.put(r, site);
            sitesById.put(site.id, site);
        }
        site.entryRel = rel;
        return "http://127.0.0.1:" + port() + "/" + token + "/" + site.id + "/" + encodePath(rel);
    }

    /** Whether {@code file} lies under a served site (so its edits and saves should reload the browser). */
    public boolean serves(Path file) {
        Path p = file.toAbsolutePath().normalize();
        for (Site s : sitesByRoot.values()) {
            if (p.startsWith(s.root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Advances the version of every site holding {@code file}, so its open pages reload (or only swap their
     * stylesheets, when {@code file} is a {@code .css}). Returns whether any site holds it.
     */
    public boolean changed(Path file) {
        Path p = file.toAbsolutePath().normalize();
        boolean stylesheet = isStylesheet(p);
        boolean any = false;
        for (Site s : sitesByRoot.values()) {
            if (p.startsWith(s.root)) {
                s.changed(stylesheet);
                any = true;
            }
        }
        return any;
    }

    /** Advances every site's version so every open page reloads. */
    public void bumpVersion() {
        sitesByRoot.values().forEach(s -> s.changed(false));
    }

    /** Stops the server and forgets every site (idempotent). */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        sitesById.clear();
        sitesByRoot.clear();
    }

    // --- request handling -------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            Headers out = ex.getResponseHeaders();
            out.set("Cache-Control", "no-store");
            out.set("X-Content-Type-Options", "nosniff");
            // Same-origin pages still send a Referer (the bounce below needs it); a cross-origin request — a
            // CDN script or image, a link out — never carries the token.
            out.set("Referrer-Policy", "same-origin");
            int port = ex.getLocalAddress().getPort();
            if (!isAllowedHost(ex.getRequestHeaders().getFirst("Host"), port)) {
                sendStatus(ex, 403); // DNS rebinding / a foreign origin: not addressed to this loopback listener
                return;
            }
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                out.set("Allow", "GET, HEAD");
                sendStatus(ex, 405);
                return;
            }
            String inner = route(ex.getRequestURI().getPath(), token);
            if (inner == null) {
                // A root-absolute asset reference (<link href="/app.css">) from a page we served: the browser's
                // Referer proves it already holds the token, so bounce it under that page's site prefix.
                String site = refererSite(ex.getRequestHeaders().getFirst("Referer"), port, token);
                if (site != null) {
                    String query = ex.getRequestURI().getRawQuery();
                    String to = "/" + token + "/" + site + ex.getRequestURI().getRawPath()
                            + (query == null ? "" : "?" + query);
                    out.set("Location", to);
                    sendStatus(ex, 307);
                } else {
                    sendStatus(ex, 404);
                }
                return;
            }
            int slash = inner.indexOf('/', 1);
            String siteId = slash < 0 ? inner.substring(1) : inner.substring(1, slash);
            String rest = slash < 0 ? "/" : inner.substring(slash);
            Site site = sitesById.get(siteId);
            if (site == null) {
                sendStatus(ex, 404);
            } else if (LR_PATH.equals(rest)) {
                String answer = site.poll(parseSince(ex.getRequestURI().getRawQuery()));
                if (answer == null) {
                    sendStatus(ex, 204); // no change: the client polls again shortly
                } else {
                    writeBody(ex, 200, "text/plain; charset=utf-8", answer.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                serveFile(ex, site, rest);
            }
        } catch (RuntimeException e) {
            sendStatus(ex, 500);
        } finally {
            ex.close();
        }
    }

    private void serveFile(HttpExchange ex, Site site, String urlPath) throws IOException {
        // urlPath is URI.getPath(), which is already %-decoded — do NOT URLDecoder.decode again (that is form
        // decoding: it turns "+" into a space and double-decodes "%25", breaking asset filenames that contain
        // "+", "%", or a space).
        String rel = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
        if (rel.isEmpty()) {
            rel = site.entryRel; // "/" ⇒ the previewed file
        }
        boolean entry = rel.equals(site.entryRel); // the page the user asked for is served even in a hidden folder
        if (!entry && isHiddenOrSecret(rel)) {
            sendStatus(ex, 404); // .env, .git/config, id_rsa, *.pem: same-origin with every script on the page
            return;
        }
        Path resolved = safeResolve(site.root, rel);
        if (resolved == null) {
            sendStatus(ex, 403); // path traversal outside the site root
            return;
        }
        if (!entry && hidesBehindALink(site.root, resolved)) {
            sendStatus(ex, 404); // an in-root symlink that leads into .git or another hidden folder
            return;
        }
        Path fileName = resolved.getFileName();
        String type = contentType(fileName == null ? "" : fileName.toString());
        boolean html = "text/html".equals(type);
        String live = liveText.apply(resolved);
        if (live != null) {
            // An open file: the editor's text, unsaved edits included (encoded as UTF-8, so labelled so).
            String text = html ? injectReloadScript(live, scriptFor(site)) : live;
            writeBody(ex, 200, withCharset(type, true), text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!Files.isRegularFile(resolved)) {
            sendStatus(ex, 404);
            return;
        }
        long size = Files.size(resolved);
        String range = ex.getRequestHeaders().getFirst("Range");
        if (size <= injectCap && (html || (isText(type) && range == null))) {
            byte[] bytes = Files.readAllBytes(resolved);
            // Label UTF-8 only when the bytes are UTF-8; otherwise the page's own <meta charset> (or @charset, or
            // the browser's sniffing) decides, so a Latin-1 page is not turned into U+FFFD.
            boolean utf8 = isUtf8(bytes);
            byte[] body = html ? injectReloadScript(bytes, scriptFor(site)) : bytes;
            writeBody(ex, 200, withCharset(type, utf8), body);
            return;
        }
        streamFile(ex, resolved, size, type, range);
    }

    /** Streams {@code file} (or the one byte range asked for) without reading it into memory. */
    private static void streamFile(HttpExchange ex, Path file, long size, String type, @Nullable String range)
            throws IOException {
        Headers out = ex.getResponseHeaders();
        out.set("Accept-Ranges", "bytes");
        out.set("Content-Type", type);
        long start = 0;
        long end = size - 1;
        int status = 200;
        if (range != null) {
            long[] r = parseRange(range, size);
            if (r == UNSATISFIABLE) {
                out.set("Content-Range", "bytes */" + size);
                sendStatus(ex, 416);
                return;
            }
            if (r != null) {
                start = r[0];
                end = r[1];
                status = 206;
                out.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
        }
        long length = Math.max(0, end - start + 1);
        if (isHead(ex) || length == 0) {
            out.set("Content-Length", Long.toString(length));
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, length);
        try (FileChannel ch = FileChannel.open(file);
                OutputStream body = ex.getResponseBody()) {
            WritableByteChannel sink = Channels.newChannel(body);
            long pos = start;
            long left = length;
            while (left > 0) {
                long n = ch.transferTo(pos, left, sink);
                if (n <= 0) {
                    break; // the file shrank under us: the short body ends the exchange
                }
                pos += n;
                left -= n;
            }
        }
    }

    private static void writeBody(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        if (isHead(ex) || body.length == 0) {
            ex.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            ex.sendResponseHeaders(status, -1); // HEAD: headers only, and no JDK warning about a length
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static boolean isHead(HttpExchange ex) {
        return "HEAD".equals(ex.getRequestMethod());
    }

    private static void sendStatus(HttpExchange ex, int status) {
        try {
            ex.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // client already gone; nothing to do
        }
    }

    private static long parseSince(@Nullable String rawQuery) {
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

    private String scriptFor(Site site) {
        return reloadScript(site.version(), "/" + token + "/" + site.id, goneMessage);
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

    private static @Nullable Path userHome() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }

    /**
     * Whether a request's {@code Host} header names this listener: exactly {@code 127.0.0.1:<port>},
     * {@code localhost:<port>} or {@code [::1]:<port>}. A page on {@code evil.example} whose DNS was re-pointed
     * at 127.0.0.1 still sends {@code Host: evil.example:<port>}, which is how rebinding is told apart from the
     * browser tab we opened. A missing header (HTTP/1.0, a raw socket) is refused too.
     */
    static boolean isAllowedHost(@Nullable String hostHeader, int port) {
        if (hostHeader == null) {
            return false;
        }
        String h = hostHeader.strip().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port) || h.equals("[::1]:" + port);
    }

    /**
     * Strips the required {@code /<token>} prefix from a request path, returning the rest (always starting
     * with {@code /}), or {@code null} when the prefix is absent or wrong — the caller answers 404 without
     * touching any site. Compared in constant time so the token cannot be recovered byte-by-byte.
     */
    static @Nullable String route(@Nullable String path, @Nullable String token) {
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

    /**
     * The site id of {@code referer} when it is a page this server itself served under the token (same listener,
     * the token prefix, a numeric site segment), else null.
     */
    static @Nullable String refererSite(@Nullable String referer, int port, String token) {
        if (referer == null) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(referer.strip());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !isAllowedHost(uri.getRawAuthority(), port)) {
                return null;
            }
            String inner = route(uri.getPath(), token);
            if (inner == null || inner.length() < 2) {
                return null;
            }
            int slash = inner.indexOf('/', 1);
            String site = slash < 0 ? inner.substring(1) : inner.substring(1, slash);
            return site.isEmpty() || !site.chars().allMatch(c -> c >= '0' && c <= '9') ? null : site;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether a URL path (relative to a site root) names something no page should read: any segment starting
     * with {@code .} ({@code .env}, {@code .git/config}, {@code .ssh/}, {@code .npmrc}, {@code .netrc}) other than
     * {@code .well-known}, or a private key ({@code *.pem}, {@code *.key}, an SSH identity {@code id_*} without
     * an extension or ending in {@code .pub}).
     */
    static boolean isHiddenOrSecret(String rel) {
        String[] segments = rel.split("[/\\\\]");
        for (String seg : segments) {
            if (seg.startsWith(".") && !seg.equals(".well-known")) {
                return true;
            }
        }
        String name = segments.length == 0 ? "" : segments[segments.length - 1].toLowerCase(Locale.ROOT);
        if (name.endsWith(".pem") || name.endsWith(".key")) {
            return true;
        }
        return name.startsWith("id_") && (name.indexOf('.') < 0 || name.endsWith(".pub"));
    }

    /** Whether {@code resolved}'s real path, relative to the root's, passes through a hidden or secret name. */
    private static boolean hidesBehindALink(Path root, Path resolved) {
        try {
            Path rel = root.toRealPath().relativize(resolved.toRealPath());
            return isHiddenOrSecret(rel.toString());
        } catch (IOException | IllegalArgumentException e) {
            return false; // not there (the read 404s) or not comparable
        }
    }

    /**
     * Whether serving {@code docRoot} would expose far more than a web site: a filesystem root; the user's home
     * directory or any ancestor of it ({@code /home}, {@code /Users}); a catch-all folder of the home (Downloads,
     * Desktop, Documents, and their localized XDG names) when the page sits directly in it; the temp folder or an
     * ancestor of it; or anything on a non-default (remote) file system, whose home cannot be compared with
     * ours. {@code home} may be null (unknown) — then only the home rules are skipped.
     */
    public static boolean isUnsafeDocRoot(@Nullable Path docRoot, @Nullable Path home) {
        if (docRoot == null || docRoot.getFileSystem() != FileSystems.getDefault()) {
            return true;
        }
        Path root = real(docRoot);
        if (root.getParent() == null) {
            return true;
        }
        if (home != null) {
            Path h = real(home);
            if (h.startsWith(root)) {
                return true;
            }
            for (Path folder : catchAllFolders(h)) {
                if (root.equals(real(folder))) {
                    return true;
                }
            }
        }
        String tmp = System.getProperty("java.io.tmpdir");
        return tmp != null && !tmp.isBlank() && real(Path.of(tmp)).startsWith(root);
    }

    /** {@link #isUnsafeDocRoot(Path, Path)} against the current user's home. */
    public static boolean isUnsafeDocRoot(@Nullable Path docRoot) {
        return isUnsafeDocRoot(docRoot, userHome());
    }

    /** Downloads, Desktop and Documents under {@code home}, plus their names from {@code ~/.config/user-dirs.dirs}. */
    static List<Path> catchAllFolders(Path home) {
        List<Path> out = new ArrayList<>();
        out.add(home.resolve("Downloads"));
        out.add(home.resolve("Desktop"));
        out.add(home.resolve("Documents"));
        Path dirs = home.resolve(".config").resolve("user-dirs.dirs");
        try {
            if (Files.isRegularFile(dirs)) {
                for (String line : Files.readAllLines(dirs)) {
                    Path p = xdgUserDir(line, home);
                    if (p != null) {
                        out.add(p);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable: the English names still apply
        }
        return out;
    }

    /** {@code XDG_DOWNLOAD_DIR="$HOME/Descargas"} ⇒ {@code <home>/Descargas}, for the three catch-all keys only. */
    static @Nullable Path xdgUserDir(String line, Path home) {
        String l = line.strip();
        int eq = l.indexOf('=');
        if (eq < 0) {
            return null;
        }
        String key = l.substring(0, eq).strip();
        if (!key.equals("XDG_DOWNLOAD_DIR") && !key.equals("XDG_DESKTOP_DIR") && !key.equals("XDG_DOCUMENTS_DIR")) {
            return null;
        }
        String value = l.substring(eq + 1).strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("$HOME/")) {
            return home.resolve(value.substring("$HOME/".length()));
        }
        return value.startsWith("/") ? Path.of(value) : null;
    }

    /**
     * The folder to serve for {@code file}: the window's project root when the file is inside it (and the project
     * is a safe root); for a loose file, the nearest ancestor holding an {@code index.html} (searching up to, not
     * into, an unsafe folder such as the home), else the file's own folder. The result may still be unsafe (a
     * page sitting directly in the home): the caller checks {@link #isUnsafeDocRoot} and refuses.
     */
    public static Path siteRoot(Path file, @Nullable Path projectRoot, @Nullable Path home) {
        Path abs = file.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        Path folder = parent == null ? abs : parent;
        if (projectRoot != null && projectRoot.getFileSystem() == abs.getFileSystem()) {
            Path pr = projectRoot.toAbsolutePath().normalize();
            if (abs.startsWith(pr) && !abs.equals(pr) && !isUnsafeDocRoot(pr, home)) {
                return pr;
            }
        }
        for (Path d = folder; d != null && !isUnsafeDocRoot(d, home); d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("index.html"))) {
                return d;
            }
        }
        return folder;
    }

    /** {@link #siteRoot(Path, Path, Path)} against the current user's home. */
    public static Path siteRoot(Path file, @Nullable Path projectRoot) {
        return siteRoot(file, projectRoot, userHome());
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
    static @Nullable Path safeResolve(Path root, String rel) {
        Path resolved;
        try {
            resolved = root.resolve(rel).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        if (!resolved.startsWith(root)) {
            return null; // lexical escape (…/../…, absolute path)
        }
        try {
            return resolved.toRealPath().startsWith(root.toRealPath()) ? resolved : null;
        } catch (IOException notYetExisting) {
            return resolved; // no such file — safe to return; the read will 404
        }
    }

    /** Splices {@code script} before the <em>last</em> {@code </body>} (appends it when there is none). */
    static String injectReloadScript(String html, String script) {
        int idx = lastBodyClose(html.length(), html::charAt);
        return idx >= 0 ? html.substring(0, idx) + script + html.substring(idx) : html + script;
    }

    /**
     * The byte form, for a page read from disk, leaving its encoding alone: {@code script} is ASCII, so splicing it
     * into any ASCII-compatible encoding (UTF-8, Latin-1, Windows-1252, Shift_JIS…) is safe. A UTF-16/32 page
     * (byte-order mark, or a leading NUL) is returned unchanged — it previews, without live reload.
     */
    static byte[] injectReloadScript(byte[] html, String script) {
        boolean wide = html.length >= 2
                && ((html[0] == (byte) 0xFF && html[1] == (byte) 0xFE)
                        || (html[0] == (byte) 0xFE && html[1] == (byte) 0xFF)
                        || html[0] == 0
                        || html[1] == 0);
        if (wide) {
            return html;
        }
        byte[] s = script.getBytes(StandardCharsets.US_ASCII);
        int idx = lastBodyClose(html.length, i -> html[i] & 0xFF);
        int at = idx >= 0 ? idx : html.length;
        byte[] out = new byte[html.length + s.length];
        System.arraycopy(html, 0, out, 0, at);
        System.arraycopy(s, 0, out, at, s.length);
        System.arraycopy(html, at, out, at + s.length, html.length - at);
        return out;
    }

    /**
     * The index of the last {@code </body} followed by {@code >}, {@code /} or whitespace, matched ASCII
     * case-insensitively without changing the text's length (so a Turkish {@code İ}, two chars when lowercased,
     * cannot shift the index), or -1. The last one, so a {@code </body>} in a script string or a comment
     * earlier in the page is passed over.
     */
    private static int lastBodyClose(int length, IntUnaryOperator at) {
        String needle = "</body";
        for (int i = length - needle.length(); i >= 0; i--) {
            boolean match = true;
            for (int k = 0; k < needle.length() && match; k++) {
                int c = at.applyAsInt(i + k);
                if (c >= 'A' && c <= 'Z') {
                    c += 'a' - 'A';
                }
                match = c == needle.charAt(k);
            }
            if (match) {
                int after = i + needle.length();
                int c = after < length ? at.applyAsInt(after) : '>';
                if (c == '>' || c == '/' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * The live-reload client: polls {@code <base>/__editora_livereload?v=<version>} every
     * {@value #POLL_INTERVAL_MS} ms. 204 ⇒ no change; 200 ⇒ reload, or with {@code " css"} re-fetch the
     * stylesheets only; anything else (a 404 after a restart, a 500, a network error) backs off exponentially and
     * after six failures in a row stops and shows {@code goneMessage}. The script is pure ASCII.
     */
    static String reloadScript(long version, String base, String goneMessage) {
        return "\n<script>(function(){var v=" + version + ",u='" + base + LR_PATH + "?v=',f=0;"
                + "function later(ms){setTimeout(poll,ms);}"
                + "function gone(){var d=document.createElement('div');d.textContent=" + jsString(goneMessage) + ";"
                + "d.setAttribute('style','position:fixed;left:8px;bottom:8px;z-index:2147483647;padding:6px 10px;"
                + "font:13px system-ui,sans-serif;background:#333;color:#fff;border-radius:4px;opacity:.92');"
                + "(document.body||document.documentElement).appendChild(d);}"
                + "function fail(){f++;if(f>=6){gone();}else{later(Math.min(30000,1000*Math.pow(2,f)));}}"
                + "function css(){var l=document.querySelectorAll('link[rel~=\"stylesheet\"]');"
                + "for(var i=0;i<l.length;i++){var h=l[i].getAttribute('href');if(!h){continue;}"
                + "h=h.replace(/([?&])editora-lr=\\d+&?/,'$1').replace(/[?&]$/,'');"
                + "l[i].setAttribute('href',h+(h.indexOf('?')<0?'?':'&')+'editora-lr='+Date.now());}}"
                + "function poll(){fetch(u+v,{cache:'no-store'}).then(function(r){"
                + "if(r.status===204){f=0;later(" + POLL_INTERVAL_MS + ");return;}"
                + "if(r.status!==200){fail();return;}"
                + "return r.text().then(function(t){var p=t.split(' ');"
                + "if(p[1]==='css'){v=+p[0];f=0;css();later(" + POLL_INTERVAL_MS + ");}else{location.reload();}});"
                + "}).catch(fail);}"
                + "later(" + POLL_INTERVAL_MS + ");})();</script>\n";
    }

    /** A single-quoted JavaScript string literal, every non-ASCII or markup-sensitive char escaped as \\uXXXX. */
    static String jsString(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '\'' || c == '\\' || c == '<' || c == '>' || c == '&') {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * Parses a single {@code bytes=} range against a file of {@code size} bytes: {@code {start, end}} inclusive,
     * {@link #UNSATISFIABLE} when it lies past the end, or null to ignore the header (malformed, several ranges,
     * another unit) and send the whole file.
     */
    static long @Nullable [] parseRange(String header, long size) {
        String h = header.strip().toLowerCase(Locale.ROOT);
        if (!h.startsWith("bytes=") || h.indexOf(',') >= 0) {
            return null;
        }
        String spec = h.substring("bytes=".length()).strip();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String a = spec.substring(0, dash).strip();
            String b = spec.substring(dash + 1).strip();
            if (a.isEmpty()) { // suffix: the last N bytes
                if (b.isEmpty()) {
                    return null;
                }
                long n = Long.parseLong(b);
                if (n <= 0 || size == 0) {
                    return UNSATISFIABLE;
                }
                return new long[] {Math.max(0, size - n), size - 1};
            }
            long start = Long.parseLong(a);
            long last = b.isEmpty() ? Long.MAX_VALUE : Long.parseLong(b);
            if (start < 0 || last < start) {
                return null;
            }
            if (start >= size) {
                return UNSATISFIABLE;
            }
            return new long[] {start, Math.min(last, size - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Maps a filename to a Content-Type by extension (no charset; octet-stream fallback). */
    static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "html", "htm", "xhtml" -> "text/html";
            case "css" -> "text/css";
            case "js", "mjs", "cjs" -> "text/javascript";
            case "json", "map" -> "application/json";
            case "jsonld" -> "application/ld+json";
            case "webmanifest" -> "application/manifest+json";
            case "xml" -> "application/xml";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "md" -> "text/markdown";
            case "vtt" -> "text/vtt";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "apng" -> "image/apng";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "wasm" -> "application/wasm";
            case "pdf" -> "application/pdf";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "ogv" -> "video/ogg";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "oga", "ogg" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    /** Text types: they may carry a {@code charset} parameter, and are buffered when small. */
    static boolean isText(String type) {
        return type.startsWith("text/")
                || type.equals("application/json")
                || type.equals("application/ld+json")
                || type.equals("application/manifest+json")
                || type.equals("application/xml")
                || type.equals("image/svg+xml");
    }

    private static String withCharset(String type, boolean utf8) {
        return utf8 && isText(type) ? type + "; charset=utf-8" : type;
    }

    private static boolean isStylesheet(Path p) {
        Path name = p.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".css");
    }

    /** Whether {@code bytes} decode as UTF-8 without a single malformed sequence (through a small buffer). */
    static boolean isUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(8192);
        while (true) {
            CoderResult r = decoder.decode(in, out, true);
            if (r.isError()) {
                return false;
            }
            if (r.isUnderflow()) {
                out.clear();
                return !decoder.flush(out).isError();
            }
            out.clear(); // overflow: drop what was decoded and go on
        }
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
