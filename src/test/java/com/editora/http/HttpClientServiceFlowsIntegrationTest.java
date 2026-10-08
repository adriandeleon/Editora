package com.editora.http;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request flows of {@link HttpClientService} against a server on 127.0.0.1: a whole file run in order
 * with one cookie jar and captured responses, the Digest challenge round trip, the per-request directives,
 * multipart and file bodies, and the failures that must stop a request from being sent.
 */
@Tag("fx")
class HttpClientServiceFlowsIntegrationTest {

    private HttpServer server;
    private int port;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastCookie = new AtomicReference<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger digestHits = new AtomicInteger();
    private final CountDownLatch holdHeaders = new CountDownLatch(1);
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private HttpClientService svc;

    @BeforeAll
    static void bootFx() {
        // HttpClientService posts its result via Platform.runLater, so the toolkit must be up.
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another test booted it
        }
        javafx.application.Platform.setImplicitExit(false);
    }

    private static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/login", ex -> {
            seen.add("login");
            ex.getResponseHeaders().add("Set-Cookie", "sid=abc123; Path=/");
            ex.getResponseHeaders().add("Content-Type", "application/json");
            reply(ex, 200, "{\"token\":\"t0k3n\"}");
        });
        server.createContext("/whoami", ex -> {
            seen.add("whoami"
                    + (ex.getRequestURI().getRawQuery() == null
                            ? ""
                            : "?" + ex.getRequestURI().getRawQuery()));
            lastCookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            authorizations.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            reply(ex, 200, "you");
        });
        server.createContext("/echo", ex -> {
            seen.add(ex.getRequestMethod() + " echo");
            lastContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "ok");
        });
        server.createContext("/digest", ex -> {
            digestHits.incrementAndGet();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            authorizations.add(String.valueOf(auth));
            if (auth == null) {
                ex.getResponseHeaders()
                        .add("WWW-Authenticate", "Digest realm=\"editora\", nonce=\"n0nce\", qop=\"auth\"");
                reply(ex, 401, "");
            } else {
                reply(ex, 200, "welcome");
            }
        });
        server.createContext("/basic-only", ex -> {
            digestHits.incrementAndGet();
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"editora\"");
            reply(ex, 401, "");
        });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "/echo");
            reply(ex, 302, "");
        });
        server.createContext("/slow", ex -> {
            slowStarted.countDown();
            try {
                holdHeaders.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.createContext("/megabyte", ex -> {
            byte[] big = new byte[1024 * 1024 + 10];
            java.util.Arrays.fill(big, (byte) 'a');
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, big.length);
            ex.getResponseBody().write(big);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        svc = new HttpClientService();
    }

    @AfterEach
    void stopServer() {
        holdHeaders.countDown();
        svc.shutdown();
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private com.editora.http.HttpExchange run(HttpFile.Parsed request, Map<String, String> vars, Path baseDir)
            throws Exception {
        AtomicReference<com.editora.http.HttpExchange> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.run(request, vars, baseDir, ex -> {
            got.set(ex);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the request should have completed");
        return got.get();
    }

    private com.editora.http.HttpExchange run(String requestText, Path baseDir) throws Exception {
        return run(HttpFile.parseRequest(requestText), Map.of(), baseDir);
    }

    /** The requests of a whole file, carrying their names and directives. */
    private static List<HttpFile.Parsed> requests(String fileText) {
        return HttpFile.parse(fileText).stream().map(HttpFile::parseRequest).toList();
    }

    private List<com.editora.http.HttpExchange> runAll(String fileText, Map<String, String> vars) throws Exception {
        AtomicReference<List<com.editora.http.HttpExchange>> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        HttpClientService.Handle handle = svc.runAll(requests(fileText), vars, null, all -> {
            got.set(all);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the run should have completed");
        handle.delivered().get(30, TimeUnit.SECONDS);
        assertFalse(handle.isCancelled());
        return got.get();
    }

    @Test
    void aWholeFileRunsInOrderSharingCookiesAndEarlierResponses() throws Exception {
        List<com.editora.http.HttpExchange> all = runAll(
                "### login\nPOST " + url("/login") + "\n\n### who\nGET " + url("/whoami")
                        + "?t={{login.response.body.$.token}}&u={{user}}\n",
                Map.of("user", "ada"));
        assertEquals(2, all.size());
        assertEquals(200, all.get(0).result().status());
        assertEquals("you", all.get(1).result().body());
        assertEquals(List.of("login", "whoami?t=t0k3n&u=ada"), seen);
        assertEquals("sid=abc123", lastCookie.get(), "the second request sends the cookie the first one set");
        assertEquals("GET " + url("/whoami") + "?t=t0k3n&u=ada", all.get(1).label());
    }

    @Test
    void noCookieJarKeepsARequestOutOfTheSharedJar() throws Exception {
        List<com.editora.http.HttpExchange> all = runAll(
                "### login\nPOST " + url("/login") + "\n\n### who\n# @no-cookie-jar\nGET " + url("/whoami") + "\n",
                Map.of());
        assertEquals(200, all.get(1).result().status());
        assertNull(lastCookie.get());
    }

    @Test
    void anEmptyRunDeliversAnEmptyList() throws Exception {
        assertEquals(List.of(), runAll("", Map.of()));
    }

    @Test
    void aDigestShorthandAnswersTheChallenge() throws Exception {
        com.editora.http.HttpExchange ex =
                run("GET " + url("/digest") + "?a=1\nAuthorization: Digest ada s3cret\n", null);
        assertEquals(200, ex.result().status());
        assertEquals("welcome", ex.result().body());
        assertEquals(2, digestHits.get());
        // First anonymous — the shorthand itself is never sent — then the computed answer.
        assertEquals("null", authorizations.get(0));
        String answer = authorizations.get(1);
        assertTrue(answer.startsWith("Digest "), answer);
        assertTrue(answer.contains("username=\"ada\""), answer);
        assertTrue(answer.contains("nonce=\"n0nce\""), answer);
        assertTrue(answer.contains("uri=\"/digest?a=1\""), answer);
        assertFalse(answer.contains("s3cret"), "the password is hashed, never sent");
    }

    @Test
    void aDigestShorthandIsNotAnsweredToAnotherScheme() throws Exception {
        com.editora.http.HttpExchange ex =
                run("GET " + url("/basic-only") + "\nAuthorization: Digest ada s3cret\n", null);
        assertEquals(401, ex.result().status());
        assertEquals(1, digestHits.get(), "no second attempt: the server did not ask for Digest");
    }

    @Test
    void anOrdinaryAuthorizationHeaderIsSentAsWritten() throws Exception {
        run("GET " + url("/whoami") + "\nAuthorization: Bearer abc\n", null);
        assertEquals(List.of("Bearer abc"), authorizations);
    }

    @Test
    void redirectsAreFollowedUnlessTheRequestSaysNo() throws Exception {
        List<com.editora.http.HttpExchange> all = runAll(
                "### follow\nGET " + url("/moved") + "\n\n### stay\n# @no-redirect\nGET " + url("/moved") + "\n",
                Map.of());
        assertEquals(200, all.get(0).result().status());
        assertEquals("ok", all.get(0).result().body());
        assertEquals(302, all.get(1).result().status());
        assertEquals(List.of("GET echo"), seen);
    }

    @Test
    void aRequestTimeoutDirectiveStopsWaitingForHeaders() throws Exception {
        List<com.editora.http.HttpExchange> all =
                runAll("# @timeout 1\n# @connection-timeout 5\nGET " + url("/slow") + "\n", Map.of());
        assertTrue(slowStarted.await(30, TimeUnit.SECONDS));
        HttpResult r = all.get(0).result();
        assertTrue(r.failed());
        assertTrue(r.error().contains("timed out"), r.error());
        assertTrue(r.error().endsWith("(" + url("/slow") + ")"), r.error());
    }

    @Test
    void aMultipartBodyGetsABoundaryWhenTheHeaderHasNone() throws Exception {
        com.editora.http.HttpExchange ex = run(
                HttpFile.parseRequest("POST " + url("/echo") + "\nContent-Type: multipart/form-data\n\n"
                        + "--x\nContent-Disposition: form-data; name=\"who\"\n\n{{name}}\n--x--\n"),
                Map.of("name", "ada"),
                null);
        assertEquals(200, ex.result().status());
        String type = lastContentType.get();
        assertNotNull(type);
        assertTrue(type.startsWith("multipart/form-data; boundary=EditoraBoundary"), type);
        assertTrue(ex.requestBody().startsWith("(multipart/form-data, "), ex.requestBody());
        // The header the exchange reports is the one that was sent.
        assertEquals(
                type,
                ex.headers().stream()
                        .filter(h -> h[0].equalsIgnoreCase("Content-Type"))
                        .findFirst()
                        .orElseThrow()[1]);
    }

    @Test
    void aMultipartFileOutsideTheRequestFolderStopsTheRequest(@TempDir Path dir) throws Exception {
        com.editora.http.HttpExchange ex = run(
                "POST " + url("/echo") + "\nContent-Type: multipart/form-data; boundary=x\n\n"
                        + "--x\nContent-Disposition: form-data; name=\"f\"; filename=\"p\"\n\n< ../../etc/passwd\n--x--\n",
                dir);
        assertTrue(ex.result().failed());
        assertTrue(
                ex.result().error().startsWith("request not sent: "),
                ex.result().error());
        assertTrue(
                ex.result().error().contains("outside the request folder"),
                ex.result().error());
        assertEquals(List.of(), seen, "nothing reached the server");
    }

    @Test
    void aSubstitutedBodyFileIsSentWithItsVariablesFilledIn(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("body.json"), "{\"who\":\"{{name}}\"}");
        com.editora.http.HttpExchange ex = run(
                HttpFile.parseRequest("POST " + url("/echo") + "\nContent-Type: application/json\n\n<@ ./body.json\n"),
                Map.of("name", "ada"),
                dir);
        assertEquals(200, ex.result().status());
        assertEquals("{\"who\":\"ada\"}", lastBody.get());
        assertEquals("{\"who\":\"ada\"}", ex.requestBody());
    }

    @Test
    void aBodyFileInAnotherEncodingIsDecodedWithIt(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("latin.txt"), "café {{x}}".getBytes(StandardCharsets.ISO_8859_1));
        run(HttpFile.parseRequest("POST " + url("/echo") + "\n\n<@latin1 ./latin.txt\n"), Map.of("x", "olé"), dir);
        assertEquals("café olé", lastBody.get());
    }

    @Test
    void aBodyFileCannotBeResolvedWithoutASavedRequestFile() throws Exception {
        com.editora.http.HttpExchange ex = run("POST " + url("/echo") + "\n\n< ./body.json\n", null);
        assertEquals(
                "request not sent: body file needs a saved request file to resolve against: ./body.json",
                ex.result().error());
        assertEquals(List.of(), seen);
    }

    @Test
    void aBodyFileOutsideTheFolderOrMissingStopsTheRequest(@TempDir Path dir) throws Exception {
        assertEquals(
                "request not sent: body file outside the request folder: ../secret.txt",
                run("POST " + url("/echo") + "\n\n< ../secret.txt\n", dir)
                        .result()
                        .error());
        assertEquals(
                "request not sent: body file not found: ./nope.json",
                run("POST " + url("/echo") + "\n\n< ./nope.json\n", dir)
                        .result()
                        .error());
        // An encoding the JVM does not know is also "cannot read this body".
        Files.writeString(dir.resolve("b.txt"), "x");
        assertEquals(
                "request not sent: body file not found: ./b.txt",
                run("POST " + url("/echo") + "\n\n<@klingon ./b.txt\n", dir)
                        .result()
                        .error());
        assertEquals(List.of(), seen);
    }

    @Test
    void aBodyGluedToTheHeadersIsNotSentAndSaysSo() throws Exception {
        com.editora.http.HttpExchange ex =
                run("POST " + url("/echo") + "\nContent-Type: application/json\n{\"a\": 1}\n", null);
        assertEquals(200, ex.result().status());
        assertEquals("", lastBody.get());
        assertTrue(
                ex.result().warnings().stream().anyMatch(w -> w.contains("separated from the headers by a blank line")),
                ex.result().warnings().toString());
    }

    @Test
    void aRefusedConnectionReportsTheUrl() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closed = s.getLocalPort();
        }
        String target = "http://127.0.0.1:" + closed + "/x";
        HttpResult r = run("GET " + target + "\n", null).result();
        assertTrue(r.failed());
        assertFalse(r.error().isBlank());
        assertTrue(r.error().endsWith("  (" + target + ")"), r.error());
    }

    @Test
    void aRequestWithoutAUrlFailsWithoutNamingOne() throws Exception {
        HttpResult r = run(
                        new HttpFile.Parsed(
                                "", "", List.of(), "", null, null, List.of(), HttpFile.Directives.NONE, null),
                        Map.of(),
                        null)
                .result();
        assertTrue(r.failed());
        assertFalse(r.error().isBlank());
        assertFalse(r.error().contains("("), r.error());
    }

    @Test
    void aMissingMethodMeansGet() throws Exception {
        com.editora.http.HttpExchange ex = run(
                new HttpFile.Parsed(
                        "", url("/echo"), List.of(), "", null, null, List.of(), HttpFile.Directives.NONE, null),
                Map.of(),
                null);
        assertEquals("GET", ex.method());
        assertEquals(List.of("GET echo"), seen);
    }

    @Test
    void autoEncodingCanBeTurnedOff() throws Exception {
        List<com.editora.http.HttpExchange> all = runAll(
                "GET " + url("/whoami") + "?q=a b\n\n###\n# @no-auto-encoding\nGET " + url("/whoami") + "?q=a%20b\n",
                Map.of());
        assertEquals(url("/whoami") + "?q=a%20b", all.get(0).url());
        assertEquals(url("/whoami") + "?q=a%20b", all.get(1).url());
        assertEquals(List.of("whoami?q=a%20b", "whoami?q=a%20b"), seen);
    }

    @Test
    void aCapOfAMegabyteOrMoreIsReportedInMegabytes() throws Exception {
        svc.shutdown();
        svc = new HttpClientService(1024 * 1024, Duration.ofMinutes(1));
        svc.setRedirectGuard(null); // back to "no guard": must not break a run
        HttpResult r = run("GET " + url("/megabyte") + "\n", null).result();
        assertTrue(r.truncated());
        assertEquals(1024 * 1024, r.rawBody().length);
        assertTrue(
                r.warnings().stream().anyMatch(w -> w.startsWith("response truncated at 1 MB")),
                r.warnings().toString());
    }

    @Test
    void cancellingARunStopsItBeforeTheNextRequest() throws Exception {
        AtomicReference<List<com.editora.http.HttpExchange>> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        HttpClientService.Handle handle = svc.runAll(
                requests("GET " + url("/slow") + "\n\n###\nGET " + url("/whoami") + "\n"), Map.of(), null, all -> {
                    got.set(all);
                    done.countDown();
                });
        assertTrue(slowStarted.await(30, TimeUnit.SECONDS));
        handle.cancel();
        handle.cancel(); // a second cancel is a no-op
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(handle.isCancelled());
        assertEquals(1, got.get().size(), "the request after the cancelled one is not started");
        assertEquals(HttpClientService.CANCELLED, got.get().get(0).result().error());
        assertEquals(List.of(), seen);
    }
}
