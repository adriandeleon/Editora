package com.editora.http;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: a header the JDK client would reject (a pasted value with a trailing newline) must not be
 * dropped in silence. Only {@code Authorization} was trimmed before, so a <em>custom</em> auth header
 * ({@code X-Api-Key}, common) carrying the same paste artifact went out missing and the user saw a puzzling
 * {@code 401}; a genuinely un-sendable header was dropped with no word at all. Verified against a real local
 * server that reports the header it actually received.
 */
@Tag("fx")
class HttpHeaderDropIntegrationTest {

    @BeforeAll
    static void bootFx() {
        // HttpClientService.run posts its result via Platform.runLater, so the toolkit must be up.
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another test booted it
        }
        javafx.application.Platform.setImplicitExit(false);
    }

    /** A server that records an auth header of the one request it serves, then replies 200. */
    private static HttpServer serverRecording(String headerName, AtomicReference<String> received) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.set(ex.getRequestHeaders().getFirst(headerName));
            byte[] body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server;
    }

    private static HttpExchange run(HttpClientService svc, String requestText, Map<String, String> vars, Path baseDir)
            throws Exception {
        HttpFile.Parsed parsed = HttpFile.parseRequest(requestText);
        AtomicReference<HttpExchange> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.run(parsed, vars, baseDir, ex -> {
            got.set(ex);
            done.countDown();
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "the request should have completed");
        return got.get();
    }

    @Test
    void aCustomAuthHeaderWithAPastedTrailingNewlineStillReachesTheServer(@TempDir Path dir) throws Exception {
        // Many APIs authenticate with a custom header (X-Api-Key, X-Auth-Token, apikey). Only Authorization
        // was trimmed before, so a custom header carrying a pasted trailing \r\n was dropped in silence and the
        // request went out unauthenticated — a mystery 401. It is now trimmed and sent, like Authorization.
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = serverRecording("X-Api-Key", received);
        HttpClientService svc = new HttpClientService();
        try {
            int port = server.getAddress().getPort();
            String req = "GET http://127.0.0.1:" + port + "/\nX-Api-Key: {{token}}\n\n";
            HttpExchange ex = run(svc, req, Map.of("token", "sk-secret-123\r\n"), dir);

            assertEquals(200, ex.result().status());
            assertEquals(
                    "sk-secret-123",
                    received.get(),
                    "the server must receive the custom auth header (trimmed), not an unauthenticated request");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnsendableHeaderIsSurfacedInTheReport(@TempDir Path dir) throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = serverRecording("X-Api-Key", received);
        HttpClientService svc = new HttpClientService();
        try {
            int port = server.getAddress().getPort();
            // an injection attempt in the value: can't be sent, must be reported
            String req = "GET http://127.0.0.1:" + port + "/\nAuthorization: Bearer {{token}}\n\n";
            HttpExchange ex = run(svc, req, Map.of("token", "a\r\nX-Injected: evil"), dir);

            assertTrue(
                    ex.result().warnings().stream().anyMatch(w -> w.contains("Authorization")),
                    "the dropped header must be surfaced: " + ex.result().warnings());
            String report = HttpResponseFormat.render(ex.result());
            assertTrue(report.startsWith("⚠"), "the warning shows atop the report: " + report);
        } finally {
            server.stop(0);
        }
    }
}
