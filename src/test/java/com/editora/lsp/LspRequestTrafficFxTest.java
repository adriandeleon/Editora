package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much {@link LspManager} leaves a language server to do: what it sends when the editor repeats itself
 * (every typing pause asks for diagnostics, the outline, folding ranges, semantic tokens and inlay hints
 * again), what it withdraws, and what it stops before the FX thread.
 *
 * <p>The server here is one that never answers ({@code FakeLanguageServer.holdReplies}) — jdtls while it
 * indexes — because that is where repeating requests hurts: every one that is neither cancelled nor
 * skipped stays queued on the server ahead of the request whose answer is actually wanted.
 */
@Tag("fx")
class LspRequestTrafficFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private final List<Path> diagnosed = new CopyOnWriteArrayList<>();
    private ServerCapabilities capabilities = everything(false);
    private Path file;

    /** A server offering each of the repeated requests; semantic tokens whole-document or by range. */
    private static ServerCapabilities everything(boolean semanticRange) {
        var caps = new ServerCapabilities();
        caps.setDiagnosticProvider(new org.eclipse.lsp4j.DiagnosticRegistrationOptions());
        caps.setDocumentSymbolProvider(true);
        caps.setFoldingRangeProvider(true);
        caps.setInlayHintProvider(true);
        caps.setDocumentHighlightProvider(true);
        var tokens = new SemanticTokensWithRegistrationOptions();
        tokens.setLegend(new SemanticTokensLegend(List.of("variable"), List.of()));
        tokens.setFull(Either.forLeft(true));
        tokens.setRange(semanticRange ? Either.forLeft(true) : null);
        caps.setSemanticTokensProvider(tokens);
        return caps;
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((path, diagnostics) -> diagnosed.add(path), (type, message) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.attachForTest(fake, capabilities);
        });
        manager.configure(true, Map.of("java", "jdtls"));
        file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private FakeLanguageServer open() {
        manager.openDocument(file, root, "java", "class A {}");
        return fakes.get(0);
    }

    /** Everything the editor asks for when typing pauses and the caret rests. */
    private void askForEverything() {
        manager.pullDiagnostics(file);
        manager.latestDocumentSymbols(file, symbols -> {});
        manager.foldingRanges(file, regions -> {});
        manager.requestSemanticTokens(file, 0, 0, 1, 10, tokens -> {});
        manager.requestInlayHints(file, 0, 0, 1, 10, hints -> {});
        manager.documentHighlights(file, 0, 6, spans -> {});
    }

    private static void drainFx() throws Exception {
        var barrier = new CountDownLatch(1);
        Platform.runLater(barrier::countDown);
        assertTrue(barrier.await(10, TimeUnit.SECONDS));
    }

    private static final int KINDS = 6;

    /**
     * Twenty typing pauses against a server that is not answering used to leave 120 requests queued on it,
     * 114 of them for text that no longer exists. Now each pause withdraws the previous pause's requests.
     */
    @Test
    void typingAgainstASlowServerKeepsOneRequestPerKindAndCancelsTheRest() {
        FakeLanguageServer fake = open();
        fake.holdReplies = true;
        int pauses = 20;

        for (int i = 0; i < pauses; i++) {
            manager.changeDocument(file, "class A { int f" + i + "; }");
            askForEverything();
        }

        assertEquals(KINDS * pauses, fake.held.size(), "precondition: every pause asked for every kind");
        assertEquals(KINDS, fake.unanswered(), "one unanswered request per kind, however long the user types");
        assertEquals(KINDS * (pauses - 1), fake.cancelled(), "every superseded request was cancelled");
        assertEquals(KINDS, manager.latestRequestsInFlight());
    }

    /** Scroll-settle, caret rest and server refreshes re-ask without the document having changed. */
    @Test
    void askingAgainForTheSameDocumentVersionSendsNothingWhileTheAnswerIsOutstanding() {
        FakeLanguageServer fake = open();
        fake.holdReplies = true;

        for (int i = 0; i < 10; i++) {
            askForEverything();
        }

        assertEquals(KINDS, fake.held.size(), "one request per kind; the other nine rounds sent nothing");
        assertEquals(0, fake.cancelled());
        assertEquals(1, fake.diagnosticPulls.size());
        assertEquals(1, fake.documentSymbols.size());
        assertEquals(1, fake.foldingRanges.size());
        assertEquals(1, fake.semanticFulls.size());
        assertEquals(1, fake.inlayHints.size());
        assertEquals(1, fake.highlights.size());
    }

    /** A different range or position is a different question: the old one is withdrawn, the new one asked. */
    @Test
    void aDifferentRangeSupersedesInsteadOfJoining() {
        FakeLanguageServer fake = open();
        fake.holdReplies = true;

        manager.requestInlayHints(file, 0, 0, 3, 10, hints -> {});
        manager.requestInlayHints(file, 1, 2, 3, 10, hints -> {});
        manager.documentHighlights(file, 0, 6, spans -> {});
        manager.documentHighlights(file, 0, 7, spans -> {});

        assertEquals(2, fake.inlayHints.size());
        assertEquals(2, fake.highlights.size());
        assertEquals(2, fake.cancelled());
        assertEquals(2, fake.unanswered());
    }

    @Test
    void theSupersededCallbackNeverRunsAndTheLatestOneGetsTheAnswer() throws Exception {
        FakeLanguageServer fake = open();
        fake.holdReplies = true;
        List<String> delivered = new CopyOnWriteArrayList<>();

        manager.latestDocumentSymbols(file, symbols -> delivered.add("first"));
        manager.changeDocument(file, "class A { int f; }");
        manager.latestDocumentSymbols(file, symbols -> delivered.add("second"));
        manager.latestDocumentSymbols(file, symbols -> delivered.add("third")); // same version: joins
        @SuppressWarnings("unchecked")
        var running = (java.util.concurrent.CompletableFuture<Object>) fake.held.get(1);
        running.complete(List.of());
        drainFx();

        assertEquals(List.of("third"), delivered);
    }

    /** A save can change what a pull returns for unchanged text, so it must not join an older pull. */
    @Test
    void aSaveWithdrawsAnOutstandingPullSoTheNextOneIsSent() {
        FakeLanguageServer fake = open();
        fake.holdReplies = true;

        manager.pullDiagnostics(file);
        manager.saveDocument(file, "class A {}");
        manager.pullDiagnostics(file);

        assertEquals(2, fake.diagnosticPulls.size());
        assertEquals(1, fake.cancelled());
    }

    /** What a server refresh request relies on: its announcement must not be answered by an older reply. */
    @Test
    void invalidatingADocumentSendsEveryRefreshableKindAfresh() {
        FakeLanguageServer fake = open();
        fake.holdReplies = true;
        askForEverything();

        manager.invalidateRequests(file);
        askForEverything();

        // Everything a server can ask to have refreshed; the caret's highlight request is left running.
        assertEquals(KINDS * 2 - 1, fake.held.size());
        assertEquals(KINDS - 1, fake.cancelled());
        assertEquals(1, fake.highlights.size());
    }

    @Test
    void closingADocumentCancelsItsRequestsAndReleasesItsBookkeeping() {
        FakeLanguageServer fake = open();
        fake.holdReplies = true;
        askForEverything();
        assertEquals(1, manager.diagnosticGenerationEntries());

        manager.closeDocument(file);

        assertEquals(KINDS, fake.cancelled());
        assertEquals(0, manager.latestRequestsInFlight());
        assertEquals(0, manager.diagnosticGenerationEntries(), "one entry per closed document used to stay");
    }

    // --- diagnostics for files that are not open ------------------------------------------------------

    private static PublishDiagnosticsParams publish(Path path) {
        return new PublishDiagnosticsParams(
                path.toUri().toString(),
                List.of(new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), "boom")));
    }

    /**
     * jdtls publishes for every file of a project it imports. With the Problems window showing open files
     * only, none of those may reach the FX thread at all — nor leave an entry behind per file.
     */
    @Test
    void publishesForUnopenedFilesAreDroppedBeforeTheFxThreadInOpenDocumentScope() throws Exception {
        open();
        manager.setOpenDocumentDiagnosticsOnly(true);
        LanguageServerSession session = manager.sessionForTest(file);

        for (int i = 0; i < 200; i++) {
            session.publishDiagnostics(publish(root.resolve("Unopened" + i + ".java")));
        }
        session.publishDiagnostics(publish(file));
        drainFx();

        assertEquals(List.of(file), diagnosed, "only the open document's publish is delivered");
        assertEquals(1, manager.diagnosticGenerationEntries(), "nothing is remembered for the other 200");
    }

    /** Project scope (after a workspace build) still gets everything — and still remembers only open files. */
    @Test
    void projectScopeDeliversUnopenedFilesWithoutTrackingThem() throws Exception {
        open();
        LanguageServerSession session = manager.sessionForTest(file);
        Path other = root.resolve("Unopened.java");

        session.publishDiagnostics(publish(other));
        session.publishDiagnostics(publish(other));
        session.publishDiagnostics(publish(file));
        drainFx();

        assertEquals(List.of(other, other, file), diagnosed);
        assertEquals(1, manager.diagnosticGenerationEntries());
    }

    /**
     * Removing the generation entry on close must not let a reply for the closed document through — nor
     * for the same file reopened, whose own count starts again.
     */
    @Test
    void aPublishQueuedBeforeACloseIsRejectedEvenAfterTheFileIsReopened() throws Exception {
        open();
        LanguageServerSession session = manager.sessionForTest(file);
        var entered = new CountDownLatch(1);
        var hold = new CountDownLatch(1);
        Platform.runLater(() -> {
            entered.countDown();
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        try {
            session.publishDiagnostics(publish(file)); // queued behind the blocked FX thread
            manager.closeDocument(file);
            manager.openDocument(file, root, "java", "class A {}");
        } finally {
            hold.countDown();
        }
        drainFx();

        assertTrue(diagnosed.isEmpty(), "diagnostics computed for the closed document must not be shown");
    }

    // --- whole-document semantic tokens ---------------------------------------------------------------

    @Test
    void wholeDocumentTokensAreCurrentUntilTheDocumentChanges() throws Exception {
        FakeLanguageServer fake = open();
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 0, 0));
        assertFalse(manager.wholeDocumentTokensCurrent(file), "nothing delivered yet");

        var delivered = new CountDownLatch(1);
        manager.requestSemanticTokens(file, 0, 0, 1, 10, tokens -> delivered.countDown());
        assertTrue(delivered.await(10, TimeUnit.SECONDS));
        assertTrue(manager.wholeDocumentTokensCurrent(file));

        manager.changeDocument(file, "class A { int f; }");
        assertFalse(manager.wholeDocumentTokensCurrent(file), "the server holds newer text than the tokens");
    }

    @Test
    void aRefreshMakesWholeDocumentTokensStale() throws Exception {
        FakeLanguageServer fake = open();
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 0, 0));
        var delivered = new CountDownLatch(1);
        manager.requestSemanticTokens(file, 0, 0, 1, 10, tokens -> delivered.countDown());
        assertTrue(delivered.await(10, TimeUnit.SECONDS));

        manager.invalidateRequests(file);

        assertFalse(manager.wholeDocumentTokensCurrent(file));
    }

    /** With range requests a scroll does show new lines, so there is never a "current" whole document. */
    @Test
    void aRangeCapableServerIsNeverCurrent() throws Exception {
        capabilities = everything(true);
        FakeLanguageServer fake = open();
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 0, 0));
        var delivered = new CountDownLatch(1);
        manager.requestSemanticTokens(file, 0, 0, 1, 10, tokens -> delivered.countDown());
        assertTrue(delivered.await(10, TimeUnit.SECONDS));

        assertFalse(manager.wholeDocumentTokensCurrent(file));
        assertEquals(1, fake.semanticRanges.size());
    }
}
