package com.editora.ui;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response view's states between responses, driven through the coordinator against a server on 127.0.0.1: the
 * empty state and its rows (U6), the "Previous" row while running and after Cancel (U7), the trust prompt's wait
 * (U8) and the error card (U9).
 */
@Tag("fx")
class HttpPanelStatesFxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/json", ex -> {
            hits.add("json");
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            hits.add("missing");
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            hits.add("slow");
            slowStarted.countDown();
            try {
                releaseSlow.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        ops = new Ops(Files.createTempDirectory("http-states-test"));
        coordinator = new HttpClientCoordinator(host, ops);
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseSlow.countDown();
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
        HttpHistorySync.awaitIdleForTest();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** A saved {@code .http} buffer whose panel is in a scene (as in Split), so its empty state is fed. */
    private EditorBuffer open(Path file, String text) throws Exception {
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            coordinator.ensureHttpPreview(created);
            new Scene(new Group(coordinator.panelForTest(created)), 900, 600);
            return created;
        });
        HttpEnvCache.awaitIdleForTest();
        buffers.add(b);
        return b;
    }

    private HttpClientPanel panel(EditorBuffer b) {
        return coordinator.panelForTest(b);
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!FxTestSupport.callOnFx(condition::getAsBoolean)) {
            assertTrue(System.nanoTime() < deadline, what);
            Thread.sleep(20);
        }
    }

    /** "Sending {@code label}…", the running status up to its clock. */
    static String runningPrefix(String label) {
        return tr("httppanel.running", label, "\u0001").split("\u0001")[0];
    }

    private static String cancelledPrefix() {
        return tr("httppanel.cancelledAfter", "").replace(" s", "").strip();
    }

    // --- U6 -------------------------------------------------------------------------------------------------------

    @Test
    void theEmptyStateListsTheRequestsFollowsTheCaretAndARowRunsItsRequest(@TempDir Path dir) throws Exception {
        String text = "# Demo API\n\n### Get one user\n# @name getUser\nGET " + url("/json")
                + "\n\n### Missing page\nGET " + url("/missing") + "\n";
        EditorBuffer b = open(dir.resolve("api.http"), text);
        HttpClientPanel p = panel(b);
        HttpEmptyState empty = p.emptyStateForTest();
        assertEquals(HttpClientPanel.BodyView.EMPTY, FxTestSupport.callOnFx(p::bodyViewForTest));
        assertFalse(FxTestSupport.callOnFx(() -> p.tabsForTest().isVisible()), "no empty tabs, no disabled icons");
        assertFalse(
                FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().isVisible()),
                "an empty History is not shown");
        await(() -> empty.rowsForTest().size() == 2, "the file's requests are listed");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(0, 0));
        await(() -> empty.titleForTest().equals(tr("httppanel.empty.noRequest")), "the caret is above the requests");
        assertEquals(
                tr("httppanel.empty.runRow", "Missing page"),
                FxTestSupport.callOnFx(() -> empty.rowsForTest().get(1).getAccessibleText()));

        FxTestSupport.runOnFx(() -> b.getArea().moveTo(3, 0));
        await(
                () -> empty.titleForTest().equals(tr("httppanel.empty.willSend", "Get one user")),
                "the title follows the caret (debounced), named by the ### title");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(7, 0));
        await(
                () -> empty.titleForTest().equals(tr("httppanel.empty.willSend", "Missing page")),
                "…to the next request");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(0, 0));

        // Typing a request in: the list follows once the typing rests.
        FxTestSupport.runOnFx(() -> b.getArea().appendText("\n### Third\nGET " + url("/json") + "?3\n"));
        await(() -> empty.rowsForTest().size() == 3, "a new request is listed");

        // A click on a row runs that request (the caret is elsewhere).
        Button missing = FxTestSupport.callOnFx(() -> empty.rowsForTest().get(1));
        FxTestSupport.runOnFx(missing::fire);
        host.awaitStatus(s -> s.startsWith(tr("status.http.failedStatus", "GET " + url("/missing"), "404 Not Found")));
        assertEquals(List.of("missing"), hits);
        assertEquals(HttpClientPanel.BodyView.RESPONSE, FxTestSupport.callOnFx(p::bodyViewForTest));
        assertTrue(FxTestSupport.callOnFx(() -> p.tabsForTest().isVisible()));

        // Clear brings it back, and Enter on a focused row runs that request.
        FxTestSupport.runOnFx(p::clearForTest);
        assertEquals(HttpClientPanel.BodyView.EMPTY, FxTestSupport.callOnFx(p::bodyViewForTest));
        Button first = FxTestSupport.callOnFx(() -> empty.rowsForTest().get(0));
        FxTestSupport.runOnFx(() -> Event.fireEvent(
                first, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        host.awaitStatus(s -> s.equals(tr("status.http.finished", "GET " + url("/json"), "200 OK")));
        assertEquals(List.of("missing", "json"), hits);
    }

    @Test
    void theEmptyStateShowsTheRunChordOnlyWhenItIsBound() throws Exception {
        HttpEmptyState empty = FxTestSupport.callOnFx(() -> new HttpEmptyState(line -> {}));
        FxTestSupport.runOnFx(() -> empty.setChord("Ctrl+Alt+R"));
        assertEquals("Ctrl+Alt+R", FxTestSupport.callOnFx(empty::chordForTest));
        FxTestSupport.runOnFx(() -> empty.setChord(null));
        assertNull(FxTestSupport.callOnFx(empty::chordForTest), "unbound: no chord line");
    }

    // --- U7 -------------------------------------------------------------------------------------------------------

    @Test
    void whileRunningThePreviousResponseIsOneRowAndCancelPutsItBack(@TempDir Path dir) throws Exception {
        String text = "### Get one user\n# @name getUser\nGET " + url("/json") + "\n\n### Slow one\nGET " + url("/slow")
                + "\n";
        EditorBuffer b = open(dir.resolve("api.http"), text);
        HttpClientPanel p = panel(b);
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 2));
        host.awaitStatus(s -> s.startsWith(tr("status.http.finished", "GET " + url("/json"), "")));
        HttpHistoryEntry first =
                FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().getValue());

        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 5));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        String status = FxTestSupport.callOnFx(p::statusForTest);
        assertTrue(status.startsWith(runningPrefix("Slow one")), "named by its title: " + status);
        assertEquals("", FxTestSupport.callOnFx(p::shownBodyForTest), "no old body under the new run");
        assertEquals(HttpClientPanel.BodyView.PREVIOUS, FxTestSupport.callOnFx(p::bodyViewForTest));
        String row = FxTestSupport.callOnFx(p::previousRowForTest);
        assertTrue(row.contains("200 OK") && row.endsWith("getUser"), row);
        assertTrue(FxTestSupport.callOnFx(() -> p.controlsForTest().progress().isVisible()), "the progress line");

        // The row shows its response; the status still says what runs.
        FxTestSupport.runOnFx(p::clickPreviousForTest);
        assertTrue(FxTestSupport.callOnFx(p::headersForTest).startsWith("HTTP"));
        assertTrue(FxTestSupport.callOnFx(p::statusForTest).startsWith(runningPrefix("Slow one")));
        // History stays selectable; back to nothing picked, and then Cancel.
        FxTestSupport.runOnFx(() -> {
            p.controlsForTest().historyPicker().setValue(null);
            coordinator.cancelRun(b);
        });
        String cancelled = FxTestSupport.callOnFx(p::statusForTest);
        assertTrue(cancelled.startsWith(cancelledPrefix()), cancelled);
        assertEquals(HttpClientPanel.BodyView.PREVIOUS, FxTestSupport.callOnFx(p::bodyViewForTest));
        assertEquals(
                first,
                FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().getValue()));
        assertNull(FxTestSupport.callOnFx(p::getSelectedExchange), "nothing to copy until it is shown");
        assertFalse(FxTestSupport.callOnFx(() -> p.controlsForTest().progress().isVisible()));
    }

    // --- U8 -------------------------------------------------------------------------------------------------------

    @Test
    void aTrustPromptShowsNoClockAndTheClockStartsWhenItIsAnswered(@TempDir Path dir) throws Exception {
        String text = "### Json\nGET " + url("/json") + "\n\n### Slow with a handler\nGET " + url("/slow")
                + "\n\n> {% client.log(1) %}\n";
        EditorBuffer b = open(dir.resolve("api.http"), text);
        HttpClientPanel p = panel(b);
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 1));
        host.awaitStatus(s -> s.startsWith(tr("status.http.finished", "GET " + url("/json"), "")));
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch afterAnswer = new CountDownLatch(1);
        ops.onScripts = () -> {
            seen.add(p.statusForTest());
            seen.add(String.valueOf(p.controlsForTest().progress().isVisible()));
            Platform.runLater(() -> {
                seen.add(p.statusForTest());
                afterAnswer.countDown();
            });
            return false; // declined: the handler does not run, the request is still sent
        };
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 4));
        assertTrue(afterAnswer.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(tr("httppanel.waiting"), seen.get(0), "no clock while the prompt waits");
        assertEquals("false", seen.get(1), "no progress line while nothing is sent");
        String[] around =
                tr("httppanel.running", "Slow with a handler", "\u0001").split("\u0001", -1);
        java.util.regex.Matcher sending = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(around[0])
                        + "(\\d+)[.,](\\d)" + java.util.regex.Pattern.quote(around[1]))
                .matcher(seen.get(2));
        assertTrue(sending.matches(), seen.get(2));
        assertEquals("0", sending.group(1), "the clock starts at zero once it is answered: " + seen.get(2));
        releaseSlow.countDown();
        host.awaitStatus(s -> s.startsWith(tr("status.http.finished", "GET " + url("/slow"), "")));
        assertTrue(FxTestSupport.callOnFx(p::statusForTest).startsWith("200 OK"));
        assertFalse(FxTestSupport.callOnFx(p::notesForTest).isEmpty(), "the declined handler is noted");
    }

    @Test
    void aRunEndedWhileItsPromptWaitsGoesBackWithoutCancelledAfter(@TempDir Path dir) throws Exception {
        String text = "### Json\n# @name first\nGET " + url("/json") + "\n\n### With a handler\nGET " + url("/json")
                + "?2\n\n> {% client.log(1) %}\n";
        EditorBuffer b = open(dir.resolve("api.http"), text);
        HttpClientPanel p = panel(b);
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 2));
        host.awaitStatus(s -> s.startsWith(tr("status.http.finished", "GET " + url("/json"), "")));
        ops.onScripts = () -> {
            coordinator.cancelRun(b); // Cancel while the question is open (e.g. the window closes it)
            return false;
        };
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 5));
        await(() -> !p.isRunning(), "the run ended");
        String status = FxTestSupport.callOnFx(p::statusForTest);
        assertFalse(status.startsWith(cancelledPrefix()), "nothing was sent, so nothing ran for a while: " + status);
        assertEquals(HttpClientPanel.BodyView.PREVIOUS, FxTestSupport.callOnFx(p::bodyViewForTest));
        assertTrue(FxTestSupport.callOnFx(p::previousRowForTest).endsWith("first"));
        assertFalse(FxTestSupport.callOnFx(() -> p.controlsForTest().progress().isVisible()));
        assertEquals(List.of("json"), hits, "the request was never sent");
    }

    // --- U9 -------------------------------------------------------------------------------------------------------

    @Test
    void aRefusedConnectionShowsTheCardOnBodyWithRunAgain(@TempDir Path dir) throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        String nobody = "http://127.0.0.1:" + closed + "/nothing";
        EditorBuffer b =
                open(dir.resolve("api.http"), "### Json\nGET " + url("/json") + "\n\n### Nobody\nGET " + nobody + "\n");
        HttpClientPanel p = panel(b);
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 1));
        host.awaitStatus(s -> s.startsWith(tr("status.http.finished", "GET " + url("/json"), "")));
        FxTestSupport.runOnFx(() -> p.tabsForTest().show(HttpResponseTab.HEADERS)); // as if picked by hand
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 4));
        host.awaitStatus(s -> s.startsWith(tr("status.http.failedError", "GET " + nobody, "")));

        HttpErrorCard card = p.errorCardForTest();
        assertEquals(HttpClientPanel.BodyView.ERROR, FxTestSupport.callOnFx(p::bodyViewForTest));
        assertEquals(HttpResponseTab.BODY, FxTestSupport.callOnFx(p::selectedTabForTest), "decision 4: the card");
        assertEquals(HttpFailureKind.REFUSED, FxTestSupport.callOnFx(card::kindForTest));
        assertEquals(tr("httppanel.failure.refused"), FxTestSupport.callOnFx(card::titleForTest));
        String why = FxTestSupport.callOnFx(card::explanationForTest);
        assertTrue(why.contains("127.0.0.1:" + closed), why);
        assertFalse(FxTestSupport.callOnFx(() -> card.copyCurlForTest().isDisabled()), "it was sent: copyable");
        assertTrue(FxTestSupport.callOnFx(() -> card.isVisible()));

        host.statuses.clear();
        FxTestSupport.runOnFx(() -> card.runAgainForTest().fire());
        assertEquals(tr("status.http.running", "GET " + nobody), host.awaitStatus());
        host.awaitStatus(s -> s.startsWith(tr("status.http.failedError", "GET " + nobody, "")));
    }

    // --- support ------------------------------------------------------------------------------------------------

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

        String awaitStatus() throws InterruptedException {
            return awaitStatus(s -> true);
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

    private static final class Ops implements HttpClientCoordinator.WindowOps {
        final com.editora.config.HttpResponseHistory store;
        volatile java.util.function.Supplier<Boolean> onScripts = () -> false;

        Ops(Path configDir) {
            store = new com.editora.config.HttpResponseHistory(configDir);
        }

        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void updateRunGating() {}

        @Override
        public com.editora.config.HttpResponseHistory historyStore() {
            return store;
        }

        @Override
        public boolean allowScripts(Path file) {
            return onScripts.get();
        }
    }
}
