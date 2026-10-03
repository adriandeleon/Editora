package com.editora.http;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against a real local server: request and response bodies are bytes all the way (a binary
 * upload/download is byte-identical), an XML body is actually sent, a missing body file stops the request
 * instead of sending it empty, an oversized response is cut with a visible warning, and a response that never
 * ends can be cancelled — or runs into the deadline — without holding up the next run.
 */
@Tag("fx")
class HttpClientBytesIntegrationTest {

    /** Every byte value, twice — not valid UTF-8, so any String round trip corrupts it. */
    private static final byte[] BINARY = allBytes();

    private HttpServer server;
    private int port;
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final CountDownLatch streamStarted = new CountDownLatch(1);
    private final CountDownLatch releaseStream = new CountDownLatch(1);

    private static byte[] allBytes() {
        byte[] b = new byte[512];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

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

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/echo", ex -> {
            hits.incrementAndGet();
            receivedBody.set(ex.getRequestBody().readAllBytes());
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.createContext("/binary", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, BINARY.length);
            ex.getResponseBody().write(BINARY);
            ex.close();
        });
        server.createContext("/big", ex -> {
            byte[] big = "0123456789".repeat(1000).getBytes(StandardCharsets.UTF_8); // 10 000 bytes
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, big.length);
            ex.getResponseBody().write(big);
            ex.close();
        });
        server.createContext(
                "/stream",
                ex -> { // a server-sent-event style response that never finishes
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0); // chunked
                    OutputStream out = ex.getResponseBody();
                    out.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    streamStarted.countDown();
                    try {
                        releaseStream.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ignore) {
                        Thread.currentThread().interrupt();
                    }
                    ex.close();
                });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        releaseStream.countDown();
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static HttpExchange await(CountDownLatch done, AtomicReference<HttpExchange> got) throws Exception {
        assertTrue(done.await(20, TimeUnit.SECONDS), "the request should have completed");
        return got.get();
    }

    private static HttpExchange run(HttpClientService svc, String requestText, Path baseDir) throws Exception {
        AtomicReference<HttpExchange> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.run(HttpFile.parseRequest(requestText), Map.of(), baseDir, ex -> {
            got.set(ex);
            done.countDown();
        });
        return await(done, got);
    }

    @Test
    void aBinaryDownloadKeepsItsBytesAndTheRedirectWritesThemVerbatim(@TempDir Path dir) throws Exception {
        HttpClientService svc = new HttpClientService();
        try {
            HttpExchange ex = run(svc, "GET " + url("/binary") + "\n\n>>! out/download.bin\n", dir);
            assertEquals(200, ex.result().status());
            assertArrayEquals(BINARY, ex.result().rawBody(), "the response is kept as bytes");
            assertEquals(BINARY.length, ex.result().sizeBytes());
            assertTrue(ex.result().binary());
            assertArrayEquals(
                    BINARY,
                    Files.readAllBytes(dir.resolve("out/download.bin")),
                    ">> must write the bytes received, not a re-encoded String");
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void aRawFileBodyIsUploadedByteForByte(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("payload.bin"), BINARY);
        HttpClientService svc = new HttpClientService();
        try {
            HttpExchange ex = run(
                    svc, "POST " + url("/echo") + "\nContent-Type: application/octet-stream\n\n< ./payload.bin\n", dir);
            assertEquals(200, ex.result().status());
            assertArrayEquals(BINARY, receivedBody.get(), "the server must receive the file's exact bytes");
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void anXmlBodyReachesTheServer(@TempDir Path dir) throws Exception {
        HttpClientService svc = new HttpClientService();
        try {
            String xml = "<?xml version=\"1.0\"?>\n<order id=\"7\"><item>café</item></order>";
            HttpExchange ex = run(svc, "POST " + url("/echo") + "\nContent-Type: text/xml\n\n" + xml + "\n", dir);
            assertEquals(200, ex.result().status());
            assertEquals(xml, new String(receivedBody.get(), StandardCharsets.UTF_8));
            assertTrue(ex.result().warnings().isEmpty(), ex.result().warnings().toString());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void aMissingOrEscapingBodyFileStopsTheRequestWithAWarning(@TempDir Path dir) throws Exception {
        HttpClientService svc = new HttpClientService();
        try {
            HttpExchange missing = run(svc, "POST " + url("/echo") + "\n\n< ./nope.json\n", dir);
            assertTrue(missing.result().failed(), "not sent");
            assertTrue(
                    missing.result().error().contains("nope.json"),
                    missing.result().error());
            assertEquals(
                    List.of("body file not found: ./nope.json"),
                    missing.result().warnings());

            HttpExchange outside = run(svc, "POST " + url("/echo") + "\n\n< ../secret.txt\n", dir);
            assertTrue(outside.result().failed());
            assertTrue(outside.result().warnings().get(0).contains("outside the request folder"));

            String form = "POST " + url("/echo") + "\nContent-Type: multipart/form-data; boundary=X\n\n"
                    + "--X\nContent-Disposition: form-data; name=\"f\"; filename=\"a.bin\"\n\n< ./a.bin\n--X--\n";
            HttpExchange part = run(svc, form, dir);
            assertTrue(part.result().failed());
            assertTrue(
                    part.result().warnings().get(0).contains("a.bin"),
                    part.result().warnings().toString());

            assertEquals(0, hits.get(), "none of the three requests may reach the server with an empty body");
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void anOversizedResponseIsCutAtTheCapWithAVisibleWarning(@TempDir Path dir) throws Exception {
        HttpClientService svc = new HttpClientService(4096, Duration.ofSeconds(30));
        try {
            HttpExchange ex = run(svc, "GET " + url("/big") + "\n", dir);
            HttpResult r = ex.result();
            assertEquals(200, r.status());
            assertTrue(r.truncated());
            assertEquals(4096, r.rawBody().length);
            assertTrue(
                    r.warnings().stream().anyMatch(w -> w.contains("truncated at 4096 bytes")),
                    r.warnings().toString());
            assertTrue(HttpResponseFormat.render(r).contains("truncated"));

            HttpClientService roomy = new HttpClientService(10_000, Duration.ofSeconds(30));
            try {
                HttpResult whole = run(roomy, "GET " + url("/big") + "\n", dir).result();
                assertFalse(whole.truncated(), "a body that exactly fits is not flagged");
                assertEquals(10_000, whole.rawBody().length);
            } finally {
                roomy.shutdown();
            }
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void cancellingAnEndlessResponseFreesTheClientForTheNextRun(@TempDir Path dir) throws Exception {
        HttpClientService svc = new HttpClientService();
        try {
            AtomicReference<HttpExchange> got = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            HttpClientService.Handle handle =
                    svc.run(HttpFile.parseRequest("GET " + url("/stream") + "\n"), Map.of(), dir, ex -> {
                        got.set(ex);
                        done.countDown();
                    });
            assertTrue(streamStarted.await(20, TimeUnit.SECONDS), "the stream should have started");

            // While the stream is still open, a second run completes: it is not queued behind the first.
            assertEquals(
                    200, run(svc, "GET " + url("/big") + "\n", dir).result().status());
            assertEquals(1, done.getCount(), "the endless request is still running");

            handle.cancel();
            HttpExchange cancelled = await(done, got);
            assertTrue(handle.isCancelled());
            assertTrue(cancelled.result().failed());
            assertEquals(HttpClientService.CANCELLED, cancelled.result().error());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void anEndlessResponseStopsAtTheDeadlineAndKeepsWhatArrived(@TempDir Path dir) throws Exception {
        HttpClientService svc =
                new HttpClientService(HttpClientService.DEFAULT_MAX_RESPONSE_BYTES, Duration.ofMillis(300));
        try {
            HttpResult r = run(svc, "GET " + url("/stream") + "\n", dir).result();
            assertFalse(r.failed(), "the partial response is shown, not discarded: " + r.error());
            assertEquals(200, r.status());
            assertEquals("data: first\n\n", r.body());
            assertTrue(r.truncated());
            assertTrue(
                    r.warnings().stream().anyMatch(w -> w.contains("still arriving")),
                    r.warnings().toString());
        } finally {
            svc.shutdown();
        }
    }
}
