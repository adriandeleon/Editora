package com.editora.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit and loopback tests for {@link LivePreviewServer} (script injection, MIME map, guards, sites, Range). */
class LivePreviewServerTest {

    private static final String SCRIPT = LivePreviewServer.reloadScript(7, "/tok/1", "gone");

    private LivePreviewServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        server = new LivePreviewServer();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        server.stop(); // every test's server is stopped, started or not
    }

    // --- script injection (E5, E10, E11) -------------------------------------------------------

    @Test
    void injectsScriptBeforeBodyClose() {
        String out = LivePreviewServer.injectReloadScript("<html><body><h1>Hi</h1></body></html>", SCRIPT);
        int script = out.indexOf("__editora_livereload");
        assertTrue(script >= 0 && script < out.indexOf("</body>"), "script sits before </body>");
        assertTrue(out.contains("var v=7"), "current version baked into the script");
        assertTrue(out.contains("u='/tok/1/__editora_livereload?v='"), "the poll goes through the site prefix");
        assertTrue(out.startsWith("<html><body><h1>Hi</h1>"), "original markup preserved");
    }

    @Test
    void bodyTagMatchIsCaseInsensitiveAndAppendsWithoutOne() {
        String upper = LivePreviewServer.injectReloadScript("<BODY>x</BODY >", SCRIPT);
        assertTrue(upper.indexOf("__editora_livereload") < upper.indexOf("</BODY >"));
        assertEquals("<h1>fragment</h1>" + SCRIPT, LivePreviewServer.injectReloadScript("<h1>fragment</h1>", SCRIPT));
        assertEquals(
                "<bodyx></bodyx>" + SCRIPT,
                LivePreviewServer.injectReloadScript("<bodyx></bodyx>", SCRIPT),
                "</bodyx> is not </body>");
    }

    @Test
    void turkishCapitalDottedIDoesNotShiftTheSplicePoint() {
        String html = "<html><body><p>İstanbul İzmir " + "İ".repeat(25) + "</p></body></html>";
        String out = LivePreviewServer.injectReloadScript(html, SCRIPT); // threw / spliced into "</body" before
        assertEquals(html.replace("</body>", SCRIPT + "</body>"), out);
        byte[] bytes = LivePreviewServer.injectReloadScript(html.getBytes(StandardCharsets.UTF_8), SCRIPT);
        assertEquals(out, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void theLastBodyCloseWinsOverOneInAScriptOrComment() {
        String html = "<body><script>var s=\"</body>\";</script><!-- </body> old footer --></body></html>";
        String out = LivePreviewServer.injectReloadScript(html, SCRIPT);
        assertTrue(out.startsWith("<body><script>var s=\"</body>\";</script><!-- </body> old footer -->" + SCRIPT));
        assertTrue(out.endsWith("</body></html>"));
    }

    @Test
    void byteInjectionLeavesTheEncodingAloneAndSkipsUtf16() {
        byte[] latin1 = "<html><body>café</body></html>".getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = LivePreviewServer.injectReloadScript(latin1, SCRIPT);
        assertEquals("<html><body>café" + SCRIPT + "</body></html>", new String(out, StandardCharsets.ISO_8859_1));
        byte[] utf16 = "﻿<body></body>".getBytes(StandardCharsets.UTF_16LE);
        assertSame(utf16, LivePreviewServer.injectReloadScript(utf16, SCRIPT), "a UTF-16 page is left untouched");
    }

    @Test
    void theClientIsAsciiOnlyAndBacksOffOnErrors() {
        String script = LivePreviewServer.reloadScript(3, "/t/2", "Der Vorschauserver wurde beendet <ü>");
        assertTrue(script.chars().allMatch(c -> c < 0x80), "pure ASCII, so it splices into any page encoding");
        assertTrue(script.contains("\\u00fc") && script.contains("\\u003c"), "the message is escaped");
        assertTrue(script.contains("r.status===204"), "only 204 means: poll again on the normal interval");
        assertTrue(script.contains("if(r.status!==200){fail();"), "any other status backs off (no hot loop)");
        assertTrue(script.contains("Math.pow(2,f)") && script.contains("f>=6"), "exponential, then gives up");
        assertTrue(script.contains("later(" + LivePreviewServer.POLL_INTERVAL_MS + ")"), "a short poll");
        assertTrue(script.contains("p[1]==='css'"), "a stylesheet-only change swaps the stylesheets");
    }

    // --- pure helpers ----------------------------------------------------------------------------

    @Test
    void contentTypeByExtensionIncludingMedia() {
        assertEquals("text/html", LivePreviewServer.contentType("index.html"));
        assertEquals("text/css", LivePreviewServer.contentType("style.CSS"));
        assertEquals("text/javascript", LivePreviewServer.contentType("app.js"));
        assertEquals("text/javascript", LivePreviewServer.contentType("app.mjs"));
        assertEquals("image/svg+xml", LivePreviewServer.contentType("logo.svg"));
        assertEquals("font/woff2", LivePreviewServer.contentType("font.woff2"));
        assertEquals("video/mp4", LivePreviewServer.contentType("demo.mp4"));
        assertEquals("video/webm", LivePreviewServer.contentType("demo.webm"));
        assertEquals("audio/mpeg", LivePreviewServer.contentType("song.mp3"));
        assertEquals("audio/ogg", LivePreviewServer.contentType("song.ogg"));
        assertEquals("audio/wav", LivePreviewServer.contentType("beep.wav"));
        assertEquals("application/pdf", LivePreviewServer.contentType("doc.pdf"));
        assertEquals("text/csv", LivePreviewServer.contentType("data.csv"));
        assertEquals("application/manifest+json", LivePreviewServer.contentType("site.webmanifest"));
        assertEquals("application/octet-stream", LivePreviewServer.contentType("data.bin"));
        assertEquals("application/octet-stream", LivePreviewServer.contentType("noext"));
    }

    @Test
    void parsesSingleByteRanges() {
        assertArrayEquals(new long[] {0, 3}, LivePreviewServer.parseRange("bytes=0-3", 10));
        assertArrayEquals(new long[] {5, 9}, LivePreviewServer.parseRange("bytes=5-", 10));
        assertArrayEquals(new long[] {7, 9}, LivePreviewServer.parseRange("bytes=-3", 10));
        assertArrayEquals(new long[] {0, 9}, LivePreviewServer.parseRange("bytes=-30", 10));
        assertArrayEquals(new long[] {8, 9}, LivePreviewServer.parseRange("bytes=8-100", 10));
        assertSame(LivePreviewServer.UNSATISFIABLE, LivePreviewServer.parseRange("bytes=10-", 10));
        assertNull(LivePreviewServer.parseRange("bytes=0-1,4-5", 10), "several ranges: send the whole file");
        assertNull(LivePreviewServer.parseRange("items=0-1", 10));
        assertNull(LivePreviewServer.parseRange("bytes=5-2", 10));
        assertNull(LivePreviewServer.parseRange("bytes=x-", 10));
    }

    @Test
    void hiddenAndSecretPathsAreRecognised() {
        for (String p : List.of(
                ".env",
                ".git/config",
                ".git/HEAD",
                "sub/.ssh/id_ed25519",
                ".npmrc",
                ".netrc",
                "a/.hidden/x.css",
                "certs/server.pem",
                "tls/site.key",
                "id_rsa",
                "keys/id_ed25519.pub",
                "keys/id_work",
                "a\\.git\\config")) {
            assertTrue(LivePreviewServer.isHiddenOrSecret(p), p);
        }
        for (String p :
                List.of("index.html", "css/site.css", ".well-known/security.txt", "img/id_card.png", "keynote.html")) {
            assertFalse(LivePreviewServer.isHiddenOrSecret(p), p);
        }
    }

    @Test
    void safeResolveKeepsPathsInsideRoot() {
        Path root = Path.of("/srv/site").toAbsolutePath().normalize();
        assertEquals(root.resolve("index.html"), LivePreviewServer.safeResolve(root, "index.html"));
        assertEquals(root.resolve("css/app.css"), LivePreviewServer.safeResolve(root, "css/app.css"));
        assertNotNull(LivePreviewServer.safeResolve(root, "sub/../index.html"));
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
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks not creatable on this platform");
        }
        assertNull(LivePreviewServer.safeResolve(root, "escape.txt"), "a symlink escaping the root is rejected");
        Files.writeString(root.resolve("index.html"), "<html></html>");
        assertNotNull(LivePreviewServer.safeResolve(root, "index.html"));
        Path inner = Files.writeString(root.resolve("real.css"), "body{}");
        Files.createSymbolicLink(root.resolve("alias.css"), inner);
        assertNotNull(LivePreviewServer.safeResolve(root, "alias.css"), "an in-root symlink is fine");
    }

    // --- end-to-end: a real loopback server (no browser involved) ---------------------------------

    @Test
    void servesLiveTextAndAssetsAndGuardsTraversalAndPolls(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("site"));
        Path html = dir.resolve("index.html");
        Files.writeString(html, "<html><body>placeholder</body></html>"); // on disk, but served from live text
        Files.writeString(dir.resolve("app.css"), "body{color:red}");
        Files.writeString(tmp.resolve("secret.txt"), "TOP SECRET"); // outside the site root
        Path liveKey = html.toAbsolutePath().normalize();
        server.setLiveText(p -> p.equals(liveKey) ? "<html><body>LIVE EDIT</body></html>" : null);
        server.start();
        String url = server.serve(dir, html);
        String base = url.substring(0, url.lastIndexOf('/'));
        assertTrue(base.matches("http://127\\.0\\.0\\.1:\\d+/[0-9a-f]{32}"), "the site's own port and token: " + url);

        HttpResponse<String> page = get(url);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("LIVE EDIT"), "served the live buffer text");
        assertFalse(page.body().contains("placeholder"), "did not serve the stale disk content");
        assertTrue(page.body().contains("__editora_livereload"), "reload script injected");
        assertEquals(page.body(), get(base + "/").body(), "/ is the site's index.html");

        HttpResponse<String> css = get(base + "/app.css");
        assertEquals(200, css.statusCode());
        assertEquals(
                "text/css; charset=utf-8",
                css.headers().firstValue("Content-Type").orElse(""));
        assertEquals("body{color:red}", css.body());

        HttpResponse<String> escape = get(base + "/../secret.txt");
        assertTrue(escape.statusCode() == 403 || escape.statusCode() == 404, "traversal blocked");

        // The poll is answered at once: 204 at the current version, 200 + the new version after a change.
        String poll = base + LivePreviewServer.LR_PATH + "?v=";
        long t0 = System.nanoTime();
        assertEquals(204, get(poll + "1").statusCode());
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "no held request");
        assertTrue(server.changed(html));
        HttpResponse<String> lr = get(poll + "1");
        assertEquals(200, lr.statusCode());
        assertEquals("2", lr.body(), "a full reload");
        assertTrue(server.changed(dir.resolve("app.css")));
        assertEquals("3 css", get(poll + "2").body(), "only a stylesheet changed since: swap, don't reload");
        assertEquals("3", get(poll + "1").body(), "a client older than the HTML change reloads");
        assertFalse(server.changed(tmp.resolve("elsewhere.css")), "a file of no site");
    }

    @Test
    void hidesDotfilesGitAndKeysButServesWellKnown(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<html><body>x</body></html>");
        Files.writeString(dir.resolve(".env"), "SECRET_KEY=hunter2");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), "[core]\n\trepositoryformatversion = 0\n");
        Files.writeString(dir.resolve("server.pem"), "-----BEGIN PRIVATE KEY-----");
        Files.writeString(dir.resolve("id_ed25519"), "-----BEGIN OPENSSH PRIVATE KEY-----");
        Files.createDirectories(dir.resolve(".well-known"));
        Files.writeString(dir.resolve(".well-known/security.txt"), "Contact: x");
        boolean linked = true;
        try {
            Files.createSymbolicLink(dir.resolve("meta"), dir.resolve(".git"));
        } catch (UnsupportedOperationException | IOException e) {
            linked = false;
        }
        server.start();
        String url = server.serve(dir, html);
        String base = url.substring(0, url.lastIndexOf('/'));
        for (String p :
                List.of("/.env", "/.git/config", "/.git/HEAD", "/server.pem", "/id_ed25519", "/%2egit/config")) {
            assertEquals(404, get(base + p).statusCode(), p);
        }
        if (linked) {
            assertEquals(404, get(base + "/meta/config").statusCode(), "a symlink into .git");
        }
        assertEquals(200, get(base + "/.well-known/security.txt").statusCode());
    }

    @Test
    void aPageInASubfolderLoadsTheSiteRootsAssets(@TempDir Path dir) throws Exception {
        Path site = Files.createDirectories(dir.resolve("site"));
        Files.createDirectories(site.resolve("css"));
        Files.writeString(site.resolve("css/site.css"), "body{}");
        Files.writeString(site.resolve("index.html"), "<html><body>home</body></html>");
        Path about = Files.createDirectories(site.resolve("pages")).resolve("about.html");
        Files.writeString(about, "<link rel=stylesheet href=\"../css/site.css\"><a href=\"../index.html\">");
        server.start();
        String url = server.serve(SiteRoots.siteRoot(about, null, dir.resolve("no-home")), about);
        assertTrue(url.endsWith("/pages/about.html"), url);
        URI page = URI.create(url);
        assertEquals(200, get(page.resolve("../css/site.css").toString()).statusCode(), "../css from pages/");
        assertEquals(200, get(page.resolve("../index.html").toString()).statusCode());
        // A root-absolute reference bounces under the page's own site token.
        int port = page.getPort();
        String token = page.getPath().split("/")[1];
        String location = rawHeader(port, "/css/site.css", "127.0.0.1:" + port, url, "Location");
        assertEquals("/" + token + "/css/site.css", location);
        // href="/" — the logo link — is the site's home page, not the page it was clicked on (E104).
        assertEquals("/" + token + "/", rawHeader(port, "/", "127.0.0.1:" + port, url, "Location"));
        assertTrue(get(page.resolve("/" + token + "/").toString()).body().contains("home"));
    }

    @Test
    void severalSitesEachKeepTheirOwnPrefixAndVersion(@TempDir Path dir) throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Path pageA = Files.writeString(a.resolve("index.html"), "<body>A</body>");
        Path pageB = Files.writeString(b.resolve("index.html"), "<body>B</body>");
        server.start();
        String urlA = server.serve(a, pageA);
        String urlB = server.serve(b, pageB);
        assertNotEquals(urlA, urlB);
        assertTrue(get(urlA).body().contains("A"), "the first tab still works after a second site is served");
        assertTrue(get(urlB).body().contains("B"));
        assertEquals(urlA, server.serve(a, pageA), "previewing the first again reuses its site");
        assertNotEquals(URI.create(urlA).getPort(), URI.create(urlB).getPort(), "each site has its own origin");
        server.changed(pageB);
        String pollA = urlA.replace("/index.html", LivePreviewServer.LR_PATH + "?v=1");
        String pollB = urlB.replace("/index.html", LivePreviewServer.LR_PATH + "?v=1");
        assertEquals(204, get(pollA).statusCode(), "an edit in site B does not reload site A");
        assertEquals(200, get(pollB).statusCode());
        assertTrue(server.serves(pageA) && server.serves(pageB) && !server.serves(dir.resolve("c.html")));
    }

    @Test
    void streamsFilesAndAnswersRanges(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        byte[] media = new byte[64 * 1024];
        for (int i = 0; i < media.length; i++) {
            media[i] = (byte) i;
        }
        Files.write(dir.resolve("clip.mp4"), media);
        Files.writeString(dir.resolve("big.html"), "<body>" + "x".repeat(4096) + "</body>");
        server.injectCap = 1024; // pretend 1 KB is the in-memory limit
        server.start();
        String base = server.serve(dir, html).replace("/index.html", "");

        HttpResponse<byte[]> full = getBytes(base + "/clip.mp4", null);
        assertEquals(200, full.statusCode());
        assertArrayEquals(media, full.body());
        assertEquals("video/mp4", full.headers().firstValue("Content-Type").orElse(""));
        assertEquals("bytes", full.headers().firstValue("Accept-Ranges").orElse(""));

        HttpResponse<byte[]> part = getBytes(base + "/clip.mp4", "bytes=256-259");
        assertEquals(206, part.statusCode());
        assertArrayEquals(new byte[] {0, 1, 2, 3}, part.body());
        assertEquals(
                "bytes 256-259/65536",
                part.headers().firstValue("Content-Range").orElse(""));
        assertEquals(416, getBytes(base + "/clip.mp4", "bytes=70000-").statusCode());

        String big = get(base + "/big.html").body();
        assertFalse(big.contains("__editora_livereload"), "HTML over the cap is streamed as is");
        assertEquals(4096 + 13, big.length());
    }

    @Test
    void aFileOverTwoGigabytesIsStreamed(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "a sparse 3 GB file would be zero-filled on NTFS");
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        Path huge = dir.resolve("huge.bin"); // sparse; Files.readAllBytes fails on anything over 2 GB
        long size = 3L * 1024 * 1024 * 1024;
        try (RandomAccessFile raf = new RandomAccessFile(huge.toFile(), "rw")) {
            raf.setLength(size);
            raf.seek(size - 4);
            raf.write(new byte[] {1, 2, 3, 4});
        }
        server.start();
        String base = server.serve(dir, html).replace("/index.html", "");
        HttpResponse<byte[]> tail = getBytes(base + "/huge.bin", "bytes=-4");
        assertEquals(206, tail.statusCode());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, tail.body());
        HttpResponse<Void> head = client.send(
                HttpRequest.newBuilder(URI.create(base + "/huge.bin"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, head.statusCode());
        assertEquals(
                Long.toString(size), head.headers().firstValue("Content-Length").orElse(""));
    }

    @Test
    void siblingPagesKeepTheirOwnEncoding(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        byte[] latin1 = "<meta charset=\"iso-8859-1\"><body>café</body>".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(dir.resolve("latin1.html"), latin1);
        Files.writeString(dir.resolve("utf8.html"), "<body>café</body>");
        server.start();
        String base = server.serve(dir, html).replace("/index.html", "");
        HttpResponse<byte[]> l = getBytes(base + "/latin1.html", null);
        assertEquals("text/html", l.headers().firstValue("Content-Type").orElse(""), "no charset forced on it");
        String body = new String(l.body(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("café") && body.contains("__editora_livereload"), "bytes kept, script added");
        HttpResponse<byte[]> u = getBytes(base + "/utf8.html", null);
        assertEquals(
                "text/html; charset=utf-8",
                u.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void onlyGetAndHeadWithHardenedHeaders(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        Files.writeString(dir.resolve("app.css"), "body{}");
        server.start();
        String css = server.serve(dir, html).replace("index.html", "app.css");
        for (String method : List.of("POST", "PUT", "DELETE", "OPTIONS", "TRACE")) {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(css))
                            .method(method, HttpRequest.BodyPublishers.ofString("x"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, r.statusCode(), method);
            assertEquals("GET, HEAD", r.headers().firstValue("Allow").orElse(""), method);
        }
        HttpResponse<String> ok = get(css);
        assertEquals(
                "nosniff", ok.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("same-origin", ok.headers().firstValue("Referrer-Policy").orElse(""));
        assertEquals("no-store", ok.headers().firstValue("Cache-Control").orElse(""));
        HttpResponse<Void> head = client.send(
                HttpRequest.newBuilder(URI.create(css))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, head.statusCode());
        assertEquals("6", head.headers().firstValue("Content-Length").orElse(""));
    }

    @Test
    void idleConnectionsDoNotStallPageLoads(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        server.start();
        String url = server.serve(dir, html);
        int port = URI.create(url).getPort();
        List<Socket> idle = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) { // more than the old 16-thread pool; no token needed to do this
                Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
                s.getOutputStream()
                        .write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                idle.add(s);
            }
            long t0 = System.nanoTime();
            assertEquals(200, get(url).statusCode());
            assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "answered at once");
        } finally {
            for (Socket s : idle) {
                s.close();
            }
        }
    }

    @Test
    void aRestartGetsAFreshTokenAndForgetsItsSites(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        server.start();
        String first = server.serve(dir, html);
        server.stop();
        server.start();
        assertFalse(server.serves(html));
        String second = server.serve(dir, html);
        assertNotEquals(
                URI.create(first).getPath(),
                URI.create(second).getPath(),
                "a URL from the last run (argv, history) stops working");
        String stale = "http://127.0.0.1:" + URI.create(second).getPort()
                + URI.create(first).getPath();
        assertEquals(404, get(stale).statusCode());
        assertEquals(200, get(second).statusCode());
    }

    // --- request gate: Host header, token prefix, unsafe roots ------------------------------------

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
        assertEquals("/1/index.html", LivePreviewServer.route("/abc123/1/index.html", "abc123"));
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
    void refererMustBeAPageOfThisSiteUnderItsToken() {
        assertTrue(LivePreviewServer.isOwnPage("http://127.0.0.1:4000/abc123/index.html", 4000, "abc123"));
        assertTrue(LivePreviewServer.isOwnPage("http://localhost:4000/abc123/", 4000, "abc123"));
        assertFalse(LivePreviewServer.isOwnPage("http://127.0.0.1:4000/index.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.isOwnPage("http://127.0.0.1:4000/other/x.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.isOwnPage("http://evil.example:4000/abc123/x.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.isOwnPage("http://127.0.0.1:4001/abc123/x.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.isOwnPage("https://127.0.0.1:4000/abc123/x.html", 4000, "abc123"));
        assertFalse(LivePreviewServer.isOwnPage("not a url", 4000, "abc123"));
        assertFalse(LivePreviewServer.isOwnPage(null, 4000, "abc123"));
    }

    @Test
    void refusesToServeTheHomeDirectory() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        server.start();
        assertThrows(IOException.class, () -> server.serve(home, home.resolve("index.html")));
        assertFalse(server.serves(home.resolve("index.html")), "nothing is served afterwards");
        assertThrows(IOException.class, () -> server.serve(home.resolve("a"), home.resolve("b/index.html")));
    }

    @Test
    void rejectsForeignHostHeadersAndRequestsWithoutTheToken(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<html><body>x</body></html>");
        Files.writeString(dir.resolve("app.css"), "body{color:red}");
        server.start();
        URI url = URI.create(server.serve(dir, html));
        int port = url.getPort();
        String site = "/" + url.getPath().split("/")[1];
        String host = "127.0.0.1:" + port;

        // DNS rebinding: the attacker's hostname resolves to 127.0.0.1, but the Host header gives it away.
        assertEquals(403, rawStatus(port, site + "/app.css", "evil.example:" + port, null));
        assertEquals(403, rawStatus(port, site + "/app.css", null, null), "no Host header at all");
        // A local port scanner: right Host, no token → nothing is reachable.
        assertEquals(404, rawStatus(port, "/app.css", host, null));
        assertEquals(404, rawStatus(port, "/", host, null));
        assertEquals(404, rawStatus(port, LivePreviewServer.LR_PATH + "?v=0", host, null));
        assertEquals(404, rawStatus(port, "/deadbeef/app.css", host, null), "a wrong token");
        assertEquals(200, rawStatus(port, site + "/app.css", host, null));
        assertEquals(200, rawStatus(port, site + "/app.css", "localhost:" + port, null));
        String ownPage = "http://" + host + site + "/index.html";
        assertEquals(307, rawStatus(port, "/app.css", host, ownPage));
        assertEquals(404, rawStatus(port, "/app.css", host, "http://evil.example/x.html"));
        assertEquals(404, rawStatus(port, "/app.css", host, "http://" + host + "/index.html"));
    }

    // --- HTML review 2 (E101–E105, E110, E113, position 2) ----------------------------------------

    /** E102: a page of one site cannot read another site's files or unsaved text, whatever path it guesses. */
    @Test
    void eachSiteHasItsOwnOriginAndToken(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path loose = Files.createDirectories(dir.resolve("download"));
        Path index = Files.writeString(project.resolve("index.html"), "<body>project</body>");
        Files.writeString(project.resolve("app.js"), "on disk");
        Path page = Files.writeString(loose.resolve("page.html"), "<body>downloaded</body>");
        Path app = project.resolve("app.js").toAbsolutePath().normalize();
        server.setLiveText(p -> p.equals(app) ? "apiKey='sk-live-123'" : null);
        server.start();
        URI a = URI.create(server.serve(project, index));
        URI b = URI.create(server.serve(loose, page));
        String tokenA = a.getPath().split("/")[1];
        String tokenB = b.getPath().split("/")[1];
        assertNotEquals(a.getPort(), b.getPort(), "another port is another origin for the browser");
        assertNotEquals(tokenA, tokenB);
        assertTrue(tokenA.matches("[0-9a-f]{32}") && tokenB.matches("[0-9a-f]{32}"), "random, not a counter");
        String originB = "http://127.0.0.1:" + b.getPort();
        for (String guess : List.of("/" + tokenB + "/app.js", "/" + tokenB + "/1/app.js", "/" + tokenA + "/app.js")) {
            assertEquals(404, get(originB + guess).statusCode(), guess);
        }
        assertEquals("apiKey='sk-live-123'", get(a.resolve("app.js").toString()).body(), "its own site still reads it");
        assertEquals(
                "same-origin",
                get(a.toString())
                        .headers()
                        .firstValue("Cross-Origin-Resource-Policy")
                        .orElse(""));
    }

    /** E104: "/" and a folder URL serve that folder's index.html; a folder without the slash is redirected. */
    @Test
    void foldersServeTheirIndexAndGetTheirSlash(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<body>home</body>");
        Path blog = Files.createDirectories(dir.resolve("blog"));
        Files.writeString(blog.resolve("index.html"), "<body>blog home</body>");
        Path post = Files.writeString(blog.resolve("post.html"), "<body>post</body>");
        Files.createDirectories(dir.resolve("empty"));
        server.start();
        URI url = URI.create(server.serve(dir, post));
        String base = "http://127.0.0.1:" + url.getPort() + "/" + url.getPath().split("/")[1];
        assertTrue(get(base + "/").body().contains("home"), "/ is the site root's index.html, not the previewed page");
        assertFalse(get(base + "/").body().contains("post"));
        assertTrue(get(base + "/blog/").body().contains("blog home"));
        int port = url.getPort();
        String host = "127.0.0.1:" + port;
        String token = url.getPath().split("/")[1];
        assertEquals(301, rawStatus(port, "/" + token + "/blog?x=1", host, null));
        assertEquals("/" + token + "/blog/?x=1", rawHeader(port, "/" + token + "/blog?x=1", host, null, "Location"));
        assertEquals("/" + token + "/", rawHeader(port, "/" + token, host, null, "Location"));
        assertEquals(404, get(base + "/empty/").statusCode(), "no index, no listing");
    }

    /** E101 and position 2: encrypted files, credential stores, state files and databases are never served. */
    @Test
    void encryptedFilesAndCredentialStoresAre404(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        List<String> secret = List.of(
                "notes.txt.gpg",
                "page.html.asc",
                "cert.p12",
                "cert.PFX",
                "vault.kdbx",
                "terraform.tfstate",
                "terraform.tfstate.backup",
                "prod.tfvars",
                "app.sqlite",
                "app.sqlite3",
                "data.db",
                "credentials.json",
                "credentials-prod.json",
                "secrets.yml");
        for (String name : secret) {
            Files.writeString(dir.resolve(name), "secret");
        }
        Files.writeString(dir.resolve("data.json"), "{}");
        Path live = dir.resolve("notes.txt.gpg").toAbsolutePath().normalize();
        server.setLiveText(p -> p.equals(live) ? "DECRYPTED" : null);
        server.start();
        String base = server.serve(dir, html).replace("/index.html", "");
        for (String name : secret) {
            assertEquals(404, get(base + "/" + name).statusCode(), name);
        }
        assertEquals(200, get(base + "/data.json").statusCode());
        assertEquals(404, get(server.serve(dir, dir.resolve("page.html.asc"))).statusCode(), "not even as a page");
    }

    /** E113: a page previewed in a hidden folder loads its own assets there, but not that folder's dotfiles. */
    @Test
    void aPageInAHiddenFolderLoadsItsOwnAssets(@TempDir Path dir) throws Exception {
        Path hidden = Files.createDirectories(dir.resolve(".vitepress"));
        Path page = Files.writeString(hidden.resolve("page.html"), "<link rel=stylesheet href=h.css>");
        Files.writeString(hidden.resolve("h.css"), "body{}");
        Files.writeString(hidden.resolve(".env"), "SECRET=1");
        Files.createDirectories(dir.resolve(".other"));
        Files.writeString(dir.resolve(".other/x.css"), "body{}");
        server.start();
        String url = server.serve(dir, page);
        String folder = url.substring(0, url.lastIndexOf('/'));
        assertEquals(200, get(url).statusCode());
        assertEquals(200, get(folder + "/h.css").statusCode(), "the entry's own folder");
        assertEquals(404, get(folder + "/.env").statusCode());
        assertEquals(404, get(folder + "/../.other/x.css").statusCode(), "another hidden folder");
    }

    @Test
    void servableNamesAllowAnEntrysFolderButNeverGitOrSecrets() {
        List<String> entries = List.of(".github/docs/page.html", ".git/hooks/x.html");
        assertTrue(LivePreviewServer.isServable("index.html", List.of()));
        assertFalse(LivePreviewServer.isServable(".github/x.css", List.of()));
        assertTrue(LivePreviewServer.isServable(".github/docs/page.html", entries));
        assertTrue(LivePreviewServer.isServable(".github/docs/img/logo.png", entries));
        assertFalse(LivePreviewServer.isServable(".github/docs/.env", entries));
        assertFalse(LivePreviewServer.isServable(".github/docs/cert.p12", entries));
        assertFalse(LivePreviewServer.isServable(".git/hooks/x.html", entries), ".git is never served");
        assertFalse(LivePreviewServer.isServable(".git/hooks/pre-commit", entries));
        assertFalse(LivePreviewServer.isServable("page.html.gpg", List.of("page.html.gpg")), "not even as an entry");
        assertFalse(LivePreviewServer.isServable(".ssh/id_rsa", List.of(".ssh/page.html")));
        assertTrue(LivePreviewServer.isServable(".draft.html", List.of(".draft.html")), "a dotfile page at the root");
        assertTrue(
                LivePreviewServer.isServable(".a/.b/x.css", List.of(".a/p.html", ".a/.b/q.html")),
                "the closest entry folder that allows it wins");
    }

    /**
     * E105: at most {@link LivePreviewServer#MAX_BUFFERED} pages are built in memory at once. With every permit
     * taken, a page request waits instead of allocating, and it is answered once one is free.
     */
    @Test
    void inMemoryResponsesAreBounded(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body>" + "x".repeat(10_000) + "</body>");
        server.start();
        String url = server.serve(dir, html);
        java.util.concurrent.Semaphore permits = permits();
        permits.acquire(LivePreviewServer.MAX_BUFFERED);
        var pending =
                client.sendAsync(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        try {
            Thread.sleep(700); // well past the time an unblocked answer takes
            assertFalse(pending.isDone(), "no permit, no in-memory page");
        } finally {
            permits.release(LivePreviewServer.MAX_BUFFERED);
        }
        HttpResponse<String> r = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("__editora_livereload") && r.body().contains("x".repeat(10_000)));
        assertEquals(LivePreviewServer.MAX_BUFFERED, permits.availablePermits(), "released after the answer");
    }

    /**
     * E202: real clients that ask for a big page and never read it hold every permit (each is held while its body
     * is written). Another page then gets a 503 once the bounded wait is over — not a hang — while streamed files
     * still answer, and pages load again as soon as those clients go.
     */
    @Test
    void clientsThatStopReadingCannotStallEveryPage(@TempDir Path dir) throws Exception {
        byte[] body = new byte[12 * 1024 * 1024]; // well past any loopback socket buffer, under the 20 MB cap
        java.util.Arrays.fill(body, (byte) 'x');
        Files.write(dir.resolve("big.html"), body);
        Path page = Files.writeString(dir.resolve("index.html"), "<body>small</body>");
        Files.writeString(dir.resolve("app.js"), "let a = 1;");
        server.bufferedWaitMillis = 1500;
        server.start();
        String url = server.serve(dir, page);
        URI uri = URI.create(url);
        String bigPath = uri.getRawPath().replace("index.html", "big.html");
        java.util.concurrent.Semaphore permits = permits();
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < LivePreviewServer.MAX_BUFFERED; i++) {
                Socket s = new Socket();
                s.setReceiveBufferSize(4096);
                s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), uri.getPort()), 5000);
                OutputStream out = s.getOutputStream();
                out.write(("GET " + bigPath + " HTTP/1.1\r\nHost: 127.0.0.1:" + uri.getPort() + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                held.add(s); // never read
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            int stillTaken = 0;
            while (stillTaken < 10) { // all taken, and still taken half a second later: those writes are stuck
                stillTaken = permits.availablePermits() == 0 ? stillTaken + 1 : 0;
                assertTrue(System.nanoTime() < deadline, "the stalled clients hold every permit");
                Thread.sleep(50);
            }
            long t0 = System.nanoTime();
            HttpResponse<String> busy = get(url);
            long waited = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(503, busy.statusCode(), "a page that cannot be built in time is refused, not left hanging");
            assertEquals("1", busy.headers().firstValue("Retry-After").orElse(null));
            assertTrue(waited >= 1400 && waited < 5000, "answered after the bounded wait: " + waited + " ms");
            assertEquals(200, get(url.replace("index.html", "app.js")).statusCode(), "streamed files still answer");
        } finally {
            for (Socket s : held) {
                s.close();
            }
        }
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (permits.availablePermits() < LivePreviewServer.MAX_BUFFERED) {
            assertTrue(System.nanoTime() < deadline, "the permits come back once those clients are gone");
            Thread.sleep(20);
        }
        HttpResponse<String> again = get(url);
        assertEquals(200, again.statusCode());
        assertTrue(again.body().contains("small"));
    }

    /** E105: text other than HTML is streamed (never read whole), still labelled UTF-8 only when it is. */
    @Test
    void textIsStreamedAndKeepsItsCharsetLabel(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body></body>");
        Files.writeString(dir.resolve("data.json"), "{\"k\":\"é\"}");
        Files.write(dir.resolve("latin1.css"), "a{content:\"é\"}".getBytes(StandardCharsets.ISO_8859_1));
        server.start();
        String base = server.serve(dir, html).replace("/index.html", "");
        HttpResponse<byte[]> json = getBytes(base + "/data.json", null);
        assertEquals(
                "application/json; charset=utf-8",
                json.headers().firstValue("Content-Type").orElse(""));
        assertEquals("bytes", json.headers().firstValue("Accept-Ranges").orElse(""), "the streaming path");
        assertEquals(206, getBytes(base + "/data.json", "bytes=0-1").statusCode(), "so ranges work for text too");
        HttpResponse<byte[]> css = getBytes(base + "/latin1.css", null);
        assertEquals("text/css", css.headers().firstValue("Content-Type").orElse(""), "not forced to UTF-8");
        assertTrue(LivePreviewServer.isUtf8(dir.resolve("data.json")));
        assertFalse(LivePreviewServer.isUtf8(dir.resolve("latin1.css")));
        assertFalse(LivePreviewServer.isUtf8(dir.resolve("missing.txt")));
        // A multi-byte character split across the 64 KB read boundary is still UTF-8.
        Path edge = dir.resolve("edge.txt");
        Files.writeString(edge, "a".repeat(64 * 1024 - 1) + "é" + "b".repeat(10));
        assertTrue(LivePreviewServer.isUtf8(edge));
    }

    /** E105/E111: editor text over the live cap is not served; the file on disk is. */
    @Test
    void liveTextOverTheCapIsServedFromDisk(@TempDir Path dir) throws Exception {
        Path html = Files.writeString(dir.resolve("index.html"), "<body>disk</body>");
        String huge = "x".repeat(LivePreviewServer.MAX_LIVE_CHARS + 1);
        Path key = html.toAbsolutePath().normalize();
        server.setLiveText(p -> p.equals(key) ? huge : null);
        server.start();
        String body = get(server.serve(dir, html)).body();
        assertTrue(body.startsWith("<body>disk"), "the disk copy, with the script");
    }

    /** E110: the stylesheet swap leaves other origins' stylesheets (a CDN, Google Fonts) alone. */
    @Test
    void theStylesheetSwapOnlyTouchesOwnOriginLinks() {
        String script = LivePreviewServer.reloadScript(1, "/t", "gone");
        assertTrue(script.contains("new URL(h,location.href).origin!==location.origin"), script);
        assertTrue(script.chars().allMatch(c -> c < 0x80));
    }

    /** More than {@link LivePreviewServer#MAX_SITES} sites: the least recently served one is stopped. */
    @Test
    void theLeastRecentlyServedSiteIsStoppedPastTheLimit(@TempDir Path dir) throws Exception {
        server.start();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i <= LivePreviewServer.MAX_SITES; i++) {
            Path site = Files.createDirectories(dir.resolve("s" + i));
            Files.writeString(site.resolve("index.html"), "<body>site " + i + "</body>");
        }
        for (int i = 0; i < LivePreviewServer.MAX_SITES; i++) {
            urls.add(server.serve(dir.resolve("s" + i), dir.resolve("s" + i + "/index.html")));
        }
        server.serve(dir.resolve("s0"), dir.resolve("s0/index.html")); // s1 is now the least recently served
        Path last = dir.resolve("s" + LivePreviewServer.MAX_SITES);
        server.serve(last, last.resolve("index.html"));
        assertTrue(server.serves(dir.resolve("s0/index.html")), "served recently: kept");
        assertFalse(server.serves(dir.resolve("s1/index.html")), "the least recent one is gone");
        assertTrue(get(urls.get(0)).body().contains("site 0"));
        assertThrows(IOException.class, () -> get(urls.get(1)), "its listener is closed");
    }

    private java.util.concurrent.Semaphore permits() throws Exception {
        java.lang.reflect.Field f = LivePreviewServer.class.getDeclaredField("buffered");
        f.setAccessible(true);
        return (java.util.concurrent.Semaphore) f.get(server);
    }

    /** Sends one raw HTTP/1.x GET (so the Host header is ours to choose) and returns the response status. */
    private static int rawStatus(int port, String target, String host, String referer) throws Exception {
        return Integer.parseInt(raw(port, target, host, referer).get(0).split(" ")[1]);
    }

    private static String rawHeader(int port, String target, String host, String referer, String name)
            throws Exception {
        for (String line : raw(port, target, host, referer)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                return line.substring(colon + 1).strip();
            }
        }
        return null;
    }

    private static List<String> raw(int port, String target, String host, String referer) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
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
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            List<String> lines = new ArrayList<>();
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                lines.add(line);
            }
            assertFalse(lines.isEmpty(), "the server answered");
            return lines;
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String url, String range) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (range != null) {
            b.header("Range", range);
        }
        return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
