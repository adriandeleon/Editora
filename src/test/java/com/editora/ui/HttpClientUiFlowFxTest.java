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

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

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
 * The HTTP Client's integration with the editor and the config folder, against a server on 127.0.0.1: the
 * saved history and per-file environment surviving a restart, {@code @no-log}, masked credentials, the Run
 * button, Run All progress, the editor menu and ▶ tooltip of a request file, Simple mode and remote files.
 */
@Tag("fx")
class HttpClientUiFlowFxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private final List<HttpClientCoordinator> coordinators = new ArrayList<>();

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
        server.createContext("/json", ex -> {
            hits.add("json " + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("Set-Cookie", "session=abc");
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
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseSlow.countDown();
        coordinators.forEach(HttpClientCoordinator::shutdown);
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
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
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            c.ensureHttpPreview(created);
            return created;
        });
        buffers.add(b);
        return b;
    }

    /** Waits until {@code check} holds on the FX thread (a saved history is read off it). */
    private static void await(java.util.concurrent.Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!FxTestSupport.callOnFx(check)) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void theLastResponsesAndTheEnvironmentComeBackAfterARestartMaskedAndWithoutNoLogOnes() throws Exception {
        Files.writeString(work.resolve("http-client.env.json"), "{\"dev\":{\"t\":\"s3cr3t\"},\"prod\":{}}");
        Ops ops = new Ops(config);
        HttpClientCoordinator first = coordinator(ops);
        Path file = work.resolve("api.http");
        String text = "### Get json\nGET " + url("/json") + "\nAuthorization: Bearer {{t}}\n\n"
                + "### Secret\n# @no-log\nGET " + url("/json") + "?secret\n";
        EditorBuffer b = open(first, file, text);
        HttpClientPanel panel = first.panelForTest(b);
        ComboBox<String> env = FxTestSupport.field(panel, "envCombo");
        FxTestSupport.runOnFx(() -> env.setValue("dev"));
        FxTestSupport.runOnFx(() -> first.runRequest(b, 1));
        host.awaitStatus(s -> !s.startsWith("Running"));
        FxTestSupport.runOnFx(() -> first.runRequest(b, 6));
        host.awaitStatus(s -> !s.startsWith("Running"));
        assertEquals("json Bearer s3cr3t", hits.get(0), "the environment was applied");

        String saved = Files.readString(ops.store.fileFor(file));
        assertFalse(saved.contains("s3cr3t"), "credential headers are masked on disk: " + saved);
        assertFalse(saved.contains("session=abc"), "so is Set-Cookie");
        assertFalse(saved.contains("?secret"), "a # @no-log request is never saved");
        assertTrue(saved.contains("{\\\"ok\\\":true}"), "the body is kept: " + saved);

        // A new window (a restart) shows the saved response and the file's environment again.
        HttpClientCoordinator second = coordinator(new Ops(config));
        EditorBuffer again = open(second, file, text);
        HttpClientPanel restored = second.panelForTest(again);
        await(() -> restored.getSelectedExchange() != null);
        assertEquals(1, FxTestSupport.callOnFx(() -> restored.history().size()));
        HttpHistoryEntry entry = FxTestSupport.callOnFx(restored::getSelectedEntry);
        assertEquals("Get json", entry.name());
        assertEquals(200, entry.result().status());
        assertEquals("dev", FxTestSupport.callOnFx(restored::getSelectedEnvironment));
    }

    @Test
    void theEnvironmentIsRememberedPerFileAndANameAFileLacksFallsBackToNone() throws Exception {
        Files.createDirectories(work.resolve("a"));
        Files.createDirectories(work.resolve("b"));
        Files.writeString(work.resolve("a/http-client.env.json"), "{\"dev\":{}}");
        Files.writeString(work.resolve("b/http-client.env.json"), "{\"staging\":{}}");
        Files.writeString(work.resolve("b/http-client.private.env.json"), "{\"local\":{}}");
        Ops ops = new Ops(config);
        ops.legacy = "dev"; // the old window-wide choice
        HttpClientCoordinator c = coordinator(ops);
        EditorBuffer a = open(c, work.resolve("a/a.http"), "GET " + url("/json") + "\n");
        EditorBuffer b = open(c, work.resolve("b/b.http"), "GET " + url("/json") + "\n");
        assertEquals("dev", FxTestSupport.callOnFx(() -> c.panelForTest(a).getSelectedEnvironment()));
        assertEquals("", FxTestSupport.callOnFx(() -> c.panelForTest(b).getSelectedEnvironment()), "b has no dev");
        ComboBox<String> envB = FxTestSupport.field(c.panelForTest(b), "envCombo");
        assertEquals(
                List.of("", "staging", "local"),
                FxTestSupport.callOnFx(() -> List.copyOf(envB.getItems())),
                "names from both env files");

        FxTestSupport.runOnFx(() -> envB.setValue("local"));
        FxTestSupport.runOnFx(() -> {
            c.refreshEnvironments(a);
            c.refreshEnvironments(b);
        });
        assertEquals("dev", FxTestSupport.callOnFx(() -> c.panelForTest(a).getSelectedEnvironment()), "a is untouched");
        assertEquals("local", FxTestSupport.callOnFx(() -> c.panelForTest(b).getSelectedEnvironment()));
    }

    @Test
    void envFilesInAParentFolderApply(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("http-client.env.json"), "{\"dev\":{\"host\":\"" + url("") + "\"}}");
        Ops ops = new Ops(config);
        ops.projectRoot = project;
        HttpClientCoordinator c = coordinator(ops);
        EditorBuffer b = open(c, project.resolve("api/v1/req.http"), "GET {{host}}/json\n");
        ComboBox<String> env = FxTestSupport.field(c.panelForTest(b), "envCombo");
        FxTestSupport.runOnFx(() -> env.setValue("dev"));
        FxTestSupport.runOnFx(() -> c.runRequest(b, 0));
        assertEquals(
                tr("status.http.finished", "GET " + url("/json"), "200 OK"),
                host.awaitStatus(s -> !s.startsWith("Running")));
    }

    @Test
    void theRunButtonSendsTheSelectedResponsesRequestAgain() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        EditorBuffer b = open(
                c, work.resolve("two.http"), "GET " + url("/json") + "?one\n\n###\nGET " + url("/json") + "?two\n");
        FxTestSupport.runOnFx(() -> c.runRequest(b, 3));
        host.awaitStatus(s -> !s.startsWith("Running"));
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(0)); // the caret is on the other request
        Button run = FxTestSupport.field(c.panelForTest(b), "runButton");
        FxTestSupport.runOnFx(run::fire);
        assertEquals(
                tr("status.http.finished", "GET " + url("/json") + "?two", "200 OK"),
                host.awaitStatus(s -> !s.startsWith("Running")));
    }

    @Test
    void aRunAllShowsWhichRequestIsInFlight() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        EditorBuffer b =
                open(c, work.resolve("all.http"), "GET " + url("/json") + "\n\n###\nGET " + url("/slow") + "\n");
        FxTestSupport.runOnFx(c::runFile);
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        HttpClientPanel p = c.panelForTest(b);
        await(() -> p.statusForTest().contains(tr("httppanel.runningOf", 2, 2, "GET " + url("/slow"))));
        releaseSlow.countDown();
        assertEquals(tr("status.http.allDone", 2), host.awaitStatus(s -> !s.startsWith("Running")));
    }

    @Test
    void simpleModeSaysItIsSimpleModeNotTheSetting() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        EditorBuffer b = open(c, work.resolve("api.http"), "GET " + url("/json") + "\n");
        host.simple = true;
        FxTestSupport.runOnFx(() -> c.runRequest(b, 0));
        assertEquals(tr("status.http.simpleMode"), host.last);
        assertEquals(List.of(), hits);
    }

    @Test
    void aRemoteRequestFileGetsNoRunGlyphsAfterASettingsApply() throws Exception {
        HttpClientCoordinator c = coordinator(new Ops(config));
        EditorBuffer local = open(c, work.resolve("local.http"), "GET " + url("/json") + "\n");
        EditorBuffer remote = open(c, work.resolve("remote.http"), "GET " + url("/json") + "\n");
        host.remote = remote;
        FxTestSupport.runOnFx(c::applySupport);
        assertTrue(FxTestSupport.<Boolean>field(local, "httpFeatureEnabled"));
        assertFalse(FxTestSupport.<Boolean>field(remote, "httpFeatureEnabled"), "a remote file cannot run here");
    }

    @Test
    void theEditorMenuOfARequestFileRunsRequestsAndTheGlyphSaysSo() throws Exception {
        int[] runs = {-1, 0};
        Stage stage = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            buffers.add(b);
            b.setDisplayName("api.http");
            b.setHttpRunHandler(line -> runs[0] = line);
            b.setHttpRunAllHandler(() -> runs[1]++);
            b.setHttpRunTooltip(() -> "Run this request (C-M-r)");
            b.setHttpEnabled(true);
            b.setContent("### one\nGET http://127.0.0.1:1/\nAccept: */*\n");
            b.settleRunScan();
            Stage st = new Stage();
            st.setScene(new Scene(b.getNode(), 800, 600));
            st.show();
            return st;
        });
        try {
            EditorBuffer b = buffers.get(buffers.size() - 1);
            FxTestSupport.drainFx();
            List<String> labels = FxTestSupport.callOnFx(() -> {
                b.getArea().moveTo(1, 0);
                b.getArea()
                        .fireEvent(
                                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
                ContextMenu menu = null;
                for (Window w : Window.getWindows()) {
                    if (w instanceof ContextMenu m && m.isShowing()) {
                        menu = m;
                    }
                }
                assertNotNull(menu, "the editor menu opened");
                List<String> texts =
                        menu.getItems().stream().map(MenuItem::getText).toList();
                menu.getItems().get(0).fire();
                menu.getItems().get(1).fire();
                menu.hide();
                return texts;
            });
            assertEquals(tr("editmenu.runHttpRequest"), labels.get(0));
            assertEquals(tr("editmenu.runHttpFile"), labels.get(1));
            assertFalse(labels.contains(tr("command.file.run")), "no Run File: it launched java on the file");
            assertTrue(runs[0] >= 0, "Run Request ran the request under the click");
            assertEquals(1, runs[1]);
            assertEquals(
                    "Run this request (C-M-r)",
                    FxTestSupport.callOnFx(
                            () -> FxTestSupport.call(b, "runGlyphTooltip", new Class<?>[] {int.class}, 1)));
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    private final class Ops implements HttpClientCoordinator.WindowOps {
        final HttpResponseHistory store;
        volatile String legacy = "";
        volatile Path projectRoot;

        Ops(Path configDir) {
            store = new HttpResponseHistory(configDir);
        }

        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void updateRunGating() {}

        @Override
        public String savedEnvironment() {
            return legacy;
        }

        @Override
        public HttpResponseHistory historyStore() {
            return store;
        }

        @Override
        public Path projectRoot() {
            return projectRoot;
        }
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        volatile EditorBuffer active;
        volatile EditorBuffer remote;
        volatile boolean simple;
        volatile String last;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean simpleModeActive() {
            return simple;
        }

        @Override
        public boolean isLocalBuffer(EditorBuffer buffer) {
            return buffer != remote;
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
