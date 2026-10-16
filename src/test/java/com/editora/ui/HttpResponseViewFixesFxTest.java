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

import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response view after the second review, checked by what the user or the server actually gets: which
 * request Run re-sends after an edit (the path the 127.0.0.1 server receives), the warnings apart from the
 * headers, what a Run All leaves on disk, untitled "save first" results never written, a closed tab's timer
 * stopped, Escape in Preview, and how narrow the panel lets a Split get.
 */
@Tag("fx")
class HttpResponseViewFixesFxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private final List<HttpClientCoordinator> coordinators = new ArrayList<>();
    private final List<Stage> stages = new ArrayList<>();

    @TempDir
    Path work;

    @TempDir
    Path config;

    private Host host;

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
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            if (path.equals("/slow")) {
                slowStarted.countDown();
                try {
                    releaseSlow.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(path.equals("/missing") ? 404 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseSlow.countDown();
        coordinators.forEach(HttpClientCoordinator::shutdown);
        server.stop(0);
        FxTestSupport.runOnFx(() -> {
            stages.forEach(Stage::close);
            buffers.forEach(EditorBuffer::dispose);
        });
        HttpHistorySync.awaitIdleForTest(); // a save still queued would write into the temp folder being deleted
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpClientCoordinator coordinator(Ops ops) {
        HttpClientCoordinator c = new HttpClientCoordinator(host, ops);
        coordinators.add(c);
        return c;
    }

    private EditorBuffer open(HttpClientCoordinator c, Path file, String text) throws Exception {
        if (file != null) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        }
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            if (file != null) {
                created.setPath(file);
            } else {
                created.setDisplayName("scratch.http");
            }
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            c.ensureHttpPreview(created);
            return created;
        });
        HttpEnvCache.awaitIdleForTest(); // the env files are read off the FX thread
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

    private String finished() throws InterruptedException {
        return host.awaitStatus(s -> !s.startsWith("Running"));
    }

    /** The saved history file of {@code file} once {@code check} holds for its text. */
    private String awaitSaved(Ops ops, Path file, Predicate<String> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            Path saved = ops.store.fileFor(file);
            String text = Files.exists(saved) ? Files.readString(saved) : "";
            if (check.test(text)) {
                return text;
            }
            assertTrue(System.nanoTime() < deadline, "timed out waiting for the saved history: " + text);
            Thread.sleep(20);
        }
    }

    // --- U2: Run re-sends the same request, found by name or method + URL template, never by position ----------

    @Test
    void runAfterInsertingARequestAboveResendsTheSameRequestNotTheOneNowAtItsPosition() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        String text = "### Users\nGET " + url("/users") + "\n\n### Missing\nGET " + url("/missing") + "\n";
        EditorBuffer b = open(c, work.resolve("api.http"), text);
        FxTestSupport.runOnFx(() -> c.runRequest(b, 4));
        finished();
        hits.clear();
        // A DELETE inserted at the top: the "Missing" request is now third, and position 1 holds "Users".
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "### Danger\nDELETE " + url("/danger") + "\n\n"));
        Button run = c.panelForTest(b).controlsForTest().runButton();
        FxTestSupport.runOnFx(run::fire);
        finished();
        assertEquals(List.of("GET /missing"), hits, "the server got the request the response came from");
    }

    @Test
    void anUnnamedRequestIsFoundByItsMethodAndUrlTemplateWhateverTheEnvironment() throws Exception {
        Files.writeString(
                work.resolve("http-client.env.json"),
                "{\"a\":{\"host\":\"" + url("") + "\",\"id\":\"1\"},\"b\":{\"host\":\"" + url("")
                        + "\",\"id\":\"2\"}}");
        HttpClientCoordinator c = coordinator(new Ops(config));
        String text = "GET {{host}}/one\n\n###\nGET {{host}}/items/{{id}}\n";
        EditorBuffer b = open(c, work.resolve("t.http"), text);
        javafx.scene.control.ComboBox<String> env =
                c.panelForTest(b).controlsForTest().environmentPicker();
        FxTestSupport.runOnFx(() -> env.setValue("a"));
        FxTestSupport.runOnFx(() -> c.runRequest(b, 3));
        finished();
        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(0, "POST {{host}}/other\n\n###\n"); // position 1 is now /one
            env.setValue("b"); // the URL it sent no longer equals what the template gives now
        });
        hits.clear();
        Button run = c.panelForTest(b).controlsForTest().runButton();
        FxTestSupport.runOnFx(run::fire);
        finished();
        assertEquals(List.of("GET /items/2"), hits);
    }

    @Test
    void aRemovedRequestRunsTheOneAtTheCaretAndSaysSo() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        String text = "### Gone\nGET " + url("/gone") + "\n\n### Kept\nGET " + url("/kept") + "\n";
        EditorBuffer b = open(c, work.resolve("g.http"), text);
        FxTestSupport.runOnFx(() -> c.runRequest(b, 1));
        finished();
        FxTestSupport.runOnFx(() -> {
            b.getArea().replaceText("### Kept\nGET " + url("/kept") + "\n");
            b.getArea().moveTo(1, 0);
        });
        hits.clear();
        Button run = c.panelForTest(b).controlsForTest().runButton();
        FxTestSupport.runOnFx(run::fire);
        host.awaitStatus(s -> s.equals(tr("status.http.rerunAtCaret", "GET " + url("/gone"))));
        finished();
        assertEquals(List.of("GET /kept"), hits);
    }

    // --- U4: warnings in their own block, a badge on the status line, headers left alone -----------------------

    @Test
    void manyWarningsGoToTheirOwnBlockAndTheHeadersBoxStartsWithTheStatusLine() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        StringBuilder q = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            q.append(i == 1 ? "?" : "&")
                    .append("v")
                    .append(i)
                    .append("={{u")
                    .append(i)
                    .append("}}");
        }
        EditorBuffer b = open(c, work.resolve("w.http"), "GET " + url("/json") + q + "\n");
        FxTestSupport.runOnFx(() -> c.runRequest(b, 0));
        finished();
        HttpClientPanel p = c.panelForTest(b);
        TextArea headers = p.controlsForTest().headersArea();
        String shown = FxTestSupport.callOnFx(headers::getText);
        assertTrue(shown.startsWith("HTTP"), "the headers box starts with the status line: " + shown);
        assertFalse(shown.contains("⚠"), "no warning sits among the headers");
        assertEquals(tr("httppanel.warnings", 8), FxTestSupport.callOnFx(p::warnBadgeForTest));
        String notes = FxTestSupport.callOnFx(p::notesForTest);
        assertTrue(
                notes.contains("{{u1}}") && notes.contains("{{u8}}"), "every warning is in the notes block: " + notes);
    }

    // --- U12: a Run All saved like the rest, but each request keeps its newest response; per-request times ------

    @Test
    void aLongRunAllDoesNotPushTheOnlyResponseOfAnotherRequestOffTheDisk() throws Exception {
        Ops ops = new Ops(config);
        HttpClientCoordinator c = coordinator(ops);
        Path file = work.resolve("many.http");
        StringBuilder text = new StringBuilder("### Lonely\nGET " + url("/lonely") + "\n\n");
        for (int i = 0; i < 6; i++) {
            text.append("### R")
                    .append(i)
                    .append("\nGET ")
                    .append(url("/r" + i))
                    .append("\n\n");
        }
        EditorBuffer b = open(c, file, text.toString());
        FxTestSupport.runOnFx(() -> c.runRequest(b, 1));
        finished();
        // Without "Lonely": a Run All of the 6 others, twice — 12 newer responses, more than the 10 kept.
        String others = text.substring(text.indexOf("### R0"));
        for (int round = 0; round < 2; round++) {
            FxTestSupport.runOnFx(() -> b.getArea().replaceText(others));
            FxTestSupport.runOnFx(c::runFile);
            finished();
        }
        // Both Run Alls are on disk once the 10 places are full (Lonely + 6 + 6 more than fit).
        String saved = awaitSaved(ops, file, s -> s.split("\"requestIndex\"", -1).length - 1 == 10);
        assertTrue(saved.contains("/lonely"), "the only response of Lonely is still saved: " + saved);
        HttpClientPanel p = c.panelForTest(b);
        assertEquals(tr("httppanel.runAllOk", 6), FxTestSupport.callOnFx(p::runSummaryForTest));
    }

    // --- U13: an untitled buffer's "not sent — save first" results are never written ---------------------------

    @Test
    void notSentResultsOfAnUntitledBufferAreNotSavedWithItsHistory() throws Exception {
        Ops ops = new Ops(config);
        HttpClientCoordinator c = coordinator(ops);
        String text = "### Fine\nGET " + url("/fine") + "\n\n### Needs file\nPOST " + url("/x") + "\n\n< ./body.json\n";
        EditorBuffer b = open(c, null, text);
        FxTestSupport.runOnFx(c::runFile);
        finished();
        Path file = work.resolve("saved.http");
        FxTestSupport.runOnFx(() -> {
            b.setPath(file);
            c.ensureHttpPreview(b); // what Save As does through ensurePreviewControls
        });
        HttpEnvCache.awaitIdleForTest(); // the env files are read off the FX thread
        String saved = awaitSaved(ops, file, s -> s.contains("/fine"));
        assertFalse(saved.contains("Needs file"), "a \"save the file first\" result is not history: " + saved);
    }

    // --- Q4: a tab closed while its request runs stops the panel's timer ----------------------------------------

    @Test
    void closingATabWhileItsRequestRunsStopsTheElapsedTimer() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        EditorBuffer b = open(c, work.resolve("s.http"), "GET " + url("/slow") + "\n");
        HttpClientPanel p = c.panelForTest(b);
        FxTestSupport.runOnFx(() -> c.runRequest(b, 0));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        Timeline elapsed = p.controlsForTest().elapsed();
        assertEquals(Animation.Status.RUNNING, FxTestSupport.callOnFx(elapsed::getStatus));
        FxTestSupport.runOnFx(() -> c.onBufferClosed(b));
        assertEquals(Animation.Status.STOPPED, FxTestSupport.callOnFx(elapsed::getStatus));
        assertFalse(FxTestSupport.callOnFx(() -> c.isRunningForTest(b)));
    }

    // --- U6: Escape in Preview shows the editor and focuses it ---------------------------------------------------

    @Test
    void escapeInPreviewShowsTheEditorAndFocusesIt() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        EditorBuffer b = open(c, work.resolve("e.http"), "GET " + url("/json") + "\n");
        FxTestSupport.runOnFx(() -> {
            Stage st = new Stage();
            stages.add(st);
            st.setScene(new Scene(b.getNode(), 900, 600));
            st.show();
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
        });
        FxTestSupport.drainFx();
        HttpClientPanel p = c.panelForTest(b);
        javafx.scene.Node body = p.controlsForTest().bodyArea();
        await(() -> body.getScene() != null); // the panel's own SplitPane adds the body on its first layout
        FxTestSupport.runOnFx(() -> {
            body.requestFocus();
            body.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
        });
        FxTestSupport.drainFx();
        assertEquals(EditorBuffer.MarkdownViewMode.SPLIT, FxTestSupport.callOnFx(b::getMarkdownViewMode));
        await(() -> b.getArea().getScene() != null); // the Split adds the editor on its next layout
        assertTrue(
                FxTestSupport.callOnFx(() -> b.getArea().getScene().getFocusOwner() == b.getArea()),
                "the editor has the focus");
    }

    // --- U5: a narrow Split leaves the editor room ---------------------------------------------------------------

    @Test
    void thePanelCanBeNarrowerThanItsControlsAndThePickerFollows() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        EditorBuffer b = open(c, work.resolve("n.http"), "GET " + url("/json") + "\n");
        HttpClientPanel p = c.panelForTest(b);
        assertTrue(FxTestSupport.callOnFx(() -> p.minWidth(-1)) <= HttpClientPanel.MIN_WIDTH, "min width");
        FxTestSupport.runOnFx(() -> {
            Stage st = new Stage();
            stages.add(st);
            st.setScene(new Scene(p, 280, 500));
            st.show();
        });
        FxTestSupport.drainFx();
        javafx.scene.control.ComboBox<?> history = p.controlsForTest().historyPicker();
        double w = FxTestSupport.callOnFx(history::getWidth);
        assertTrue(w <= 280 - 60, "the History picker narrowed to the panel: " + w);
    }

    // --- support ------------------------------------------------------------------------------------------------

    private final class Ops implements HttpClientCoordinator.WindowOps {
        final HttpResponseHistory store;

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
        public boolean simpleModeActive() {
            return false;
        }

        @Override
        public boolean isLocalBuffer(EditorBuffer buffer) {
            return true;
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
