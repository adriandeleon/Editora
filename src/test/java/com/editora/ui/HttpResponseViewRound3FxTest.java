package com.editora.ui;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.config.HttpResponseHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.http.HttpExchange;
import com.editora.http.HttpFixtures;
import com.editora.http.HttpResult;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response view after the third review, judged by what the user or the server gets: what a 127.0.0.1 server
 * receives from a remote file, what the clipboard holds after Emacs's M-w, what is laid out and focused in a real
 * stage, which line shows the running ■, and what the panel says after a cancel or a request that was not sent.
 */
@Tag("fx")
class HttpResponseViewRound3FxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final CountDownLatch slowStarted = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private final List<HttpClientCoordinator> coordinators = new ArrayList<>();
    private final List<Stage> stages = new ArrayList<>();
    private KeymapManager keymapBefore;

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
        keymapBefore = TextInputKeymap.sharedKeymap();
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
            byte[] body = "{\"ok\":true,\"line\":2}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
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
        TextInputKeymap.setShared(keymapBefore);
        FxTestSupport.runOnFx(() -> {
            stages.forEach(Stage::close);
            buffers.forEach(EditorBuffer::dispose);
        });
        HttpHistorySync.awaitIdleForTest();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpClientCoordinator coordinator() {
        HttpClientCoordinator c = new HttpClientCoordinator(host, new Ops(config));
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
            c.applySupport(); // the ▶ glyphs and the request lines the running ■ is drawn on
            return created;
        });
        HttpEnvCache.awaitIdleForTest(); // the env files are read off the FX thread
        buffers.add(b);
        return b;
    }

    private Stage show(Node root, double w, double h) throws Exception {
        Stage st = FxTestSupport.callOnFx(() -> {
            Stage s = new Stage();
            stages.add(s);
            s.setScene(new Scene((javafx.scene.Parent) root, w, h));
            s.show();
            return s;
        });
        FxTestSupport.drainFx();
        return st;
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

    private static void key(Node target, KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false));
        target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, ctrl, alt, false));
    }

    private static void emacs() {
        KeymapManager k = new KeymapManager();
        k.loadNamed("emacs");
        TextInputKeymap.setShared(k);
    }

    private static HttpExchange exchange(String label, HttpResult r) {
        return HttpFixtures.exchange(label, "GET", "http://x.test/" + label, List.of(), "", r);
    }

    private static HttpResult ok(String body, List<String[]> headers, List<String> warnings) {
        return HttpFixtures.text(200, headers, body, "application/json", 3, body.length(), null, warnings);
    }

    private static HttpHistoryEntry entry(HttpExchange ex) {
        return new HttpHistoryEntry(ex, null, 0, System.currentTimeMillis(), false, false, false);
    }

    private static HttpClientPanel panel() throws Exception {
        return FxTestSupport.callOnFx(() -> new HttpClientPanel(null, ex -> {}, ex -> {}, "monospace", 12));
    }

    private static void showIn(HttpClientPanel p, HttpExchange... ex) throws Exception {
        FxTestSupport.callOnFx(() -> {
                    p.showEntries(List.of(ex).stream()
                            .map(HttpResponseViewRound3FxTest::entry)
                            .toList());
                    return p.bodyRenderForTest();
                })
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        FxTestSupport.drainFx();
    }

    private static double rowHeight() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Text t = new Text("Xg");
            t.setFont(javafx.scene.text.Font.font("monospace", 12));
            return t.getLayoutBounds().getHeight();
        });
    }

    // --- X2: a remote file's requests do not run from this machine -------------------------------------------

    @Test
    void aRemoteFilesRequestsAreRefusedEverywhereAndRunSaysWhy() throws Exception {
        Path zip = work.resolve("remote.zip");
        try (FileSystem remote =
                FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
            HttpClientCoordinator c = coordinator();
            EditorBuffer b = open(c, remote.getPath("/api/remote.http"), "GET " + url("/remote") + "\n");
            assertFalse(Vfs.isLocal(FxTestSupport.callOnFx(b::getPath)), "a non-default file system stands for SFTP");
            HttpClientPanel p = c.panelForTest(b);

            FxTestSupport.runOnFx(() -> c.runRequest(b, 0));
            assertEquals(tr("status.http.remoteBlocked"), host.last);
            FxTestSupport.runOnFx(c::runRequestAtCaret);
            assertEquals(tr("status.http.remoteBlocked"), host.last);
            FxTestSupport.runOnFx(c::runFile);
            assertEquals(tr("status.http.remoteBlocked"), host.last);
            FxTestSupport.runOnFx(c::cancelActiveRequest);
            assertEquals(tr("status.http.remoteBlocked"), host.last);
            FxTestSupport.runOnFx(() -> p.controlsForTest().runButton().fire()); // disabled: does nothing

            assertTrue(
                    FxTestSupport.callOnFx(() -> p.controlsForTest().runButton().isDisabled()), "Run is disabled");
            assertEquals(
                    tr("httppanel.remoteRunTip"),
                    FxTestSupport.callOnFx(
                            () -> p.controlsForTest().runBlockedTooltip().getText()),
                    "and a tooltip says why");
        }
        // A local file still runs.
        host.statuses.clear();
        HttpClientCoordinator c = coordinator();
        EditorBuffer local = open(c, work.resolve("local.http"), "GET " + url("/local") + "\n");
        assertFalse(FxTestSupport.callOnFx(
                () -> c.panelForTest(local).controlsForTest().runButton().isDisabled()));
        FxTestSupport.runOnFx(() -> c.runRequest(local, 0));
        finished();
        // Sent after the remote file's attempts, so anything they had sent would have arrived first.
        assertEquals(List.of("GET /local"), hits, "only the local file's request reached the server");
    }

    // --- X3, X15: the configured keymap in the response view; the warnings block by keyboard; C-g ----------------

    @Test
    void emacsMwCopiesAndCnMovesInTheResponseViewAndCkEditsNothing() throws Exception {
        emacs();
        HttpClientPanel p = panel();
        List<String[]> headers = List.<String[]>of(new String[] {"content-type", "application/json"});
        showIn(p, exchange("a", ok("{\"a\":1,\"b\":2}", headers, List.of())));
        show(p, 700, 600);
        FxTestSupport.runOnFx(() -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString("MARKER");
            Clipboard.getSystemClipboard().setContent(cc);
        });
        var body = p.controlsForTest().bodyArea();
        FxTestSupport.runOnFx(() -> {
            body.requestFocus();
            body.selectRange(0, 1);
            key(body, KeyCode.W, false, false, true); // M-w
        });
        assertEquals(
                "{", FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()), "M-w copied");
        FxTestSupport.runOnFx(() -> {
            body.moveTo(0);
            key(body, KeyCode.N, false, true, false); // C-n
        });
        assertEquals(1, FxTestSupport.callOnFx(body::getCurrentParagraph), "C-n moved down a line");

        TextArea head = p.controlsForTest().headersArea();
        String before = FxTestSupport.callOnFx(head::getText);
        FxTestSupport.runOnFx(() -> {
            head.requestFocus();
            head.selectRange(0, 4);
            key(head, KeyCode.W, false, false, true);
            head.positionCaret(0);
            key(head, KeyCode.K, false, true, false); // C-k: kill-line, which must not touch a read-only view
        });
        assertEquals(
                "HTTP",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        assertEquals(before, FxTestSupport.callOnFx(head::getText), "the headers are unchanged");
    }

    @Test
    void theWarningsBlockOpensByKeyboardAndCgLeavesThePanel() throws Exception {
        emacs();
        HttpClientPanel p = panel();
        int[] escapes = {0};
        FxTestSupport.runOnFx(() -> p.setOnEscape(() -> escapes[0]++));
        showIn(p, exchange("w", ok("{}", List.of(), List.of("first warning", "second warning", "third warning"))));
        show(p, 700, 600);
        assertFalse(FxTestSupport.callOnFx(p::notesExpandedForTest), "more than two warnings start collapsed");
        Label badge = p.controlsForTest().warnBadgeLabel();
        assertTrue(FxTestSupport.callOnFx(badge::isFocusTraversable), "the badge takes the focus");
        FxTestSupport.runOnFx(() -> {
            badge.requestFocus();
            key(badge, KeyCode.ENTER, false, false, false);
        });
        assertTrue(FxTestSupport.callOnFx(p::notesExpandedForTest), "Enter on the badge opens the warnings");

        FxTestSupport.runOnFx(() -> {
            p.controlsForTest().notesPane().setExpanded(false);
            p.controlsForTest().requestPane().setExpanded(true);
        });
        FxTestSupport.drainFx();
        TextArea request = p.controlsForTest().requestArea();
        FxTestSupport.runOnFx(() -> {
            request.requestFocus();
            key(request, KeyCode.TAB, false, false, false);
        });
        assertEquals(
                p.controlsForTest().notesPane(),
                FxTestSupport.callOnFx(() -> request.getScene().getFocusOwner()),
                "Tab from the request reaches the collapsed warnings, not past them");

        FxTestSupport.runOnFx(() -> {
            p.controlsForTest().bodyArea().requestFocus();
            key(p.controlsForTest().bodyArea(), KeyCode.G, false, true, false); // C-g
        });
        assertEquals(1, escapes[0], "C-g leaves the panel like Escape");
    }

    // --- X4: the top section sizes to its content -------------------------------------------------------------

    @Test
    void twoLongWarningsShowWholeAndTheHeadersKeepTheirRows() throws Exception {
        HttpClientPanel p = panel();
        List<String[]> headers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            headers.add(new String[] {"x-header-" + i, "value " + i});
        }
        String w = "{{token}} was sent as empty text: not defined in the file or the selected environment.";
        show(p, 450, 820);
        showIn(p, exchange("h", ok("{}", headers, List.of(w, w.replace("token", "user")))));
        double row = rowHeight();
        // The top section is fitted after layout: wait for it to settle rather than for a fixed time.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && (FxTestSupport.callOnFx(() -> p.controlsForTest().notesArea().getHeight()) < 3.5 * row
                        || FxTestSupport.callOnFx(
                                        () -> p.controlsForTest().headersArea().getHeight())
                                < 4 * row)) {
            Thread.sleep(20);
        }
        double notes =
                FxTestSupport.callOnFx(() -> p.controlsForTest().notesArea().getHeight());
        double heads =
                FxTestSupport.callOnFx(() -> p.controlsForTest().headersArea().getHeight());
        assertTrue(FxTestSupport.callOnFx(p::notesExpandedForTest));
        assertTrue(notes >= 3.5 * row, "both wrapped warnings show (" + notes + " px, row " + row + ")");
        assertTrue(heads >= 4 * row, "the headers keep several rows (" + heads + " px, row " + row + ")");
    }

    // --- X8: a narrow panel keeps its status line whole ---------------------------------------------------------

    @Test
    void aNarrowPanelShortensTheBadgeNotTheStatus() throws Exception {
        HttpClientPanel p = panel();
        List<String> eleven = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            eleven.add("{{u" + i + "}} was sent as empty text");
        }
        Stage stage = show(p, 900, 600);
        showIn(p, exchange("n", ok("{\"x\":1}", List.of(), eleven)));
        await(() -> p.warnBadgeForTest() != null && p.warnBadgeForTest().startsWith("⚠ 11"));
        Label status = p.controlsForTest().statusLabel();
        // At what width the badge must shorten depends on the platform's fonts (macOS fits the full badge at
        // 300 px), so check the rule itself at every width: the status line is cut only once the badge is short.
        for (int width = 900; width >= 260; width -= 20) {
            int w = width;
            String[] seen = FxTestSupport.callOnFx(() -> {
                stage.setWidth(w);
                p.applyCss();
                p.layout();
                return new String[] {status.getText(), ((Text) status.lookup(".text")).getText(), p.warnBadgeForTest()};
            });
            boolean statusCut = !seen[0].equals(seen[1]);
            boolean badgeShort = "⚠ 11".equals(seen[2]);
            assertTrue(
                    !statusCut || badgeShort,
                    "at " + w + " px the status line was cut before the badge: " + seen[1] + " | " + seen[2]);
        }
    }

    // --- X6: a cancelled Run All says it was cancelled ----------------------------------------------------------

    @Test
    void aRunAllCancelledAfterTwoSaysHowFarItGot() throws Exception {
        HttpClientCoordinator c = coordinator();
        String text = "### A\nGET " + url("/a") + "\n\n### B\nGET " + url("/b") + "\n\n### C\nGET " + url("/slow")
                + "\n\n### D\nGET " + url("/d") + "\n";
        EditorBuffer b = open(c, work.resolve("all.http"), text);
        FxTestSupport.runOnFx(c::runFile);
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        HttpClientPanel p = c.panelForTest(b);
        FxTestSupport.runOnFx(() -> p.controlsForTest().runButton().fire()); // Cancel
        releaseSlow.countDown();
        await(() -> p.runSummaryForTest() != null);
        assertEquals(tr("httppanel.runAllCancelled", 2, 4, 0L), FxTestSupport.callOnFx(p::runSummaryForTest));
    }

    // --- X7, X12, X13, Z10: an untitled buffer's "save first" result ---------------------------------------------

    @Test
    void anUnsentResultIsShownSaysNotSentAndHasNoCurlToCopy() throws Exception {
        HttpClientCoordinator c = coordinator();
        EditorBuffer b = open(c, null, "POST " + url("/x") + "\n\n< ./body.json\n");
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.EDITOR));
        FxTestSupport.runOnFx(() -> c.runRequest(b, 0));
        String status = host.awaitStatus(s -> !s.startsWith("Running"));
        assertEquals(
                EditorBuffer.MarkdownViewMode.SPLIT,
                FxTestSupport.callOnFx(b::getMarkdownViewMode),
                "the result is shown, as a sent request's is");
        assertEquals(
                tr(
                        "status.http.notSentError",
                        "POST " + url("/x"),
                        HttpStatusText.notSentReason(tr("httppanel.saveFirstToUse", "< ./body.json"))),
                status);
        int instruction = status.indexOf("save the file first");
        assertTrue(
                instruction > 0 && instruction < status.indexOf("< ./body.json"),
                "the instruction comes before the details, which a narrow status bar cuts: " + status);
        HttpClientPanel p = c.panelForTest(b);
        assertEquals(tr("httppanel.notSent"), FxTestSupport.callOnFx(p::statusForTest), "not \"Request failed\"");
        assertTrue(
                FxTestSupport.callOnFx(
                        () -> p.controlsForTest().copyCurlButton().isDisabled()),
                "nothing to copy");
        FxTestSupport.runOnFx(() -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString("MARKER");
            Clipboard.getSystemClipboard().setContent(cc);
        });
        FxTestSupport.runOnFx(c::copyActiveAsCurl);
        assertEquals(tr("status.http.curlNotSent"), host.last);
        assertEquals(
                "MARKER",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        assertEquals(List.of(), hits);
    }

    // --- X9: the panel says Run sent the caret's request ---------------------------------------------------------

    @Test
    void runSendingTheCaretsRequestSaysSoOnThePanelAfterTheResult() throws Exception {
        HttpClientCoordinator c = coordinator();
        String text = "### Gone\nGET " + url("/gone") + "\n\n### Kept\nGET " + url("/kept") + "\n";
        EditorBuffer b = open(c, work.resolve("g.http"), text);
        FxTestSupport.runOnFx(() -> c.runRequest(b, 1));
        finished();
        FxTestSupport.runOnFx(() -> {
            b.getArea().replaceText("### Kept\nGET " + url("/kept") + "\n");
            b.getArea().moveTo(1, 0);
        });
        HttpClientPanel p = c.panelForTest(b);
        FxTestSupport.runOnFx(() -> p.controlsForTest().runButton().fire());
        host.awaitStatus(s -> s.startsWith("GET " + url("/kept"))); // the result replaced the status-bar note
        assertEquals(
                tr("status.http.rerunAtCaret", "GET " + url("/gone")),
                FxTestSupport.callOnFx(p::runSummaryForTest),
                "the panel still says which request ran");
    }

    // --- X10: after Cancel nothing old is shown; History can be looked at during a run ---------------------------

    @Test
    void cancellingShowsNoOldResponseAndHistoryCanBeBrowsedDuringARun() throws Exception {
        HttpClientCoordinator c = coordinator();
        String text = "### Json\nGET " + url("/json") + "\n\n### Slow\nGET " + url("/slow") + "\n";
        EditorBuffer b = open(c, work.resolve("c.http"), text);
        FxTestSupport.runOnFx(() -> c.runRequest(b, 1));
        finished();
        FxTestSupport.runOnFx(() -> c.runRequest(b, 4));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        HttpClientPanel p = c.panelForTest(b);
        FxTestSupport.runOnFx(() -> p.controlsForTest()
                .historyPicker()
                .setValue(p.controlsForTest().historyPicker().getItems().get(0)));
        assertTrue(
                FxTestSupport.callOnFx(p::headersForTest).startsWith("HTTP"), "an older response shows during the run");
        assertTrue(FxTestSupport.callOnFx(p::statusForTest).contains(url("/slow")), "…under the running status");

        FxTestSupport.runOnFx(() -> {
            p.controlsForTest().historyPicker().setValue(null);
            c.cancelRun(b);
        });
        assertEquals(tr("httppanel.cancelled"), FxTestSupport.callOnFx(p::statusForTest));
        assertEquals("", FxTestSupport.callOnFx(p::headersForTest), "no old response under \"Cancelled\"");
        assertNull(FxTestSupport.callOnFx(p::getSelectedExchange));
    }

    // --- X5: the ■ stays on its own request -----------------------------------------------------------------------

    @Test
    void theRunningMarkStaysOnItsRequestWhenAnotherHasTheSameUrl() throws Exception {
        HttpClientCoordinator c = coordinator();
        String text = "### First\nGET " + url("/slow") + "\nX-Which: first\n\n### Second\nGET " + url("/slow")
                + "\nX-Which: second\n";
        EditorBuffer b = open(c, work.resolve("dup.http"), text);
        FxTestSupport.runOnFx(() -> c.runRequest(b, 5));
        assertTrue(slowStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(5, FxTestSupport.callOnFx(b::httpRunningLine));
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "### Top\nGET " + url("/json") + "\n\n"));
        await(() -> b.httpRunningLine() != 5);
        assertEquals(8, FxTestSupport.callOnFx(b::httpRunningLine), "the second request, not the first (line 4)");
    }

    // --- X14: a folded request keeps a ▶ ---------------------------------------------------------------------------

    @Test
    void foldAllLeavesARunGlyphPerRequest() throws Exception {
        HttpClientCoordinator c = coordinator();
        String text = "### One\nGET " + url("/1") + "\nAccept: */*\n\n### Two\nGET " + url("/2") + "\nAccept: */*\n";
        EditorBuffer b = open(c, work.resolve("f.http"), text);
        FxTestSupport.runOnFx(() -> {
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.EDITOR);
            c.applySupport();
        });
        show(b.getNode(), 900, 600);
        await(() -> b.getNode().lookupAll(".run-marker").size() == 2);
        assertNull(
                FxTestSupport.callOnFx(() -> b.getArea().getParagraphGraphic(0).lookup(".run-marker")),
                "unfolded, the ### line has no glyph of its own");
        await(() -> {
            b.foldAll(); // the fold regions come from a scan that may not have landed yet
            return b.getArea().isFolded(1) && b.getArea().isFolded(5);
        });
        // Each folded request's ▶ is drawn on its ### header, the line that still shows.
        await(() -> b.getArea().getParagraphGraphic(0).lookup(".run-marker") != null);
        await(() -> b.getArea().getParagraphGraphic(4).lookup(".run-marker") != null);
    }

    // --- X16: History newest first, a steady code column, a wider picker when there is room, warnings in order ----

    @Test
    void aRunAllIsListedNewestFirstAndItsWarningsInFileOrder() throws Exception {
        HttpClientCoordinator c = coordinator();
        String text = "### A\nGET " + url("/a") + "?x={{u1}}\nX-A: {{u9}}\n\n### B\nGET " + url("/b") + "\n";
        EditorBuffer b = open(c, work.resolve("o.http"), text);
        FxTestSupport.runOnFx(c::runFile);
        finished();
        HttpClientPanel p = c.panelForTest(b);
        List<String> names = FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().getItems().stream()
                .map(HttpHistoryEntry::name)
                .toList());
        assertEquals(List.of("B", "A"), names, "newest first, like the rest of History");
        FxTestSupport.runOnFx(() -> p.controlsForTest()
                .historyPicker()
                .setValue(p.controlsForTest().historyPicker().getItems().get(1)));
        String notes = FxTestSupport.callOnFx(p::notesForTest);
        assertTrue(notes.indexOf("{{u1}}") < notes.indexOf("{{u9}}"), "the URL's warning first: " + notes);
    }

    @Test
    void historyCodesLineUpAndThePickerUsesTheRoomItHas() throws Exception {
        HttpClientPanel p = panel();
        show(p, 1300, 600);
        showIn(
                p,
                exchange("ok", ok("{}", List.of(), List.of())),
                exchange("bad", HttpResult.failure("refused", List.of())));
        await(() -> p.controlsForTest().historyPicker().getWidth() > 0);
        assertTrue(
                FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().getWidth())
                        > HttpPanelLayout.HISTORY_WIDTH,
                "wider than 340 px in a wide panel");
        double[] widths = new double[2];
        for (int i = 0; i < 2; i++) {
            int k = i;
            FxTestSupport.runOnFx(() -> p.controlsForTest()
                    .historyPicker()
                    .setValue(p.controlsForTest().historyPicker().getItems().get(k)));
            FxTestSupport.drainFx();
            widths[k] = FxTestSupport.callOnFx(() -> {
                Label code = (Label) p.controlsForTest().historyPicker().lookup(".http-history-code");
                return Math.max(code.minWidth(-1), code.prefWidth(-1));
            });
        }
        assertEquals(widths[0], widths[1], 0.5, "a ⚠ row's code is as wide as a 200 row's");
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
