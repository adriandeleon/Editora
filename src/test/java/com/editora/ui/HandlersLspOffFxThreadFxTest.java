package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;
import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.editor.fold.FoldManager;
import com.editora.editor.markdown.MarkdownRenderer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspTestHooks;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1), the language-server handlers: each test drives on the FX thread, the way an event does, what
 * used to diff, parse or encode there — an edit in a buffer synced to a (wired) fake server, the fold regions
 * recomputed when the language or the server's regions change and a fold command asked meanwhile, a Markdown hover —
 * and listens to {@link BlockingSites}: every named place is heard off the FX thread, none on it.
 *
 * <p>What it cannot see: work at a place that does not name itself (it checks the named places, not every loop); the
 * few LSP messages still encoded where they are sent (the resolves, {@code workspace/executeCommand} and
 * {@code workspace/didChangeConfiguration}, whose parameters the caller may still hold); and a real server's own
 * timing (the fake answers at once).
 */
@Tag("fx")
class HandlersLspOffFxThreadFxTest {

    private record Heard(String site, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        if (fx != null) {
            fx.close();
        }
    }

    private void listen() {
        heard.clear();
        BlockingSites.listenForTest((site, thread) -> heard.add(new Heard(site, Platform.isFxApplicationThread())));
    }

    private void awaitHeard(String site) throws Exception {
        SettingsRig.awaitFx(
                site + " heard off FX",
                () -> heard.stream().anyMatch(h -> h.site().equals(site) && !h.fx()));
    }

    private void assertNothingOnFx() {
        BlockingSites.listenForTest(null);
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "work done in place on the FX thread; heard: " + heard);
    }

    private static ServerCapabilities incremental() {
        ServerCapabilities caps = LspTestHooks.caps();
        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setChange(TextDocumentSyncKind.Incremental);
        caps.setTextDocumentSync(sync);
        return caps;
    }

    @Test
    void anEditSyncedToTheServerIsDiffedAndEncodedOffTheFxThread() throws Exception {
        fx = new LspCoordinatorFixture(root, incremental(), true);
        EditorBuffer b = fx.open("A.java", "class A {\n    void go() {}\n}\n");
        FakeLanguageServer server = fx.server();
        SettingsRig.awaitFx("the server opened the file", () -> !new ArrayList<>(server.opened).isEmpty());
        server.hoverResponse = new Hover(new MarkupContent("markdown", "**hi**"));
        listen();
        // Typed, then a hover: the request syncs the text on screen first, both sent from the FX thread.
        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(b.getArea().getLength(), "// typed\n");
            fx.coordinator.showHover();
        });
        SettingsRig.awaitFx("the change reached the server", () -> lastChange(server) != null);
        DidChangeTextDocumentParams change = lastChange(server);
        assertNotNull(change.getContentChanges().get(0).getRange(), "an incremental splice");
        assertEquals("// typed\n", change.getContentChanges().get(0).getText());
        SettingsRig.awaitFx("the hover shown", () -> popup() != null);
        awaitHeard(LspTestHooks.SYNC_DIFF_SITE);
        awaitHeard(LspTestHooks.ENCODE_SITE);
        // A command with arguments the caller keeps: frozen as sent, encoded on the writer like the rest.
        boolean[] answered = {false};
        java.util.Map<String, Object> arg = new java.util.HashMap<>(java.util.Map.of("k", "v"));
        Path file = root.resolve("A.java");
        FxTestSupport.runOnFx(() -> fx.manager.executeCommand(
                file, "editora.test", new ArrayList<>(List.of(arg)), (r, e) -> answered[0] = true));
        SettingsRig.awaitFx("the command answered", () -> answered[0]);
        assertEquals(1, new ArrayList<>(server.executedCommands).size());
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    /** A hover with a code fence in a language whose grammar may be cold: it is never loaded on the FX thread. */
    @Test
    void aCodeFenceInAHoverLoadsItsGrammarOffTheFxThread() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        EditorBuffer b = fx.open("A.java", "class A {\n    void go() {}\n}\n");
        fx.awaitHighlighted(b);
        fx.caret(b, 1, 9);
        fx.server().hoverResponse = new Hover(
                new MarkupContent("markdown", "```elixir\ndefmodule A do\nend\n```\n\n```python\nx = 1\n```"));
        listen();
        fx.run(() -> fx.coordinator.showHover());
        SettingsRig.awaitFx("the hover shown", () -> popup() != null);
        FxTestSupport.drainFx();
        assertNothingOnFx(); // GrammarRegistry.LOAD_SITE, when a grammar was cold, was heard off FX only
    }

    private static DidChangeTextDocumentParams lastChange(FakeLanguageServer server) {
        try {
            List<DidChangeTextDocumentParams> copy = new ArrayList<>(server.changed);
            return copy.isEmpty() ? null : copy.get(copy.size() - 1);
        } catch (java.util.ConcurrentModificationException racing) {
            return null;
        }
    }

    private Popup popup() {
        return FxTestSupport.field(fx.coordinator.popups, "hoverPopup");
    }

    @Test
    void aMarkdownHoverIsParsedOffTheFxThreadAndShownWhenItLands() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        EditorBuffer b = fx.open("A.java", "class A {\n    void go() {}\n}\n");
        fx.awaitHighlighted(b);
        fx.caret(b, 1, 9);
        fx.server().hoverResponse = new Hover(new MarkupContent("markdown", "The **go** method."));
        listen();
        fx.run(() -> fx.coordinator.showHover());
        SettingsRig.awaitFx("the hover shown", () -> popup() != null);
        awaitHeard(MarkdownRenderer.POPUP_PARSE_SITE);
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    /** A hover hidden before its parse lands is never shown. */
    @Test
    void aHoverHiddenBeforeItsParseLandsStaysHidden() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        EditorBuffer b = fx.open("A.java", "class A {\n    void go() {}\n}\n");
        fx.awaitHighlighted(b);
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        CodeArea area = FxTestSupport.callOnFx(b::getArea);
        FxTestSupport.runOnFx(() -> {
            fx.coordinator.popups.showHoverPopup(area, "The **go** method.", List.of());
            fx.coordinator.popups.hideHoverPopup(); // a caret move, a scroll, Escape
        });
        assertEquals(1, held.size(), "the parse was asked for");
        held.forEach(Runnable::run);
        FxTestSupport.drainFx();
        assertNull(popup(), "a stale parse shows nothing");
    }

    @Test
    void foldRegionsAreDetectedOffTheFxThreadAndAFoldAskedMeanwhileWaitsForThem() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        StringBuilder text = new StringBuilder("/*\n * a comment\n */\nclass A {\n");
        for (int i = 0; i < 200; i++) {
            text.append("    void m")
                    .append(i)
                    .append("() {\n        int x = ")
                    .append(i)
                    .append(";\n    }\n");
        }
        text.append("}\n");
        EditorBuffer b = fx.open("A.java", text.toString());
        listen();
        FoldManager folds = FxTestSupport.callOnFx(b::getFoldManager);
        boolean[] foldedAtOnce = {true};
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(0); // in the block comment, lines 0 to 2
            folds.setLanguage("java"); // a detection the command waits for
            folds.foldAtCaret(); // asked before the regions are known: waits, never acts on the old ones
            foldedAtOnce[0] = b.getArea().isFolded(1);
        });
        SettingsRig.awaitFx("the regions detected", () -> !folds.regions().isEmpty());
        SettingsRig.awaitFx(
                "the waiting fold ran once they landed", () -> b.getArea().isFolded(1));
        FxTestSupport.runOnFx(folds::unfoldAll);
        awaitHeard(FoldManager.DETECT_SITE);
        int[] count = {-1};
        FxTestSupport.runOnFx(() -> folds.foldAllBlockComments(n -> count[0] = n));
        SettingsRig.awaitFx("the comments folded", () -> count[0] >= 0);
        assertEquals(1, count[0], "the one block comment");
        awaitHeard(FoldManager.KIND_SCAN_SITE);
        FxTestSupport.runOnFx(() -> b.setLspFoldingRegions(List.of(new com.editora.text.FoldRegions.Region(3, 10))));
        SettingsRig.awaitFx(
                "the server's regions applied",
                () -> folds.regions().stream().anyMatch(r -> r.startLine() == 3 && r.endLine() == 10));
        FxTestSupport.drainFx();
        assertNothingOnFx();
        assertTrue(!foldedAtOnce[0], "nothing folded before the regions were known");
    }
}
