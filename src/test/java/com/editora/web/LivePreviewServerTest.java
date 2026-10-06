package com.editora.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LivePreviewServer}'s pure helpers (script injection, MIME map, traversal guard). */
class LivePreviewServerTest {

    @Test
    void injectsScriptBeforeBodyClose() {
        String html = "<html><body><h1>Hi</h1></body></html>";
        String out = LivePreviewServer.injectReloadScript(html, 7, "tok");
        int script = out.indexOf("__editora_livereload");
        int bodyClose = out.indexOf("</body>");
        assertTrue(script >= 0, "script injected");
        assertTrue(script < bodyClose, "script sits before </body>");
        assertTrue(out.contains("var v=7"), "current version baked into the script");
        assertTrue(out.contains("fetch('/tok/__editora_livereload?v='"), "the poll goes through the token prefix");
        assertTrue(out.startsWith("<html><body><h1>Hi</h1>"), "original markup preserved");
    }

    @Test
    void bodyTagMatchIsCaseInsensitive() {
        String out = LivePreviewServer.injectReloadScript("<BODY>x</BODY>", 1, "tok");
        assertTrue(out.indexOf("__editora_livereload") < out.indexOf("</BODY>"));
    }

    @Test
    void appendsScriptWhenNoBodyTag() {
        String out = LivePreviewServer.injectReloadScript("<h1>fragment</h1>", 3, "tok");
        assertTrue(out.startsWith("<h1>fragment</h1>"));
        assertTrue(out.contains("__editora_livereload"));
    }

    @Test
    void contentTypeByExtension() {
        assertTrue(LivePreviewServer.contentType("index.html").startsWith("text/html"));
        assertTrue(LivePreviewServer.contentType("style.CSS").startsWith("text/css"));
        assertTrue(LivePreviewServer.contentType("app.js").startsWith("text/javascript"));
        assertEquals("image/svg+xml", LivePreviewServer.contentType("logo.svg"));
        assertEquals("image/png", LivePreviewServer.contentType("pic.png"));
        assertEquals("font/woff2", LivePreviewServer.contentType("font.woff2"));
        assertEquals("application/octet-stream", LivePreviewServer.contentType("data.bin"));
        assertEquals("application/octet-stream", LivePreviewServer.contentType("noext"));
    }

    @Test
    void safeResolveKeepsPathsInsideRoot() {
        Path root = Path.of("/srv/site").toAbsolutePath().normalize();
        assertEquals(root.resolve("index.html"), LivePreviewServer.safeResolve(root, "index.html"));
        assertEquals(root.resolve("css/app.css"), LivePreviewServer.safeResolve(root, "css/app.css"));
        assertNotNull(LivePreviewServer.safeResolve(root, "sub/../index.html"));
    }

    @Test
    void safeResolveRejectsTraversalOutsideRoot() {
        Path root = Path.of("/srv/site").toAbsolutePath().normalize();
        assertNull(LivePreviewServer.safeResolve(root, "../secret.txt"));
        assertNull(LivePreviewServer.safeResolve(root, "../../etc/passwd"));
        assertNull(LivePreviewServer.safeResolve(root, "a/../../outside.txt"));
    }

    @Test
    void safeResolveRejectsASymlinkPointingOutsideRoot(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("site")).toRealPath();
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "top secret");
        try {
            Files.createSymbolicLink(root.resolve("escape.txt"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks not creatable on this platform");
        }
        // The lexical normalize()+startsWith check passes (escape.txt is under root), but the link's real
        // target is outside → must be rejected.
        assertNull(LivePreviewServer.safeResolve(root, "escape.txt"), "a symlink escaping the root is rejected");

        // A normal file and an in-root symlink are still allowed.
        Files.writeString(root.resolve("index.html"), "<html></html>");
        assertNotNull(LivePreviewServer.safeResolve(root, "index.html"));
        Path inner = Files.writeString(root.resolve("real.css"), "body{}");
        Files.createSymbolicLink(root.resolve("alias.css"), inner);
        assertNotNull(LivePreviewServer.safeResolve(root, "alias.css"), "an in-root symlink is fine");
    }

    // --- end-to-end: a real loopback server (no browser involved) ---------------------------------

    @Test
    void servesLiveTextAndAssetsAndGuardsTraversalAndLongPoll(@TempDir Path dir) throws Exception {
        Path html = dir.resolve("index.html");
        Files.writeString(html, "<html><body>placeholder</body></html>"); // on disk, but served from live text
        Files.writeString(dir.resolve("app.css"), "body{color:red}");
        Files.writeString(dir.getParent().resolve("secret.txt"), "TOP SECRET"); // outside the doc root

        LivePreviewServer server = new LivePreviewServer();
        try {
            server.start();
            server.setPreview(html, () -> "<html><body>LIVE EDIT</body></html>");
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String base = "http://127.0.0.1:" + server.port() + "/" + server.token();
            assertTrue(server.previewUrl().startsWith(base + "/"), "the preview URL carries the token prefix");

            // The previewed file: served from live text (not the disk content), with the reload script injected.
            HttpResponse<String> page = get(client, server.previewUrl());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("LIVE EDIT"), "served the live buffer text");
            assertTrue(!page.body().contains("placeholder"), "did not serve the stale disk content");
            assertTrue(page.body().contains("__editora_livereload"), "reload script injected");

            // A sibling asset: served from disk with the right content type.
            HttpResponse<String> css = get(client, base + "/app.css");
            assertEquals(200, css.statusCode());
            assertTrue(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
            assertEquals("body{color:red}", css.body());

            // Path traversal is rejected (403), so the sibling-of-root secret never leaks.
            HttpResponse<String> escape = get(client, base + "/../secret.txt");
            assertTrue(escape.statusCode() == 403 || escape.statusCode() == 404, "traversal blocked");

            // Long-poll: a request with an old version returns immediately with the current (bumped) version.
            server.bumpVersion();
            HttpResponse<String> lr = get(client, base + LivePreviewServer.LR_PATH + "?v=0");
            assertEquals(200, lr.statusCode());
            assertTrue(Long.parseLong(lr.body().trim()) > 0, "returns the advanced version");
        } finally {
            server.stop();
        }
    }

    // --- request gate: Host header, token prefix, doc-root refusal -------------------------------

    @Test
    void allowsOnlyLoopbackHostHeadersForThisPort() {
        assertTrue(LivePreviewServer.isAllowedHost("127.0.0.1:4000", 4000));
        assertTrue(LivePreviewServer.isAllowedHost("localhost:4000", 4000));
        assertTrue(LivePreviewServer.isAllowedHost("LOCALHOST:4000", 4000));
        assertTrue(LivePreviewServer.isAllowedHost("[::1]:4000", 4000));
        assertFalse(LivePreviewServer.isAllowedHost("evil.example:4000", 4000), "a DNS-rebound hostname");
        assertFalse(LivePreviewServer.isAllowedHost("127.0.0.1.evil.example:4000", 4000));
        assertFalse(LivePreviewServer.isAllowedHost("127.0.0.1:4001", 4000), "another port");
        assertFalse(LivePreviewServer.isAllowedHost("127.0.0.1", 4000), "no port");
        assertFalse(LivePreviewServer.isAllowedHost("user@127.0.0.1:4000", 4000));
        assertFalse(LivePreviewServer.isAllowedHost(null, 4000), "a missing Host header");
    }

    @Test
    void routeRequiresTheTokenAsTheFirstSegment() {
        assertEquals("/index.html", LivePreviewServer.route("/abc123/index.html", "abc123"));
        assertEquals("/css/app.css", LivePreviewServer.route("/abc123/css/app.css", "abc123"));
        assertEquals("/", LivePreviewServer.route("/abc123/", "abc123"));
        assertEquals("/", LivePreviewServer.route("/abc123", "abc123"));
        assertNull(LivePreviewServer.route("/index.html", "abc123"), "no prefix");
        assertNull(LivePreviewServer.route("/abc12/index.html", "abc123"), "a shorter guess");
        assertNull(LivePreviewServer.route("/abc1234/index.html", "abc123"), "a longer guess");
        assertNull(LivePreviewServer.route("/x/abc123/index.html", "abc123"), "the token must come first");
        assertNull(LivePreviewServer.route("/", "abc123"));
        assertNull(LivePreviewServer.route("//index.html", ""), "an empty token never matches");
    }

    @Test
    void refererMustBeAPageThisServerServedUnderTheToken() {
        assertTrue(LivePreviewServer.refererHasToken("http://127.0.0.1:4000/abc123/index.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.refererHasToken("http://127.0.0.1:4000/index.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.refererHasToken("http://evil.example:4000/abc123/x.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.refererHasToken("http://127.0.0.1:4001/abc123/x.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.refererHasToken("not a url", 4000, "abc123"));
        assertFalse(LivePreviewServer.refererHasToken(null, 4000, "abc123"));
    }

    @Test
    void homeDirectoryItsAncestorsAndFilesystemRootsAreUnsafeDocRoots(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home/alice"));
        Path site = Files.createDirectories(home.resolve("projects/site"));
        assertTrue(LivePreviewServer.isUnsafeDocRoot(home, home), "the home directory itself");
        assertTrue(LivePreviewServer.isUnsafeDocRoot(home.getParent(), home), "an ancestor of home (/home)");
        assertTrue(LivePreviewServer.isUnsafeDocRoot(tmp.getRoot(), home), "a filesystem root");
        assertTrue(LivePreviewServer.isUnsafeDocRoot(tmp.getRoot(), null), "a root is refused even with no home");
        assertTrue(LivePreviewServer.isUnsafeDocRoot(null, home));
        assertFalse(LivePreviewServer.isUnsafeDocRoot(site, home), "a project folder under home is fine");
        assertFalse(LivePreviewServer.isUnsafeDocRoot(tmp.resolve("elsewhere"), home), "an unrelated folder");
    }

    @Test
    void refusesToServeAFileSittingDirectlyInTheHomeDirectory() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        LivePreviewServer server = new LivePreviewServer();
        try {
            server.start();
            assertThrows(
                    java.io.IOException.class, () -> server.setPreview(home.resolve("index.html"), () -> "<html/>"));
            // …and nothing is served afterwards, token or not.
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            assertEquals(404, get(client, server.previewUrl()).statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void rejectsForeignHostHeadersAndRequestsWithoutTheToken(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<html><body>x</body></html>");
        Files.writeString(dir.resolve("app.css"), "body{color:red}");
        LivePreviewServer server = new LivePreviewServer();
        try {
            server.start();
            server.setPreview(html, () -> "<html><body>LIVE</body></html>");
            int port = server.port();
            String tokenPath = "/" + server.token();

            // DNS rebinding: the attacker's hostname resolves to 127.0.0.1, but the Host header gives it away —
            // even with the right token.
            assertEquals(403, rawStatus(port, tokenPath + "/app.css", "evil.example:" + port, null));
            assertEquals(403, rawStatus(port, tokenPath + "/app.css", null, null), "no Host header at all");
            // A local port scanner: right Host, no token → nothing under the doc root is reachable.
            assertEquals(404, rawStatus(port, "/app.css", "127.0.0.1:" + port, null));
            assertEquals(404, rawStatus(port, "/", "127.0.0.1:" + port, null));
            assertEquals(404, rawStatus(port, LivePreviewServer.LR_PATH + "?v=0", "127.0.0.1:" + port, null));
            assertEquals(404, rawStatus(port, "/deadbeef/app.css", "127.0.0.1:" + port, null), "a wrong token");
            // The real thing.
            assertEquals(200, rawStatus(port, tokenPath + "/app.css", "127.0.0.1:" + port, null));
            assertEquals(200, rawStatus(port, tokenPath + "/app.css", "localhost:" + port, null));
            // A root-absolute asset reference from a served page is bounced under the prefix; a foreign or
            // token-less Referer is not.
            String ownPage = "http://127.0.0.1:" + port + tokenPath + "/index.html";
            assertEquals(307, rawStatus(port, "/app.css", "127.0.0.1:" + port, ownPage));
            assertEquals(404, rawStatus(port, "/app.css", "127.0.0.1:" + port, "http://evil.example/x.html"));
            assertEquals(
                    404, rawStatus(port, "/app.css", "127.0.0.1:" + port, "http://127.0.0.1:" + port + "/index.html"));
        } finally {
            server.stop();
        }
    }

    /** Sends one raw HTTP/1.x GET (so the Host header is ours to choose) and returns the response status. */
    private static int rawStatus(int port, String target, String host, String referer) throws Exception {
        try (java.net.Socket socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            StringBuilder req =
                    new StringBuilder("GET " + target + (host == null ? " HTTP/1.0" : " HTTP/1.1") + "\r\n");
            if (host != null) {
                req.append("Host: ").append(host).append("\r\n");
            }
            if (referer != null) {
                req.append("Referer: ").append(referer).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(req.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII))
                    .readLine();
            assertNotNull(statusLine, "the server answered");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
