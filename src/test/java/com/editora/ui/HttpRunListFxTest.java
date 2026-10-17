package com.editora.ui;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.config.HttpResponseHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.vfs.Vfs;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Run All results list (U10), end to end against a 127.0.0.1 server: one row per request, the first failure
 * selected and shown, "Only failures", a row's selection filling the tabs, Enter going to the request, a single run or
 * Clear taking the list away, and the row menu's Run again and Copy as cURL.
 */
@Tag("fx")
class HttpRunListFxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private int port;
    private int closedPort;
    private final List<String> hits = new CopyOnWriteArrayList<>();
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
            hits.add(ex.getRequestMethod() + " " + path);
            int code = path.equals("/missing") ? 404 : path.equals("/boom") ? 500 : 200;
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort(); // nobody listens there once it is closed: connection refused
        }
        host = new Host();
        host.settings.setHttpClientSupport(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinators.forEach(HttpClientCoordinator::shutdown);
        server.stop(0);
        FxTestSupport.runOnFx(() -> {
            stages.forEach(Stage::close);
            buffers.forEach(EditorBuffer::dispose);
        });
        HttpHistorySync.awaitIdleForTest();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** Five requests: 200, 404, 500, a 200 whose check fails, connection refused. Request lines 1, 4, 7, 11, 14. */
    private String demo() {
        return "### List\nGET " + url("/users") + "\n\n"
                + "### Missing\nGET " + url("/missing") + "\n\n"
                + "### Boom\nGET " + url("/boom") + "\n\n"
                + "### Tested\n# @assert status == 201\nGET " + url("/tested") + "\n\n"
                + "### Nobody\nGET http://127.0.0.1:" + closedPort + "/nothing\n";
    }

    private HttpClientCoordinator coordinator() {
        HttpClientCoordinator c = new HttpClientCoordinator(host, new Ops(config));
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
            c.applySupport();
            return created;
        });
        HttpEnvCache.awaitIdleForTest();
        buffers.add(b);
        return b;
    }

    private static void await(Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!FxTestSupport.callOnFx(check)) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    /** Runs the whole file and waits for the list. */
    private HttpRunList runAll(HttpClientCoordinator c, HttpClientPanel p) throws Exception {
        FxTestSupport.runOnFx(c::runFile);
        HttpRunList list = p.runListForTest();
        await(() -> !p.isRunning() && list.isShown());
        FxTestSupport.callOnFx(p::bodyRenderForTest).get(WAIT_SECONDS, TimeUnit.SECONDS);
        return list;
    }

    private static List<String> names(HttpRunList list) throws Exception {
        return FxTestSupport.callOnFx(() -> list.listForTest().getItems().stream()
                .map(HttpRunRows.Row::name)
                .toList());
    }

    private static String selectedName(HttpRunList list) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            HttpRunRows.Row row = list.listForTest().getSelectionModel().getSelectedItem();
            return row == null ? null : row.name();
        });
    }

    private static void select(HttpRunList list, String name) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<HttpRunRows.Row> v = list.listForTest();
            for (HttpRunRows.Row row : v.getItems()) {
                if (row.name().equals(name)) {
                    v.getSelectionModel().select(row);
                }
            }
        });
    }

    @Test
    void aRunAllListsEveryRequestAndShowsTheFirstFailure() throws Exception {
        HttpClientCoordinator c = coordinator();
        EditorBuffer b = open(c, work.resolve("api.http"), demo());
        HttpClientPanel p = c.panelForTest(b);
        HttpRunList list = runAll(c, p);

        assertEquals(List.of("List", "Missing", "Boom", "Tested", "Nobody"), names(list), "one row per request");
        List<HttpRunRows.Row> rows =
                FxTestSupport.callOnFx(() -> List.copyOf(list.listForTest().getItems()));
        assertEquals(
                List.of("200", "404", "500", "200", tr("httppanel.runList.failedPill")),
                rows.stream().map(HttpRunRows.Row::status).toList());
        assertEquals("GET /missing", rows.get(1).detail());
        assertEquals("✗ 0/1", rows.get(3).tests(), "the failed check");
        assertEquals("—", rows.get(0).tests());
        String title = FxTestSupport.callOnFx(list::titleForTest);
        assertTrue(
                title.startsWith("Run All · 3 of 5 failed · " + tr("httppanel.runAllTestsFailed", 1L) + " · "), title);
        assertTrue(title.endsWith(" s"), "the run's time: " + title);
        assertEquals("http-run-failed", FxTestSupport.callOnFx(list::pillStyleForTest));

        assertEquals("Missing", selectedName(list), "the first failure is selected");
        assertEquals(
                "404 Not Found",
                FxTestSupport.callOnFx(() -> p.controlsForTest().statusLabel().getText()));
        assertEquals(
                "Missing",
                FxTestSupport.callOnFx(
                        () -> p.controlsForTest().historyPicker().getValue().name()),
                "…and shown, as History's selection");
        assertEquals(
                tr("status.http.someFailed", 3L, 5) + " · " + tr("httppanel.runAllTestsFailed", 1L),
                host.last,
                "the status bar says the same as before");
    }

    @Test
    void onlyFailuresFiltersAndSelectingARowFillsTheTabs() throws Exception {
        HttpClientCoordinator c = coordinator();
        EditorBuffer b = open(c, work.resolve("api.http"), demo());
        HttpClientPanel p = c.panelForTest(b);
        HttpRunList list = runAll(c, p);

        FxTestSupport.runOnFx(() -> list.onlyFailuresForTest().setSelected(true));
        assertEquals(List.of("Missing", "Boom", "Tested", "Nobody"), names(list));
        assertEquals("Missing", selectedName(list), "the selection stays");

        select(list, "Tested");
        FxTestSupport.callOnFx(p::bodyRenderForTest).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(
                "Tested",
                FxTestSupport.callOnFx(
                        () -> p.controlsForTest().historyPicker().getValue().name()));
        assertEquals(HttpResponseTab.TESTS, FxTestSupport.callOnFx(p::selectedTabForTest), "a failed test → Tests");
        assertTrue(FxTestSupport.callOnFx(p::testsForTest).contains("status == 201"));

        select(list, "Nobody");
        FxTestSupport.callOnFx(p::bodyRenderForTest).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(
                tr("httppanel.requestFailed"),
                FxTestSupport.callOnFx(() -> p.controlsForTest().statusLabel().getText()));

        FxTestSupport.runOnFx(() -> list.onlyFailuresForTest().setSelected(false));
        assertEquals(5, names(list).size());
        assertEquals("Nobody", selectedName(list));
        select(list, "List");
        assertEquals(
                "200 OK",
                FxTestSupport.callOnFx(() -> p.controlsForTest().statusLabel().getText()));
        FxTestSupport.runOnFx(() -> list.onlyFailuresForTest().setSelected(true));
        assertNull(selectedName(list), "its row is filtered out");
        assertEquals(
                "200 OK",
                FxTestSupport.callOnFx(() -> p.controlsForTest().statusLabel().getText()),
                "…and it is still shown");
    }

    @Test
    void enterGoesToTheRequestInTheEditorLeavingPreview() throws Exception {
        HttpClientCoordinator c = coordinator();
        EditorBuffer b = open(c, work.resolve("api.http"), demo());
        HttpClientPanel p = c.panelForTest(b);
        HttpRunList list = runAll(c, p);
        FxTestSupport.runOnFx(() -> {
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
            b.getArea().moveTo(0);
        });
        select(list, "Tested");
        FxTestSupport.runOnFx(() -> {
            ListView<HttpRunRows.Row> v = list.listForTest();
            v.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        assertEquals(11, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "the request line");
        assertEquals(EditorBuffer.MarkdownViewMode.SPLIT, FxTestSupport.callOnFx(b::getMarkdownViewMode));

        // A request that is gone says so and moves nothing.
        FxTestSupport.runOnFx(() -> b.getArea().replaceText("### Other\nGET " + url("/other") + "\n"));
        select(list, "Boom");
        FxTestSupport.runOnFx(() -> list.listForTest()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        assertEquals(tr("status.http.requestGone"), host.last);
    }

    @Test
    void aSingleRunOrClearTakesTheListAwayAndAnotherRunsEntryDeselectsIt() throws Exception {
        HttpClientCoordinator c = coordinator();
        EditorBuffer b = open(c, work.resolve("api.http"), demo());
        HttpClientPanel p = c.panelForTest(b);
        FxTestSupport.runOnFx(() -> c.runRequest(b, 1)); // a single run first: no list
        await(() -> !p.isRunning() && p.getSelectedEntry() != null);
        assertFalse(FxTestSupport.callOnFx(() -> p.runListForTest().isShown()), "a single run shows no list");
        HttpHistoryEntry single = FxTestSupport.callOnFx(p::getSelectedEntry);

        HttpRunList list = runAll(c, p);
        FxTestSupport.runOnFx(() -> p.controlsForTest().historyPicker().setValue(single));
        assertTrue(FxTestSupport.callOnFx(list::isShown), "another run's entry keeps the list");
        assertNull(selectedName(list), "…with no row selected");
        HttpHistoryEntry boom = FxTestSupport.callOnFx(
                () -> list.listForTest().getItems().get(2).entry());
        FxTestSupport.runOnFx(() -> p.controlsForTest().historyPicker().setValue(boom));
        assertEquals("Boom", selectedName(list), "an entry of the run selects its row");

        FxTestSupport.runOnFx(() -> c.runRequest(b, 1));
        await(() -> !p.isRunning() && !list.isShown());
        assertFalse(FxTestSupport.callOnFx(list::isShown), "the next run, whichever, takes it away");

        runAll(c, p);
        FxTestSupport.runOnFx(p::clearForTest);
        assertFalse(FxTestSupport.callOnFx(list::isShown), "Clear takes it away");
    }

    @Test
    void theRowMenuRunsThatRequestAgainAndCopiesItAsCurl() throws Exception {
        HttpClientCoordinator c = coordinator();
        EditorBuffer b = open(c, work.resolve("api.http"), demo());
        HttpClientPanel p = c.panelForTest(b);
        HttpRunList list = runAll(c, p);
        List<MenuItem> menu =
                FxTestSupport.callOnFx(() -> list.listForTest().getContextMenu().getItems());

        select(list, "Boom");
        FxTestSupport.runOnFx(() -> menu.get(1).fire()); // Copy as cURL
        String curl =
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
        assertTrue(curl.startsWith("curl") && curl.contains(url("/boom")), curl);

        hits.clear();
        FxTestSupport.runOnFx(() -> menu.get(0).fire()); // Run again
        await(() -> !p.isRunning() && !list.isShown() && p.getSelectedEntry() != null);
        assertEquals(List.of("GET /boom"), hits, "that request only");
    }

    @Test
    void theListTakesAtMostAboutFortyPercentAndCollapsesForThePanelsLife() throws Exception {
        HttpClientCoordinator c = coordinator();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            text.append("### R")
                    .append(i)
                    .append("\nGET ")
                    .append(url("/r" + i))
                    .append("\n\n");
        }
        EditorBuffer b = open(c, work.resolve("many.http"), text.toString());
        HttpClientPanel p = c.panelForTest(b);
        // Already in Split: a run reveals the response view (Editor → Split), and that rebuild would take the panel
        // back
        // out of this test's stage into the buffer's own (unshown) view, where the list keeps a stale height.
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT));
        FxTestSupport.callOnFx(() -> {
            Stage s = new Stage();
            stages.add(s);
            s.setScene(new Scene(new javafx.scene.layout.StackPane(p), 900, 600));
            s.show();
            return s;
        });
        HttpRunList list = runAll(c, p);
        FxTestSupport.drainFx();
        await(() -> list.getHeight() > 60); // its rows land a layout pass after the list is shown
        double listHeight = FxTestSupport.callOnFx(list::getHeight);
        double panelHeight = FxTestSupport.callOnFx(p::getHeight);
        assertTrue(listHeight > 60, "it shows: " + listHeight);
        assertTrue(listHeight <= panelHeight * 0.4 + 1, listHeight + " of " + panelHeight);
        assertEquals("R0", selectedName(list), "all succeeded: the first");

        FxTestSupport.runOnFx(() -> list.chevronForTest().fire());
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(list::collapsedForTest));
        assertFalse(FxTestSupport.callOnFx(() -> list.listForTest().isVisible()), "only the title line stays");
        await(() -> list.getHeight() < 50); // the next layout pass gives the tabs the room

        runAll(c, p);
        assertTrue(FxTestSupport.callOnFx(list::collapsedForTest), "the next Run All stays collapsed");
        assertFalse(FxTestSupport.callOnFx(() -> list.listForTest().isVisible()));
    }

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
        volatile String last;

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
            return buffer != null && Vfs.isLocal(buffer.getPath());
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
}
