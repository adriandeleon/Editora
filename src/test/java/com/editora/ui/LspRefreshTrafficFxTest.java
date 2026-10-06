package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.lsp.SymbolNode;
import org.eclipse.lsp4j.SemanticTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link LspCoordinator} asks language servers for when nothing the user did calls for it: a server
 * announcing capabilities one registration at a time, a typing pause with the Structure window closed, a
 * scroll over a document whose tokens the server can only send whole.
 *
 * <p>Driven with real buffers and a real {@link LspManager} over recording fakes, so every assertion is a
 * count of requests that reached a server.
 */
@Tag("fx")
class LspRefreshTrafficFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeHost host;
    private FakeOps ops;
    private List<FakeLanguageServer> fakes;

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }
    }

    private static final class FakeOps extends LspOpsStub {
        final AtomicInteger capabilitiesReady = new AtomicInteger();
        volatile CountDownLatch capabilitiesFlushed = new CountDownLatch(1);
        StructurePanel structure;

        @Override
        public void onServerCapabilitiesReady() {
            capabilitiesReady.incrementAndGet();
            capabilitiesFlushed.countDown();
        }

        @Override
        public StructurePanel structurePanel() {
            return structure;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(manager, LspTestHooks.refreshableCaps());
        manager.configure(true, Map.of("java", "jdtls"));
        host = new FakeHost();
        host.settings.setSemanticHighlight(true);
        host.settings.setInlayHints(true);
        ops = new FakeOps();
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(manager::shutdownAll);
    }

    /** A wired, synced java buffer in {@code project} (its own LSP root); it becomes the active buffer. */
    private EditorBuffer open(String project, String name) throws Exception {
        Path dir = root.resolve(project);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>"); // a root marker: one server per project
        Path f = dir.resolve(name);
        Files.writeString(f, "class A {}\n");
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(f);
            b.setContent("class A {}\n");
            host.buffers.add(b);
            host.active = b;
            coordinator.wireBuffer(b);
            // These tests play each typing pause and scroll-settle by hand; the buffer's own 300 ms settle
            // timer firing in the middle of one would add requests depending on how fast the test runs.
            FxTestSupport.invoke(FxTestSupport.field(b, "settledEdits"), "dispose");
            return b;
        });
    }

    private void forget(FakeLanguageServer fake) throws Exception {
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            fake.diagnosticPulls.clear();
            fake.foldingRanges.clear();
            fake.semanticFulls.clear();
            fake.semanticDeltas.clear();
            fake.inlayHints.clear();
            fake.documentSymbols.clear();
        });
    }

    private static int semanticRequests(FakeLanguageServer fake) {
        return fake.semanticFulls.size() + fake.semanticDeltas.size();
    }

    /** Twenty registrations that leave the providers this test counts as they are. */
    private static final List<String> REGISTERED = List.of(
            "textDocument/completion",
            "textDocument/signatureHelp",
            "textDocument/hover",
            "textDocument/definition",
            "textDocument/implementation",
            "textDocument/typeDefinition",
            "textDocument/declaration",
            "textDocument/references",
            "textDocument/documentHighlight",
            "textDocument/documentSymbol",
            "textDocument/codeAction",
            "textDocument/formatting",
            "textDocument/rangeFormatting",
            "textDocument/onTypeFormatting",
            "textDocument/rename",
            "textDocument/selectionRange",
            "textDocument/prepareCallHierarchy",
            "textDocument/prepareTypeHierarchy",
            "workspace/symbol",
            "workspace/executeCommand");

    // --- capability and "ready" refreshes ------------------------------------------------------------

    /**
     * jdtls and tinymist register their features one request at a time after initialize. Each registration
     * used to re-request diagnostics, folding ranges, semantic tokens and inlay hints for every open tab at
     * once: 20 registrations × 20 tabs × 4 requests. Now the burst is one refresh, and the two kinds whose
     * replies only the visible tab uses are asked for that tab alone.
     */
    @Test
    void aBurstOfCapabilityRegistrationsCostsOneRefreshOfTheServersDocuments() throws Exception {
        int tabs = 20;
        EditorBuffer last = null;
        for (int i = 0; i < tabs; i++) {
            last = open("a", "A" + i + ".java");
        }
        FakeLanguageServer fake = fakes.get(0);
        assertEquals(1, fakes.size(), "precondition: one project, one server");
        forget(fake);
        ops.capabilitiesReady.set(0);
        ops.capabilitiesFlushed = new CountDownLatch(1);
        Path any = last.getPath();

        for (String method : REGISTERED) {
            LspTestHooks.registerCapability(manager, any, method);
        }
        assertTrue(ops.capabilitiesFlushed.await(30, TimeUnit.SECONDS), "the coalesced refresh never ran");
        FxTestSupport.drainFx();

        assertEquals(tabs, fake.diagnosticPulls.size(), "one pull per open document, not one per registration");
        assertEquals(tabs, fake.foldingRanges.size());
        assertEquals(1, semanticRequests(fake), "semantic tokens: only the tab on screen");
        assertEquals(1, fake.inlayHints.size(), "inlay hints: only the tab on screen");
        assertEquals(1, ops.capabilitiesReady.get(), "one capability notification for the burst");
    }

    /** A server speaks for its own documents: another project's server and tabs are not re-requested. */
    @Test
    void aRefreshReachesOnlyTheDocumentsOfTheServerThatAsked() throws Exception {
        EditorBuffer a = open("a", "A.java");
        EditorBuffer b = open("b", "B.java");
        assertEquals(2, fakes.size(), "precondition: two projects, two servers");
        FakeLanguageServer fakeA = fakes.get(0);
        FakeLanguageServer fakeB = fakes.get(1);
        forget(fakeA);
        forget(fakeB);

        LspTestHooks.registerCapability(manager, a.getPath(), "textDocument/hover");
        LspTestHooks.refreshSemanticTokens(manager, a.getPath());
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(coordinator::flushRefreshes);

        assertEquals(1, fakeA.diagnosticPulls.size());
        assertEquals(1, fakeA.foldingRanges.size());
        assertTrue(
                fakeB.diagnosticPulls.isEmpty() && fakeB.foldingRanges.isEmpty() && semanticRequests(fakeB) == 0,
                "the other server was asked nothing");
        assertEquals(0, semanticRequests(fakeA), "a's tab is in the background: its tokens wait until it is shown");
        assertNotNull(b);
    }

    /** The other half of "only the visible tab": the skipped request is made when the tab is shown. */
    @Test
    void aBackgroundTabGetsItsTokensAndHintsWhenItIsShown() throws Exception {
        EditorBuffer background = open("a", "A.java");
        open("a", "B.java"); // now the active tab
        FakeLanguageServer fake = fakes.get(0);
        forget(fake);

        LspTestHooks.registerCapability(manager, background.getPath(), "textDocument/hover");
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(coordinator::flushRefreshes);
        assertEquals(1, semanticRequests(fake), "only the active tab so far");
        assertEquals(1, fake.inlayHints.size());

        FxTestSupport.runOnFx(() -> {
            host.active = background;
            coordinator.onBufferShown(background);
        });

        assertEquals(2, semanticRequests(fake), "the shown tab catches up");
        assertEquals(2, fake.inlayHints.size());

        FxTestSupport.runOnFx(() -> coordinator.onBufferShown(background));
        assertEquals(2, semanticRequests(fake), "and owes nothing the second time");
    }

    /** "Ready" takes the same coalesced, per-server route (jdtls says it again when its import finishes). */
    @Test
    void aReadyStatusRefreshesThatServersDocumentsOnce() throws Exception {
        EditorBuffer a = open("a", "A.java");
        open("b", "B.java");
        FakeLanguageServer fakeA = fakes.get(0);
        FakeLanguageServer fakeB = fakes.get(1);
        forget(fakeA);
        forget(fakeB);

        for (int i = 0; i < 5; i++) {
            LspTestHooks.languageStatus(manager, a.getPath(), "ServiceReady");
        }
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(coordinator::flushRefreshes);

        assertEquals(1, fakeA.diagnosticPulls.size(), "five announcements, one refresh");
        assertTrue(fakeB.diagnosticPulls.isEmpty(), "and not for the other server");
    }

    // --- the Structure outline ------------------------------------------------------------------------

    private StructurePanel structurePanel(EditorBuffer buffer) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StructurePanel panel = new StructurePanel();
            panel.attach(buffer);
            ops.structure = panel;
            return panel;
        });
    }

    private void typingPause(EditorBuffer b, String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.getArea().replaceText(text);
            b.sendLspChange(); // didChange…
            ((Runnable) FxTestSupport.field(b, "lspDiagnosticsRequester")).run(); // …and what rides its debounce
        });
        FxTestSupport.drainFx();
    }

    /** The outline was requested on every typing pause whether or not anything displayed it. */
    @Test
    void typingWithTheStructureWindowClosedDoesNotRequestDocumentSymbols() throws Exception {
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        fake.documentSymbolResponse = List.of();
        structurePanel(b); // never put in a scene: the tool window is closed
        forget(fake);

        for (int i = 0; i < 5; i++) {
            typingPause(b, "class A { int f" + i + "; }\n");
        }

        assertTrue(fake.documentSymbols.isEmpty(), "nobody is looking at the outline");
        assertEquals(5, fake.diagnosticPulls.size(), "precondition: the pauses did reach the server");
        assertEquals(5, fake.foldingRanges.size());
    }

    @Test
    void anOpenStructureWindowStillGetsTheOutlineOnEveryPause() throws Exception {
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        StructurePanel panel = structurePanel(b);
        FxTestSupport.runOnFx(() -> new Scene(new StackPane(panel), 300, 400));
        forget(fake);

        for (int i = 0; i < 3; i++) {
            typingPause(b, "class A { int f" + i + "; }\n");
        }

        assertEquals(3, fake.documentSymbols.size());
    }

    /** Opening the window must bring the outline up to date: the refreshes it skipped are owed. */
    @Test
    void showingTheStructureWindowRequestsTheOutlineItSkipped() throws Exception {
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        StructurePanel panel = structurePanel(b);
        forget(fake);
        typingPause(b, "class A { int f; }\n");
        assertTrue(fake.documentSymbols.isEmpty());

        FxTestSupport.runOnFx(() -> new Scene(new StackPane(panel), 300, 400));

        assertEquals(1, fake.documentSymbols.size(), "shown: fetch the outline for the text as it is now");
    }

    /**
     * Jump to Structure reads the outline with the window closed. It must not be handed symbols whose lines
     * belong to older text, and it must get the server's outline — fetched when it asks.
     */
    @Test
    void thePickerGetsAFreshServerOutlineWithTheWindowClosed() throws Exception {
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        StructurePanel panel = structurePanel(b);
        AtomicInteger reloads = new AtomicInteger();
        FxTestSupport.runOnFx(() -> {
            panel.setOnHiddenOutlineChanged(reloads::incrementAndGet);
            panel.setLspSymbols(b, List.of(new SymbolNode("Stale", "", "class", 0, 0, List.of())));
        });
        forget(fake);
        reloads.set(0);
        var symbol = new org.eclipse.lsp4j.DocumentSymbol(
                "Fresh",
                org.eclipse.lsp4j.SymbolKind.Class,
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 0), new org.eclipse.lsp4j.Position(1, 5)),
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(1, 0), new org.eclipse.lsp4j.Position(1, 5)));
        fake.documentSymbolResponse = List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(symbol));

        typingPause(b, "\nclass Fresh {}\n"); // skipped: the held symbols are now stale
        List<StructurePanel.Outline> atOpen = FxTestSupport.callOnFx(panel::outline);
        FxTestSupport.drainFx(); // the reply
        List<StructurePanel.Outline> afterReply = FxTestSupport.callOnFx(panel::outline);

        assertTrue(atOpen.stream().noneMatch(o -> o.label().contains("Stale")), "stale symbols: " + atOpen);
        assertEquals(1, fake.documentSymbols.size(), "the picker asked for the outline, once");
        assertEquals(1, reloads.get(), "and is told when it arrives");
        assertTrue(afterReply.stream().anyMatch(o -> o.label().contains("Fresh")), "outline: " + afterReply);
    }

    // --- scrolling ------------------------------------------------------------------------------------

    private static void semanticRequester(EditorBuffer b) {
        ((Runnable) FxTestSupport.field(b, "semanticTokensRequester")).run();
    }

    /**
     * jdtls has no range requests: every semantic-tokens request returns the whole document. Scroll-settle
     * re-requested it each time although nothing a scroll does can change the answer.
     */
    @Test
    void scrollingDoesNotRefetchWholeDocumentTokens() throws Exception {
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 0, 0));
        forget(fake);
        FxTestSupport.runOnFx(() -> semanticRequester(b)); // the settle after an edit: fetches
        FxTestSupport.drainFx();
        assertEquals(1, semanticRequests(fake), "precondition: the buffer has its tokens");
        int hintsBefore = fake.inlayHints.size();

        for (int i = 0; i < 10; i++) {
            FxTestSupport.runOnFx(() -> semanticRequester(b)); // what each scroll-settle runs
            FxTestSupport.drainFx();
        }

        assertEquals(1, semanticRequests(fake), "ten scrolls, no whole-document transfer");
        assertEquals(hintsBefore + 10, fake.inlayHints.size(), "inlay hints are by range and do follow the scroll");
    }

    /** An edit — even one the server is never told about — makes the buffer's tokens stale: fetch again. */
    @Test
    void tokensAreFetchedAgainAfterAnEdit() throws Exception {
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 0, 0));
        forget(fake);
        FxTestSupport.runOnFx(() -> semanticRequester(b));
        FxTestSupport.drainFx();

        // Type a character and delete it: the text the server holds is unchanged, the buffer's tokens are not.
        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(0, "x");
            b.getArea().deleteText(0, 1);
            b.sendLspChange();
            semanticRequester(b);
        });
        FxTestSupport.drainFx();
        assertEquals(2, semanticRequests(fake), "a net-zero edit still needs its tokens back");

        typingPause(b, "class B {}\n");
        FxTestSupport.runOnFx(() -> semanticRequester(b));
        FxTestSupport.drainFx();
        assertEquals(3, semanticRequests(fake), "a real edit: new text, new tokens");
    }

    /** A server's own refresh request is not a scroll: it says the answer changed, so it is fetched. */
    @Test
    void aServerRefreshStillFetchesWholeDocumentTokens() throws Exception {
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 0, 0));
        forget(fake);
        FxTestSupport.runOnFx(() -> semanticRequester(b));
        FxTestSupport.drainFx();

        LspTestHooks.refreshSemanticTokens(manager, b.getPath());
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(coordinator::flushRefreshes);

        assertEquals(2, semanticRequests(fake));
    }

    /** Lays the buffer out in a scene tall enough to scroll, then scrolls it. */
    private static void scroll(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.getArea().replaceText("line\n".repeat(400));
            StackPane pane = new StackPane(b.getNode());
            new Scene(pane, 400, 300);
            pane.applyCss();
            pane.layout();
        });
        FxTestSupport.drainFx();
        double before = FxTestSupport.callOnFx(
                () -> b.getArea().estimatedScrollYProperty().getValue());
        FxTestSupport.runOnFx(() -> {
            b.getArea().showParagraphAtTop(200);
            b.getNode().getScene().getRoot().layout();
        });
        FxTestSupport.drainFx();
        double after = FxTestSupport.callOnFx(
                () -> b.getArea().estimatedScrollYProperty().getValue());
        assertTrue(after != before, "precondition: the view scrolled (" + before + " → " + after + ")");
    }

    /**
     * The buffer's scroll-settle only runs while semantic highlighting is on, so with it off scrolling never
     * fetched inlay hints for the lines scrolled to.
     */
    @Test
    void scrollingRefreshesInlayHintsWhenSemanticHighlightingIsOff() throws Exception {
        host.settings.setSemanticHighlight(false);
        EditorBuffer b = open("a", "A.java");
        FakeLanguageServer fake = fakes.get(0);
        forget(fake);
        assertFalse(FxTestSupport.callOnFx(b::isSemanticActive), "precondition");
        PauseTransition settle = FxTestSupport.field(coordinator, "inlayScrollSettle");

        scroll(b);
        boolean armed = FxTestSupport.callOnFx(() -> settle.getStatus() == Animation.Status.RUNNING);
        assertTrue(armed || fake.inlayHints.size() == 1, "the scroll armed the settle timer (or it already ran)");
        FxTestSupport.runOnFx(() -> {
            settle.stop();
            settle.getOnFinished().handle(null); // the debounce elapsing; a no-op if it already has
        });

        assertEquals(1, fake.inlayHints.size());
    }

    /** With semantic highlighting on the buffer's own scroll-settle covers it; no second trigger. */
    @Test
    void theExtraScrollTriggerStaysOutOfTheWayWhenSemanticHighlightingIsOn() throws Exception {
        EditorBuffer b = open("a", "A.java");
        assertTrue(FxTestSupport.callOnFx(b::isSemanticActive), "precondition");
        PauseTransition settle = FxTestSupport.field(coordinator, "inlayScrollSettle");

        scroll(b);

        assertEquals(Animation.Status.STOPPED, FxTestSupport.callOnFx(settle::getStatus));
    }
}
