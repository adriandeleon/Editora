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
import javafx.scene.control.TableView;
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

        TableView<String[]> head = p.controlsForTest().headersTable();
        String before = FxTestSupport.callOnFx(p::headersForTest);
        FxTestSupport.runOnFx(() -> {
            p.tabsForTest().button(HttpResponseTab.HEADERS).fire();
            head.requestFocus();
            head.getSelectionModel().select(0);
            key(head, KeyCode.W, false, false, true); // M-w copies the selected header
            key(head, KeyCode.K, false, true, false); // C-k: kill-line, which must not touch a read-only view
        });
        assertEquals(
                "content-type: application/json",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        assertEquals(before, FxTestSupport.callOnFx(p::headersForTest), "the headers are unchanged");
    }

    @Test
    void theTabsAreReachedAndSwitchedByKeyboardAndCgLeavesThePanel() throws Exception {
        emacs();
        HttpClientPanel p = panel();
        int[] escapes = {0};
        FxTestSupport.runOnFx(() -> p.setOnEscape(() -> escapes[0]++));
        List<String[]> headers = List.<String[]>of(new String[] {"content-type", "application/json"});
        showIn(p, exchange("w", ok("{}", headers, List.of("first warning", "second warning", "third warning"))));
        show(p, 700, 600);
        assertEquals(HttpResponseTab.BODY, FxTestSupport.callOnFx(p::selectedTabForTest), "warnings do not switch");
        HttpTabStrip strip = p.tabsForTest();
        javafx.scene.control.ToggleButton body = strip.button(HttpResponseTab.BODY);
        assertTrue(FxTestSupport.callOnFx(body::isFocusTraversable), "Tab reaches the selected tab");
        assertFalse(
                FxTestSupport.callOnFx(
                        () -> strip.button(HttpResponseTab.HEADERS).isFocusTraversable()),
                "…and only that one: the arrows move between them");
        FxTestSupport.runOnFx(() -> {
            body.requestFocus();
            key(body, KeyCode.RIGHT, false, false, false);
        });
        assertEquals(HttpResponseTab.HEADERS, FxTestSupport.callOnFx(p::selectedTabForTest), "Right picks Headers");
        assertTrue(FxTestSupport.callOnFx(
                () -> strip.button(HttpResponseTab.HEADERS).isFocused()));
        FxTestSupport.runOnFx(() -> key(strip.button(HttpResponseTab.HEADERS), KeyCode.END, false, false, false));
        assertEquals(HttpResponseTab.NOTES, FxTestSupport.callOnFx(p::selectedTabForTest), "End picks the last tab");
        assertTrue(FxTestSupport.callOnFx(() -> p.notesForTest().contains("third warning")));

        // Tab leaves a read-only area (which would keep it) for Run; Shift+Tab goes back to the strip's end.
        FxTestSupport.runOnFx(() -> strip.button(HttpResponseTab.REQUEST).fire());
        var request = p.controlsForTest().requestArea();
        FxTestSupport.runOnFx(() -> {
            request.requestFocus();
            key(request, KeyCode.TAB, false, false, false);
        });
        assertEquals(
                p.controlsForTest().runButton(),
                FxTestSupport.callOnFx(() -> request.getScene().getFocusOwner()),
                "Tab from the request goes round to Run");
        FxTestSupport.runOnFx(() -> {
            request.requestFocus();
            key(request, KeyCode.TAB, true, false, false);
        });
        assertEquals(
                p.controlsForTest().moreButton(),
                FxTestSupport.callOnFx(() -> request.getScene().getFocusOwner()),
                "Shift+Tab goes back to the actions");
        FxTestSupport.runOnFx(() -> strip.button(HttpResponseTab.BODY).fire());

        FxTestSupport.runOnFx(() -> {
            p.controlsForTest().bodyArea().requestFocus();
            key(p.controlsForTest().bodyArea(), KeyCode.G, false, true, false); // C-g
        });
        assertEquals(1, escapes[0], "C-g leaves the panel like Escape");
    }

    // --- U1: the body gets the room; the other parts are tabs ----------------------------------------------------

    @Test
    void theBodyGetsTheRoomAndTwoLongWarningsShowWholeInTheirTab() throws Exception {
        HttpClientPanel p = panel();
        List<String[]> headers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            headers.add(new String[] {"x-header-" + i, "value " + i});
        }
        String w = "{{token}} was sent as empty text: not defined in the file or the selected environment.";
        show(p, 450, 820);
        showIn(p, exchange("h", ok("{}", headers, List.of(w, w.replace("token", "user")))));
        double body =
                FxTestSupport.callOnFx(() -> p.controlsForTest().bodyArea().getHeight());
        // Six headers and two warnings used to leave the body a few lines: now only the header row and the tabs
        // come before it (Environment wraps to a second header row at this width).
        assertTrue(body >= 820 - 160, "the body has the panel's height less the header and the tabs: " + body);
        assertEquals("Headers 6", FxTestSupport.callOnFx(() -> p.tabTextForTest(HttpResponseTab.HEADERS)));
        FxTestSupport.runOnFx(
                () -> p.tabsForTest().button(HttpResponseTab.NOTES).fire());
        FxTestSupport.drainFx();
        double row = rowHeight();
        double notes =
                FxTestSupport.callOnFx(() -> p.controlsForTest().notesArea().getHeight());
        assertTrue(notes >= 3.5 * row, "both wrapped warnings show (" + notes + " px, row " + row + ")");
        assertTrue(FxTestSupport.callOnFx(() -> p.controlsForTest().notesArea().isVisible()));
        assertFalse(
                FxTestSupport.callOnFx(
                        () -> p.tabContentForTest(HttpResponseTab.BODY).isVisible()),
                "one tab at a time");
    }

    // --- X8, U1: a narrow panel narrows History first and never cuts the status ------------------------------------

    @Test
    void aNarrowPanelNarrowsHistoryFirstNeverCutsTheStatusAndShortensTheTabs() throws Exception {
        HttpClientPanel p = panel();
        List<String> eleven = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            eleven.add("{{u" + i + "}} was sent as empty text");
        }
        com.editora.http.HttpTests tests = new com.editora.http.HttpTests(
                List.of(new com.editora.http.HttpTests.Test("a", true, "")), List.of(), null);
        List<String[]> headers = List.<String[]>of(new String[] {"content-type", "application/json"});
        show(p, 900, 600);
        FxTestSupport.runOnFx(() -> p.getScene() // the app's widths: its tab padding and the compact strip's
                .getStylesheets()
                .add(HttpClientPanel.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm()));
        showIn(p, exchange("n", ok("{\"x\":1}", headers, eleven).withTests(tests, List.of())));
        Label status = p.controlsForTest().statusLabel();
        double previousHistory = Double.MAX_VALUE;
        int previousRows = 1;
        boolean previousMeta = true;
        // Where each step happens depends on the platform's fonts, so check the rules at every width.
        for (int width = 900; width >= 260; width -= 20) {
            int w = width;
            Object[] seen = FxTestSupport.callOnFx(() -> {
                p.resize(w, 600); // directly: a stage's new width reaches its scene on a later pulse
                p.applyCss();
                p.layout();
                p.layout(); // the strip settles its fit in the pass after a width change…
                p.applyCss(); // …and its compact padding in the next
                p.layout();
                HttpPanelLayout.HeaderFit fit = p.headerForTest().fitForTest();
                double tabsRight = 0;
                for (HttpResponseTab t : HttpResponseTab.values()) {
                    var b = p.tabsForTest().button(t);
                    if (b.isVisible()) {
                        tabsRight = Math.max(
                                tabsRight, b.localToScene(b.getLayoutBounds()).getMaxX());
                    }
                }
                return new Object[] {
                    status.getText(),
                    ((Text) status.lookup(".text")).getText(),
                    fit,
                    p.controlsForTest().historyPicker().getWidth(),
                    tabsRight,
                    p.getWidth(),
                    p.tabTextForTest(HttpResponseTab.NOTES)
                };
            });
            HttpPanelLayout.HeaderFit fit = (HttpPanelLayout.HeaderFit) seen[2];
            double history = (double) seen[3];
            assertEquals(seen[0], seen[1], "at " + w + " px the status was cut");
            assertTrue(
                    history >= HttpPanelLayout.HISTORY_MIN_WIDTH - 0.5,
                    "at " + w + " px History is below its least: " + history);
            if (fit.rows() == previousRows && fit.meta() == previousMeta) {
                assertTrue(history <= previousHistory + 0.5, "at " + w + " px History grew while the panel shrank");
            }
            assertTrue(
                    fit.rows() > 1
                            || fit.meta()
                            || !previousMeta
                            || previousHistory <= HttpPanelLayout.HISTORY_MIN_WIDTH + 40,
                    "at " + w + " px the timing hid before History had narrowed");
            assertTrue(
                    fit.rows() == 1 || previousRows > 1 || previousHistory <= HttpPanelLayout.HISTORY_MIN_WIDTH + 40,
                    "at " + w + " px the pickers wrapped before History had narrowed: " + fit + " after "
                            + previousHistory);
            assertTrue(
                    (double) seen[4] <= (double) seen[5] + 0.5,
                    "at " + w + " px a tab is cut off: " + seen[4] + " > " + seen[5]);
            previousHistory = history;
            previousRows = fit.rows();
            previousMeta = fit.meta();
            if (w == 900) {
                assertEquals("Notes 11", seen[6], "a wide strip names its tabs");
            }
        }
        assertEquals("11", FxTestSupport.callOnFx(() -> p.tabTextForTest(HttpResponseTab.NOTES)), "at 260 px");
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
        HttpRunList list = p.runListForTest();
        await(list::isShown);
        assertTrue(
                FxTestSupport.callOnFx(list::titleForTest)
                        .startsWith(tr("httppanel.runList.title") + " · " + tr("httppanel.runList.cancelled", 2, 4)),
                FxTestSupport.callOnFx(list::titleForTest));
        assertTrue(
                FxTestSupport.callOnFx(list::getAccessibleText).startsWith(tr("httppanel.runAllCancelled", 2, 4, 0L)),
                "not \"all 2 succeeded\"");
        assertEquals(
                List.of("A", "B"),
                FxTestSupport.callOnFx(() -> list.listForTest().getItems().stream()
                        .map(HttpRunRows.Row::name)
                        .toList()),
                "the two that finished are listed");
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
                FxTestSupport.callOnFx(p::runNoticeForTest),
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
        assertTrue(
                FxTestSupport.callOnFx(p::statusForTest).startsWith(HttpPanelStatesFxTest.runningPrefix("Slow")),
                "…under the running status (named by its ### title; the URL is in its tooltip)");

        FxTestSupport.runOnFx(() -> {
            p.controlsForTest().historyPicker().setValue(null);
            c.cancelRun(b);
        });
        String status = FxTestSupport.callOnFx(p::statusForTest);
        assertTrue(
                status.startsWith(
                        tr("httppanel.cancelledAfter", "").replace(" s", "").strip()),
                status);
        assertEquals("", FxTestSupport.callOnFx(p::headersForTest), "no old response under \"Cancelled\"");
        assertNull(FxTestSupport.callOnFx(p::getSelectedExchange));
        // U7: the response shown before the run is its "Previous" row again, selected in History; a click shows it.
        assertNotNull(FxTestSupport.callOnFx(p::previousRowForTest));
        assertNotNull(
                FxTestSupport.callOnFx(() -> p.controlsForTest().historyPicker().getValue()));
        FxTestSupport.runOnFx(p::clickPreviousForTest);
        assertTrue(FxTestSupport.callOnFx(p::headersForTest).startsWith("HTTP"), "the row shows its response");
        assertTrue(FxTestSupport.callOnFx(p::statusForTest).startsWith("200 OK"));
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
