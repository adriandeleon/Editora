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

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.http.HttpExchange;
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
 * Running requests from a {@code .http} buffer against a server on 127.0.0.1: one request, the whole file,
 * environments, cancelling and superseding a run, and what the response viewer's actions do with the result.
 */
@Tag("fx")
class HttpClientRunFlowFxTest {

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
            hits.add("json " + ex.getRequestHeaders().getFirst("X-Token"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            hits.add("missing");
            byte[] body = "nope".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(404, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/bytes", ex -> {
            byte[] body = {0, 1, 2, 3, 0, (byte) 0xff};
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
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
        releaseSlow.countDown();
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** A saved {@code .http} buffer with its response panel attached, as the window sets it up. */
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
        buffers.add(b);
        return b;
    }

    private HttpClientPanel panel(EditorBuffer b) {
        return coordinator.panelForTest(b);
    }

    private String panelStatus(EditorBuffer b) throws Exception {
        Label status = FxTestSupport.field(panel(b), "status");
        return FxTestSupport.callOnFx(status::getText);
    }

    @Test
    void runningTheRequestAtTheCaretShowsItsResponse(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "@token = s3cr3t\n\n### first\nGET " + url("/json") + "\nX-Token: {{token}}\n\n### second\nGET "
                        + url("/missing") + "\n");
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(3, 0);
            coordinator.runRequestAtCaret();
        });
        String label = "GET " + url("/json");
        assertEquals(tr("status.http.running", label), host.awaitStatus());
        assertEquals(tr("status.http.done", label), host.awaitStatus());

        assertEquals(List.of("json s3cr3t"), hits, "the file's own variable was filled in");
        assertFalse(coordinator.isRunningForTest(b));
        HttpExchange shown = FxTestSupport.callOnFx(() -> panel(b).getSelectedExchange());
        assertEquals(200, shown.result().status());
        assertTrue(FxTestSupport.callOnFx(() -> panel(b).getResponseText()).contains("{\"ok\":true}"));
        assertEquals(tr("httppanel.done"), panelStatus(b));
        assertTrue(FxTestSupport.callOnFx(b::hasHttpPreview));

        // The second request answers 404: the same flow, reported as a failure.
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 7));
        host.awaitStatus();
        assertEquals(tr("status.http.failed", 404), host.awaitStatus());
        assertEquals(tr("httppanel.failed", 1), panelStatus(b));
        // Both are in the history, newest first.
        ComboBox<HttpExchange> history = FxTestSupport.field(panel(b), "historyCombo");
        assertEquals(
                List.of(404, 200),
                FxTestSupport.callOnFx(() -> history.getItems().stream()
                        .map(ex -> ex.result().status())
                        .toList()));
        // Picking the older one shows it again.
        FxTestSupport.runOnFx(() -> history.setValue(history.getItems().get(1)));
        assertEquals(
                200,
                FxTestSupport.callOnFx(() -> panel(b).getSelectedExchange())
                        .result()
                        .status());
    }

    @Test
    void aRequestCannotRunWhenDisabledUnsavedOrAwayFromAnyRequest(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "# only a comment\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        assertEquals(tr("status.http.noRequest"), host.last);
        FxTestSupport.runOnFx(coordinator::runFile);
        assertEquals(tr("status.http.noRequest"), host.last, "a file without requests");

        FxTestSupport.runOnFx(() -> coordinator.runRequest(null, 0));
        assertEquals(tr("status.http.saveFirst"), host.last);
        EditorBuffer unsaved = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setDisplayName("scratch.http");
            created.setContent("GET " + url("/json") + "\n");
            return created;
        });
        buffers.add(unsaved);
        host.active = unsaved;
        FxTestSupport.runOnFx(() -> coordinator.runRequest(unsaved, 0));
        assertEquals(tr("status.http.saveFirst"), host.last);
        FxTestSupport.runOnFx(coordinator::runFile);
        assertEquals(tr("status.http.saveFirst"), host.last);

        // Not an .http buffer at all.
        EditorBuffer plain = FxTestSupport.callOnFx(EditorBuffer::new);
        buffers.add(plain);
        host.active = plain;
        FxTestSupport.runOnFx(() -> {
            coordinator.runRequestAtCaret();
            coordinator.runFile();
            coordinator.selectEnvironment();
        });
        assertEquals(tr("status.http.noRequest"), host.last);
        host.active = null;
        FxTestSupport.runOnFx(coordinator::runFile);
        assertEquals(tr("status.http.noRequest"), host.last);

        host.settings.setHttpClientSupport(false);
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        assertEquals(tr("statusbar.tip.httpDisabled"), host.last);
        assertEquals(List.of(), hits);
    }

    @Test
    void runningTheFileUsesTheSelectedEnvironmentWithPrivateValuesOnTop(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("http-client.env.json"),
                "{\"dev\":{\"base\":\"" + url("")
                        + "\",\"token\":\"public\"},\"prod\":{\"base\":\"http://127.0.0.1:1\"}}");
        Files.writeString(dir.resolve("http-client.private.env.json"), "{\"dev\":{\"token\":\"private\"}}");
        ops.saved = "dev";
        EditorBuffer b = httpBuffer(
                dir.resolve("all.http"),
                "### one\nGET {{base}}/json\nX-Token: {{token}}\n\n### two\nGET {{base}}/missing\n");

        ComboBox<String> env = FxTestSupport.field(panel(b), "envCombo");
        assertEquals(List.of("", "dev", "prod"), FxTestSupport.callOnFx(() -> List.copyOf(env.getItems())));
        assertEquals("dev", FxTestSupport.callOnFx(() -> panel(b).getSelectedEnvironment()));
        assertNull(ops.persisted, "restoring the saved environment is not a change to save");

        FxTestSupport.runOnFx(coordinator::runFile);
        assertEquals(tr("status.http.running", "all.http"), host.awaitStatus());
        assertEquals(tr("status.http.failed", 2), host.awaitStatus(), "one of the two requests failed");
        assertEquals(List.of("json private", "missing"), hits);
        ComboBox<HttpExchange> history = FxTestSupport.field(panel(b), "historyCombo");
        assertEquals(2, FxTestSupport.callOnFx(() -> history.getItems().size()));

        // Choosing another environment in the picker is remembered, and the next run resolves against it.
        FxTestSupport.runOnFx(() -> env.setValue("prod"));
        assertEquals("prod", ops.persisted);
        FxTestSupport.runOnFx(() -> env.setValue(null));
        assertEquals("", ops.persisted);
        assertEquals("", FxTestSupport.callOnFx(() -> panel(b).getSelectedEnvironment()));

        // The command reveals the response panel and refreshes the list from disk.
        Files.writeString(dir.resolve("http-client.env.json"), "{\"only\":{}}");
        FxTestSupport.runOnFx(coordinator::selectEnvironment);
        assertEquals(List.of("", "only"), FxTestSupport.callOnFx(() -> List.copyOf(env.getItems())));
    }

    @Test
    void aFileWhoseRequestsAllSucceedReportsDone(@TempDir Path dir) throws Exception {
        EditorBuffer b =
                httpBuffer(dir.resolve("ok.http"), "GET " + url("/json") + "\n\n###\nGET " + url("/json") + "\n");
        FxTestSupport.runOnFx(coordinator::runFile);
        host.awaitStatus();
        assertEquals(tr("status.http.done", "ok.http"), host.awaitStatus());
        assertEquals(tr("httppanel.done"), panelStatus(b));
        assertEquals(2, hits.size());
    }

    @Test
    void cancellingStopsTheRunAndALateResultIsNotShown(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("slow.http"), "GET " + url("/slow") + "\n");
        FxTestSupport.runOnFx(coordinator::cancelActiveRequest);
        assertEquals(tr("status.http.nothingRunning"), host.last);

        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(coordinator.isRunningForTest(b));
        Button cancel = FxTestSupport.field(panel(b), "cancelButton");
        assertFalse(FxTestSupport.callOnFx(cancel::isDisabled));

        FxTestSupport.runOnFx(cancel::fire); // the panel's own Cancel
        assertEquals(tr("status.http.cancelled"), host.last);
        assertFalse(coordinator.isRunningForTest(b));
        assertEquals(tr("httppanel.cancelled"), panelStatus(b));
        assertTrue(FxTestSupport.callOnFx(cancel::isDisabled));
        assertNull(FxTestSupport.callOnFx(() -> panel(b).getSelectedExchange()), "the cancelled run shows nothing");

        FxTestSupport.runOnFx(() -> coordinator.cancelRun(null));
        assertEquals(tr("status.http.nothingRunning"), host.last);
    }

    @Test
    void aNewRunReplacesTheOneInFlight(@TempDir Path dir) throws Exception {
        EditorBuffer b =
                httpBuffer(dir.resolve("two.http"), "GET " + url("/slow") + "\n\n###\nGET " + url("/json") + "\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        host.statuses.clear();

        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 3));
        assertEquals(tr("status.http.running", "GET " + url("/json")), host.awaitStatus());
        assertEquals(tr("status.http.done", "GET " + url("/json")), host.awaitStatus());
        ComboBox<HttpExchange> history = FxTestSupport.field(panel(b), "historyCombo");
        assertEquals(
                List.of(url("/json")),
                FxTestSupport.callOnFx(
                        () -> history.getItems().stream().map(HttpExchange::url).toList()),
                "only the run that replaced it is shown");
    }

    @Test
    void closingTheBufferCancelsItsRunAndDropsItsPanel(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("slow.http"), "GET " + url("/slow") + "\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        FxTestSupport.runOnFx(() -> coordinator.onBufferClosed(b));
        assertFalse(coordinator.isRunningForTest(b));
        assertNull(panel(b));
    }

    @Test
    void theSelectedResponseCanBeCopiedAsCurlOrOpenedInATab(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(
                dir.resolve("api.http"),
                "POST " + url("/json") + "\nX-Token: abc\n\npayload\n\n###\nGET " + url("/bytes") + "\n");
        // Nothing has been run yet.
        FxTestSupport.runOnFx(() -> {
            coordinator.copyActiveAsCurl();
            coordinator.openActiveResponseInTab();
        });
        assertEquals(tr("status.http.noResponse"), host.last);
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(coordinator, "saveResponse", EditorBuffer.class, b));
        assertEquals(tr("status.http.noResponse"), host.last);
        assertEquals(List.of(), ops.opened);

        host.statuses.clear();
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        host.awaitStatus();
        host.awaitStatus();

        FxTestSupport.runOnFx(coordinator::copyActiveAsCurl);
        assertEquals(tr("status.http.curlCopied"), host.last);
        String curl =
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
        assertTrue(curl.startsWith("curl"), curl);
        assertTrue(curl.contains(url("/json")), curl);
        assertTrue(curl.contains("X-Token: abc"), curl);
        assertTrue(curl.contains("payload"), curl);

        FxTestSupport.runOnFx(coordinator::openActiveResponseInTab);
        assertEquals(1, ops.opened.size());
        EditorBuffer tab = ops.opened.get(0);
        buffers.add(tab);
        assertEquals("{\"ok\":true}", FxTestSupport.callOnFx(tab::getContent), "the body as received");
        assertEquals("response.json", FxTestSupport.callOnFx(tab::getTitle));

        // The panel's own buttons do the same for the response on show.
        Button openInTab = FxTestSupport.field(panel(b), "openTabButton");
        Button copyCurl = FxTestSupport.field(panel(b), "copyCurlButton");
        FxTestSupport.runOnFx(() -> {
            openInTab.fire();
            copyCurl.fire();
        });
        assertEquals(2, ops.opened.size());
        buffers.add(ops.opened.get(1));

        // A binary response has no text form to open.
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 6));
        host.statuses.clear();
        assertEquals(tr("status.http.done", "GET " + url("/bytes")), host.awaitStatus(s -> !s.startsWith("Running")));
        assertTrue(FxTestSupport.callOnFx(openInTab::isDisabled));
        FxTestSupport.runOnFx(coordinator::openActiveResponseInTab);
        assertEquals(tr("status.http.binaryResponse"), host.last);
        assertEquals(2, ops.opened.size());

        // Clear empties the viewer and its actions.
        Button clear = FxTestSupport.field(panel(b), "clearButton");
        FxTestSupport.runOnFx(clear::fire);
        assertNull(FxTestSupport.callOnFx(() -> panel(b).getSelectedExchange()));
        assertEquals("", FxTestSupport.callOnFx(() -> panel(b).getResponseText()));
        assertTrue(FxTestSupport.callOnFx(copyCurl::isDisabled));
        assertEquals(tr("httppanel.idle"), panelStatus(b));
    }

    @Test
    void aRequestThatCouldNotBeSentHasNoResponseToOpen(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("bad.http"), "POST " + url("/json") + "\n\n< ./no-such-body.json\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        host.awaitStatus();
        assertEquals(tr("status.http.failed", 0), host.awaitStatus());
        FxTestSupport.runOnFx(coordinator::openActiveResponseInTab);
        assertEquals(tr("status.http.noResponse"), host.last);
        assertEquals(List.of(), ops.opened);
        assertEquals(List.of(), hits);
    }

    @Test
    void aCurlCommandOnTheClipboardBecomesARequest(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "GET https://example.test/a\n");
        setClipboard("ls -la");
        FxTestSupport.runOnFx(coordinator::importCurl);
        assertEquals(tr("status.http.notCurl"), host.last);
        setClipboard("   ");
        FxTestSupport.runOnFx(coordinator::importCurl);
        assertEquals(tr("status.http.notCurl"), host.last);

        setClipboard("  curl -X PUT https://example.test/b -H 'Accept: text/plain'  ");
        FxTestSupport.runOnFx(coordinator::importCurl);
        assertEquals(tr("status.http.curlImported"), host.last);
        assertEquals(
                "GET https://example.test/a\n\n###\nPUT https://example.test/b\nAccept: text/plain\n\n",
                FxTestSupport.callOnFx(b::getContent),
                "appended to the open .http file as a new request");
        assertEquals(List.of(), ops.opened);

        // An empty .http file gets no leading blank line.
        EditorBuffer empty = httpBuffer(dir.resolve("empty.http"), "");
        FxTestSupport.runOnFx(coordinator::importCurl);
        assertTrue(FxTestSupport.callOnFx(empty::getContent).startsWith("###\nPUT "));

        // Anywhere else, the request opens in a new tab.
        EditorBuffer plain = FxTestSupport.callOnFx(EditorBuffer::new);
        buffers.add(plain);
        host.active = plain;
        FxTestSupport.runOnFx(coordinator::importCurl);
        assertEquals(1, ops.opened.size());
        EditorBuffer tab = ops.opened.get(0);
        buffers.add(tab);
        assertEquals("PUT https://example.test/b\nAccept: text/plain\n", FxTestSupport.callOnFx(tab::getContent));
        assertTrue(FxTestSupport.callOnFx(tab::isHttpFile));
        host.active = null;
        FxTestSupport.runOnFx(coordinator::importCurl);
        assertEquals(2, ops.opened.size());
        buffers.add(ops.opened.get(1));
    }

    @Test
    void aRedirectOverAnExistingFileGoesAheadWithoutLocalHistoryAndSaysItWasNotKept(@TempDir Path dir)
            throws Exception {
        Path target = Files.writeString(dir.resolve("out.json"), "previous download");
        EditorBuffer b = httpBuffer(dir.resolve("save.http"), "GET " + url("/json") + "\n\n>>! out.json\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        host.awaitStatus();
        host.awaitStatus();
        assertEquals("{\"ok\":true}", Files.readString(target));
        HttpExchange shown = FxTestSupport.callOnFx(() -> panel(b).getSelectedExchange());
        assertEquals(
                1, shown.result().written().size(), shown.result().written().toString());
    }

    @Test
    void aRedirectIsRefusedWhileItsTargetIsOpenWithUnsavedChanges(@TempDir Path dir) throws Exception {
        Path target = Files.writeString(dir.resolve("out.json"), "on disk");
        EditorBuffer open = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(target);
            created.setContent("on disk");
            created.markClean();
            created.replaceWholeDocument("typed but not saved");
            host.buffers.add(created);
            return created;
        });
        buffers.add(open);
        EditorBuffer b = httpBuffer(dir.resolve("save.http"), "GET " + url("/json") + "\n\n>>! out.json\n");
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 0));
        host.awaitStatus();
        host.awaitStatus();
        assertEquals("on disk", Files.readString(target));
        HttpExchange shown = FxTestSupport.callOnFx(() -> panel(b).getSelectedExchange());
        assertEquals(List.of(), shown.result().written());
        assertTrue(
                shown.result().warnings().stream().anyMatch(w -> w.contains("out.json")),
                shown.result().warnings().toString());
    }

    @Test
    void switchingTheFeatureOffRemovesThePanelsAndRegatesTheBuffers(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), "GET " + url("/json") + "\n");
        host.settings.setFontFamily("Monospaced");
        host.settings.setFontSize(19);
        FxTestSupport.runOnFx(coordinator::applySupport);
        assertNotNull(panel(b));
        assertEquals(1, ops.gatings);

        host.settings.setHttpClientSupport(false);
        FxTestSupport.runOnFx(coordinator::applySupport);
        assertNull(panel(b));
        assertFalse(FxTestSupport.callOnFx(b::hasHttpPreview));
        assertEquals(2, ops.gatings);
        FxTestSupport.runOnFx(() -> {
            coordinator.refreshFor(null);
            coordinator.refreshFor(b);
            coordinator.onBufferClosed(null);
            coordinator.refreshEnvironments(b); // no panel: nothing to refresh
        });
        assertNull(panel(b));
    }

    private static void setClipboard(String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
        });
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

        String awaitStatus() throws InterruptedException {
            return awaitStatus(s -> true);
        }

        String awaitStatus(java.util.function.Predicate<String> wanted) throws InterruptedException {
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
        final List<EditorBuffer> opened = new CopyOnWriteArrayList<>();
        volatile String saved = "";
        volatile String persisted;
        volatile int gatings;

        @Override
        public void openTab(EditorBuffer buffer) {
            opened.add(buffer);
        }

        @Override
        public void updateRunGating() {
            gatings++;
        }

        @Override
        public String savedEnvironment() {
            return saved;
        }

        @Override
        public void persistEnvironment(String env) {
            persisted = env;
        }
    }
}
