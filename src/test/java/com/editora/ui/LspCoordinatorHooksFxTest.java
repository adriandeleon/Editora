package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.completion.CompletionResult;
import com.editora.completion.CompletionSource;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspTestHooks;
import com.editora.lsp.WorkspaceEditHazards;
import com.editora.lsp.WorkspaceEditJournal;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of {@link LspCoordinator} nobody invokes as a command: the hooks it gives each buffer (what the
 * editor calls while the user types, pastes, or presses Tab), the questions it puts to the user before
 * something irreversible, and what it does when a server asks for a refresh or refuses to start.
 */
@Tag("fx")
class LspCoordinatorHooksFxTest {

    private static final String SOURCE = "class A {\n    void go() {}\n}\n";

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.refreshableCaps());
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    /** A buffer that keeps the hooks the coordinator gives it, so a test can call them as the editor does. */
    private static class HookedBuffer extends EditorBuffer {
        CompletionSource completion;
        BiConsumer<Object, Consumer<String>> completionDoc;
        LspRangeFormatter rangeFormatter;
        LspOnTypeFormatter onTypeFormatter;
        LspPasteImportsRequester pasteImports;
        LspSmartSemicolonRequester smartSemicolon;
        Consumer<Character> signatureHelp;
        Runnable occurrences;
        Runnable diagnostics;
        Runnable semanticTokens;
        Consumer<String> change;
        BiConsumer<Integer, List<EditorBuffer.CodeLens>> codeLens;
        List<EditorBuffer.CodeLens> lenses;

        @Override
        public void setLspCompletionSource(CompletionSource source) {
            completion = source;
            super.setLspCompletionSource(source);
        }

        @Override
        public void setCompletionDocResolver(BiConsumer<Object, Consumer<String>> resolver) {
            completionDoc = resolver;
            super.setCompletionDocResolver(resolver);
        }

        @Override
        public void setLspRangeFormatter(LspRangeFormatter formatter) {
            rangeFormatter = formatter;
            super.setLspRangeFormatter(formatter);
        }

        @Override
        public void setLspOnTypeFormatter(LspOnTypeFormatter formatter) {
            onTypeFormatter = formatter;
            super.setLspOnTypeFormatter(formatter);
        }

        @Override
        public void setLspPasteImportsRequester(LspPasteImportsRequester requester) {
            pasteImports = requester;
            super.setLspPasteImportsRequester(requester);
        }

        @Override
        public void setLspSmartSemicolonRequester(LspSmartSemicolonRequester requester) {
            smartSemicolon = requester;
            super.setLspSmartSemicolonRequester(requester);
        }

        @Override
        public void setSignatureHelpRequester(Consumer<Character> requester) {
            signatureHelp = requester;
            super.setSignatureHelpRequester(requester);
        }

        @Override
        public void setOccurrenceRequester(Runnable requester) {
            occurrences = requester;
            super.setOccurrenceRequester(requester);
        }

        @Override
        public void setLspDiagnosticsRequester(Runnable requester) {
            diagnostics = requester;
            super.setLspDiagnosticsRequester(requester);
        }

        @Override
        public void setSemanticTokensRequester(Runnable requester) {
            semanticTokens = requester;
            super.setSemanticTokensRequester(requester);
        }

        @Override
        public void setLspChangeListener(Consumer<String> listener) {
            change = listener;
            super.setLspChangeListener(listener);
        }

        @Override
        public void setCodeLensHandler(BiConsumer<Integer, List<EditorBuffer.CodeLens>> handler) {
            codeLens = handler;
            super.setCodeLensHandler(handler);
        }

        @Override
        public void setCodeLenses(List<EditorBuffer.CodeLens> lenses) {
            this.lenses = lenses;
            super.setCodeLenses(lenses);
        }
    }

    /** A wired buffer on the fake server (wiring it is what {@code MainController.addBuffer} does). */
    private HookedBuffer wired(String name) throws Exception {
        HookedBuffer buffer = fx.open(name, SOURCE, FxTestSupport.callOnFx(HookedBuffer::new));
        FxTestSupport.runOnFx(() -> fx.coordinator.wireBuffer(buffer));
        return buffer;
    }

    /** A wired buffer with no file: nothing a language server could manage. */
    private HookedBuffer wiredUnmanaged() throws Exception {
        HookedBuffer buffer = FxTestSupport.callOnFx(HookedBuffer::new);
        FxTestSupport.runOnFx(() -> {
            fx.show(buffer);
            fx.host.active = buffer;
            fx.coordinator.wireBuffer(buffer);
        });
        return buffer;
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    // --- the hooks a buffer is given -----------------------------------------------------------------

    @Test
    void completionAndItsDocumentationComeFromTheServerForAManagedBuffer() throws Exception {
        var options = new CompletionOptions();
        options.setResolveProvider(true);
        fx.capabilities.setCompletionProvider(options);
        HookedBuffer buffer = wired("A.java");
        var item = new CompletionItem("goFaster");
        fx.server().completionFuture = CompletableFuture.completedFuture(Either.forLeft(List.of(item)));
        fx.server().completionResolver = unresolved -> {
            var resolved = new CompletionItem(unresolved.getLabel());
            resolved.setDocumentation("Goes faster.");
            return resolved;
        };
        var result = new AtomicReference<CompletionResult>();
        var doc = new AtomicReference<String>();
        var answered = new CountDownLatch(1);

        FxTestSupport.runOnFx(() -> buffer.completion.request(1, 9, 1, null, r -> {
            result.set(r);
            answered.countDown();
        }));
        assertTrue(answered.await(30, TimeUnit.SECONDS), "the completion never came back");
        fx.run(() -> buffer.completionDoc.accept(item, doc::set));

        assertEquals("goFaster", result.get().items().get(0).label());
        assertEquals(new Position(1, 9), fx.server().completions.get(0).getPosition());
        assertEquals("Goes faster.", doc.get());
    }

    @Test
    void theHooksAnswerEmptyAtOnceForABufferNoServerManages() throws Exception {
        HookedBuffer buffer = wiredUnmanaged();
        var completion = new AtomicReference<CompletionResult>();
        var doc = new AtomicReference<>("unset");
        var formatted = new AtomicReference<List<LspTextEdit>>();
        var onType = new AtomicReference<List<LspTextEdit>>();
        var semicolon = new AtomicReference<>(new int[] {-1});

        FxTestSupport.runOnFx(() -> {
            buffer.completion.request(0, 0, 1, null, completion::set).run();
            buffer.completionDoc.accept(new CompletionItem("x"), doc::set);
            buffer.rangeFormatter.format(0, 0, 0, 0, formatted::set);
            buffer.onTypeFormatter.format(0, 0, ';', onType::set);
            buffer.smartSemicolon.request(0, 0, semicolon::set);
            buffer.pasteImports.request(0, 0, 0, 3, "foo", () -> true);
            buffer.change.accept("typed");
            buffer.diagnostics.run();
            buffer.semanticTokens.run();
            buffer.occurrences.run();
            buffer.signatureHelp.accept('(');
        });

        assertSame(CompletionResult.EMPTY, completion.get());
        assertNull(doc.get());
        assertEquals(List.of(), formatted.get());
        assertEquals(List.of(), onType.get());
        assertNull(semicolon.get());
        assertTrue(fx.fakes.isEmpty(), "no server was started for it");
        assertTrue(fx.host.statuses.isEmpty(), "and the automatic hooks say nothing");
    }

    @Test
    void tabAndTypedCharactersAreFormattedByTheServerAgainstTheTextOnScreen() throws Exception {
        fx.capabilities.setDocumentRangeFormattingProvider(true);
        fx.capabilities.setDocumentOnTypeFormattingProvider(
                new org.eclipse.lsp4j.DocumentOnTypeFormattingOptions(";", List.of("}")));
        HookedBuffer buffer = wired("A.java");
        fx.server().formattingResponse = List.of(new TextEdit(range(1, 0, 4), "\t"));
        fx.server().onTypeFormattingResponse = List.of(new TextEdit(range(1, 0, 4), "  "));
        var formatted = new AtomicReference<List<LspTextEdit>>();
        var onType = new AtomicReference<List<LspTextEdit>>();

        fx.run(() -> {
            buffer.setContent("class A {\n    void go() { }\n}\n"); // typed, not yet sent
            buffer.rangeFormatter.format(1, 0, 1, 18, formatted::set);
        });
        assertEquals(List.of(new LspTextEdit(1, 0, 1, 4, "\t")), formatted.get());
        assertEquals(
                range(1, 0, 18),
                FakeLanguageServer.last(fx.server().rangeFormattings).getRange());
        assertEquals(
                fx.host.settings.getTabSize(),
                FakeLanguageServer.last(fx.server().rangeFormattings)
                        .getOptions()
                        .getTabSize());
        assertTrue(
                fx.server().changed.stream()
                        .anyMatch(c -> c.getContentChanges().stream()
                                .anyMatch(e -> e.getText().contains("{ }"))),
                "the server formats the text on screen, so the pending edit is sent first");

        fx.run(() -> buffer.onTypeFormatter.format(1, 18, ';', onType::set));
        assertEquals(List.of(new LspTextEdit(1, 0, 1, 4, "  ")), onType.get());
        assertEquals(";", FakeLanguageServer.last(fx.server().onTypeFormattings).getCh());
    }

    @Test
    void smartSemicolonAndPasteImportsGoToAServerThatAdvertisesThem() throws Exception {
        fx.capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(
                List.of(com.editora.lsp.JdtlsSmartSemicolon.COMMAND, "java.edit.handlePasteEvent")));
        HookedBuffer buffer = wired("A.java");
        var semicolon = new AtomicReference<int[]>();
        fx.server().executeCommandHandler = params -> params.getCommand().equals("java.edit.handlePasteEvent")
                ? null
                : JsonParser.parseString("{\"position\":{\"line\":1,\"character\":16}}");

        fx.run(() -> buffer.smartSemicolon.request(1, 12, semicolon::set));
        assertEquals(List.of(1, 16), List.of(semicolon.get()[0], semicolon.get()[1]));

        fx.run(() -> buffer.pasteImports.request(1, 4, 1, 8, "List", () -> true));
        assertEquals(
                "java.edit.handlePasteEvent",
                FakeLanguageServer.last(fx.server().executedCommands).getCommand(),
                "the paste is reported so the server can add the imports it needs");
    }

    /** A net-zero edit (type, then Backspace) sends the server nothing, so nothing will re-publish the
     *  marks the edit cleared: they are put back from what is known. */
    @Test
    void aNetZeroEditRestoresTheDiagnosticsItCleared() throws Exception {
        List<List<com.editora.editor.LspDiagnostic>> shown = new ArrayList<>();
        HookedBuffer buffer = fx.open("A.java", SOURCE, FxTestSupport.callOnFx(() -> new HookedBuffer() {
            @Override
            public void setLspDiagnostics(List<com.editora.editor.LspDiagnostic> diagnostics) {
                shown.add(diagnostics);
                super.setLspDiagnostics(diagnostics);
            }
        }));
        FxTestSupport.runOnFx(() -> fx.coordinator.wireBuffer(buffer));
        var diagnostic = new com.editora.editor.LspDiagnostic(
                1, 9, 1, 11, com.editora.editor.LspDiagnostic.Severity.ERROR, "x", null, null);
        FxTestSupport.runOnFx(() -> fx.coordinator.onDiagnostics(buffer.getPath(), List.of(diagnostic)));
        shown.clear();

        fx.run(() -> buffer.change.accept(SOURCE)); // the same text the server already has

        // (The editor's own typing-pause timer may call the hook once more; every call must do the same.)
        assertFalse(shown.isEmpty(), "the marks were not put back");
        assertTrue(shown.stream().allMatch(List.of(diagnostic)::equals), "what was put back: " + shown);
        assertTrue(fx.server().changed.isEmpty(), "an unchanged document is not re-sent");
    }

    @Test
    void theTypingPauseHookRefreshesDiagnosticsOutlineFoldsAndTokens() throws Exception {
        HookedBuffer buffer = wired("A.java");
        FakeLanguageServer server = fx.server();
        int pulls = server.diagnosticPulls.size();
        int symbols = server.documentSymbols.size();
        int folds = server.foldingRanges.size();
        int tokens = server.semanticFulls.size();
        int hints = server.inlayHints.size();
        fx.host.settings.setInlayHints(true);
        FxTestSupport.runOnFx(() -> fx.coordinator.applyInlayHints());
        fx.settle();
        hints = server.inlayHints.size();

        fx.run(() -> {
            buffer.setContent("class A {\n    void go() {}\n    int more;\n}\n");
            buffer.sendLspChange();
            buffer.diagnostics.run();
            buffer.semanticTokens.run();
        });

        // "More than before", not "one more": the editor's own typing-pause timer runs the same hooks.
        assertTrue(server.diagnosticPulls.size() > pulls, "diagnostics were not pulled again");
        assertTrue(server.documentSymbols.size() > symbols, "the outline was not refreshed");
        assertTrue(server.foldingRanges.size() > folds, "the folds were not refreshed");
        assertTrue(server.semanticFulls.size() > tokens, "the text changed, so the tokens are stale");
        assertTrue(server.inlayHints.size() > hints, "the hints were not refreshed");
    }

    // --- code lenses ---------------------------------------------------------------------------------

    private static CodeLens lens(int line, String title, String command) {
        var lens = new CodeLens(range(line, 9, 11));
        lens.setCommand(new Command(title, command));
        return lens;
    }

    @Test
    void aClickOnALineWithSeveralLensesAsksWhichOne() throws Exception {
        fx.capabilities.setCodeLensProvider(new org.eclipse.lsp4j.CodeLensOptions(false));
        fx.capabilities.setImplementationProvider(Either.forLeft(true));
        fx.host.settings.setCodeLens(true);
        HookedBuffer buffer = wired("A.java");
        fx.server().codeLensResponse = List.of(
                lens(1, "3 references", "java.show.references"),
                lens(1, "2 implementations", "java.show.implementations"),
                lens(1, "Run | Debug", "java.test.run"));
        Path impl = Files.writeString(root.resolve("Impl.java"), "class Impl {}\n");
        Path user = Files.writeString(root.resolve("User.java"), "class User {}\n");
        fx.server().implementationResponse = List.of(new Location(impl.toUri().toString(), range(0, 6, 10)));
        fx.server().referenceResponse = List.of(new Location(user.toUri().toString(), range(0, 6, 10)));

        fx.run(() -> fx.coordinator.requestCodeLens(buffer));
        assertEquals(
                List.of("3 references", "2 implementations"),
                buffer.lenses.stream().map(EditorBuffer.CodeLens::label).toList(),
                "a lens that only the server's own editor could run is not shown");

        // Both lenses of the line were under the click.
        fx.run(() -> buffer.codeLens.accept(1, buffer.lenses));
        fx.run(() -> fx.choose(1));
        assertEquals(List.of(new LspCoordinatorFixture.Jump(impl, 0, 6)), fx.ops.jumps);
        assertEquals(
                new Position(1, 9),
                fx.server().implementations.get(0).getPosition(),
                "the request is made from the declaration's name, where the lens put the caret");

        // One lens under the click: no question. A references lens lists even a lone reference.
        int opened = fx.ops.referencesWindowOpened;
        fx.run(() -> buffer.codeLens.accept(1, List.of(buffer.lenses.get(0))));
        assertFalse(fx.host.overlay.isShowing());
        assertEquals(opened + 1, fx.ops.referencesWindowOpened);
        assertEquals(1, fx.ops.jumps.size());

        // Nothing under it, or something that is not one of ours: nothing happens.
        fx.run(() -> {
            buffer.codeLens.accept(1, List.of());
            buffer.codeLens.accept(1, List.of(new EditorBuffer.CodeLens(1, "stray", "not a lens span")));
        });
        assertEquals(1, fx.ops.jumps.size());

        fx.host.settings.setCodeLens(false);
        fx.run(() -> fx.coordinator.requestCodeLens(buffer));
        assertNull(buffer.lenses, "turning the setting off clears the lenses");
    }

    // --- refresh requests from the server ------------------------------------------------------------

    @Test
    void aRefreshIsRequestedForTheTabOnScreenAndOwedToTheOthers() throws Exception {
        fx.host.settings.setInlayHints(true);
        fx.host.settings.setSemanticHighlight(true);
        // Not wired: a wired buffer re-requests on its own typing-pause timer, and this test counts requests.
        EditorBuffer background = fx.open("B.java", SOURCE);
        EditorBuffer active = fx.open("A.java", SOURCE);
        FakeLanguageServer server = fx.server();
        String a = active.getPath().toUri().toString();
        String b = background.getPath().toUri().toString();
        int tokensA =
                count(server.semanticFulls.stream().map(p -> p.getTextDocument().getUri()), a);
        int tokensB =
                count(server.semanticFulls.stream().map(p -> p.getTextDocument().getUri()), b);
        int pullsB = count(
                server.diagnosticPulls.stream().map(p -> p.getTextDocument().getUri()), b);
        int foldsB =
                count(server.foldingRanges.stream().map(p -> p.getTextDocument().getUri()), b);
        int hintsA =
                count(server.inlayHints.stream().map(p -> p.getTextDocument().getUri()), a);
        int hintsB =
                count(server.inlayHints.stream().map(p -> p.getTextDocument().getUri()), b);

        fx.run(() -> {
            for (String kind : List.of("diagnostics", "semanticTokens", "inlayHints", "foldingRanges")) {
                LspTestHooks.refresh(fx.manager, active.getPath(), kind);
            }
        });
        // The requests are folded into one flush after a short window; whether that timer has fired yet
        // or not, flushing here leaves the same requests made exactly once.
        fx.run(() -> fx.coordinator.flushRefreshes());

        assertEquals(
                tokensA + 1,
                count(server.semanticFulls.stream().map(p -> p.getTextDocument().getUri()), a));
        assertEquals(
                hintsA + 1,
                count(server.inlayHints.stream().map(p -> p.getTextDocument().getUri()), a));
        assertEquals(
                pullsB + 1,
                count(
                        server.diagnosticPulls.stream()
                                .map(p -> p.getTextDocument().getUri()),
                        b),
                "diagnostics are kept per buffer, so a hidden tab is refreshed too");
        assertEquals(
                foldsB + 1,
                count(server.foldingRanges.stream().map(p -> p.getTextDocument().getUri()), b));
        assertEquals(
                tokensB,
                count(server.semanticFulls.stream().map(p -> p.getTextDocument().getUri()), b),
                "tokens for a hidden tab would be dropped on arrival: not asked for");
        assertEquals(
                hintsB,
                count(server.inlayHints.stream().map(p -> p.getTextDocument().getUri()), b));

        // …until that tab is shown.
        fx.run(() -> {
            fx.host.active = background;
            fx.coordinator.onBufferShown(background);
            fx.coordinator.onBufferShown(null);
        });
        assertEquals(
                tokensB + 1,
                count(server.semanticFulls.stream().map(p -> p.getTextDocument().getUri()), b));
        assertEquals(
                hintsB + 1,
                count(server.inlayHints.stream().map(p -> p.getTextDocument().getUri()), b));
    }

    private static int count(java.util.stream.Stream<String> uris, String uri) {
        return (int) uris.filter(uri::equals).count();
    }

    // --- crashes and withheld starts -----------------------------------------------------------------

    @Test
    void aCrashLeavesAnotherProjectsBuffersAlone() throws Exception {
        Path otherProject = Files.createDirectories(root.resolve("other"));
        Files.writeString(otherProject.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        EditorBuffer elsewhere = fx.open("other/B.java", "class B {}\n");
        EditorBuffer here = fx.open("A.java", SOURCE);
        assertEquals(2, fx.fakes.size(), "two project roots, two servers");

        FxTestSupport.runOnFx(() -> LspTestHooks.simulateServerDeath(fx.manager, here.getPath()));
        fx.settle();

        assertTrue(fx.manager.isManaged(elsewhere.getPath()));
        assertTrue(FxTestSupport.callOnFx(elsewhere::isLspActive), "the other project's server did not crash");
        assertTrue(fx.manager.isManaged(here.getPath()), "the crashed one was restarted");
        assertEquals(3, fx.fakes.size());
    }

    /** A run or debug launch routes through any open Java file — which may be a tab never shown, and so
     *  never opened on the server. */
    @Test
    void ensureManagedOpensADeferredBackgroundTab() throws Exception {
        EditorBuffer active = fx.open("A.java", SOURCE);
        EditorBuffer background = FxTestSupport.callOnFx(EditorBuffer::new);
        Path file = Files.writeString(root.resolve("B.java"), "class B {}\n");
        FxTestSupport.runOnFx(() -> {
            background.setPath(file);
            background.setContent("class B {}\n");
            fx.show(background);
            fx.coordinator.wireBuffer(background); // not the active tab: deferred
        });
        assertFalse(fx.manager.isManaged(file));

        FxTestSupport.runOnFx(() -> {
            fx.coordinator.ensureManaged(null);
            fx.coordinator.ensureManaged(active.getPath()); // already managed: nothing to do
            fx.coordinator.ensureManaged(root.resolve("NotOpen.java"));
            fx.coordinator.ensureManaged(file);
        });

        assertTrue(fx.manager.isManaged(file));
        assertFalse(fx.manager.isManaged(root.resolve("NotOpen.java")));
    }

    /**
     * astro-ls has no TypeScript of its own and would load the folder's — code from a folder nobody has
     * trusted. The server is not started (nothing is forked here: the manager withholds before it would),
     * and the user is told why and which command changes that.
     */
    @Test
    void anAstroServerWithheldForAnUntrustedFolderIsExplained() throws Exception {
        Path site = Files.createDirectories(root.resolve("site"));
        Files.createFile(Files.createDirectories(site.resolve("node_modules/typescript/lib"))
                .resolve("typescript.js"));
        Path page = Files.writeString(site.resolve("index.astro"), "---\n---\n");
        String explained = tr("status.lsp.astroSdkUntrusted", tr("command.lsp.trustProjectSettings"));
        var told = new CountDownLatch(1);
        var settings = new com.editora.config.Settings();
        settings.setAstroLspCommand(root.resolve("absent/astro-ls") + " --stdio");
        EditorBuffer buffer = FxTestSupport.callOnFx(EditorBuffer::new);
        var host = new CoordinatorHostStub() {
            @Override
            public com.editora.config.Settings settings() {
                return settings;
            }

            @Override
            public void forEachBuffer(Consumer<EditorBuffer> action) {
                action.accept(buffer);
            }

            @Override
            public EditorBuffer activeBuffer() {
                return buffer;
            }

            @Override
            public void setStatus(String message) {
                if (explained.equals(message)) {
                    told.countDown();
                }
            }
        };
        var real = new com.editora.lsp.LspManager((f, d) -> {}, (t, m) -> {});
        try {
            real.configure(true, java.util.Map.of("astro", settings.getAstroLspCommand()));
            FxTestSupport.runOnFx(() -> {
                LspCoordinator coordinator = new LspCoordinator(host, real, new LspOpsStub());
                coordinator.setServerAvailableForTest("astro", true);
                buffer.setPath(page);
                buffer.setContent("---\n---\n");
                coordinator.syncBuffer(buffer);
            });

            assertTrue(told.await(30, TimeUnit.SECONDS), "the user was never told why there is no Astro server");
            assertFalse(real.isManaged(page), "a document must not look managed by a server that never started");
            assertFalse(FxTestSupport.callOnFx(buffer::isLspActive));
        } finally {
            real.close();
            FxTestSupport.runOnFx(buffer::dispose);
        }
    }

    // --- the questions asked before something irreversible -------------------------------------------

    /** What a dialog said, and the answer given to it. */
    private record Asked(String header, String content) {}

    /**
     * Answers the next dialog to appear with its button of kind {@code answer}. The future completes with
     * what the dialog said once the answer has been acted on: the code that asked runs on when the dialog
     * closes, and only a task queued behind it can know that it has.
     */
    private CompletableFuture<Asked> answerNextDialog(ButtonBar.ButtonData answer) throws Exception {
        CompletableFuture<Asked> asked = new CompletableFuture<>();
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.isShowing()
                            && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane pane) {
                        stop();
                        Asked said = new Asked(pane.getHeaderText(), pane.getContentText());
                        ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                        .filter(type -> type.getButtonData() == answer)
                                        .findFirst()
                                        .orElseThrow()))
                                .fire();
                        javafx.application.Platform.runLater(() -> asked.complete(said));
                        return;
                    }
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        return asked;
    }

    @Test
    void trustingAFolderShowsWhatItWouldRunAndTakesTheAnswer() throws Exception {
        Path project = Files.createDirectories(root.resolve("site"));
        List<String> requests = List.of("rust: ./tools/ra --stdio", "astro: node_modules/typescript/lib");

        CompletableFuture<Asked> first = answerNextDialog(ButtonBar.ButtonData.OK_DONE);
        boolean trusted = FxTestSupport.callOnFx(() -> fx.coordinator.trustConfirmer.test(project, requests));
        assertTrue(trusted);
        Asked asked = first.get(30, TimeUnit.SECONDS);
        assertEquals(tr("dialog.lsp.trust.header", "site"), asked.header());
        assertEquals(tr("dialog.lsp.trust.body", project.toString(), String.join("\n", requests)), asked.content());

        answerNextDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
        assertFalse(FxTestSupport.callOnFx(() -> fx.coordinator.trustConfirmer.test(project, requests)));
    }

    @Test
    void aDestructiveEditNamesEachPathAndIsOnlyAppliedOnAnExplicitYes() throws Exception {
        Path folder = root.resolve("old");
        Path replaced = root.resolve("Kept.java");
        List<WorkspaceEditHazards.Hazard> hazards = List.of(
                new WorkspaceEditHazards.Hazard(folder, WorkspaceEditHazards.Kind.DELETE_DIRECTORY, null),
                new WorkspaceEditHazards.Hazard(replaced, WorkspaceEditHazards.Kind.OVERWRITE, null));

        CompletableFuture<Asked> first = answerNextDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
        assertFalse(FxTestSupport.callOnFx(() -> fx.coordinator.destructiveEditConfirmer.test(hazards)));
        Asked asked = first.get(30, TimeUnit.SECONDS);
        assertEquals(tr("dialog.lsp.destructiveEdit.header"), asked.header());
        assertEquals(
                tr("dialog.lsp.destructiveEdit.deleteFolder", folder.toString()) + "\n"
                        + tr("dialog.lsp.destructiveEdit.overwrite", replaced.toString()),
                asked.content());

        answerNextDialog(ButtonBar.ButtonData.OK_DONE);
        assertTrue(FxTestSupport.callOnFx(() -> fx.coordinator.destructiveEditConfirmer.test(hazards)));
    }

    /** The default button of the destructive-edit question must be Cancel: Enter may not delete a folder. */
    @Test
    void enterDoesNotConfirmADestructiveEdit() throws Exception {
        List<WorkspaceEditHazards.Hazard> hazards = List.of(
                new WorkspaceEditHazards.Hazard(root.resolve("old"), WorkspaceEditHazards.Kind.DELETE_FILE, null));
        CompletableFuture<List<String>> defaults = new CompletableFuture<>();
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.isShowing()
                            && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane pane) {
                        stop();
                        List<String> out = new ArrayList<>();
                        for (var type : pane.getButtonTypes()) {
                            Button button = (Button) pane.lookupButton(type);
                            if (button.isDefaultButton()) {
                                out.add(type.getButtonData().name());
                            }
                        }
                        defaults.complete(out);
                        ((Button) pane.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
                        return;
                    }
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);

        assertFalse(FxTestSupport.callOnFx(() -> fx.coordinator.destructiveEditConfirmer.test(hazards)));
        assertEquals(List.of("CANCEL_CLOSE"), defaults.get(30, TimeUnit.SECONDS));
    }

    /** Leaves {@code name} moved aside under a hidden name, as a process that died mid-transaction does. */
    private Path interruptedDelete(Path journals, Path project, String name, boolean committed) throws Exception {
        Path lost = Files.writeString(project.resolve(name), "class Lost {}\n");
        Path stage = project.resolve(".editora-lsp-" + name + ".deleted");
        var journal = WorkspaceEditJournal.begin(journals);
        journal.deleting(lost, stage);
        Files.move(lost, stage);
        if (committed) {
            journal.committed();
        }
        journal.abandon();
        return stage;
    }

    @Test
    void anInterruptedRefactoringIsOfferedAndRestoredOnRequest() throws Exception {
        Path journals = root.resolve("journals");
        Path project = Files.createDirectories(root.resolve("project"));
        Path stage = interruptedDelete(journals, project, "Lost.java", false);
        fx.ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> fx.coordinator.workspaceEditJournalDir = journals);

        CompletableFuture<Asked> restore = answerNextDialog(ButtonBar.ButtonData.OK_DONE);
        FxTestSupport.runOnFx(() -> fx.coordinator.offerInterruptedEdits());

        Asked asked = restore.get(30, TimeUnit.SECONDS);
        fx.settle();
        assertEquals(tr("dialog.lsp.interruptedEdit.header"), asked.header());
        assertEquals(project.resolve("Lost.java").toString(), asked.content(), "the files it is about are named");
        assertEquals("class Lost {}\n", Files.readString(project.resolve("Lost.java")));
        assertFalse(Files.exists(stage));
        assertEquals(tr("status.lsp.interruptedEdit.restored", 1), fx.host.lastStatus());
    }

    @Test
    void notNowLeavesAnInterruptedRefactoringForTheNextProjectOpen() throws Exception {
        Path journals = root.resolve("journals");
        Path project = Files.createDirectories(root.resolve("project"));
        Path stage = interruptedDelete(journals, project, "Lost.java", false);
        fx.ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> fx.coordinator.workspaceEditJournalDir = journals);

        CompletableFuture<Asked> later = answerNextDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
        FxTestSupport.runOnFx(() -> fx.coordinator.offerInterruptedEdits());
        later.get(30, TimeUnit.SECONDS);
        fx.settle();

        assertTrue(Files.exists(stage), "nothing moves without a yes");
        assertFalse(Files.exists(project.resolve("Lost.java")));
        try (var kept = Files.list(journals)) {
            assertEquals(1, kept.count(), "the journal is kept to ask again");
        }
        assertTrue(fx.host.statuses.isEmpty());
        // The same project is asked about once per run: a second call raises no dialog (it would block here).
        fx.run(() -> fx.coordinator.offerInterruptedEdits());
        assertTrue(Files.exists(stage));
    }

    @Test
    void leavingAnInterruptedRefactoringAsItIsStopsTheQuestion() throws Exception {
        Path journals = root.resolve("journals");
        Path project = Files.createDirectories(root.resolve("project"));
        Path stage = interruptedDelete(journals, project, "Lost.java", false);
        fx.ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> fx.coordinator.workspaceEditJournalDir = journals);

        CompletableFuture<Asked> keep = answerNextDialog(ButtonBar.ButtonData.OTHER); // "Leave As Is"
        FxTestSupport.runOnFx(() -> fx.coordinator.offerInterruptedEdits());
        keep.get(30, TimeUnit.SECONDS);
        fx.settle();

        assertTrue(Files.exists(stage), "every file stays where it is");
        assertEquals(tr("status.lsp.interruptedEdit.kept", stage.getFileName().toString()), fx.host.lastStatus());
        assertEquals(List.of(), WorkspaceEditJournal.pending(journals), "and it is not offered again");
    }

    /** The edit had been decided; only the clean-up of the old copies was cut short. */
    @Test
    void theOldCopiesOfAFinishedRefactoringAreRemovedOnRequest() throws Exception {
        Path journals = root.resolve("journals");
        Path project = Files.createDirectories(root.resolve("project"));
        Path stage = interruptedDelete(journals, project, "Gone.java", true);
        fx.ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> fx.coordinator.workspaceEditJournalDir = journals);

        CompletableFuture<Asked> asked = answerNextDialog(ButtonBar.ButtonData.OK_DONE);
        FxTestSupport.runOnFx(() -> fx.coordinator.offerInterruptedEdits());

        assertEquals(
                tr("dialog.lsp.interruptedEdit.headerCommitted"),
                asked.get(30, TimeUnit.SECONDS).header());
        fx.settle();
        assertFalse(Files.exists(stage));
        assertFalse(Files.exists(project.resolve("Gone.java")), "a decided delete is not undone");
        assertEquals(tr("status.lsp.interruptedEdit.removed"), fx.host.lastStatus());
    }

    @Test
    void aProjectWithNothingInterruptedIsNotAsked() throws Exception {
        Path journals = Files.createDirectories(root.resolve("journals"));
        FxTestSupport.runOnFx(() -> {
            fx.coordinator.offerInterruptedEdits(); // no project, no journal directory
            fx.coordinator.workspaceEditJournalDir = journals;
            fx.ops.projectRoot = root;
            fx.coordinator.offerInterruptedEdits();
        });
        fx.settle();
        assertTrue(fx.host.statuses.isEmpty());
        assertTrue(fx.host.errors.isEmpty());
    }
}
