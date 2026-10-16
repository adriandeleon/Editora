package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The secrets trust gate, judged by what a server on 127.0.0.1 receives: from an untrusted folder, no form of
 * reading the process environment or {@code .env} reaches the server without the prompt — the space form
 * {@code {{$env X}}}, extra braces, an {@code @var} or an environment value standing for it, a header, a {@code <@}
 * body file or multipart part — and the prompt names the reference as written and the host the run contacts,
 * never the value. {@code PATH} stands in for a secret: every platform has it.
 */
@Tag("fx")
class HttpSecretGateFxTest {

    private static final String SECRET = System.getenv("PATH");

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private Host host;
    private Ops ops;
    private HttpClientCoordinator coordinator;
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(SECRET != null && SECRET.length() > 3, "the test needs PATH to be set");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add(
                    ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " body=" + body);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        ops = new Ops();
        coordinator = new HttpClientCoordinator(host, ops);
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    private EditorBuffer httpBuffer(Path file, String text) throws Exception {
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            coordinator.ensureHttpPreview(created);
            return created;
        });
        HttpEnvCache.awaitIdleForTest(); // the env files are read off the FX thread
        buffers.add(b);
        return b;
    }

    /** Runs the request on {@code line} and waits for the run (and anything it sent) to finish. */
    private void runAndWait(EditorBuffer b, int line) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, line));
        awaitIdle(b);
    }

    private void awaitIdle(EditorBuffer b) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (FxTestSupport.callOnFx(() -> coordinator.isRunningForTest(b))) {
            assertTrue(System.nanoTime() < deadline, "the run never ended");
            Thread.sleep(20);
        }
        Thread.sleep(150); // anything sent has reached the server by now
        FxTestSupport.drainFx();
    }

    /** Untrusted and declined: nothing reached the server, the prompt named {@code read}, and no secret showed. */
    private void assertGated(Path dir, String text, int line, String read) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), text);
        runAndWait(b, line);
        assertEquals(List.of(), hits, "nothing may reach the server without the prompt");
        assertEquals(List.of(List.of(read)), ops.askedReads, "asked once, naming the reference as written");
        assertEquals(List.of(List.of("127.0.0.1:" + port)), ops.askedHosts, "the host the run contacts");
        assertNoSecretShown();
        assertTrue(host.last.contains(read), host.last);
        com.editora.http.HttpExchange shown =
                FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).getSelectedExchange());
        StringBuilder all = new StringBuilder(shown.label() + shown.url() + shown.requestBody());
        shown.headers().forEach(h -> all.append(h[0]).append(h[1]));
        assertFalse(all.toString().contains(SECRET), "the unsent request in History holds the secret: " + all);
        assertTrue(shown.result().failed(), "reported as not sent");
    }

    private void assertNoSecretShown() {
        for (String s : host.statuses) {
            assertFalse(s.contains(SECRET), "a status message showed the secret: " + s);
        }
        for (List<String> h : ops.askedHosts) {
            assertFalse(String.join(" ", h).contains(SECRET), "the prompt showed the secret");
        }
    }

    @Test
    void theSpaceFormOfEnvAsks(@TempDir Path dir) throws Exception {
        assertGated(dir, "GET " + url() + "/a?k={{$env PATH}}\n", 0, "$env PATH");
    }

    @Test
    void extraBracesAroundAReadAsk(@TempDir Path dir) throws Exception {
        assertGated(dir, "GET " + url() + "/b?k={{{$processEnv PATH}}}\n", 0, "$processEnv PATH");
    }

    @Test
    void stackedOpeningBracesAsk(@TempDir Path dir) throws Exception {
        assertGated(dir, "GET " + url() + "/c?k={{{{$processEnv PATH}}\n", 0, "$processEnv PATH");
    }

    @Test
    void aVariableStandingForTheReadAsks(@TempDir Path dir) throws Exception {
        assertGated(dir, "@k = {{$env PATH}}\nGET " + url() + "/d?k={{k}}\n", 1, "$env PATH");
    }

    @Test
    void aMultipartPartFileThatReadsASecretAsks(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("part.txt"), "secret={{$processEnv PATH}}");
        assertGated(
                dir,
                "POST " + url() + "/e\nContent-Type: multipart/form-data; boundary=b\n\n--b\n"
                        + "Content-Disposition: form-data; name=\"f\"\n\n<@ ./part.txt\n--b--\n",
                0,
                "$processEnv PATH");
    }

    @Test
    void anEnvironmentValueStandingForAnotherThatReadsASecretAsks(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("http-client.env.json"), "{\"dev\": {\"a\": \"{{$processEnv PATH}}\", \"b\": \"{{a}}\"}}");
        ops.saved = "dev"; // the window's old choice, which this file defines: selected
        assertGated(dir, "GET " + url() + "/f?k={{b}}\n", 0, "$processEnv PATH");
    }

    @Test
    void aHeaderThatReadsASecretAsks(@TempDir Path dir) throws Exception {
        assertGated(dir, "GET " + url() + "/g\nAuthorization: Bearer {{$env PATH}}\n", 0, "$env PATH");
    }

    @Test
    void aSubstitutedBodyFileThatReadsASecretAsks(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("body.txt"), "k={{$dotenv KEY}}");
        Files.writeString(dir.resolve(".env"), "KEY=fake-dotenv-value\n");
        assertGated(dir, "POST " + url() + "/h\nContent-Type: text/plain\n\n<@ ./body.txt\n", 0, "$dotenv KEY");
    }

    @Test
    void theHostComesFromTheVariablesAndTheHostHeaderAsTheRunUsesThem(@TempDir Path dir) throws Exception {
        assertGated(dir, "@base = " + url() + "\nGET {{base}}/i?k={{$env PATH}}\n", 1, "$env PATH");
        ops.askedReads.clear();
        ops.askedHosts.clear();
        assertGated(dir, "GET /j?k={{$env PATH}}\nHost: 127.0.0.1:" + port + "\n", 0, "$env PATH");
    }

    @Test
    void runAllAsksOnceAndStillSendsTheRequestsThatReadNoSecret(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "### one\nGET " + url() + "/one?k={{$env PATH}}\n\n### plain\nGET " + url() + "/plain\n\n### two\nGET "
                        + url() + "/two?k={{$processEnv.PATH}}\n");
        FxTestSupport.runOnFx(() -> {
            host.active = b;
            coordinator.runFile();
        });
        awaitIdle(b);
        assertEquals(List.of(List.of("$env PATH", "$processEnv.PATH")), ops.askedReads, "one prompt for the run");
        assertEquals(1, hits.size(), hits.toString());
        assertTrue(hits.get(0).startsWith("/plain"), hits.toString());
        assertNoSecretShown();
    }

    @Test
    void trustedTheValueIsSent(@TempDir Path dir) throws Exception {
        ops.trust = true;
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "@k = {{$env PATH}}\nGET " + url() + "/k?k={{k}}\n");
        runAndWait(b, 1);
        assertEquals(1, hits.size(), hits.toString());
        assertEquals(List.of(List.of("$env PATH")), ops.askedReads);
        assertTrue(
                java.net.URLDecoder.decode(hits.get(0), StandardCharsets.UTF_8).contains(SECRET), "the value went out");
    }

    @Test
    void aRequestThatReadsNoSecretIsNeverAsked(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "@k = {{$uuid}}\nGET " + url() + "/l?k={{k}}\n");
        runAndWait(b, 1);
        assertEquals(List.of(), ops.askedReads);
        assertEquals(1, hits.size());
    }

    @Test
    void pathologicalBracesDoNotHoldTheFxThread(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "POST " + url() + "/m\nContent-Type: text/plain\n\nhello {{" + " ".repeat(3000) + "\n");
        // The old gate ran a cubic regex over this on the FX thread: well past awaitIdle's 30 s bound. Now the dry
        // resolution runs on the worker, so the run ends (and its request arrives) — no wall-clock threshold needed.
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        awaitIdle(b);
        assertEquals(1, hits.size());
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        volatile EditorBuffer active;
        volatile String last = "";

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            last = message;
            statuses.add(message);
        }
    }

    private static final class Ops implements HttpClientCoordinator.WindowOps {
        volatile boolean trust;
        volatile String saved = "";
        final List<List<String>> askedReads = new CopyOnWriteArrayList<>();
        final List<List<String>> askedHosts = new CopyOnWriteArrayList<>();

        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void updateRunGating() {}

        @Override
        public String savedEnvironment() {
            return saved;
        }

        @Override
        public boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
            askedReads.add(List.copyOf(reads));
            askedHosts.add(List.copyOf(hosts));
            return trust;
        }
    }
}
