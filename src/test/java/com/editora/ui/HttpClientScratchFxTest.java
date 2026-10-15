package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.scene.control.ComboBox;

import com.editora.config.HttpResponseHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Untitled {@code .http} buffers run like scratch files (C12), and a running request shows a ■ on its gutter
 * glyph that cancels it, with its resolved request line in the "Running …" status (C10). Local server only.
 */
@Tag("fx")
class HttpClientScratchFxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private HttpClientCoordinator coordinator;
    private Host host;
    private Ops ops;

    @TempDir
    Path project;

    @TempDir
    Path config;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/json", ex -> {
            hits.add(ex.getRequestURI().toString());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            slowStarted.countDown();
            try {
                releaseSlow.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        ops = new Ops(config);
        coordinator = new HttpClientCoordinator(host, ops);
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseSlow.countDown();
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** An untitled {@code .http} buffer with its run glyphs and response panel, as the window wires it. */
    private EditorBuffer untitled(String text) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setDisplayName("scratch.http");
            created.setContent(text);
            created.setHttpRunHandler(line -> coordinator.runRequest(created, line));
            created.setHttpCancelHandler(() -> coordinator.cancelRun(created));
            created.setHttpEnabled(true);
            created.settleRunScan();
            host.buffers.add(created);
            host.active = created;
            coordinator.ensureHttpPreview(created);
            return created;
        });
        buffers.add(b);
        return b;
    }

    private static void await(java.util.concurrent.Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!FxTestSupport.callOnFx(check)) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void anUntitledBufferRunsWithTheProjectsEnvironment() throws Exception {
        Files.writeString(project.resolve("http-client.env.json"), "{\"dev\":{\"host\":\"" + url("") + "\"}}");
        ops.projectRoot = project;
        EditorBuffer b = untitled("GET {{host}}/json?scratch\n");
        ComboBox<String> env = FxTestSupport.field(coordinator.panelForTest(b), "envCombo");
        assertEquals(List.of("", "dev"), FxTestSupport.callOnFx(() -> List.copyOf(env.getItems())));
        FxTestSupport.runOnFx(() -> env.setValue("dev"));
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        assertEquals(
                tr("status.http.running", "GET " + url("/json?scratch")),
                host.awaitStatus(s -> true),
                "the Running label has the variables filled in");
        assertEquals(
                tr("status.http.finished", "GET " + url("/json?scratch"), "200 OK"),
                host.awaitStatus(s -> !s.startsWith("Running")));
        assertEquals(List.of("/json?scratch"), hits);
    }

    @Test
    void whatNeedsTheFilesFolderIsNotSentAndSaysSaveFirst() throws Exception {
        EditorBuffer b = untitled("POST " + url("/json") + "\n\n< ./body.json\n\n###\nGET " + url("/json")
                + "?two\n\n>> out.json\n\n###\nGET " + url("/json") + "?three\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        String status = host.awaitStatus(s -> !s.startsWith("Running"));
        assertTrue(status.contains(tr("httppanel.saveFirstToUse", "< ./body.json")), status);
        assertEquals(List.of(), hits, "nothing was sent");

        host.statuses.clear();
        FxTestSupport.runOnFx(coordinator::runFile);
        assertEquals(tr("status.http.someFailed", 2, 3), host.awaitStatus(s -> !s.startsWith("Running")));
        assertEquals(List.of("/json?three"), hits, "the request that needs no folder still went out");
        HttpClientPanel p = coordinator.panelForTest(b);
        assertEquals(
                List.of(tr("httppanel.saveFirstToUse", "< ./body.json"), tr("httppanel.saveFirstToUse", ">> out.json")),
                FxTestSupport.callOnFx(() -> p.history().stream()
                        .limit(3)
                        .map(e -> e.result().error())
                        .filter(java.util.Objects::nonNull)
                        .toList()));
    }

    @Test
    void anUntitledBuffersHistoryMovesIntoTheStoreWhenItIsFirstSaved() throws Exception {
        EditorBuffer b = untitled("GET " + url("/json") + "\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        host.awaitStatus(s -> !s.startsWith("Running"));
        HttpClientPanel p = coordinator.panelForTest(b);
        assertEquals(1, FxTestSupport.callOnFx(() -> p.history().size()), "kept in memory while untitled");

        Path file = project.resolve("saved.http");
        Files.writeString(file, "GET " + url("/json") + "\n");
        FxTestSupport.runOnFx(() -> {
            b.setPath(file);
            coordinator.ensureHttpPreview(b); // what Save As does through ensurePreviewControls
        });
        await(() -> Files.exists(ops.store.fileFor(file)));
        assertEquals(1, ops.store.load(file).responses.size());
    }

    @Test
    void aRunningRequestsGlyphIsAStopThatCancelsIt() throws Exception {
        EditorBuffer b = untitled("### fast\nGET " + url("/json") + "\n\n### slow\nGET " + url("/slow") + "\n");
        int slowLine = 4;
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, slowLine));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(slowLine, FxTestSupport.callOnFx(b::httpRunningLine), "the slow request's ▶ is now a ■");
        assertEquals(
                tr("editmenu.cancelHttpRequest.tip"),
                FxTestSupport.callOnFx(
                        () -> FxTestSupport.call(b, "runGlyphTooltip", new Class<?>[] {int.class}, slowLine)));

        FxTestSupport.runOnFx(() -> FxTestSupport.call(b, "onRunGlyph", new Class<?>[] {int.class}, slowLine));
        assertEquals(tr("status.http.cancelled"), host.last, "a click on the ■ cancels the run");
        assertEquals(-1, FxTestSupport.callOnFx(b::httpRunningLine), "…and it is a ▶ again");
    }

    @Test
    void aSecretReadFromAnUntitledBufferIsTrustedAsTheProjectOrRefused() throws Exception {
        EditorBuffer b = untitled("GET " + url("/json") + "?k={{$processEnv.HOME}}\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        assertEquals(tr("status.http.secretsNeedFolder", "$processEnv.HOME"), host.last, "no project: not read");
        assertEquals(List.of(), hits);

        ops.projectRoot = project;
        ops.allowSecrets = true;
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        host.awaitStatus(s -> s.contains("200"));
        assertEquals(project.resolve("scratch.http"), ops.askedFor, "asked about the project folder");
    }

    private final class Ops implements HttpClientCoordinator.WindowOps {
        final HttpResponseHistory store;
        volatile Path projectRoot;
        volatile boolean allowSecrets;
        volatile Path askedFor;

        Ops(Path configDir) {
            store = new HttpResponseHistory(configDir);
        }

        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void updateRunGating() {}

        @Override
        public HttpResponseHistory historyStore() {
            return store;
        }

        @Override
        public Path projectRoot() {
            return projectRoot;
        }

        @Override
        public boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
            askedFor = file;
            return allowSecrets;
        }
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        volatile EditorBuffer active;
        volatile String last;

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

        String awaitStatus(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                String status = statuses.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(status, "no such status message arrived");
                if (wanted.test(status)) {
                    return status;
                }
            }
        }
    }
}
