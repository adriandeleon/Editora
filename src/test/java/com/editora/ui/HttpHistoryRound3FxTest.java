package com.editora.ui;

import java.io.File;
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

import javafx.application.Platform;
import javafx.scene.input.Clipboard;

import com.editora.config.HttpResponseHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.http.HttpClientService;
import com.editora.http.ResponseRedirects;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The third review's history, cancel, re-run and Copy as cURL fixes, checked where the user meets them: what a
 * 127.0.0.1 server receives (from Editora and from the copied command, run by the real curl), what the saved history
 * holds on disk, and what the status bar says.
 */
@Tag("fx")
class HttpHistoryRound3FxTest {

    private static final long WAIT_SECONDS = 30;
    private static final Path CURL = Path.of("/usr/bin/curl");

    @TempDir
    Path work;

    @TempDir
    Path config;

    private HttpServer server;
    /** What the server got: "METHOD /path ct=<content type> body=<body>", the body's boundary replaced by B. */
    private final List<String> hits = new CopyOnWriteArrayList<>();

    private final List<String> protocols = new CopyOnWriteArrayList<>();

    private volatile CountDownLatch slow = new CountDownLatch(1);
    private final List<HttpClientCoordinator> coordinators = new ArrayList<>();
    private final List<EditorBuffer> buffers = new ArrayList<>();
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
            byte[] in = ex.getRequestBody().readAllBytes();
            String ct = String.valueOf(ex.getRequestHeaders().getFirst("Content-Type"));
            String body = new String(in, StandardCharsets.UTF_8);
            int b = ct.indexOf("boundary=");
            if (b >= 0) {
                body = body.replace(ct.substring(b + 9).replace("\"", ""), "B").replace("\r\n", "\n");
                ct = ct.substring(0, b) + "boundary=B";
            }
            hits.add(ex.getRequestMethod() + " " + path + " ct=" + ct + " body=" + body);
            protocols.add(ex.getProtocol());
            if (path.equals("/slow")) {
                try {
                    slow.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.equals("/r302")) {
                ex.getResponseHeaders().add("Location", "/landed");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] out = ("{\"path\":\"" + path + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
            } catch (java.io.IOException gone) {
                // the client went away
            }
            ex.close();
        });
        server.start();
        store = new HttpResponseHistory(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        slow.countDown();
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

    /** Opens {@code text} as {@code file}; does not wait for its saved history to be read. */
    private EditorBuffer openNow(Host host, HttpClientCoordinator c, Path file, String text) throws Exception {
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
        HttpEnvCache.awaitIdleForTest(); // the env files are read off the FX thread
        buffers.add(b);
        return b;
    }

    private EditorBuffer open(Host host, HttpClientCoordinator c, Path file, String text) throws Exception {
        EditorBuffer b = openNow(host, c, file, text);
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        return b;
    }

    private static void await(java.util.concurrent.Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!FxTestSupport.callOnFx(check)) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }

    private void run(HttpClientCoordinator c, EditorBuffer b, int line) throws Exception {
        FxTestSupport.runOnFx(() -> c.runRequest(b, line));
        await(() -> !c.isRunningForTest(b));
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
    }

    private void awaitHit(String prefix) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (hits.stream().noneMatch(h -> h.startsWith(prefix))) {
            assertTrue(System.nanoTime() < deadline, "the server never got " + prefix + ": " + hits);
            Thread.sleep(10);
        }
    }

    private List<String> savedPaths(Path file) {
        return new HttpResponseHistory(config)
                .load(file).responses.stream()
                        .map(r -> r.url().replace(url(""), ""))
                        .toList();
    }

    // --- Y1 ----------------------------------------------------------------------------------------------------

    @Test
    void aRunPressedBeforeTheSavedHistoryIsReadKeepsTheSavedHistory() throws Exception {
        Path file = work.resolve("api.http");
        String text =
                "### a\nGET " + url("/a") + "\n\n### b\nGET " + url("/b") + "\n\n### slow\nGET " + url("/slow") + "\n";
        Host first = new Host();
        HttpClientCoordinator one = coordinator(first);
        EditorBuffer b1 = open(first, one, file, text);
        run(one, b1, 1);
        run(one, b1, 4);
        FxTestSupport.runOnFx(() -> one.onBufferClosed(b1));
        HttpHistorySync.awaitIdleForTest();
        assertEquals(List.of("/b", "/a"), savedPaths(file));

        // Reopened while the history thread is busy (another file's big history): Run is pressed at once.
        Host second = new Host();
        HttpClientCoordinator two = coordinator(second);
        CountDownLatch busy = HttpHistorySync.holdForTest();
        EditorBuffer b2 = openNow(second, two, file, text);
        FxTestSupport.runOnFx(() -> two.runRequest(b2, 7));
        awaitHit("GET /slow");
        busy.countDown();
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        slow.countDown();
        await(() -> !two.isRunningForTest(b2));
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();

        assertEquals(List.of("/slow", "/b", "/a"), savedPaths(file), "the saved responses survive the run");
        assertEquals(
                3, FxTestSupport.callOnFx(() -> two.panelForTest(b2).history().size()));
    }

    // --- Y8 ----------------------------------------------------------------------------------------------------

    @Test
    void aResponseThatLandsAfterAClearIsSavedAsItIsShown() throws Exception {
        // Window one runs a slow request; window two, on the same file, clears the history meanwhile.
        Path file = work.resolve("clear.http");
        String text = "### a\nGET " + url("/a") + "\n\n### slow\nGET " + url("/slow") + "\n";
        Host one = new Host();
        HttpClientCoordinator first = coordinator(one);
        EditorBuffer b1 = open(one, first, file, text);
        Host two = new Host();
        HttpClientCoordinator second = coordinator(two);
        EditorBuffer b2 = open(two, second, file, text);
        run(second, b2, 1);
        FxTestSupport.runOnFx(() -> first.runRequest(b1, 4));
        awaitHit("GET /slow");
        FxTestSupport.runOnFx(() -> second.panelForTest(b2).clearButtonForTest().fire());
        HttpHistorySync.awaitIdleForTest();
        slow.countDown();
        await(() -> !first.isRunningForTest(b1));
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
        assertEquals(
                1, FxTestSupport.callOnFx(() -> first.panelForTest(b1).history().size()));
        assertEquals(List.of("/slow"), savedPaths(file), "shown after the Clear, so kept after the Clear");
    }

    // --- Y12 ---------------------------------------------------------------------------------------------------

    @Test
    void cancellingARequestWhoseAnswerIsAlreadyInSaysSo() throws Exception {
        Path file = work.resolve("late.http");
        Files.writeString(work.resolve("keep.json"), "ORIGINAL");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "### g\nGET " + url("/g") + "\n\n>>! ./keep.json\n");
        HttpClientService service = FxTestSupport.field(c, "service");
        // The cancel arrives while the response is being written: the answer is in, the file is left alone.
        service.setRedirectGuard(target -> {
            CountDownLatch cancelled = new CountDownLatch(1);
            Platform.runLater(() -> {
                c.cancelRun(b);
                cancelled.countDown();
            });
            try {
                cancelled.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ResponseRedirects.Decision.of(ResponseRedirects.Verdict.NOT_KEPT);
        });
        host.statuses.clear();
        FxTestSupport.runOnFx(() -> c.runRequest(b, 1));
        String wanted = tr("status.http.cancelledTooLate");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!host.all.contains(wanted)) {
            assertTrue(System.nanoTime() < deadline, host.all.toString());
            Thread.sleep(10);
        }
        assertEquals("ORIGINAL", Files.readString(work.resolve("keep.json")));
    }

    // --- Y5 / Z1 / Z2: Run on a history entry --------------------------------------------------------------------

    @Test
    void runOnAnEntrySendsItsOwnRequestAfterAnotherIsInsertedAbove() throws Exception {
        Path file = work.resolve("items.http");
        String two = "###\nPOST " + url("/items") + "\nContent-Type: application/json\n\n{\"n\":\"A\"}\n\n"
                + "###\nPOST " + url("/items") + "\nContent-Type: application/json\n\n{\"n\":\"B\"}\n";
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, two);
        run(c, b, 7); // B
        FxTestSupport.runOnFx(() -> b.setContent("###\nGET " + url("/health") + "\n\n" + two));
        hits.clear();
        FxTestSupport.runOnFx(() -> {
            HttpClientPanel p = c.panelForTest(b);
            p.historyPickerForTest().setValue(p.history().get(0));
            p.runButtonForTest().fire();
        });
        await(() -> !c.isRunningForTest(b));
        assertEquals(1, hits.size(), hits.toString());
        assertTrue(hits.get(0).endsWith("body={\"n\":\"B\"}"), hits.toString());
    }

    @Test
    void anUnnamedEntryWhoseRequestIsGoneRunsTheCaretsRequestAndSaysSoInsteadOfAGenericOne() throws Exception {
        Path file = work.resolve("gone.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "###\nDELETE " + url("/users/1") + "\n");
        run(c, b, 1);
        // The DELETE of user 1 is removed; a generic DELETE stays, and the caret is on a harmless GET.
        String now = "@kind = things\n@id = 9\n\n###\nGET " + url("/health") + "\n\n###\nDELETE http://127.0.0.1:"
                + server.getAddress().getPort() + "/{{kind}}/{{id}}\n";
        FxTestSupport.runOnFx(() -> {
            b.setContent(now);
            b.getArea().moveTo(4, 0);
        });
        hits.clear();
        host.statuses.clear();
        FxTestSupport.runOnFx(() -> {
            HttpClientPanel p = c.panelForTest(b);
            p.historyPickerForTest().setValue(p.history().get(0));
            p.runButtonForTest().fire();
        });
        await(() -> !c.isRunningForTest(b));
        assertEquals(1, hits.size(), hits.toString());
        assertTrue(hits.get(0).startsWith("GET /health"), hits.toString());
        assertTrue(
                host.all.stream().anyMatch(s -> s.equals(tr("status.http.rerunAtCaret", "DELETE " + url("/users/1")))),
                host.all.toString());
    }

    @Test
    void anUnnamedRequestWithASpaceAndAccentsIsFoundAgain() throws Exception {
        Path file = work.resolve("search.http");
        String text = "###\nGET " + url("/other") + "\n\n###\nGET " + url("/search") + "?q=hello world&city=München\n";
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, text);
        run(c, b, 4);
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(1, 0)); // the caret on the other request
        hits.clear();
        FxTestSupport.runOnFx(() -> {
            HttpClientPanel p = c.panelForTest(b);
            p.historyPickerForTest().setValue(p.history().get(0));
            p.runButtonForTest().fire();
        });
        await(() -> !c.isRunningForTest(b));
        assertEquals(1, hits.size(), hits.toString());
        assertTrue(hits.get(0).startsWith("GET /search"), hits.toString());
    }

    // --- Copy as cURL, run by the real curl ----------------------------------------------------------------------

    /** Runs {@code b}'s request at {@code line}, then its copied cURL command; returns [Editora's hits, curl's]. */
    private List<List<String>> sentByBoth(HttpClientCoordinator c, EditorBuffer b, int line) throws Exception {
        hits.clear();
        run(c, b, line);
        List<String> editora = List.copyOf(hits);
        String command = FxTestSupport.callOnFx(() -> {
            Clipboard.getSystemClipboard().clear();
            HttpClientPanel p = c.panelForTest(b);
            p.historyPickerForTest().setValue(p.history().get(0));
            p.copyCurlButtonForTest().fire();
            return Clipboard.getSystemClipboard().getString();
        });
        assertTrue(command != null && command.startsWith("curl "), String.valueOf(command));
        hits.clear();
        Process curl = new ProcessBuilder("bash", "-c", CURL + command.substring(4) + " -s -o /dev/null")
                .directory(work.toFile())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .start();
        assertTrue(curl.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "curl did not finish");
        assertEquals(0, curl.exitValue(), new String(curl.getInputStream().readAllBytes()));
        return List.of(editora, List.copyOf(hits));
    }

    private static boolean curlAvailable() {
        return Files.isExecutable(CURL) && Files.isExecutable(Path.of("/bin/bash"));
    }

    @Test
    void copiedCurlSendsInlinePartsWithTheirTypeAndFilename() throws Exception {
        assumeTrue(curlAvailable(), "needs /usr/bin/curl");
        Path file = work.resolve("form.http");
        String text = "### form\nPOST " + url("/form") + "\nContent-Type: multipart/form-data; boundary=BB\n\n"
                + "--BB\nContent-Disposition: form-data; name=\"meta\"\nContent-Type: application/json\n\n{\"k\": \"v; w\"}\n"
                + "--BB\nContent-Disposition: form-data; name=\"inline\"; filename=\"in line.csv\"\n\na,b\n1,2\n"
                + "--BB\nContent-Disposition: form-data; name=\"plain\"\n\n@not-a-file\n--BB--\n";
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, text);
        List<List<String>> both = sentByBoth(c, b, 1);
        String fromCurl = both.get(1).get(0);
        assertTrue(fromCurl.contains("name=\"meta\"\nContent-Type: application/json\n\n{\"k\": \"v; w\"}"), fromCurl);
        assertTrue(fromCurl.contains("name=\"inline\"; filename=\"in line.csv\""), fromCurl);
        assertTrue(fromCurl.contains("a,b\n1,2"), fromCurl);
        assertTrue(fromCurl.contains("name=\"plain\"\n\n@not-a-file"), fromCurl);
        String fromEditora = both.get(0).get(0);
        assertTrue(fromEditora.contains("name=\"meta\"\nContent-Type: application/json"), fromEditora);
    }

    @Test
    void copiedCurlOfARedirectedPostFollowsWithAGetAsTheRunDid() throws Exception {
        assumeTrue(curlAvailable(), "needs /usr/bin/curl");
        Path file = work.resolve("redirect.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(
                host, c, file, "### post\nPOST " + url("/r302") + "\nContent-Type: application/json\n\n{\"x\":1}\n");
        List<List<String>> both = sentByBoth(c, b, 1);
        assertTrue(both.get(0).get(1).startsWith("GET /landed"), both.get(0).toString());
        assertTrue(both.get(1).get(1).startsWith("GET /landed"), "curl re-posted: " + both.get(1));
    }

    @Test
    void copiedCurlOfABodyWithoutContentTypeSendsNoneEither() throws Exception {
        assumeTrue(curlAvailable(), "needs /usr/bin/curl");
        Path file = work.resolve("plain.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "### plain\nPOST " + url("/plain") + "\n\nhello=world&x\n");
        List<List<String>> both = sentByBoth(c, b, 1);
        assertEquals("POST /plain ct=null body=hello=world&x", both.get(0).get(0));
        assertEquals(both.get(0), both.get(1));
    }

    @Test
    void copiedCurlAsksForTheVersionTheRequestLineNamed() throws Exception {
        assumeTrue(curlAvailable(), "needs /usr/bin/curl");
        Path file = work.resolve("v10.http");
        Host host = new Host();
        HttpClientCoordinator c = coordinator(host);
        EditorBuffer b = open(host, c, file, "### old\nGET " + url("/old") + " HTTP/1.0\n");
        sentByBoth(c, b, 1);
        assertEquals(List.of("HTTP/1.0"), protocols.subList(protocols.size() - 1, protocols.size()));
    }

    // --- harness -----------------------------------------------------------------------------------------------

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
        final List<String> all = new CopyOnWriteArrayList<>();
        volatile EditorBuffer active;

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
            statuses.add(message);
            all.add(message);
        }

        @Override
        public void setError(String message) {
            setStatus(message);
        }
    }
}
