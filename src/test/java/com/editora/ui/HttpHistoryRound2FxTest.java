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

import javafx.scene.input.Clipboard;

import com.editora.config.HttpResponseHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saved response history as the user and the server meet it, against a 127.0.0.1 server: what Copy as
 * cURL puts on the clipboard for a restored entry, what is on disk after two windows run the same file, what a
 * cancelled Run All leaves in History, what happens to a history when its file is renamed or deleted, and
 * where the running ■ goes when the requests move.
 */
@Tag("fx")
class HttpHistoryRound2FxTest {

    private static final long WAIT_SECONDS = 30;
    private static final byte[] LATIN = "café crème".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path work;

    @TempDir
    Path config;

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch slowStarted = new CountDownLatch(1);
    private volatile CountDownLatch releaseSlow = new CountDownLatch(1);
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private final List<HttpClientCoordinator> coordinators = new ArrayList<>();
    private HttpResponseHistory store;

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
            ex.getRequestBody().readAllBytes();
            hits.add(ex.getRequestMethod() + " " + path);
            if (path.equals("/slow")) {
                slowStarted.countDown();
                try {
                    releaseSlow.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.equals("/digest") && ex.getRequestHeaders().getFirst("Authorization") == null) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"r\", qop=\"auth\", nonce=\"n1\"");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            if (path.equals("/redirect")) {
                ex.getResponseHeaders().add("Location", "/latin");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] body =
                    path.equals("/latin") ? LATIN : ("{\"path\":\"" + path + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders()
                    .add("Content-Type", path.equals("/latin") ? "text/plain; charset=ISO-8859-1" : "application/json");
            try {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } catch (java.io.IOException cancelled) {
                // the client went away
            }
            ex.close();
        });
        server.start();
        store = new HttpResponseHistory(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseSlow.countDown();
        coordinators.forEach(HttpClientCoordinator::shutdown);
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private HttpClientCoordinator coordinator(Host host) {
        HttpClientCoordinator c = new HttpClientCoordinator(host, new Ops(store));
        coordinators.add(c);
        return c;
    }

    private EditorBuffer open(Host host, HttpClientCoordinator c, Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            created.setHttpEnabled(true);
            host.buffers.add(created);
            host.active = created;
            c.ensureHttpPreview(created);
            return created;
        });
        buffers.add(b);
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        return b;
    }

    private static void await(java.util.concurrent.Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!FxTestSupport.callOnFx(check)) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    /** Runs request {@code line} of {@code b} and waits until it has landed and been saved. */
    private void run(Host host, HttpClientCoordinator c, EditorBuffer b, int line) throws Exception {
        host.statuses.clear();
        FxTestSupport.runOnFx(() -> {
            host.active = b;
            c.runRequest(b, line);
        });
        host.awaitStatus(s -> !s.startsWith(tr("status.http.running", "").strip()) && !s.startsWith("Running"));
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
    }

    private List<String> savedUrls(Path file) {
        return store.load(file).responses.stream()
                .map(r -> r.url().replace(url(""), ""))
                .toList();
    }

    @Test
    void copyAsCurlOfARestoredEntrySendsWhatWasSentNotTheSummary() throws Exception {
        Files.write(work.resolve("pic.bin"), new byte[3000]);
        Files.write(work.resolve("big.bin"), new byte[2_000_000]);
        Path file = work.resolve("api.http");
        String text = "### form\nPOST " + url("/up") + "\nContent-Type: multipart/form-data; boundary=BB\n\n"
                + "--BB\nContent-Disposition: form-data; name=\"f\"; filename=\"pic.bin\"\n\n< ./pic.bin\n--BB--\n\n"
                + "### put\nPUT " + url("/up") + " HTTP/1.1\nContent-Type: application/octet-stream\n\n< ./big.bin\n\n"
                + "### digest\nGET " + url("/digest") + "\nAuthorization: Digest ann FAKE-PASS\n";
        Host first = new Host();
        HttpClientCoordinator a = coordinator(first);
        EditorBuffer b = open(first, a, file, text);
        run(first, a, b, 1);
        run(first, a, b, 11);
        run(first, a, b, 17);
        String onDisk = Files.readString(store.fileFor(file));
        assertFalse(onDisk.contains("FAKE-PASS"), "the Digest password is never saved");
        assertTrue(onDisk.contains("\"digestUser\":\"ann\""), onDisk);

        // A restart: a new window reads the saved history.
        Host second = new Host();
        HttpClientCoordinator c = coordinator(second);
        EditorBuffer again = open(second, c, file, text);
        HttpClientPanel p = c.panelForTest(again);
        await(() -> p.history().size() == 3);
        List<String> curls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int index = i;
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<javafx.scene.control.ComboBox<HttpHistoryEntry>>field(p, "historyCombo")
                        .setValue(p.history().get(index));
                c.copyActiveAsCurl();
            });
            assertEquals(tr("status.http.curlCopied"), second.last);
            curls.add(
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        }
        String digest = curls.get(0);
        String put = curls.get(1);
        String form = curls.get(2);
        assertTrue(digest.contains("--digest -u 'ann'"), "curl asks for the password: " + digest);
        assertFalse(digest.contains("FAKE-PASS"), digest);
        assertTrue(put.contains("--data-binary '@" + work.resolve("big.bin").toAbsolutePath()), put);
        assertTrue(put.contains("--http1.1"), put);
        assertFalse(put.contains("(body from"), "never the summary as the body: " + put);
        assertTrue(form.contains("-F 'f=@\"" + work.resolve("pic.bin").toAbsolutePath()), form);
        assertFalse(form.contains("--data-raw"), form);
    }

    @Test
    void aRestoredResponseKeepsItsBytesProtocolAndRedirect() throws Exception {
        Path file = work.resolve("latin.http");
        Host host = new Host();
        HttpClientCoordinator a = coordinator(host);
        EditorBuffer b = open(host, a, file, "GET " + url("/redirect") + "\n");
        run(host, a, b, 0);
        Host later = new Host();
        HttpClientCoordinator c = coordinator(later);
        EditorBuffer again = open(later, c, file, "GET " + url("/redirect") + "\n");
        HttpClientPanel p = c.panelForTest(again);
        await(() -> p.history().size() == 1);
        HttpHistoryEntry e = FxTestSupport.callOnFx(() -> p.history().get(0));
        assertArrayEquals(LATIN, e.result().rawBody(), "Save writes the bytes received, not UTF-8");
        assertEquals("HTTP/1.1", e.result().protocol());
        assertEquals(url("/latin"), e.exchange().details().finalUrl());
    }

    @Test
    void anEntryFromAnOlderVersionIsNotCopiedAsItsSummary() throws Exception {
        Path file = work.resolve("old.http");
        HttpResponseHistory.Stored v1 = new HttpResponseHistory.Stored();
        v1.file = file.toString();
        v1.responses.add(new HttpResponseHistory.Response(
                5,
                "up",
                0,
                "POST",
                url("/up"),
                List.of(),
                "(multipart/form-data, 3080 bytes)",
                200,
                List.of(),
                "{}",
                false,
                false,
                "application/json",
                1,
                2,
                null,
                List.of(),
                false));
        store.save(file, v1);
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "POST " + url("/up") + "\n");
        HttpClientPanel p = c.panelForTest(b);
        await(() -> p.getSelectedExchange() != null);
        FxTestSupport.runOnFx(() -> Clipboard.getSystemClipboard().clear());
        FxTestSupport.runOnFx(c::copyActiveAsCurl);
        assertEquals(tr("status.http.curlUnavailable"), host.last);
        assertFalse(FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().hasString()), "nothing was copied");
    }

    @Test
    void twoWindowsOnOneFileKeepEachOthersResponsesAndAClearStaysCleared() throws Exception {
        Path file = work.resolve("two.http");
        String text = "### a\nGET " + url("/a") + "\n\n### b\nGET " + url("/b") + "\n";
        Host hostA = new Host();
        Host hostB = new Host();
        HttpClientCoordinator a = coordinator(hostA);
        HttpClientCoordinator b = coordinator(hostB);
        EditorBuffer inA = open(hostA, a, file, text);
        EditorBuffer inB = open(hostB, b, file, text);
        run(hostA, a, inA, 1);
        run(hostB, b, inB, 4);
        run(hostA, a, inA, 1);
        assertEquals(List.of("/a", "/b", "/a"), savedUrls(file), "no window's save dropped the other's response");

        FxTestSupport.runOnFx(() -> FxTestSupport.call(a.panelForTest(inA), "clear", new Class<?>[0]));
        HttpHistorySync.awaitIdleForTest();
        assertEquals(List.of(), savedUrls(file));
        run(hostB, b, inB, 4); // B still shows the old ones: they must not come back
        assertEquals(List.of("/b"), savedUrls(file), "only the run after the Clear is kept");
    }

    @Test
    void cancellingARunAllKeepsTheResponsesThatFinished() throws Exception {
        Path file = work.resolve("all.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(
                host,
                c,
                file,
                "### create\nPOST " + url("/create") + "\n\n### slow\nGET " + url("/slow") + "\n\n### third\nGET "
                        + url("/third") + "\n");
        FxTestSupport.runOnFx(c::runFile);
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        FxTestSupport.runOnFx(() -> c.cancelRun(b));
        await(() -> c.panelForTest(b).history().size() == 1);
        HttpHistorySync.awaitIdleForTest();
        HttpHistoryEntry kept =
                FxTestSupport.callOnFx(() -> c.panelForTest(b).history().get(0));
        assertEquals(url("/create"), kept.exchange().url(), "the POST the server applied is in History");
        assertEquals(List.of("/create"), savedUrls(file), "…and saved");
        assertEquals(tr("status.http.cancelledAfter", 1, 3), host.last);
        assertFalse(hits.contains("GET /third"), "the rest was not started");
    }

    @Test
    void aRestoredBodyCutByTheHistoryDoesNotClaimToBeWhole() throws Exception {
        Path file = work.resolve("cut.http");
        HttpResponseHistory.Stored st = new HttpResponseHistory.Stored();
        st.file = file.toString();
        String cut = "x".repeat(HttpResponseHistory.MAX_BODY_CHARS);
        st.responses.add(new HttpResponseHistory.Response(
                9,
                "big",
                0,
                "GET",
                url("/big"),
                List.of(),
                "",
                200,
                List.of(),
                cut,
                true,
                false,
                "text/plain",
                5,
                3_000_000,
                null,
                List.of(),
                false,
                "HTTP/1.1",
                "UTF-8",
                null));
        store.save(file, st);
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "GET " + url("/big") + "\n");
        HttpClientPanel p = c.panelForTest(b);
        CodeArea body = FxTestSupport.field(p, "bodyArea");
        await(() -> body.getText().contains("saved history kept only"));
        String shown = FxTestSupport.callOnFx(body::getText);
        assertFalse(shown.contains("has the full body"), shown.substring(shown.length() - 300));
        assertFalse(shown.contains("were downloaded"), "the whole response was downloaded");
    }

    @Test
    void aRenamedFilesHistoryMovesAndADeletedFilesHistoryGoes() throws Exception {
        Path file = work.resolve("old.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "GET " + url("/a") + "\n");
        run(host, c, b, 0);
        Path oldHistory = store.fileFor(file);
        assertTrue(Files.exists(oldHistory));

        Path renamed = work.resolve("new.http");
        Files.move(file, renamed);
        FxTestSupport.runOnFx(() -> {
            b.setPath(renamed);
            c.ensureHttpPreview(b);
        });
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
        assertEquals(List.of("/a"), savedUrls(renamed), "the history moved with the file");
        assertFalse(Files.exists(oldHistory), "the old name's history is gone");

        // Save As keeps the original's history: that file is still there.
        Path copy = work.resolve("copy.http");
        Files.copy(renamed, copy);
        FxTestSupport.runOnFx(() -> {
            b.setPath(copy);
            c.ensureHttpPreview(b);
        });
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
        assertTrue(Files.exists(store.fileFor(renamed)));
        assertEquals(List.of("/a"), savedUrls(copy));

        // Deleting the file and closing its tab deletes its history.
        Files.delete(copy);
        FxTestSupport.runOnFx(() -> c.onBufferClosed(b));
        HttpHistorySync.awaitIdleForTest();
        assertFalse(Files.exists(store.fileFor(copy)), "no orphan with response bodies is left behind");
    }

    @Test
    void oneDamagedSavedEntryIsLeftOutAndTheWindowSaysSo() throws Exception {
        Path file = work.resolve("damaged.http");
        Files.createDirectories(store.fileFor(file).getParent());
        Files.writeString(
                store.fileFor(file),
                "{\"schemaVersion\":1,\"file\":\"" + file.toString().replace("\\", "\\\\") + "\",\"environment\":\"\","
                        + "\"responses\":[{\"at\":3,\"method\":\"GET\",\"url\":\"http://h/good1\",\"status\":200,\"body\":\"x\"},"
                        + "{\"at\":2,\"method\":\"GET\",\"url\":\"http://h/bad\",\"status\":\"two hundred\"},"
                        + "{\"at\":1,\"method\":\"GET\",\"url\":\"http://h/good2\",\"status\":200,\"body\":\"y\"}]}");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "GET " + url("/a") + "\n");
        await(() -> c.panelForTest(b).history().size() == 2);
        assertTrue(
                host.errors.contains(tr("status.http.historyPartlyUnreadable", 1, "damaged.http")),
                host.errors.toString());
    }

    @Test
    void theRunningMarkFollowsItsRequestWhenALineIsInsertedAbove() throws Exception {
        Path file = work.resolve("mark.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "### fast\nGET " + url("/a") + "\n\n### slow\nGET " + url("/slow") + "\n");
        FxTestSupport.runOnFx(() -> c.runRequest(b, 4));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(4, FxTestSupport.callOnFx(b::httpRunningLine));
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "### added\nDELETE " + url("/x") + "\n\n"));
        await(() -> b.httpRunningLine() == 7);
        assertTrue(FxTestSupport.callOnFx(() -> c.isRunningForTest(b)));
    }

    @Test
    void credentialKeysAreMaskedOnDisk() throws Exception {
        Path file = work.resolve("keys.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(
                host,
                c,
                file,
                "GET " + url("/a")
                        + "\nX-Auth-Key: FAKE-CF\nOcp-Apim-Subscription-Key: FAKE-AZ\nX-Session-Id: FAKE-S\n");
        run(host, c, b, 0);
        String onDisk = Files.readString(store.fileFor(file));
        assertFalse(onDisk.contains("FAKE-"), onDisk);
    }

    @Test
    void theExportDialogLeavesTheHttpHistoryOutUnlessTicked() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.Dialog<Boolean> d = ExportConfigDialog.build(null);
            javafx.scene.control.CheckBox box =
                    (javafx.scene.control.CheckBox) d.getDialogPane().lookup("#include-http-history");
            assertFalse(box.isSelected(), "off by default");
            assertEquals(tr("dialog.exportConfig.includeHttpHistory"), box.getText());
            javafx.scene.control.ButtonType export =
                    d.getDialogPane().getButtonTypes().get(0);
            assertEquals(false, d.getResultConverter().call(export));
            box.setSelected(true);
            assertEquals(true, d.getResultConverter().call(export));
            assertEquals(null, d.getResultConverter().call(javafx.scene.control.ButtonType.CANCEL), "cancelled");
        });
    }

    private static final class Ops implements HttpClientCoordinator.WindowOps {
        final HttpResponseHistory store;

        Ops(HttpResponseHistory store) {
            this.store = store;
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
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile EditorBuffer active;
        volatile String last;

        Host() {
            settings.setHttpClientSupport(true);
        }

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

        @Override
        public void setError(String message) {
            errors.add(message);
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
