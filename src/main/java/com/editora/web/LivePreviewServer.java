package com.editora.web;

import java.io.IOException;
import java.io.InputStream;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import com.editora.text.EncryptedNames;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

/**
 * A tiny embedded HTTP server (JDK {@link HttpServer}) that serves HTML sites so a real browser can render a page
 * with its CSS/JS/image assets, bound to <strong>loopback only</strong> (never the LAN).
 *
 * <p>It serves any number of <em>sites</em> ({@link #serve}), each a folder with <strong>its own origin</strong>:
 * its own loopback listener (port) and its own random token, so a page previewed later — a downloaded one, say —
 * cannot read an earlier site's files or unsaved buffers, which the browser's same-origin policy now keeps apart
 * (HTML review 2, E102). Every listener runs on one shared executor. A file that is open in the editor is served
 * from its <em>live in-memory text</em> (the injected {@link #setLiveText lookup}), so unsaved edits show. Every
 * HTML page gets a small <strong>live-reload script</strong> that short-polls {@code /<token>/__editora_livereload}
 * every {@value #POLL_INTERVAL_MS} ms; the server answers each poll at once (no request is ever held), and {@link
 * #changed} advances the version of the site holding the changed file. A stylesheet-only change is answered with
 * {@code "<version> css"} and the page swaps its own-origin stylesheets instead of reloading.
 *
 * <p>Loopback binding is not a boundary on its own: any local process can reach the port, a web page can reach it
 * through DNS rebinding (its own hostname re-pointed at 127.0.0.1), and every script the page runs (CDN ones
 * included) is same-origin with its site. So every request must (1) carry a {@code Host} header naming that
 * site's listener ({@link #isAllowedHost}) and (2) present the site's random token (new for every site and every
 * {@link #start}) as its first path segment ({@link #route}); hidden files and folders, private keys, credential
 * stores and encrypted files are never served ({@link #isHiddenOrSecret}); and a root that would expose the home
 * directory, a catch-all folder such as Downloads, a temp folder, a filesystem root or a remote file system is
 * refused outright ({@link SiteRoots#isUnsafeDocRoot}). {@code /} and a folder URL serve that folder's {@code
 * index.html}. Files are streamed (with single-range {@code Range} support), not read whole: only HTML up to
 * {@link #MAX_INJECT_BYTES} (and live text) is held in memory, at most {@value #MAX_BUFFERED} responses at a time
 * (a request that waits {@value #BUFFERED_WAIT_MILLIS} ms for its turn is answered 503).
 *
 * <p>Lifecycle is owned by {@code HtmlPreviewService} (one server per window). The pure helpers are unit-tested.
 */
public final class LivePreviewServer {

    /** The poll endpoint the injected script hits, under a site's token; always answered at once. */
    static final String LR_PATH = "/__editora_livereload";

    /** How often the injected client polls, in milliseconds. */
    static final int POLL_INTERVAL_MS = 700;

    /** HTML on disk larger than this is streamed as is (no live-reload script) rather than read into memory. */
    static final long MAX_INJECT_BYTES = 20L * 1024 * 1024;

    /**
     * Editor text longer than this is not served live (the file is served from disk): the window does not even
     * snapshot such a buffer, as every other feature backs off for large files (E105, E111).
     */
    public static final int MAX_LIVE_CHARS = 4 * 1024 * 1024;

    /** At most this many responses are built in memory at once (HTML with the script, live text): E105. */
    static final int MAX_BUFFERED = 4;

    /**
     * How long a request waits for one of the {@link #MAX_BUFFERED} permits before it is answered 503: a permit is
     * held while its body is written, so clients that stop reading must not stall every page for good (E202).
     */
    static final long BUFFERED_WAIT_MILLIS = 10_000;

    /** At most this many sites keep a listener; serving one more stops the least recently served. */
    static final int MAX_SITES = 32;

    /** A text file larger than this is streamed without first checking that its bytes are UTF-8. */
    private static final long MAX_CHARSET_CHECK = 64L * 1024 * 1024;

    private static final int CHUNK = 64 * 1024;

    /** {@link #parseRange}'s answer for a range that starts past the end of the file (416). */
    static final long[] UNSATISFIABLE = new long[0];

    static {
        // The JDK server's own limits, read once by its configuration class (so they apply only when it has not
        // been used yet in this JVM, and only where the user has not set them): a connection that has not sent
        // its whole request within 60 s is closed, and one listener holds at most 512 connections (E113).
        setIfAbsent("sun.net.httpserver.maxReqTime", "60");
        setIfAbsent("jdk.httpserver.maxConnections", "512");
    }

    /** {@link #MAX_INJECT_BYTES}, lowered by tests. */
    volatile long injectCap = MAX_INJECT_BYTES;

    /** {@link #BUFFERED_WAIT_MILLIS}, lowered by tests. */
    volatile long bufferedWaitMillis = BUFFERED_WAIT_MILLIS;

    private @Nullable ExecutorService pool;
    private final Semaphore buffered = new Semaphore(MAX_BUFFERED);
    private final Map<Path, Site> sites = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong();
    private volatile Function<Path, @Nullable String> liveText = p -> null;
    private volatile String goneMessage = "The Editora preview server has stopped.";

    /** One served folder: its own listener and token, the pages opened in it, and its reload version. */
    private static final class Site {
        final Path root; // absolute, normalized
        final String token = newToken();
        final HttpServer http;
        final int port;
        /** The pages previewed in this site (root-relative, {@code /}-separated): served even in a hidden folder. */
        final Set<String> entries = ConcurrentHashMap.newKeySet();

        volatile long lastServed;
        private long version = 1;
        private long fullVersion = 1; // the last version that needs a full reload (not just a stylesheet swap)

        Site(Path root, HttpServer http) {
            this.root = root;
            this.http = http;
            this.port = http.getAddress().getPort();
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

        String base() {
            return "http://127.0.0.1:" + port + "/" + token;
        }
    }

    /** Readies the server (idempotent): sites get their listeners, and fresh tokens, as they are served. */
    public synchronized void start() {
        if (pool == null) {
            // A virtual thread per exchange: the JDK server reads request headers on the executor, so a fixed
            // pool could be exhausted by a few idle connections (any local process, no token needed). Nothing
            // here holds a request open on purpose: the live-reload poll is answered at once.
            pool = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("html-preview-http-", 0).factory());
        }
    }

    public synchronized boolean isRunning() {
        return pool != null;
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
     * Serves {@code root} (adding it as a site on a listener of its own, or reusing the site already serving it)
     * and returns the URL that renders {@code entry}, which must lie under it. Refuses an unsafe root ({@link
     * SiteRoots#isUnsafeDocRoot}).
     */
    public synchronized String serve(Path root, Path entry) throws IOException {
        ExecutorService p = pool;
        if (p == null) {
            throw new IOException("the preview server is not running");
        }
        Path r = root.toAbsolutePath().normalize();
        Path e = entry.toAbsolutePath().normalize();
        if (!e.startsWith(r) || e.equals(r)) {
            throw new IOException(entry + " is not inside " + root);
        }
        if (SiteRoots.isUnsafeDocRoot(r, SiteRoots.userHome())) {
            throw new IOException("refusing to serve " + r + " (home, catch-all or temp folder, or a filesystem root)");
        }
        String rel = r.relativize(e).toString().replace('\\', '/');
        Site site = sites.get(r);
        if (site == null) {
            evictIfFull();
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.setExecutor(p);
            Site created = new Site(r, s);
            s.createContext("/", ex -> handle(ex, created));
            s.start();
            sites.put(r, created);
            site = created;
        }
        site.entries.add(rel);
        site.lastServed = clock.incrementAndGet();
        return site.base() + "/" + encodePath(rel);
    }

    /** Stops the least recently served site when {@link #MAX_SITES} are served (its open tabs show "stopped"). */
    private void evictIfFull() {
        while (sites.size() >= MAX_SITES) {
            Site oldest = null;
            for (Site s : sites.values()) {
                if (oldest == null || s.lastServed < oldest.lastServed) {
                    oldest = s;
                }
            }
            if (oldest == null) {
                return;
            }
            sites.remove(oldest.root);
            oldest.http.stop(0);
        }
    }

    /** Whether {@code file} lies under a served site (so its edits and saves should reload the browser). */
    public boolean serves(Path file) {
        Path p = file.toAbsolutePath().normalize();
        for (Site s : sites.values()) {
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
        for (Site s : sites.values()) {
            if (p.startsWith(s.root)) {
                s.changed(stylesheet);
                any = true;
            }
        }
        return any;
    }

    /** Advances every site's version so every open page reloads. */
    public void bumpVersion() {
        sites.values().forEach(s -> s.changed(false));
    }

    /** Stops every site's listener and forgets them (idempotent); a later {@link #start} hands out new tokens. */
    public synchronized void stop() {
        for (Site s : sites.values()) {
            s.http.stop(0);
        }
        sites.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    // --- request handling -------------------------------------------------------------------------

    private void handle(HttpExchange ex, Site site) throws IOException {
        try {
            Headers out = ex.getResponseHeaders();
            out.set("Cache-Control", "no-store");
            out.set("X-Content-Type-Options", "nosniff");
            // Same-origin pages still send a Referer (the bounce below needs it); a cross-origin request — a
            // CDN script or image, a link out — never carries the token.
            out.set("Referrer-Policy", "same-origin");
            // Another origin (another previewed site, any web page) cannot even embed what is served here.
            out.set("Cross-Origin-Resource-Policy", "same-origin");
            if (!isAllowedHost(ex.getRequestHeaders().getFirst("Host"), site.port)) {
                sendStatus(ex, 403); // DNS rebinding / a foreign origin: not addressed to this loopback listener
                return;
            }
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                out.set("Allow", "GET, HEAD");
                sendStatus(ex, 405);
                return;
            }
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            String inner = route(path, site.token);
            if (inner == null) {
                // A root-absolute reference (<link href="/app.css">, <a href="/">) from a page of this site: the
                // browser's Referer proves it already holds the token, so bounce it under the token.
                if (isOwnPage(ex.getRequestHeaders().getFirst("Referer"), site.port, site.token)) {
                    redirect(ex, 307, "/" + site.token + ex.getRequestURI().getRawPath(), query);
                } else {
                    sendStatus(ex, 404);
                }
                return;
            }
            if (path.equals("/" + site.token)) {
                redirect(ex, 301, "/" + site.token + "/", query); // so the index's relative links resolve
            } else if (LR_PATH.equals(inner)) {
                String answer = site.poll(parseSince(query));
                if (answer == null) {
                    sendStatus(ex, 204); // no change: the client polls again shortly
                } else {
                    writeBody(ex, 200, "text/plain; charset=utf-8", answer.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                serveFile(ex, site, inner);
            }
        } catch (RuntimeException e) {
            sendStatus(ex, 500);
        } finally {
            ex.close();
        }
    }

    private void serveFile(HttpExchange ex, Site site, String urlPath) throws IOException {
        // Read first: an edit landing while the text is read then bumps past the version the page is given, so
        // the page reloads once more rather than missing that edit (E113).
        long version = site.version();
        // urlPath is URI.getPath(), which is already %-decoded — do NOT URLDecoder.decode again (that is form
        // decoding: it turns "+" into a space and double-decodes "%25", breaking asset filenames that contain
        // "+", "%", or a space).
        String rel = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
        boolean folderUrl = rel.isEmpty() || rel.endsWith("/");
        if (folderUrl) {
            rel = rel + "index.html"; // "/" and "blog/" serve that folder's index, as every static host does
        }
        if (!isServable(rel, site.entries)) {
            sendStatus(ex, 404); // .env, .git/config, id_rsa, *.p12, *.gpg: same-origin with every script
            return;
        }
        Path resolved = safeResolve(site.root, rel);
        if (resolved == null) {
            sendStatus(ex, 403); // path traversal outside the site root
            return;
        }
        if (hidesBehindALink(site.root, resolved, site.entries)) {
            sendStatus(ex, 404); // an in-root symlink that leads into .git or another hidden folder
            return;
        }
        if (!folderUrl && Files.isDirectory(resolved)) {
            redirect(
                    ex,
                    301,
                    ex.getRequestURI().getRawPath() + "/",
                    ex.getRequestURI().getRawQuery());
            return;
        }
        Path fileName = resolved.getFileName();
        String type = contentType(fileName == null ? "" : fileName.toString());
        boolean html = "text/html".equals(type);
        String script = html ? reloadScript(version, "/" + site.token, goneMessage) : null;
        String live = liveText.apply(resolved);
        if (live != null && live.length() <= MAX_LIVE_CHARS) {
            // An open file: the editor's text, unsaved edits included (encoded as UTF-8, so labelled so).
            buffered(ex, withCharset(type, true), () -> live.getBytes(StandardCharsets.UTF_8), script);
            return;
        }
        if (!Files.isRegularFile(resolved)) {
            sendStatus(ex, 404);
            return;
        }
        long size = Files.size(resolved);
        String range = ex.getRequestHeaders().getFirst("Range");
        if (html && size <= injectCap) {
            buffered(ex, null, () -> Files.readAllBytes(resolved), script);
            return;
        }
        // Only HTML needs splicing; everything else is streamed. A text file is labelled UTF-8 only when its bytes
        // are (checked through a small buffer), so a Latin-1 stylesheet keeps its own @charset.
        boolean utf8 = isText(type) && size <= MAX_CHARSET_CHECK && isUtf8(resolved);
        streamFile(ex, resolved, size, withCharset(type, utf8), range);
    }

    /** Builds a response body in memory. */
    @FunctionalInterface
    private interface Body {
        byte[] read() throws IOException;
    }

    /**
     * Sends a body built in memory, the live-reload {@code script} spliced in when non-null, holding one of {@link
     * #MAX_BUFFERED} permits while the body exists (so parallel requests for a big page cannot exhaust the heap).
     * One that gets no permit within {@link #bufferedWaitMillis} is answered 503 (E202): the permits may all be held
     * by clients that stopped reading. A null {@code contentType} means HTML read from disk: labelled UTF-8 only
     * when its bytes are.
     */
    private void buffered(HttpExchange ex, @Nullable String contentType, Body body, @Nullable String script)
            throws IOException {
        try {
            if (!buffered.tryAcquire(bufferedWaitMillis, TimeUnit.MILLISECONDS)) {
                ex.getResponseHeaders().set("Retry-After", "1");
                sendStatus(ex, 503); // busy, not broken: the page loads again once those clients read or go
                return;
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            sendStatus(ex, 503);
            return;
        }
        try {
            byte[] bytes = body.read();
            // Label UTF-8 only when the bytes are UTF-8; otherwise the page's own <meta charset> (or the browser's
            // sniffing) decides, so a Latin-1 page is not turned into U+FFFD.
            String type = contentType != null ? contentType : withCharset("text/html", isUtf8(bytes));
            byte[] s = script == null ? new byte[0] : script.getBytes(StandardCharsets.US_ASCII);
            int at = script == null ? -1 : injectionPoint(bytes);
            int extra = at < 0 ? 0 : s.length;
            ex.getResponseHeaders().set("Content-Type", type);
            long length = (long) bytes.length + extra;
            if (isHead(ex) || length == 0) {
                ex.getResponseHeaders().set("Content-Length", Long.toString(length));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, length);
            try (OutputStream out = ex.getResponseBody()) {
                int split = at < 0 ? bytes.length : at;
                writeChunked(out, bytes, 0, split);
                if (at >= 0) {
                    out.write(s);
                }
                writeChunked(out, bytes, split, bytes.length);
            }
        } finally {
            buffered.release();
        }
    }

    /** Writes in {@value #CHUNK}-byte pieces: the JDK copies each write into a buffer of its size. */
    private static void writeChunked(OutputStream out, byte[] b, int from, int to) throws IOException {
        for (int i = from; i < to; i += CHUNK) {
            out.write(b, i, Math.min(CHUNK, to - i));
        }
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
                long n = ch.transferTo(pos, Math.min(left, CHUNK), sink);
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

    private static void redirect(HttpExchange ex, int status, String location, @Nullable String rawQuery) {
        ex.getResponseHeaders().set("Location", location + (rawQuery == null ? "" : "?" + rawQuery));
        sendStatus(ex, status);
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

    private static void setIfAbsent(String key, String value) {
        try {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        } catch (SecurityException ignored) {
            // the JDK defaults then apply
        }
    }

    // --- pure helpers (unit-tested) ---------------------------------------------------------------

    private static String newToken() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
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
     * Whether {@code referer} is a page of the site on {@code port} with {@code token}: the same listener and the
     * token prefix. Only then is a root-absolute request bounced under the token (not an open redirect).
     */
    static boolean isOwnPage(@Nullable String referer, int port, String token) {
        if (referer == null) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(referer.strip());
            return "http".equalsIgnoreCase(uri.getScheme())
                    && isAllowedHost(uri.getRawAuthority(), port)
                    && route(uri.getPath(), token) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Folders never served, not even when a previewed page sits in them. */
    private static final Set<String> NEVER_FOLDERS = Set.of(".git", ".ssh", ".gnupg", ".aws", ".docker", ".kube");

    /**
     * Whether a URL path (relative to a site root) names something no page should read: any segment starting
     * with {@code .} ({@code .env}, {@code .git/config}, {@code .ssh/}, {@code .npmrc}, {@code .netrc}) other than
     * {@code .well-known}; a private key ({@code *.pem}, {@code *.key}, an SSH identity {@code id_*} without an
     * extension or ending in {@code .pub}); a certificate or password store, infrastructure state or a database
     * ({@code *.p12}, {@code *.pfx}, {@code *.kdbx}, {@code *.tfstate}, {@code *.tfvars}, {@code *.sqlite},
     * {@code *.sqlite3}, {@code *.db}, {@code credentials*.json}, {@code secrets.*}); or an encrypted file
     * ({@code *.gpg}, {@code *.asc}, {@code *.pgp}: ADR 0017 — even its ciphertext is not handed out).
     */
    static boolean isHiddenOrSecret(String rel) {
        String[] segments = rel.split("[/\\\\]");
        for (String seg : segments) {
            if (seg.startsWith(".") && !seg.equals(".well-known")) {
                return true;
            }
        }
        return isSecretName(segments.length == 0 ? "" : segments[segments.length - 1]);
    }

    private static boolean isSecretName(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        for (String ext :
                List.of(".pem", ".key", ".p12", ".pfx", ".kdbx", ".tfstate", ".tfvars", ".sqlite", ".sqlite3", ".db")) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        if (name.contains(".tfstate.") // terraform.tfstate.backup
                || name.startsWith("credentials") && name.endsWith(".json")
                || name.startsWith("secrets.")
                || EncryptedNames.extensionOf(name) != null) {
            return true;
        }
        return name.startsWith("id_") && (name.indexOf('.') < 0 || name.endsWith(".pub"));
    }

    /**
     * Whether {@code rel} may be served, given the {@code entries} previewed in its site: a previewed page itself,
     * and what lies in its own folder, are served even in a hidden folder ({@code .github/}, {@code .vitepress/}),
     * still minus dotfiles and secrets inside it and never in {@code .git}, {@code .ssh} and their like; secrets
     * and encrypted files never are (E113).
     */
    static boolean isServable(String rel, Collection<String> entries) {
        String r = rel.replace('\\', '/');
        String[] segments = r.split("/");
        if (isSecretName(segments.length == 0 ? "" : segments[segments.length - 1])) {
            return false;
        }
        for (String entry : entries) {
            int slash = entry.lastIndexOf('/');
            String folder = slash < 0 ? "" : entry.substring(0, slash);
            if (neverServed(folder)) {
                continue;
            }
            if (r.equals(entry)
                    || !folder.isEmpty()
                            && r.startsWith(folder + "/")
                            && !isHiddenOrSecret(r.substring(folder.length() + 1))) {
                return true;
            }
        }
        return !isHiddenOrSecret(r);
    }

    private static boolean neverServed(String folder) {
        for (String seg : folder.split("/")) {
            if (NEVER_FOLDERS.contains(seg.toLowerCase(Locale.ROOT))
                    || seg.toLowerCase(Locale.ROOT).startsWith(".env")) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code resolved}'s real path, relative to the root's, passes through a hidden or secret name. */
    private static boolean hidesBehindALink(Path root, Path resolved, Collection<String> entries) {
        try {
            Path rel = root.toRealPath().relativize(resolved.toRealPath());
            return !isServable(rel.toString(), entries);
        } catch (IOException | IllegalArgumentException e) {
            return false; // not there (the read 404s) or not comparable
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
        int at = injectionPoint(html);
        if (at < 0) {
            return html;
        }
        byte[] s = script.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[html.length + s.length];
        System.arraycopy(html, 0, out, 0, at);
        System.arraycopy(s, 0, out, at, s.length);
        System.arraycopy(html, at, out, at + s.length, html.length - at);
        return out;
    }

    /**
     * Where the script goes in a page's bytes: before the last {@code </body>}, else at the end; -1 for a
     * UTF-16/32 page (byte-order mark, or a leading NUL), which is left alone.
     */
    static int injectionPoint(byte[] html) {
        boolean wide = html.length >= 2
                && ((html[0] == (byte) 0xFF && html[1] == (byte) 0xFE)
                        || (html[0] == (byte) 0xFE && html[1] == (byte) 0xFF)
                        || html[0] == 0
                        || html[1] == 0);
        if (wide) {
            return -1;
        }
        int idx = lastBodyClose(html.length, i -> html[i] & 0xFF);
        return idx >= 0 ? idx : html.length;
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
     * {@value #POLL_INTERVAL_MS} ms. 204 ⇒ no change; 200 ⇒ reload, or with {@code " css"} re-fetch the page's
     * own-origin stylesheets only; anything else (a 404 after a restart, a 500, a network error) backs off exponentially and
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
                // Only this site's own stylesheets: a CDN one is left cached and untouched (E110).
                + "try{if(new URL(h,location.href).origin!==location.origin){continue;}}catch(e){continue;}"
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

    /** {@link #isUtf8(byte[])} for a file, read through a small buffer (nothing is held in memory). */
    static boolean isUtf8(Path file) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.allocate(CHUNK);
        CharBuffer out = CharBuffer.allocate(CHUNK);
        try (InputStream stream = Files.newInputStream(file)) {
            while (true) {
                int n = stream.read(in.array(), in.position(), in.remaining());
                boolean end = n < 0;
                if (!end) {
                    in.position(in.position() + n);
                }
                in.flip();
                CoderResult r;
                do {
                    r = decoder.decode(in, out, end);
                    if (r.isError()) {
                        return false;
                    }
                    out.clear();
                } while (r.isOverflow());
                in.compact();
                if (end) {
                    return !decoder.flush(out).isError();
                }
            }
        } catch (IOException e) {
            return false;
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
