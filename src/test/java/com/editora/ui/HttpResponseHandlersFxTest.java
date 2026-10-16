package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;

import com.editora.config.HttpResponseHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
import com.editora.http.HttpTests;
import com.editora.http.NodeResponseScripts;
import com.editora.process.ProcessRunner;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Response handlers and checks in the window: what the Tests block and its badge show, what the server receives
 * when a handler or {@code @capture} stores a token, what an untrusted folder runs, the Run All summary, the History
 * mark, what the saved history keeps on disk, and the trust prompt's default button.
 */
@Tag("fx")
class HttpResponseHandlersFxTest {

    private static final long WAIT_SECONDS = 60;

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    private Host host;
    private Ops ops;
    private Path configDir;
    private HttpClientCoordinator coordinator;
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static boolean nodeAvailable() {
        return NodeResponseScripts.version(
                        ProcessRunner.resolveExecutable(List.of("node")).get(0))
                != null;
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/login", ex -> {
            hits.add("login");
            reply(ex, "{\"token\":\"tok-4567\",\"id\":7}");
        });
        server.createContext("/me", ex -> {
            hits.add("me " + ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, "{\"id\":7}");
        });
        server.createContext("/hit", ex -> {
            hits.add("hit");
            reply(ex, "{}");
        });
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        configDir = Files.createTempDirectory("http-handlers-test");
        ops = new Ops(configDir);
        coordinator = new HttpClientCoordinator(host, ops);
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws java.io.IOException {
        ex.getRequestBody().readAllBytes();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
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
        HttpEnvCache.awaitIdleForTest();
        HttpHistorySync.awaitIdleForTest();
        buffers.add(b);
        return b;
    }

    private HttpClientPanel panel(EditorBuffer b) {
        return coordinator.panelForTest(b);
    }

    private void runAllAndWait(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> {
            host.active = b;
            coordinator.runFile();
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        do {
            assertTrue(System.nanoTime() < deadline, "the run never ended; the server got " + hits);
            Thread.sleep(30);
            FxTestSupport.drainFx();
        } while (FxTestSupport.callOnFx(() -> coordinator.isRunningForTest(b)));
        FxTestSupport.drainFx();
    }

    // --- the panel alone ---------------------------------------------------------------------------------------

    @Test
    void theTestsBlockShowsEachTestTheLogAndTheError() throws Exception {
        HttpClientPanel p =
                FxTestSupport.callOnFx(() -> new HttpClientPanel(null, ex -> {}, ex -> {}, "monospace", 12));
        HttpTests tests = new HttpTests(
                List.of(
                        new HttpTests.Test("status == 200", true, ""),
                        new HttpTests.Test("user is 8", false, "id was 7")),
                List.of("token stored"),
                "ReferenceError: x is not defined\n    at /p/api.http:9:3");
        HttpResult r = HttpFixtures.text(200, List.of(), "{}", "application/json", 3, 2, null)
                .withTests(tests, List.of());
        FxTestSupport.runOnFx(() -> {
            p.resize(900, 600);
            p.showEntries(List.of(
                    HttpHistoryEntry.of(HttpFixtures.exchange("GET http://h/", "GET", "http://h/", List.of(), "", r))));
        });
        FxTestSupport.drainFx();
        String block = FxTestSupport.callOnFx(p::testsForTest);
        assertTrue(block.startsWith("Tests: 1 passed, 1 failed"), block);
        assertTrue(block.contains("✓  status == 200"), block);
        assertTrue(block.contains("✗  user is 8  —  id was 7"), block);
        assertTrue(block.contains("token stored"), block);
        assertTrue(block.contains("✗  ReferenceError: x is not defined\n    at /p/api.http:9:3"), block);
        assertTrue(FxTestSupport.callOnFx(p::testsExpandedForTest), "a failure opens the block");
        assertEquals("✓ 1 passed · ✗ 1 failed · ✗ handler error", FxTestSupport.callOnFx(p::testsBadgeForTest));
        assertEquals(
                List.of("✓ 1 passed = passed", "✗ 1 failed = failed", "✗ handler error = failed"),
                FxTestSupport.callOnFx(p::testsBadgePartsForTest),
                "the passing part is green, the failing parts red");

        // A response that ran no tests has no badge and no block.
        HttpResult plain = HttpFixtures.text(200, List.of(), "{}", "application/json", 3, 2, null);
        FxTestSupport.runOnFx(() -> p.showEntries(List.of(HttpHistoryEntry.of(
                HttpFixtures.exchange("GET http://h/2", "GET", "http://h/2", List.of(), "", plain)))));
        assertNull(FxTestSupport.callOnFx(p::testsBadgeForTest));
        assertEquals("", FxTestSupport.callOnFx(p::testsForTest));
    }

    @Test
    void aNarrowPanelShortensTheTestsBadge() throws Exception {
        HttpTests tests = new HttpTests(
                List.of(new HttpTests.Test("a", true, ""), new HttpTests.Test("b", true, "")), List.of(), null);
        HttpResult r = HttpFixtures.text(200, List.of(), "{}", "application/json", 3, 2, null)
                .withTests(tests, List.of());
        HttpExchange ex = HttpFixtures.exchange("GET http://h/", "GET", "http://h/", List.of(), "", r);
        for (double width : new double[] {900, 120}) {
            HttpClientPanel p =
                    FxTestSupport.callOnFx(() -> new HttpClientPanel(null, e -> {}, e -> {}, "monospace", 12));
            javafx.stage.Stage stage = FxTestSupport.callOnFx(() -> {
                javafx.stage.Stage st = new javafx.stage.Stage();
                st.setScene(new javafx.scene.Scene(p, width, 400));
                st.show();
                return st;
            });
            try {
                FxTestSupport.drainFx();
                FxTestSupport.runOnFx(() -> p.showEntries(List.of(HttpHistoryEntry.of(ex))));
                String want = width > 500 ? "✓ 2 passed" : "✓2";
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                while (!want.equals(FxTestSupport.callOnFx(p::testsBadgeForTest))) {
                    assertTrue(
                            System.nanoTime() < deadline,
                            width + " px: " + FxTestSupport.callOnFx(p::testsBadgeForTest));
                    Thread.sleep(20);
                }
                assertEquals(
                        List.of(want + " = passed"), FxTestSupport.callOnFx(p::testsBadgePartsForTest), width + " px");
            } finally {
                FxTestSupport.runOnFx(stage::close);
            }
        }
    }

    // --- the coordinator, end to end ---------------------------------------------------------------------------

    @Test
    void aTrustedRunTestsCapturesAndSendsTheTokenAndKeepsTheResults(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        ops.trust = true;
        Path file = dir.resolve("api.http");
        EditorBuffer b = httpBuffer(
                file,
                "### login\n# @assert status == 200\n# @assert $.id == 8\nPOST " + url("/login") + "\n\n> {%\n"
                        + "  client.test('token present', () => client.assert(!!response.body.token, 'no token'));\n"
                        + "  client.global.set('token', response.body.token);\n"
                        + "  client.log('stored');\n%}\n\n"
                        + "### me\nGET " + url("/me") + "\nAuthorization: Bearer {{token}}\n");
        runAllAndWait(b);
        assertEquals(List.of("login", "me Bearer tok-4567"), hits);
        assertEquals(1, ops.scriptAsks.get(), "asked once for the run");

        HttpClientPanel p = panel(b);
        String summary = FxTestSupport.callOnFx(p::runSummaryForTest);
        assertTrue(summary.endsWith(" · tests failed in 1 request"), summary);
        // Both answered 200: the run lands on the request whose tests failed.
        assertEquals(
                "login",
                FxTestSupport.callOnFx(
                        () -> p.controlsForTest().historyPicker().getValue().name()));
        String block = FxTestSupport.callOnFx(p::testsForTest);
        assertTrue(block.startsWith("Tests: 2 passed, 1 failed"), block);
        assertTrue(block.contains("✗  $.id == 8  —  expected == 8, got 7"), block);
        assertTrue(block.contains("✓  token present"), block);
        assertTrue(block.contains("stored"), block);
        assertEquals("✓ 2 passed · ✗ 1 failed", FxTestSupport.callOnFx(p::testsBadgeForTest));
        assertEquals(
                List.of("✓ 2 passed = passed", "✗ 1 failed = failed"),
                FxTestSupport.callOnFx(p::testsBadgePartsForTest));

        // On disk: the test results are kept with the response.
        HttpHistorySync.awaitIdleForTest();
        String onDisk = Files.readString(ops.store.fileFor(file));
        assertTrue(onDisk.contains("\"schemaVersion\" : 4") || onDisk.contains("\"schemaVersion\":4"), onDisk);
        assertTrue(onDisk.contains("token present"), onDisk);
        assertTrue(onDisk.contains("expected == 8, got 7"), onDisk);
        HttpResponseHistory.Stored stored = ops.store.load(file);
        HttpResponseHistory.Response login = stored.responses.stream()
                .filter(r -> "login".equals(r.name()))
                .findFirst()
                .orElseThrow();
        assertEquals(3, login.tests().tests().size());
        assertEquals(List.of("stored"), login.tests().log());
        // …and come back as they were.
        HttpHistoryEntry back = HttpHistoryEntry.fromStored(login);
        assertEquals(1, back.result().tests().failed());
    }

    @Test
    void anUntrustedFolderRunsTheChecksButNotTheHandler(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        ops.trust = false;
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "### login\n# @assert status == 200\n# @capture token = $.token\nPOST " + url("/login") + "\n\n"
                        + "> {% fetch('" + url("/hit") + "'); client.global.set('token', 'from-handler'); %}\n\n"
                        + "### me\nGET " + url("/me") + "\nAuthorization: Bearer {{token}}\n");
        runAllAndWait(b);
        Thread.sleep(300);
        assertEquals(List.of("login", "me Bearer tok-4567"), hits, "the handler never ran; @capture did");
        assertEquals(1, ops.scriptAsks.get());
        HttpClientPanel p = panel(b);
        FxTestSupport.runOnFx(() -> {
            var history = p.controlsForTest().historyPicker();
            history.setValue(history.getItems().stream()
                    .filter(e -> "login".equals(e.name()))
                    .findFirst()
                    .orElseThrow());
        });
        assertEquals("✓ 1 passed", FxTestSupport.callOnFx(p::testsBadgeForTest));
        String notes = FxTestSupport.callOnFx(p::notesForTest);
        assertTrue(notes.contains("not trusted to run scripts"), notes);
    }

    @Test
    void aPreRequestScriptsVariableIsSentAndItsLogShown(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        ops.trust = true;
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "< {% request.variables.set('t', 'tok-1'); client.log('signed') %}\nGET " + url("/me")
                        + "\nAuthorization: Bearer {{t}}\n");
        runAllAndWait(b);
        assertEquals(List.of("me Bearer tok-1"), hits);
        String block = FxTestSupport.callOnFx(() -> panel(b).testsForTest());
        assertTrue(block.contains("signed"), block);
    }

    @Test
    void aPreRequestScriptThatCannotRunLeavesItsRequestUnsent(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        ops.trust = false;
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "< {% request.variables.set('t', 'tok-1') %}\nGET " + url("/me") + "\nAuthorization: Bearer {{t}}\n");
        runAllAndWait(b);
        Thread.sleep(200);
        assertEquals(List.of(), hits, "nothing was sent");
        HttpClientPanel p = panel(b);
        assertEquals(
                com.editora.i18n.Messages.tr("httppanel.notSent"),
                FxTestSupport.callOnFx(() -> p.controlsForTest().statusLabel().getText()));
        String error =
                FxTestSupport.callOnFx(() -> p.getSelectedExchange().result().error());
        assertTrue(error.contains("its folder is not trusted to run scripts"), error);
        assertEquals(1, ops.scriptAsks.get());
    }

    @Test
    void debugRequestSaysWhyItCannotRun(@TempDir Path dir) throws Exception {
        EditorBuffer plain = httpBuffer(dir.resolve("plain.http"), "GET " + url("/login") + "\n");
        host.statuses.clear();
        FxTestSupport.runOnFx(coordinator::debugRequestAtCaret);
        assertEquals(
                com.editora.i18n.Messages.tr("status.http.debugNoScript"), host.statuses.poll(10, TimeUnit.SECONDS));
        EditorBuffer scripted =
                httpBuffer(dir.resolve("api.http"), "GET " + url("/login") + "\n\n> {% client.log(1) %}\n");
        FxTestSupport.runOnFx(() -> host.active = scripted);
        host.statuses.clear();
        FxTestSupport.runOnFx(coordinator::debugRequestAtCaret);
        assertEquals(
                com.editora.i18n.Messages.tr("status.http.debugUnavailable"), host.statuses.poll(10, TimeUnit.SECONDS));
        host.settings.setHttpScriptsEnabled(false);
        host.statuses.clear();
        FxTestSupport.runOnFx(coordinator::debugRequestAtCaret);
        assertEquals(
                com.editora.i18n.Messages.tr("status.http.debugScriptsOff"), host.statuses.poll(10, TimeUnit.SECONDS));
        assertEquals(List.of(), hits, "nothing was sent");
        assertTrue(plain != null);
    }

    @Test
    void debugRequestHandsEachScriptsInspectorToTheDebugger(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node is not installed");
        ops.trust = true;
        java.util.concurrent.BlockingQueue<String> attached = new java.util.concurrent.LinkedBlockingQueue<>();
        ops.debugger = (port, label, shim) -> attached.add(port + " " + label + " " + shim.getFileName());
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"), "### login\nGET " + url("/login") + "\n\n> {% client.log('ran') %}\n");
        FxTestSupport.runOnFx(coordinator::debugRequestAtCaret);
        String call = attached.poll(30, TimeUnit.SECONDS);
        assertNotNull(call, "the script never waited for a debugger");
        String[] parts = call.split(" ", 2);
        assertTrue(Integer.parseInt(parts[0]) > 0, call);
        assertTrue(parts[1].startsWith("Response handler of login "), call);
        assertTrue(parts[1].endsWith(".js"), "the shim is named, to be stepped over: " + call);
        assertTrue(FxTestSupport.callOnFx(() -> coordinator.isRunningForTest(b)), "waiting for its debugger");
        FxTestSupport.runOnFx(() -> coordinator.cancelRun(b)); // nobody attaches here: Cancel ends it
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (FxTestSupport.callOnFx(() -> coordinator.isRunningForTest(b))) {
            assertTrue(System.nanoTime() < deadline, "the run never ended");
            Thread.sleep(30);
        }
        assertEquals(List.of("login"), hits, "the request itself went out");
    }

    @Test
    void handlersOffInSettingsAskNothing(@TempDir Path dir) throws Exception {
        host.settings.setHttpScriptsEnabled(false);
        EditorBuffer b =
                httpBuffer(dir.resolve("api.http"), "GET " + url("/login") + "\n\n> {% client.global.set('a', 1) %}\n");
        runAllAndWait(b);
        assertEquals(0, ops.scriptAsks.get());
        String notes = FxTestSupport.callOnFx(() -> panel(b).notesForTest());
        assertTrue(notes.contains("turned off in Settings"), notes);
    }

    @Test
    void theHistoryRowMarksAResponseWhoseTestsFailed() throws Exception {
        HttpTests failed = new HttpTests(List.of(new HttpTests.Test("t", false, "no")), List.of(), null);
        HttpResult r = HttpFixtures.text(200, List.of(), "{}", "application/json", 3, 2, null)
                .withTests(failed, List.of());
        HttpExchange ex = HttpFixtures.exchange("GET http://h/", "GET", "http://h/", List.of(), "", r);
        HttpHistoryCell cell = FxTestSupport.callOnFx(() -> new HttpHistoryCell(java.time.ZoneId.of("UTC")));
        String mark = FxTestSupport.callOnFx(() -> {
            cell.updateItem(HttpHistoryEntry.of(ex), false);
            javafx.scene.control.Label l = (javafx.scene.control.Label)
                    ((javafx.scene.layout.HBox) cell.getGraphic()).getChildren().get(1);
            return l.isVisible() ? l.getText() + " " + l.getStyleClass() : "hidden";
        });
        assertTrue(mark.startsWith("✗ "), mark);
        assertTrue(mark.contains("http-test-failed"), mark);
    }

    // --- the trust prompt ---------------------------------------------------------------------------------------

    @Test
    void theScriptTrustPromptDefaultsToCancel(@TempDir Path dir) throws Exception {
        TrustPrompts.Prompt prompt =
                FxTestSupport.callOnFx(() -> TrustPrompts.httpScripts(null, dir, dir.resolve("api.http")));
        FxTestSupport.runOnFx(() -> prompt.alert().show());
        try {
            FxTestSupport.drainFx();
            DialogPane pane = prompt.alert().getDialogPane();
            Button cancel = FxTestSupport.callOnFx(() -> (Button) pane.lookupButton(ButtonType.CANCEL));
            Button trust = FxTestSupport.callOnFx(() -> (Button) pane.lookupButton(prompt.trust()));
            assertTrue(FxTestSupport.callOnFx(cancel::isDefaultButton));
            assertFalse(FxTestSupport.callOnFx(trust::isDefaultButton));
            Node focused = FxTestSupport.callOnFx(() -> pane.getScene().getFocusOwner());
            assertEquals(cancel, focused);
            String body = FxTestSupport.callOnFx(pane::getContentText);
            assertTrue(body.contains("api.http") && body.contains(dir.toString()), body);
            assertTrue(body.contains("environment's variables"), "the apostrophe survives MessageFormat: " + body);
            assertEquals("Trust folder and run", FxTestSupport.callOnFx(trust::getText));
        } finally {
            FxTestSupport.runOnFx(() -> prompt.alert().close());
        }
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        volatile EditorBuffer active;

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
            statuses.add(message);
        }
    }

    private static final class Ops implements HttpClientCoordinator.WindowOps {
        final HttpResponseHistory store;
        final Path cache;
        volatile boolean trust;
        final AtomicInteger scriptAsks = new AtomicInteger();

        volatile com.editora.http.NodeResponseScripts.Debugger debugger;

        @Override
        public com.editora.http.NodeResponseScripts.Debugger scriptDebugger() {
            return debugger;
        }

        Ops(Path configDir) {
            store = new HttpResponseHistory(configDir);
            cache = configDir.resolve("cache").resolve("http-scripts");
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
        public boolean allowScripts(Path file) {
            assertNotNull(file);
            scriptAsks.incrementAndGet();
            return trust;
        }

        @Override
        public boolean isTrusted(Path file) {
            return trust;
        }

        @Override
        public Path scriptCacheDir() {
            return cache;
        }
    }
}
