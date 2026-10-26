package com.editora.lsp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LspManager}'s pure capability helpers (trigger chars, semantic-tokens gate,
 *  jdtls initializationOptions). */
class LspManagerTest {

    @TempDir
    java.nio.file.Path tempDir;

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

    private java.nio.file.Path typeScriptSdkIn(java.nio.file.Path dir, String marker) throws Exception {
        var sdk = dir.resolve("node_modules/typescript/lib");
        java.nio.file.Files.createDirectories(sdk);
        java.nio.file.Files.createFile(sdk.resolve(marker));
        return sdk;
    }

    @SuppressWarnings("unchecked")
    @Test
    void astroInitOptionsCarryTheChosenTypeScriptSdk() throws Exception {
        var sdk = typeScriptSdkIn(tempDir, "typescript.js");

        var options = (Map<String, Object>) LspManager.initOptionsFor("astro", List.of(), sdk, false);
        var typescript = (Map<String, Object>) options.get("typescript");

        assertEquals(sdk.toString(), typescript.get("tsdk"));
        assertNull(LspManager.initOptionsFor("astro", List.of()), "no SDK, no options: the server is not started");
    }

    /** astro-ls runs the JavaScript in the SDK directory, so an untrusted folder must not supply it. */
    @Test
    void anUntrustedFolderDoesNotSupplyTheAstroTypeScriptSdk() throws Exception {
        typeScriptSdkIn(tempDir, "typescript.js");

        assertNull(LspManager.astroTypeScriptSdk(tempDir, null, List.of()));
        assertNull(LspManager.astroTypeScriptSdk(tempDir.resolve("packages/site"), null, List.of()));
    }

    @Test
    void aTrustedFolderSuppliesItsOwnAstroTypeScriptSdk() throws Exception {
        var sdk = typeScriptSdkIn(tempDir, "typescript.js");

        assertEquals(sdk, LspManager.astroTypeScriptSdk(tempDir, tempDir, List.of()));
    }

    @Test
    void astroSdkDiscoveryWalksUpForHoistedTypeScriptInsideTheTrustedFolder() throws Exception {
        var sdk = typeScriptSdkIn(tempDir, "tsserverlibrary.js");

        assertEquals(sdk, LspManager.astroTypeScriptSdk(tempDir.resolve("packages/site"), tempDir, List.of()));
    }

    /** Only {@code packages} is trusted: the walk must not pick up the SDK of the untrusted folder above it. */
    @Test
    void astroSdkDiscoveryStopsAtTheTrustedFolder() throws Exception {
        typeScriptSdkIn(tempDir, "typescript.js");
        var site = tempDir.resolve("packages/site");

        assertEquals(
                site.resolve("node_modules/typescript/lib"),
                LspManager.astroTypeScriptSdk(site, tempDir.resolve("packages"), List.of()));
        assertNull(LspManager.findTypeScriptSdk(site, tempDir.resolve("packages")));
    }

    @Test
    void trustedCeilingIsTheTopmostTrustedAncestor() {
        var trusted = tempDir.resolve("work");
        var root = trusted.resolve("packages/site");

        assertEquals(trusted, LspManager.trustedCeiling(root, p -> p.startsWith(trusted)));
        assertNull(LspManager.trustedCeiling(root, p -> false));
        assertNull(LspManager.trustedCeiling(null, p -> true));
    }

    /** The SDK beside the installed server is the user's own, so it serves trusted and untrusted folders. */
    @Test
    void astroSdkDiscoveryFallsBackBesideGlobalServer() throws Exception {
        var server = tempDir.resolve("lib/node_modules/@astrojs/language-server/bin/nodeServer.js");
        java.nio.file.Files.createDirectories(server.getParent());
        java.nio.file.Files.createFile(server);
        var sdk = typeScriptSdkIn(tempDir.resolve("lib"), "typescript.js");
        var project = tempDir.resolve("project");
        typeScriptSdkIn(project, "typescript.js");

        assertEquals(sdk.toRealPath(), LspManager.astroTypeScriptSdk(project, null, List.of(server.toString())));
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

    // --- $/progress (#683) ---------------------------------------------------------------------

    @Test
    void progressTextComposesTitleMessageAndPercentage() {
        assertEquals("Importing projects", LanguageServerSession.progressText("Importing projects", null, null));
        assertEquals(
                "Importing projects — 3/10 (30%)",
                LanguageServerSession.progressText("Importing projects", "3/10", 30));
        assertEquals("Build (75%)", LanguageServerSession.progressText("Build", "  ", 75));
        // Pyright begins each analysis with an empty title: nothing to echo, so nothing is left behind (B11).
        assertNull(LanguageServerSession.progressText(null, null, null), "nothing to say: no '…' left in the bar");
        assertNull(LanguageServerSession.progressText("", "  ", 40));
        assertEquals("1 file to analyze", LanguageServerSession.progressText("", "1 file to analyze", null));
    }

    // --- Semantic-tokens delta (#679) ----------------------------------------------------------

    @Test
    void fullDeltaSupportDetectedOnlyFromTheOptionsFormWithDeltaTrue() {
        assertFalse(LspManager.fullDeltaSupported(null));
        var boolFull = stProvider(true, false, true); // full=true (boolean form) — no delta
        assertFalse(LspManager.fullDeltaSupported(boolFull));
        var opts = new SemanticTokensWithRegistrationOptions();
        opts.setLegend(new SemanticTokensLegend(List.of("keyword"), List.of()));
        var fullOpts = new org.eclipse.lsp4j.SemanticTokensServerFull(true); // delta=true
        opts.setFull(fullOpts);
        assertTrue(LspManager.fullDeltaSupported(opts));
        var noDelta = new SemanticTokensWithRegistrationOptions();
        noDelta.setFull(new org.eclipse.lsp4j.SemanticTokensServerFull(false));
        assertFalse(LspManager.fullDeltaSupported(noDelta));
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

    // --- Inlay hints (#681) --------------------------------------------------------------------

    @Test
    void inlayHintProviderDetectedFromEitherForm() {
        assertFalse(LspManager.inlayHintProvider(null));
        assertFalse(LspManager.inlayHintProvider(new ServerCapabilities()));
        ServerCapabilities bool = new ServerCapabilities();
        bool.setInlayHintProvider(true);
        assertTrue(LspManager.inlayHintProvider(bool));
        ServerCapabilities opts = new ServerCapabilities();
        opts.setInlayHintProvider(new org.eclipse.lsp4j.InlayHintRegistrationOptions());
        assertTrue(LspManager.inlayHintProvider(opts));
    }

    @Test
    void inlayLabelJoinsPartsAndStrips() {
        var plain = new org.eclipse.lsp4j.InlayHint(
                new org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(" x: "));
        assertEquals("x:", LspManager.inlayLabel(plain));
        var parts = new org.eclipse.lsp4j.InlayHint(
                new org.eclipse.lsp4j.Position(0, 0),
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(List.of(
                        new org.eclipse.lsp4j.InlayHintLabelPart("a"),
                        new org.eclipse.lsp4j.InlayHintLabelPart(": int"))));
        assertEquals("a: int", LspManager.inlayLabel(parts));
    }

    // --- Call / type hierarchy (#682) ----------------------------------------------------------

    @Test
    void hierarchyProvidersDetectedFromEitherForm() {
        assertFalse(LspManager.callHierarchyProvider(null));
        assertFalse(LspManager.typeHierarchyProvider(new ServerCapabilities()));
        ServerCapabilities calls = new ServerCapabilities();
        calls.setCallHierarchyProvider(true);
        assertTrue(LspManager.callHierarchyProvider(calls));
        ServerCapabilities types = new ServerCapabilities();
        types.setTypeHierarchyProvider(new org.eclipse.lsp4j.TypeHierarchyRegistrationOptions());
        assertTrue(LspManager.typeHierarchyProvider(types));
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

    // --- java-debug capability (#711) ------------------------------------------------------------

    @Test
    void providesJavaDebugWhenTheServerAdvertisesTheStartDebugSessionCommand() {
        // A jdtls that ships the java-debug plugin (e.g. Homebrew's) lists its commands here — that's the
        // only signal available when there is no locally-located plugin jar to inject.
        ServerCapabilities caps = new ServerCapabilities();
        caps.setExecuteCommandProvider(new org.eclipse.lsp4j.ExecuteCommandOptions(List.of(
                "java.project.refreshDiagnostics", LspManager.JAVA_DEBUG_COMMAND, "java.edit.organizeImports")));
        assertTrue(LspManager.providesJavaDebug(caps));
    }

    @Test
    void doesNotProvideJavaDebugWithoutThatCommand() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setExecuteCommandProvider(
                new org.eclipse.lsp4j.ExecuteCommandOptions(List.of("java.edit.organizeImports")));
        assertFalse(LspManager.providesJavaDebug(caps));
    }

    @Test
    void providesJavaDebugIsNullSafe() {
        assertFalse(LspManager.providesJavaDebug(null)); // no capabilities yet (server still initializing)
        assertFalse(LspManager.providesJavaDebug(new ServerCapabilities())); // no executeCommandProvider
        ServerCapabilities nullCommands = new ServerCapabilities();
        nullCommands.setExecuteCommandProvider(new org.eclipse.lsp4j.ExecuteCommandOptions());
        assertFalse(LspManager.providesJavaDebug(nullCommands)); // provider present, command list null
    }

    // --- on-type formatting triggers (#740) ---------------------------------------------------------

    /**
     * The spec splits the trigger characters into a mandatory {@code firstTriggerCharacter} and an optional
     * {@code moreTriggerCharacter} list. Reading only the first one — the easy mistake — would leave
     * {@code }} and Enter unhandled on jdtls, which lists exactly those two as "more".
     */
    @Test
    void onTypeTriggersCombinesTheFirstAndTheMoreCharacters() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setDocumentOnTypeFormattingProvider(
                new org.eclipse.lsp4j.DocumentOnTypeFormattingOptions(";", List.of("\n", "}")));

        assertEquals(java.util.Set.of(';', '\n', '}'), LspManager.onTypeTriggersOf(caps));
    }

    @Test
    void onTypeTriggersHandlesAProviderWithNoExtraCharacters() {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setDocumentOnTypeFormattingProvider(new org.eclipse.lsp4j.DocumentOnTypeFormattingOptions(";"));

        assertEquals(java.util.Set.of(';'), LspManager.onTypeTriggersOf(caps));
    }

    /** No provider means the feature stays inert for that server — an empty set, never a null or a throw. */
    @Test
    void onTypeTriggersIsEmptyWithoutAProvider() {
        assertTrue(LspManager.onTypeTriggersOf(null).isEmpty());
        assertTrue(LspManager.onTypeTriggersOf(new ServerCapabilities()).isEmpty());
    }

    @Test
    void onTypeTriggersIgnoresBlankEntries() {
        ServerCapabilities caps = new ServerCapabilities();
        var options = new org.eclipse.lsp4j.DocumentOnTypeFormattingOptions("");
        options.setMoreTriggerCharacter(java.util.Arrays.asList(null, "", "}"));
        caps.setDocumentOnTypeFormattingProvider(options);

        assertEquals(java.util.Set.of('}'), LspManager.onTypeTriggersOf(caps));
    }

    // --- jdtls extendedClientCapabilities (#741) ----------------------------------------------------

    /**
     * <b>The invariant that keeps this feature from regressing.</b> Declaring a {@code *PromptSupport} flag
     * changes that action's reply from a directly-appliable edit into a {@code java.action.*Prompt} command —
     * so a flag without a picker behind it doesn't leave the action as it was, it makes it appear and then do
     * nothing. Measured on jdtls 1.60: without any flags the accessor actions work; with
     * {@code advancedGenerateAccessorsSupport} they become prompt commands.
     */
    @Test
    void everyDeclaredPromptFlagHasAPickerBehindIt() {
        var caps = LspManager.javaExtendedClientCapabilities();

        for (String key : caps.keySet()) {
            if (!key.endsWith("PromptSupport")) {
                continue;
            }
            // "generateToStringPromptSupport" -> "java.action.generateToStringPrompt"
            String command = "java.action." + key.substring(0, key.length() - "Support".length());
            assertNotNull(
                    JdtlsGenerate.forCommand(command), key + " is declared but JdtlsGenerate cannot drive " + command);
        }
    }

    /** The three flags that need no client UI — each backed by something Editora already implements. */
    @Test
    void theNoUiCapabilitiesAreDeclared() {
        var caps = LspManager.javaExtendedClientCapabilities();

        assertEquals(Boolean.TRUE, caps.get("classFileContentsSupport"), "we open jdt:// source (#665)");
        assertEquals(Boolean.TRUE, caps.get("resolveAdditionalTextEditsSupport"), "we resolve on accept (#410)");
        assertEquals(Boolean.TRUE, caps.get("progressReportProvider"), "we show $/progress (#683)");
    }

    /**
     * The flags that turn a working action into a client-side command are declared together with the code
     * that carries the command out: the accessor and delegate prompts in {@link JdtlsGenerate}, and
     * {@code java.action.applyRefactoringCommand} in {@link JdtlsRefactor}.
     */
    @Test
    void theClientDrivenRefactoringsAreDeclaredWithTheirDrivers() {
        var caps = LspManager.javaExtendedClientCapabilities();

        assertEquals(Boolean.TRUE, caps.get("advancedGenerateAccessorsSupport"));
        assertNotNull(JdtlsGenerate.forCommand("java.action.generateAccessorsPrompt"));
        assertEquals(Boolean.TRUE, caps.get("moveRefactoringSupport"));
        assertEquals(Boolean.TRUE, caps.get("extractInterfaceSupport"));
        assertEquals(
                Boolean.TRUE,
                caps.get("advancedExtractRefactoringSupport"),
                "jdtls offers Extract Interface only with both flags");
    }

    /**
     * Extract at a bare caret (the expression picker over {@code java/inferSelection}), Introduce Parameter
     * through {@code java/getRefactorEdit}, and the move confirmation are driven by {@code LspCoordinator}
     * (Java review D7, D11).
     */
    @Test
    void theInferredSelectionAndMoveConfirmationFlowsAreDeclared() {
        var caps = LspManager.javaExtendedClientCapabilities();

        assertEquals(List.of("extractMethod", "extractVariable", "extractField"), caps.get("inferSelectionSupport"));
        assertEquals(Boolean.TRUE, caps.get("advancedIntroduceParameterRefactoringSupport"));
        assertEquals(Boolean.TRUE, caps.get("moveRefactoringConfirmationSupport"));
    }

    /**
     * The import chooser answers jdtls's {@code chooseImports} client command (A3), which resolving "Add all missing
     * imports" also asks once {@code advancedOrganizeImportsSupport} is declared (fourth review D6; checked live by
     * {@code JdtlsClientSettingsProbeTest}).
     */
    @Test
    void theImportChooserFlowIsDeclared() {
        var caps = LspManager.javaExtendedClientCapabilities();

        assertEquals(Boolean.TRUE, caps.get("advancedOrganizeImportsSupport"), "Add all missing imports asks (D6)");
        assertEquals(Boolean.TRUE, caps.get("executeClientCommandSupport"), "the import chooser answers it (A3)");
    }

    /**
     * jdtls reads its preferences by dotted key ({@code java.signatureHelp.description.enabled}), walking the
     * nested maps. A value placed one level too high resolves to no key at all, which is how the signature
     * Javadoc stayed off (Java review A2) — so these are checked by their flattened names, in both channels.
     */
    @SuppressWarnings("unchecked")
    @Test
    void theJavaPreferencesReachJdtlsUnderTheirReadKeys() {
        Map<String, Object> init = (Map<String, Object>)
                ((Map<String, Object>) LspManager.javaInitOptions(List.of()).get("settings")).get("java");
        Map<String, Object> pushed =
                (Map<String, Object>) LspServerSettings.push("java", false).get("java");
        for (Map<String, Object> java : List.of(init, pushed)) {
            Map<String, Object> keys = flatten("java", java);
            assertEquals(Boolean.TRUE, keys.get("java.signatureHelp.enabled"));
            assertEquals(Boolean.TRUE, keys.get("java.signatureHelp.description.enabled"));
            assertEquals(LspServerSettings.FILTERED_TYPES, keys.get("java.completion.filteredTypes"));
            assertEquals("all", keys.get("java.inlayHints.parameterNames.enabled"));
            assertEquals(Boolean.TRUE, keys.get("java.inlayHints.parameterNames.suppressWhenSameNameNumbered"));
            assertEquals(Boolean.TRUE, keys.get("java.inlayHints.variableTypes.enabled"));
            assertEquals(Boolean.TRUE, keys.get("java.inlayHints.parameterTypes.enabled"));
            assertEquals(Boolean.TRUE, keys.get("java.symbols.includeSourceMethodDeclarations"));
            assertEquals(Boolean.TRUE, keys.get("java.edit.smartSemicolonDetection.enabled"));
            assertEquals(Boolean.FALSE, keys.get("java.format.onType.enabled"));
            assertEquals(Boolean.TRUE, keys.get("java.referencesCodeLens.enabled"));
            assertEquals("all", keys.get("java.implementationCodeLens"));
            assertEquals("automatic", keys.get("java.configuration.updateBuildConfiguration"), "Java review C1");
            assertEquals(LspServerSettings.FAVORITE_STATICS, keys.get("java.completion.favoriteStaticMembers"));
            assertEquals("automatic", keys.get("java.compile.nullAnalysis.mode"));
            assertEquals(Boolean.TRUE, keys.get("java.completion.chain.enabled"));
            assertEquals(LspServerSettings.NULLABLE, keys.get("java.compile.nullAnalysis.nullable"));
            assertEquals(LspServerSettings.NONNULL, keys.get("java.compile.nullAnalysis.nonnull"));
            assertEquals(LspServerSettings.NONNULL_BY_DEFAULT, keys.get("java.compile.nullAnalysis.nonnullbydefault"));
        }
        assertEquals(Boolean.FALSE, flatten("java", init).get("java.autobuild.enabled"), "init keeps autobuild off");
    }

    /** Gradle runs on the projects' default JDK, the user's (Java review C4), not on what jdtls runs on. */
    @SuppressWarnings("unchecked")
    @Test
    void gradleRunsOnTheDefaultRuntime() {
        var jdks = List.of(new JavaRuntimes.Jdk(27, "/jdk27"), new JavaRuntimes.Jdk(25, "/jdk25"));
        Map<String, Object> java = (Map<String, Object>)
                ((Map<String, Object>) LspManager.javaInitOptions(List.of(), false, jdks, "/jdk25")
                                .get("settings"))
                        .get("java");
        Map<String, Object> keys = flatten("java", java);
        assertEquals("/jdk25", keys.get("java.import.gradle.java.home"));
        assertEquals("automatic", keys.get("java.configuration.updateBuildConfiguration"), "kept beside the runtimes");
        List<Map<String, Object>> runtimes = (List<Map<String, Object>>) keys.get("java.configuration.runtimes");
        assertEquals("/jdk25", JavaRuntimes.defaultPath(runtimes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(String prefix, Map<String, Object> map) {
        Map<String, Object> out = new java.util.HashMap<>();
        map.forEach((k, v) -> {
            if (v instanceof Map<?, ?> nested) {
                out.putAll(flatten(prefix + "." + k, (Map<String, Object>) nested));
            } else {
                out.put(prefix + "." + k, v);
            }
        });
        return out;
    }

    /** The options object must still carry what it did before — the flags are additive to it. */
    @Test
    void initOptionsKeepAutobuildOffAndTheDebugBundles() {
        var withBundle = LspManager.javaInitOptions(List.of("/tmp/java-debug.jar"));

        assertNotNull(withBundle.get("settings"));
        assertNotNull(withBundle.get("extendedClientCapabilities"));
        assertEquals(List.of("/tmp/java-debug.jar"), withBundle.get("bundles"));
        assertFalse(LspManager.javaInitOptions(List.of()).containsKey("bundles"), "no bundles when not debugging");
    }

    // --- code lenses ---------------------------------------------------------------------------------

    private static org.eclipse.lsp4j.CodeLens lens(int line, int col, String title, String command) {
        var range = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(line, col), new org.eclipse.lsp4j.Position(line, col + 3));
        return new org.eclipse.lsp4j.CodeLens(
                range, command == null ? null : new org.eclipse.lsp4j.Command(title, command), null);
    }

    @Test
    void aLensIsClassifiedByItsCommand() {
        assertEquals(
                LspManager.CodeLensKind.REFERENCES,
                LspManager.codeLensKind(new org.eclipse.lsp4j.Command("2 references", "java.show.references")));
        assertEquals(
                LspManager.CodeLensKind.IMPLEMENTATIONS,
                LspManager.codeLensKind(
                        new org.eclipse.lsp4j.Command("1 implementation", "java.show.implementations")));
        assertEquals(
                LspManager.CodeLensKind.REFERENCES,
                LspManager.codeLensKind(new org.eclipse.lsp4j.Command("3 references", "editor.action.showReferences")));
        assertNull(
                LspManager.codeLensKind(new org.eclipse.lsp4j.Command("Run", "rust-analyzer.runSingle")),
                "a lens that runs something is the server's own editor integration");
        assertNull(LspManager.codeLensKind(null));
    }

    @Test
    void onlyCountingLensesBecomeSpansInLineOrder() {
        var spans = LspManager.codeLensSpans(java.util.Arrays.asList(
                lens(9, 4, "1 implementation", "java.show.implementations"),
                lens(9, 4, "2 references", "java.show.references"),
                lens(3, 13, " 5 references ", "java.show.references"),
                lens(4, 0, "Run", "rust-analyzer.runSingle"),
                lens(6, 0, "0 implementations", "java.show.implementations"),
                lens(7, 0, "0 references", "java.show.references"),
                lens(5, 0, "unresolved", null),
                null));

        assertEquals(
                List.of(
                        new LspManager.CodeLensSpan(3, 13, "5 references", LspManager.CodeLensKind.REFERENCES),
                        new LspManager.CodeLensSpan(7, 0, "0 references", LspManager.CodeLensKind.REFERENCES),
                        new LspManager.CodeLensSpan(9, 4, "2 references", LspManager.CodeLensKind.REFERENCES),
                        new LspManager.CodeLensSpan(9, 4, "1 implementation", LspManager.CodeLensKind.IMPLEMENTATIONS)),
                spans);
        assertTrue(LspManager.codeLensSpans(null).isEmpty());
    }

    /**
     * Writing a new file is a create then a modify inside one coalescing window. Overwriting the first with the
     * second told Pyright "changed" about a file it never knew, so a new module stayed unresolved (Python
     * review B6).
     */
    @Test
    void watchedEventsForOnePathMergeInsteadOfOverwriting() {
        var created = LspManager.WatchedKind.CREATED;
        var changed = LspManager.WatchedKind.CHANGED;
        var deleted = LspManager.WatchedKind.DELETED;
        assertEquals(created, LspManager.mergeWatched(created, changed));
        assertEquals(created, LspManager.mergeWatched(null, created));
        org.junit.jupiter.api.Assertions.assertNull(LspManager.mergeWatched(created, deleted), "never existed");
        assertEquals(changed, LspManager.mergeWatched(deleted, created), "an atomic save");
        assertEquals(deleted, LspManager.mergeWatched(changed, deleted));
        assertEquals(changed, LspManager.mergeWatched(changed, changed));
    }

    /**
     * For the Maven pom.xml server "the executable resolves" is only "java is there": the folder its
     * {@code -cp "<dir>/*"} names must hold every jar too (Maven review C3). Other servers keep the plain check.
     */
    @Test
    void theMavenServerIsAvailableOnlyWithACompleteInstallFolder(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path javaExe = dir.resolve("java");
        java.nio.file.Files.writeString(javaExe, "#!/bin/sh\nexit 0\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(javaExe.toFile().setExecutable(true), "needs an executable bit");
        java.nio.file.Path maven = java.nio.file.Files.createDirectories(dir.resolve("maven"));
        java.util.List<String> argv =
                com.editora.run.ProgramArgs.commandLine(com.editora.install.InstallCatalog.jvmClasspathCommand(
                        maven, com.editora.install.InstallCatalog.LEMMINX_MAIN_CLASS));
        argv = new java.util.ArrayList<>(argv);
        argv.set(0, javaExe.toString());

        assertTrue(LspManager.available(argv), "the plain check: java is there");
        assertFalse(
                LspManager.available(LspServerRegistry.MAVEN_POM_SERVER_ID, argv), "an empty folder is not an install");
        assertTrue(LspManager.available("xml", argv), "another server with the same shape keeps the plain check");

        for (String name : java.util.List.of(
                "org.eclipse.lemminx-0.30.1.jar",
                "lemminx-maven-0.12.0.jar",
                "guava-33.7.2-jre.jar",
                "commons-lang3-3.21.0.jar")) {
            java.nio.file.Files.writeString(maven.resolve(name), "");
        }
        assertFalse(LspManager.available(LspServerRegistry.MAVEN_POM_SERVER_ID, argv), "jsoup still missing");
        java.nio.file.Files.writeString(maven.resolve("jsoup-1.23.2.jar"), "");
        assertTrue(LspManager.available(LspServerRegistry.MAVEN_POM_SERVER_ID, argv));

        java.util.List<String> script = java.util.List.of(javaExe.toString());
        assertTrue(
                LspManager.available(LspServerRegistry.MAVEN_POM_SERVER_ID, script),
                "a user's own launcher has no folder to check");
        assertFalse(LspManager.available(LspServerRegistry.MAVEN_POM_SERVER_ID, java.util.List.of()));
    }

    /** A bare caret on no squiggle carries the diagnostics of its line, or quick fixes vanish off the token (A5). */
    @Test
    void aCaretOffTheSquiggleStillCarriesItsLinesDiagnostics() {
        var onLine = new org.eclipse.lsp4j.Diagnostic(
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(4, 8), new org.eclipse.lsp4j.Position(4, 23)),
                "Unhandled exception");
        var elsewhere = new org.eclipse.lsp4j.Diagnostic(
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(9, 0), new org.eclipse.lsp4j.Position(9, 3)),
                "Other");
        var caret =
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(4, 0), new org.eclipse.lsp4j.Position(4, 0));
        assertEquals(List.of(onLine), LspManager.contextDiagnostics(List.of(onLine, elsewhere), caret));
        var onToken = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(4, 10), new org.eclipse.lsp4j.Position(4, 10));
        assertEquals(List.of(onLine), LspManager.contextDiagnostics(List.of(onLine, elsewhere), onToken));
        var selection =
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(4, 0), new org.eclipse.lsp4j.Position(4, 2));
        assertEquals(
                List.of(),
                LspManager.contextDiagnostics(List.of(onLine, elsewhere), selection),
                "a selection is exact");
    }

    /** Fixes first, then assists, refactorings, source; duplicates dropped; preferred first (A4). */
    @Test
    void codeActionsAreGroupedByKindAndDeduplicated() {
        var items = List.of(
                new LspManager.CodeActionItem("Generate toString()...", "source.generate.toString", false, "a"),
                new LspManager.CodeActionItem("Extract interface...", "refactor.extract.interface", false, "b"),
                new LspManager.CodeActionItem("Organize imports", "quickassist", false, "c"),
                new LspManager.CodeActionItem("Import 'LocalDate'", "quickfix", false, "d"),
                new LspManager.CodeActionItem("Organize imports", "source.organizeImports", false, "e"),
                new LspManager.CodeActionItem("Surround with try/catch", "quickfix", false, "f"));
        assertEquals(
                List.of(
                        "Import 'LocalDate'",
                        "Surround with try/catch",
                        "Organize imports",
                        "Extract interface...",
                        "Generate toString()..."),
                CodeActionKinds.arrange(items, i -> true).stream()
                        .map(LspManager.CodeActionItem::title)
                        .toList());
        assertEquals(
                List.of("Generate toString()...", "Organize imports"),
                CodeActionKinds.arrange(items, i -> CodeActionKinds.group(i.kind()) == CodeActionKinds.Group.SOURCE)
                        .stream()
                        .map(LspManager.CodeActionItem::title)
                        .toList());
        assertTrue(CodeActionKinds.matches("refactor.extract.function", "refactor.extract"));
        assertFalse(CodeActionKinds.matches("refactor.extracted", "refactor.extract"));
        assertEquals(CodeActionKinds.Group.ASSIST, CodeActionKinds.group(""));
    }

    @Test
    void repeatedCallsToOneMemberAreOneRowWithTheirCount() {
        java.nio.file.Path a = java.nio.file.Path.of("/p/A.java");
        var append = new LspManager.HierarchyNode("append(String)", "StringBuilder", "method", null, 90, 4, "x1");
        var appendAgain =
                new LspManager.HierarchyNode("append(String)", "StringBuilder", "method", null, 90, 4, "x2", 2);
        var report = new LspManager.HierarchyNode("report()", "demo.A", "method", a, 3, 8, "x3");
        var merged = LspManager.mergeCalls(java.util.List.of(append, report, appendAgain));
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("append(String)x3", "report()x1"),
                merged.stream().map(n -> n.name() + "x" + n.calls()).toList(),
                "first-seen order; the call sites add up (Java review B19)");
        org.junit.jupiter.api.Assertions.assertSame(append.raw(), merged.get(0).raw(), "the first item expands");
    }
}
