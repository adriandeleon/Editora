package com.editora.http;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import com.editora.i18n.Messages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against real 127.0.0.1 servers: what the server receives and what the user is told, for the engine fixes of
 * the second HTTP Client review — a cancelled upload releases its file, a cancelled re-run keeps the chained
 * token, JDK failures read in plain words, and a hostile Content-Encoding is refused.
 */
@Tag("fx") // posts results through the FX toolkit, like the other engine integration tests
class HttpEngineRound2IntegrationTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private HttpClientService svc;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch uploadStarted = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);

    @BeforeAll
    static void boot() {
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another test booted it
        }
        javafx.application.Platform.setImplicitExit(false);
        Messages.init("en");
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/slow-upload", ex -> {
            uploadStarted.countDown();
            try {
                release.await(30, TimeUnit.SECONDS); // reads nothing until released: the client blocks mid-upload
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                ex.getRequestBody().readAllBytes();
                ex.sendResponseHeaders(204, -1);
            } catch (Exception gone) {
                // the client hung up
            }
            ex.close();
        });
        server.createContext("/login", ex -> {
            received.add(ex.getRequestURI().toString());
            try {
                if (received.stream().filter(s -> s.startsWith("/login")).count() > 1) {
                    release.await(30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"token\":\"FAKE-T1\"}".getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } catch (Exception gone) {
                // cancelled
            }
            ex.close();
        });
        server.createContext("/use", ex -> {
            received.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/layers", ex -> {
            byte[] body = "plain".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 5; i++) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                    gz.write(body);
                }
                body = bo.toByteArray();
            }
            ex.getResponseHeaders().add("Content-Encoding", "gzip, gzip, gzip, gzip, gzip");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        svc = new HttpClientService();
    }

    @AfterEach
    void stop() {
        release.countDown();
        svc.shutdown();
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static HttpFile.Parsed request(String text) {
        return HttpFile.parseRequest(HttpFile.parse(text).get(0));
    }

    private HttpExchange run(String text, CapturedResponses session) throws Exception {
        CompletableFuture<HttpExchange> f = new CompletableFuture<>();
        svc.run(request(text), Map.of(), dir, session, HttpClientService.SecretGate.UNCHECKED, f::complete);
        return f.get(30, TimeUnit.SECONDS);
    }

    /** The descriptors this process holds open on {@code file} (Linux only). */
    private static long openHandles(Path file) throws Exception {
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            return fds.filter(fd -> {
                        try {
                            return Files.readSymbolicLink(fd).equals(file.toRealPath());
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
        }
    }

    @Test
    void aCancelledUploadReleasesItsFileAtOnce() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "counts descriptors through /proc");
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[48 * 1024 * 1024]); // larger than the socket buffers: the send blocks
        Files.write(dir.resolve("part.bin"), new byte[48 * 1024 * 1024]);
        String[] bodies = {
            "POST " + url("/slow-upload") + "\nContent-Type: application/octet-stream\n\n< ./big.bin\n",
            "POST " + url("/slow-upload") + "\nContent-Type: multipart/form-data; boundary=BB\n\n--BB\n"
                    + "Content-Disposition: form-data; name=\"f\"; filename=\"p\"\n\n< ./part.bin\n--BB--\n"
        };
        for (String text : bodies) {
            Path file = text.contains("big.bin") ? big : dir.resolve("part.bin");
            uploadStarted = new CountDownLatch(1);
            release = new CountDownLatch(1);
            CompletableFuture<HttpExchange> f = new CompletableFuture<>();
            HttpClientService.Handle h = svc.run(
                    request(text),
                    Map.of(),
                    dir,
                    new CapturedResponses(),
                    HttpClientService.SecretGate.UNCHECKED,
                    f::complete);
            assertTrue(uploadStarted.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (openHandles(file) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20); // the client opens the file when it starts sending the body
            }
            assertEquals(1, openHandles(file), "the upload is reading the file");
            h.cancel();
            assertEquals(
                    HttpClientService.CANCELLED,
                    f.get(10, TimeUnit.SECONDS).result().error());
            assertEquals(0, openHandles(file), "no handle is left for the garbage collector to close");
            release.countDown();
        }
    }

    @Test
    void cancellingAReRunOfLoginKeepsTheTokenTheNextRequestSends() throws Exception {
        CapturedResponses session = new CapturedResponses();
        String login = "### login\nGET " + url("/login") + "\n";
        String use = "### use\nGET " + url("/use") + "?t={{login.response.body.token}}\n";
        assertEquals(200, run(login, session).result().status());
        CompletableFuture<HttpExchange> f = new CompletableFuture<>();
        HttpClientService.Handle again =
                svc.run(request(login), Map.of(), dir, session, HttpClientService.SecretGate.UNCHECKED, f::complete);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (received.stream().filter(s -> s.startsWith("/login")).count() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        again.cancel();
        assertEquals(
                HttpClientService.CANCELLED,
                f.get(10, TimeUnit.SECONDS).result().error());
        run(use, session);
        assertEquals("/use?t=FAKE-T1", received.get(received.size() - 1), "the server gets the earlier run's token");
    }

    @Test
    void jdkFailuresAreToldInPlainWords() throws Exception {
        try (ServerSocket banner = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
                ServerSocket hangUp = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread b = new Thread(() -> serve(banner, "SSH-2.0-OpenSSH_9.6\r\n"));
            Thread c = new Thread(() -> serve(hangUp, null));
            b.setDaemon(true);
            c.setDaemon(true);
            b.start();
            c.start();
            String notHttp = run("GET http://127.0.0.1:" + banner.getLocalPort() + "/x\n", new CapturedResponses())
                    .result()
                    .error();
            assertTrue(notHttp.startsWith("127.0.0.1:" + banner.getLocalPort() + " did not answer with HTTP"), notHttp);
            String closed = run("GET http://127.0.0.1:" + hangUp.getLocalPort() + "/x\n", new CapturedResponses())
                    .result()
                    .error();
            assertTrue(closed.contains("if the server expects https://, use https://"), closed);
        }
    }

    private static void serve(ServerSocket socket, String reply) {
        while (!socket.isClosed()) {
            try (Socket s = socket.accept()) {
                InputStream in = s.getInputStream();
                in.read(new byte[8192]);
                if (reply != null) {
                    OutputStream out = s.getOutputStream();
                    out.write(reply.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (Exception closed) {
                return;
            }
        }
    }

    @Test
    void aBodyWithMoreThanFourEncodingsIsShownAsItArrivedWithAWarning() throws Exception {
        HttpResult r =
                run("GET " + url("/layers") + "\n", new CapturedResponses()).result();
        assertEquals(200, r.status());
        assertTrue(
                r.warnings().stream().anyMatch(w -> w.contains("more than 4 content encodings")),
                r.warnings().toString());
        assertEquals(0x1f, r.rawBody()[0] & 0xff, "the gzip bytes are kept, not half-decoded");
    }
}
