package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LspManager}'s request/response round-trips: the params it sends and the neutral values it hands
 * back. These are the paths that produce everything the user sees — go-to-definition targets, the Structure
 * outline, formatting edits, semantic tokens — and none of them had a test, because reaching them needed a
 * forked server.
 *
 * <p>Two themes get particular attention because they are Java-specific and have bitten before: the
 * {@code jdt://} class-file target that go-to-definition must keep rather than drop (#665), and the
 * <b>degradation contract</b> — every one of these methods swallows a transport failure into an empty result
 * on purpose, so a failing server must never surface as an exception on the FX thread.
 */
@Tag("fx")
class LspManagerRequestsFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        fakes.clear();
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
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

    /** Capabilities the next session is attached with; a test sets this before opening. */
    private ServerCapabilities capabilities = new ServerCapabilities();

    private FakeLanguageServer open() {
        manager.openDocument(file, root, "java", "class A {}");
        return fakes.get(0);
    }

    /** Runs a request and blocks for its FX-thread callback, returning what it delivered. */
    private <T> T await(Consumer<Consumer<T>> request) throws Exception {
        var result = new AtomicReference<T>();
        var latch = new CountDownLatch(1);
        request.accept(v -> {
            result.set(v);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the callback never fired");
        return result.get();
    }

    private static Location location(String uri, int line, int ch) {
        return new Location(uri, new Range(new Position(line, ch), new Position(line, ch + 1)));
    }

    // --- definition, incl. the jdt:// library target (#665) ------------------------------------------

    @Test
    void definitionMapsAFileLocationToATarget() throws Exception {
        var fake = open();
        Path other = root.resolve("B.java");
        Files.writeString(other, "class B {}");
        fake.definitionResponse = List.of(location(other.toUri().toString(), 4, 7));

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 1, 2, cb));

        assertEquals(1, targets.size());
        assertEquals(other, targets.get(0).file());
        assertEquals(4, targets.get(0).line());
        assertEquals(7, targets.get(0).character());
        assertNull(targets.get(0).classFileUri(), "a real file target carries no class-file URI");
    }

    /**
     * A definition inside a JDK/dependency class arrives under a {@code jdt://} URI, which no filesystem can
     * open. Dropping it made {@code M-.} on {@code String}/{@code List} report "no definition" — the most
     * common Java navigation there is (#665). It must survive as a path-less target carrying the URI.
     */
    @Test
    void definitionKeepsAJdtClassFileTargetInsteadOfDroppingIt() throws Exception {
        var fake = open();
        String jdt = "jdt://contents/java.base/java.lang/String.class?=demo/foo";
        fake.definitionResponse = List.of(location(jdt, 120, 4));

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 1, 2, cb));

        assertEquals(1, targets.size(), "a jdt:// definition must not be dropped (#665)");
        assertNull(targets.get(0).file(), "it has no filesystem path");
        assertEquals(jdt, targets.get(0).classFileUri());
        assertEquals(120, targets.get(0).line());
    }

    /** References stay file-only — the References panel is file-based, so a jdt:// entry has nowhere to go. */
    @Test
    void referencesDropNonFileUris() throws Exception {
        var fake = open();
        Path other = root.resolve("B.java");
        Files.writeString(other, "class B {}");
        fake.referenceResponse = List.of(
                location(other.toUri().toString(), 1, 1),
                location("jdt://contents/java.base/java.lang/String.class", 2, 2));

        List<LspManager.Target> targets = await(cb -> manager.references(file, 1, 2, cb));

        assertEquals(1, targets.size(), "only the file reference is usable");
        assertEquals(other, targets.get(0).file());
    }

    // --- implementation / type definition / declaration (#735, #736) ---------------------------------

    /**
     * The three navigation siblings share one response walk with definition. What a test has to pin is that
     * each still goes out as <b>its own</b> request — routing two of them to the same server method compiles,
     * runs, and answers plausibly, which is precisely the failure this catches.
     */
    @Test
    void eachNavigationRequestGoesOutAsItsOwnMethod() throws Exception {
        var fake = open();

        List<LspManager.Target> ignoredImpl = await(cb -> manager.implementation(file, 3, 4, cb));
        List<LspManager.Target> ignoredType = await(cb -> manager.typeDefinition(file, 5, 6, cb));
        List<LspManager.Target> ignoredDecl = await(cb -> manager.declaration(file, 7, 8, cb));
        assertNotNull(ignoredImpl);
        assertNotNull(ignoredType);
        assertNotNull(ignoredDecl);

        assertEquals(1, fake.implementations.size(), "textDocument/implementation");
        assertEquals(1, fake.typeDefinitions.size(), "textDocument/typeDefinition");
        assertEquals(1, fake.declarations.size(), "textDocument/declaration");
        assertTrue(fake.definitions.isEmpty(), "none of them may fall through to definition");
        assertEquals(new Position(3, 4), fake.implementations.get(0).getPosition());
        assertEquals(new Position(5, 6), fake.typeDefinitions.get(0).getPosition());
        assertEquals(new Position(7, 8), fake.declarations.get(0).getPosition());
        assertEquals(
                file.toUri().toString(),
                fake.implementations.get(0).getTextDocument().getUri(),
                "the request must name the file it was asked about");
    }

    @Test
    void implementationMapsLocationsToTargets() throws Exception {
        var fake = open();
        Path other = root.resolve("B.java");
        Files.writeString(other, "class B {}");
        fake.implementationResponse = List.of(location(other.toUri().toString(), 4, 7));

        List<LspManager.Target> targets = await(cb -> manager.implementation(file, 1, 2, cb));

        assertEquals(1, targets.size());
        assertEquals(other, targets.get(0).file());
        assertEquals(4, targets.get(0).line());
        assertEquals(7, targets.get(0).character());
    }

    /**
     * Unlike references, an implementation can live inside a dependency — implementing a library interface is
     * ordinary. It shares definition's walk precisely so the {@code jdt://} target survives here too, and the
     * coordinator can open it as read-only source rather than reporting nothing found.
     */
    @Test
    void implementationKeepsAJdtClassFileTarget() throws Exception {
        var fake = open();
        String jdt = "jdt://contents/java.base/java.lang/Comparable.class?=demo/foo";
        fake.implementationResponse = List.of(location(jdt, 12, 4));

        List<LspManager.Target> targets = await(cb -> manager.implementation(file, 1, 2, cb));

        assertEquals(1, targets.size());
        assertNull(targets.get(0).file());
        assertEquals(jdt, targets.get(0).classFileUri());
    }

    @Test
    void typeDefinitionAndDeclarationMapTheirOwnResponses() throws Exception {
        var fake = open();
        Path other = root.resolve("B.java");
        Files.writeString(other, "class B {}");
        fake.typeDefinitionResponse = List.of(location(other.toUri().toString(), 1, 1));
        fake.declarationResponse = List.of(location(other.toUri().toString(), 9, 9));

        List<LspManager.Target> typeTargets = await(cb -> manager.typeDefinition(file, 0, 0, cb));
        List<LspManager.Target> declTargets = await(cb -> manager.declaration(file, 0, 0, cb));

        assertEquals(1, typeTargets.get(0).line());
        assertEquals(9, declTargets.get(0).line());
    }

    /** A transport failure degrades to an empty list — never an exception on the FX thread. */
    @Test
    void theNavigationRequestsDegradeOnServerFailure() throws Exception {
        var fake = open();
        fake.failEverything = true;

        List<LspManager.Target> impl = await(cb -> manager.implementation(file, 1, 1, cb));
        List<LspManager.Target> type = await(cb -> manager.typeDefinition(file, 1, 1, cb));
        List<LspManager.Target> decl = await(cb -> manager.declaration(file, 1, 1, cb));

        assertTrue(impl.isEmpty());
        assertTrue(type.isEmpty());
        assertTrue(decl.isEmpty());
    }

    /**
     * The capability gates decide whether the command runs at all or reports "this server doesn't support
     * it". A server that advertises nothing must read as unsupported, not as "nothing found".
     */
    @Test
    void theCapabilityGatesFollowWhatTheServerAdvertises() {
        capabilities = new ServerCapabilities();
        capabilities.setImplementationProvider(Either.forLeft(true));
        capabilities.setTypeDefinitionProvider(Either.forLeft(false));
        open();

        assertTrue(manager.supportsImplementation(file));
        assertFalse(manager.supportsTypeDefinition(file), "advertised false");
        assertFalse(manager.supportsDeclaration(file), "not advertised at all");
    }

    /** A server that omits the (spec-required) range must not produce a bogus target or an exception. */
    @Test
    void aRangelessLocationIsSkipped() throws Exception {
        var fake = open();
        // Built via setters, not the ctor: lsp4j's constructors reject nulls, but gson bypasses them when
        // decoding the wire — so a non-conforming server really can hand us a range-less Location.
        var rangeless = new Location();
        rangeless.setUri(root.resolve("B.java").toUri().toString());
        fake.definitionResponse = List.of(rangeless);

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 1, 2, cb));

        assertTrue(targets.isEmpty(), "a range-less location cannot be navigated to");
    }

    // --- formatting ----------------------------------------------------------------------------------

    @Test
    void formattingMapsEditsAndSendsTheTabSizeHints() throws Exception {
        var fake = open();
        fake.formattingResponse = List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 4)), "    "));

        List<com.editora.editor.LspTextEdit> edits = await(cb -> manager.formatDocument(file, 2, true, cb));

        assertEquals(1, edits.size());
        assertEquals(0, edits.get(0).startLine());
        assertEquals(4, edits.get(0).endCol());
        assertEquals("    ", edits.get(0).newText());
        var sent = FakeLanguageServer.last(fake.formattings);
        assertEquals(2, sent.getOptions().getTabSize(), "the tab-size hint must reach the server");
        assertTrue(sent.getOptions().isInsertSpaces());
    }

    /** A null newText must become "" rather than a null that later NPEs inside the editor. */
    @Test
    void aNullNewTextBecomesEmptyString() throws Exception {
        var fake = open();
        var edit = new TextEdit(); // setters, not the ctor — see aRangelessLocationIsSkipped
        edit.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        fake.formattingResponse = List.of(edit);

        List<com.editora.editor.LspTextEdit> edits = await(cb -> manager.formatDocument(file, 4, true, cb));

        assertEquals("", edits.get(0).newText());
    }

    // --- semantic tokens: range vs full, chosen by capability ----------------------------------------

    private static ServerCapabilities semanticCaps(boolean range, boolean full) {
        var prov = new SemanticTokensWithRegistrationOptions();
        prov.setLegend(new SemanticTokensLegend(List.of("variable", "parameter"), List.of("declaration")));
        prov.setRange(range ? Either.forLeft(true) : null);
        prov.setFull(full ? Either.forLeft(true) : null);
        var caps = new ServerCapabilities();
        caps.setSemanticTokensProvider(prov);
        return caps;
    }

    @Test
    void aRangeCapableServerGetsARangeRequestClampedToTheDocument() throws Exception {
        capabilities = semanticCaps(true, true);
        var fake = open();
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 1, 0));

        List<com.editora.editor.SemanticToken> tokens =
                await(cb -> manager.requestSemanticTokens(file, 0, 26, 27, 0, cb));

        assertEquals(1, fake.semanticRanges.size(), "a range-capable server must get the range request");
        assertTrue(fake.semanticFulls.isEmpty());
        assertEquals(
                26,
                FakeLanguageServer.last(fake.semanticRanges).getRange().getEnd().getLine(),
                "the range must be clamped to the document, as inlay hints are (#715)");
        assertEquals(1, tokens.size(), "the decoded token should come back");
    }

    /** jdtls advertises range=false, full=true — it must take the whole-document path instead. */
    @Test
    void aRangelessServerFallsBackToAFullRequest() throws Exception {
        capabilities = semanticCaps(false, true);
        var fake = open();
        fake.semanticTokensResponse = new SemanticTokens(List.of(0, 0, 3, 1, 0));

        List<com.editora.editor.SemanticToken> ignored =
                await(cb -> manager.requestSemanticTokens(file, 0, 26, 27, 0, cb));
        assertNotNull(ignored);

        assertTrue(fake.semanticRanges.isEmpty(), "a range-less server must not get a range request");
        assertEquals(1, fake.semanticFulls.size(), "it takes the full path (jdtls's shape)");
    }

    /** No legend ⇒ nothing can be decoded, so no request should be made at all. */
    @Test
    void aServerWithoutASemanticLegendIsNotAsked() throws Exception {
        capabilities = new ServerCapabilities();
        var fake = open();

        List<com.editora.editor.SemanticToken> tokens =
                await(cb -> manager.requestSemanticTokens(file, 0, 10, 11, 0, cb));

        assertTrue(tokens.isEmpty());
        assertTrue(fake.semanticRanges.isEmpty() && fake.semanticFulls.isEmpty());
    }

    // --- inlay hints ---------------------------------------------------------------------------------

    @Test
    void inlayHintsAreMappedToSpansAndBlankLabelsDropped() throws Exception {
        var fake = open();
        var withLabel = new org.eclipse.lsp4j.InlayHint(new Position(6, 38), Either.forLeft("name:"));
        var blank = new org.eclipse.lsp4j.InlayHint(new Position(7, 10), Either.forLeft("   "));
        fake.inlayHintResponse = List.of(withLabel, blank);

        List<LspManager.InlayHintSpan> spans = await(cb -> manager.requestInlayHints(file, 0, 26, 27, 0, cb));

        assertEquals(1, spans.size(), "a blank label carries no information and must be dropped");
        assertEquals(6, spans.get(0).line());
        assertEquals(38, spans.get(0).col());
        assertEquals("name:", spans.get(0).label());
    }

    // --- document + workspace symbols ----------------------------------------------------------------

    @Test
    void documentSymbolsAreMappedToTheNeutralTree() throws Exception {
        var fake = open();
        var method = new org.eclipse.lsp4j.DocumentSymbol(
                "greet",
                org.eclipse.lsp4j.SymbolKind.Method,
                new Range(new Position(3, 0), new Position(5, 1)),
                new Range(new Position(3, 8), new Position(3, 13)));
        var cls = new org.eclipse.lsp4j.DocumentSymbol(
                "A",
                org.eclipse.lsp4j.SymbolKind.Class,
                new Range(new Position(0, 0), new Position(9, 1)),
                new Range(new Position(0, 6), new Position(0, 7)));
        cls.setChildren(List.of(method));
        fake.documentSymbolResponse = List.of(Either.forRight(cls));

        List<SymbolNode> symbols = await(cb -> manager.documentSymbols(file, cb));

        assertEquals(1, symbols.size());
        assertEquals("A", symbols.get(0).name());
        assertEquals("class", symbols.get(0).kind());
        assertEquals(1, symbols.get(0).children().size(), "a class keeps its members");
        assertEquals("greet", symbols.get(0).children().get(0).name());
    }

    @Test
    void workspaceSymbolsHandleTheModernShape() throws Exception {
        var fake = open();
        Path other = root.resolve("B.java");
        Files.writeString(other, "class B {}");
        var ws = new org.eclipse.lsp4j.WorkspaceSymbol(
                "B",
                org.eclipse.lsp4j.SymbolKind.Class,
                Either.forLeft(location(other.toUri().toString(), 0, 6)));
        fake.workspaceSymbolResponse = List.of(ws);

        List<LspManager.WorkspaceSymbolMatch> matches = await(cb -> manager.workspaceSymbols(file, "B", cb));

        assertEquals(1, matches.size());
        assertEquals("B", matches.get(0).name());
        assertEquals("class", matches.get(0).kind());
        assertEquals(other, matches.get(0).file());
        assertEquals("B", FakeLanguageServer.last(fake.workspaceSymbols).getQuery(), "the query must be sent");
    }

    // --- rename --------------------------------------------------------------------------------------

    @Test
    void prepareRenameMapsThePlaceholderShape() throws Exception {
        var fake = open();
        var prep =
                new org.eclipse.lsp4j.PrepareRenameResult(new Range(new Position(2, 4), new Position(2, 9)), "greet");
        fake.prepareRenameResponse = org.eclipse.lsp4j.jsonrpc.messages.Either3.forSecond(prep);

        LspManager.RenamePrep result = await(cb -> manager.prepareRename(file, 2, 5, cb));

        assertTrue(result.allowed());
        assertEquals("greet", result.placeholder());
        assertEquals(2, result.startLine());
        assertEquals(9, result.endCol());
    }

    /** A null response means "renaming is not valid here" per the spec — it must refuse, not proceed. */
    @Test
    void prepareRenameRefusesOnANullResponse() throws Exception {
        open();
        LspManager.RenamePrep result = await(cb -> manager.prepareRename(file, 2, 5, cb));
        assertTrue(!result.allowed(), "a null prepareRename means rename is not possible here");
    }

    // --- the degradation contract --------------------------------------------------------------------

    /**
     * Every one of these swallows a transport failure into an empty result <em>by design</em>, so a failing
     * or half-dead server degrades instead of throwing on the FX thread. Asserted together because it is a
     * contract of the layer, not of any one method.
     */
    @Test
    void aFailingServerDegradesToEmptyResultsRatherThanThrowing() throws Exception {
        var fake = open();
        fake.failEverything = true;

        List<LspManager.Target> defs = await(cb -> manager.definition(file, 1, 1, cb));
        List<LspManager.Target> refs = await(cb -> manager.references(file, 1, 1, cb));
        List<SymbolNode> docSyms = await(cb -> manager.documentSymbols(file, cb));
        List<LspManager.WorkspaceSymbolMatch> wsSyms = await(cb -> manager.workspaceSymbols(file, "q", cb));
        List<com.editora.editor.LspTextEdit> fmt = await(cb -> manager.formatDocument(file, 4, true, cb));
        List<LspManager.InlayHintSpan> hints = await(cb -> manager.requestInlayHints(file, 0, 5, 6, 0, cb));
        LspManager.RenamePrep prep = await(cb -> manager.prepareRename(file, 1, 1, cb));

        assertTrue(defs.isEmpty(), "definition");
        assertTrue(refs.isEmpty(), "references");
        assertTrue(docSyms.isEmpty(), "documentSymbols");
        assertTrue(wsSyms.isEmpty(), "workspaceSymbols");
        assertTrue(fmt.isEmpty(), "formatDocument");
        assertTrue(hints.isEmpty(), "inlayHints");
        assertTrue(!prep.allowed(), "prepareRename");
    }

    /** Requests against a file with no session must still call back (empty) rather than hang. */
    @Test
    void requestsForAnUnmanagedFileCallBackEmpty() throws Exception {
        Path unopened = root.resolve("Nope.java");
        Files.writeString(unopened, "class Nope {}");

        List<LspManager.Target> defs = await(cb -> manager.definition(unopened, 0, 0, cb));
        List<SymbolNode> syms = await(cb -> manager.documentSymbols(unopened, cb));
        List<LspManager.InlayHintSpan> hints = await(cb -> manager.requestInlayHints(unopened, 0, 1, 2, 0, cb));
        List<com.editora.editor.LspTextEdit> fmt = await(cb -> manager.formatDocument(unopened, 4, true, cb));

        assertTrue(defs.isEmpty());
        assertTrue(syms.isEmpty());
        assertTrue(hints.isEmpty());
        assertTrue(fmt.isEmpty());
    }

    /** Callbacks must arrive on the FX thread — they touch the editor directly. */
    @Test
    void callbacksAreDeliveredOnTheFxThread() throws Exception {
        open();
        var onFx = new AtomicReference<Boolean>();
        var latch = new CountDownLatch(1);
        manager.documentSymbols(file, s -> {
            onFx.set(Platform.isFxApplicationThread());
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNotNull(onFx.get());
        assertTrue(onFx.get(), "results are applied to the editor, so they must land on the FX thread");
    }
}
