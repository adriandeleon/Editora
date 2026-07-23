package com.editora.lsp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LspManager}'s pure capability helpers (trigger chars, semantic-tokens gate,
 *  jdtls initializationOptions). */
class LspManagerTest {

    private static SemanticTokensWithRegistrationOptions stProvider(boolean withLegend, Boolean range, Boolean full) {
        var opts = new SemanticTokensWithRegistrationOptions(); // no-arg: legend stays null (ctor rejects null)
        if (withLegend) {
            opts.setLegend(new SemanticTokensLegend(List.of("keyword"), List.of("static")));
        }
        if (range != null) {
            opts.setRange(range);
        }
        if (full != null) {
            opts.setFull(full);
        }
        return opts;
    }

    @Test
    void extractsTriggerCharactersFromCapabilities() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setCompletionProvider(new CompletionOptions(false, List.of(".", "<", "/")));
        assertEquals(Set.of('.', '<', '/'), LspManager.triggerCharsOf(caps));
    }

    @Test
    void workspaceSymbolProviderDetectedFromEitherForm() {
        assertFalse(LspManager.workspaceSymbolProvider(null));
        assertFalse(LspManager.workspaceSymbolProvider(new ServerCapabilities())); // unset
        ServerCapabilities bool = new ServerCapabilities();
        bool.setWorkspaceSymbolProvider(true);
        assertTrue(LspManager.workspaceSymbolProvider(bool));
        ServerCapabilities off = new ServerCapabilities();
        off.setWorkspaceSymbolProvider(false);
        assertFalse(LspManager.workspaceSymbolProvider(off));
        ServerCapabilities opts = new ServerCapabilities();
        opts.setWorkspaceSymbolProvider(new org.eclipse.lsp4j.WorkspaceSymbolOptions()); // options form → supported
        assertTrue(LspManager.workspaceSymbolProvider(opts));
    }

    @Test
    void usesOnlyTheFirstCharOfMultiCharTriggers() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setCompletionProvider(new CompletionOptions(false, List.of("::", "->")));
        assertEquals(Set.of(':', '-'), LspManager.triggerCharsOf(caps));
    }

    @Test
    void emptyWhenNoCompletionProviderOrNoTriggers() {
        assertTrue(LspManager.triggerCharsOf(null).isEmpty());
        assertTrue(LspManager.triggerCharsOf(new ServerCapabilities()).isEmpty()); // no completionProvider
        ServerCapabilities noTriggers = new ServerCapabilities();
        noTriggers.setCompletionProvider(new CompletionOptions()); // completionProvider, null triggerCharacters
        assertTrue(LspManager.triggerCharsOf(noTriggers).isEmpty());
    }

    @Test
    void semanticTokensProviderSupportedWithRangeOrFull() {
        ServerCapabilities range = new ServerCapabilities();
        range.setSemanticTokensProvider(stProvider(true, true, null));
        assertNotNull(LspManager.semanticTokensProvider(range)); // legend + range → supported

        ServerCapabilities fullOnly = new ServerCapabilities();
        fullOnly.setSemanticTokensProvider(stProvider(true, false, true));
        assertNotNull(LspManager.semanticTokensProvider(
                fullOnly)); // full-only (e.g. some servers) → supported, full fallback
    }

    @Test
    void semanticTokensProviderNullWhenNeitherRangeNorFull() {
        ServerCapabilities neither = new ServerCapabilities();
        neither.setSemanticTokensProvider(stProvider(true, false, false));
        assertNull(LspManager.semanticTokensProvider(neither));

        ServerCapabilities unset = new ServerCapabilities();
        unset.setSemanticTokensProvider(stProvider(true, null, null));
        assertNull(LspManager.semanticTokensProvider(unset)); // range + full both unset
    }

    @Test
    void semanticTokensProviderNullWhenAbsentOrNoLegend() {
        assertNull(LspManager.semanticTokensProvider(null));
        assertNull(LspManager.semanticTokensProvider(new ServerCapabilities())); // no provider at all
        ServerCapabilities noLegend = new ServerCapabilities();
        noLegend.setSemanticTokensProvider(stProvider(false, true, true));
        assertNull(LspManager.semanticTokensProvider(noLegend)); // can't decode without a legend
    }

    @SuppressWarnings("unchecked")
    @Test
    void initOptionsFor() {
        // json/css/html: provideFormatter flips the servers' advertised documentFormattingProvider to true,
        // so Format Document becomes available (#468; verified against the real servers).
        assertEquals(Map.of("provideFormatter", true), LspManager.initOptionsFor("json", List.of()));
        assertEquals(Map.of("provideFormatter", true), LspManager.initOptionsFor("css", List.of()));
        assertEquals(Map.of("provideFormatter", true), LspManager.initOptionsFor("html", List.of()));
        // go keeps its semanticTokens flag; java delegates to javaInitOptions; others need none.
        assertEquals(Map.of("semanticTokens", true), LspManager.initOptionsFor("go", List.of()));
        assertEquals(LspManager.javaInitOptions(List.of()), LspManager.initOptionsFor("java", List.of()));
        // maven-pom (lemminx-maven): turn the extension on with the heavy Central index disabled (local ~/.m2).
        assertEquals(
                Map.of("settings", Map.of("xml", Map.of("maven", Map.of("central", Map.of("skip", true))))),
                LspManager.initOptionsFor("maven-pom", List.of()));
        assertNull(LspManager.initOptionsFor("python", List.of()));
        assertNull(LspManager.initOptionsFor("typescript", List.of()));
        assertNull(LspManager.initOptionsFor(null, List.of()));
    }

    @Test
    void javaInitOptionsAlwaysDisablesAutobuildNoDebugBundles() {
        Map<String, Object> opts = LspManager.javaInitOptions(List.of());
        assertFalse(opts.containsKey("bundles"));
        Map<String, Object> settings = (Map<String, Object>) opts.get("settings");
        Map<String, Object> java = (Map<String, Object>) settings.get("java");
        Map<String, Object> autobuild = (Map<String, Object>) java.get("autobuild");
        assertEquals(Boolean.FALSE, autobuild.get("enabled"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void javaInitOptionsIncludesBundlesWhenDebugging() {
        List<String> jars = List.of("/path/to/java-debug.jar");
        Map<String, Object> opts = LspManager.javaInitOptions(jars);
        assertEquals(jars, opts.get("bundles"));
        Map<String, Object> settings = (Map<String, Object>) opts.get("settings");
        Map<String, Object> java = (Map<String, Object>) settings.get("java");
        Map<String, Object> autobuild = (Map<String, Object>) java.get("autobuild");
        assertEquals(Boolean.FALSE, autobuild.get("enabled")); // autobuild stays off even while debugging
    }

    /**
     * {@code shutdownServer} finds a server's sessions by {@code key.startsWith(serverId + SEP)}. The key was
     * built with a <b>raw NUL byte</b> typed straight into the source while the prefix used a <b>space</b>, so
     * the scan never matched and shutdownServer did nothing at all — silently. Its callers are "disable this
     * server in Settings" (the server kept running and kept publishing diagnostics) and {@code restartServer},
     * which is how toggling Debug is supposed to reload jdtls with the java-debug bundle (it never did).
     *
     * <p>The raw control character is why it survived: it made LspManager.java <b>binary</b> to grep/rg, which
     * skip it without a word — so the two halves could never be compared by searching for them.
     */
    @Test
    void aSessionKeyIsMatchedByTheShutdownPrefixForItsServer() {
        java.nio.file.Path root = java.nio.file.Path.of("/proj");
        String key = LspManager.sessionKey("json", root);
        assertTrue(
                key.startsWith(LspManager.sessionKeyPrefix("json")),
                "shutdownServer(\"json\") must match its own session key: " + key.replace("\u0000", "<NUL>"));
    }

    /** …and must not match a different server whose id merely starts the same. */
    @Test
    void theShutdownPrefixDoesNotMatchASimilarlyNamedServer() {
        java.nio.file.Path root = java.nio.file.Path.of("/proj");
        assertFalse(LspManager.sessionKey("jsonx", root).startsWith(LspManager.sessionKeyPrefix("json")));
        assertFalse(LspManager.sessionKey("java", root).startsWith(LspManager.sessionKeyPrefix("javascript")));
    }

    /** The separator must be a character no server id or URI can contain, or the scan could match wrongly. */
    @Test
    void theSessionKeySeparatorCannotOccurInAnIdOrUri() {
        String key = LspManager.sessionKey("json", java.nio.file.Path.of("/proj"));
        assertEquals(1, key.chars().filter(c -> c == 0).count(), "exactly one separator in the key");
    }

    // --- Signature help (#674) -----------------------------------------------------------------

    @Test
    void signatureHelpProviderAndTriggerCharsExtracted() {
        assertFalse(LspManager.signatureHelpProvider(null));
        assertFalse(LspManager.signatureHelpProvider(new ServerCapabilities()));
        ServerCapabilities caps = new ServerCapabilities();
        var opts = new org.eclipse.lsp4j.SignatureHelpOptions(List.of("(", ","));
        opts.setRetriggerCharacters(List.of(")"));
        caps.setSignatureHelpProvider(opts);
        assertTrue(LspManager.signatureHelpProvider(caps));
        assertEquals(Set.of('(', ',', ')'), LspManager.signatureTriggerCharsOf(caps));
        assertEquals(Set.of(), LspManager.signatureTriggerCharsOf(null));
    }

    // --- Watched files (#677) ------------------------------------------------------------------

    @Test
    void eventsUnderRootFilterByPathContainmentAndMapKinds() {
        var changes = List.of(
                new LspManager.WatchedFile(java.nio.file.Path.of("/proj/src/A.java"), LspManager.WatchedKind.CHANGED),
                new LspManager.WatchedFile(java.nio.file.Path.of("/proj/pom.xml"), LspManager.WatchedKind.CREATED),
                new LspManager.WatchedFile(java.nio.file.Path.of("/other/B.java"), LspManager.WatchedKind.DELETED),
                // /proj2 must NOT be contained by /proj (component containment, not string prefix)
                new LspManager.WatchedFile(java.nio.file.Path.of("/proj2/C.java"), LspManager.WatchedKind.CHANGED));
        var events = LspManager.eventsUnderRoot(changes, java.nio.file.Path.of("/proj"));
        assertEquals(2, events.size());
        assertEquals(org.eclipse.lsp4j.FileChangeType.Changed, events.get(0).getType());
        assertTrue(events.get(0).getUri().endsWith("A.java"));
        assertEquals(org.eclipse.lsp4j.FileChangeType.Created, events.get(1).getType());
    }

    // --- Rename (#676) -------------------------------------------------------------------------

    @Test
    void renameProviderDetectedFromEitherForm() {
        assertFalse(LspManager.renameProvider(null));
        assertFalse(LspManager.renameProvider(new ServerCapabilities()));
        ServerCapabilities bool = new ServerCapabilities();
        bool.setRenameProvider(true);
        assertTrue(LspManager.renameProvider(bool));
        ServerCapabilities opts = new ServerCapabilities();
        opts.setRenameProvider(new org.eclipse.lsp4j.RenameOptions());
        assertTrue(LspManager.renameProvider(opts));
    }

    @Test
    void mapPrepareHandlesAllThreeShapesAndRefusal() {
        assertFalse(LspManager.mapPrepare(null).allowed(), "null response = rename not valid here");
        var range =
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 4), new org.eclipse.lsp4j.Position(1, 9));
        var fromRange = LspManager.mapPrepare(org.eclipse.lsp4j.jsonrpc.messages.Either3.forFirst(range));
        assertTrue(fromRange.allowed());
        assertEquals(4, fromRange.startCol());
        var prep = new org.eclipse.lsp4j.PrepareRenameResult(range, "oldName");
        var fromPrep = LspManager.mapPrepare(org.eclipse.lsp4j.jsonrpc.messages.Either3.forSecond(prep));
        assertTrue(fromPrep.allowed());
        assertEquals("oldName", fromPrep.placeholder());
        var fromDefault = LspManager.mapPrepare(org.eclipse.lsp4j.jsonrpc.messages.Either3.forThird(
                new org.eclipse.lsp4j.PrepareRenameDefaultBehavior(true)));
        assertTrue(fromDefault.allowed());
        assertEquals("", fromDefault.placeholder());
    }

    // --- Document highlight (#675) -------------------------------------------------------------

    @Test
    void documentHighlightProviderDetectedFromEitherForm() {
        assertFalse(LspManager.documentHighlightProvider(null));
        assertFalse(LspManager.documentHighlightProvider(new ServerCapabilities()));
        ServerCapabilities bool = new ServerCapabilities();
        bool.setDocumentHighlightProvider(true);
        assertTrue(LspManager.documentHighlightProvider(bool));
        ServerCapabilities off = new ServerCapabilities();
        off.setDocumentHighlightProvider(false);
        assertFalse(LspManager.documentHighlightProvider(off));
        ServerCapabilities opts = new ServerCapabilities();
        opts.setDocumentHighlightProvider(new org.eclipse.lsp4j.DocumentHighlightOptions());
        assertTrue(LspManager.documentHighlightProvider(opts));
    }

    // --- Code actions (#670) -------------------------------------------------------------------

    @Test
    void codeActionProviderDetectedFromEitherForm() {
        assertFalse(LspManager.codeActionProvider(null));
        assertFalse(LspManager.codeActionProvider(new ServerCapabilities())); // unset
        ServerCapabilities bool = new ServerCapabilities();
        bool.setCodeActionProvider(true);
        assertTrue(LspManager.codeActionProvider(bool));
        ServerCapabilities off = new ServerCapabilities();
        off.setCodeActionProvider(false);
        assertFalse(LspManager.codeActionProvider(off));
        ServerCapabilities opts = new ServerCapabilities();
        opts.setCodeActionProvider(new org.eclipse.lsp4j.CodeActionOptions()); // options form → supported
        assertTrue(LspManager.codeActionProvider(opts));
    }

    @Test
    void diagnosticsOverlappingFiltersToTheRequestedRange() {
        var inRange = new org.eclipse.lsp4j.Diagnostic(range(2, 0, 2, 10), "on the requested line");
        var touchingEnd =
                new org.eclipse.lsp4j.Diagnostic(range(0, 0, 2, 0), "multi-line, ends where the range starts");
        var before = new org.eclipse.lsp4j.Diagnostic(range(0, 0, 1, 5), "entirely above");
        var after = new org.eclipse.lsp4j.Diagnostic(range(5, 0, 6, 0), "entirely below");
        var hits = LspManager.diagnosticsOverlapping(
                java.util.List.of(inRange, touchingEnd, before, after), range(2, 0, 3, 0));
        assertEquals(java.util.List.of(inRange, touchingEnd), hits);
    }

    private static org.eclipse.lsp4j.Range range(int sl, int sc, int el, int ec) {
        return new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(sl, sc), new org.eclipse.lsp4j.Position(el, ec));
    }

    // --- jdt:// class-file navigation (#665) ---------------------------------------------------

    @Test
    void classFileTitleIsTheLastSegmentWithoutTheQuery() {
        assertEquals(
                "String.class",
                LspManager.classFileTitle("jdt://contents/java.base/java.lang/String.class?%3Ddemo%2F%5C%2F"
                        + "modules%5C%2Fjava.base%3D%2Fjavadoc_location%3D"));
        assertEquals("List.class", LspManager.classFileTitle("jdt://contents/rt.jar/java.util/List.class"));
        assertEquals("", LspManager.classFileTitle(null));
        // No path segment at all → fall back to the whole URI rather than a blank tab title.
        assertEquals("weird", LspManager.classFileTitle("weird"));
    }

    @Test
    void rawStringResultAcceptsStringAndGsonStringPrimitiveOnly() {
        assertEquals("src", LspManager.rawStringResult("src"));
        assertEquals("src", LspManager.rawStringResult(new com.google.gson.JsonPrimitive("src")));
        assertNull(LspManager.rawStringResult(new com.google.gson.JsonPrimitive(42)));
        assertNull(LspManager.rawStringResult(null));
        assertNull(LspManager.rawStringResult(List.of()));
    }

    // --- jdtls -data workspace claims (#668) ---------------------------------------------------

    @Test
    void workspaceCandidateSuffixesFromTheSecondAttempt() {
        assertEquals("abc123", LspManager.workspaceCandidate("abc123", 1));
        assertEquals("abc123-2", LspManager.workspaceCandidate("abc123", 2));
        assertEquals("abc123-3", LspManager.workspaceCandidate("abc123", 3));
    }

    /** Two live claims of the same root (two windows) must get DIFFERENT dirs — sharing one Eclipse
     *  workspace wedges the second jdtls on its {@code .metadata/.lock} (#668). */
    @Test
    void aSecondClaimOfTheSameRootGetsASuffixedDirAndReleaseRecycles() {
        String base = "test-claim-" + System.nanoTime(); // unique so a parallel test can't collide
        String first = LspManager.claimJdtlsWorkspaceName(base);
        String second = LspManager.claimJdtlsWorkspaceName(base);
        try {
            assertEquals(base, first, "first claimant keeps the canonical dir (persisted index reuse)");
            assertEquals(base + "-2", second, "second claimant is suffixed, never shared");
        } finally {
            LspManager.releaseJdtlsWorkspaceName(first);
            LspManager.releaseJdtlsWorkspaceName(second);
        }
        // Released → the canonical dir is claimable again (a later window reuses the index).
        String again = LspManager.claimJdtlsWorkspaceName(base);
        try {
            assertEquals(base, again);
        } finally {
            LspManager.releaseJdtlsWorkspaceName(again);
        }
    }
}
