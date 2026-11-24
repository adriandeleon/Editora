package com.editora.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.AppInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

/**
 * The user guide that ships inside the app, served to the browser from {@code 127.0.0.1}.
 *
 * <p>The guide's source is this repository's {@code user-docs/}; the website's Astro build turns it into the
 * same HTML the website serves ({@code scripts/build-app-docs.mjs} in editora-website), and a packaged build
 * carries that folder as resources under {@value #ROOT} (the {@code docs.site.dir} property of the pom). A build
 * without it (a development run, a local {@code -Pdist} without Node) has no {@value #MANIFEST} and every docs
 * link goes to the website as before.
 *
 * <p>The pages link each other, their styles and the search index by absolute path ({@code /docs/v-…/},
 * {@code /_astro/…}, {@code /pagefind/…}), so they are served from the root of their own origin rather than
 * opened as {@code file:} pages, where the browser would not resolve those paths and would block the search's
 * fetches. Anything the copy does not hold (News, Features, an archived version) is redirected to the same path on
 * {@link AppInfo#HOMEPAGE}, so every link in a page still lands somewhere.
 *
 * <p>The server starts on the first docs link opened ({@link #localize}), on the caller's thread, which is the link
 * classifier's background thread, never the FX thread. It listens on loopback only and answers only requests whose
 * {@code Host} names it (DNS rebinding); everything it serves is public, read-only documentation, so it needs no
 * token. It first tries {@value #PREFERRED_PORT}, so the browser keeps the site's own settings (the theme) from one
 * run to the next, and takes any free port when that one is busy (another Editora, say).
 */
public final class AppDocs {

    /** Where the bundled site sits among the module's resources. Not a package name, so never encapsulated. */
    static final String ROOT = "/com/editora/docs-site";

    /** Written last by the bundle build: {@code {"version": "…", "docs": "/docs/v-…/", …}}. */
    static final String MANIFEST = ROOT + "/app-docs.json";

    /** Tried first, so the page's origin, and with it what the page keeps in the browser, stays the same. */
    static final int PREFERRED_PORT = 47_831;

    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    /** Opens one file of the bundled site by its path under {@link #ROOT}; replaced by tests. */
    static volatile java.util.function.Function<String, @Nullable InputStream> files =
            path -> AppDocs.class.getResourceAsStream(ROOT + path);

    private static final Object LOCK = new Object();
    private static volatile @Nullable String bundledVersion;
    private static volatile boolean manifestRead;
    private static @Nullable HttpServer server;
    private static @Nullable ExecutorService pool;
    private static int port;

    private AppDocs() {}

    /**
     * True when this build carries the guide for the version it is: the manifest is there and names {@link
     * AppInfo#releaseVersion()}. A copy for another version is not used, since every link the app makes names its
     * own version. Reads one small resource, once.
     */
    public static boolean bundled() {
        String v = bundledVersion();
        return v != null && v.equals(AppInfo.releaseVersion());
    }

    /** The version the bundled guide was built for, or null when the build has none. */
    public static @Nullable String bundledVersion() {
        if (!manifestRead) {
            synchronized (LOCK) {
                if (!manifestRead) {
                    bundledVersion = readManifestVersion();
                    manifestRead = true;
                }
            }
        }
        return bundledVersion;
    }

    private static @Nullable String readManifestVersion() {
        try (InputStream in = files.apply("/app-docs.json")) {
            if (in == null) {
                return null;
            }
            return manifestVersion(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** The {@code version} of a manifest's JSON text, or null. Pure, for tests. */
    static @Nullable String manifestVersion(String json) {
        Matcher m = VERSION.matcher(json);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * {@code url} itself, or, when it is a page of this version's docs on the website and the build carries them,
     * the same page on the local server (started now if it is not running). Blocks briefly the first time (binding
     * a loopback port), so it is called off the FX thread: from the link classifier.
     */
    public static String localize(String url) {
        if (!isThisVersionsDocs(url, AppInfo.docsUrl()) || !bundled()) {
            return url;
        }
        int p = ensureStarted();
        return p <= 0 ? url : toLocal(url, p);
    }

    /** Whether {@code url} is a page of this version's docs on the website, the links {@link #localize} serves. */
    public static boolean isThisVersionsDocs(@Nullable String url) {
        return isThisVersionsDocs(url, AppInfo.docsUrl());
    }

    /**
     * Whether {@code url} is under {@code docsBase} (this version's docs on the website): the base itself, or the
     * base followed by {@code /}, {@code ?} or {@code #}. Pure, for tests.
     */
    static boolean isThisVersionsDocs(@Nullable String url, String docsBase) {
        if (url == null || !url.startsWith(docsBase)) {
            return false;
        }
        if (url.length() == docsBase.length()) {
            return true;
        }
        char next = url.charAt(docsBase.length());
        return next == '/' || next == '?' || next == '#';
    }

    /** The website URL {@code url} (under {@link AppInfo#HOMEPAGE}) on the local server at {@code port}. Pure. */
    static String toLocal(String url, int port) {
        String rest = url.substring(AppInfo.HOMEPAGE.length());
        return "http://127.0.0.1:" + port + (rest.isEmpty() ? "/" : rest);
    }

    /**
     * The local server's port, starting it if needed; 0 when it could not start or there is no guide to serve (the
     * caller keeps the web link). The guide is checked again under the lock, since {@link #localize} checks it
     * before: tests swap {@link #files} and stop the server, and a caller that checked before the swap must not
     * start a server after it.
     */
    static int ensureStarted() {
        synchronized (LOCK) {
            if (server != null) {
                return port;
            }
            if (!bundled()) {
                return 0;
            }
            try {
                InetAddress loopback = InetAddress.getLoopbackAddress();
                HttpServer http;
                try {
                    http = HttpServer.create(new InetSocketAddress(loopback, PREFERRED_PORT), 0);
                } catch (BindException busy) {
                    http = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
                }
                ExecutorService exec = Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "editora-docs-server");
                    t.setDaemon(true);
                    return t;
                });
                int bound = http.getAddress().getPort();
                http.createContext("/", ex -> handle(ex, bound));
                http.setExecutor(exec);
                http.start();
                server = http;
                pool = exec;
                port = bound;
                return bound;
            } catch (IOException e) {
                return 0;
            }
        }
    }

    /** Stops the server, if it runs (tests; the app's threads are daemons and end with it). */
    static void stop() {
        synchronized (LOCK) {
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (pool != null) {
                pool.shutdownNow();
                pool = null;
            }
            port = 0;
        }
    }

    private static void handle(HttpExchange ex, int boundPort) throws IOException {
        try (ex) {
            if (!LivePreviewServer.isAllowedHost(ex.getRequestHeaders().getFirst("Host"), boundPort)) {
                send(ex, 403, null);
                return;
            }
            String method = ex.getRequestMethod();
            boolean head = "HEAD".equals(method);
            if (!head && !"GET".equals(method)) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                send(ex, 405, null);
                return;
            }
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            if ("/".equals(path) || "".equals(path)) {
                redirect(ex, "/docs/v-" + AppInfo.releaseVersion() + "/");
                return;
            }
            String resource = path == null ? null : resolve(path);
            if (resource != null) {
                for (String candidate : candidates(resource)) {
                    InputStream in = files.apply(candidate);
                    if (in != null) {
                        try (in) {
                            serve(ex, candidate, in, head);
                        }
                        return;
                    }
                }
            }
            redirect(ex, webUrl(uri));
        }
    }

    /**
     * The request path as a resource path under {@link #ROOT}, or null for one that must not be looked up (a
     * {@code ..} or {@code .} segment, a backslash or a NUL). Pure, for tests.
     */
    static @Nullable String resolve(String path) {
        if (!path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return null;
        }
        for (String seg : path.split("/", -1)) {
            if (seg.equals("..") || seg.equals(".")) {
                return null;
            }
        }
        return path;
    }

    /**
     * The resources to try for a resolved path, in order: a folder's {@code index.html} first (a page URL, with or
     * without its trailing slash, and a command page such as {@code /commands/file.save} whose last segment has a
     * dot), then the file itself. The folder's page comes first because a jar answers a folder's own name too.
     */
    static String[] candidates(String path) {
        if (path.endsWith("/")) {
            return new String[] {path + "index.html"};
        }
        return new String[] {path + "/index.html", path};
    }

    /** The same request on the website, for a page the bundled copy does not have. Pure, for tests. */
    static String webUrl(URI uri) {
        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        String query = uri.getRawQuery();
        return AppInfo.HOMEPAGE + path + (query == null ? "" : "?" + query);
    }

    private static void serve(HttpExchange ex, String name, InputStream in, boolean head) throws IOException {
        byte[] body = in.readAllBytes();
        ex.getResponseHeaders().set("Content-Type", contentType(name));
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        if (head) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** The Content-Type for a file, text types in UTF-8. Pagefind's own index files are binary. */
    static String contentType(String name) {
        String type = LivePreviewServer.contentType(name);
        boolean text = type.startsWith("text/")
                || type.equals("application/json")
                || type.equals("application/xml")
                || type.equals("image/svg+xml");
        return text && !type.contains("charset") ? type + "; charset=utf-8" : type;
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        send(ex, 302, null);
    }

    private static void send(HttpExchange ex, int status, @Nullable String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** For tests: whether the server runs. */
    static boolean running() {
        synchronized (LOCK) {
            return server != null;
        }
    }

    /** For tests: forget the manifest so the next call reads it again. */
    static void resetManifest() {
        synchronized (LOCK) {
            manifestRead = false;
            bundledVersion = null;
        }
    }
}
