package com.editora.http;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import com.editora.i18n.Messages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link HttpClientService} sends and reads, against servers on 127.0.0.1: a run that throws still
 * finishes, compressed bodies are decoded, the protocol follows the request line, the origin form and a custom
 * {@code Host}, the body charset, Digest with SHA-256, the {@code @timeout} silence limit, redirect warnings,
 * streamed uploads, connection reuse, {@code @no-log}, and plain-language connection errors.
 */
@Tag("fx")
class HttpEngineIntegrationTest {

    private HttpServer server;
    private HttpServer other;
    private int port;
    private int otherPort;
    private HttpClientService svc;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    private final List<String> remotePorts = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeAll
    static void bootFx() {
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another test booted it
        }
        javafx.application.Platform.setImplicitExit(false);
        Messages.init("en");
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String type, byte[] body)
            throws java.io.IOException {
        if (type != null) {
            ex.getResponseHeaders().add("Content-Type", type);
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(s)));
    }

    @BeforeEach
    void startServers() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        other.setExecutor(pool);
        server.createContext("/dump", ex -> {
            StringBuilder sb = new StringBuilder(ex.getRequestMethod() + " " + ex.getRequestURI() + "\n");
            ex.getRequestHeaders()
                    .forEach((k, v) -> sb.append(k.toLowerCase(java.util.Locale.ROOT))
                            .append(": ")
                            .append(String.join(",", v))
                            .append('\n'));
            seen.add(sb.toString());
            lastBody.set(ex.getRequestBody().readAllBytes());
            remotePorts.add(String.valueOf(ex.getRemoteAddress().getPort()));
            reply(ex, 200, "text/plain", utf8(sb.toString()));
        });
        server.createContext("/gzip", ex -> {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                gz.write(utf8("{\"compressed\":\"gzip\"}"));
            }
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            reply(ex, 200, "application/json", bo.toByteArray());
        });
        server.createContext("/deflate", ex -> {
            boolean raw = ex.getRequestURI().getQuery() != null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (DeflaterOutputStream d =
                    new DeflaterOutputStream(bo, new Deflater(Deflater.DEFAULT_COMPRESSION, raw))) {
                d.write(utf8("deflated " + (raw ? "raw" : "zlib")));
            }
            ex.getResponseHeaders().add("Content-Encoding", "deflate");
            reply(ex, 200, "text/plain", bo.toByteArray());
        });
        server.createContext("/br", ex -> {
            ex.getResponseHeaders().add("Content-Encoding", "br");
            reply(ex, 200, "text/plain", new byte[] {0x0b, 0x02, (byte) 0x80});
        });
        server.createContext("/digestint", ex -> {
            byte[] received = ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"r\", nonce=\"n\", qop=\"auth-int\"");
                reply(ex, 401, null, new byte[0]);
                return;
            }
            Map<String, String> p = DigestAuth.parseChallenge(auth);
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                String ha1 = HexFormat.of().formatHex(md5.digest(utf8("ada:r:s3cret")));
                String bodyHash = HexFormat.of().formatHex(md5.digest(received));
                String ha2 = HexFormat.of().formatHex(md5.digest(utf8("POST:/digestint:" + bodyHash)));
                String expected = HexFormat.of()
                        .formatHex(md5.digest(
                                utf8(ha1 + ":n:" + p.get("nc") + ":" + p.get("cnonce") + ":auth-int:" + ha2)));
                boolean ok = "auth-int".equals(p.get("qop")) && expected.equals(p.get("response"));
                reply(ex, ok ? 200 : 401, "text/plain", utf8(ok ? "welcome " + received.length : "bad answer"));
            } catch (Exception e) {
                reply(ex, 500, "text/plain", utf8(e.toString()));
            }
        });
        server.createContext("/digest256", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                ex.getResponseHeaders()
                        .add("WWW-Authenticate", "Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=MD5");
                ex.getResponseHeaders()
                        .add("WWW-Authenticate", "Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=SHA-256");
                reply(ex, 401, null, new byte[0]);
                return;
            }
            Map<String, String> p = DigestAuth.parseChallenge(auth);
            try {
                String ha1 = sha256("ada:r:s3cret");
                String ha2 = sha256("GET:/digest256");
                String expected = sha256(ha1 + ":n:" + p.get("nc") + ":" + p.get("cnonce") + ":auth:" + ha2);
                boolean ok = "SHA-256".equals(p.get("algorithm")) && expected.equals(p.get("response"));
                reply(ex, ok ? 200 : 401, "text/plain", utf8(ok ? "welcome" : "bad answer"));
            } catch (Exception e) {
                reply(ex, 500, "text/plain", utf8(e.toString()));
            }
        });
        server.createContext("/stall", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, 0);
            OutputStream o = ex.getResponseBody();
            try {
                o.write(utf8("first"));
                o.flush();
                release.await(20, TimeUnit.SECONDS);
            } catch (Exception gone) {
                // the client hung up
            }
            ex.close();
        });
        server.createContext("/cross", ex -> {
            ex.getResponseHeaders().add("Location", "http://localhost:" + otherPort + "/landed");
            reply(ex, 302, null, new byte[0]);
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            reply(ex, 302, null, new byte[0]);
        });
        server.createContext("/hop", ex -> {
            ex.getResponseHeaders().add("Location", "/dump");
            reply(ex, 302, null, new byte[0]);
        });
        other.createContext("/landed", ex -> {
            seen.add("landed x-api-key=" + ex.getRequestHeaders().getFirst("X-Api-Key"));
            reply(ex, 200, "text/plain", utf8("landed"));
        });
        server.start();
        other.start();
        port = server.getAddress().getPort();
        otherPort = other.getAddress().getPort();
        svc = new HttpClientService();
    }

    @AfterEach
    void stopServers() {
        release.countDown();
        svc.shutdown();
        server.stop(0);
        other.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpExchange run(HttpClientService service, String fileText, Map<String, String> vars, Path dir)
            throws Exception {
        HttpFile.Parsed p = HttpFile.parseRequest(HttpFile.parse(fileText).get(0));
        AtomicReference<HttpExchange> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        service.run(p, vars, dir, null, SecretGate.UNCHECKED, ex -> {
            got.set(ex);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the request should have completed");
        return got.get();
    }

    private HttpExchange run(String fileText, Path dir) throws Exception {
        return run(svc, fileText, Map.of(), dir);
    }

    private List<HttpExchange> runAll(String fileText, Path dir) throws Exception {
        AtomicReference<List<HttpExchange>> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.runAll(
                HttpFile.parse(fileText).stream().map(HttpFile::parseRequest).toList(),
                Map.of(),
                dir,
                null,
                SecretGate.UNCHECKED,
                null,
                all -> {
                    got.set(all);
                    done.countDown();
                });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the run should have completed");
        return got.get();
    }

    // --- B3: a run always finishes ---------------------------------------------------------------------------

    @Test
    void aRunThatBreaksStillReportsWhatDidNotResolve(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("out.txt"), "previous");
        svc.setRedirectGuard(target -> {
            throw new AssertionError("guard exploded");
        });
        List<HttpExchange> all =
                runAll("### first\nGET " + url("/dump") + "?n=1\nX-Missing: {{missing}}\n\n>>! out.txt\n", dir);
        HttpResult r = all.get(0).result();
        assertTrue(r.failed(), "the request failed unexpectedly");
        assertTrue(
                r.warnings().stream().anyMatch(w -> w.contains("{{missing}}")),
                "the unresolved variable is still reported: " + r.warnings());
    }

    @Test
    void anErrorThrownDuringARunIsReportedAndRunAllCarriesOn(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("out.txt"), "previous");
        svc.setRedirectGuard(target -> {
            throw new AssertionError("guard exploded"); // an Error, like an OutOfMemoryError while reading
        });
        List<HttpExchange> all = runAll(
                "### first\nGET " + url("/dump") + "?n=1\n\n>>! out.txt\n\n### second\nGET " + url("/dump") + "?n=2\n",
                dir);
        assertEquals(2, all.size(), "the second request still ran");
        assertTrue(all.get(0).result().failed());
        assertTrue(
                all.get(0).result().error().contains("guard exploded"),
                all.get(0).result().error());
        assertEquals(200, all.get(1).result().status());
        assertEquals("previous", Files.readString(dir.resolve("out.txt")));
    }

    // --- B5: compressed responses ---------------------------------------------------------------------------

    @Test
    void gzipAndDeflateBodiesAreDecodedAndSavedDecoded(@TempDir Path dir) throws Exception {
        HttpExchange gz = run("GET " + url("/gzip") + "\nAccept-Encoding: gzip, deflate, br\n\n>> body.json\n", dir);
        assertEquals("{\"compressed\":\"gzip\"}", gz.result().body());
        assertTrue(gz.result().warnings().isEmpty(), gz.result().warnings().toString());
        assertEquals(
                "{\"compressed\":\"gzip\"}", Files.readString(dir.resolve("body.json")), ">> keeps the decoded body");
        assertEquals(
                "deflated zlib",
                run("GET " + url("/deflate") + "\n", dir).result().body());
        assertEquals(
                "deflated raw",
                run("GET " + url("/deflate") + "?raw\n", dir).result().body());
    }

    @Test
    void anEncodingEditoraCannotDecodeIsSaidNotHidden() throws Exception {
        HttpExchange br = run("GET " + url("/br") + "\n", null);
        assertEquals(200, br.result().status());
        assertArrayEquals(new byte[] {0x0b, 0x02, (byte) 0x80}, br.result().rawBody());
        assertTrue(
                br.result().warnings().stream().anyMatch(w -> w.contains("br")),
                br.result().warnings().toString());
    }

    // --- B6: protocol version --------------------------------------------------------------------------------

    @Test
    void plainHttpIsSentAsHttp11WithoutAnH2cUpgrade() throws Exception {
        HttpExchange ex = run("GET " + url("/dump") + " HTTP/1.1\n", null);
        String saw = seen.get(0);
        assertFalse(saw.contains("upgrade: h2c"), saw);
        assertFalse(saw.contains("http2-settings"), saw);
        assertEquals("HTTP/1.1", ex.result().protocol());
        assertTrue(HttpResponseFormat.render(ex.result()).startsWith("HTTP/1.1 200"));
    }

    // --- B7: Host -------------------------------------------------------------------------------------------

    @Test
    void theOriginFormTakesItsHostFromTheHostHeader() throws Exception {
        HttpExchange ex = run("GET /dump?x=1\nHost: 127.0.0.1:" + port + "\n", null);
        assertEquals(200, ex.result().status(), String.valueOf(ex.result().error()));
        assertEquals(url("/dump?x=1"), ex.url());
        assertTrue(ex.result().warnings().isEmpty(), ex.result().warnings().toString());
    }

    @Test
    void aDifferentHostHeaderIsSentAsWritten() throws Exception {
        HttpExchange ex = run("GET " + url("/dump") + "\nHost: api.example.test\n", null);
        assertEquals(200, ex.result().status());
        assertTrue(seen.get(0).contains("host: api.example.test"), seen.get(0));
        assertTrue(ex.result().warnings().isEmpty(), ex.result().warnings().toString());
    }

    // --- B8: body charset -----------------------------------------------------------------------------------

    @Test
    void theBodyIsEncodedWithTheDeclaredCharset() throws Exception {
        run("POST " + url("/dump") + "\nContent-Type: text/plain; charset=ISO-8859-1\n\ncafé\n", null);
        assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xe9}, lastBody.get());
        HttpExchange utf = run("POST " + url("/dump") + "\nContent-Type: text/plain\n\ncafé\n", null);
        assertArrayEquals("café".getBytes(StandardCharsets.UTF_8), lastBody.get(), "no charset: UTF-8");
        assertTrue(utf.result().warnings().isEmpty());
        HttpExchange lossy =
                run("POST " + url("/dump") + "\nContent-Type: text/plain; charset=ISO-8859-1\n\n€\n", null);
        assertTrue(
                lossy.result().warnings().stream().anyMatch(w -> w.contains("ISO-8859-1")),
                lossy.result().warnings().toString());
    }

    // --- B9: variables in redirect paths --------------------------------------------------------------------

    @Test
    void aRedirectPathFillsInItsVariables(@TempDir Path dir) throws Exception {
        HttpExchange ex = run(svc, "GET " + url("/dump") + "\n\n>> {{out}}/r.txt\n", Map.of("out", "results"), dir);
        assertTrue(Files.exists(dir.resolve("results/r.txt")));
        assertFalse(Files.exists(dir.resolve("{{out}}")));
        assertEquals(List.of("response saved to results/r.txt"), ex.result().written());
    }

    // --- B10: Digest SHA-256 --------------------------------------------------------------------------------

    @Test
    void aSha256DigestChallengeIsAnsweredWithSha256() throws Exception {
        HttpExchange ex = run("GET " + url("/digest256") + "\nAuthorization: Digest ada s3cret\n", null);
        assertEquals(200, ex.result().status(), ex.result().body());
        assertEquals("welcome", ex.result().body());
        assertEquals("ada", ex.details().digestUser());
    }

    // --- B13: @timeout covers a silent body -----------------------------------------------------------------

    @Test
    void timeoutAlsoLimitsTheSilenceBetweenBodyReads() throws Exception {
        // Stopped by the 1 s silence limit, not the 20 s stall: the body is cut ("first" only, marked partial) and
        // the warning names @timeout — a stall that ran to its end would give the whole body and no warning.
        HttpExchange ex = run("# @timeout 1\nGET " + url("/stall") + "\n", null);
        assertFalse(ex.result().failed());
        assertEquals("first", ex.result().body(), "what arrived is kept");
        assertTrue(ex.result().truncated(), "an incomplete body is marked partial");
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("@timeout")),
                ex.result().warnings().toString());
    }

    // --- B17: redirects -------------------------------------------------------------------------------------

    @Test
    void aRedirectToAnotherHostNamesTheHeadersThatWentAlong() throws Exception {
        HttpExchange ex = run("GET " + url("/cross") + "\nX-Api-Key: k\nAccept: */*\n", null);
        assertEquals("landed", ex.result().body());
        assertEquals("http://localhost:" + otherPort + "/landed", ex.details().finalUrl());
        String w = String.join(" | ", ex.result().warnings());
        assertTrue(w.contains("localhost:" + otherPort) && w.contains("X-Api-Key"), w);
        assertFalse(w.contains("Accept"), w);
    }

    @Test
    void aRedirectLoopSaysWhyItStoppedAndASameHostHopIsQuiet() throws Exception {
        HttpExchange loop = run("GET " + url("/loop") + "\n", null);
        assertEquals(302, loop.result().status());
        assertTrue(
                loop.result().warnings().stream().anyMatch(w -> w.contains("redirects")),
                loop.result().warnings().toString());
        HttpExchange hop = run("GET " + url("/hop") + "\n", null);
        assertEquals(url("/dump"), hop.details().finalUrl());
        assertTrue(hop.result().warnings().isEmpty(), hop.result().warnings().toString());
    }

    // --- B21 / B11: streamed uploads and how they are recorded ----------------------------------------------

    @Test
    void aFileBodyIsStreamedAndRecordedForTheCurlExport(@TempDir Path dir) throws Exception {
        byte[] big = new byte[2 * 1024 * 1024 + 3];
        new java.util.Random(7).nextBytes(big);
        Files.write(dir.resolve("big.bin"), big);
        HttpExchange ex =
                run("POST " + url("/dump") + "\nContent-Type: application/octet-stream\n\n< ./big.bin\n", dir);
        assertArrayEquals(big, lastBody.get());
        assertEquals(
                dir.resolve("big.bin").toAbsolutePath().toString(), ex.details().bodyFile());
        assertTrue(ex.requestBody().contains("big.bin") && ex.requestBody().contains("MB"), ex.requestBody());
        String curl = CurlExport.toCurl(ex);
        assertTrue(curl.contains("--data-binary '@" + dir.resolve("big.bin").toAbsolutePath()), curl);
    }

    @Test
    void aMultipartRequestRecordsItsFieldsForTheCurlExport(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("a.png"), new byte[] {1, 2, 3});
        HttpExchange ex = run(
                svc,
                "POST " + url("/dump") + "\nContent-Type: multipart/form-data; boundary=XX\n\n--XX\n"
                        + "Content-Disposition: form-data; name=\"title\"\n\n{{t}}\n--XX\n"
                        + "Content-Disposition: form-data; name=\"img\"; filename=\"a.png\"\nContent-Type: image/png\n\n"
                        + "< ./a.png\n--XX--\n",
                Map.of("t", "hello"),
                dir);
        List<HttpExchange.FormPart> parts = ex.details().formParts();
        assertEquals(2, parts.size());
        assertEquals(new HttpExchange.FormPart("title", "hello", null, null, null), parts.get(0));
        assertEquals("img", parts.get(1).name());
        assertEquals(
                dir.resolve("a.png").toAbsolutePath().toString(), parts.get(1).file());
        assertEquals("a.png", parts.get(1).filename());
        assertEquals("image/png", parts.get(1).contentType());
    }

    private static final String FORM = "Content-Type: multipart/form-data; boundary=XX\n\n--XX\n"
            + "Content-Disposition: form-data; name=\"title\"\n\n{{t}}\n--XX\n"
            + "Content-Disposition: form-data; name=\"blob\"; filename=\"{{f}}\"\n"
            + "Content-Type: application/octet-stream\n\n< ./{{f}}\n--XX--\n";

    @Test
    void aMultipartFilePartIsStreamedWithAKnownContentLength(@TempDir Path dir) throws Exception {
        byte[] big = new byte[2 * 1024 * 1024 + 11];
        new java.util.Random(11).nextBytes(big);
        Files.write(dir.resolve("big.bin"), big);
        Map<String, String> vars = Map.of("t", "hello", "f", "big.bin");
        HttpExchange ex = run(svc, "POST " + url("/dump") + "\n" + FORM, vars, dir);

        assertEquals(200, ex.result().status(), String.valueOf(ex.result().error()));
        byte[] expected = HttpFixtures.form(
                Multipart.parse(HttpFile.parseRequest("POST /x\n" + FORM).body(), "XX"),
                "XX",
                dir,
                t -> HttpFixtures.substitute(t, vars, java.time.ZonedDateTime.now()));
        assertArrayEquals(expected, lastBody.get(), "the streamed body is byte for byte the form");
        String saw = seen.get(0);
        assertTrue(saw.contains("content-length: " + expected.length + "\n"), saw);
        assertFalse(saw.contains("transfer-encoding"), saw);
        assertEquals("(multipart/form-data, " + expected.length + " bytes)", ex.requestBody());
        HttpExchange.FormPart blob = ex.details().formParts().get(1);
        assertEquals(dir.resolve("big.bin").toAbsolutePath().toString(), blob.file(), "the substituted path");
        assertEquals("big.bin", blob.filename());
        assertTrue(
                CurlExport.toCurl(ex)
                        .contains("blob=@\"" + dir.resolve("big.bin").toAbsolutePath() + "\""),
                CurlExport.toCurl(ex));
    }

    @Test
    void anAuthIntDigestHashesTheStreamedMultipartBody(@TempDir Path dir) throws Exception {
        byte[] data = new byte[300 * 1024];
        new java.util.Random(5).nextBytes(data);
        Files.write(dir.resolve("d.bin"), data);
        HttpExchange ex = run(
                svc,
                "POST " + url("/digestint") + "\nAuthorization: Digest ada s3cret\n" + FORM,
                Map.of("t", "x", "f", "d.bin"),
                dir);
        assertEquals(200, ex.result().status(), ex.result().body());
        assertTrue(ex.result().body().startsWith("welcome "), ex.result().body());
    }

    @Test
    void anAuthIntDigestHashesAStreamedFileBody(@TempDir Path dir) throws Exception {
        byte[] data = new byte[700 * 1024];
        new java.util.Random(9).nextBytes(data);
        Files.write(dir.resolve("raw.bin"), data);
        HttpExchange ex = run(
                "POST " + url("/digestint")
                        + "\nAuthorization: Digest ada s3cret\nContent-Type: application/octet-stream\n\n"
                        + "< ./raw.bin\n",
                dir);
        assertEquals(200, ex.result().status(), ex.result().body());
        assertEquals("welcome " + data.length, ex.result().body());
    }

    @Test
    void aSubstitutedFilePartIsExportedAsTheTextThatWasSent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("meta.json"), "{\"id\":\"{{id}}\"}");
        HttpExchange ex = run(
                svc,
                "POST " + url("/dump") + "\nContent-Type: multipart/form-data; boundary=XX\n\n--XX\n"
                        + "Content-Disposition: form-data; name=\"meta\"\n\n<@ ./meta.json\n--XX--\n",
                Map.of("id", "42"),
                dir);
        assertEquals(
                new HttpExchange.FormPart("meta", "{\"id\":\"42\"}", null, null, null),
                ex.details().formParts().get(0));
        assertTrue(new String(lastBody.get(), StandardCharsets.UTF_8).contains("{\"id\":\"42\"}"));
    }

    // --- B22: one client per run ----------------------------------------------------------------------------

    @Test
    void theRequestsOfARunShareOneConnection() throws Exception {
        runAll(
                "GET " + url("/dump") + "?1\n\n###\nGET " + url("/dump") + "?2\n\n###\nGET " + url("/dump") + "?3\n",
                null);
        assertEquals(3, remotePorts.size());
        assertEquals(1, remotePorts.stream().distinct().count(), "kept alive: " + remotePorts);
    }

    // --- B23: @no-log ---------------------------------------------------------------------------------------

    @Test
    void aNoLogRequestIsMarkedAndItsLabelHidesQueryValues() throws Exception {
        HttpExchange ex = run("# @no-log\nGET " + url("/dump") + "?token=s3cret&x\n", null);
        assertTrue(ex.details().noLog());
        assertEquals("GET " + url("/dump") + "?token=…&x", ex.label());
        assertTrue(ex.url().contains("s3cret"), "the request itself is unchanged");
        assertFalse(run("GET " + url("/dump") + "\n", null).details().noLog());
    }

    // --- C11: connection errors -----------------------------------------------------------------------------

    @Test
    void aRefusedConnectionSaysSoInPlainWords() throws Exception {
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closed = s.getLocalPort();
        }
        HttpResult r = run("GET http://127.0.0.1:" + closed + "/x\n", null).result();
        assertTrue(
                r.error().startsWith("Connection refused — is a server listening on 127.0.0.1:" + closed + "?"),
                r.error());
        HttpResult scheme = run("GET ftp://127.0.0.1/x\n", null).result();
        assertTrue(scheme.error().startsWith("Only http:// and https:// URLs"), scheme.error());
    }

    @Test
    void aHeaderTimeoutNamesTheLimit() throws Exception {
        try (java.net.ServerSocket silent =
                new java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))) {
            HttpResult r = run("# @timeout 1\nGET http://127.0.0.1:" + silent.getLocalPort() + "/\n", null)
                    .result();
            assertTrue(r.error().startsWith("No response within 1 s"), r.error());
            assertTrue(r.error().contains("@timeout"), r.error());
        }
    }

    @Test
    void theBodyDeadlineStillAppliesWithoutTimeout() throws Exception {
        HttpClientService quick = new HttpClientService(1024 * 1024, Duration.ofMillis(800));
        try {
            HttpExchange ex = run(quick, "GET " + url("/stall") + "\n", Map.of(), null);
            assertEquals("first", ex.result().body());
            assertTrue(
                    ex.result().warnings().stream().anyMatch(w -> w.contains("still arriving")),
                    ex.result().warnings().toString());
            assertNull(ex.details().finalUrl());
        } finally {
            quick.shutdown();
        }
    }
}
