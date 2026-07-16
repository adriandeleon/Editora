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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LivePreviewServer}'s pure helpers (script injection, MIME map, traversal guard). */
class LivePreviewServerTest {

    @Test
    void injectsScriptBeforeBodyClose() {
        String html = "<html><body><h1>Hi</h1></body></html>";
        String out = LivePreviewServer.injectReloadScript(html, 7);
        int script = out.indexOf("__editora_livereload");
        int bodyClose = out.indexOf("</body>");
        assertTrue(script >= 0, "script injected");
        assertTrue(script < bodyClose, "script sits before </body>");
        assertTrue(out.contains("var v=7"), "current version baked into the script");
        assertTrue(out.startsWith("<html><body><h1>Hi</h1>"), "original markup preserved");
    }

    @Test
    void bodyTagMatchIsCaseInsensitive() {
        String out = LivePreviewServer.injectReloadScript("<BODY>x</BODY>", 1);
        assertTrue(out.indexOf("__editora_livereload") < out.indexOf("</BODY>"));
    }

    @Test
    void appendsScriptWhenNoBodyTag() {
        String out = LivePreviewServer.injectReloadScript("<h1>fragment</h1>", 3);
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
            String base = "http://127.0.0.1:" + server.port();

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

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
